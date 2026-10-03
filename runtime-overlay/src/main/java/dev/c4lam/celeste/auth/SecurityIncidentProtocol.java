package dev.c4lam.celeste.auth;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.Strictness;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import com.google.gson.Gson;

/** Signed diagnostic responses never authorize code delivery or gameplay. */
final class SecurityIncidentProtocol {
    static final long WARNING_NANOS = 5_000_000_000L;
    enum Kind { SELF_DEBUGGER, RESIDENT_MUTATION, MODULE_MUTATION }
    enum Phase { BEGIN, CONFIRM, OUTCOME }
    enum Status { WARNING, PENDING, REVOKED }
    enum Scope { PLUS, LICENSE }
    record Binding(long account, String device, long process, String session, String release,
                   String catalog, String hostDigest, Scope scope) {
        Binding {
            if (account <= 0 || process <= 0 || !opaque(device,32,48) || !opaque(session,32,32)
                    || !opaque(release,1,128) || !hex(catalog) || !hex(hostDigest))
                throw new IllegalArgumentException("Invalid security incident binding");
            Objects.requireNonNull(scope);
        }
    }
    record Observation(Kind kind, String artifact, String expected, String observed) {
        Observation {
            Objects.requireNonNull(kind);
            if (kind == Kind.SELF_DEBUGGER) {
                if (!artifact.equals("process") || !expected.isEmpty() || !observed.isEmpty())
                    throw new IllegalArgumentException("Debugger observations must contain no process inventory");
            } else if (!opaque(artifact,1,128) || !hex(expected) || !hex(observed) || expected.equals(observed)) {
                throw new IllegalArgumentException("Expected a confirmed retained-byte mismatch");
            }
        }
        static Observation debugger() { return new Observation(Kind.SELF_DEBUGGER,"process","",""); }
        boolean sameFamily(Observation other) {
            return other != null && kind==other.kind && artifact.equals(other.artifact) && expected.equals(other.expected);
        }
    }
    record Sample(Observation observation, String id, long atMillis) {
        Sample { Objects.requireNonNull(observation); if(!opaque(id,22,64)||atMillis<0)throw new IllegalArgumentException("Invalid observation"); }
    }
    record Hardware(String core, String monitor, String hwid) {
        Hardware { if(!hex(core)||!hex(monitor)||!hex(hwid))throw new IllegalArgumentException("Invalid device evidence"); }
    }
    record Request(Binding binding, String incident, String nonce, Phase phase,
                   String firstObservationId, Sample sample, String challenge, long timestampMillis) {
        Request {
            Objects.requireNonNull(binding); Objects.requireNonNull(phase); Objects.requireNonNull(sample);
            if (!opaque(incident,22,64) || !opaque(nonce,22,64)
                    || !opaque(firstObservationId,22,64) || timestampMillis<0
                    || phase==Phase.BEGIN&&!firstObservationId.equals(sample.id())
                    || (phase == Phase.BEGIN ? !challenge.isEmpty() : !opaque(challenge,22,64)))
                throw new IllegalArgumentException("Invalid incident request");
        }
        Observation observation(){return sample.observation();}
    }
    /** Constructed only after signature and complete request-context verification below. */
    static final class Decision {
        private final String challenge;
        private final Status status;
        private final long confirmAfterNanos, expiresNanos;
        Decision(String challenge, Status status, long confirmAfterNanos, long expiresNanos) {
            this.challenge=challenge; this.status=status;
            this.confirmAfterNanos=confirmAfterNanos; this.expiresNanos=expiresNanos;
        }
        String challenge() { return challenge; }
        boolean revoked() { return status==Status.REVOKED; }
        Status status() { return status; }
        long confirmAfterNanos() { return confirmAfterNanos; }
        long expiresNanos() { return expiresNanos; }
    }

    /** Exact canonical scalar JSON for the isolated backend adapter. Hardware is freshly supplied per request. */
    static byte[] body(Request r, Hardware hardware) {
        Binding b=r.binding(); Observation o=r.observation(); TreeMap<String,Object> c=new TreeMap<>();
        c.put("protocol","celeste-security-incident-1");c.put("product","celeste_plus");
        c.put("sub",Long.toString(b.account()));c.put("device",b.device());c.put("process_id",b.process());c.put("session_id",b.session());
        c.put("release",b.release());c.put("beta_catalog_sha256",b.catalog());c.put("client_sha256",b.hostDigest());
        c.put("hardware_sha256",hardware.core());c.put("monitor_sha256",hardware.monitor());c.put("hwid_sha256",hardware.hwid());
        c.put("scope",b.scope()==Scope.PLUS?"celeste_plus":"license");c.put("phase",r.phase().name().toLowerCase(java.util.Locale.ROOT));
        c.put("incident_id",r.incident());c.put("request_nonce",r.nonce());c.put("timestamp_ms",r.timestampMillis());
        c.put("first_observation_id",r.firstObservationId());c.put("observation_id",r.sample().id());c.put("observed_at_ms",r.sample().atMillis());
        c.put("signal",o.kind().name().toLowerCase(java.util.Locale.ROOT));c.put("artifact",o.artifact());
        c.put("expected_sha256",o.expected());c.put("observed_sha256",o.observed());c.put("challenge_id",r.challenge());
        return new Gson().toJson(c).getBytes(StandardCharsets.UTF_8);
    }

    static Decision verify(PublicKey serverKey, Request request, String compact,
                           long nowEpochSeconds, long nowNanos) throws GeneralSecurityException {
        return AuthServiceClient.verifyIncidentDecision(serverKey, request, compact, nowEpochSeconds, nowNanos);
    }
    static Map<String,String> object(byte[] bytes) throws IOException,GeneralSecurityException {
        if(bytes.length>6144)throw invalid();
        // Reject malformed UTF-8 instead of permitting replacement characters.
        String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        try(JsonReader reader=new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT); reader.beginObject(); Map<String,String> out=new HashMap<>();
            while(reader.hasNext()) {
                if(out.size()>=32)throw invalid(); String key=reader.nextName();
                if(out.containsKey(key))throw invalid(); JsonToken type=reader.peek();
                if(type!=JsonToken.STRING && type!=JsonToken.NUMBER)throw invalid();
                String value=reader.nextString(); if(key.length()>64 || value.length()>256)throw invalid();
                out.put(key,value);
            }
            reader.endObject(); if(reader.peek()!=JsonToken.END_DOCUMENT)throw invalid(); return out;
        }
    }
    static byte[] decode(String value) throws GeneralSecurityException {
        if(!value.matches("[A-Za-z0-9_-]+"))throw invalid(); byte[] decoded=Base64.getUrlDecoder().decode(value);
        if(!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value))throw invalid(); return decoded;
    }
    static long number(Map<String,String> c,String key)throws GeneralSecurityException {
        String value=c.get(key); if(value==null || !value.matches("0|[1-9][0-9]{0,18}"))throw invalid();
        return Long.parseLong(value);
    }
    static void expect(Map<String,String> c,String key,String value)throws GeneralSecurityException {
        if(!value.equals(c.get(key)))throw invalid();
    }
    private static boolean hex(String s){return s!=null && s.matches("[0-9a-f]{64}");}
    static boolean opaque(String s,int min,int max){return s!=null && s.length()>=min && s.length()<=max && s.matches("[A-Za-z0-9_-]+");}
    static GeneralSecurityException invalid(){return new GeneralSecurityException("Invalid security incident response");}
    private SecurityIncidentProtocol() { }
}
