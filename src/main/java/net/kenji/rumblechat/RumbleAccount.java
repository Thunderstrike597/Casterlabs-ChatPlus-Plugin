package net.kenji.rumblechat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.rakurai.json.element.JsonObject;
import net.kenji.rumblechat.rumble.Json;
import net.kenji.rumblechat.rumble.RumbleAuth;
import net.kenji.rumblechat.rumble.RumbleSender;
import net.kenji.rumblechat.secure.SecureStore;

/**
 * The Rumble login used for sending chat messages.
 *
 * Modes:
 * - off:      sending to Rumble disabled.
 * - cookie:   the user pastes their rumble.com "u_s" session cookie.
 * - password: the user gives username + password; the plugin logs in itself
 *             and logs in again when the session expires.
 *
 * Secrets (cookie, password, session) are encrypted with Windows DPAPI before
 * being written to account.json, and never logged.
 */
public final class RumbleAccount {
    private final File file;
    private final BiConsumer<String, Throwable> log;

    private String mode = "off";
    private String username = "";
    private String encCookie = "";
    private String encPassword = "";
    private String encSession = "";

    // Decrypted session kept in memory only.
    private @Nullable String sessionCache;

    // Pending 2FA (in memory only).
    private @Nullable String pendingTotpId;
    private @Nullable String pendingUserKey;
    private java.util.List<String> pending2faOptions = java.util.Collections.emptyList();
    private @Nullable String codeSentTo;

    private String lastMessage = "";

    public RumbleAccount(File dataDir, BiConsumer<String, Throwable> log) {
        this.file = new File(dataDir, "account.json");
        this.log = log;
        this.load();
    }

    /* ---------------- */
    /* State for the UI */
    /* ---------------- */

    public synchronized Map<String, Object> status() {
        Map<String, Object> s = JsonOut.obj();
        s.put("mode", this.mode);
        s.put("username", this.username);
        s.put("secure_supported", SecureStore.isSupported());
        s.put("has_cookie", !this.encCookie.isEmpty());
        s.put("has_password", !this.encPassword.isEmpty());
        s.put("needs_2fa", this.pendingTotpId != null);
        s.put("twofa_options", new java.util.ArrayList<Object>(this.pending2faOptions));
        s.put("code_sent_to", this.codeSentTo);
        s.put("ready", this.isReady());
        s.put("message", this.lastMessage);
        return s;
    }

    public synchronized boolean isReady() {
        switch (this.mode) {
            case "cookie":
                return !this.encCookie.isEmpty();
            case "password":
                return !this.encPassword.isEmpty() && this.pendingTotpId == null;
            default:
                return false;
        }
    }

    /* ---------------- */
    /* Actions          */
    /* ---------------- */

    public synchronized void setOff() {
        this.mode = "off";
        this.lastMessage = "Sending to Rumble is off.";
        this.save();
    }

    public synchronized void saveCookie(String cookie) throws IOException {
        String c = cookie.trim();
        if (c.startsWith(RumbleAuth.SESSION_COOKIE + "=")) c = c.substring(RumbleAuth.SESSION_COOKIE.length() + 1);
        if (c.endsWith(";")) c = c.substring(0, c.length() - 1);
        if (c.isEmpty()) throw new IOException("The cookie value is empty.");

        this.encCookie = SecureStore.encrypt(c);
        this.mode = "cookie";
        this.sessionCache = c;
        this.lastMessage = "Session cookie saved (encrypted).";
        this.save();
    }

    public synchronized void savePassword(String username, String password) throws IOException, InterruptedException {
        if (username.trim().isEmpty() || password.isEmpty()) throw new IOException("Enter both username and password.");
        String user = username.trim();
        String enc = SecureStore.encrypt(password);

        // Try the new login FIRST. If Rumble rejects it, the existing (working) login is left untouched.
        RumbleAuth.LoginResult r = RumbleAuth.login(user, password);

        this.username = user;
        this.encPassword = enc;
        this.mode = "password";
        this.save();
        this.handleLoginResult(r);
    }

    /** Asks Rumble to email/text the 2FA code. */
    public synchronized void request2faCode(String option) throws IOException, InterruptedException {
        if (this.pendingTotpId == null) throw new IOException("Rumble isn't waiting for a 2FA code.");
        this.codeSentTo = RumbleAuth.request2faCode(this.pendingTotpId, this.pendingUserKey, option);
        this.lastMessage = this.codeSentTo == null
            ? "Open your authenticator app and enter the Rumble code."
            : "Code sent to " + this.codeSentTo + ". Enter it below.";
    }

    public synchronized void submit2fa(String code) throws IOException, InterruptedException {
        if (this.pendingTotpId == null) throw new IOException("Rumble isn't waiting for a 2FA code.");
        String token = RumbleAuth.verify2fa(this.pendingTotpId, this.pendingUserKey, code);
        this.clear2fa();
        this.storeSession(token);
        this.lastMessage = "Logged in as " + this.username + ".";
    }

