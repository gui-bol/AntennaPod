package de.danoeh.antennapod.net.discovery;

import de.danoeh.antennapod.net.common.UserAgentInterceptor;

import java.security.MessageDigest;
import java.util.Locale;

import okhttp3.Request;

/** Signature des requêtes PodcastIndex (sha1(key+secret+unixtime)), partagée entre les clients. */
final class PodcastIndexApi {

    private PodcastIndexApi() {
    }

    static Request buildAuthenticatedRequest(String url) {
        String apiHeaderTime = String.valueOf(System.currentTimeMillis() / 1000L);
        String data4Hash = BuildConfig.PODCASTINDEX_API_KEY + BuildConfig.PODCASTINDEX_API_SECRET + apiHeaderTime;
        String hashString = sha1(data4Hash);

        return new Request.Builder()
                .addHeader("X-Auth-Date", apiHeaderTime)
                .addHeader("X-Auth-Key", BuildConfig.PODCASTINDEX_API_KEY)
                .addHeader("Authorization", hashString)
                .addHeader("User-Agent", UserAgentInterceptor.USER_AGENT)
                .url(url)
                .build();
    }

    private static String sha1(String clearString) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
            messageDigest.update(clearString.getBytes("UTF-8"));
            StringBuilder buffer = new StringBuilder();
            for (byte b : messageDigest.digest()) {
                buffer.append(String.format(Locale.ROOT, "%02x", b));
            }
            return buffer.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
