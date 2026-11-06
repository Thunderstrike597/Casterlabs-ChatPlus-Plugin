package net.kenji.rumblechat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.caffeinated.pluginsdk.Caffeinated;
import co.casterlabs.caffeinated.pluginsdk.CaffeinatedPlugin;
import co.casterlabs.caffeinated.pluginsdk.widgets.Widget;
import co.casterlabs.caffeinated.pluginsdk.widgets.WidgetSettings;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonNull;
import co.casterlabs.rakurai.json.element.JsonObject;
import net.kenji.rumblechat.rumble.Json;

/**
 * Compat with Kenji's "Chat Spotlight" plugin: lets the ChatPlus dock pick the
 * message that Chat Spotlight's overlay shows on stream.
 *
 * ChatPlus never links against Chat Spotlight's classes. Everything is looked up
 * by plugin ID at runtime, so if Chat Spotlight isn't installed (or is an old
 * version) the compat simply reports "not available" and nothing errors.
 *
 * Two ways of talking to it, best first:
 *   1. Chat Spotlight 1.1+ has public compatShow / compatClear / compatCurrent methods.
 *   2. Older builds: write its "current" field and broadcast the same widget
 *      messages its own picker dock would (spotlight_show / spotlight_clear).
 *
 * Whatever is on screen is polled a few times a second, so the ChatPlus dock
 * also follows changes made from Chat Spotlight's own picker, and the overlay's
 * auto-hide timer.
 */
final class ChatSpotlightCompat {
    static final String PLUGIN_ID = "dev.thedevkenji.chatspotlight";
    private static final String OVERLAY_NS = PLUGIN_ID + ".overlay";
    private static final String PICKER_NS = PLUGIN_ID + ".picker";
    private static final long POLL_MS = 400;

    /** The user's choices from Settings > ChatPlus > Compat > ChatSpotlight. */
    static final class Options {
        boolean enabled = false;
        String chooseType = "click"; // click | button | menu
        boolean hideButton = true;
        String highlight = "full"; // rim | full

        static Options read(WidgetSettings s) {
            Options o = new Options();
            o.enabled = s.getBoolean("compat_chatspotlight.enabled", false);
            o.chooseType = oneOf(s.getString("compat_chatspotlight.choose_type", "click"), "click", "click", "button", "menu");
            o.hideButton = s.getBoolean("compat_chatspotlight.hide_button", true);
            o.highlight = oneOf(s.getString("compat_chatspotlight.highlight", "full"), "full", "rim", "full");
            return o;
        }

        private static String oneOf(String v, String def, String... allowed) {
            for (String a : allowed) if (a.equals(v)) return v;
            return def;
        }
    }

    private final Consumer<String> log;
    private final Runnable onStateChanged;
    private volatile Options options = new Options();

    // Last state pushed to the docks; the poller only broadcasts when this changes.
    private volatile String lastFingerprint = "";
    private volatile boolean warnedMissing = false;
    private volatile boolean warnedBroken = false;

    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor((r) -> {
        Thread t = new Thread(r, "ChatPlus-SpotlightCompat");
        t.setDaemon(true);
        return t;
    });

