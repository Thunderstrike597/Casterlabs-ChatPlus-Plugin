package net.kenji.rumblechat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.caffeinated.pluginsdk.Caffeinated;
import co.casterlabs.commons.functional.tuples.Pair;

import co.casterlabs.caffeinated.pluginsdk.CaffeinatedPlugin;
import co.casterlabs.caffeinated.pluginsdk.CaffeinatedPluginImplementation;
import co.casterlabs.caffeinated.pluginsdk.widgets.WidgetSettings;
import co.casterlabs.caffeinated.pluginsdk.widgets.settings.WidgetSettingsItem;
import co.casterlabs.caffeinated.pluginsdk.widgets.settings.WidgetSettingsLayout;
import co.casterlabs.caffeinated.pluginsdk.widgets.settings.WidgetSettingsSection;
import lombok.NonNull;
import net.kenji.rumblechat.rumble.RumbleChatClient;
import net.kenji.rumblechat.rumble.RumbleHttp;
import net.kenji.rumblechat.rumble.RumbleIds;
import net.kenji.rumblechat.rumble.RumbleLiveStreamApi;

/**
 * Adds Rumble chat to Caffeinated's normal chat (every dock, widget and plugin
 * receives it through Koi), plus a "Stream Chat + Rumble" dock that can also
 * send to Rumble.
 *
 * Settings live in their own tab: Settings > Rumble Chat.
 * The Rumble login for sending lives in the dock's own settings panel.
 */
@CaffeinatedPluginImplementation
public class RumbleChatPlugin extends CaffeinatedPlugin {
    private static final long API_POLL_MS = 15_000;
    private static final long MANUAL_RETRY_MS = 30_000;

    private volatile Thread worker;
    private volatile RumbleChatClient client;
    private volatile int generation = 0;
    private String lastConfig = "";

    /** Base-10 chat ID of the Rumble chat we're connected to right now, 0 if none. */
    private volatile long currentChatId = 0;
    private RumbleAccount account;
    private final List<RumbleChatDock> docks = new CopyOnWriteArrayList<>();
    private volatile @Nullable String iconDataUri;
    private volatile @Nullable String dinkDataUri;
    private volatile @Nullable Config activeConfig;

    private static final long STREAM_RECHECK_SECONDS = 30;
    private static final java.util.concurrent.ScheduledExecutorService RECHECK = java.util.concurrent.Executors.newSingleThreadScheduledExecutor((r) -> {
        Thread t = new Thread(r, "RumbleChat-StreamCheck");
        t.setDaemon(true);
        return t;
    });

    /* ---------------- */
    /* Lifecycle        */
    /* ---------------- */

    @Override
    public void onInit() {
        RumbleKoiBridge.LOG = (msg, t) -> {
            if (t == null) {
                this.getLogger().info(msg);
            } else {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                this.getLogger().warn("%s\n%s", msg, sw);
            }
        };
        this.account = new RumbleAccount(dataDir(), RumbleKoiBridge.LOG);
        this.iconDataUri = RumbleIcon.load();
        this.dinkDataUri = loadAppSound("dink.mp3");

        Caffeinated.getInstance().getPlugins().registerWidgetFactory(this, RumbleChatDock.DETAILS, (details) -> {
            RumbleChatDock dock = new RumbleChatDock(this);
            this.docks.add(dock);
            return dock;
        });

        this.createSettingsApplet();
        this.setSettingsLayout(buildLayout());
        this.applySettings(true);
        this.getLogger().info("Rumble Chat loaded.");
    }

    @Override
    protected void onSettingsUpdate() {
        this.applySettings(false);
    }

    @Override
    public void onClose() {
        this.stop();
    }

    @Override
    public @NonNull String getName() {
        return "Rumble Chat";
    }

