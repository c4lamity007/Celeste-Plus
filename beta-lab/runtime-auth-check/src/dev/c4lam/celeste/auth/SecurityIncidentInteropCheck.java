package dev.c4lam.celeste.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import static dev.c4lam.celeste.auth.SecurityIncidentProtocol.*;

/** Verifies actual Python-proposal HTTP response vectors with synthetic keys. No network or process actions. */
public final class SecurityIncidentInteropCheck {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected synthetic vector path");
        JsonObject root=new Gson().fromJson(Files.readString(Path.of(args[0])),JsonObject.class);
        if(!root.get("synthetic_only").getAsBoolean())throw new AssertionError("Synthetic vectors only");
        var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
            Base64.getUrlDecoder().decode(string(root,"server_public_key_spki"))));
        int count=0;
        for(var value:root.getAsJsonArray("vectors")) {
            JsonObject vector=value.getAsJsonObject(),r=vector.getAsJsonObject("request");
            Binding binding=new Binding(Long.parseLong(string(r,"sub")),string(r,"device"),r.get("process_id").getAsLong(),
                string(r,"session_id"),string(r,"release"),string(r,"beta_catalog_sha256"),string(r,"client_sha256"),
                string(r,"scope").equals("celeste_plus")?Scope.PLUS:Scope.LICENSE);
            Observation observation=new Observation(Kind.valueOf(string(r,"signal").toUpperCase(Locale.ROOT)),
                string(r,"artifact"),string(r,"expected_sha256"),string(r,"observed_sha256"));
            Sample sample=new Sample(observation,string(r,"observation_id"),r.get("observed_at_ms").getAsLong());
            Request request=new Request(binding,string(r,"incident_id"),string(r,"request_nonce"),
                Phase.valueOf(string(r,"phase").toUpperCase(Locale.ROOT)),string(r,"first_observation_id"),sample,
                string(r,"challenge_id"),r.get("timestamp_ms").getAsLong());
            byte[] body=SecurityIncidentProtocol.body(request,new Hardware(string(r,"hardware_sha256"),
                string(r,"monitor_sha256"),string(r,"hwid_sha256")));
            if(!new String(body,StandardCharsets.UTF_8).equals(string(vector,"request_json")))
                throw new AssertionError("Canonical request differs: "+string(vector,"label"));
            long now=vector.get("now_ms").getAsLong();
            Decision decision=SecurityIncidentProtocol.verify(key,request,string(vector,"decision"),now/1000,0);
            if(!decision.status().name().toLowerCase(Locale.ROOT).equals(string(vector.getAsJsonObject("claims"),"status")))
                throw new AssertionError("Decision differs: "+string(vector,"label"));
            count++;
        }
        if(count!=4)throw new AssertionError("Expected four response vectors");
        System.out.println("PASS Python/Java incident interoperability: 4 signed HTTP response vectors and 4 exact canonical request bodies; synthetic keys only");
    }
    private static String string(JsonObject o,String key){return o.get(key).getAsString();}
}
