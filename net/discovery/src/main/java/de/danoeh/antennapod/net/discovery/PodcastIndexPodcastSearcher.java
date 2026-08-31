package de.danoeh.antennapod.net.discovery;

import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import de.danoeh.antennapod.net.common.UserAgentInterceptor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.core.SingleOnSubscribe;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.schedulers.Schedulers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PodcastIndexPodcastSearcher implements PodcastSearcher {
    private static final String SEARCH_API_URL = "https://api.podcastindex.org/api/1.0/search/byterm?q=%s";

    public PodcastIndexPodcastSearcher() {
    }

    @Override
    public Single<List<PodcastSearchResult>> search(String query) {
        return Single.create((SingleOnSubscribe<List<PodcastSearchResult>>) subscriber -> {
            String encodedQuery;
            try {
                encodedQuery = URLEncoder.encode(query, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                // this won't ever be thrown
                encodedQuery = query;
            }
            String formattedUrl = String.format(SEARCH_API_URL, encodedQuery);
            List<PodcastSearchResult> podcasts = new ArrayList<>();
            try {
                OkHttpClient client = AntennapodHttpClient.getHttpClient();
                Response response = client.newCall(PodcastIndexApi.buildAuthenticatedRequest(formattedUrl)).execute();

                if (response.isSuccessful()) {
                    String resultString = response.body().string();
                    JSONObject result = new JSONObject(resultString);
                    JSONArray j = result.getJSONArray("feeds");

                    for (int i = 0; i < j.length(); i++) {
                        JSONObject podcastJson = j.getJSONObject(i);
                        if (!podcastJson.has("url")) {
                            continue;
                        }
                        String title = podcastJson.optString("title", "Unknown");
                        String imageUrl = podcastJson.optString("image", "");
                        String feedUrl = podcastJson.optString("url", "");
                        String author = podcastJson.optString("author", "Unknown");
                        podcasts.add(new PodcastSearchResult(title, imageUrl, feedUrl, author));
                    }
                } else {
                    subscriber.onError(new IOException(response.toString()));
                }
            } catch (IOException | JSONException e) {
                subscriber.onError(e);
            }
            subscriber.onSuccess(podcasts);
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public Single<String> lookupUrl(String url) {
        return Single.just(url);
    }

    @Override
    public boolean urlNeedsLookup(String url) {
        return false;
    }

    @Override
    public String getName() {
        return "Podcast Index";
    }

}
