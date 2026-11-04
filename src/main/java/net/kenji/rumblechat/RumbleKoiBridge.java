package net.kenji.rumblechat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.rakurai.json.element.JsonArray;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonObject;
import net.kenji.rumblechat.rumble.Json;

/**
 * Converts Rumble chat SSE payloads into Koi chat events (as Koi's JSON wire
 * format) and pushes them into Caffeinated's normal chat.
 */
public class RumbleKoiBridge {
    /** Set by the plugin so errors land in Caffeinated's app.log. */
    public static volatile BiConsumer<String, Throwable> LOG = (msg, t) -> {
        System.err.println(msg);
        if (t != null) t.printStackTrace();
    };

    private static final String RUMBLE = "https://rumble.com";
    private static final String DEFAULT_AVATAR = "data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 24 24%22%3E%3Crect width=%2224%22 height=%2224%22 fill=%22%2385c742%22/%3E%3C/svg%3E";
    private static final int MAX_SEEN = 5000;

    // Rumble sends users / channels / badge definitions alongside messages, and
    // messages only reference them by ID, so we cache them.
    private final Map<String, JsonObject> users = new HashMap<>();
    private final Map<String, JsonObject> channels = new HashMap<>();
    private final Map<String, String> badgeIcons = new HashMap<>();

    /** Message IDs already shown (Rumble resends recent history on every reconnect). */
    private final Set<String> seen = Collections.synchronizedSet(new LinkedHashSet<>());

    private final String platform; // "RUMBLE" or "CUSTOM_INTEGRATION"
    private final Map<String, Object> streamer;
    private final boolean showHistory;
    private final boolean showRants;
    private final boolean showBadges;
    private final @Nullable String platformIcon; // data: URI, first badge

    private boolean firstInit = true;
    private int emitted = 0;

    public RumbleKoiBridge(String platform, long chatId, boolean showHistory, boolean showRants, boolean showBadges, @Nullable String platformIcon) {
        this.platform = platform;
        this.streamer = profile(platform, "rumble-stream-" + chatId);
        this.showHistory = showHistory;
        this.showRants = showRants;
        this.showBadges = showBadges;
        this.platformIcon = platformIcon;
    }

    /* ---------------- */
    /* Event entry      */
    /* ---------------- */

    public void handle(String type, JsonObject data) {
        switch (type) {
            case "init": {
                this.loadBadges(data);
                this.cacheUsersAndChannels(data);
                // The init event replays recent chat history. Show it only if asked,
                // and only on the very first connection.
                this.handleMessages(data, this.firstInit && this.showHistory);
                this.firstInit = false;
                break;
            }

            case "messages": {
                this.cacheUsersAndChannels(data);
                this.handleMessages(data, true);
                break;
            }

            case "delete_messages":
            case "delete_non_rant_messages": {
                JsonArray ids = Json.arr(data, "message_ids");
                if (ids == null) break;
                for (JsonElement e : ids) {
                    String id = Json.elemStr(e);
                    if (id != null) this.hide(id);
                }
                break;
            }

            default:
                // pin_message, unpin_message, mute_users, ... not needed for a chat feed.
                break;
        }
    }

    /* ---------------- */
    /* Messages         */
    /* ---------------- */

    private void handleMessages(JsonObject data, boolean emit) {
        JsonArray messages = Json.arr(data, "messages");
        if (messages == null) return;

        for (JsonElement e : messages) {
            if (!e.isJsonObject()) continue;
            JsonObject msg = e.getAsObject();
            String id = Json.str(msg, "id");
            if (id == null) continue;

            // Always remember IDs (even un-shown history) so reconnects don't replay them.
            boolean isNew = this.markSeen(id);
            if (!emit || !isNew) continue;

            try {
                Map<String, Object> event = this.toRichMessage(msg, id);
                if (event == null) continue;
                KoiDispatch.send(JsonOut.toJsonObject(event), true);
                if (++this.emitted <= 3) {
                    LOG.accept("Sent Rumble message #" + this.emitted + " to Caffeinated chat: " + Json.str(msg, "text", ""), null);
                }
            } catch (Throwable t) {
                LOG.accept("Failed to show Rumble message " + id + ": " + t, t);
            }
        }
    }

