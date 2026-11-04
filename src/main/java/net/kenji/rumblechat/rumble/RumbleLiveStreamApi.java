package net.kenji.rumblechat.rumble;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.rakurai.json.element.JsonArray;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonObject;

/**
 * Rumble's official "Live Stream API" (rumble.com/account/livestream-api).
 * Each account gets a private URL with a key in it. We only use it to find out
 * which stream is live right now, because every Rumble livestream is a new video
 * with its own chat ID.
 */
public final class RumbleLiveStreamApi {
    private RumbleLiveStreamApi() {}

    /** Human-readable summary of the last API response, for the log. */
    public static volatile String lastSummary = "";

    public static final class LiveInfo {
        public final String streamIdB36;
        public final long chatId;
        public final String title;

        LiveInfo(String streamIdB36, String title) {
            this.streamIdB36 = streamIdB36;
            this.chatId = RumbleIds.base36To10(streamIdB36);
            this.title = title;
        }
    }

    /**
     * @return the first stream currently marked live, or null if nothing is live.
     */
    public static @Nullable LiveInfo findLiveStream(String apiUrl) throws IOException, InterruptedException {
        HttpResponse<String> res = RumbleHttp.CLIENT.send(
            HttpRequest.newBuilder(URI.create(apiUrl.trim()))
                .header("User-Agent", RumbleHttp.USER_AGENT)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        if (res.statusCode() == 401 || res.statusCode() == 403) {
            throw new IOException("Rumble rejected the Live Stream API URL (HTTP " + res.statusCode() + "). Check the key.");
        }
        if (res.statusCode() != 200) {
            throw new IOException("Rumble Live Stream API returned HTTP " + res.statusCode());
        }

        JsonObject root = Json.parseObject(res.body());
        if (root == null) {
            String body = res.body();
            lastSummary = "API reply wasn't JSON: " + body.substring(0, Math.min(200, body.length()));
            return null;
        }
        JsonArray streams = Json.arr(root, "livestreams");
        if (streams == null) {
            lastSummary = "API reply has no 'livestreams' list (account: " + Json.str(root, "username", "?") + ")";
            return null;
        }

        StringBuilder sb = new StringBuilder("API account '" + Json.str(root, "username", "?") + "', streams:");
        LiveInfo found = null;
        int count = 0;
        for (JsonElement e : streams) {
            if (!e.isJsonObject()) continue;
            JsonObject s = e.getAsObject();
            String id = Json.str(s, "id");
            count++;
            sb.append(" [id=").append(id)
                .append(" is_live=").append(Json.str(s, "is_live"))
                .append(" title=").append(Json.str(s, "title", "")).append(']');
            if (found != null || id == null || id.isEmpty()) continue;
            if (!Json.bool(s, "is_live")) continue;
            found = new LiveInfo(id, Json.str(s, "title", ""));
        }
        if (count == 0) sb.append(" none");
        lastSummary = sb.toString();
        return found;
    }
}
