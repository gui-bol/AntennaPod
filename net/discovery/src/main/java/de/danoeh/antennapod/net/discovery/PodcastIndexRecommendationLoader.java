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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * Fork Balado : suggestions personnalisées. Dérive les catégories des podcasts les plus
 * écoutés (via PodcastIndex /podcasts/byfeedurl) puis propose les tendances PodcastIndex
 * dans ces catégories (/podcasts/trending), en excluant les abonnements existants.
 * Résultat mis en cache 24 h par profil (et invalidé si les podcasts sources changent).
 */
public class PodcastIndexRecommendationLoader {
    private static final String TAG = "PodcastIndexRecommend";
    private static final String BYFEEDURL_URL = "https://api.podcastindex.org/api/1.0/podcasts/byfeedurl?url=%s";
    private static final String TRENDING_URL =
            "https://api.podcastindex.org/api/1.0/podcasts/trending?max=%d&cat=%s&lang=%s&since=%d";
    private static final String PREFS_NAME = "RecommendationsCache";
    private static final String PREF_TIMESTAMP = "timestamp";
    private static final String PREF_SEEDS_HASH = "seedsHash";
    private static final String PREF_RESULT = "resultJson";
    private static final long CACHE_TTL_MS = 24L * 3600 * 1000;
    private static final int MAX_SEEDS = 5;
    private static final int MAX_CATEGORIES = 3;
    private static final int TRENDING_WINDOW_DAYS = 30;

    private final Context context;

    public PodcastIndexRecommendationLoader(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * @param seedFeedUrls URLs de flux, ordonnées de la plus écoutée à la moins écoutée.
     * @param subscribed   abonnements actuels (exclus des résultats).
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
            List<PodcastSearchResult> cached = parseResults(prefs.getString(PREF_RESULT, "[]"),
                    subscribedUrls(subscribed), limit);
            if (!cached.isEmpty()) {
                return cached;
            }
        }

        try {
            List<String> categories = topCategories(seeds);
            if (categories.isEmpty()) {
                return new ArrayList<>();
            }
            String trendingJson = fetchTrending(categories, limit);
            List<PodcastSearchResult> results = parseResults(trendingJson, subscribedUrls(subscribed), limit);
            if (!results.isEmpty()) {
                prefs.edit()
                        .putInt(PREF_SEEDS_HASH, seedsHash)
                        .putLong(PREF_TIMESTAMP, System.currentTimeMillis())
                        .putString(PREF_RESULT, trendingJson)
                        .apply();
            }
            return results;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "Failed to load recommendations", e);
            return new ArrayList<>();
        }
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

    /** Catégories pondérées par le rang d'écoute des podcasts sources. */
    private List<String> topCategories(List<String> seeds) throws IOException, JSONException {
        Map<String, Integer> weights = new HashMap<>();
        OkHttpClient client = AntennapodHttpClient.getHttpClient();
        for (int i = 0; i < seeds.size(); i++) {
            String url = String.format(Locale.ROOT, BYFEEDURL_URL, URLEncoder.encode(seeds.get(i), "UTF-8"));
            try (Response response = client.newCall(PodcastIndexApi.buildAuthenticatedRequest(url)).execute()) {
                if (!response.isSuccessful()) {
                    continue;
                }
                JSONObject feed = new JSONObject(response.body().string()).optJSONObject("feed");
                if (feed == null) {
                    continue;
                }
                JSONObject categories = feed.optJSONObject("categories");
                if (categories == null) {
                    continue;
                }
                int weight = seeds.size() - i;
                for (java.util.Iterator<String> it = categories.keys(); it.hasNext(); ) {
                    String name = categories.optString(it.next(), "");
                    if (!name.isEmpty()) {
                        weights.merge(name, weight, Integer::sum);
                    }
                }
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(weights.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        List<String> top = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_CATEGORIES, sorted.size()); i++) {
            top.add(sorted.get(i).getKey());
        }
        return top;
    }

    private String fetchTrending(List<String> categories, int limit) throws IOException {
        String langs = Locale.getDefault().getLanguage();
        if (!"en".equals(langs)) {
            langs += ",en";
        }
        long since = System.currentTimeMillis() / 1000L - TRENDING_WINDOW_DAYS * 24L * 3600;
        StringBuilder cats = new StringBuilder();
        for (String category : categories) {
            if (cats.length() > 0) {
                cats.append(',');
            }
            cats.append(URLEncoder.encode(category, "UTF-8"));
        }
        // On demande large : les abonnements existants seront filtrés ensuite.
        String url = String.format(Locale.ROOT, TRENDING_URL, Math.max(40, limit * 3), cats, langs, since);
        OkHttpClient client = AntennapodHttpClient.getHttpClient();
        try (Response response = client.newCall(PodcastIndexApi.buildAuthenticatedRequest(url)).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("trending: " + response);
            }
            return response.body().string();
        }
    }

    private List<PodcastSearchResult> parseResults(String json, Set<String> excludeUrls, int limit) {
        // Les podcasts dans la langue de l'appareil passent en premier.
        List<PodcastSearchResult> preferredLanguage = new ArrayList<>();
        List<PodcastSearchResult> otherLanguages = new ArrayList<>();
        String deviceLanguage = Locale.getDefault().getLanguage();
        try {
            JSONArray feeds = new JSONObject(json).optJSONArray("feeds");
            if (feeds == null) {
                return preferredLanguage;
            }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < feeds.length(); i++) {
                JSONObject feed = feeds.getJSONObject(i);
                String feedUrl = feed.optString("url", "");
                if (feedUrl.isEmpty() || excludeUrls.contains(feedUrl) || !seen.add(feedUrl)) {
                    continue;
                }
                PodcastSearchResult result = new PodcastSearchResult(
                        feed.optString("title", "Unknown"),
                        feed.optString("image", ""),
                        feedUrl,
                        feed.optString("author", ""));
                if (feed.optString("language", "").toLowerCase(Locale.ROOT).startsWith(deviceLanguage)) {
                    preferredLanguage.add(result);
                } else {
                    otherLanguages.add(result);
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse recommendations", e);
        }
        preferredLanguage.addAll(otherLanguages);
        return preferredLanguage.size() > limit ? preferredLanguage.subList(0, limit) : preferredLanguage;
    }
}
