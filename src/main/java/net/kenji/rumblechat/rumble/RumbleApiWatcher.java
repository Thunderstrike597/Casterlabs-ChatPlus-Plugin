package net.kenji.rumblechat.rumble;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.rakurai.json.element.JsonArray;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonObject;

/**
 * Polls the official Rumble Live Stream API (rumble.com/account/livestream-api)
 * and reports what's new since the last check:
 *
 *   followers.recent_followers       {username, followed_on}
 *   subscribers.recent_subscribers   {username, amount_cents, subscribed_on}   (paid channel subs)
 *   gifted_subs.recent_gifted_subs   {purchased_by, total_gifts, gift_type, video_id}
 *
 * The API has no push events, so: the first successful reply only seeds what we
 * already know (old followers/subs never alert), and after that anything we
 * haven't seen is reported, oldest first. Works whether or not you're live.
 *
 * Each kind only runs if a handler is set (onFollow / onSubscriber / onGiftedSubs).
 */
public final class RumbleApiWatcher {
    private static final int MAX_SEEN = 2000;

    public static final class Follow {
        public final String username;
        public final String followedOn;

        public Follow(String username, String followedOn) {
            this.username = username;
            this.followedOn = followedOn;
        }
    }

    public static final class Subscriber {
        public final String username;
        public final int amountCents;
        public final String subscribedOn;

        public Subscriber(String username, int amountCents, String subscribedOn) {
            this.username = username;
            this.amountCents = amountCents;
            this.subscribedOn = subscribedOn;
        }
    }

    public static final class GiftedSubs {
        public final String purchasedBy;
        public final int totalGifts;
        public final String giftType;
        public final String videoId;

        public GiftedSubs(String purchasedBy, int totalGifts, String giftType, String videoId) {
            this.purchasedBy = purchasedBy;
            this.totalGifts = totalGifts;
            this.giftType = giftType;
            this.videoId = videoId;
        }

        String key() {
            return this.purchasedBy.toLowerCase(Locale.ROOT) + "|" + this.videoId + "|" + this.totalGifts + "|" + this.giftType;
        }
    }

    private final String apiUrl;
    private final long intervalMs;
    private final Consumer<String> log;

    private volatile @Nullable Consumer<Follow> onFollow;
    private volatile @Nullable Consumer<Subscriber> onSubscriber;
    private volatile @Nullable Consumer<GiftedSubs> onGiftedSubs;

    private final Set<String> seenFollowers = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> seenSubs = Collections.synchronizedSet(new LinkedHashSet<>());
    private Map<String, Integer> lastGiftCounts = new HashMap<>();
    private volatile boolean seeded = false;
    private volatile boolean running = false;
    private volatile @Nullable Thread thread;
    private String lastError = null;

    public RumbleApiWatcher(String apiUrl, long intervalMs, Consumer<String> log) {
        this.apiUrl = apiUrl.trim();
        this.intervalMs = Math.max(5_000, intervalMs);
        this.log = log;
    }

    public RumbleApiWatcher onFollow(@Nullable Consumer<Follow> handler) {
        this.onFollow = handler;
        return this;
    }

    public RumbleApiWatcher onSubscriber(@Nullable Consumer<Subscriber> handler) {
        this.onSubscriber = handler;
        return this;
    }

    public RumbleApiWatcher onGiftedSubs(@Nullable Consumer<GiftedSubs> handler) {
        this.onGiftedSubs = handler;
        return this;
    }