    public synchronized void forget() {
        this.mode = "off";
        this.username = "";
        this.encCookie = "";
        this.encPassword = "";
        this.encSession = "";
        this.sessionCache = null;
        this.clear2fa();
        this.lastMessage = "Rumble login removed.";
        this.save();
    }

    /** Sends a chat message, logging in again once if the session expired (password mode). */
    public void send(long chatId, String text) throws IOException, InterruptedException {
        String token;
        synchronized (this) {
            token = this.currentToken();
        }
        try {
            RumbleSender.send(chatId, token, text);
        } catch (RumbleSender.SessionExpiredException e) {
            synchronized (this) {
                if (!"password".equals(this.mode)) {
                    this.lastMessage = "Your Rumble session cookie has expired. Paste a new one.";
                    throw new IOException(this.lastMessage);
                }
                this.log.accept("Rumble session expired, logging in again.", null);
                this.sessionCache = null;
                this.encSession = "";
                this.loginWithPassword(SecureStore.decrypt(this.encPassword));
                token = this.currentToken();
            }
            RumbleSender.send(chatId, token, text);
        }
    }

    /* ---------------- */
    /* Internals        */
    /* ---------------- */

    private String currentToken() throws IOException, InterruptedException {
        if (this.sessionCache != null) return this.sessionCache;

        switch (this.mode) {
            case "cookie": {
                String c = SecureStore.decrypt(this.encCookie);
                if (c == null) throw new IOException("No Rumble session cookie saved.");
                return this.sessionCache = c;
            }
            case "password": {
                if (this.pendingTotpId != null) {
                    throw new IOException("Rumble needs your 2FA code (dock settings → Rumble account).");
                }
                String s = SecureStore.decrypt(this.encSession);
                if (s != null && !s.isEmpty()) return this.sessionCache = s;
                this.loginWithPassword(SecureStore.decrypt(this.encPassword));
                if (this.sessionCache == null) throw new IOException(this.lastMessage);
                return this.sessionCache;
            }
            default:
                throw new IOException("Sending to Rumble is off. Set up your Rumble account in the dock settings.");
        }
    }

    private void loginWithPassword(@Nullable String password) throws IOException, InterruptedException {
        if (password == null || password.isEmpty()) throw new IOException("No Rumble password saved.");
        this.handleLoginResult(RumbleAuth.login(this.username, password));
    }

    private void handleLoginResult(RumbleAuth.LoginResult r) throws IOException, InterruptedException {
        this.clear2fa();
        if (r.needs2fa()) {
            this.pendingTotpId = r.totpId;
            this.pendingUserKey = r.userKey;
            this.pending2faOptions = r.options;
            this.codeSentTo = null;
            this.lastMessage = "Rumble needs a 2FA code.";
            // If email/SMS is the only way to get a code, ask Rumble to send it right away.
            if (!r.options.contains("authenticator") && r.options.size() == 1) {
                try {
                    this.request2faCode(r.options.get(0));
                } catch (IOException e) {
                    this.log.accept("Couldn't request the Rumble 2FA code: " + e.getMessage(), null);
                }
            }
            return;
        }
        this.storeSession(r.sessionToken);
        this.lastMessage = "Logged in as " + this.username + ".";
    }

    private void clear2fa() {
        this.pendingTotpId = null;
        this.pendingUserKey = null;
        this.pending2faOptions = java.util.Collections.emptyList();
        this.codeSentTo = null;
    }

    private void storeSession(String token) throws IOException {
        this.sessionCache = token;
        this.encSession = SecureStore.encrypt(token);
        this.save();
    }

    private void load() {
        try {
            if (!this.file.isFile()) return;
            JsonObject o = Json.parseObject(new String(Files.readAllBytes(this.file.toPath()), StandardCharsets.UTF_8));
            if (o == null) return;
            this.mode = Json.str(o, "mode", "off");
            this.username = Json.str(o, "username", "");
            this.encCookie = Json.str(o, "cookie", "");
            this.encPassword = Json.str(o, "password", "");
            this.encSession = Json.str(o, "session", "");

            if ("password".equals(this.mode) && !this.encSession.isEmpty()) {
                this.lastMessage = "Logged in as " + this.username + " (saved login).";
            } else if ("cookie".equals(this.mode) && !this.encCookie.isEmpty()) {
                this.lastMessage = "Using your saved session cookie.";
            }
        } catch (Exception e) {
            this.log.accept("Couldn't read Rumble account file: " + e, null);
        }
    }

    private void save() {
        try {
            Map<String, Object> o = JsonOut.obj();
            o.put("_note", "Secrets below are encrypted with Windows DPAPI for this Windows user only.");
            o.put("mode", this.mode);
            o.put("username", this.username);
            o.put("cookie", this.encCookie);
            o.put("password", this.encPassword);
            o.put("session", this.encSession);
            this.file.getParentFile().mkdirs();
            Files.write(this.file.toPath(), JsonOut.write(o).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            this.log.accept("Couldn't save Rumble account file: " + e, null);
        }
    }
}
