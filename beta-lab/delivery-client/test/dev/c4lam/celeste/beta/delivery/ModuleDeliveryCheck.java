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

/** Offline contract checks against the actual Python personalization fixture and signed mock server. */
public final class ModuleDeliveryCheck {
    private static int assertions;
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);assertions++;}
    private static void rejected(Throwing action) throws Exception {
        try{action.run();}catch(GeneralSecurityException|IOException|IllegalArgumentException|SecurityException expected){assertions++;return;}
        throw new AssertionError("Expected rejection");
    }
    public static void main(String[] args) throws Exception {
        JsonObject golden=BetaDeliveryClient.object(Files.readAllBytes(Path.of("beta-lab/delivery-client/fixtures/module-delivery-v1-golden.json")),512);
        container(golden);delivery(golden);busyRetry(golden);loader();
        System.out.println("Module delivery: "+assertions+" assertions passed (offline fixtures)");
    }
    private static byte[] bytes(JsonObject value,String field){return Base64.getUrlDecoder().decode(value.get(field).getAsString());}
    private static byte[] utf(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static String hash(byte[] value)throws Exception{return BetaDeliveryClient.sha256(value);}
    private static String encoded(byte[] value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    private static void container(JsonObject golden) throws Exception {
        byte[] key=bytes(golden,"key_base64url"),cipher=bytes(golden,"ciphertext_base64url"),jar=bytes(golden,"approved_jar_base64url");
        JsonObject manifest=golden.getAsJsonObject("manifest");
        String catalog=golden.get("catalog_sha256").getAsString();
        check(Arrays.equals(jar,ModuleContainer.open(cipher,key,manifest,catalog)),"Actual Python container decrypts to exact approved executable");
        byte[] wrong=key.clone();wrong[0]^=1;
        rejected(()->ModuleContainer.open(cipher,wrong,manifest,catalog));
        rejected(()->ModuleContainer.open(cipher,key,manifest,"0".repeat(64)));
        for(int offset:new int[]{0,11,12,cipher.length/2,cipher.length-1}) {
            byte[] changed=cipher.clone();changed[offset]^=1;
            JsonObject revised=manifest.deepCopy();revised.addProperty("ciphertext_sha256",hash(changed));
            rejected(()->ModuleContainer.open(changed,key,revised,catalog));
        }
        for(String field:List.of("module_id","watermark","real_record","executable_sha256","entries_sha256")) {
            JsonObject changed=manifest.deepCopy();changed.addProperty(field,"wrong");
            rejected(()->ModuleContainer.open(cipher,key,changed,catalog));
        }
        Path cachePath=Files.createTempDirectory("celeste-module-cache-check-");
        ModuleCiphertextCache cache=new ModuleCiphertextCache(cachePath);
        cache.put(hash(cipher),cipher);
        check(Arrays.equals(cipher,cache.read(hash(cipher),cipher.length)),"Cache contains exact ciphertext");
        check(cache.read(hash(cipher),cipher.length+1)==null,"Cache size mismatches ignored");
        byte[] tampered=cipher.clone();tampered[20]^=1;
        rejected(()->cache.put(hash(cipher),tampered));
        Files.write(cachePath.resolve(hash(cipher)+".cstm"),tampered);
        check(cache.read(hash(cipher),cipher.length)==null,"Corrupt cache cannot reach decryption");
        rejected(()->cache.read("../outside",cipher.length));
    }
    private static void delivery(JsonObject golden) throws Exception {
        try(Fixture fixture=new Fixture(golden)) {
            Files.delete(fixture.cache);
            Files.writeString(fixture.cache,"Unavailable cache before client construction");
            ModuleDeliveryClient client=fixture.client();
            check(!client.authorized(fixture.moduleId()),"Unavailable cache cannot grant authorization");
            try(var prepared=client.load(fixture.moduleId())) {
                check(prepared.authorized(),"Client starts and authenticates even when cache was already unavailable");
                check(Arrays.equals(prepared.jar(),bytes(golden,"approved_jar_base64url")),"Memory fallback retains exact approved executable");
            }
            check(fixture.authorizations==1 && fixture.transfers==1,"Unavailable cache still requires live authorization and transfer");
            check(Files.isRegularFile(fixture.cache),"Memory fallback does not replace conflicting cache path");
            fixture.status=403;rejected(()->client.load(fixture.moduleId()));
            check(!client.authorized(fixture.moduleId()),"Memory fallback still fails closed on server denial");
        }
        try(Fixture fixture=new Fixture(golden)) {
            ModuleDeliveryClient client=fixture.client();
            String id=fixture.moduleId();
            check(!client.authorized(id),"No module authorization inferred from session or disk");
            byte[] owned;
            try(var prepared=client.load(id)) {
                check(client.authorized(id) && prepared.authorized(),"Verified capability enables exact module");
                check(Arrays.equals(prepared.jar(),bytes(golden,"approved_jar_base64url")),"Verified delivery preserves executable bytes");
                owned=prepared.jar();
            }
            check(allZero(owned),"Closing preparation wipes plaintext bytes");
            int transfers=fixture.transfers,authorizations=fixture.authorizations;
            try(var again=client.load(id)) {check(again.authorized(),"Warm re-enable uses live authorization");}
            check(fixture.transfers==transfers && fixture.authorizations==authorizations+1,"Warm re-enable reauthorizes without downloading cached ciphertext");
            fixture.replaceIdentity();
            check(!client.authorized(id),"Capabilities do not survive another signed process/session identity");
            fixture.restoreIdentity();
            fixture.status=401;
            rejected(()->client.authorize(List.of(id)));
            check(!client.authorized(id),"Explicit denial revokes all existing keys");
        }
        try(Fixture fixture=new Fixture(golden)) {
            ModuleDeliveryClient client=fixture.client();
            fixture.badCatalog=true;rejected(client::fetchCatalog);
        }
        try(Fixture fixture=new Fixture(golden)) {
            ModuleDeliveryClient client=fixture.client();
            fixture.badPersonalization=true;rejected(()->client.load(fixture.moduleId()));
            check(!client.authorized(fixture.moduleId()),"Personalization cannot replace approved executable");
        }
        try(Fixture fixture=new Fixture(golden)) {
            ModuleDeliveryClient client=fixture.client();
            fixture.replay=true;rejected(client::fetchCatalog);
        }
        try(Fixture fixture=new Fixture(golden)) {
            ModuleDeliveryClient client=fixture.client();
            String id=fixture.moduleId();
            try(var first=client.load(id)) {check(first.authorized(),"Initial activation authorized");}
            fixture.status=503;client.renewActive();
            check(client.authorized(id),"No active modules means no renewal request");
            fixture.current.set(null);
            check(!client.authorized(id),"Missing runtime lease blocks cached capability");
            rejected(()->client.load(id));
        }
        try(Fixture fixture=new Fixture(golden)) {
            ModuleDeliveryClient client=fixture.client();
            String id=fixture.moduleId();
            try(var first=client.load(id)) {
                int before=fixture.authorizations;
                client.renewActive();
                check(first.authorized() && fixture.authorizations==before+1,"Active module renewal retains existing implementation authorization");
            }
            client.close();check(!client.authorized(id),"Explicit close disables cached modules");
            ModuleDeliveryClient restarted=fixture.client();
            fixture.status=403;rejected(()->restarted.load(id));
            check(!restarted.authorized(id),"Ciphertext cache cannot authorize another runtime client");
        }
        try(Fixture fixture=new Fixture(golden)) {
            ModuleDeliveryClient client=fixture.client();
            fixture.duringTransfer=()->client.authorize(List.of(fixture.moduleId()));
            try(var loaded=client.load(fixture.moduleId())) {
                check(loaded.authorized(),"In-flight download survives concurrent capability renewal");
                check(fixture.authorizations==2,"Renewal actually replaced the map key before decrypt");
            }
        }
        try(Fixture fixture=new Fixture(golden)) {
            ModuleDeliveryClient client=fixture.client();
            try(var original=client.load(fixture.moduleId())) {
                fixture.session.addProperty("session_id","replacement_session_123456");
                fixture.restoreIdentity();
                check(!original.authorized(),"Original preparation loses permission after runtime identity changes");
                try(var replacement=client.load(fixture.moduleId())) {
                    check(replacement.authorized(),"Replacement session may obtain its own live authorization");
                    check(!original.authorized(),"New session permission cannot revive old prepared handles");
                    rejected(original::jar);
                }
            }
        }
        try(Fixture fixture=new Fixture(golden)) {
            fixture.session.addProperty("exp",Instant.now().getEpochSecond()+20);
            fixture.restoreIdentity();
            ModuleDeliveryClient client=fixture.client();
            try(var prepared=client.load(fixture.moduleId())) {
                check(prepared.authorized(),"Fresh module permission remains valid near the runtime lease boundary");
                check(fixture.authorizations==1,"Near-expiry activation does not repeat a live authorization that cannot extend its host lease");
            }
            try(var prepared=client.load(fixture.moduleId())) {
                check(prepared.authorized() && fixture.authorizations==2,"Warm activation still performs its own fresh authorization");
            }
        }
        try(Fixture fixture=new Fixture(golden)) {
            ModuleDeliveryClient client=fixture.client();
            Files.delete(fixture.cache);
            Files.writeString(fixture.cache,"Unavailable optional cache");
            try(var prepared=client.load(fixture.moduleId())) {
                check(prepared.authorized(),"Unavailable persistent cache does not block authenticated in-memory delivery");
                check(Arrays.equals(prepared.jar(),bytes(golden,"approved_jar_base64url")),"Cache failure never skips exact executable verification");
            }
            fixture.badPersonalization=true;
            rejected(()->client.load(fixture.moduleId()));
        }
        try(Fixture fixture=new Fixture(golden)) {
            ModuleDeliveryClient client=fixture.client();
            client.fetchCatalog();
            var verified=ModuleDeliveryClient.class.getDeclaredField("verified");verified.setAccessible(true);
            Object initial=verified.get(client);
            try(var prepared=client.load(fixture.moduleId())) {
                check(initial!=null && verified.get(client)==initial,"Repeated checks reuse only the same immutable verified session");
                fixture.restoreIdentity();
                client.authorize(List.of(fixture.moduleId()));
                check(verified.get(client)!=initial && prepared.authorized(),"Replacement heartbeat session is independently verified while preserving same-identity leases");
                long before=System.nanoTime();
                for(int i=0;i<100_000;i++)if(!prepared.authorized())throw new AssertionError("Live authorization expired during hot-path check");
                System.out.println("Module dispatch authorization: "+((System.nanoTime()-before)/100_000)+" ns/check (100000 checks; diagnostic, no timing threshold)");
                fixture.current.set(ModuleDeliveryClient.verifiedSession(fixture.token,System.nanoTime()+10_000_000L,raw(fixture.keys)));
                Thread.sleep(20);
                check(!prepared.authorized(),"Cached verification never extends the monotonic runtime lease");
                rejected(()->client.load(fixture.moduleId()));
            }
            client.close();check(verified.get(client)==null,"Client retirement drops its cached signed session reference");
        }
        try(Fixture fixture=new Fixture(golden)) {
            ModuleDeliveryClient client=fixture.client();
            client.close();client.close();
            check(fixture.transportCloses==1,"Retiring module delivery releases its transport ownership exactly once");
        }
        var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        try(DpopProofSigner signer=new DpopProofSigner(pair.getPrivate(),raw(pair))) {
            byte[] body=utf("{\"modules\":[\"example\"]}");
            String proof=signer.proofForModule("POST",URI.create("https://example.invalid/v1/modules/authorize"),"token",
                encoded(new byte[32]),encoded(new byte[24]),"1".repeat(64),body);
            JsonObject claims=BetaDeliveryClient.object(Base64.getUrlDecoder().decode(proof.split("\\.")[1]));
            check(claims.get("bht").getAsString().equals(encoded(MessageDigest.getInstance("SHA-256").digest(body))),"DPoP binds exact request body");
        }
    }
    private static boolean allZero(byte[] bytes){for(byte b:bytes)if(b!=0)return false;return true;}
    private static void busyRetry(JsonObject golden)throws Exception{
        for(String path:List.of("/catalog","/authorize","/artifacts/"))try(Fixture fixture=new Fixture(golden)){
            fixture.busyPath=path;fixture.busyRemaining=2;fixture.statusHeaders=Map.of("Retry-After",List.of("1"));
            List<Long> waits=new ArrayList<>();fixture.retryWait=waits::add;
            try(ModuleDeliveryClient client=fixture.client();var prepared=client.load(fixture.moduleId())){
                check(prepared.authorized() && waits.size()==2,"Busy "+path+" recovers within the same bounded activation");
                check(waits.stream().allMatch(delay->delay>=1000&&delay<=1250),"Retry respects server delay plus bounded jitter");
                List<Map<String,String>> repeated=new ArrayList<>();
                for(int i=0;i<fixture.requestPaths.size();i++)if(fixture.requestPaths.get(i).contains(path))repeated.add(fixture.requestHeaders.get(i));
                check(repeated.size()==3,"Only busy operation is retried");
                for(String header:List.of("DPoP","X-Celeste-Request-Nonce","X-Celeste-Delivery-Key","X-Celeste-Module-Token-Proof"))
                    check(repeated.stream().map(values->values.get(header)).distinct().count()==3,"Each retry generates fresh "+header);
                check(fixture.evidenceCollections==fixture.requestHeaders.size(),"Every retry recollects current hardware/client evidence");
            }
        }
        try(Fixture fixture=new Fixture(golden)){
            fixture.status=503;fixture.statusHeaders=Map.of("Retry-After",List.of("1"));List<Long> waits=new ArrayList<>();fixture.retryWait=waits::add;
            try(var client=fixture.client()){
                rejected(client::fetchCatalog);
                check(fixture.requestHeaders.size()==4&&waits.size()==3,"Repeated busy response stops after exactly three retries");
                check(waits.stream().mapToLong(Long::longValue).sum()<=3750 && !client.authorized(fixture.moduleId()),"Busy retry cannot authorize or wait without bounds");
            }
        }
        for(int status:List.of(401,403,409,429))try(Fixture fixture=new Fixture(golden)){
            fixture.status=status;fixture.statusHeaders=Map.of("Retry-After",List.of("1"));fixture.retryWait=delay->{throw new AssertionError("Denied response was retried");};
            try(var client=fixture.client()){rejected(client::fetchCatalog);check(fixture.requestHeaders.size()==1,"Status "+status+" never retries");}
        }
        for(List<String> header:List.of(List.<String>of(),List.of("0"),List.of("4"),List.of("bogus"),List.of("1","1")))try(Fixture fixture=new Fixture(golden)){
            fixture.status=503;fixture.statusHeaders=Map.of("Retry-After",header);fixture.retryWait=delay->{throw new AssertionError("Invalid delay was retried");};
            try(var client=fixture.client()){rejected(client::fetchCatalog);check(fixture.requestHeaders.size()==1,"Missing/invalid/long/duplicate retry hints are not accepted");}
        }
        try(Fixture fixture=new Fixture(golden)){
            fixture.status=503;fixture.wrongOrigin=true;fixture.statusHeaders=Map.of("Retry-After",List.of("1"));fixture.retryWait=delay->{throw new AssertionError("Wrong origin was retried");};
            try(var client=fixture.client()){rejected(client::fetchCatalog);check(fixture.requestHeaders.size()==1,"Wrong response origin cannot request retry");}
        }
        for(boolean malformed:List.of(false,true))try(Fixture fixture=new Fixture(golden)){
            fixture.networkFailure=!malformed;fixture.badCatalog=malformed;fixture.statusHeaders=Map.of("Retry-After",List.of("1"));fixture.retryWait=delay->{throw new AssertionError("Failure was retried");};
            try(var client=fixture.client()){rejected(client::fetchCatalog);check(fixture.requestHeaders.size()==1,"Network/integrity failures never enter busy retry");}
        }
        try(Fixture fixture=new Fixture(golden)){
            fixture.status=503;fixture.statusHeaders=Map.of("Retry-After",List.of("1"));
            fixture.retryWait=delay->{fixture.current.set(null);};
            try(var client=fixture.client()){rejected(client::fetchCatalog);check(fixture.requestHeaders.size()==1,"Lost host lease stops retry before the next request");}
        }
        try(Fixture fixture=new Fixture(golden)){
            fixture.status=503;fixture.statusHeaders=Map.of("Retry-After",List.of("1"));
            fixture.current.set(ModuleDeliveryClient.verifiedSession(fixture.token,System.nanoTime()+500_000_000L,raw(fixture.keys)));
            fixture.retryWait=delay->{throw new AssertionError("Wait beyond original deadline accepted");};
            try(var client=fixture.client()){
                try{client.fetchCatalog();throw new AssertionError("Insufficient retry budget accepted");}catch(IOException expected){assertions++;}
                check(fixture.requestHeaders.size()==1,"Retry delay cannot extend original host deadline");
                fixture.status=200;fixture.restoreIdentity();client.fetchCatalog();
                check(fixture.requestHeaders.size()==2,"Insufficient busy budget does not revoke a still-valid session");
            }
        }
        try(Fixture fixture=new Fixture(golden)){
            fixture.status=503;fixture.statusHeaders=Map.of("Retry-After",List.of("1"));AtomicReference<ModuleDeliveryClient> current=new AtomicReference<>();
            fixture.retryWait=delay->current.get().close();
            try(var client=fixture.client()){current.set(client);rejected(client::fetchCatalog);check(fixture.requestHeaders.size()==1,"Client close during wait prevents another request");}
        }
        try(Fixture fixture=new Fixture(golden)){
            fixture.status=503;fixture.statusHeaders=Map.of("Retry-After",List.of("1"));fixture.retryWait=delay->{throw new InterruptedException("fixture wait cancelled");};
            try(var client=fixture.client()){
                try{client.fetchCatalog();throw new AssertionError("Interrupted retry succeeded");}catch(InterruptedException expected){assertions++;}
                check(fixture.requestHeaders.size()==1,"Worker interruption propagates without another wire attempt");
            }
        }
        try(Fixture fixture=new Fixture(golden);var client=fixture.client()){
            Thread.currentThread().interrupt();
            try{client.fetchCatalog();throw new AssertionError("Interrupted worker started request");}
            catch(InterruptedException expected){check(Thread.currentThread().isInterrupted(),"Already interrupted worker preserves cancellation flag");}
            finally{Thread.interrupted();}
            check(fixture.requestHeaders.isEmpty()&&fixture.evidenceCollections==0,"Already interrupted worker performs no measurement or wire request");
        }
    }
    private static byte[] raw(KeyPair pair){byte[] value=pair.getPublic().getEncoded();return Arrays.copyOfRange(value,value.length-32,value.length);}
    private static final class Fixture implements AutoCloseable {
        final JsonObject golden,catalog,pin,session;
        final KeyPair keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final KeyPair installation=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final KeyPair tokenKeys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final DpopProofSigner signer=new DpopProofSigner(installation.getPrivate(),raw(installation));
        final AtomicReference<ModuleDeliveryClient.Session> current=new AtomicReference<>();
        final Path cache=Files.createTempDirectory("celeste-module-delivery-check-");
        String token;
        int status=200,transfers,authorizations,transportCloses;
        int busyRemaining,evidenceCollections;
        String busyPath;
        boolean wrongOrigin,networkFailure;
        Map<String,List<String>> statusHeaders=Map.of();
        final List<Map<String,String>> requestHeaders=new ArrayList<>();
        final List<String> requestPaths=new ArrayList<>();
        ModuleDeliveryClient.RetryWait retryWait=Thread::sleep;
        boolean badCatalog,badPersonalization,replay;
        Throwing duringTransfer;
        Fixture(JsonObject golden) throws Exception {
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
        String moduleId(){return catalog.getAsJsonArray("modules").get(0).getAsJsonObject().get("id").getAsString();}
        void restoreIdentity() throws Exception {token=sign(session);current.set(ModuleDeliveryClient.verifiedSession(token,System.nanoTime()+89_000_000_000L,raw(keys)));}
        void replaceIdentity() throws Exception {
            JsonObject changed=session.deepCopy();changed.addProperty("process_id",98765);
            current.set(ModuleDeliveryClient.verifiedSession(sign(changed),System.nanoTime()+89_000_000_000L,raw(keys)));
        }
        ModuleDeliveryClient client() throws Exception {
            return new ModuleDeliveryClient(URI.create("https://example.invalid"),raw(keys),signer,current::get,
                catalog.get("runtime_sha256").getAsString(),"b".repeat(64),utf(BetaDeliveryClient.canonical(pin)),
                new ModuleCiphertextCache(cache),new ModuleDeliveryClient.Transport(){
                    @Override public BetaDeliveryClient.Reply request(String method,URI uri,Map<String,String> headers,byte[] body,int limit,Duration timeout)throws IOException{
                        return Fixture.this.request(method,uri,headers,body,limit,timeout);
                    }
                    @Override public void close(){transportCloses++;}
                },()->{evidenceCollections++;return
                    ModuleDeliveryClient.evidence("1".repeat(64),"2".repeat(64),"3".repeat(64),List.of("4".repeat(64)),
                        "d".repeat(64),catalog.get("runtime_sha256").getAsString(),message->{
                            Signature proof=Signature.getInstance("Ed25519");proof.initSign(tokenKeys.getPrivate());
                            proof.update(message);return proof.sign();
                        });},retryWait);
        }
        JsonObject binding(Map<String,String> headers,String audience) throws Exception {
            JsonObject value=session.deepCopy();value.remove("status");value.addProperty("aud",audience);
            value.addProperty("host_abi",1);value.addProperty("catalog_sha256",golden.get("catalog_sha256").getAsString());
            value.addProperty("executable_root_sha256",catalog.get("executable_root_sha256").getAsString());
            value.addProperty("session_token_sha256",hash(token.getBytes(StandardCharsets.US_ASCII)));
            value.addProperty("evidence_sha256",hash(Base64.getUrlDecoder().decode(headers.get("X-Celeste-Module-Evidence"))));
            value.addProperty("request_nonce",replay?"X".repeat(32):headers.get("X-Celeste-Request-Nonce"));return value;
        }
        BetaDeliveryClient.Reply request(String method,URI uri,Map<String,String> headers,byte[] body,int limit,Duration timeout)
                throws IOException {
            try {
                requestHeaders.add(Map.copyOf(headers));requestPaths.add(uri.getPath());
                if(networkFailure)throw new IOException("Synthetic network failure");
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
                int responseStatus=status;
                if(busyPath!=null&&uri.getPath().contains(busyPath)&&busyRemaining>0){busyRemaining--;responseStatus=503;}
                if(responseStatus!=200)return new BetaDeliveryClient.Reply(wrongOrigin?URI.create("https://elsewhere.invalid/v1/modules/catalog"):uri,responseStatus,statusHeaders,new byte[0]);
                byte[] response;
                if(uri.getPath().endsWith("/catalog")) {
                    JsonObject value=new JsonObject(),sent=catalog.deepCopy();
                    if(badCatalog)sent.getAsJsonArray("modules").get(0).getAsJsonObject().addProperty("sha256","0".repeat(64));
                    value.add("catalog",sent);value.addProperty("proof",sign(binding(headers,"celeste-module-catalog")));response=utf(value.toString());
                } else if(uri.getPath().endsWith("/authorize")) {
                    authorizations++;
                    JsonObject manifest=golden.getAsJsonObject("manifest").deepCopy();
                    if(badPersonalization)manifest.addProperty("executable_sha256","0".repeat(64));
                    JsonObject claims=binding(headers,"celeste-module-capability");claims.addProperty("module_id",moduleId());claims.add("manifest",manifest);
                    JsonObject item=new JsonObject();item.addProperty("module_id",moduleId());item.addProperty("capability",sign(claims));
                    item.addProperty("key",golden.get("key_base64url").getAsString());item.add("manifest",manifest);
                    item.addProperty("url","/v1/modules/artifacts/"+catalog.get("release_id").getAsString()+"/"+manifest.get("artifact_id").getAsString());
                    JsonArray list=new JsonArray();list.add(item);
                    JsonObject value=new JsonObject();value.addProperty("catalog_sha256",golden.get("catalog_sha256").getAsString());
                    value.add("expires_at",session.get("exp"));value.add("capabilities",list);response=utf(value.toString());
                } else {
                    transfers++;
                    if(duringTransfer!=null) { Throwing action=duringTransfer;duringTransfer=null;action.run(); }
                    response=bytes(golden,"ciphertext_base64url");
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
    private static void loader() throws Exception {
        Path root=Files.createTempDirectory("celeste-module-loader-check-");
        Path source=root.resolve("Entry.java");
        Files.writeString(source,"package fixture.independent; public final class Entry {public String value(){return \"loaded\";}}");
        check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",root.toString(),source.toString())==0,"Compiled actual disposable module");
        Map<String,byte[]> entries=new TreeMap<>();
        entries.put("fixture/independent/Entry.class",Files.readAllBytes(root.resolve("fixture/independent/Entry.class")));
        entries.put("fixture/resource.txt",utf("resource"));
        entries.put("assets/celeste/fixture.txt",utf("verified asset"));
        byte[] jar=jar(entries);String digest=entriesDigest(entries);
        assertions+=ModuleRetainedReadConcurrencyCheck.run(jar,hash(jar),digest);
        ModuleMemoryClassLoader loader=ModuleMemoryClassLoader.open(ModuleDeliveryCheck.class.getClassLoader(),jar,hash(jar),digest);
        Map<String,byte[]> assets=loader.assetSnapshot();
        check(assets.size()==1 && assets.containsKey("assets/celeste/fixture.txt"),"Only verified game assets exported");
        Arrays.fill(assets.get("assets/celeste/fixture.txt"),(byte)0);
        check(loader.getResourceAsStream("assets/celeste/fixture.txt").read()=='v',"Asset buffers have independent ownership");
        Class<?> type=loader.loadClass("fixture.independent.Entry");
        loader.verifyIntegrity();
        check(type.getClassLoader()==loader,"Module defined by isolated loader");
        check(type.getMethod("value").invoke(type.getConstructor().newInstance()).equals("loaded"),"Isolated implementation executes");
        InputStream stream=loader.getResourceAsStream("fixture/resource.txt");
        java.net.URL url=loader.getResource("fixture/resource.txt");
        check(stream.read()=='r',"Owned resource readable");loader.close();
        rejected(stream::read);rejected(()->url.openStream().read());
        check(loader.isClosed(),"Loader retired");
        try { loader.assetSnapshot(); throw new AssertionError("Retired asset export accepted"); }
        catch (IllegalStateException expected) { assertions++; }
        rejected(()->ModuleMemoryClassLoader.open(ModuleDeliveryCheck.class.getClassLoader(),jar,"0".repeat(64),digest));
        rejected(()->ModuleMemoryClassLoader.open(ModuleDeliveryCheck.class.getClassLoader(),jar,hash(jar),"0".repeat(64)));
        try (ModuleMemoryClassLoader modified=ModuleMemoryClassLoader.open(ModuleDeliveryCheck.class.getClassLoader(),jar,hash(jar),digest)) {
            var retained=ModuleMemoryClassLoader.class.getDeclaredField("entries");retained.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String,byte[]> actual=(Map<String,byte[]>)retained.get(modified);
            actual.get("assets/celeste/fixture.txt")[0]^=1;
            rejected(modified::verifyIntegrity);
        }
        Map<String,byte[]> invalid=new TreeMap<>(entries);invalid.put("../escape",new byte[0]);byte[] traversal=jar(invalid);
        rejected(()->ModuleMemoryClassLoader.open(ModuleDeliveryCheck.class.getClassLoader(),traversal,hash(traversal),entriesDigest(invalid)));
        invalid.remove("../escape");invalid.put("java/lang/Fake.class",entries.values().iterator().next());byte[] platform=jar(invalid);
        rejected(()->ModuleMemoryClassLoader.open(ModuleDeliveryCheck.class.getClassLoader(),platform,hash(platform),entriesDigest(invalid)));
        invalid.remove("java/lang/Fake.class");invalid.put("native/test.dll",new byte[0]);byte[] nativeJar=jar(invalid);
        rejected(()->ModuleMemoryClassLoader.open(ModuleDeliveryCheck.class.getClassLoader(),nativeJar,hash(nativeJar),entriesDigest(invalid)));
        byte[] sized=storedJar(entries);
        try(var direct=ModuleMemoryClassLoader.open(ModuleDeliveryCheck.class.getClassLoader(),sized,hash(sized),digest)) {
            check(direct.loadClass("fixture.independent.Entry").getClassLoader()==direct,"Declared-size entries authenticate and define normally");
            direct.verifyIntegrity();assertions++;
        }
        byte[] oversized=sized.clone();ByteBuffer.wrap(oversized).order(ByteOrder.LITTLE_ENDIAN).putInt(22,8*1024*1024+1);
        rejected(()->ModuleMemoryClassLoader.open(ModuleDeliveryCheck.class.getClassLoader(),oversized,hash(oversized),digest));
        byte[] truncated=Arrays.copyOf(sized,45);
        rejected(()->ModuleMemoryClassLoader.open(ModuleDeliveryCheck.class.getClassLoader(),truncated,hash(truncated),digest));
        Map<String,byte[]> streamingOversized=new TreeMap<>(entries);streamingOversized.put("fixture/large.bin",new byte[8*1024*1024+1]);
        byte[] streaming=jar(streamingOversized);
        rejected(()->ModuleMemoryClassLoader.open(ModuleDeliveryCheck.class.getClassLoader(),streaming,hash(streaming),entriesDigest(streamingOversized)));
    }
    private static byte[] storedJar(Map<String,byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){for(var entry:entries.entrySet()){
            CRC32 crc=new CRC32();crc.update(entry.getValue());ZipEntry record=new ZipEntry(entry.getKey());
            record.setMethod(ZipEntry.STORED);record.setSize(entry.getValue().length);record.setCrc(crc.getValue());
            zip.putNextEntry(record);zip.write(entry.getValue());zip.closeEntry();}}
        return bytes.toByteArray();
    }
    private static byte[] jar(Map<String,byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){for(var entry:entries.entrySet()){
            zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
        return bytes.toByteArray();
    }
    private static String entriesDigest(Map<String,byte[]> entries) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(var entry:entries.entrySet()){
            byte[] name=utf(entry.getKey());hash.update(ByteBuffer.allocate(4).putInt(name.length).array());hash.update(name);
            hash.update(ByteBuffer.allocate(8).putLong(entry.getValue().length).array());hash.update(entry.getValue());
        }return HexFormat.of().formatHex(hash.digest());
    }
}
