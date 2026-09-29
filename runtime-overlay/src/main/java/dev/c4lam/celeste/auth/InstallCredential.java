package dev.c4lam.celeste.auth;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;

/** Session-owned installation material; no private key is retained in an immutable String. */
final class InstallCredential implements AutoCloseable {
    private final CredentialMaterial material;
    private InstallCredential(CredentialMaterial material) { this.material = material; }
    InstallCredential(String deviceId, long uid, byte[] signingPrivate, byte[] encryptionPrivate, byte[] tokenSeed,
                      String certificate, String gameUuid, String gameName) {
        this(new CredentialMaterial(deviceId, uid, signingPrivate, encryptionPrivate, tokenSeed, certificate, gameUuid, gameName));
    }
    // Preserve the previous constructor descriptor for fixtures/integrations. Production uses bytes/codec.
    InstallCredential(String deviceId, long uid, String signingPrivate, String encryptionPrivate, String tokenSeed,
                      String certificate, String gameUuid, String gameName) {
        this(legacy(deviceId, uid, signingPrivate, encryptionPrivate, tokenSeed, certificate, gameUuid, gameName));
    }
    private static CredentialMaterial legacy(String device, long uid, String signing, String encryption, String token,
                                             String certificate, String uuid, String name) {
        byte[] a = null, b = null, c = null;
        try {
            a = Base64.getUrlDecoder().decode(signing); b = Base64.getUrlDecoder().decode(encryption); c = Base64.getUrlDecoder().decode(token);
            return new CredentialMaterial(device, uid, a, b, c, certificate, uuid, name);
        } finally { if(a!=null)Arrays.fill(a,(byte)0); if(b!=null)Arrays.fill(b,(byte)0); if(c!=null)Arrays.fill(c,(byte)0); }
    }
    static InstallCredential decode(byte[] plain) throws GeneralSecurityException {
        InstallCredential credential = new InstallCredential(CredentialMaterial.decode(plain));
        try { credential.validate(); return credential; }
        catch (GeneralSecurityException | RuntimeException | Error failure) { credential.close(); throw failure; }
    }
    byte[] encode() throws GeneralSecurityException { return material.encode(); }
    String deviceId() { return material.deviceId(); }
    long uid() { return material.uid(); }
    String certificate() { return material.certificate(); }
    String gameUuid() { return material.gameUuid(); }
    String gameName() { return material.gameName(); }
    byte[] signInstallation(byte[] message) throws GeneralSecurityException { return material.signInstallation(message); }
    byte[] signToken(byte[] message) throws GeneralSecurityException { return material.signToken(message); }
    DpopProofSigner signer(byte[] publicKey) throws GeneralSecurityException { return material.signer(publicKey, Clock.systemUTC()); }
    void validate() throws GeneralSecurityException { material.validate(); }
    boolean hasGameIdentity() { return gameUuid()!=null&&!gameUuid().isBlank()&&gameName()!=null&&!gameName().isBlank(); }
    InstallCredential withGameIdentity(GameIdentity identity) throws GeneralSecurityException {
        return new InstallCredential(material.withGameIdentity(identity.uuid(), identity.username()));
    }
    boolean isDestroyed() { return material.isDestroyed(); }
    @Override public void close() { material.close(); }
    @Override public String toString() { return "Protected installation credential"; }
}
