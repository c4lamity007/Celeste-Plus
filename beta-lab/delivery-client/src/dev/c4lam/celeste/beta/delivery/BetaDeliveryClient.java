package dev.c4lam.celeste.beta.delivery;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Celeste+ verification and bounded memory delivery. Never loads or executes code. */
public final class BetaDeliveryClient {
    public static final int MAX_ARTIFACT_BYTES = 64 * 1024 * 1024;
    public static final long MAX_RELEASE_BYTES = 128L * 1024 * 1024;
    static final int MAX_TOKEN_BYTES = 16_384;
    static final int MAX_MANIFEST_RESPONSE_BYTES = 20_480;
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);
    static final List<String> IDS = List.of("bootstrap-a", "bootstrap-b", "payload-a", "payload-b");
    private static final Set<String> CATALOG_KEYS = Set.of("schema", "release_id", "client_version", "runtime_sha256", "artifacts");
    private static final Set<String> MANIFEST_KEYS = Set.of("product", "aud", "sub", "device", "iat", "nbf", "exp",
        "installer_jti", "installer_sha256", "catalog_sha256", "schema", "release_id", "client_version", "runtime_sha256", "artifacts");
    private static final Set<String> INSTALLER_KEYS = Set.of("product", "aud", "sub", "device", "iat", "nbf", "exp", "jti",
        "release", "client_sha256", "payload_release", "payload_sha256", "bootstrap", "bootstrap_sha256");
    private final URI origin;
    private final PublicKey pinnedKey;
    private final String installerToken;
    private final String expectedDevice;
    private final long expectedUid;
    private final Clock clock;
    private final Transport transport;

    public BetaDeliveryClient(URI origin, byte[] pinnedEd25519Key, String installerToken,
                              String expectedDevice, long expectedUid) throws GeneralSecurityException {
        this(origin, pinnedEd25519Key, installerToken, expectedDevice, expectedUid, Clock.systemUTC(), new HttpsTransport());
    }

    /** Package-local offline seam. Production construction always uses bounded, nonredirecting HTTPS. */
    BetaDeliveryClient(URI origin, byte[] pinnedEd25519Key, String installerToken, String expectedDevice,
                       long expectedUid, Clock clock, Transport transport) throws GeneralSecurityException {
        if (origin == null || !"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null
            || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
            || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
            || origin.getPort() == 0 || origin.getPort() > 65535) throw invalid("HTTPS origin required");
        if (pinnedEd25519Key == null || pinnedEd25519Key.length != 32 || expectedUid <= 0
            || expectedDevice == null || !expectedDevice.matches("[A-Za-z0-9_-]{32,48}")) throw invalid("Invalid trust context");
        this.origin = origin.resolve("/");
        byte[] encoded = new byte[44];
        System.arraycopy(HexFormat.of().parseHex("302a300506032b6570032100"), 0, encoded, 0, 12);
        System.arraycopy(pinnedEd25519Key, 0, encoded, 12, 32);
        pinnedKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        this.installerToken = installerToken;
        this.expectedDevice = expectedDevice;
        this.expectedUid = expectedUid;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        installer();
    }

    public record Artifact(String id, String role, String url, String sha256, int size) { }

    /** Only this verifier can construct a catalog. No token is exposed in its printable metadata. */
    public static final class VerifiedCatalog {
        private final BetaDeliveryClient owner;
        private final String token;
        private final String releaseId;
        private final String clientVersion;
        private final String runtimeSha256;
        private final String catalogSha256;
        private final long expiresAt;
        private final List<Artifact> artifacts;

        private VerifiedCatalog(BetaDeliveryClient owner, String token, String releaseId, String clientVersion,
                                String runtimeSha256, String catalogSha256, long expiresAt, List<Artifact> artifacts) {
            this.owner = owner; this.token = token; this.releaseId = releaseId; this.clientVersion = clientVersion;
            this.runtimeSha256 = runtimeSha256; this.catalogSha256 = catalogSha256; this.expiresAt = expiresAt;
            this.artifacts = List.copyOf(artifacts);
        }
        public String releaseId() { return releaseId; }
        public String clientVersion() { return clientVersion; }
        public String runtimeSha256() { return runtimeSha256; }
        public String catalogSha256() { return catalogSha256; }
        public long expiresAt() { return expiresAt; }
        public List<Artifact> artifacts() { return artifacts; }
    }

    /** Closing zeroes retained arrays; callers own any copies they choose to make. */
    public static final class MemoryRelease implements AutoCloseable {
        private final VerifiedCatalog catalog;
        private final Map<String, byte[]> bytes;
        private boolean closed;
        private MemoryRelease(VerifiedCatalog catalog, Map<String, byte[]> bytes) {
            this.catalog = catalog; this.bytes = bytes;
        }
        public VerifiedCatalog catalog() { return catalog; }
        public synchronized ByteBuffer artifact(String id) {
            if (closed) throw new IllegalStateException("Release is closed");
            byte[] value = bytes.get(id);
            if (value == null) throw new IllegalArgumentException("Unknown beta artifact");
            return ByteBuffer.wrap(value).asReadOnlyBuffer();
        }
        @Override public synchronized void close() {
            if (!closed) { closed = true; bytes.values().forEach(value -> Arrays.fill(value, (byte) 0)); bytes.clear(); }
        }
    }

    public VerifiedCatalog verifyManifest(String manifestToken) throws GeneralSecurityException {
        JsonObject installer = installer();
        JsonObject claims = signed(manifestToken, "celeste-beta-manifest");
        exact(claims, MANIFEST_KEYS);
        identity(claims);
        long now = clock.instant().getEpochSecond();
        long issued = integer(claims, "iat"), expires = integer(claims, "exp");
        if (issued > now || integer(claims, "nbf") != issued || expires <= now || expires <= issued
            || expires - issued > 120 || expires > integer(installer, "exp")
            || !text(claims, "installer_jti").equals(text(installer, "jti"))
            || !hex(claims, "installer_sha256").equals(sha256(installerToken.getBytes(StandardCharsets.US_ASCII)))) {
            throw invalid("Manifest is not bound to the current installer grant");
        }
        String releaseId = text(claims, "release_id"), version = text(claims, "client_version");
        String runtimeDigest = hex(claims, "runtime_sha256");
        if (!text(claims, "schema").equals("celeste-beta-release-2")
            || !releaseId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")
            || !version.matches("[!-~]{1,40}") || releaseId.equals(text(installer, "payload_release"))
            || runtimeDigest.equals(hex(installer, "payload_sha256"))) throw invalid("Invalid beta release identity");
        JsonElement rawArtifacts = claims.get("artifacts");
        if (!rawArtifacts.isJsonArray() || rawArtifacts.getAsJsonArray().size() != 4) throw invalid("Expected four beta artifacts");
        List<Artifact> artifacts = new ArrayList<>();
        long total = 0;
        for (int index = 0; index < IDS.size(); index++) {
            JsonElement raw = rawArtifacts.getAsJsonArray().get(index);
            if (!raw.isJsonObject()) throw invalid("Invalid artifact metadata");
            JsonObject item = raw.getAsJsonObject();
            exact(item, Set.of("id", "role", "url", "sha256", "size"));
            String id = text(item, "id"), role = text(item, "role"), url = text(item, "url");
            long size = integer(item, "size");
            if (!id.equals(IDS.get(index)) || !role.equals(index < 2 ? "bootstrap" : "payload")
                || !url.equals("/v1/beta/artifacts/" + releaseId + "/" + id)
                || size <= 0 || size > MAX_ARTIFACT_BYTES) throw invalid("Invalid artifact identity, path or size");
            total += size;
            if (total > MAX_RELEASE_BYTES) throw invalid("Beta release exceeds memory limit");
            artifacts.add(new Artifact(id, role, url, hex(item, "sha256"), (int) size));
        }
        JsonObject catalog = new JsonObject();
        CATALOG_KEYS.forEach(key -> catalog.add(key, claims.get(key)));
        String catalogHash = hex(claims, "catalog_sha256");
        if (!catalogHash.equals(sha256(canonical(catalog).getBytes(StandardCharsets.UTF_8)))) throw invalid("Catalog digest mismatch");
        return new VerifiedCatalog(this, manifestToken, releaseId, version, runtimeDigest, catalogHash, expires, artifacts);
    }

    public VerifiedCatalog fetchCatalog() throws IOException, InterruptedException, GeneralSecurityException {
        installer();
        byte[] body = fetch("/v1/beta/manifest", null, MAX_MANIFEST_RESPONSE_BYTES, "application/json");
        try {
            JsonObject wrapper = object(body);
            exact(wrapper, Set.of("manifest"));
            return verifyManifest(text(wrapper, "manifest"));
        } finally { Arrays.fill(body, (byte) 0); }
    }

    public MemoryRelease download(VerifiedCatalog catalog) throws IOException, InterruptedException, GeneralSecurityException {
        current(catalog);
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        boolean complete = false;
        try {
            for (Artifact artifact : catalog.artifacts) {
                current(catalog);
                byte[] body = fetch(artifact.url(), catalog.token, artifact.size(), "application/octet-stream");
                bytes.put(artifact.id(), body);
                if (body.length != artifact.size() || !sha256(body).equals(artifact.sha256())) throw invalid("Artifact integrity mismatch");
                current(catalog);
            }
            complete = true;
            return new MemoryRelease(catalog, bytes);
        } finally { if (!complete) bytes.values().forEach(value -> Arrays.fill(value, (byte) 0)); }
    }

    private void current(VerifiedCatalog catalog) throws GeneralSecurityException {
        if (catalog == null || catalog.owner != this) throw invalid("Catalog belongs to another delivery context");
        installer();
        if (catalog.expiresAt <= clock.instant().getEpochSecond()) throw invalid("Beta manifest expired");
    }

    private byte[] fetch(String path, String manifest, int limit, String mediaType)
        throws IOException, InterruptedException, GeneralSecurityException {
        URI uri = origin.resolve(path);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + installerToken);
        headers.put("Accept", mediaType);
        headers.put("Cache-Control", "no-store");
        if (manifest != null) headers.put("X-Celeste-Beta-Manifest", manifest);
        Reply reply = transport.get(uri, Map.copyOf(headers), limit, REQUEST_TIMEOUT);
        byte[] body = reply.body();
        boolean accepted = false;
        try {
            if (!uri.equals(reply.uri()) || reply.status() != 200 || body == null || body.length > limit)
                throw new IOException("Beta response origin, status or size rejected");
            List<String> types = values(reply.headers(), "Content-Type");
            if (types.size() != 1 || !types.getFirst().split(";", 2)[0].trim().equalsIgnoreCase(mediaType))
                throw new IOException("Beta response content type rejected");
            List<String> lengths = values(reply.headers(), "Content-Length");
            if (lengths.size() > 1 || (!lengths.isEmpty() && (!lengths.getFirst().matches("0|[1-9][0-9]*")
                || Long.parseLong(lengths.getFirst()) != body.length))) throw new IOException("Beta response length rejected");
            List<String> encodings = values(reply.headers(), "Content-Encoding");
            if (encodings.size() > 1 || (!encodings.isEmpty() && !encodings.getFirst().equalsIgnoreCase("identity")))
                throw new IOException("Encoded beta responses are not accepted");
            accepted = true;
            return body;
        } catch (NumberFormatException exception) {
            throw new IOException("Beta response length rejected", exception);
        } finally { if (!accepted && body != null) Arrays.fill(body, (byte) 0); }
    }

    static List<String> values(Map<String, List<String>> headers, String name) {
        List<String> values = new ArrayList<>();
        headers.forEach((key, value) -> { if (key.equalsIgnoreCase(name)) values.addAll(value); });
        return values;
    }

    private JsonObject installer() throws GeneralSecurityException {
        JsonObject claims = signed(installerToken, "celeste-installer");
        exact(claims, INSTALLER_KEYS);
        identity(claims);
        long now = clock.instant().getEpochSecond();
        if (integer(claims, "iat") > now + 5 || integer(claims, "nbf") > now + 5
            || integer(claims, "exp") <= now || !text(claims, "jti").matches("[A-Za-z0-9_-]{16,128}"))
            throw invalid("Installer grant is not current");
        for (String field : List.of("release", "payload_release", "bootstrap")) {
            if (text(claims, field).isEmpty() || text(claims, field).length() > 80) throw invalid("Invalid installer identity");
        }
        for (String field : List.of("client_sha256", "payload_sha256", "bootstrap_sha256")) hex(claims, field);
        return claims;
    }

    private void identity(JsonObject claims) throws GeneralSecurityException {
        if (!text(claims, "product").equals("celeste_plus") || !text(claims, "sub").equals(Long.toString(expectedUid)) || !text(claims, "device").equals(expectedDevice))
            throw invalid("Grant does not match the configured Celeste+ installation");
    }

    private JsonObject signed(String token, String audience) throws GeneralSecurityException {
        if (token == null || token.length() > MAX_TOKEN_BYTES) throw invalid("Invalid signed token");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) throw invalid("Invalid signed token");
        byte[] header = decode(parts[0]), payload = decode(parts[1]), signatureBytes = decode(parts[2]);
        if (signatureBytes.length != 64) throw invalid("Invalid signature length");
        Signature signature = Signature.getInstance("Ed25519");
        signature.initVerify(pinnedKey);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(signatureBytes)) throw invalid("Signature did not match the pinned server");
        JsonObject metadata = object(header), claims = object(payload);
        exact(metadata, Set.of("alg", "kid", "typ"));
        if (!text(metadata, "alg").equals("EdDSA") || !text(metadata, "typ").equals("CST")
            || !text(metadata, "kid").equals("celeste-release-1") || !text(claims, "aud").equals(audience))
            throw invalid("Unexpected token type");
        return claims;
    }

    static byte[] decode(String encoded) throws GeneralSecurityException {
        try {
            if (!encoded.matches("[A-Za-z0-9_-]+")) throw invalid("Invalid base64url");
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) throw invalid("Noncanonical base64url");
            return decoded;
        } catch (IllegalArgumentException exception) { throw new GeneralSecurityException("Invalid base64url", exception); }
    }

    static JsonObject object(byte[] bytes) throws GeneralSecurityException {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (JsonReader reader = new JsonReader(new StringReader(decoded))) {
                reader.setStrictness(Strictness.STRICT);
                JsonElement value = read(reader, 0);
                if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw invalid("Expected one JSON object");
                return value.getAsJsonObject();
            }
        } catch (IOException | IllegalStateException | NumberFormatException exception) {
            throw new GeneralSecurityException("Malformed signed metadata", exception);
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException, GeneralSecurityException {
        if (depth > 12) throw invalid("Metadata nesting exceeds limit");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject(); reader.beginObject(); int count = 0;
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (++count > 64 || object.has(key)) throw invalid("Duplicate or excessive metadata keys");
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject(); return object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) { if (array.size() >= 64) throw invalid("Metadata array exceeds limit"); array.add(read(reader, depth + 1)); }
                reader.endArray(); return array;
            }
            case STRING -> { return new JsonPrimitive(reader.nextString()); }
            case NUMBER -> {
                String number = reader.nextString();
                if (!number.matches("0|[1-9][0-9]*")) throw invalid("Expected exact nonnegative integer");
                return new JsonPrimitive(Long.parseLong(number));
            }
            default -> throw invalid("Unexpected metadata type");
        }
    }

    private static void exact(JsonObject object, Set<String> keys) throws GeneralSecurityException {
        if (!object.keySet().equals(keys)) throw invalid("Unexpected metadata fields");
    }
    private static String text(JsonObject object, String key) throws GeneralSecurityException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid("Expected text metadata");
        return value.getAsString();
    }
    private static String hex(JsonObject object, String key) throws GeneralSecurityException {
        String value = text(object, key);
        if (!value.matches("[0-9a-f]{64}")) throw invalid("Invalid SHA-256 metadata");
        return value;
    }
    private static long integer(JsonObject object, String key) throws GeneralSecurityException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid("Expected integer metadata");
        return value.getAsLong();
    }
    static String sha256(byte[] bytes) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static String canonical(JsonElement value) throws GeneralSecurityException {
        if (value.isJsonObject()) {
            List<String> fields = new ArrayList<>();
            for (String key : new TreeSet<>(value.getAsJsonObject().keySet())) fields.add(quote(key) + ":" + canonical(value.getAsJsonObject().get(key)));
            return "{" + String.join(",", fields) + "}";
        }
        if (value.isJsonArray()) {
            List<String> entries = new ArrayList<>();
            for (JsonElement element : value.getAsJsonArray()) entries.add(canonical(element));
            return "[" + String.join(",", entries) + "]";
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return quote(value.getAsString());
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return Long.toString(value.getAsLong());
        throw invalid("Unsupported canonical metadata type");
    }
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b"); case '\f' -> out.append("\\f"); case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> {
                    if (character < 32 || character > 126) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    else out.append(character);
                }
            }
        }
        return out.append('"').toString();
    }
    static GeneralSecurityException invalid(String message) { return new GeneralSecurityException(message); }

    @FunctionalInterface interface Transport {
        Reply get(URI uri, Map<String, String> headers, int maximumBytes, Duration timeout) throws IOException, InterruptedException;
    }
    record Reply(URI uri, int status, Map<String, List<String>> headers, byte[] body) { }
}
