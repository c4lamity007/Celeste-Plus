package dev.c4lam.celeste.auth;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.KnownFolders;
import com.sun.jna.platform.win32.Shell32Util;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import net.fabricmc.loader.api.FabricLoader;

final class CredentialStore {
    // The native launcher's stable protocol channel; independent of payload releases.
    private static final String LAUNCHER_CHANNEL = "a39fbbf8e5c344edb39ee82d";
    private static final byte[] MAGIC = {'C', 'S', 'T', '1'};
    private static final byte[] PORTABLE_MAGIC = {'C', 'S', 'P', '2'};
    private static final byte[] PORTABLE_AAD = "Celeste normal credential v2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_SERVICE = "dev.c4lam.celeste.normal".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_ACCOUNT = LAUNCHER_CHANNEL.getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_PLAINTEXT_BYTES = 1024 * 1024;
    private static final Gson GSON = new Gson();
    private final Path path;
    private final String product;

    CredentialStore() {
        this("celeste");
    }

    CredentialStore(String product) {
        Path auth = FabricLoader.getInstance().getConfigDir().resolve("celeste").resolve("auth");
        if ("celeste_plus".equals(product)) auth = auth.resolve("celeste_plus");
        else if (!"celeste".equals(product)) throw new IllegalArgumentException("Unknown license product");
        this.path = auth.resolve("device.bin");
        this.product = product;
    }

    CredentialStore(Path path) {
        this.path = path;
        this.product = "celeste";
    }

    static Path launcherActivationPath(Path localAppData) {
        if (!localAppData.isAbsolute()) throw new IllegalArgumentException("User data path must be absolute");
        return localAppData.resolve("Celeste").resolve("auth").resolve(LAUNCHER_CHANNEL)
            .resolve("celeste-active.bin");
    }

    static CredentialStore launcherActivation() throws GeneralSecurityException {
        try {
            return new CredentialStore(launcherActivationPath(currentUserDataRoot()));
        } catch (RuntimeException exception) {
            throw new GeneralSecurityException("Could not locate this account's Celeste activation", exception);
        }
    }