    ChatSpotlightCompat(Consumer<String> log, Runnable onStateChanged) {
        this.log = log;
        this.onStateChanged = onStateChanged;
        this.poller.scheduleWithFixedDelay(this::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
    }

    void close() {
        this.poller.shutdownNow();
    }

    void setOptions(Options o) {
        this.options = o;
        if (o.enabled && !isInstalled() && !this.warnedMissing) {
            this.warnedMissing = true;
            this.log.accept("ChatSpotlight compat is on, but Chat Spotlight isn't installed. The dock will act normally until it is.");
        }
        this.lastFingerprint = ""; // Force a push with the new options.
        this.poll();
    }

    /* ---------------- */
    /* State for docks  */
    /* ---------------- */

    /** True when the toggle is on AND Chat Spotlight is loaded. */
    boolean isActive() {
        return this.options.enabled && isInstalled();
    }

    /** Everything the dock needs, sent as "cp:spotlight". */
    Map<String, Object> state() {
        Options o = this.options;
        boolean installed = isInstalled();
        Map<String, Object> s = JsonOut.obj();
        s.put("enabled", o.enabled);
        s.put("installed", installed);
        s.put("active", o.enabled && installed);
        s.put("choose_type", o.chooseType);
        s.put("hide_button", o.hideButton);
        s.put("highlight", o.highlight);

        String id = null, name = null;
        if (o.enabled && installed) {
            JsonObject cur = current();
            if (cur != null) {
                id = Json.str(cur, "id");
                name = Json.str(cur, "name");
            }
        }
        s.put("current_id", id);
        s.put("current_name", name);
        return s;
    }

    private void poll() {
        try {
            String fp = JsonOut.write(this.state());
            if (!Objects.equals(fp, this.lastFingerprint)) {
                this.lastFingerprint = fp;
                this.onStateChanged.run();
            }
        } catch (Throwable t) {
            this.warnOnce(t);
        }
    }

    /* ---------------- */
    /* Actions          */
    /* ---------------- */

    /** Puts a message on screen. payload = {id, platform, platform_name, name, color, badges, html}. */
    void show(JsonObject payload) {
        if (!isActive() || payload == null) return;
        try {
            CaffeinatedPlugin plugin = plugin();
            if (plugin == null) return;

            Method api = findMethod(plugin, "compatShow", JsonElement.class);
            if (api != null) {
                api.invoke(plugin, payload);
            } else {
                setCurrentField(plugin, payload);
                broadcast(plugin, OVERLAY_NS, "spotlight_show", payload);
                broadcast(plugin, PICKER_NS, "spotlight_state", payload);
            }
        } catch (Throwable t) {
            this.warnOnce(t);
        }
        this.poll();
    }

    /** Takes whatever is on screen off it. */
    void clear() {
        if (!isActive()) return;
        try {
            CaffeinatedPlugin plugin = plugin();
            if (plugin == null) return;

            Method api = findMethod(plugin, "compatClear");
            if (api != null) {
                api.invoke(plugin);
            } else {
                setCurrentField(plugin, null);
                broadcast(plugin, OVERLAY_NS, "spotlight_clear", JsonNull.INSTANCE);
                broadcast(plugin, PICKER_NS, "spotlight_state", JsonNull.INSTANCE);
            }
        } catch (Throwable t) {
            this.warnOnce(t);
        }
        this.poll();
    }

    /* ---------------- */
    /* Lookups          */
    /* ---------------- */

    private static boolean isInstalled() {
        try {
            return Caffeinated.getInstance().getPlugins().isPluginPresent(PLUGIN_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    private static @Nullable CaffeinatedPlugin plugin() {
        try {
            return Caffeinated.getInstance().getPlugins().getPluginById(PLUGIN_ID);
        } catch (Throwable t) {
            return null;
        }
    }

    /** What Chat Spotlight is showing right now, or null. */
    private @Nullable JsonObject current() {
        CaffeinatedPlugin plugin = plugin();
        if (plugin == null) return null;
        try {
            Object v;
            Method api = findMethod(plugin, "compatCurrent");
            if (api != null) {
                v = api.invoke(plugin);
            } else {
                Field f = currentField(plugin);
                v = f == null ? null : f.get(null);
            }
            return (v instanceof JsonObject) ? (JsonObject) v : null;
        } catch (Throwable t) {
            this.warnOnce(t);
            return null;
        }
    }

    private static @Nullable Method findMethod(Object target, String name, Class<?>... params) {
        try {
            return target.getClass().getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static @Nullable Field currentField(CaffeinatedPlugin plugin) {
        try {
            Field f = plugin.getClass().getDeclaredField("current");
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setCurrentField(CaffeinatedPlugin plugin, @Nullable JsonElement value) throws IllegalAccessException {
        Field f = currentField(plugin);
        if (f != null) f.set(null, value);
    }

    private static void broadcast(CaffeinatedPlugin plugin, String namespace, String type, JsonElement payload) {
        for (Widget widget : plugin.getWidgets()) {
            if (namespace.equals(widget.getNamespace())) {
                try {
                    widget.broadcastToAll(type, payload);
                } catch (Throwable ignored) {}
            }
        }
    }

    private void warnOnce(Throwable t) {
        if (this.warnedBroken) return;
        this.warnedBroken = true;
        this.log.accept("ChatSpotlight compat hit a problem (it will keep trying quietly): " + t);
    }
}
