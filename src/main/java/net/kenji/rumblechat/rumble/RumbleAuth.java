package net.kenji.rumblechat.rumble;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.rakurai.json.element.JsonArray;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonObject;

/**
 * Logs in to Rumble the same way rumble.com's login form does (port of the
 * flow used by the cocorum library):
 *
 * 1. service.php?name=user.get_salts  -> three salts for this username
 * 2. hash the password with them (MD5 "stretch" hashing, see below)
 * 3. service.php?name=user.2fa.first_step -> tells us whether 2FA is needed
 * 4a. no 2FA:  service.php?name=user.login              -> sets the u_s cookie
 * 4b. 2FA:     service.php?name=user.2fa.verify_totp    -> sets the u_s cookie
 *
 * The u_s cookie is the session token used to post chat messages.
 * The plain password is never sent; only the salted hashes are.
 */
public final class RumbleAuth {
    private static final String SERVICE = System.getProperty("rumblechat.service", "https://rumble.com/service.php"); // overridable for tests
    public static final String SESSION_COOKIE = "u_s";

    private RumbleAuth() {}

    /**
     * rumble.com/service.php sits behind Cloudflare's bot check, which compares
     * the client's TLS/HTTP fingerprint with its User-Agent. A Java client that
     * claims to be Chrome is an obvious mismatch, so we try honest identities
     * first and remember whichever one Cloudflare lets through.
     */
    private static final class Strategy {
        final String name;
        final @Nullable String userAgent; // null = Java's own default UA
        final HttpClient.Version version;

        Strategy(String name, @Nullable String userAgent, HttpClient.Version version) {
            this.name = name;
            this.userAgent = userAgent;
            this.version = version;
        }
    }

    private static final String HONEST_UA = "RumbleChat-for-Caffeinated/1.0 (Java; +https://casterlabs.co)";

    private static final Strategy[] STRATEGIES = {
            new Strategy("honest UA, HTTP/1.1", HONEST_UA, HttpClient.Version.HTTP_1_1),
            new Strategy("honest UA, HTTP/2", HONEST_UA, HttpClient.Version.HTTP_2),
            new Strategy("Java default UA, HTTP/1.1", null, HttpClient.Version.HTTP_1_1),
            new Strategy("browser UA, HTTP/1.1", RumbleHttp.USER_AGENT, HttpClient.Version.HTTP_1_1),
    };

    private static volatile @Nullable Strategy working = null;

    /** Cloudflare (or Rumble) refused the request outright. */
    private static final class BlockedException extends IOException {
        private static final long serialVersionUID = 1L;

        BlockedException(String msg) {
            super(msg);
        }
    }

    private static final class Conn {
        final HttpClient http;
        final Strategy strategy;

        Conn(CookieManager cookies, Strategy strategy) {
            this.strategy = strategy;
            this.http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .version(strategy.version)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        }
    }

    /** Runs a login flow with each connection style until one isn't blocked. */
    private interface Flow<T> {
        T run(Conn conn, CookieManager cookies) throws IOException, InterruptedException;
    }

    private static <T> T withFallback(Flow<T> flow) throws IOException, InterruptedException {
        java.util.List<Strategy> order = new java.util.ArrayList<>();
        if (working != null) order.add(working);
        for (Strategy st : STRATEGIES) if (st != working) order.add(st);

        StringBuilder tried = new StringBuilder();
        for (Strategy st : order) {
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            try {
                T result = flow.run(new Conn(cookies, st), cookies);
                working = st;
                return result;
            } catch (BlockedException e) {
                if (tried.length() > 0) tried.append("; ");
                tried.append(st.name).append(" -> ").append(e.getMessage());
            }
        }
        throw new IOException("Rumble's Cloudflare protection blocked the plugin's login requests (" + tried
            + "). Use the 'Browser session cookie' option instead; it doesn't need this login step.");
    }

    public static final class LoginResult {
        /** Set when login finished. */
        public final @Nullable String sessionToken;
        /** Set when Rumble wants a 2FA code. */
        public final @Nullable String totpId;
        public final @Nullable String userKey;
        /** 2FA methods enabled on the account: "authenticator", "email", "phone". */
        public final java.util.List<String> options;

        private LoginResult(String token, String totpId, String userKey, java.util.List<String> options) {
            this.sessionToken = token;
            this.totpId = totpId;
            this.userKey = userKey;
            this.options = options;
        }

