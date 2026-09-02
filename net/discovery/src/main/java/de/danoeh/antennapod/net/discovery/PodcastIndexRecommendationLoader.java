package de.danoeh.antennapod.net.discovery;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import de.danoeh.antennapod.storage.preferences.ProfileManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * Fork Balado : suggestions personnalisées, dérivées des podcasts les plus écoutés.
 *
 * <p>Pipeline, dans cet ordre :
 * <ol>
 *   <li>Termes distinctifs du flux source par TF-IDF sur la bibliothèque de l'utilisateur
 *       (voir {@link SuggestionTerms}).</li>
 *   <li><b>Une requête {@code search/byterm} par terme, jamais plusieurs ensemble.</b> Vérifié
 *       contre l'API : {@code q} est traité en ET et la correspondance porte sur le titre —
 *       « lapin » renvoie 40 résultats, « lapin soir » en renvoie <b>0</b>.</li>
 *   <li>Filtre par catégorie partagée avec le flux source, puis classement par recouvrement
 *       de termes (voir {@link SuggestionCandidate}).</li>
 * </ol>
 *
 * <p><b>La catégorie sert à écarter, jamais à trouver.</b> C'était l'erreur de la version
 * précédente de cette classe : elle <i>cherchait</i> par catégorie via {@code trending?cat=},
 * qui classe par popularité mondiale — d'où des suggestions génériques et hors sujet, sans
 * rapport avec un thème précis comme la jeunesse. Ne pas y revenir.
 *
 * <p>Résultat mis en cache 24 h par profil, invalidé si les podcasts sources changent.
 */
public class PodcastIndexRecommendationLoader {
    private static final String TAG = "PodcastIndexRecommend";
    private static final String BYFEEDURL_URL =
            "https://api.podcastindex.org/api/1.0/podcasts/byfeedurl?url=%s";
    private static final String BYTERM_URL =
            "https://api.podcastindex.org/api/1.0/search/byterm?q=%s&max=40";
    private static final String PREFS_NAME = "RecommendationsCache";
    // v3 : le cache précédent contient les résultats de l'approche par catégorie, et sa TTL de
    // 24 h les servirait encore après la mise à jour. Le renommer force un recalcul.
    private static final String PREF_TIMESTAMP = "timestamp_v3";
    private static final String PREF_SEEDS_HASH = "seedsHash_v3";
    private static final String PREF_RESULT = "resultJson_v3";
    private static final long CACHE_TTL_MS = 24L * 3600 * 1000;
    /** Trois sources, pas cinq : chacune coûte une requête de catégories plus une par terme,
     *  et ça tourne sur un forfait mobile. */
    private static final int MAX_SEEDS = 3;
    private static final int MAX_TERMS = 6;
    private static final int MAX_QUERIES_PER_SEED = 4;
    private static final int MAX_PER_SEED = 4;
    private static final int MIN_TERMS = 2;

    private final Context context;