    public synchronized void start() {
        if (this.running) return;
        this.running = true;
        Thread t = new Thread(this::loop, "RumbleApiWatcher");
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
                    this.log.accept("Rumble Live Stream API checks are working again.");
                    this.lastError = null;
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (!Objects.equals(msg, this.lastError)) {
                    this.lastError = msg;
                    this.log.accept("Rumble Live Stream API check failed (will keep trying): " + msg);
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
        if (!this.running) return;
        this.process(root);
    }

    /** Handles one API reply. Package-visible so it can be tested without HTTP. */
    void process(JsonObject root) {
        List<Follow> follows = parseFollowers(root); // newest first
        List<Subscriber> subs = parseSubscribers(root); // newest first
        List<GiftedSubs> gifts = parseGiftedSubs(root); // newest first
        Map<String, Integer> giftCounts = countGifts(gifts);

        if (!this.seeded) {
            for (Follow f : follows) remember(this.seenFollowers, key(f.username));
            for (Subscriber s : subs) remember(this.seenSubs, subKey(s));
            this.lastGiftCounts = giftCounts;
            this.seeded = true;
            this.log.accept(String.format(
                "Rumble Live Stream API watcher started (already known: %d followers, %d subs, %d gifts).",
                follows.size(), subs.size(), gifts.size()
            ));
            return;
        }

        // Followers: each username alerts once per session (no refollow spam).
        List<Follow> newFollows = new ArrayList<>();
        for (Follow f : follows) {
            if (!this.seenFollowers.contains(key(f.username))) newFollows.add(f);
        }
        Collections.reverse(newFollows);
        for (Follow f : newFollows) {
            remember(this.seenFollowers, key(f.username));
            deliver(this.onFollow, f);
        }

        // Subscribers: username + subscribed_on, so a renewal counts again.
        List<Subscriber> newSubs = new ArrayList<>();
        for (Subscriber s : subs) {
            if (!this.seenSubs.contains(subKey(s))) newSubs.add(s);
        }
        Collections.reverse(newSubs);
        for (Subscriber s : newSubs) {
            remember(this.seenSubs, subKey(s));
            deliver(this.onSubscriber, s);
        }

        // Gifted subs have no ID or time, so compare how often each (buyer, stream, size) shows up.
        List<GiftedSubs> newGifts = new ArrayList<>();
        Map<String, Integer> remaining = new HashMap<>(giftCounts);
        for (GiftedSubs g : gifts) {
            int now = remaining.getOrDefault(g.key(), 0);
            int before = this.lastGiftCounts.getOrDefault(g.key(), 0);
            if (now > before) {
                newGifts.add(g);
                remaining.put(g.key(), now - 1);
            }
        }
        this.lastGiftCounts = giftCounts;
        Collections.reverse(newGifts);
        for (GiftedSubs g : newGifts) deliver(this.onGiftedSubs, g);
    }

    private <T> void deliver(@Nullable Consumer<T> handler, T value) {
        if (handler == null) return;
        try {
            handler.accept(value);
        } catch (Throwable t) {
            this.log.accept("A Rumble event handler failed: " + t);
        }
    }

    private static void remember(Set<String> set, String key) {
        synchronized (set) {
            set.add(key);
            while (set.size() > MAX_SEEN) set.remove(set.iterator().next());
        }
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private static String subKey(Subscriber s) {
        return key(s.username) + "|" + s.subscribedOn;
    }

    private static Map<String, Integer> countGifts(List<GiftedSubs> gifts) {
        Map<String, Integer> m = new HashMap<>();
        for (GiftedSubs g : gifts) m.merge(g.key(), 1, Integer::sum);
        return m;
    }

    /* ---------------- */
    /* Parsing          */
    /* ---------------- */

    /** followers.recent_followers (+ latest_follower). Newest first, no duplicates. */
    public static List<Follow> parseFollowers(JsonObject root) {
        List<Follow> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        JsonObject followers = Json.obj(root, "followers");
        if (followers == null) return out;
        for (JsonObject o : latestAndRecent(followers, "latest_follower", "recent_followers")) {
            String name = Json.str(o, "username");
            if (name == null || name.isEmpty() || !names.add(key(name))) continue;
            out.add(new Follow(name, Json.str(o, "followed_on", "")));
        }
        return out;
    }

    /** subscribers.recent_subscribers (+ latest_subscriber). Newest first, no duplicates. */
    public static List<Subscriber> parseSubscribers(JsonObject root) {
        List<Subscriber> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        JsonObject subscribers = Json.obj(root, "subscribers");
        if (subscribers == null) return out;
        for (JsonObject o : latestAndRecent(subscribers, "latest_subscriber", "recent_subscribers")) {
            String name = Json.str(o, "username");
            if (name == null || name.isEmpty()) name = Json.str(o, "user");
            if (name == null || name.isEmpty()) continue;
            Subscriber s = new Subscriber(name, (int) Math.round(Json.num(o, "amount_cents", 0)), Json.str(o, "subscribed_on", ""));
            if (keys.add(subKey(s))) out.add(s);
        }
        return out;
    }

    /**
     * gifted_subs.recent_gifted_subs. Newest first. latest_gifted_sub is only used
     * when the recent list is missing (it's normally also the list's first entry).
     */
    public static List<GiftedSubs> parseGiftedSubs(JsonObject root) {
        List<GiftedSubs> out = new ArrayList<>();
        JsonObject gifted = Json.obj(root, "gifted_subs");
        if (gifted == null) return out;

        List<JsonObject> items = new ArrayList<>();
        JsonArray recent = Json.arr(gifted, "recent_gifted_subs");
        if (recent != null) {
            for (JsonElement e : recent) {
                if (e != null && e.isJsonObject()) items.add(e.getAsObject());
            }
        } else {
            JsonObject latest = Json.obj(gifted, "latest_gifted_sub");
            if (latest != null) items.add(latest);
        }

        for (JsonObject o : items) {
            String buyer = Json.str(o, "purchased_by");
            if (buyer == null || buyer.isEmpty()) continue;
            out.add(new GiftedSubs(
                buyer,
                Math.max(1, (int) Math.round(Json.num(o, "total_gifts", 1))),
                Json.str(o, "gift_type", ""),
                Json.str(o, "video_id", "")
            ));
        }
        return out;
    }

    private static List<JsonObject> latestAndRecent(JsonObject block, String latestKey, String recentKey) {
        List<JsonObject> list = new ArrayList<>();
        JsonObject latest = Json.obj(block, latestKey);
        if (latest != null) list.add(latest);
        JsonArray recent = Json.arr(block, recentKey);
        if (recent != null) {
            for (JsonElement e : recent) {
                if (e != null && e.isJsonObject()) list.add(e.getAsObject());
            }
        }
        return list;
    }
}
