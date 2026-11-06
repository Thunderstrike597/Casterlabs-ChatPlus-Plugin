package net.kenji.rumblechat.rumble;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns whatever the user pasted (popup chat URL, embed URL, watch-page URL or a
 * raw ID) into the base-10 numeric ID that Rumble's chat endpoint wants.
 *
 * Rumble uses two encodings for the same number: base 36 in most URLs and APIs
 * ("6abc12"), base 10 for the chat server ("123456789").
 */
public final class RumbleIds {
    private static final Pattern POPUP = Pattern.compile("rumble\\.com/chat/popup/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMBED = Pattern.compile("rumble\\.com/embed/v([0-9a-z]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WATCH = Pattern.compile("rumble\\.com/(v[0-9a-z]+)(?:-[^/?#]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ONLY_DIGITS = Pattern.compile("\\d+");
    private static final Pattern BASE36 = Pattern.compile("[0-9a-z]+", Pattern.CASE_INSENSITIVE);

    private RumbleIds() {}

    public static long base36To10(String b36) {
        return Long.parseLong(b36.toLowerCase(), 36);
    }

    /**
     * @return the base-10 chat/stream ID.
     * @throws IllegalArgumentException if the input can't be understood.
     */
    public static long resolveChatId(String input, HttpClient http) throws IOException, InterruptedException {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("No stream URL or ID entered.");
        }
        String text = input.trim();

        Matcher m = POPUP.matcher(text);
        if (m.find()) return Long.parseLong(m.group(1));

        m = EMBED.matcher(text);
        if (m.find()) return base36To10(m.group(1));

        m = WATCH.matcher(text);
        if (m.find()) {
            // Watch-page slugs ("v6abc12-my-stream.html") are NOT the real ID, Rumble's
            // oEmbed endpoint maps them to the embed ID.
            return base36To10(lookupEmbedId(m.group(1), http));
        }

        if (ONLY_DIGITS.matcher(text).matches()) return Long.parseLong(text);
        if (BASE36.matcher(text).matches()) return base36To10(text);

        throw new IllegalArgumentException("Couldn't find a Rumble stream ID in: " + text);
    }

    private static final Pattern EMBED_IN_HTML = Pattern.compile("rumble\\.com/embed/v([0-9a-z]+)", Pattern.CASE_INSENSITIVE);

    private static String lookupEmbedId(String vslug, HttpClient http) throws IOException, InterruptedException {
        String lookup = "https://rumble.com/" + vslug + "-.html";
        String url = "https://rumble.com/api/Media/oembed.json?url=" + URLEncoder.encode(lookup, StandardCharsets.UTF_8);

        HttpResponse<String> res = http.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", RumbleHttp.USER_AGENT)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        if (res.statusCode() == 404) {
            throw new IllegalArgumentException("Rumble has no video at " + vslug);
        }
        if (res.statusCode() != 200) {
            throw new IOException("Rumble oEmbed lookup failed with HTTP " + res.statusCode());
        }

        Matcher m = EMBED_IN_HTML.matcher(res.body());
        if (!m.find()) {
            throw new IOException("Rumble's oEmbed reply for " + vslug + " had no embed ID");
        }
        return m.group(1);
    }

}
