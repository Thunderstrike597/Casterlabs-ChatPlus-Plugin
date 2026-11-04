package net.kenji.rumblechat.secure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.Nullable;

/**
 * Encrypts secrets with Windows DPAPI (the same protection Windows uses for
 * saved Wi-Fi and browser passwords). Only the same Windows user on the same
 * PC can decrypt the result.
 *
 * Java has no built-in DPAPI binding, so this calls PowerShell's
 * ConvertFrom-SecureString / ConvertTo-SecureString, which are DPAPI-backed.
 * The secret is passed through stdin, never on the command line (command lines
 * are visible to other programs).
 */
public final class SecureStore {
    private static final String PREAMBLE = "[Console]::InputEncoding=[Text.Encoding]::UTF8;[Console]::OutputEncoding=[Text.Encoding]::UTF8;$s=[Console]::In.ReadToEnd();";

    private static final String ENCRYPT = PREAMBLE
        + "[Console]::Out.Write((ConvertTo-SecureString -String $s -AsPlainText -Force | ConvertFrom-SecureString))";

    private static final String DECRYPT = PREAMBLE
        + "$ss=ConvertTo-SecureString -String $s.Trim();"
        + "$p=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($ss);"
        + "try{[Console]::Out.Write([Runtime.InteropServices.Marshal]::PtrToStringBSTR($p))}"
        + "finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p)}";

    private SecureStore() {}

    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** @return an opaque encrypted string, safe to write to disk. */
    public static String encrypt(String secret) throws IOException {
        if (!isSupported()) throw new IOException("Secure storage needs Windows (DPAPI).");
        if (secret == null || secret.isEmpty()) return "";
        String out = run(ENCRYPT, secret).trim();
        if (out.isEmpty()) throw new IOException("Encryption returned nothing.");
        return out;
    }

    public static @Nullable String decrypt(@Nullable String blob) throws IOException {
        if (blob == null || blob.isEmpty()) return null;
        if (!isSupported()) throw new IOException("Secure storage needs Windows (DPAPI).");
        return run(DECRYPT, blob);
    }

    private static String run(String script, String stdin) throws IOException {
        Process p = new ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script
        ).redirectErrorStream(false).start();

        try (OutputStream os = p.getOutputStream()) {
            os.write(stdin.getBytes(StandardCharsets.UTF_8));
        }

        byte[] out = readAll(p.getInputStream());
        byte[] err = readAll(p.getErrorStream());

        try {
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("PowerShell timed out.");
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.");
        }

        if (p.exitValue() != 0) {
            // Never include stdin in errors; stderr only contains PowerShell's message.
            String msg = new String(err, StandardCharsets.UTF_8).trim();
            throw new IOException("PowerShell failed: " + (msg.length() > 300 ? msg.substring(0, 300) : msg));
        }

        String s = new String(out, StandardCharsets.UTF_8);
        // Strip a UTF-8 BOM if PowerShell added one.
        if (s.startsWith("﻿")) s = s.substring(1);
        return s;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
