package dev.c4lam.celeste.beta.delivery;

import com.google.gson.*;
import dev.c4lam.celeste.auth.DpopProofSigner;
import dev.c4lam.celeste.beta.memory.ModuleMemoryClassLoader;
import java.io.*;
import java.net.URI;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import javax.tools.ToolProvider;

/** Synthetic signed transport; never contacts a network service. */
public final class ProviderFixture implements AutoCloseable {
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private static byte[] bytes(JsonObject value,String field){return Base64.getUrlDecoder().decode(value.get(field).getAsString());}
    private static byte[] utf(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static String hash(byte[] value)throws Exception{return BetaDeliveryClient.sha256(value);}
    private static String encoded(byte[] value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    private static byte[] raw(KeyPair pair){byte[] value=pair.getPublic().getEncoded();return Arrays.copyOfRange(value,value.length-32,value.length);}

        final JsonObject golden,catalog,pin,session;
        final KeyPair keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final KeyPair installation=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final KeyPair tokenKeys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final DpopProofSigner signer=new DpopProofSigner(installation.getPrivate(),raw(installation));
        final AtomicReference<ModuleDeliveryClient.Session> current=new AtomicReference<>();
        final Path cache=Files.createTempDirectory("celeste-module-delivery-check-");
        String token;
        public volatile int status=200,transfers,authorizations,catalogRequests;
        public volatile String busyPath;
        public volatile int busyRemaining;
        @FunctionalInterface public interface RetryPause { void pause(long millis) throws InterruptedException; }
        public volatile RetryPause retryPause=Thread::sleep;
        public volatile java.util.function.UnaryOperator<ModuleDeliveryClient.Session> observeSession=value->value;
        public final List<List<String>> authorizationBatches=java.util.Collections.synchronizedList(new ArrayList<>());
        boolean badCatalog,badPersonalization,replay;
        Throwing duringTransfer;
        public ProviderFixture(JsonObject golden) throws Exception {
            this.golden=golden;catalog=golden.getAsJsonObject("catalog").deepCopy();
            pin=catalog.deepCopy();pin.remove("runtime_sha256");pin.remove("executable_root_sha256");pin.addProperty("schema","celeste-module-executables-1");
            long now=Instant.now().getEpochSecond();
            session=new JsonObject();
            session.addProperty("aud","celeste-session");session.addProperty("product","celeste_plus");session.addProperty("status","active");
            session.addProperty("sub","123");session.addProperty("device","D".repeat(32));session.addProperty("release",catalog.get("release_id").getAsString());
            session.addProperty("client_version","fixture-v1");session.addProperty("client_sha256",catalog.get("runtime_sha256").getAsString());
            session.addProperty("beta_catalog_sha256","b".repeat(64));
            JsonObject core=new JsonObject();core.addProperty("system","1".repeat(64));
            core.addProperty("board","2".repeat(64));core.addProperty("processor","3".repeat(64));
            session.addProperty("hardware_sha256",hash(utf(BetaDeliveryClient.canonical(core))));
            session.addProperty("hwid_sha256","d".repeat(64));session.addProperty("process_id",12345);
            session.addProperty("session_id","fixture_session_123456789");session.addProperty("iat",now);
            session.addProperty("nbf",now-5);session.addProperty("exp",now+89);
            JsonObject cnf=new JsonObject();cnf.addProperty("jkt",signer.thumbprint());session.add("cnf",cnf);
            restoreIdentity();
        }
        public String moduleId(){return catalog.getAsJsonArray("modules").get(0).getAsJsonObject().get("id").getAsString();}
        void restoreIdentity() throws Exception {token=sign(session);current.set(ModuleDeliveryClient.verifiedSession(token,System.nanoTime()+89_000_000_000L,raw(keys)));}
        void replaceIdentity() throws Exception {
            JsonObject changed=session.deepCopy();changed.addProperty("process_id",98765);
            current.set(ModuleDeliveryClient.verifiedSession(sign(changed),System.nanoTime()+89_000_000_000L,raw(keys)));
        }
        public ModuleDeliveryClient client() throws Exception {
            return new ModuleDeliveryClient(URI.create("https://example.invalid"),raw(keys),signer,()->observeSession.apply(current.get()),
                catalog.get("runtime_sha256").getAsString(),"b".repeat(64),utf(BetaDeliveryClient.canonical(pin)),
                new ModuleCiphertextCache(cache),this::controlledRequest,()->
                    ModuleDeliveryClient.evidence("1".repeat(64),"2".repeat(64),"3".repeat(64),List.of("4".repeat(64)),
                        "d".repeat(64),catalog.get("runtime_sha256").getAsString(),message->{
                            Signature proof=Signature.getInstance("Ed25519");proof.initSign(tokenKeys.getPrivate());
                            proof.update(message);return proof.sign();
                        }),millis->retryPause.pause(millis));
        }
        JsonObject binding(Map<String,String> headers,String audience) throws Exception {
            JsonObject value=session.deepCopy();value.remove("status");value.addProperty("aud",audience);
            value.addProperty("host_abi",1);value.addProperty("catalog_sha256",golden.get("catalog_sha256").getAsString());
            value.addProperty("executable_root_sha256",catalog.get("executable_root_sha256").getAsString());
            value.addProperty("session_token_sha256",hash(token.getBytes(StandardCharsets.US_ASCII)));
            value.addProperty("evidence_sha256",hash(Base64.getUrlDecoder().decode(headers.get("X-Celeste-Module-Evidence"))));
            value.addProperty("request_nonce",replay?"X".repeat(32):headers.get("X-Celeste-Request-Nonce"));return value;
        }
        @FunctionalInterface public interface Boundary { void apply(String path) throws Exception; }
        public volatile Boundary before = path -> {}, after = path -> {};
        private BetaDeliveryClient.Reply controlledRequest(String method,URI uri,Map<String,String> headers,byte[] body,int limit,Duration timeout) throws IOException {
            try { before.apply(uri.getPath());try {return request(method,uri,headers,body,limit,timeout);}finally{after.apply(uri.getPath());} }
            catch(Exception failure){throw new IOException("Controlled fixture transport stopped",failure);}
        }
        private JsonObject record(String identity,boolean artifact) {
            for(JsonElement element:golden.getAsJsonArray("artifacts")) {
                JsonObject record=element.getAsJsonObject();JsonObject manifest=record.getAsJsonObject("manifest");
                if(manifest.get(artifact?"artifact_id":"module_id").getAsString().equals(identity))return record;
            }
            throw new IllegalArgumentException("Unapproved fixture module");
        }
        BetaDeliveryClient.Reply request(String method,URI uri,Map<String,String> headers,byte[] body,int limit,Duration timeout)
                throws IOException {
            try {
                check(timeout.toSeconds()<=20,"Transport deadline bounded");
                JsonObject proof=BetaDeliveryClient.object(Base64.getUrlDecoder().decode(headers.get("DPoP").split("\\.")[1]));
                check(proof.get("bht").getAsString().equals(encoded(MessageDigest.getInstance("SHA-256").digest(body))),"Every request body is proof-bound");
                byte[] evidence=Base64.getUrlDecoder().decode(headers.get("X-Celeste-Module-Evidence"));
                check(proof.get("meh").getAsString().equals(encoded(MessageDigest.getInstance("SHA-256").digest(evidence))),
                    "Every request binds measured hardware and integrity evidence");
                Signature tokenCheck=Signature.getInstance("Ed25519");tokenCheck.initVerify(tokenKeys.getPublic());
                tokenCheck.update(utf("celeste-module-proof-v1\n"+headers.get("DPoP")));
                check(tokenCheck.verify(Base64.getUrlDecoder().decode(headers.get("X-Celeste-Module-Token-Proof"))),
                    "Every request proves possession of the independent token key");
                if(uri.getPath().endsWith("/catalog"))catalogRequests++;
                int responseStatus=status;boolean busy=false;
                synchronized(this){if(busyPath!=null&&uri.getPath().contains(busyPath)&&busyRemaining>0){busyRemaining--;responseStatus=503;busy=true;}}
                if(responseStatus!=200)return new BetaDeliveryClient.Reply(uri,responseStatus,busy?Map.of("Retry-After",List.of("1")):Map.of(),new byte[0]);
                byte[] response;
                if(uri.getPath().endsWith("/catalog")) {
                    JsonObject value=new JsonObject(),sent=catalog.deepCopy();
                    if(badCatalog)sent.getAsJsonArray("modules").get(0).getAsJsonObject().addProperty("sha256","0".repeat(64));
                    value.add("catalog",sent);value.addProperty("proof",sign(binding(headers,"celeste-module-catalog")));response=utf(value.toString());
                } else if(uri.getPath().endsWith("/authorize")) {
                    synchronized(this){authorizations++;}
                    List<String> batch=new ArrayList<>();for(JsonElement id:BetaDeliveryClient.object(body).getAsJsonArray("modules"))batch.add(id.getAsString());authorizationBatches.add(List.copyOf(batch));
                    JsonArray list=new JsonArray();
                    for(JsonElement requested:BetaDeliveryClient.object(body).getAsJsonArray("modules")) {
                        String identity=requested.getAsString();JsonObject record=record(identity,false);
                        JsonObject manifest=record.getAsJsonObject("manifest").deepCopy();
                        JsonObject claims=binding(headers,"celeste-module-capability");claims.addProperty("module_id",identity);claims.add("manifest",manifest);
                        JsonObject item=new JsonObject();item.addProperty("module_id",identity);item.addProperty("capability",sign(claims));
                        item.addProperty("key",record.get("key_base64url").getAsString());item.add("manifest",manifest);
                        item.addProperty("url","/v1/modules/artifacts/"+catalog.get("release_id").getAsString()+"/"+manifest.get("artifact_id").getAsString());list.add(item);
                    }
                    JsonObject value=new JsonObject();value.addProperty("catalog_sha256",golden.get("catalog_sha256").getAsString());
                    value.add("expires_at",session.get("exp"));value.add("capabilities",list);response=utf(value.toString());
                } else {
                    synchronized(this){transfers++;}
                    if(duringTransfer!=null) { Throwing action=duringTransfer;duringTransfer=null;action.run(); }
                    response=bytes(record(uri.getPath().substring(uri.getPath().lastIndexOf('/')+1),true),"ciphertext_base64url");
                }
                byte[] encrypted=BetaDeliveryClientCheck.seal(response,uri.getPath(),token,
                    headers.get("X-Celeste-Request-Nonce"),headers.get("X-Celeste-Delivery-Key"));
                return new BetaDeliveryClient.Reply(uri,200,Map.of("Content-Type",List.of("application/octet-stream"),
                    "Content-Length",List.of(Integer.toString(encrypted.length))),encrypted);
            }catch(Exception failure){throw new IOException("Fixture server failed",failure);}
        }
        String sign(JsonObject claims) throws Exception {
            String head=encoded(utf("{\"alg\":\"EdDSA\",\"kid\":\"celeste-release-1\",\"typ\":\"CST\"}"));
            String data=head+"."+encoded(utf(claims.toString()));
            Signature signature=Signature.getInstance("Ed25519");signature.initSign(keys.getPrivate());signature.update(utf(data));
            return data+"."+encoded(signature.sign());
        }
        @Override public void close(){signer.close();}
    }