    @Override
    public @NonNull String getId() {
        return "net.kenji.rumble_chat";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getAuthor() {
        return "Kenji";
    }

    /* ---------------- */
    /* Settings         */
    /* ---------------- */

    private static WidgetSettingsLayout buildLayout() {
        Map<String, String> modes = new LinkedHashMap<>();
        modes.put("api", "Auto-detect my live stream (Live Stream API URL)");
        modes.put("manual", "A specific stream / chat URL");

        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("rumble", "Rumble");
        tags.put("custom", "Custom Integration");

        return new WidgetSettingsLayout()
            .addSection(
                new WidgetSettingsSection("connection", "Rumble Connection")
                    .addItem(WidgetSettingsItem.asCheckbox("enabled", "Enabled", true))
                    .addItem(WidgetSettingsItem.asDropdown("mode", "Find the chat by", "api", modes))
                    .addItem(WidgetSettingsItem.asPassword(
                        "api_url", "Live Stream API URL",
                        "", "https://rumble.com/-livestream-api/get-data?key=..."
                    ))
                    .addItem(WidgetSettingsItem.asText(
                        "stream_url", "Stream / chat URL or ID",
                        "", "rumble.com/v6abc12-... or rumble.com/chat/popup/123456789"
                    ))
            )
            .addSection(
                new WidgetSettingsSection("display", "Chat Display")
                    .addItem(WidgetSettingsItem.asDropdown("platform_tag", "Show messages as platform", "rumble", tags))
                    .addItem(WidgetSettingsItem.asCheckbox("show_rants", "Show Rants as donations", true))
                    .addItem(WidgetSettingsItem.asCheckbox("show_icon", "Show Rumble icon before names (stock chat & widgets)", true))
                    .addItem(WidgetSettingsItem.asCheckbox("show_badges", "Show Rumble badges", true))
                    .addItem(WidgetSettingsItem.asCheckbox("show_history", "Show messages sent before connecting", false))
                    .addItem(WidgetSettingsItem.asCheckbox("status_messages", "Post connect / disconnect notices in chat", true))
            );
    }

    private static final class Config {
        boolean enabled;
        String mode;
        String apiUrl;
        String streamUrl;
        String platformTag;
        boolean showRants;
        boolean showBadges;
        boolean showIcon;
        boolean showHistory;
        boolean statusMessages;

        static Config read(WidgetSettings s) {
            Config c = new Config();
            c.enabled = s.getBoolean("connection.enabled", true);
            c.mode = s.getString("connection.mode", "api");
            c.apiUrl = s.getString("connection.api_url", "").trim();
            c.streamUrl = s.getString("connection.stream_url", "").trim();
            c.platformTag = s.getString("display.platform_tag", "rumble");
            c.showRants = s.getBoolean("display.show_rants", true);
            c.showBadges = s.getBoolean("display.show_badges", true);
            c.showIcon = s.getBoolean("display.show_icon", true);
            c.showHistory = s.getBoolean("display.show_history", false);
            c.statusMessages = s.getBoolean("display.status_messages", true);
            return c;
        }

        String fingerprint() {
            return String.join("|",
                String.valueOf(enabled), mode, apiUrl, streamUrl, platformTag,
                String.valueOf(showRants), String.valueOf(showBadges), String.valueOf(showIcon),
                String.valueOf(showHistory), String.valueOf(statusMessages)
            );
        }
    }

    /** Restarts the connection only when something actually changed (text boxes fire on every keystroke). */
    private synchronized void applySettings(boolean force) {
        Config cfg = Config.read(this.settings());
        String fp = cfg.fingerprint();
        if (!force && Objects.equals(fp, this.lastConfig)) return;
        this.lastConfig = fp;

        this.stop();
        if (!cfg.enabled) {
            this.getLogger().info("Rumble Chat disabled.");
            return;
        }

        boolean configured = "api".equals(cfg.mode) ? !cfg.apiUrl.isEmpty() : !cfg.streamUrl.isEmpty();
        if (!configured) {
            this.getLogger().info("Rumble Chat isn't configured yet (Settings > Rumble Chat).");
            return;
        }

        final int gen = this.generation;
        Thread t = new Thread(() -> this.workerLoop(cfg, gen), "RumbleChat-Worker");
        t.setDaemon(true);
        this.worker = t;
        t.start();
    }

    private synchronized void stop() {
        this.generation++; // Any running worker sees it's stale and exits.
        RumbleChatClient c = this.client;
        if (c != null) c.stop();
        Thread t = this.worker;
        if (t != null) t.interrupt();
        this.client = null;
        this.worker = null;
    }

    /* ---------------- */
    /* Connection loop  */
    /* ---------------- */

    private void workerLoop(Config cfg, int gen) {
        final String platform = resolvePlatform(cfg.platformTag);
        try {
            KoiDispatch.init();
        } catch (Throwable t) {
            this.getLogger().warn("Can't hook into Caffeinated's chat: %s", t);
            return;
        }
        this.getLogger().info("Starting (mode=%s, platform=%s, koi=%s).", cfg.mode, platform, KoiDispatch.describe());
        String lastSummary = null;

        while (gen == this.generation && !Thread.currentThread().isInterrupted()) {
            try {
                long chatId;
                String label;

                if ("api".equals(cfg.mode)) {
                    RumbleLiveStreamApi.LiveInfo live = RumbleLiveStreamApi.findLiveStream(cfg.apiUrl);
                    // Log what Rumble reported whenever it changes.
                    if (!RumbleLiveStreamApi.lastSummary.equals(lastSummary)) {
                        lastSummary = RumbleLiveStreamApi.lastSummary;
                        this.getLogger().info("%s", lastSummary);
                    }
                    if (live == null) {
                        // Not live yet; check again shortly.
                        Thread.sleep(API_POLL_MS);
                        continue;
                    }
                    this.getLogger().info("Live stream found: %s -> chat id %d", live.streamIdB36, live.chatId);
                    chatId = live.chatId;
                    label = live.title.isEmpty() ? live.streamIdB36 : live.title;
                } else {
                    chatId = RumbleIds.resolveChatId(cfg.streamUrl, RumbleHttp.CLIENT);
                    label = String.valueOf(chatId);
                }

                RumbleChatClient.EndReason reason = this.runChat(cfg, platform, chatId, label);
                if (gen != this.generation) break;

                if (reason == RumbleChatClient.EndReason.CHAT_ENDED) {
                    this.getLogger().info("Rumble chat %d has ended.", chatId);
                    if (cfg.statusMessages) RumbleKoiBridge.postStatus(platform, "Rumble chat ended.");
                    // In API mode we go straight back to watching for the next stream.
                    // In manual mode the same ID can't come back, so check slowly.
                    Thread.sleep("api".equals(cfg.mode) ? API_POLL_MS : MANUAL_RETRY_MS);
                }
            } catch (InterruptedException e) {
                break;
            } catch (IllegalArgumentException e) {
                // Bad URL / ID - nothing to retry until the user fixes the setting.
                this.getLogger().warn("Rumble Chat: %s", e.getMessage());
                if (gen == this.generation && cfg.statusMessages) RumbleKoiBridge.postStatus(platform, "Rumble Chat: " + e.getMessage());
                break;
            } catch (Exception e) {
                if (gen != this.generation) break;
                this.getLogger().warn("Rumble Chat error: %s", e.toString());
                try {
                    Thread.sleep(API_POLL_MS);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    private RumbleChatClient.EndReason runChat(Config cfg, String platform, long chatId, String label) throws InterruptedException {
        String icon = null;
        if (cfg.showIcon) {
            icon = this.iconDataUri;
            if (icon == null) {
                this.getLogger().warn("No Rumble icon found in the jar (assets/rumblechat/icon.png); names will show without it.");
            }
        }
        RumbleKoiBridge bridge = new RumbleKoiBridge(platform, chatId, cfg.showHistory, cfg.showRants, cfg.showBadges, icon);
        final boolean[] announced = {
                false
        };

        RumbleChatClient c = new RumbleChatClient(chatId, new RumbleChatClient.Listener() {
            @Override
            public void onConnected(long id) {
                getLogger().info("Connected to Rumble chat %d (%s).", id, label);
                currentChatId = id;
                broadcastDockState();
                if (!announced[0]) {
                    announced[0] = true;
                    if (cfg.statusMessages) RumbleKoiBridge.postStatus(platform, "Connected to Rumble chat: " + label);
                }
            }

            private final java.util.Set<String> seenTypes = new java.util.HashSet<>();

            @Override
            public void onEvent(String type, co.casterlabs.rakurai.json.element.JsonObject data) {
                if (this.seenTypes.add(type)) {
                    getLogger().info("First '%s' event received from Rumble chat.", type);
                }
                bridge.handle(type, data);
            }

            @Override
            public void onDisconnected(String reason) {
                getLogger().warn("Rumble chat %d: %s", chatId, reason);
            }
        });

        this.client = c;
        this.activeConfig = cfg;

        // With a static stream key, restarting OBS makes Rumble start a NEW livestream
        // (new chat) while the old one lingers. Keep checking and switch when that happens.
        java.util.concurrent.ScheduledFuture<?> recheck = null;
        if ("api".equals(cfg.mode)) {
            recheck = RECHECK.scheduleWithFixedDelay(() -> {
                try {
                    this.switchIfNewerStream(cfg, chatId);
                } catch (Throwable t) {
                    this.getLogger().debug("Stream re-check failed: %s", t);
                }
            }, STREAM_RECHECK_SECONDS, STREAM_RECHECK_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        }

        try {
            return c.run();
        } finally {
            if (recheck != null) recheck.cancel(false);
            this.client = null;
            this.currentChatId = 0;
            this.broadcastDockState();
        }
    }

    /* ---------------- */
    /* Dock support     */
    /* ---------------- */

    public long getCurrentChatId() {
        return this.currentChatId;
    }

    /**
     * Asks Rumble which stream is live now; if it's a different one than we're
     * connected to, drops the old connection (the worker then connects to the new one).
     *
     * @return true if a switch was started.
     */
    private boolean switchIfNewerStream(Config cfg, long connectedChatId) throws Exception {
        RumbleLiveStreamApi.LiveInfo live = RumbleLiveStreamApi.findLiveStream(cfg.apiUrl);
        if (live == null || live.chatId == connectedChatId) return false;

        this.getLogger().info("A newer live Rumble stream is up (%s -> chat %d); switching to it.", live.streamIdB36, live.chatId);
        RumbleChatClient c = this.client;
        if (c != null && c.getChatId() == connectedChatId) c.stop();
        return true;
    }

    /**
     * Called when sending hit a closed chat. Re-checks the live stream and waits
     * (up to ~15s) for the connection to move to it.
     *
     * @return the new chat ID, or 0 if there's no other live stream.
     */
    public long refreshLiveStream() throws Exception {
        Config cfg = this.activeConfig;
        long old = this.currentChatId;
        if (cfg == null || !"api".equals(cfg.mode)) return 0;
        if (!this.switchIfNewerStream(cfg, old)) return 0;

        for (int i = 0; i < 60; i++) {
            long now = this.currentChatId;
            if (now > 0 && now != old) return now;
            Thread.sleep(250);
        }
        return 0;
    }

    public RumbleAccount getAccount() {
        return this.account;
    }

    public @Nullable String getIconDataUri() {
        return this.iconDataUri;
    }

    public @Nullable String getDinkDataUri() {
        return this.dinkDataUri;
    }

    private void broadcastDockState() {
        for (RumbleChatDock dock : this.docks) {
            try {
                dock.broadcastState();
            } catch (Throwable ignored) {}
        }
    }

    private static File dataDir() {
        String appData = System.getenv("APPDATA");
        File base = appData != null ? new File(appData) : new File(System.getProperty("user.home"));
        return new File(base, "casterlabs-caffeinated/rumble-chat");
    }

    /** Caffeinated's own UI files (icons, sounds) live in the app jar under this path. */
    private static final String APP_UI = "co/casterlabs/caffeinated/app/ui/html/";

    private static @Nullable InputStream appResource(String path) {
        ClassLoader cl = Caffeinated.getInstance().getClass().getClassLoader();
        return cl == null ? null : cl.getResourceAsStream(APP_UI + path);
    }

    private static @Nullable String loadAppSound(String name) {
        try (InputStream in = appResource("sounds/" + name)) {
            if (in == null) return null;
            return "data:audio/mpeg;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Serves the dock page. Caffeinated calls this with paths like "/dock/chat".
     * - /dock/chat          -> resources/web/dock.html
     * - /dock/icons/X.svg   -> Caffeinated's own platform icon for X (from the app jar);
     *                          for "rumble", your icon wrapped in an SVG.
     */
    @Override
    public @Nullable Pair<String, String> getResource(String resource) throws IOException {
        String path = resource.startsWith("/") ? resource.substring(1) : resource;
        int q = path.indexOf('?');
        if (q != -1) path = path.substring(0, q);

        if (path.equals("dock/chat") || path.equals("dock/chat/")) {
            return readOwn("web/dock.html", "text/html");
        }

        if (path.startsWith("dock/icons/") && path.endsWith(".svg")) {
            String platform = path.substring("dock/icons/".length(), path.length() - 4).toLowerCase();
            if (!platform.matches("[a-z_]+")) return null;

            if (platform.equals("rumble")) {
                if (this.iconDataUri == null) return null;
                String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" viewBox=\"0 0 24 24\">"
                    + "<image width=\"24\" height=\"24\" href=\"" + this.iconDataUri + "\" xlink:href=\"" + this.iconDataUri + "\"/></svg>";
                return new Pair<>(svg, "image/svg+xml");
            }

            try (InputStream in = appResource("images/services/" + platform + "/icon.svg")) {
                if (in == null) return null;
                return new Pair<>(new String(in.readAllBytes(), StandardCharsets.UTF_8), "image/svg+xml");
            }
        }

        if (path.startsWith("dock/") && path.matches("dock/[a-zA-Z0-9_.-]+\\.(js|css)")) {
            String file = path.substring("dock/".length());
            return readOwn("web/" + file, file.endsWith(".js") ? "text/javascript" : "text/css");
        }

        return null;
    }

    private @Nullable Pair<String, String> readOwn(String path, String mime) throws IOException {
        try (InputStream in = RumbleChatPlugin.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return null;
            return new Pair<>(new String(in.readAllBytes(), StandardCharsets.UTF_8), mime);
        }
    }

    /**
     * Platform name used for the chat badge. Both Caffeinated stable and newer
     * builds know RUMBLE; Custom Integration is the fallback / user choice.
     */
    private String resolvePlatform(String tag) {
        return "rumble".equals(tag) ? "RUMBLE" : "CUSTOM_INTEGRATION";
    }
}
