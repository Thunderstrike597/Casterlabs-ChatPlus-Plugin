package net.kenji.rumblechat.rumble;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chat moderation on Rumble, as the logged-in user (same service.php calls
 * rumble.com's own chat menu makes; unofficial, so this is the file to fix if
 * Rumble changes them).
 *
 * Rumble calls a timeout a "mute": the user can't chat on that video for the
 * given time.
 */
public final class RumbleModeration {
    /** Rumble's own chat menu goes no shorter than this. */
    public static final int MIN_SECONDS = 1;
    /** Upper limit we allow from the dock (24 h; the dock itself only offers up to 1 h). */
    public static final int MAX_SECONDS = 24 * 60 * 60;

    private RumbleModeration() {}

    /**
     * Mutes {@code username} in the chat of video {@code videoId} for {@code seconds}.
     * On Rumble a livestream's chat ID is the video's numeric ID, so the plugin's
     * current chat ID is passed here.
     */
    public static void timeout(long videoId, String username, int seconds, String sessionToken) throws IOException, InterruptedException {
        if (username == null || username.trim().isEmpty()) throw new IOException("No Rumble username to time out.");
        if (videoId <= 0) throw new IOException("Not connected to a live Rumble chat right now.");
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            throw new IOException("Timeouts must be between " + MIN_SECONDS + " second and " + (MAX_SECONDS / 3600) + " hours.");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("user_to_mute", username.trim());
        form.put("entity_type", "user");
        form.put("video", Long.toString(videoId));
        form.put("duration", Integer.toString(seconds));
        form.put("type", "video"); // Only this stream's chat, not every video on the channel.

        RumbleAuth.service("moderation.mute", form, sessionToken);
    }
}
