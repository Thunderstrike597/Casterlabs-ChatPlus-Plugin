package net.kenji.rumblechat.rumble;

import java.net.http.HttpClient;
import java.time.Duration;

/** Shared HTTP client + headers. Rumble rejects requests without a browser-ish UA. */
public final class RumbleHttp {
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

    public static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private RumbleHttp() {}
}
