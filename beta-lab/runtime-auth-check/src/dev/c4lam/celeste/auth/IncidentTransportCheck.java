package dev.c4lam.celeste.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import static dev.c4lam.celeste.auth.SecurityIncidentProtocol.*;

/** Synthetic credentials and HTTP only; never probes hardware or opens a socket. */
public final class IncidentTransportCheck {
    private static final Gson JSON = new Gson();
    private static final String DIGEST = "a".repeat(64), CATALOG = "b".repeat(64), HWID = "d".repeat(64);
    private static final String DEVICE = "10000000-0000-0000-0000-000000000007", SESSION = "S".repeat(32);
    private static final HardwareFingerprint FINGERPRINT = new HardwareFingerprint(DIGEST,DIGEST,DIGEST,List.of(DIGEST));
    private static final String CORE = AuthServiceClient.coreHardwareDigest(FINGERPRINT);
    private static int checks;
    private IncidentTransportCheck() { }

    public static void main(String[] args) throws Exception {
        try (Fixture fixture = new Fixture()) {
            check(fixture.client.incidentScope(fixture.session(null)) == null, "absent signed policy disables reporting");
            rejected(() -> fixture.client.incidentScope(fixture.session("all")), "unknown policy rejected");
            check(fixture.client.incidentScope(fixture.session("license")) == Scope.LICENSE, "license scope requires signed explicit policy");
            String unsigned = fixture.session("celeste_plus");
            rejected(() -> fixture.client.incidentScope(unsigned.substring(0,unsigned.length()-8)+"AAAAAAAA"), "tampered scope rejected");
            for(String field:List.of("sub","device","hardware_sha256","hwid_sha256","session_id","release","client_sha256","beta_catalog_sha256","product")) {
                JsonObject altered=JSON.fromJson(new String(Crypto.b64(unsigned.split("\\.")[1]),StandardCharsets.UTF_8),JsonObject.class);
                altered.addProperty(field,field.endsWith("sha256")?"f".repeat(64):"different");
                rejected(()->fixture.client.incidentScope(fixture.signed(altered)),"signed wrong "+field+" rejected");
            }
            rejected(()->fixture.reporter(System.nanoTime()-1,()->new Hardware(CORE,DIGEST,HWID)),"expired original lease rejected");
            rejected(()->fixture.reporter(System.nanoTime()+TimeUnit.SECONDS.toNanos(91),()->new Hardware(CORE,DIGEST,HWID)),"overlong original lease rejected");
            AtomicInteger probes = new AtomicInteger();
            Thread caller = Thread.currentThread();
            try (var reporter = fixture.reporter(System.nanoTime()+TimeUnit.SECONDS.toNanos(30), () -> {
                check(Thread.currentThread()!=caller, "hardware measurement stays off caller thread");
                int count=probes.incrementAndGet();return new Hardware(CORE, count==1?DIGEST:CATALOG, HWID);
            })) {
                String result=await(reporter.report(request(reporter.binding(),Phase.BEGIN,1), System.nanoTime()+TimeUnit.SECONDS.toNanos(30)));
                check(result.equals("fixture-decision"), "compact response returned for independent signature verifier");
                await(reporter.report(request(reporter.binding(),Phase.CONFIRM,2),System.nanoTime()+TimeUnit.SECONDS.toNanos(5)));
                await(reporter.report(request(reporter.binding(),Phase.OUTCOME,3),System.nanoTime()+TimeUnit.SECONDS.toNanos(5)));
                check(probes.get()==2, "begin and confirm fresh; outcome uses original evidence");
                check(fixture.http.bodies.get(0).get("monitor_sha256").getAsString().equals(DIGEST)
                    &&fixture.http.bodies.get(1).get("monitor_sha256").getAsString().equals(CATALOG)
                    &&fixture.http.bodies.get(2).get("monitor_sha256").getAsString().equals(DIGEST), "outcome retains original begin hardware");
                check(fixture.http.ids.size()==3, "every request has a fresh installation proof");
                Binding wrong=new Binding(42,DEVICE,12345,SESSION,"wrong-release",CATALOG,DIGEST,Scope.PLUS);
                failed(reporter.report(request(wrong,Phase.BEGIN,4),System.nanoTime()+TimeUnit.SECONDS.toNanos(1)), "wrong request binding rejected");
            }
            check(!fixture.credential.isDestroyed(), "reporter does not destroy shared credential");
        }
        for (String mode : List.of("wrong-origin","deny","oversize","wrong-type","extra-field","duplicate")) {
            try (Fixture fixture=new Fixture(); var reporter=fixture.reporter(System.nanoTime()+TimeUnit.SECONDS.toNanos(30),()->new Hardware(CORE,DIGEST,HWID))) {
                fixture.http.mode=mode;
                failed(reporter.report(request(reporter.binding(),Phase.BEGIN,1),System.nanoTime()+TimeUnit.SECONDS.toNanos(1)), mode+" rejects response");
                check(fixture.http.bodies.size()==1, "incident rejection does not retry");
            }
        }
        try (Fixture fixture=new Fixture(); var reporter=fixture.reporter(System.nanoTime()+TimeUnit.SECONDS.toNanos(30),()->new Hardware(CORE,DIGEST,"f".repeat(64)))) {
            failed(reporter.report(request(reporter.binding(),Phase.BEGIN,1),System.nanoTime()+TimeUnit.SECONDS.toNanos(1)),"changed live device rejected");
            check(fixture.http.bodies.isEmpty(),"changed identity never reaches service");
        }
        try (Fixture fixture=new Fixture()) {
            CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
            try (var reporter=fixture.reporter(System.nanoTime()+TimeUnit.SECONDS.toNanos(30),()->{
                entered.countDown();
                try { release.await(); } catch(InterruptedException canceled) { Thread.currentThread().interrupt(); }
                return new Hardware(CORE,DIGEST,HWID);
            })) {
                var call=reporter.report(request(reporter.binding(),Phase.BEGIN,1),System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(150));
                check(entered.await(1,TimeUnit.SECONDS), "worker entered hardware seam");
                failed(call,"deadline includes hardware preparation");release.countDown();
                check(fixture.http.bodies.isEmpty(), "expired preparation cannot dispatch HTTP");
            }
        }
        try (Fixture fixture=new Fixture(); var reporter=fixture.reporter(System.nanoTime()+TimeUnit.SECONDS.toNanos(30),()->new Hardware(CORE,DIGEST,HWID))) {
            fixture.http.mode="hang";
            var call=reporter.report(request(reporter.binding(),Phase.BEGIN,1),System.nanoTime()+TimeUnit.SECONDS.toNanos(5));
            check(fixture.http.sent.await(1,TimeUnit.SECONDS),"async network started");
            call.cancel();failed(call,"explicit cancellation reaches response");
            check(fixture.http.pending.isCancelled(),"explicit cancellation reaches HTTP future");
            fixture.client.close();
            failed(reporter.report(request(reporter.binding(),Phase.BEGIN,2),System.nanoTime()+TimeUnit.SECONDS.toNanos(1)),"service close retires reporter");
        }
        try (Fixture fixture=new Fixture(); var reporter=fixture.reporter(System.nanoTime()+TimeUnit.SECONDS.toNanos(30),()->new Hardware(CORE,DIGEST,HWID))) {
            fixture.http.mode="hang";
            var call=reporter.report(request(reporter.binding(),Phase.BEGIN,1),System.nanoTime()+TimeUnit.SECONDS.toNanos(5));
            check(fixture.http.sent.await(1,TimeUnit.SECONDS),"close-race HTTP started");
            var callback=call.response().handle((value,failure)->{
                check(!Thread.holdsLock(fixture.client),"service cancellation callbacks run outside service monitor");
                check(!Thread.holdsLock(reporter),"service cancellation callbacks run outside reporter monitor");
                fixture.client.close();return true;
            });
            fixture.client.close();
            check(callback.toCompletableFuture().get(1,TimeUnit.SECONDS),"reentrant close completes without lock inversion");
            check(fixture.http.pending.isCancelled(),"service close cancels pending network");
        }
        try (Fixture fixture=new Fixture(); var reporter=fixture.reporter(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(700),()->new Hardware(CORE,DIGEST,HWID))) {
            failed(reporter.report(request(reporter.binding(),Phase.OUTCOME,1),System.nanoTime()+TimeUnit.SECONDS.toNanos(1)),"outcome requires dispatched confirmation");
            await(reporter.report(request(reporter.binding(),Phase.BEGIN,2),System.nanoTime()+TimeUnit.SECONDS.toNanos(1)));
            await(reporter.report(request(reporter.binding(),Phase.CONFIRM,3),System.nanoTime()+TimeUnit.SECONDS.toNanos(1)));
            Thread.sleep(750);
            failed(reporter.report(request(reporter.binding(),Phase.CONFIRM,4),System.nanoTime()+TimeUnit.SECONDS.toNanos(1)),"original lease cannot be extended for confirmation");
            await(reporter.report(request(reporter.binding(),Phase.OUTCOME,5),System.nanoTime()+TimeUnit.SECONDS.toNanos(1)));
            check(fixture.http.bodies.size()==3,"outcome retrieval grace adds no begin/confirm authority");
        }
        System.out.println("PASS incident transport: "+checks+" assertions; synthetic HTTP and hardware only");
    }
    private static Request request(Binding binding,Phase phase,int index) {
        long now=System.currentTimeMillis();String first="F".repeat(24);
        return new Request(binding,"I".repeat(24),"N".repeat(23)+index,phase,first,
            new Sample(Observation.debugger(),phase==Phase.BEGIN?first:"O".repeat(23)+index,now),
            phase==Phase.BEGIN?"":"C".repeat(24),now);
    }
    private static String await(SecurityIncidentController.Call call) throws Exception {
        return call.response().toCompletableFuture().get(2,TimeUnit.SECONDS);
    }
    private static void failed(SecurityIncidentController.Call call,String message) throws Exception {
        try { await(call);throw new AssertionError(message); }
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.CancellationException expected) { check(true,message); }
    }
    @FunctionalInterface private interface Checked {void run() throws Exception;}
    private static void rejected(Checked action,String message) throws Exception {
        try {action.run();throw new AssertionError(message);}catch(java.security.GeneralSecurityException expected){check(true,message);}
    }
    private static synchronized void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}

    private static final class Fixture implements AutoCloseable {
        final KeyPair server=Crypto.generateEd25519(), install=Crypto.generateEd25519(), token=Crypto.generateEd25519();
        final DpopProofSigner signer=new DpopProofSigner(install.getPrivate(),Crypto.rawPublic(install.getPublic()));
        final AuthConfig config=new AuthConfig(URI.create("https://auth.invalid"),Crypto.rawPublic(server.getPublic()),"incident-test",DIGEST,"1.0-test");
        final FakeHttp http=new FakeHttp(this);
        final InstallCredential credential;
        final AuthServiceClient client;
        Fixture() throws Exception {
            JsonObject certificate=new JsonObject();certificate.addProperty("aud","celeste-installation");
            certificate.addProperty("sub","42");certificate.addProperty("device",DEVICE);
            certificate.addProperty("install_key",Crypto.b64(Crypto.rawPublic(install.getPublic())));
            certificate.addProperty("nbf",Instant.now().getEpochSecond()-1);certificate.addProperty("exp",Instant.now().getEpochSecond()+600);
            byte[] encoded=token.getPrivate().getEncoded();
            credential=new InstallCredential(DEVICE,42,install.getPrivate().getEncoded(),Crypto.generateX25519().getPrivate().getEncoded(),
                Arrays.copyOfRange(encoded,encoded.length-32,encoded.length),signed(certificate),"00000000-0000-0000-0000-000000000007","FixtureOwner");
            client=new AuthServiceClient(config,new GameIdentity(credential.gameUuid(),credential.gameName()),CATALOG,http,
                new AuthServiceClient.SessionBinding(CORE,HWID,12345,SESSION));client.bindCredential(credential);
        }
        String signed(JsonObject claims) throws Exception {
            String input=Crypto.b64("{\"alg\":\"EdDSA\",\"typ\":\"CST\"}".getBytes(StandardCharsets.UTF_8))+"."+Crypto.b64(JSON.toJson(claims).getBytes(StandardCharsets.UTF_8));
            return input+"."+Crypto.b64(Crypto.sign(server.getPrivate(),input.getBytes(StandardCharsets.US_ASCII)));
        }
        String session(String scope) throws Exception {
            JsonObject c=new JsonObject();c.addProperty("aud","celeste-session");c.addProperty("product","celeste_plus");
            c.addProperty("sub","42");c.addProperty("device",DEVICE);c.addProperty("status","active");
            c.addProperty("release",config.releaseId());c.addProperty("client_version",config.clientVersion());
            c.addProperty("client_sha256",DIGEST);c.addProperty("beta_catalog_sha256",CATALOG);
            c.addProperty("hardware_sha256",CORE);c.addProperty("hwid_sha256",HWID);c.addProperty("process_id",12345);c.addProperty("session_id",SESSION);
            c.addProperty("nbf",Instant.now().getEpochSecond()-1);c.addProperty("exp",Instant.now().getEpochSecond()+60);
            JsonObject cnf=new JsonObject();cnf.addProperty("jkt",signer.thumbprint());c.add("cnf",cnf);
            if(scope!=null)c.addProperty("security_incident_scope",scope);return signed(c);
        }
        AuthServiceClient.IncidentTransport reporter(long deadline,AuthServiceClient.IncidentHardware hardware)throws Exception {
            return client.incidentTransport(credential,session("celeste_plus"),deadline,hardware);
        }
        @Override public void close(){client.close();credential.close();signer.close();}
    }

    private static final class FakeHttp extends HttpClient {
        final Fixture fixture;final List<JsonObject> bodies=new ArrayList<>();final java.util.Set<String> ids=new java.util.HashSet<>();
        final CountDownLatch sent=new CountDownLatch(1);volatile CompletableFuture<?> pending;String mode="";
        FakeHttp(Fixture fixture){this.fixture=fixture;}
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler) {
            try {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
                    public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
                    public void onNext(ByteBuffer buffer){byte[] b=new byte[buffer.remaining()];buffer.get(b);bytes.writeBytes(b);}
                    public void onError(Throwable error){throw new AssertionError(error);}public void onComplete(){}
                });
                byte[] body=bytes.toByteArray();JsonObject decoded=JSON.fromJson(new String(body,StandardCharsets.UTF_8),JsonObject.class);bodies.add(decoded);
                check(decoded.size()==25,"exact25 canonical fields");
                String proof=request.headers().firstValue("DPoP").orElseThrow();String[] parts=proof.split("\\.");
                check(Crypto.verify(fixture.install.getPublic(),(parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII),Crypto.b64(parts[2])),"installation proof verifies");
                JsonObject claims=JSON.fromJson(new String(Crypto.b64(parts[1]),StandardCharsets.UTF_8),JsonObject.class);
                check(claims.get("bht").getAsString().equals(Crypto.b64(Crypto.sha256(body))),"proof binds exact body");
                check(claims.get("hwid").getAsString().equals(HWID)&&!claims.has("cek")&&!claims.has("cnonce"),"incident proof has hardware without fake module fields");
                String grant=request.headers().firstValue("Authorization").orElseThrow();check(grant.startsWith("DPoP "),"DPoP auth scheme");
                check(claims.get("ath").getAsString().equals(Crypto.b64(Crypto.sha256(grant.substring(5).getBytes(StandardCharsets.US_ASCII)))),"proof binds original session");
                check(ids.add(claims.get("jti").getAsString()),"unique fresh DPoP identifier");
                check(Crypto.verify(fixture.token.getPublic(),("celeste-incident-proof-v1\n"+proof).getBytes(StandardCharsets.US_ASCII),
                    Crypto.b64(request.headers().firstValue("X-Celeste-Incident-Token-Proof").orElseThrow())),"independent token proof verifies");
                check(request.uri().equals(URI.create("https://auth.invalid/v1/security/incidents")),"exact same origin route");
                check(request.timeout().orElseThrow().compareTo(Duration.ofSeconds(5))<=0,"HTTP timeout<=5seconds");
                CompletableFuture<HttpResponse<T>> future=new CompletableFuture<>();pending=future;sent.countDown();
                if(mode.equals("hang"))return future;
                String content=mode.equals("extra-field")?"{\"decision\":\"fixture-decision\",\"extra\":1}":mode.equals("duplicate")?"{\"decision\":\"first\",\"decision\":\"fixture-decision\"}":"{\"decision\":\"fixture-decision\"}";
                byte[] response=mode.equals("oversize")?new byte[16385]:content.getBytes(StandardCharsets.UTF_8);
                HttpResponse.ResponseInfo info=new HttpResponse.ResponseInfo(){
                    public int statusCode(){return mode.equals("deny")?403:200;}
                    public HttpHeaders headers(){return HttpHeaders.of(Map.of("Content-Type",List.of(mode.equals("wrong-type")?"text/html":"application/json")),(a,b)->true);}
                    public Version version(){return Version.HTTP_1_1;}
                };
                HttpResponse.BodySubscriber<T> subscriber=handler.apply(info);
                subscriber.onSubscribe(new Flow.Subscription(){public void request(long count){}public void cancel(){}});
                subscriber.onNext(List.of(ByteBuffer.wrap(response)));subscriber.onComplete();
                subscriber.getBody().whenComplete((value,error)->{
                    if(error!=null)future.completeExceptionally(error);
                    else future.complete(new Response<>(request,value,info,mode.equals("wrong-origin")));
                });return future;
            }catch(Exception error){return CompletableFuture.failedFuture(error);}
        }
        @Override public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler){throw new AssertionError("Synchronous HTTP forbidden");}
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){throw new AssertionError();}
        @Override public Optional<CookieHandler> cookieHandler(){return Optional.empty();}
        @Override public Optional<Duration> connectTimeout(){return Optional.empty();}
        @Override public Redirect followRedirects(){return Redirect.NEVER;}
        @Override public Optional<ProxySelector> proxy(){return Optional.empty();}
        @Override public SSLContext sslContext(){try{return SSLContext.getDefault();}catch(Exception e){throw new AssertionError(e);}}
        @Override public SSLParameters sslParameters(){return new SSLParameters();}
        @Override public Optional<Authenticator> authenticator(){return Optional.empty();}
        @Override public Version version(){return Version.HTTP_1_1;}
        @Override public Optional<Executor> executor(){return Optional.empty();}
    }
    private record Response<T>(HttpRequest request,T body,HttpResponse.ResponseInfo information,boolean wrongOrigin) implements HttpResponse<T> {
        public int statusCode(){return information.statusCode();}public HttpHeaders headers(){return information.headers();}
        public Optional<HttpResponse<T>> previousResponse(){return Optional.empty();}public Optional<SSLSession> sslSession(){return Optional.empty();}
        public URI uri(){return wrongOrigin?URI.create("https://foreign.invalid/"):request.uri();}public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
    }
}
