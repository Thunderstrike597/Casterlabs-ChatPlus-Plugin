package net.kenji.rumblechat.rumble;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import co.casterlabs.rakurai.json.element.JsonObject;

/**
 * Posts a chat message to a Rumble livestream as the logged-in user (same
 * request rumble.com's chat box makes; endpoint is unofficial).
 */
public final class RumbleSender {
    private static final String MESSAGE_URL = "https://web7.rumble.com/chat/api/chat/%d/message";
    public static final int MAX_LENGTH = 200;
    private static final long COOLDOWN_MS = 3_000; // Rumble rejects faster sends.

    private static final SecureRandom RANDOM = new SecureRandom();
    private static long lastSend = 0;

    private RumbleSender() {}

    /** Thrown when Rumble says the session is no longer valid. */
    public static class SessionExpiredException extends IOException {
        private static final long serialVersionUID = 1L;

        public SessionExpiredException(String msg) {
            super(msg);
        }
    }

    /** The chat ID we sent to is closed (usually an old stream). */
    public static class ChatClosedException extends IOException {
        private static final long serialVersionUID = 1L;

        public ChatClosedException(String msg) {
            super(msg);
        }
    }

    public static synchronized void send(long chatId, String sessionToken, String text) throws IOException, InterruptedException {
        String msg = text.trim();
        if (msg.isEmpty()) return;
        if (msg.length() > MAX_LENGTH) {
            throw new IOException("Rumble messages can be at most " + MAX_LENGTH + " characters (this one is " + msg.length() + ").");
        }

        long wait = lastSend + COOLDOWN_MS - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);

        String url = String.format(MESSAGE_URL, chatId);
        String body = "{\"data\":{\"request_id\":\"" + requestId() + "\",\"message\":{\"text\":"
            + jsonString(msg) + "},\"rant\":null,\"channel_id\":null}}";

        HttpResponse<String> res = RumbleHttp.CLIENT.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", RumbleHttp.USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Cookie", RumbleAuth.SESSION_COOKIE + "=" + sessionToken)
                .header("Origin", "https://rumble.com")
                .header("Referer", "https://rumble.com/")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        lastSend = System.currentTimeMillis();

        int code = res.statusCode();
        if (code == 401 || code == 403) {
            throw new SessionExpiredException("Rumble rejected the login session (HTTP " + code + ").");
        }
        if (res.body() != null && (res.body().contains("CHAT_NOT_FOUND") || res.body().contains("Chat is disabled"))) {
            throw new ChatClosedException("That Rumble stream's chat is closed.");
        }
        if (code != 200) {
            throw new IOException("Rumble didn't accept the message: HTTP " + code + " " + snippet(res.body()));
        }

        JsonObject json = Json.parseObject(res.body());
        if (Json.size(Json.arr(json, "errors")) > 0 || Json.obj(json, "errors") != null) {
            throw new IOException("Rumble refused the message: " + snippet(res.body()));
        }
    }

    private static String requestId() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String s = Base64.getEncoder().withoutPadding().encodeToString(b);
        return s.length() > 43 ? s.substring(0, 43) : s;
    }

    private static String snippet(String s) {
        s = s.replaceAll("\\s+", " ");
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