    private static Path currentUserDataRoot() throws GeneralSecurityException {
        if (isWindows()) {
            // Match the launcher's current-account Known Folder lookup, including redirected AppData.
            return Path.of(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_LocalAppData));
        }
        if (isLinux()) {
            String xdg = System.getenv("XDG_DATA_HOME");
            if (xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()) return Path.of(xdg);
        }
        String home = System.getenv("HOME");
        if (home == null || home.isBlank() || !Path.of(home).isAbsolute()) {
            throw new GeneralSecurityException("This account's HOME is unavailable");
        }
        if (isMac()) return Path.of(home, "Library", "Application Support");
        if (isLinux()) return Path.of(home, ".local", "share");
        throw new GeneralSecurityException("This operating system is unsupported");
    }

    boolean present() {
        // A damaged local path must fail locally, not silently select another identity.
        return Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    boolean exists() {
        return Files.isRegularFile(path);
    }

    InstallCredential load() throws IOException, GeneralSecurityException {
        byte[] stored = Files.readAllBytes(path);
        if (stored.length <= MAGIC.length || !Arrays.equals(MAGIC, Arrays.copyOf(stored, MAGIC.length))) {
            throw new GeneralSecurityException("Credential envelope is invalid");
        }
        byte[] protectedValue = Arrays.copyOfRange(stored, MAGIC.length, stored.length);
        byte[] plain;
        if (isWindows()) {
            try {
                plain = Crypt32Util.cryptUnprotectData(protectedValue);
            } catch (RuntimeException exception) {
                throw new GeneralSecurityException("Credential is not valid for this Windows account", exception);
            }
        } else {
            plain = portableUnprotect(protectedValue);
        }
        try {
            InstallCredential credential = GSON.fromJson(new String(plain, StandardCharsets.UTF_8), InstallCredential.class);
            if (credential == null) {
                throw new GeneralSecurityException("Credential is empty");
            }
            credential.validate();
            return credential;
        } catch (JsonParseException exception) {
            throw new GeneralSecurityException("Credential is damaged", exception);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    void save(InstallCredential credential) throws IOException, GeneralSecurityException {
        credential.validate();
        byte[] plain = GSON.toJson(credential).getBytes(StandardCharsets.UTF_8);
        byte[] protectedValue;
        try {
            if (isWindows()) {
                try {
                    protectedValue = Crypt32Util.cryptProtectData(plain);
                } catch (RuntimeException exception) {
                    throw new GeneralSecurityException("Windows could not protect the credential", exception);
                }
            } else {
                protectedValue = portableProtect(plain);
            }
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        boolean portable = !isWindows();
        prepareDirectory(path.getParent(), portable);
        Path temporary = portable
            ? privateTemporaryFile(path.getParent())
            : path.resolveSibling(path.getFileName() + ".tmp");
        byte[] stored = new byte[MAGIC.length + protectedValue.length];
        System.arraycopy(MAGIC, 0, stored, 0, MAGIC.length);
        System.arraycopy(protectedValue, 0, stored, MAGIC.length, protectedValue.length);
        try {
            Files.write(temporary, stored);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (portable) Files.deleteIfExists(temporary);
        }
    }

    void clear() throws IOException {
        Files.deleteIfExists(path);
        Files.deleteIfExists(path.resolveSibling(path.getFileName() + ".tmp"));
    }

    private static void prepareDirectory(Path directory, boolean portable) throws IOException {
        if (!portable) {
            Files.createDirectories(directory);
            return;
        }
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("The Celeste activation directory must not be a symbolic link");
        }
        try {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException exception) {
            // Some game directories use filesystems without POSIX permissions.
            // The file still contains only the protected credential envelope.
            Files.createDirectories(directory);
        }
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("The Celeste activation directory must not be a symbolic link");
        }
        PosixFileAttributeView view = Files.getFileAttributeView(directory,
            PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) {
            view.setPermissions(PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static Path privateTemporaryFile(Path directory) throws IOException {
        try {
            return Files.createTempFile(directory, ".celeste-device-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException exception) {
            return Files.createTempFile(directory, ".celeste-device-", ".tmp");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static byte[] portableProtect(byte[] plain) throws GeneralSecurityException {
        if (plain.length == 0 || plain.length > MAX_PLAINTEXT_BYTES) {
            throw new GeneralSecurityException("Credential data exceeds supported size bounds");
        }
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        return portableProtectWithKey(plain, currentKey(true), nonce);
    }

    static byte[] portableProtectWithKey(byte[] plain, byte[] key, byte[] nonce) throws GeneralSecurityException {
        if (plain.length == 0 || plain.length > MAX_PLAINTEXT_BYTES || key.length != 32 || nonce.length != 12) {
            throw new GeneralSecurityException("Credential protection input is invalid");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(PORTABLE_AAD);
            byte[] sealed = cipher.doFinal(plain);
            byte[] result = new byte[PORTABLE_MAGIC.length + nonce.length + sealed.length];
            System.arraycopy(PORTABLE_MAGIC, 0, result, 0, PORTABLE_MAGIC.length);
            System.arraycopy(nonce, 0, result, PORTABLE_MAGIC.length, nonce.length);
            System.arraycopy(sealed, 0, result, PORTABLE_MAGIC.length + nonce.length, sealed.length);
            return result;
        } catch (GeneralSecurityException exception) {
            throw new GeneralSecurityException("Could not protect this account's Celeste credential", exception);
        }
    }

    private static byte[] portableUnprotect(byte[] protectedValue) throws GeneralSecurityException {
        return PortableCredentialProtection.unprotect(protectedValue);
    }

    static byte[] portableUnprotectWithKey(byte[] protectedValue, byte[] key) throws GeneralSecurityException {
        return PortableCredentialProtection.unprotectWithKey(protectedValue, key);
    }

    private static byte[] currentKey(boolean create) throws GeneralSecurityException {
        byte[] key = isMac() ? macKey() : isLinux() ? linuxKey() : null;
        if (key != null) return key;
        if (!create) throw new GeneralSecurityException("This account's Celeste key is missing from the system password store");
        if (!isMac() && !isLinux()) throw new GeneralSecurityException("This operating system is unsupported");
        byte[] created = new byte[32];
        new SecureRandom().nextBytes(created);
        if (isMac()) macStore(created);
        else linuxStore(created);
        byte[] stored = isMac() ? macKey() : linuxKey();
        if (stored == null) throw new GeneralSecurityException("The system password store did not retain the Celeste key");
        return stored;
    }

    private interface MacKeychain extends Library {
        MacKeychain INSTANCE = Native.load("Security", MacKeychain.class);
        int SecKeychainFindGenericPassword(Pointer keychain, int serviceLength, byte[] service,
            int accountLength, byte[] account, IntByReference passwordLength,
            PointerByReference passwordData, PointerByReference item);
        int SecKeychainAddGenericPassword(Pointer keychain, int serviceLength, byte[] service,
            int accountLength, byte[] account, int passwordLength, byte[] password,
            PointerByReference item);
        int SecKeychainItemFreeContent(Pointer attributes, Pointer data);
    }

    private static byte[] macKey() throws GeneralSecurityException {
        try {
            IntByReference length = new IntByReference();
            PointerByReference data = new PointerByReference();
            int status = MacKeychain.INSTANCE.SecKeychainFindGenericPassword(null,
                KEY_SERVICE.length, KEY_SERVICE, KEY_ACCOUNT.length, KEY_ACCOUNT, length, data, null);
            if (status == -25300) return null;
            if (status != 0) throw new GeneralSecurityException("macOS Keychain is unavailable or locked");
            Pointer pointer = data.getValue();
            if (pointer == null) throw new GeneralSecurityException("macOS Keychain returned an invalid key");
            try {
                if (length.getValue() != 43) throw new GeneralSecurityException("macOS Keychain returned an invalid key");
                byte[] key = Base64.getUrlDecoder().decode(pointer.getByteArray(0, 43));
                if (key.length != 32) throw new GeneralSecurityException("macOS Keychain returned an invalid key");
                return key;
            } finally {
                MacKeychain.INSTANCE.SecKeychainItemFreeContent(null, pointer);
            }
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("macOS Keychain returned an invalid key", exception);
        } catch (LinkageError exception) {
            throw new GeneralSecurityException("macOS Keychain is unavailable", exception);
        }
    }

    private static void macStore(byte[] key) throws GeneralSecurityException {
        try {
            byte[] encoded = Base64.getUrlEncoder().withoutPadding().encode(key);
            int status = MacKeychain.INSTANCE.SecKeychainAddGenericPassword(null,
                KEY_SERVICE.length, KEY_SERVICE, KEY_ACCOUNT.length, KEY_ACCOUNT, encoded.length, encoded, null);
            if (status != 0 && status != -25299) {
                throw new GeneralSecurityException("macOS Keychain is unavailable or locked");
            }
        } catch (LinkageError exception) {
            throw new GeneralSecurityException("macOS Keychain is unavailable", exception);
        }
    }

    private static ProcessBuilder linuxCommand(String operation) {
        ProcessBuilder command = "lookup".equals(operation)
            ? new ProcessBuilder("secret-tool", "lookup", "service", "dev.c4lam.celeste.normal",
                "account", LAUNCHER_CHANNEL)
            : new ProcessBuilder("secret-tool", "store", "--label=Celeste credential key",
                "service", "dev.c4lam.celeste.normal", "account", LAUNCHER_CHANNEL);
        if (!"lookup".equals(operation)) {
            command.redirectError(ProcessBuilder.Redirect.DISCARD);
            command.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        return command;
    }

    private static byte[] linuxKey() throws GeneralSecurityException {
        return PortableCredentialProtection.linuxKeyForSave();
    }

    private static void linuxStore(byte[] key) throws GeneralSecurityException {
        Process process = null;
        byte[] encoded = Base64.getUrlEncoder().withoutPadding().encode(key);
        try {
            process = linuxCommand("store").start();
            try (OutputStream input = process.getOutputStream()) {
                input.write(encoded);
            }
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new GeneralSecurityException("Desktop Secret Service is unavailable, locked, or timed out");
            }
        } catch (IOException exception) {
            throw new GeneralSecurityException("Install secret-tool and unlock this desktop's Secret Service", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GeneralSecurityException("Desktop Secret Service save was interrupted", exception);
        } finally {
            Arrays.fill(encoded, (byte) 0);
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
