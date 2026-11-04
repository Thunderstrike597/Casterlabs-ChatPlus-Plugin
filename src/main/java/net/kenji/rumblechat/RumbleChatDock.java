package net.kenji.rumblechat;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import co.casterlabs.caffeinated.pluginsdk.Caffeinated;
import co.casterlabs.caffeinated.pluginsdk.widgets.Widget;
import co.casterlabs.caffeinated.pluginsdk.widgets.WidgetDetails;
import co.casterlabs.caffeinated.pluginsdk.widgets.WidgetInstance;
import co.casterlabs.caffeinated.pluginsdk.widgets.WidgetInstanceMode;
import co.casterlabs.caffeinated.pluginsdk.widgets.WidgetType;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonObject;
import lombok.NonNull;
import net.kenji.rumblechat.rumble.Json;
import net.kenji.rumblechat.rumble.RumbleSender;

/**
 * "Stream Chat + Rumble" dock: a copy of Caffeinated's Stream Chat dock that can
 * also send to Rumble. The page (resources/web/dock.html) reads chat through
 * the same Koi events as the stock dock; this class handles what the page
 * can't do itself: sending to Rumble and the Rumble login.
 *
 * Messages from the page:
 *   rc:hello                       -> replies rc:state
 *   rc:send     {message}          -> replies rc:send_result {ok, error}
 *   rc:account  {action, ...}      -> replies rc:state
 *   savePreferences {...}          -> stored in this dock's settings (like the stock dock)
 *   openLink    "url"
 */
public class RumbleChatDock extends Widget {
    public static final WidgetDetails DETAILS = new WidgetDetails()
        .withNamespace("net.kenji.rumble_chat.dock")
        .withIcon("chat-bubble-left-right")
        .withType(WidgetType.DOCK)
        .withFriendlyName("Stream Chat + Rumble");

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor((r) -> {
        Thread t = new Thread(r, "RumbleChat-Dock");
        t.setDaemon(true);
        return t;
    });

    private final RumbleChatPlugin plugin;

    public RumbleChatDock(RumbleChatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NonNull String getWidgetBasePath(WidgetInstanceMode mode) {
        return "/dock/chat";
    }

    @Override
    public void onNewInstance(@NonNull WidgetInstance instance) {
        instance.on("rc:hello", () -> this.sendState(instance));

        instance.on("savePreferences", (JsonElement data) -> {
            this.settings().set("preferences", data);
        });

        instance.on("openLink", (JsonElement e) -> {
            if (e != null && e.isJsonString()) Caffeinated.getInstance().openLink(e.getAsString());
        });

        instance.on("rc:send", (JsonElement e) -> WORKER.submit(() -> this.handleSend(instance, e)));
        instance.on("rc:account", (JsonElement e) -> WORKER.submit(() -> this.handleAccount(instance, e)));
    }

    private void handleSend(WidgetInstance instance, JsonElement e) {
        Map<String, Object> result = JsonOut.obj();
        String nonce = null;
        try {
            JsonObject data = (e != null && e.isJsonObject()) ? e.getAsObject() : new JsonObject();
            nonce = Json.str(data, "nonce");
            String message = Json.str(data, "message", "");

            long chatId = this.plugin.getCurrentChatId();
            if (chatId <= 0) throw new IllegalStateException("Not connected to a live Rumble chat right now.");

            try {
                this.plugin.getAccount().send(chatId, message);
            } catch (RumbleSender.ChatClosedException closed) {
                // Usually an old stream (e.g. OBS was restarted with a static key). Find the current one and retry.
                long newId = this.plugin.refreshLiveStream();
                if (newId <= 0) {
                    throw new IllegalStateException("That Rumble stream's chat is closed, and no newer live stream was found.");
                }
                this.plugin.getAccount().send(newId, message);
            }
            result.put("ok", true);
        } catch (Throwable t) {
            result.put("ok", false);
            result.put("error", t.getMessage() == null ? t.toString() : t.getMessage());
            RumbleKoiBridge.LOG.accept("Rumble send failed: " + t.getMessage(), null);
        }
        result.put("nonce", nonce);
        this.emit(instance, "rc:send_result", result);
        this.sendState(instance);
    }

    private void handleAccount(WidgetInstance instance, JsonElement e) {
        RumbleAccount account = this.plugin.getAccount();
        String error = null;
        try {
            JsonObject data = (e != null && e.isJsonObject()) ? e.getAsObject() : new JsonObject();
            switch (Json.str(data, "action", "")) {
                case "off":
                    account.setOff();
                    break;
                case "cookie":
                    account.saveCookie(Json.str(data, "cookie", ""));
                    break;
                case "password":
                    account.savePassword(Json.str(data, "username", ""), Json.str(data, "password", ""));
                    break;
                case "2fa_request":
                    account.request2faCode(Json.str(data, "option", ""));
                    break;
                case "2fa":
                    account.submit2fa(Json.str(data, "code", ""));
                    break;
                case "forget":
                    account.forget();
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            error = t.getMessage() == null ? t.toString() : t.getMessage();
            RumbleKoiBridge.LOG.accept("Rumble account action failed: " + error, null);
        }
        Map<String, Object> extra = JsonOut.obj();
        if (error != null) extra.put("error", error);
        this.sendState(instance, extra);
    }

    void sendState(WidgetInstance instance) {
        this.sendState(instance, JsonOut.obj());
    }

    private void sendState(WidgetInstance instance, Map<String, Object> extra) {
        Map<String, Object> s = JsonOut.obj();
        s.put("account", this.plugin.getAccount().status());
        s.put("connected", this.plugin.getCurrentChatId() > 0);
        s.put("icon", this.plugin.getIconDataUri());
        s.put("dink", this.plugin.getDinkDataUri());
        s.put("max_length", RumbleSender.MAX_LENGTH);
        s.putAll(extra);
        this.emit(instance, "rc:state", s);
    }

    /** Pushes fresh state to every open copy of the dock (e.g. when Rumble connects). */
    void broadcastState() {
        for (WidgetInstance instance : this.getWidgetInstances()) {
            try {
                this.sendState(instance);
            } catch (Throwable ignored) {}
        }
    }

    private void emit(WidgetInstance instance, String type, Map<String, Object> data) {
        try {
            instance.emit(type, JsonOut.toJsonObject(data));
        } catch (Throwable t) {
            RumbleKoiBridge.LOG.accept("Couldn't reach the dock: " + t, null);
        }
    }
}
