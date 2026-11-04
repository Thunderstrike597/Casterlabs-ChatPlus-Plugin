package net.kenji.rumblechat.rumble;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import co.casterlabs.rakurai.json.element.JsonObject;

/**
 * Minimal reader for Rumble's (unofficial) chat Server-Sent-Events stream:
 * https://web7.rumble.com/chat/api/chat/{id}/stream
 *
 * Each SSE event's data is JSON shaped like {"type": "...", "data": {...}}.
 * Known types: init, messages, delete_messages, delete_non_rant_messages,
 * pin_message, unpin_message, mute_users.
 *
 * Rumble answers HTTP 204 when the chat is over. Silence alone is not "over":
 * after 90s with no bytes we drop the socket and reconnect.
 */
public class RumbleChatClient {
    public interface Listener {
        void onConnected(long chatId);

        void onEvent(String type, JsonObject data);

        void onDisconnected(String reason);
    }

    public enum EndReason {
        CHAT_ENDED,
        STOPPED
    }

    private static final String SSE_URL = "https://web7.rumble.com/chat/api/chat/%d/stream";
    private static final long READ_TIMEOUT_MS = 90_000;
    private static final long RECONNECT_DELAY_MS = 2_000;

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor((r) -> {
        Thread t = new Thread(r, "RumbleChat-Watchdog");
        t.setDaemon(true);
        return t;
    });

    private final long chatId;
    private final Listener listener;
    private volatile boolean stopped = false;
    private volatile InputStream currentStream;

    public RumbleChatClient(long chatId, Listener listener) {
        this.chatId = chatId;
        this.listener = listener;
    }

    public long getChatId() {
        return this.chatId;
    }

    public void stop() {
        this.stopped = true;
        closeQuietly(this.currentStream);
    }

    /**
     * Blocks the calling thread until the chat ends or {@link #stop()} is called.
     * Reconnects by itself on network blips.
     */
    public EndReason run() throws InterruptedException {
        int failures = 0;

        while (!this.stopped) {
            try {
                HttpResponse<InputStream> res = RumbleHttp.CLIENT.send(
                    HttpRequest.newBuilder(URI.create(String.format(SSE_URL, this.chatId)))
                        .header("User-Agent", RumbleHttp.USER_AGENT)
                        .header("Accept", "text/event-stream")
                        .header("Cache-Control", "no-cache")
                        .header("Origin", "https://rumble.com")
                        .header("Referer", "https://rumble.com/")
                        .timeout(Duration.ofSeconds(15)) // headers only; body reads are guarded by the watchdog
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofInputStream()
                );

                if (res.statusCode() == 204) {
                    closeQuietly(res.body());
                    return EndReason.CHAT_ENDED;
                }
                if (res.statusCode() != 200) {
                    String snippet = "";
                    try (InputStream body = res.body()) {
                        byte[] b = body.readNBytes(300);
                        snippet = new String(b, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
                    } catch (IOException ignored) {}
                    throw new IOException("Rumble chat returned HTTP " + res.statusCode() + " " + snippet);
                }

                failures = 0;
                this.currentStream = res.body();
                this.listener.onConnected(this.chatId);
                this.readEvents(res.body());

                if (!this.stopped) {
                    this.listener.onDisconnected("Connection dropped, reconnecting...");
                }
            } catch (IOException e) {
                if (this.stopped) break;
                failures++;
                this.listener.onDisconnected(e.getMessage() + " (retry " + failures + ")");
            }

            if (this.stopped) break;
            // Back off a bit more each time something keeps failing, capped at 30s.
            Thread.sleep(Math.min(30_000, RECONNECT_DELAY_MS * Math.max(1, failures)));
        }
        return EndReason.STOPPED;
    }

    private void readEvents(InputStream in) throws IOException {
        final long[] lastActivity = {
                System.currentTimeMillis()
        };

        ScheduledFuture<?> watchdog = WATCHDOG.scheduleAtFixedRate(() -> {
            if (System.currentTimeMillis() - lastActivity[0] > READ_TIMEOUT_MS) {
                closeQuietly(in); // Unblocks readLine() so we reconnect.
            }
        }, 10, 10, TimeUnit.SECONDS);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;

            while (!this.stopped && (line = reader.readLine()) != null) {
                lastActivity[0] = System.currentTimeMillis();

                if (line.isEmpty()) {
                    // Blank line = dispatch the buffered event.
                    if (data.length() > 0) {
                        this.dispatch(data.toString());
                        data.setLength(0);
                    }
                    continue;
                }

                if (line.startsWith(":")) continue; // SSE comment / keep-alive

                if (line.startsWith("data:")) {
                    String chunk = line.substring(5);
                    if (chunk.startsWith(" ")) chunk = chunk.substring(1);
                    if (data.length() > 0) data.append('\n');
                    data.append(chunk);
                }
                // "event:", "id:" and "retry:" lines are not needed.
            }
        } catch (IOException e) {
            if (!this.stopped) throw e;
        } finally {
            watchdog.cancel(false);
            this.currentStream = null;
        }
    }

    private void dispatch(String raw) {
        JsonObject json = Json.parseObject(raw);
        if (json == null) return;

        String type = Json.str(json, "type", "");
        JsonObject data = Json.obj(json, "data");
        if (data == null) data = new JsonObject();

        try {
            this.listener.onEvent(type, data);
        } catch (Throwable t) {
            // Never let a bad message kill the reader.
            t.printStackTrace();
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {}
    }
}
