package net.kenji.rumblechat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

import org.jetbrains.annotations.Nullable;

/**
 * Loads the Rumble icon shown before Rumble usernames.
 *
 * Caffeinated draws platform icons from images bundled inside the app, and it
 * has none for Rumble. Badges, however, are plain image URLs drawn right before
 * the name, so the icon is added as the first badge, embedded as a data: URI.
 *
 * Put your image in src/main/resources/assets/rumblechat/ as icon.png (or
 * icon.svg / icon.webp / icon.gif / icon.jpg) before building.
 */
final class RumbleIcon {
    private static final String[][] CANDIDATES = {
            { "assets/rumblechat/icon.png", "image/png" },
            { "assets/rumblechat/icon.svg", "image/svg+xml" },
            { "assets/rumblechat/icon.webp", "image/webp" },
            { "assets/rumblechat/icon.gif", "image/gif" },
            { "assets/rumblechat/icon.jpg", "image/jpeg" },
    };

    private RumbleIcon() {}

    /** @return the icon as a data: URI, or null if no icon file was bundled. */
    static @Nullable String load() {
        ClassLoader cl = RumbleIcon.class.getClassLoader();
        for (String[] c : CANDIDATES) {
            try (InputStream in = cl.getResourceAsStream(c[0])) {
                if (in == null) continue;
                byte[] bytes = in.readAllBytes();
                if (bytes.length == 0) continue;
                return "data:" + c[1] + ";base64," + Base64.getEncoder().encodeToString(bytes);
            } catch (IOException ignored) {}
        }
        return null;
    }
}