        public boolean needs2fa() {
            return this.sessionToken == null && this.totpId != null;
        }
    }

    public static LoginResult login(String username, String password) throws IOException, InterruptedException {
        return withFallback((http, cookies) -> loginWith(http, cookies, username, password));
    }

    private static LoginResult loginWith(Conn http, CookieManager cookies, String username, String password) throws IOException, InterruptedException {

        // 1. Salts
        Map<String, String> saltForm = new LinkedHashMap<>();
        saltForm.put("username", username);
        JsonObject saltsRes = post(http, "user.get_salts&response_type=session", saltForm);
        JsonArray salts = Json.arr(Json.obj(saltsRes, "data"), "salts");
        if (Json.size(salts) < 3) {
            throw new IOException("Rumble didn't return login salts (wrong username?).");
        }
        String[] s = new String[3];
        int i = 0;
        for (JsonElement e : salts) {
            if (i >= 3) break;
            s[i++] = Json.elemStr(e);
        }

        // 2. Hash
        String hashes = String.join(",", passwordHashes(password, s));

        // 3. 2FA check
        Map<String, String> firstStep = new LinkedHashMap<>();
        firstStep.put("legacy_password", "1");
        firstStep.put("login", username);
        firstStep.put("password", hashes);
        firstStep.put("redirect_uri", "https://rumble.com");
        JsonObject fs = post(http, "user.2fa.first_step", firstStep);
        JsonObject fsData = Json.obj(fs, "data");
        String totpId = Json.str(fsData, "totp_id");
        if (totpId != null && !totpId.isEmpty() && !totpId.equals("null")) {
            java.util.List<String> options = new java.util.ArrayList<>();
            JsonObject opts = Json.obj(fsData, "options");
            if (opts != null) {
                for (Map.Entry<String, JsonElement> e : opts.entrySet()) {
                    String v = Json.elemStr(e.getValue());
                    if ("true".equalsIgnoreCase(v) || "1".equals(v)) options.add(e.getKey());
                }
            }
            return new LoginResult(null, totpId, Json.str(fsData, "user_key"), options);
        }

        // 4a. Login
        Map<String, String> loginForm = new LinkedHashMap<>();
        loginForm.put("username", username);
        loginForm.put("password_hashes", hashes);
        JsonObject res = post(http, "user.login", loginForm);

        String token = sessionCookie(cookies);
        if (token == null) token = Json.str(Json.obj(res, "data"), "session");
        if (token == null || token.isEmpty() || token.equals("false")) {
            throw new IOException("Rumble login failed (wrong password?).");
        }
        return new LoginResult(token, null, null, java.util.Collections.emptyList());
    }

    public static String verify2fa(String totpId, @Nullable String userKey, String code) throws IOException, InterruptedException {
        return withFallback((http, cookies) -> verify2faWith(http, cookies, totpId, userKey, code));
    }

    /**
     * Asks Rumble to send a 2FA code by email ("email") or SMS ("phone").
     * Authenticator apps make their own codes, so nothing needs sending for those.
     *
     * @return where it was sent, partly hidden by Rumble (e.g. "k***@gmail.com").
     */
    public static @Nullable String request2faCode(String totpId, @Nullable String userKey, String option) throws IOException, InterruptedException {
        final String service;
        switch (option) {
            case "email":
                service = "user.2fa.request_email_code";
                break;
            case "phone":
                service = "user.2fa.request_sms_code";
                break;
            default:
                return null;
        }
        return withFallback((http, cookies) -> {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("totp_id", totpId);
            if (userKey != null) form.put("user_key", userKey);
            JsonObject res = post(http, service, form);
            return Json.str(Json.obj(res, "data"), "sent_to", "your " + (option.equals("phone") ? "phone" : "email"));
        });
    }

    private static String verify2faWith(Conn http, CookieManager cookies, String totpId, @Nullable String userKey, String code) throws IOException, InterruptedException {

        Map<String, String> form = new LinkedHashMap<>();
        form.put("code", code.trim());
        form.put("redirect_url", "https://rumble.com");
        form.put("totp_id", totpId);
        if (userKey != null) form.put("user_key", userKey);
        post(http, "user.2fa.verify_totp", form);

        String token = sessionCookie(cookies);
        if (token == null || token.isEmpty()) {
            throw new IOException("Rumble didn't accept the 2FA code.");
        }
        return token;
    }

