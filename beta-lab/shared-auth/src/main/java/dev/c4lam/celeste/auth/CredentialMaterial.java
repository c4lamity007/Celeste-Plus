package dev.c4lam.celeste.auth;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/** Owns mutable credential secrets. Public identity/certificate fields are not secret keys.
 * Closing wipes owned buffers; JCA key-spec/provider copies have no guaranteed erasure API.
 */
public final class CredentialMaterial implements AutoCloseable {
    public static final int MAX_PLAINTEXT = 1024 * 1024;
    private final String deviceId, certificate, gameUuid, gameName;
    private final long uid;
    private byte[] signing, encryption, token;
    private boolean closed;

    public CredentialMaterial(String deviceId, long uid, byte[] signing, byte[] encryption, byte[] token,
                              String certificate, String gameUuid, String gameName) {
        this.deviceId = deviceId; this.uid = uid; this.certificate = certificate;
        this.gameUuid = gameUuid; this.gameName = gameName;
        this.signing = copy(signing); this.encryption = copy(encryption); this.token = copy(token);
    }
    private static byte[] copy(byte[] value) { return value == null ? null : value.clone(); }
    public String deviceId() { return deviceId; }
    public long uid() { return uid; }
    public String certificate() { return certificate; }
    public String gameUuid() { return gameUuid; }
    public String gameName() { return gameName; }
    public synchronized boolean isDestroyed() { return closed; }
    private void requireOpen() throws GeneralSecurityException {
        if (closed) throw new GeneralSecurityException("Installation credential has been closed");
    }
    public synchronized CredentialMaterial withGameIdentity(String uuid, String name) throws GeneralSecurityException {
        requireOpen(); return new CredentialMaterial(deviceId, uid, signing, encryption, token, certificate, uuid, name);
    }
    public synchronized void validate() throws GeneralSecurityException {
        requireOpen();
        if (deviceId == null || deviceId.length() < 32 || uid <= 0 || certificate == null || certificate.isBlank()
            || token == null || token.length != 32) throw new GeneralSecurityException("Invalid installation credential");
        destroy(privateKey("Ed25519", signing));
        destroy(privateKey("X25519", encryption));
        destroy(privateKey("Ed25519", token));
    }
    public synchronized byte[] signInstallation(byte[] message) throws GeneralSecurityException {
        requireOpen(); return signEncoded(signing, message);
    }
    public synchronized byte[] signToken(byte[] message) throws GeneralSecurityException {
        requireOpen();
        if (token == null || token.length != 32) throw new GeneralSecurityException("Invalid installation token");
        return signEncoded(token, message);
    }
    public synchronized DpopProofSigner signer(byte[] publicKey, Clock clock) throws GeneralSecurityException {
        requireOpen(); return DpopProofSigner.fromEncoded(signing, publicKey, clock);
    }
    public static byte[] signEncoded(byte[] encoded, byte[] message) throws GeneralSecurityException {
        PrivateKey key = privateKey("Ed25519", encoded);
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key); signature.update(message); return signature.sign();
        } finally { destroy(key); }
    }
    private static PrivateKey privateKey(String algorithm, byte[] encoded) throws GeneralSecurityException {
        if (encoded == null || encoded.length == 0 || encoded.length > 4096)
            throw new GeneralSecurityException("Invalid installation key");
        byte[] pkcs8;
        if (encoded.length == 32) {
            // RFC 8410 seed wrapper; avoids a second seed-bearing key-spec object.
            pkcs8 = new byte[48];
            byte[] prefix = {0x30,0x2e,0x02,0x01,0x00,0x30,0x05,0x06,0x03,0x2b,0x65,
                (byte) ("Ed25519".equals(algorithm) ? 0x70 : 0x6e),0x04,0x22,0x04,0x20};
            System.arraycopy(prefix, 0, pkcs8, 0, prefix.length);
            System.arraycopy(encoded, 0, pkcs8, prefix.length, encoded.length);
        } else pkcs8 = encoded.clone();
        try { return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(pkcs8)); }
        finally { Arrays.fill(pkcs8, (byte) 0); }
    }
    public static void destroy(PrivateKey key) {
        if (key != null) try { key.destroy(); } catch (javax.security.auth.DestroyFailedException ignored) {
            // Some JCA providers do not implement destruction. No claim of provider heap erasure.
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; wipe(signing); wipe(encryption); wipe(token);
        signing = encryption = token = null;
    }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    @Override public String toString() { return "Protected installation credential"; }

    /** Existing native/Java JSON schema, without constructing secret-bearing Strings or a JSON tree. */
    public static CredentialMaterial decode(byte[] plain) throws GeneralSecurityException {
        if (plain == null || plain.length == 0 || plain.length > MAX_PLAINTEXT)
            throw new GeneralSecurityException("Invalid credential size");
        char[] characters = new char[plain.length];
        byte[] signing = null, encryption = null, token = null;
        try {
            CharBuffer target = CharBuffer.wrap(characters);
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            var result = decoder.decode(ByteBuffer.wrap(plain), target, true); if (result.isError()) result.throwException();
            result = decoder.flush(target); if (result.isError()) result.throwException();
            Reader reader = new Reader(characters, target.position());
            String device = null, certificate = null, uuid = null, name = null; long uid = 0;
            Set<String> fields = new HashSet<>(); reader.expect('{');
            if (!reader.take('}')) do {
                char[] fieldChars = reader.string(); String field;
                try { if (fieldChars.length > 32) throw invalid(); field = new String(fieldChars); }
                finally { Arrays.fill(fieldChars, '\0'); }
                if (!fields.add(field)) throw invalid(); reader.expect(':');
                if ("uid".equals(field)) { uid = reader.integer(); continue; }
                if (!Set.of("deviceId", "certificate", "gameUuid", "gameName", "signingPrivate", "encryptionPrivate", "tokenSeed").contains(field)) throw invalid();
                char[] value = reader.nullValue() ? null : reader.string();
                try {
                    switch (field) {
                        case "signingPrivate" -> signing = secret(value);
                        case "encryptionPrivate" -> encryption = secret(value);
                        case "tokenSeed" -> token = secret(value);
                        case "deviceId" -> device = value == null ? null : new String(value);
                        case "certificate" -> certificate = value == null ? null : new String(value);
                        case "gameUuid" -> uuid = value == null ? null : new String(value);
                        case "gameName" -> name = value == null ? null : new String(value);
                        default -> throw invalid();
                    }
                } finally { if (value != null) Arrays.fill(value, '\0'); }
            } while (reader.take(','));
            else { reader.end(); throw invalid(); }
            reader.expect('}'); reader.end();
            if (device == null || uid <= 0 || certificate == null || signing == null) throw invalid();
            return new CredentialMaterial(device, uid, signing, encryption, token, certificate, uuid, name);
        } catch (java.nio.charset.CharacterCodingException | IllegalArgumentException failure) {
            throw invalid();
        } finally { Arrays.fill(characters, '\0'); wipe(signing); wipe(encryption); wipe(token); }
    }
    private static byte[] secret(char[] chars) throws GeneralSecurityException {
        if (chars == null || chars.length == 0 || chars.length > 5464) throw invalid();
        byte[] ascii = new byte[chars.length];
        try {
            for (int i = 0; i < chars.length; i++) {
                char c = chars[i];
                if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '=')) throw invalid();
                ascii[i] = (byte) c;
            }
            byte[] result = Base64.getUrlDecoder().decode(ascii);
            if (result.length > 4096) { wipe(result); throw invalid(); }
            return result;
        } catch (IllegalArgumentException failure) { throw invalid(); }
        finally { wipe(ascii); }
    }
    public synchronized byte[] encode() throws GeneralSecurityException {
        requireOpen(); validate();
        try (Output out = new Output()) {
            out.ascii("{\"deviceId\":"); out.text(deviceId); out.ascii(",\"uid\":" + uid);
            out.ascii(",\"signingPrivate\":"); out.secret(signing);
            out.ascii(",\"encryptionPrivate\":"); out.secret(encryption);
            out.ascii(",\"tokenSeed\":"); out.secret(token);
            out.ascii(",\"certificate\":"); out.text(certificate);
            if (gameUuid != null) { out.ascii(",\"gameUuid\":"); out.text(gameUuid); }
            if (gameName != null) { out.ascii(",\"gameName\":"); out.text(gameName); }
            out.ascii("}"); return out.toByteArray();
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    private static GeneralSecurityException invalid() { return new GeneralSecurityException("Invalid installation credential encoding"); }
    private static final class Output extends ByteArrayOutputStream {
        Output() { super(MAX_PLAINTEXT); }
        private void bound(int size) { if (size < 0 || size > MAX_PLAINTEXT - count) throw new IllegalArgumentException("Credential exceeds size limit"); }
        @Override public synchronized void write(int value) { bound(1); super.write(value); }
        @Override public synchronized void write(byte[] value, int offset, int size) { bound(size); super.write(value, offset, size); }
        void ascii(String value) { writeBytes(value.getBytes(StandardCharsets.US_ASCII)); }
        void secret(byte[] value) {
            byte[] encoded = Base64.getUrlEncoder().withoutPadding().encode(value);
            try { write('"'); writeBytes(encoded); write('"'); } finally { wipe(encoded); }
        }
        void text(String value) {
            // Only public identity metadata and the signed certificate reach this String serializer.
            writeBytes(new com.google.gson.Gson().toJson(value).getBytes(StandardCharsets.UTF_8));
        }
        @Override public void close() { Arrays.fill(buf, (byte) 0); reset(); }
    }
    private static final class Reader {
        final char[] data; final int length; int position;
        Reader(char[] data, int length) { this.data = data; this.length = length; }
        void space() { while (position < length && " \t\r\n".indexOf(data[position]) >= 0) position++; }
        boolean take(char c) { space(); if (position < length && data[position] == c) { position++; return true; } return false; }
        void expect(char c) throws GeneralSecurityException { if (!take(c)) throw invalid(); }
        void end() throws GeneralSecurityException { space(); if (position != length) throw invalid(); }
        boolean nullValue() { space(); if (position + 4 <= length && data[position] == 'n' && data[position+1] == 'u' && data[position+2] == 'l' && data[position+3] == 'l') { position += 4; return true; } return false; }
        long integer() throws GeneralSecurityException {
            space(); int start = position; long value = 0;
            while (position < length && data[position] >= '0' && data[position] <= '9') {
                int digit = data[position++] - '0'; if (value > (Long.MAX_VALUE - digit) / 10) throw invalid(); value = value * 10 + digit;
            }
            if (position == start || position - start > 1 && data[start] == '0') throw invalid(); return value;
        }
        char[] string() throws GeneralSecurityException {
            expect('"'); char[] buffer = new char[length-position]; int count = 0;
            try {
                while (position < length) {
                    char c = data[position++]; if (c == '"') return Arrays.copyOf(buffer, count);
                    if (c < 32) throw invalid();
                    if (c == '\\') {
                        if (position == length) throw invalid(); c = data[position++];
                        c = switch(c) {
                            case '"','\\','/' -> c; case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                            case 'u' -> { if (position + 4 > length) throw invalid(); int value = 0;
                                for(int i=0;i<4;i++) { int n=Character.digit(data[position++],16); if(n<0) throw invalid(); value=value*16+n; } yield (char)value; }
                            default -> throw invalid();
                        };
                    }
                    buffer[count++] = c;
                }
                throw invalid();
            } finally { Arrays.fill(buffer, '\0'); }
        }
    }
}