    private @Nullable Map<String, Object> toRichMessage(JsonObject msg, String id) {
        String text = Json.str(msg, "text", "");
        JsonObject rant = Json.obj(msg, "rant");
        boolean isRant = rant != null && this.showRants;

        if (text.isEmpty() && !isRant) return null;

        Map<String, Object> sender = this.buildSender(msg);

        List<Object> donations = new ArrayList<>();
        if (isRant) {
            Map<String, Object> d = JsonOut.obj();
            d.put("type", "OTHER");
            d.put("name", "Rumble Rant");
            d.put("currency", "USD");
            d.put("amount", Json.num(rant, "price_cents", 0) / 100.0);
            d.put("count", 1);
            d.put("image", sender.get("image_link"));
            donations.add(d);
        }

        return richMessage(this.streamer, sender, text, donations, metaId(id), parseTime(Json.str(msg, "time")), isRant);
    }

    private void hide(String rumbleMessageId) {
        try {
            Map<String, Object> e = JsonOut.obj();
            e.put("event_type", "META");
            e.put("streamer", this.streamer);
            e.put("timestamp", Instant.now().toString());
            e.put("meta_id", metaId(rumbleMessageId));
            e.put("is_visible", false);
            e.put("upvotes", 0);
            e.put("attributes", Collections.emptyList());
            KoiDispatch.send(JsonOut.toJsonObject(e), false);
        } catch (Throwable t) {
            LOG.accept("Failed to hide Rumble message: " + t, t);
        }
    }

    /** Posts a small status line ("Connected to Rumble chat") into the chat feed. */
    public static void postStatus(String platform, String text) {
        try {
            Map<String, Object> bot = profile(platform, "rumble-chat-plugin");
            bot.put("roles", Collections.emptyList());
            bot.put("badges", Collections.emptyList());
            bot.put("color", "#85c742");
            bot.put("username", "RumbleChat");
            bot.put("displayname", "Rumble Chat");
            bot.put("bio", "");
            bot.put("link", RUMBLE);
            bot.put("image_link", DEFAULT_AVATAR);
            bot.put("followers_count", -1);
            bot.put("subscriber_count", -1);

            Map<String, Object> event = richMessage(
                profile(platform, "rumble-chat-plugin"), bot, text,
                Collections.emptyList(), UUID.randomUUID().toString(), Instant.now(), false
            );
            KoiDispatch.send(JsonOut.toJsonObject(event), true);
        } catch (Throwable t) {
            LOG.accept("Failed to post status message: " + t, t);
        }
    }

    /** A RICH_MESSAGE event in Koi's wire format. */
    private static Map<String, Object> richMessage(Map<String, Object> streamer, Map<String, Object> sender, String text,
        List<Object> donations, String id, Instant time, boolean highlighted) {
        String html = "<span data-rich-type='text'>" + escapeHtml(text) + "</span>";

        Map<String, Object> fragment = JsonOut.obj();
        fragment.put("type", "TEXT");
        fragment.put("raw", text);
        fragment.put("html", html);
        fragment.put("text", text);

        Map<String, Object> e = JsonOut.obj();
        e.put("event_type", "RICH_MESSAGE");
        e.put("streamer", streamer);
        e.put("timestamp", time.toString());
        e.put("sender", sender);
        e.put("fragments", Collections.singletonList(fragment));
        e.put("donations", donations);
        e.put("attachments", Collections.emptyList());
        e.put("raw", text);
        e.put("html", html + "<sup class=\"upvote-counter\"></sup>");
        e.put("id", id);
        e.put("meta_id", id);
        e.put("is_visible", true);
        e.put("upvotes", 0);
        e.put("attributes", highlighted ? Collections.singletonList("HIGHLIGHTED") : Collections.emptyList());
        return e;
    }

    /* ---------------- */
    /* Users            */
    /* ---------------- */

    private static Map<String, Object> profile(String platform, String id) {
        Map<String, Object> p = JsonOut.obj();
        p.put("UPID", id + ";" + platform);
        p.put("id", id);
        p.put("channel_id", id);
        p.put("platform", platform);
        return p;
    }