    public PodcastIndexRecommendationLoader(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * @param seedFeedUrls URLs de flux, ordonnées de la plus écoutée à la moins écoutée.
     * @param subscribed   abonnements actuels : corpus du TF-IDF, et exclus des résultats.
     */
    @NonNull
    @WorkerThread
    public List<PodcastSearchResult> getRecommendations(@NonNull List<String> seedFeedUrls,
            @NonNull List<Feed> subscribed, int limit) {
        List<String> seeds = seedFeedUrls.subList(0, Math.min(MAX_SEEDS, seedFeedUrls.size()));
        SharedPreferences prefs = context.getSharedPreferences(
                ProfileManager.scopedPrefsName(PREFS_NAME), Context.MODE_PRIVATE);
        int seedsHash = seeds.hashCode();

        if (prefs.getInt(PREF_SEEDS_HASH, 0) == seedsHash
                && System.currentTimeMillis() - prefs.getLong(PREF_TIMESTAMP, 0) < CACHE_TTL_MS) {
            List<PodcastSearchResult> cached = readCache(prefs.getString(PREF_RESULT, "[]"),
                    subscribedUrls(subscribed), limit);
            if (!cached.isEmpty()) {
                return cached;
            }
        }

        List<String> corpus = new ArrayList<>();
        Map<String, String> textByFeedUrl = new LinkedHashMap<>();
        for (Feed feed : subscribed) {
            String text = feedText(feed);
            corpus.add(text);
            if (feed.getDownloadUrl() != null) {
                textByFeedUrl.put(feed.getDownloadUrl(), text);
            }
        }

        Set<String> excluded = subscribedUrls(subscribed);
        Map<String, PodcastSearchResult> results = new LinkedHashMap<>();
        String language = Locale.getDefault().getLanguage();

        for (String seedUrl : seeds) {
            if (results.size() >= limit) {
                break;
            }
            String seedText = textByFeedUrl.get(seedUrl);
            if (seedText == null) {
                continue;
            }
            List<SuggestionTerms.WeightedTerm> terms =
                    SuggestionTerms.extract(seedText, corpus, MAX_TERMS);
            if (terms.size() < MIN_TERMS) {
                continue;
            }
            try {
                Set<String> seedCategories = fetchCategories(seedUrl);
                List<SuggestionCandidate> candidates = fetchCandidates(terms, excluded, results.keySet());
                // Deux termes recoupés d'abord (précision) ; on redescend à un seul plutôt que
                // de perdre la source, ce qui arrive sur un podcast au vocabulaire très niche.
                List<SuggestionCandidate> ranked =
                        SuggestionCandidate.rank(candidates, terms, seedCategories, 2);
                if (ranked.size() < MAX_PER_SEED) {
                    ranked = SuggestionCandidate.rank(candidates, terms, seedCategories, 1);
                }
                ranked = SuggestionCandidate.preferLanguage(ranked, language, MAX_PER_SEED);
                int added = 0;
                for (SuggestionCandidate candidate : ranked) {
                    if (added >= MAX_PER_SEED || results.size() >= limit) {
                        break;
                    }
                    if (results.containsKey(candidate.feedUrl)) {
                        continue;
                    }
                    results.put(candidate.feedUrl, candidate.toResult());
                    added++;
                }
            } catch (IOException | JSONException e) {
                // Réseau ou API en défaut : on garde ce qu'on a déjà et on n'écrit pas de cache,
                // pour réessayer à la prochaine ouverture.
                Log.w(TAG, "Suggestions interrompues pour " + seedUrl, e);
                break;
            }
        }

        List<PodcastSearchResult> list = new ArrayList<>(results.values());
        if (!list.isEmpty()) {
            prefs.edit()
                    .putInt(PREF_SEEDS_HASH, seedsHash)
                    .putLong(PREF_TIMESTAMP, System.currentTimeMillis())
                    .putString(PREF_RESULT, writeCache(list))
                    .apply();
        }
        return list;
    }

    /** Métadonnées du flux : c'est le texte que voit le TF-IDF. */
    private String feedText(Feed feed) {
        return String.valueOf(feed.getTitle()) + ' ' + feed.getAuthor() + ' ' + feed.getDescription();
    }

    private Set<String> subscribedUrls(List<Feed> subscribed) {
        Set<String> urls = new HashSet<>();
        for (Feed feed : subscribed) {
            if (feed.getDownloadUrl() != null) {
                urls.add(feed.getDownloadUrl());
            }
        }
        return urls;
    }

    /** Catégories du flux source — le garde-fou du filtrage, une requête par source. */
    private Set<String> fetchCategories(String feedUrl) throws IOException, JSONException {
        Set<String> categories = new HashSet<>();
        String url = String.format(Locale.ROOT, BYFEEDURL_URL, URLEncoder.encode(feedUrl, "UTF-8"));
        try (Response response = execute(url)) {
            if (!response.isSuccessful()) {
                return categories;
            }
            JSONObject feed = new JSONObject(response.body().string()).optJSONObject("feed");
            if (feed == null) {
                return categories;
            }
            categories.addAll(readCategories(feed));
        }
        return categories;
    }

    private Set<String> readCategories(JSONObject feed) {
        Set<String> categories = new HashSet<>();
        JSONObject json = feed.optJSONObject("categories");
        if (json == null) {
            return categories;
        }
        for (Iterator<String> it = json.keys(); it.hasNext(); ) {
            String name = json.optString(it.next(), "");
            if (!name.isEmpty()) {
                categories.add(name);
            }
        }
        return categories;
    }

    /** Une requête par terme, résultats fusionnés et dédoublonnés par URL de flux. */
    private List<SuggestionCandidate> fetchCandidates(List<SuggestionTerms.WeightedTerm> terms,
            Set<String> excluded, Set<String> alreadyUsed) throws IOException, JSONException {
        Map<String, SuggestionCandidate> byUrl = new LinkedHashMap<>();
        int queries = Math.min(MAX_QUERIES_PER_SEED, terms.size());
        for (int i = 0; i < queries; i++) {
            String url = String.format(Locale.ROOT, BYTERM_URL,
                    URLEncoder.encode(terms.get(i).term, "UTF-8"));
            try (Response response = execute(url)) {
                if (!response.isSuccessful()) {
                    continue;
                }
                JSONArray feeds = new JSONObject(response.body().string()).optJSONArray("feeds");
                if (feeds == null) {
                    continue;
                }
                for (int j = 0; j < feeds.length(); j++) {
                    JSONObject feed = feeds.getJSONObject(j);
                    String feedUrl = feed.optString("url", "");
                    if (feedUrl.isEmpty() || excluded.contains(feedUrl)
                            || alreadyUsed.contains(feedUrl) || byUrl.containsKey(feedUrl)) {
                        continue;
                    }
                    byUrl.put(feedUrl, new SuggestionCandidate(
                            feed.optString("title", "Unknown"),
                            feed.optString("image", ""),
                            feedUrl,
                            feed.optString("author", ""),
                            feed.optString("description", ""),
                            feed.optString("language", ""),
                            readCategories(feed)));
                }
            }
        }
        return new ArrayList<>(byUrl.values());
    }

    private Response execute(String url) throws IOException {
        OkHttpClient client = AntennapodHttpClient.getHttpClient();
        return client.newCall(PodcastIndexApi.buildAuthenticatedRequest(url)).execute();
    }

    private String writeCache(List<PodcastSearchResult> results) {
        JSONArray array = new JSONArray();
        for (PodcastSearchResult result : results) {
            try {
                array.put(new JSONObject()
                        .put("title", result.title)
                        .put("image", result.imageUrl)
                        .put("url", result.feedUrl)
                        .put("author", result.author));
            } catch (JSONException e) {
                Log.w(TAG, "Cache non écrit", e);
            }
        }
        return array.toString();
    }

    private List<PodcastSearchResult> readCache(String json, Set<String> excludeUrls, int limit) {
        List<PodcastSearchResult> results = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length() && results.size() < limit; i++) {
                JSONObject item = array.getJSONObject(i);
                String feedUrl = item.optString("url", "");
                // Un podcast auquel l'utilisateur s'est abonné depuis n'a plus rien à faire ici.
                if (feedUrl.isEmpty() || excludeUrls.contains(feedUrl)) {
                    continue;
                }
                results.add(new PodcastSearchResult(item.optString("title", "Unknown"),
                        item.optString("image", ""), feedUrl, item.optString("author", "")));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Cache illisible, on repart de zéro", e);
        }
        return results;
    }
}
