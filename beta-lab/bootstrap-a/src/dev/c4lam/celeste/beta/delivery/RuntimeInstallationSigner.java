package dev.c4lam.celeste.beta.delivery;

import com.google.gson.JsonObject;
import dev.c4lam.celeste.auth.DpopProofSigner;
import dev.c4lam.celeste.auth.CredentialMaterial;
import dev.c4lam.celeste.auth.PortableCredentialProtection;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Locale;

/** Read-only bridge to the launcher's existing current-account protected runtime installation. */
public final class RuntimeInstallationSigner {
    private static final int MAX_PLAIN = 1024 * 1024;
    private static final int MAX_STORED = MAX_PLAIN + 64 * 1024 + 4;
    private RuntimeInstallationSigner() { }

    /** Caller has already verified this exact game's directory against the selected process. */
    public static DpopProofSigner load(Path verifiedGameDirectory, ClassLoader gameLoader,
                                       byte[] serverKey, String device, long uid)
        throws IOException, GeneralSecurityException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
        Path root = verifiedGameDirectory.toRealPath();
        Path storedPath = root.resolve("config/celeste/auth/celeste_plus/device.bin");
        if (!Files.isRegularFile(storedPath, LinkOption.NOFOLLOW_LINKS)
            || !storedPath.toRealPath().startsWith(root)) {
            throw new GeneralSecurityException("The selected instance has no protected Celeste+ installation");
        }
        byte[] stored;
        try (InputStream input = Files.newInputStream(storedPath)) { stored = input.readNBytes(MAX_STORED + 1); }
        byte[] protectedBytes = null, plain = null;
        try {
            if (stored.length <= 4 || stored.length > MAX_STORED || stored[0] != 'C' || stored[1] != 'S'
                || stored[2] != 'T' || stored[3] != '1') throw new GeneralSecurityException("Invalid protected installation envelope");
            protectedBytes = Arrays.copyOfRange(stored, 4, stored.length);
            plain = windows ? windowsUnprotect(protectedBytes, gameLoader) : PortableCredentialProtection.unprotect(protectedBytes);
            return decode(plain, serverKey, device, uid, Clock.systemUTC());
        } finally {
            Arrays.fill(stored, (byte) 0);
            if (protectedBytes != null) Arrays.fill(protectedBytes, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    private static byte[] windowsUnprotect(byte[] protectedBytes, ClassLoader gameLoader) throws GeneralSecurityException {
        final Method unprotect;
        try {
            // Reuse Minecraft's public JNA API; do not load a second native library or install a hook.
            Class<?> api = Class.forName("com.sun.jna.platform.win32.Crypt32Util", false, gameLoader);
            unprotect = api.getMethod("cryptUnprotectData", byte[].class);
            if (!Modifier.isStatic(unprotect.getModifiers()) || unprotect.getReturnType() != byte[].class) {
                throw new ReflectiveOperationException("Unexpected Windows protection API");
            }
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new GeneralSecurityException("Minecraft's Windows credential protection support is unavailable; repair the selected instance", failure);
        }
        try { return (byte[]) unprotect.invoke(null, (Object) protectedBytes); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            throw new GeneralSecurityException("Could not open the selected instance's Celeste+ installation for this Windows account");
        }
    }

    /** Offline parser seam; production plaintext comes only from existing account-protected credentials. */
    static DpopProofSigner decode(byte[] plain, byte[] serverKey, String device, long uid, Clock clock)
        throws GeneralSecurityException {
        if (plain == null || plain.length == 0 || plain.length > MAX_PLAIN || uid <= 0 || device == null) {
            throw new GeneralSecurityException("Invalid protected installation data");
        }
        try (CredentialMaterial credential = CredentialMaterial.decode(plain)) {
        if (!device.equals(credential.deviceId())
            || uid != credential.uid()) {
            throw new GeneralSecurityException("Protected installation does not match the launcher identity");
        }
        JsonObject certificate = BetaDeliveryClient.signed(credential.certificate(),
            "celeste-installation", BetaDeliveryClient.publicKey(serverKey));
        long now = clock.instant().getEpochSecond();
        if (!device.equals(BetaDeliveryClient.text(certificate, "device"))
            || !Long.toString(uid).equals(BetaDeliveryClient.text(certificate, "sub"))
            || BetaDeliveryClient.integer(certificate, "iat") > now + 5
            || BetaDeliveryClient.integer(certificate, "exp") <= now) {
            throw new GeneralSecurityException("Installation certificate does not match the current identity");
        }
        byte[] rawPublic = BetaDeliveryClient.decode(BetaDeliveryClient.text(certificate, "install_key"));
        DpopProofSigner signer = credential.signer(rawPublic, clock);
        try {
            // Legacy installation certificates predate cnf; current delivery grants never may omit it.
            if (certificate.has("cnf")) signer.requireBinding(certificate);
            return signer;
        } catch (GeneralSecurityException | RuntimeException | Error failure) { signer.close(); throw failure; }
        }
    }
}
