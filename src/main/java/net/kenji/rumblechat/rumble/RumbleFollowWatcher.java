package net.kenji.rumblechat.rumble;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.rakurai.json.element.JsonArray;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonObject;

/**
 * Finds new Rumble followers using the official Live Stream API
 * (rumble.com/account/livestream-api). The API has no push events, but every
 * reply carries the 50 most recent followers ("followers.recent_followers",
 * each {username, followed_on}), so we poll it and report names we haven't
 * seen before.
 *
 * - The first successful reply only seeds the list (old followers never alert).
 * - Each username alerts once per app session, so unfollow/refollow spam
 *   doesn't fire the alert again (same as Twitch).
 * - Works whether or not you're live.
 */
public final class RumbleFollowWatcher {
    private static final int MAX_SEEN = 2000;

    public static final class Follow {
        public final String username;
        public final String followedOn;

        public Follow(String username, String followedOn) {
            this.username = username;
            this.followedOn = followedOn;
        }
    }

    private final String apiUrl;
    private final long intervalMs;
    private final Consumer<Follow> onFollow;
    private final Consumer<String> log;

    private final Set<String> seenUsers = Collections.synchronizedSet(new LinkedHashSet<>());
    private volatile boolean seeded = false;
    private volatile boolean running = false;
    private volatile @Nullable Thread thread;
    private String lastError = null;

    public RumbleFollowWatcher(String apiUrl, long intervalMs, Consumer<Follow> onFollow, Consumer<String> log) {
        this.apiUrl = apiUrl.trim();
        this.intervalMs = Math.max(5_000, intervalMs);
        this.onFollow = onFollow;
        this.log = log;
    }

    public synchronized void start() {
        if (this.running) return;
        this.running = true;
        Thread t = new Thread(this::loop, "ChatPlus-RumbleFollows");
        t.setDaemon(true);
        this.thread = t;
        t.start();
    }

    public synchronized void stop() {
        this.running = false;
        Thread t = this.thread;
        if (t != null) t.interrupt();
        this.thread = null;
    }

    public boolean isRunning() {
        return this.running;
    }

    private void loop() {
        while (this.running && !Thread.currentThread().isInterrupted()) {
            try {
                this.pollOnce();
                if (this.lastError != null) {
                    this.log.accept("Rumble follow detection is working again.");
                    this.lastError = null;
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (!Objects.equals(msg, this.lastError)) {
                    this.lastError = msg;
                    this.log.accept("Rumble follow check failed (will keep trying): " + msg);
                }
            }
            try {
                Thread.sleep(this.intervalMs);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void pollOnce() throws IOException, InterruptedException {
        HttpResponse<String> res = RumbleHttp.CLIENT.send(
            HttpRequest.newBuilder(URI.create(this.apiUrl))
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
        if (root == null) throw new IOException("Live Stream API reply wasn't JSON.");

        List<Follow> follows = parseFollowers(root); // newest first
        if (!this.running) return;

        if (!this.seeded) {
            for (Follow f : follows) this.remember(f.username);
            this.seeded = true;
            this.log.accept("Rumble follow detection started (" + follows.size() + " recent followers already known).");
            return;
        }

        // Report oldest first so several follows in one poll keep their order.
        List<Follow> fresh = new ArrayList<>();
        for (Follow f : follows) {
            if (!this.seenUsers.contains(key(f.username))) fresh.add(f);
        }
        Collections.reverse(fresh);
        for (Follow f : fresh) {
            this.remember(f.username);
            try {
                this.onFollow.accept(f);
            } catch (Throwable t) {
                this.log.accept("A Rumble follow listener failed: " + t);
            }
        }
    }

    private void remember(String username) {
        synchronized (this.seenUsers) {
            this.seenUsers.add(key(username));
            while (this.seenUsers.size() > MAX_SEEN) {
                this.seenUsers.remove(this.seenUsers.iterator().next());
            }
        }
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    /**
     * Reads followers.recent_followers (+ followers.latest_follower) from a Live
     * Stream API reply. Newest first, no duplicates.
     */
    public static List<Follow> parseFollowers(JsonObject root) {
        List<Follow> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        JsonObject followers = Json.obj(root, "followers");
        if (followers == null) return out;

        JsonObject latest = Json.obj(followers, "latest_follower");
        addFollow(latest, out, names);

        JsonArray recent = Json.arr(followers, "recent_followers");
        if (recent != null) {
            for (JsonElement e : recent) {
                if (e != null && e.isJsonObject()) addFollow(e.getAsObject(), out, names);
            }
        }
        return out;
    }

    private static void addFollow(@Nullable JsonObject o, List<Follow> out, Set<String> names) {
        String name = Json.str(o, "username");
        if (name == null || name.isEmpty()) return;
        if (!names.add(key(name))) return;
        out.add(new Follow(name, Json.str(o, "followed_on", "")));
    }
}
