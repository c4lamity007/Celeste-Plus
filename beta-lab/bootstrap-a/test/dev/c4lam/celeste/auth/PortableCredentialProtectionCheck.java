package dev.c4lam.celeste.auth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Public test material only: no system password-store access, real credentials, or game instance. */
public final class PortableCredentialProtectionCheck {
    private static int checks;
    private PortableCredentialProtectionCheck() { }

    public static void run() throws Exception {
        byte[] key = new byte[32]; for (int index = 0; index < key.length; index++) key[index] = (byte) index;
        byte[] envelope = HexFormat.of().parseHex("43535032202122232425262728292a2bbc55c80309fb686b6e5c32a1b36c959bbc2cccfff5e5078b0282057325aa38600dfa9076eb9ec02829d82e3ff1fd07a85741d550c2");
        check(Arrays.equals(PortableCredentialProtection.unprotectWithKey(envelope, key),
            "nonsecret portable credential fixture".getBytes(StandardCharsets.UTF_8)), "normal Rust/Java CSP2 vector interoperates");
        for (int offset : new int[] {0, 4, 16, envelope.length - 1}) {
            byte[] changed = envelope.clone(); changed[offset] ^= 1;
            reject(() -> PortableCredentialProtection.unprotectWithKey(changed, key));
        }
        reject(() -> PortableCredentialProtection.unprotectWithKey(envelope, new byte[32]));
        for (byte[] invalid : new byte[][] {new byte[0], new byte[32], new byte[1024 * 1024 + 33]}) {
            reject(() -> PortableCredentialProtection.unprotect(invalid)); // rejected before any keyring lookup
        }
        String text = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        for (String ending : List.of("", "\n", "\r\n")) {
            check(Arrays.equals(PortableCredentialProtection.decodeKey((text + ending).getBytes(StandardCharsets.US_ASCII)), key), "canonical key permits ordinary command line ending");
        }
        for (String invalid : List.of(text + "=", text + "\n\n", " " + text, text + " ", text + "\r", "A".repeat(42) + "B")) {
            reject(() -> PortableCredentialProtection.decodeKey(invalid.getBytes(StandardCharsets.US_ASCII)));
        }
        check(PortableCredentialProtection.keyCommand("Mac OS X").equals(List.of("/usr/bin/security", "find-generic-password", "-s",
            "dev.c4lam.celeste.normal", "-a", "a39fbbf8e5c344edb39ee82d", "-w")), "macOS uses exact shared account without shell");
        check(PortableCredentialProtection.keyCommand("Linux").equals(List.of("secret-tool", "lookup", "service",
            "dev.c4lam.celeste.normal", "account", "a39fbbf8e5c344edb39ee82d")), "Linux uses exact shared account without shell");
        reject(() -> PortableCredentialProtection.keyCommand("Windows 11"));
        reject(() -> PortableCredentialProtection.keyCommand("unsupported"));
        Process valid = child("key");
        check(Arrays.equals(PortableCredentialProtection.readKey(valid, 10_000), key), "bounded subprocess consumes synthetic key output");
        check(PortableCredentialProtection.readKey(child("missing"), 10_000, true) == null,
            "only an explicit empty lookup miss may create a key during save");
        reject(() -> PortableCredentialProtection.readKey(child("missing"), 10_000));
        reject(() -> PortableCredentialProtection.readKey(child("locked"), 10_000, true));
        for (String mode : List.of("oversize", "failure", "slow")) {
            Process process = child(mode);
            reject(() -> PortableCredentialProtection.readKey(process, mode.equals("slow") ? 250 : 10_000));
            check(process.waitFor(3, TimeUnit.SECONDS), "failed/oversized/timed-out helper cannot remain running");
        }
        System.out.println("PASS portable credential protection: " + checks + " synthetic CSP2/key-format/process-bound checks; no keyring access");
    }

    /** Deliberately plain subprocess fixtures; never invoke the actual operating-system credential tools. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("fixture mode required");
        switch (args[0]) {
            case "key" -> {
                byte[] key = new byte[32]; for (int index = 0; index < key.length; index++) key[index] = (byte) index;
                System.out.println(Base64.getUrlEncoder().withoutPadding().encodeToString(key));
            }
            case "oversize" -> System.out.print("x".repeat(257));
            case "failure" -> { System.err.println("synthetic private diagnostic must not escape"); System.exit(2); }
            case "missing" -> System.exit(1);
            case "locked" -> { System.err.println("synthetic private diagnostic must not escape"); System.exit(1); }
            case "slow" -> Thread.sleep(30_000);
            default -> throw new IllegalArgumentException("unknown fixture mode");
        }
    }
    private static Process child(String mode) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
            PortableCredentialProtectionCheck.class.getName(), mode).start();
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void reject(Action action) throws Exception {
        try { action.run(); } catch (GeneralSecurityException expected) {
            check(!expected.getMessage().contains("synthetic private diagnostic"), "failure contains fixed diagnostics only"); return;
        }
        throw new AssertionError("invalid portable protection was accepted");
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
