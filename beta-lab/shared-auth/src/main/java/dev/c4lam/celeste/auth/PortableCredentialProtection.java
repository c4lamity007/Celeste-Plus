package dev.c4lam.celeste.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Read-only CSP2 credential protection shared by the normal runtime and isolated beta bootstrap. */
public final class PortableCredentialProtection {
    private static final byte[] MAGIC = {'C', 'S', 'P', '2'};
    private static final byte[] AAD = "Celeste normal credential v2".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_PLAIN = 1024 * 1024;
    private static final String SERVICE = "dev.c4lam.celeste.normal";
    private static final String ACCOUNT = "a39fbbf8e5c344edb39ee82d";
    private PortableCredentialProtection() { }

    /** Uses only the existing current-account key; never creates a key or changes the system store. */
    public static byte[] unprotect(byte[] protectedValue) throws GeneralSecurityException {
        requireEnvelope(protectedValue);
        ProcessBuilder command = new ProcessBuilder(keyCommand(System.getProperty("os.name", "")))
            .redirectError(ProcessBuilder.Redirect.DISCARD);
        byte[] key;
        try { key = readKey(command.start(), 15_000); }
        catch (IOException failure) {
            throw new GeneralSecurityException("System credential support is unavailable; unlock Keychain or install secret-tool for your desktop Secret Service");
        }
        try { return unprotectWithKey(protectedValue, key); }
        finally { Arrays.fill(key, (byte) 0); }
    }

    static List<String> keyCommand(String operatingSystem) throws GeneralSecurityException {
        String os = operatingSystem.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w");
        }
        if (os.contains("linux")) {
            // Distribution-installed secret-tool is resolved through the user's trusted desktop PATH,
            // matching the native launcher and the normal Java credential store. Never invoke a shell.
            return List.of("secret-tool", "lookup", "service", SERVICE, "account", ACCOUNT);
        }
        throw new GeneralSecurityException("This operating system does not support portable Celeste credentials");
    }

    static byte[] readKey(Process process, long timeoutMillis) throws GeneralSecurityException {
        return readKey(process, timeoutMillis, false);
    }

    static byte[] linuxKeyForSave() throws GeneralSecurityException {
        try { return readKey(new ProcessBuilder(keyCommand("linux")).start(), 15_000, true); }
        catch (IOException failure) { throw new GeneralSecurityException("Install secret-tool and unlock this desktop's Secret Service"); }
    }

    static byte[] readKey(Process process, long timeoutMillis, boolean allowMissing) throws GeneralSecurityException {
        FutureTask<byte[]> reader = new FutureTask<>(() -> process.getInputStream().readNBytes(257));
        FutureTask<byte[]> diagnostic = new FutureTask<>(() -> process.getErrorStream().readNBytes(1));
        Thread.ofVirtual().name("Celeste system credential reader").start(reader);
        Thread.ofVirtual().name("Celeste system credential status").start(diagnostic);
        byte[] encoded = null;
        try {
            process.getOutputStream().close();
            encoded = reader.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (encoded.length > 256 || !process.waitFor(1, TimeUnit.SECONDS)) {
                throw new GeneralSecurityException("The system credential key is missing, unavailable, or locked; authenticate in the launcher using the same account");
            }
            // Only the documented empty, diagnostic-free lookup miss can create a key during an explicit save.
            if (allowMissing && process.exitValue() == 1 && encoded.length == 0
                && diagnostic.get(1, TimeUnit.SECONDS).length == 0) return null;
            if (process.exitValue() != 0) throw new GeneralSecurityException("The system credential key is missing, unavailable, or locked; authenticate in the launcher using the same account");
            return decodeKey(encoded);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GeneralSecurityException("System credential lookup was interrupted");
        } catch (IOException | ExecutionException | TimeoutException failure) {
            throw new GeneralSecurityException("System credential lookup failed or timed out; unlock the account's password store");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
            reader.cancel(true);
            diagnostic.cancel(true);
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
        }
    }

    static byte[] decodeKey(byte[] encoded) throws GeneralSecurityException {
        if (encoded == null || encoded.length < 43 || encoded.length > 45) {
            throw new GeneralSecurityException("The system password store returned an invalid credential key");
        }
        String value = new String(encoded, StandardCharsets.US_ASCII);
        if (!value.matches("[A-Za-z0-9_-]{43}(?:\\r?\\n)?")) {
            throw new GeneralSecurityException("The system password store returned an invalid credential key");
        }
        String canonical = value.substring(0, 43);
        try {
            byte[] key = Base64.getUrlDecoder().decode(canonical);
            if (key.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(key).equals(canonical)) {
                Arrays.fill(key, (byte) 0);
                throw new GeneralSecurityException("The system password store returned an invalid credential key");
            }
            return key;
        } catch (IllegalArgumentException invalid) {
            throw new GeneralSecurityException("The system password store returned an invalid credential key");
        }
    }

    static byte[] unprotectWithKey(byte[] protectedValue, byte[] key) throws GeneralSecurityException {
        requireEnvelope(protectedValue);
        if (key == null || key.length != 32) throw new GeneralSecurityException("Invalid system credential key");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, protectedValue, 4, 12));
            cipher.updateAAD(AAD);
            return cipher.doFinal(protectedValue, 16, protectedValue.length - 16);
        } catch (GeneralSecurityException failure) {
            throw new GeneralSecurityException("Credential is not valid for this account or was damaged", failure);
        }
    }

    private static void requireEnvelope(byte[] value) throws GeneralSecurityException {
        if (value == null || value.length < 4 + 12 + 16 + 1 || value.length > 4 + 12 + 16 + MAX_PLAIN
            || !Arrays.equals(MAGIC, Arrays.copyOf(value, 4))) {
            throw new GeneralSecurityException("Credential envelope is invalid");
        }
    }
}