    /* ---------------- */
    /* Hashing          */
    /* ---------------- */

    /** Rumble's login hashing (same as cocorum's calc_password_hashes). */
    static String[] passwordHashes(String password, String[] salts) {
        String stretched1 = stretch(password, salts[0], 128);
        String final1 = md5(stretched1 + salts[1]);
        String stretched2 = stretch(password, salts[2], 128);
        return new String[] {
                final1, stretched2, salts[1]
        };
    }

    static String stretch(String password, String salt, int iterations) {
        String current = md5(salt + password);
        for (int i = 0; i < iterations; i++) {
            current = md5(current + password);
        }
        return current;
    }

    static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /* ---------------- */
    /* HTTP             */
    /* ---------------- */

    private static JsonObject post(Conn conn, String name, Map<String, String> form) throws IOException, InterruptedException {
        String body = form.entrySet().stream()
            .map((e) -> enc(e.getKey()) + "=" + enc(e.getValue()))
            .collect(Collectors.joining("&"));

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(SERVICE + "?name=" + name));
        if (conn.strategy.userAgent != null) req.header("User-Agent", conn.strategy.userAgent);

        HttpResponse<String> res = conn.http.send(
            req
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "https://rumble.com")
                .header("Referer", "https://rumble.com/")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        // Errors never include the form body (it contains password hashes).
        String shortName = name.contains("&") ? name.substring(0, name.indexOf('&')) : name;
        if (res.statusCode() == 403 || res.statusCode() == 429 || res.statusCode() == 503) {
            throw new BlockedException("HTTP " + res.statusCode() + (looksLikeCloudflare(res) ? " (Cloudflare)" : ""));
        }
        if (res.statusCode() != 200) {
            throw new IOException("Rumble login step '" + shortName + "' failed: HTTP " + res.statusCode() + reason(res.body()));
        }
        JsonObject json = Json.parseObject(res.body());
        if (json == null) {
            if (looksLikeCloudflare(res)) throw new BlockedException("Cloudflare challenge page");
            throw new IOException("Rumble login step '" + shortName + "' returned something unexpected.");
        }
        JsonObject data = Json.obj(json, "data");
        if (data != null && data.containsKey("success") && !Json.bool(data, "success")) {
            String err = Json.str(data, "error");
            if (err == null) err = Json.str(Json.obj(json, "error"), "message");
            throw new IOException("Rumble rejected login step '" + name + "'" + (err == null ? "" : ": " + err));
        }
        return json;
    }

    /** Rumble's own error text from a failed response (never contains what we sent). */
    private static String reason(@Nullable String body) {
        if (body == null || body.isEmpty()) return "";
        JsonObject json = Json.parseObject(body);
        if (json != null) {
            JsonObject data = Json.obj(json, "data");
            String msg = Json.str(data, "error");
            if (msg == null) msg = Json.str(data, "message");
            if (msg == null) msg = Json.str(Json.obj(json, "error"), "message");
            if (msg == null) msg = Json.str(json, "error");
            if (msg == null) msg = Json.str(json, "message");
            JsonArray errors = Json.arr(json, "errors");
            if (msg == null && errors != null) {
                for (JsonElement e : errors) {
                    if (e.isJsonObject()) msg = Json.str(e.getAsObject(), "message");
                    if (msg != null) break;
                }
            }
            if (msg != null) return " (" + msg + ")";
        }
        String text = body.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? "" : " (" + (text.length() > 160 ? text.substring(0, 160) + "…" : text) + ")";
    }

    private static boolean looksLikeCloudflare(HttpResponse<String> res) {
        String server = res.headers().firstValue("server").orElse("").toLowerCase();
        String body = res.body() == null ? "" : res.body();
        return server.contains("cloudflare") || res.headers().firstValue("cf-mitigated").isPresent()
            || body.contains("Just a moment") || body.contains("Attention Required") || body.contains("cf-chl");
    }

    private static @Nullable String sessionCookie(CookieManager cookies) {
        for (HttpCookie c : cookies.getCookieStore().getCookies()) {
            if (SESSION_COOKIE.equals(c.getName()) && !c.getValue().isEmpty()) {
                return c.getValue();
            }
        }
        return null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