    private Map<String, Object> buildSender(JsonObject msg) {
        String userId = Json.str(msg, "user_id", "unknown");
        JsonObject u = this.users.get(userId);

        String username = Json.str(u, "username", "rumble_user_" + userId);
        String displayname = username;

        // People can chat "as" one of their channels; show the channel name then.
        String channelId = Json.str(msg, "channel_id");
        if (channelId != null && !channelId.equals("0")) {
            JsonObject ch = this.channels.get(channelId);
            String chName = Json.str(ch, "username");
            if (chName == null) chName = Json.str(ch, "title");
            if (chName != null) displayname = chName;
        }

        String link = Json.str(u, "link", "/user/" + username);
        if (link.startsWith("/")) link = RUMBLE + link;

        String color = Json.str(u, "color", "85c742");
        if (!color.startsWith("#")) color = "#" + color;

        String avatar = firstNonEmpty(Json.str(u, "image.1"), Json.str(u, "profile_pic_url"), Json.str(u, "image"));
        if (avatar == null) avatar = DEFAULT_AVATAR;
        if (avatar.startsWith("/")) avatar = RUMBLE + avatar;

        List<Object> badgeUrls = new ArrayList<>();
        if (this.platformIcon != null) badgeUrls.add(this.platformIcon);
        Set<Object> roles = new LinkedHashSet<>();
        JsonArray badgeSlugs = Json.arr(u, "badges");
        if (badgeSlugs != null) {
            for (JsonElement e : badgeSlugs) {
                String slug = Json.elemStr(e);
                if (slug == null) continue;
                String role = roleFor(slug);
                if (role != null) roles.add(role);
                String icon = this.badgeIcons.get(slug);
                if (icon != null && this.showBadges) badgeUrls.add(icon);
            }
        }
        if (Json.bool(u, "is_follower")) roles.add("FOLLOWER");

        Map<String, Object> user = profile(this.platform, "rumble-" + userId);
        user.put("roles", new ArrayList<>(roles));
        user.put("badges", badgeUrls);
        user.put("color", color);
        user.put("username", username);
        user.put("displayname", displayname);
        user.put("bio", "");
        user.put("link", link);
        user.put("image_link", avatar);
        user.put("followers_count", -1);
        user.put("subscriber_count", -1);
        return user;
    }

    private static @Nullable String roleFor(String slug) {
        switch (slug) {
            case "admin":
                return "BROADCASTER";
            case "moderator":
                return "MODERATOR";
            case "premium":
            case "recurring_subscription":
            case "locals":
            case "locals_supporter":
                return "SUBSCRIBER";
            default:
                return null;
        }
    }

    private void cacheUsersAndChannels(JsonObject data) {
        cacheById(Json.arr(data, "users"), this.users);
        cacheById(Json.arr(data, "channels"), this.channels);
    }

    private static void cacheById(@Nullable JsonArray arr, Map<String, JsonObject> into) {
        if (arr == null) return;
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsObject();
            String id = Json.str(o, "id");
            if (id != null) into.put(id, o);
        }
    }

    private void loadBadges(JsonObject data) {
        JsonObject config = Json.obj(data, "config");
        JsonObject defs = Json.obj(config, "badges");
        if (defs == null) return;

        for (Map.Entry<String, JsonElement> entry : defs.entrySet()) {
            if (entry.getValue() == null || !entry.getValue().isJsonObject()) continue;
            JsonObject icons = Json.obj(entry.getValue().getAsObject(), "icons");
            String icon = Json.str(icons, "48");
            if (icon == null) continue;
            if (icon.startsWith("/")) icon = RUMBLE + icon;
            this.badgeIcons.put(entry.getKey(), icon);
        }
    }

    /* ---------------- */
    /* Helpers          */
    /* ---------------- */

    private boolean markSeen(String id) {
        synchronized (this.seen) {
            if (!this.seen.add(id)) return false;
            if (this.seen.size() > MAX_SEEN) {
                this.seen.remove(this.seen.iterator().next());
            }
            return true;
        }
    }

    private static String metaId(String rumbleMessageId) {
        return "rumble:" + rumbleMessageId;
    }

    /** Same escaping Koi's ChatFragment uses. */
    private static String escapeHtml(String str) {
        StringBuilder sb = new StringBuilder();
        str.codePoints().forEach((c) -> {
            if (c > 127 || "\"'<>&".indexOf(c) != -1) {
                sb.append("&#").append(c).append(';');
            } else {
                sb.appendCodePoint(c);
            }
        });
        return sb.toString();
    }

    private static Instant parseTime(@Nullable String time) {
        if (time == null) return Instant.now();
        try {
            return OffsetDateTime.parse(time).toInstant();
        } catch (Exception e) {
            try {
                // Rumble sometimes uses "+0000" instead of "+00:00".
                return OffsetDateTime.parse(time.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2")).toInstant();
            } catch (Exception e2) {
                return Instant.now();
            }
        }
    }

    private static @Nullable String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }
}
