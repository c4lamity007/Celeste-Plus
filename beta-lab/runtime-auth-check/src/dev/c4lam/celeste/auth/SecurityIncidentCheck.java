package dev.c4lam.celeste.auth;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayDeque;
import static dev.c4lam.celeste.auth.SecurityIncidentProtocol.*;

/** Local synthetic signatures and injected actions only. Never calls Windows, server, or Minecraft. */
public final class SecurityIncidentCheck {
    private static int assertions;
    private static final Binding BINDING=new Binding(7,"d".repeat(32),1234,"s".repeat(32),"fixture-release",
        "a".repeat(64),"b".repeat(64),Scope.PLUS);
    private static final Observation DETECTED=Observation.debugger();
    private static final KeyPair SERVER=key();
    static final class Time implements SecurityIncidentController.Clock {
        long nanos;
        @Override public long nanos(){return nanos;}
        @Override public long epochMillis(){return 1_000_000+nanos/1_000_000L;}
        long epochSeconds(){return epochMillis()/1000;}
        void advance(long millis){nanos+=millis*1_000_000L;}
    }
    static final class Pending implements SecurityIncidentController.Call {
        final Request request;final CompletableFuture<String> future=new CompletableFuture<>();final long deadline;int cancels;
        Pending(Request request,long deadline){this.request=request;this.deadline=deadline;}
        Request request(){return request;}CompletableFuture<String> future(){return future;}
        public CompletableFuture<String> response(){return future;}
        public void cancel(){cancels++;} // Simulate cancellation that cannot stop an already-dispatched remote request.
    }
    static final class Fixture implements AutoCloseable {
        final Time time=new Time(); final ArrayList<Pending> sent=new ArrayList<>();
        final ArrayDeque<Runnable> queue=new ArrayDeque<>();
        int ids, quarantines, stops, releases, failures;long generation=7;
        boolean throwQuarantine,throwStop,throwQueue,throwRelease,throwTransport;
        final SecurityIncidentController controller;
        Fixture(){this(90_000);}
        Fixture(long leaseMillis){controller=new SecurityIncidentController(BINDING,SERVER.getPublic(),time,
            ()->"fixture-id-"+String.format(java.util.Locale.ROOT,"%022d",++ids),
            (request,deadline)->{if(throwTransport)throw new IllegalStateException("synthetic send");var call=new Pending(request,deadline);sent.add(call);return call;},
            new SecurityIncidentController.Effects(){
                public void enqueue(Runnable action){if(throwQueue)throw new IllegalStateException("synthetic queue");queue.add(action);}
                public void quarantine(){if(throwQuarantine)throw new IllegalStateException("synthetic quarantine");quarantines++;}
                public void scheduleStop(){if(throwStop)throw new IllegalStateException("synthetic stop");stops++;}
                public void releaseReporter(){releases++;if(throwRelease)throw new IllegalStateException("synthetic release");}
                public void failed(Throwable failure){failures++;}
            },7,()->generation,leaseMillis*1_000_000);}
        Pending last(){return sent.getLast();}
        void detect(){controller.poll(DETECTED);}
        void drain(){while(!queue.isEmpty())queue.remove().run();}
        void accept()throws Exception{Pending last=last();last.future().complete(response(last.request(),time,Map.of(),SERVER));}
        void begin()throws Exception{detect();drain();accept();controller.renderedWarningFrame();}
        void frames(int count)throws Exception{for(int n=0;n<count;n++){time.advance(50);controller.renderedWarningFrame();detect();}}
        @Override public void close(){controller.close();}
    }
    private static KeyPair key(){try{return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();}catch(GeneralSecurityException e){throw new AssertionError(e);}}
    private static String encoded(byte[] value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    private static String response(Request r,Time t,Map<String,String> change,KeyPair signer)throws Exception{
        Binding b=r.binding();Observation o=r.observation();JsonObject c=new JsonObject();
        c.addProperty("aud","celeste-security-incident");c.addProperty("product","celeste_plus");
        c.addProperty("sub",Long.toString(b.account()));c.addProperty("device",b.device());
        c.addProperty("process_id",b.process());c.addProperty("session_id",b.session());
        c.addProperty("release",b.release());c.addProperty("beta_catalog_sha256",b.catalog());c.addProperty("client_sha256",b.hostDigest());
        c.addProperty("scope",b.scope()==Scope.PLUS?"celeste_plus":"license");c.addProperty("incident_id",r.incident());c.addProperty("request_nonce",r.nonce());
        c.addProperty("phase",r.phase().name().toLowerCase(java.util.Locale.ROOT));c.addProperty("signal",o.kind().name().toLowerCase(java.util.Locale.ROOT));
        c.addProperty("first_observation_id",r.firstObservationId());c.addProperty("observation_id",r.sample().id());c.addProperty("observed_at_ms",r.sample().atMillis());
        c.addProperty("artifact",o.artifact());c.addProperty("expected_sha256",o.expected());c.addProperty("observed_sha256",o.observed());
        c.addProperty("iat",t.epochSeconds());c.addProperty("nbf",t.epochSeconds());c.addProperty("exp",t.epochSeconds()+30);
        c.addProperty("status",r.phase()==Phase.BEGIN?"warning":"revoked");
        c.addProperty("confirm_after",t.epochSeconds()+(r.phase()==Phase.BEGIN?5:0));
        c.addProperty("challenge_id",r.phase()==Phase.BEGIN?"challenge-"+"c".repeat(24):r.challenge());
        change.forEach(c::addProperty);
        return sign(c.toString(),signer);
    }
    private static String sign(String payload,KeyPair signer)throws Exception{
        String body=encoded("{\"alg\":\"EdDSA\",\"kid\":\"celeste-release-1\",\"typ\":\"CST\"}".getBytes(StandardCharsets.UTF_8))+"."+encoded(payload.getBytes(StandardCharsets.UTF_8));
        Signature signature=Signature.getInstance("Ed25519");signature.initSign(signer.getPrivate());signature.update(body.getBytes(StandardCharsets.US_ASCII));
        return body+"."+encoded(signature.sign());
    }
    private static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
    private static void rejects(Request r,Time time,String token,String why)throws Exception{
        try{SecurityIncidentProtocol.verify(SERVER.getPublic(),r,token,time.epochSeconds(),time.nanos());throw new AssertionError(why);}
        catch(GeneralSecurityException expected){assertions++;}
    }
    public static void main(String[] args)throws Exception{
        warningThenSecondConfirmation();visibleAndServerTime();clearedAndUnavailable();signedContext();
        retriesAndRaces();narrowClassifications();boundsAndActions();canonicalWire();
        System.out.println("PASS security incident proposal assertions="+assertions+"; synthetic keys/actions only; no backend/Windows/game access");
    }
    private static void warningThenSecondConfirmation()throws Exception{
        try(Fixture f=new Fixture()){
            f.controller.poll(null);check(f.sent.isEmpty()&&!f.controller.accessBlocked(),"No signal does nothing");
            f.detect();f.drain();for(int n=0;n<50;n++)f.detect();
            check(f.sent.size()==1&&f.quarantines==1&&f.stops==0,"Repeated callbacks share first incident");
            check(f.controller.showWarning()&&f.controller.accessBlocked(),"First detection warns and blocks access");
            f.accept();f.controller.renderedWarningFrame();f.frames(99);
            check(f.sent.size()==1&&f.stops==0,"4.95 visible seconds cannot confirm");f.frames(1);
            check(f.sent.size()==2&&f.last().request().phase()==Phase.CONFIRM,"Fresh confirmation after five seconds");
            check(!f.sent.getFirst().request().nonce().equals(f.last().request().nonce()),"Confirmation uses fresh nonce");
            check(f.sent.getFirst().request().incident().equals(f.last().request().incident()),"Same logical incident");
            f.accept();f.drain();check(f.stops==1&&f.controller.state()==SecurityIncidentController.State.REVOKED,"Only signed confirmation schedules self-stop");
            for(int n=0;n<25;n++)f.detect();check(f.stops==1&&f.sent.size()==2,"Terminal outcome is exact-once");
        }
    }
    private static void visibleAndServerTime()throws Exception{
        try(Fixture f=new Fixture()){
            f.detect();f.last().future().complete(response(f.last().request(),f.time,Map.of("confirm_after","1008"),SERVER));
            f.controller.renderedWarningFrame();f.frames(100);check(f.sent.size()==1,"Signed eight-second bound takes precedence");
            f.time.advance(2999);f.detect();check(f.sent.size()==1,"Not before server bound");
            f.time.advance(1);f.detect();check(f.sent.size()==2,"Server bound inclusive");
        }
        try(Fixture f=new Fixture()){
            f.begin();f.time.advance(10_000);f.controller.renderedWarningFrame();f.detect();
            check(f.sent.size()==1,"Minimized/stalled frames do not satisfy visible warning");f.frames(100);
            check(f.sent.size()==2,"Five seconds of real frames qualifies afterwards");
        }
        try(Fixture f=new Fixture()){
            f.begin();f.time.advance(31_000);f.detect();
            check(f.sent.size()==1&&f.stops==0&&f.controller.state()==SecurityIncidentController.State.EXPIRED,"Expired challenge never confirms");
        }
    }
    private static void clearedAndUnavailable()throws Exception{
        for(boolean beforeResponse:new boolean[]{true,false})try(Fixture f=new Fixture()){
            f.detect();if(!beforeResponse)f.accept();f.controller.poll(null);if(beforeResponse)f.accept();
            f.controller.renderedWarningFrame();for(int i=0;i<100;i++){f.time.advance(50);f.controller.renderedWarningFrame();f.controller.poll(null);}
            check(f.stops==0&&f.sent.size()==1,"Cleared/unavailable signal never makes second strike");
            check(f.controller.accessBlocked(),"Local access stays quarantined pending explicit reauthorization");
            f.detect();check(f.sent.size()==2&&f.last().request().phase()==Phase.CONFIRM,"Later fresh matching detection can confirm");
        }
        try(Fixture f=new Fixture()){
            f.begin();f.controller.poll(new Observation(Kind.MODULE_MUTATION,"freecam","1".repeat(64),"2".repeat(64)));
            for(int i=0;i<100;i++){f.time.advance(50);f.controller.renderedWarningFrame();f.controller.poll(new Observation(Kind.MODULE_MUTATION,"freecam","1".repeat(64),"2".repeat(64)));}
            check(f.sent.size()==1,"Different family cannot confirm existing warning");
        }
        try(Fixture f=new Fixture()){
            Observation a=new Observation(Kind.MODULE_MUTATION,"freecam","1".repeat(64),"2".repeat(64));
            Observation b=new Observation(Kind.MODULE_MUTATION,"freecam","1".repeat(64),"3".repeat(64));
            f.controller.poll(a);f.accept();f.controller.renderedWarningFrame();
            for(int i=0;i<100;i++){f.time.advance(50);f.controller.renderedWarningFrame();f.controller.poll(b);}
            check(f.sent.size()==2&&f.last().request().observation().equals(b),"Fresh mismatch may have different observed bytes in same family");
        }
    }
    private static void signedContext()throws Exception{
        try(Fixture f=new Fixture()){
            f.detect();Request r=f.last().request();
            rejects(r,f.time,response(r,f.time,Map.of(),key()),"Wrong signing key");
            String valid=response(r,f.time,Map.of(),SERVER);rejects(r,f.time,valid.substring(0,valid.lastIndexOf('.')+1),"Unsigned response");
            for(var change:Map.ofEntries(Map.entry("aud","celeste-session"),Map.entry("product","celeste"),
                    Map.entry("sub","8"),Map.entry("device","e".repeat(32)),Map.entry("process_id","1235"),Map.entry("session_id","t".repeat(32)),
                    Map.entry("release","other-release"),Map.entry("beta_catalog_sha256","3".repeat(64)),Map.entry("client_sha256","4".repeat(64)),
                    Map.entry("scope","license"),Map.entry("incident_id","i".repeat(32)),Map.entry("request_nonce","n".repeat(32)),
                    Map.entry("phase","confirm"),Map.entry("signal","module_mutation"),Map.entry("artifact","other"),
                    Map.entry("expected_sha256","5".repeat(64)),Map.entry("observed_sha256","6".repeat(64)),Map.entry("status","revoked"),
                    Map.entry("confirm_after","1004"),Map.entry("exp","1000"),Map.entry("iat","1010"),Map.entry("nbf","1010"),
                    Map.entry("challenge_id","short"),Map.entry("first_observation_id","wrong-"+"x".repeat(24)),
                    Map.entry("observation_id","wrong-"+"y".repeat(24)),Map.entry("observed_at_ms","1000001")).entrySet()){
                rejects(r,f.time,response(r,f.time,Map.of(change.getKey(),change.getValue()),SERVER),"Mismatched signed "+change.getKey());
            }
            rejects(r,f.time,response(r,f.time,Map.of("exp","1091"),SERVER),"Diagnostic lifetime cannot exceed90seconds");
            String duplicate="{\"aud\":\"a\",\"aud\":\"celeste-security-incident\"}";
            rejects(r,f.time,sign(duplicate,SERVER),"Duplicate signed JSON fields");
            rejects(r,f.time,sign("[]",SERVER),"Non-object signed response");
            rejects(r,f.time,sign("{\"aud\":true}",SERVER),"Typed boolean cannot masquerade as a string claim");
            f.last().future().complete(response(r,f.time,Map.of("request_nonce","x".repeat(32)),SERVER));
            check(f.stops==0&&f.controller.accessBlocked(),"Wrong bound response never exits");
        }
    }
    private static void retriesAndRaces()throws Exception{
        try(Fixture f=new Fixture()){
            f.detect();Request first=f.last().request();f.last().future().completeExceptionally(new java.io.IOException("synthetic transport"));
            f.time.advance(999);f.detect();check(f.sent.size()==1,"Bounded retry delay");f.time.advance(1);f.detect();
            Request retry=f.last().request();check(first.incident().equals(retry.incident())&&!first.nonce().equals(retry.nonce()),"Retry uses stable event/fresh proof nonce");
            rejects(retry,f.time,response(first,f.time,Map.of(),SERVER),"Old response cannot satisfy retry nonce");f.accept();
            f.controller.renderedWarningFrame();f.frames(100);f.last().future().completeExceptionally(new java.io.IOException("lost confirmation response"));
            Sample submitted=f.last().request().sample();f.time.advance(1000);f.controller.poll(null);
            check(f.last().request().phase()==Phase.OUTCOME&&f.sent.size()==4,"Ambiguous confirmation only queries read-only outcome");
            check(f.last().request().sample().equals(submitted),"Outcome binds exact submitted evidence identity");
            f.accept();f.drain();check(f.stops==1,"Committed outcome schedules one stop");
        }
        try(Fixture f=new Fixture()){
            f.begin();f.frames(100);f.controller.poll(null);f.accept();f.drain();
            check(f.stops==1,"Signal clearing after the submitted second observation cannot hide server revocation");
        }
        try(Fixture f=new Fixture()){
            f.begin();f.frames(100);f.time.advance(40_000);f.accept();f.controller.watchdog();f.drain();
            check(f.stops==0&&f.controller.state()==SecurityIncidentController.State.EXPIRED,"Receipt after bounded request/outcome deadline cannot exit");
        }
        for(boolean confirm:new boolean[]{false,true})try(Fixture f=new Fixture()){
            f.detect();if(confirm){f.accept();f.controller.renderedWarningFrame();f.frames(100);}f.controller.close();f.accept();
            check(f.stops==0&&f.controller.state()==SecurityIncidentController.State.CLOSED,"Late response cannot affect retired controller");
        }
    }
    private static void boundsAndActions()throws Exception{
        try(Fixture f=new Fixture()){
            f.begin();f.frames(100);Pending stuck=f.last();f.time.advance(5000);f.controller.watchdog();
            check(stuck.cancels==1&&f.last().request().phase()==Phase.OUTCOME,"Watchdog cancels hung confirm and starts outcome only");
            int count=f.sent.size();stuck.future.complete(response(stuck.request,f.time,Map.of(),SERVER));f.drain();
            check(f.stops==0&&f.sent.size()==count,"Retired response ignored even if signature valid");
            Sample canceled=f.last().request().sample();f.last().future.complete(response(f.last().request(),f.time,Map.of("status","pending"),SERVER));
            f.time.advance(1000);f.controller.poll(null);check(f.sent.size()==count,"Pending tombstone does not manufacture fresh observation");
            f.detect();check(f.last().request().phase()==Phase.CONFIRM&&!f.last().request().sample().id().equals(canceled.id()),"After definitive pending, fresh sample gets new attempt ID");
        }
        try(Fixture f=new Fixture(6000)){
            f.detect();Pending old=f.last();f.time.advance(5000);f.controller.watchdog();
            check(old.cancels==1&&f.sent.size()==2,"Hung begin is retired without waiting for completion");
            f.time.advance(1000);f.controller.watchdog();check(f.releases==1&&f.controller.state()==SecurityIncidentController.State.EXPIRED,"Host expiry releases reporter even when all responses hang");
            f.controller.close();check(f.releases==1,"Reporter release is exact once");
        }
        try(Fixture f=new Fixture(8000)){
            f.begin();f.time.advance(8000);f.controller.watchdog();
            check(f.sent.size()==1&&f.releases==1&&f.stops==0,"Invisible/minimized HUD never causes strike and has bounded lifetime");
        }
        try(Fixture f=new Fixture()){
            f.begin();f.frames(100);f.time.advance(5000);f.controller.watchdog();f.time.advance(5000);f.controller.watchdog();
            check(f.controller.state()==SecurityIncidentController.State.EXPIRED&&f.releases==1&&f.stops==0,"Outcome outage ends after ten-second retrieval bound");
        }
        for(boolean stop:new boolean[]{false,true})try(Fixture f=new Fixture()){
            f.detect();if(stop){f.drain();f.accept();f.controller.renderedWarningFrame();f.frames(100);f.accept();}
            f.controller.close();f.drain();check(f.stops==0&&(stop||f.quarantines==0),"Close while action queued suppresses old effects");
        }
        for(boolean stop:new boolean[]{false,true})try(Fixture f=new Fixture()){
            f.detect();if(stop){f.drain();f.accept();f.controller.renderedWarningFrame();f.frames(100);f.accept();}
            f.generation++;f.drain();check(f.stops==0&&(stop||f.quarantines==0),"Replacement generation never receives old queued effects");
        }
        try(Fixture f=new Fixture()){
            f.detect();f.generation++;f.accept();f.drain();
            check(f.controller.state()==SecurityIncidentController.State.CLOSED&&f.releases==1&&f.stops==0,"Replacement generation invalidates in-flight callback");
        }
        try(Fixture f=new Fixture()){
            f.throwQuarantine=true;f.begin();check(f.failures==1&&f.sent.size()==1&&f.controller.accessBlocked(),"Quarantine callback exception cannot unlock or kill reporter");
            f.frames(100);f.throwStop=true;f.accept();f.drain();
            check(f.failures==2&&f.stops==0&&f.controller.state()==SecurityIncidentController.State.REVOKED,"Stop callback failure is reported, never claimed as process exit");
        }
        try(Fixture f=new Fixture()){
            f.throwQueue=true;f.detect();check(f.sent.size()==1&&f.failures==1&&f.controller.accessBlocked(),"Queue failure does not prevent report or unblock dispatch");
            f.throwRelease=true;f.controller.close();check(f.failures==2&&f.releases==1,"Release exception is contained and not retried indefinitely");
        }
        try(Fixture f=new Fixture()){
            f.throwTransport=true;f.detect();check(f.sent.isEmpty()&&f.controller.accessBlocked(),"Synchronous transport failure is nonpunitive");
            f.throwTransport=false;f.time.advance(1000);f.controller.watchdog();check(f.sent.size()==1,"Fresh nonce retry remains available after synchronous failure");
        }
    }
    private static void canonicalWire(){
        try(Fixture f=new Fixture()){
            f.detect();byte[] body=SecurityIncidentProtocol.body(f.last().request(),new Hardware("1".repeat(64),"2".repeat(64),"3".repeat(64)));
            var object=new com.google.gson.Gson().fromJson(new String(body,StandardCharsets.UTF_8),JsonObject.class);
            check(object.size()==25&&object.get("first_observation_id").getAsString().equals(f.last().request().sample().id()),"Canonical wire binds original sample");
            check(object.get("process_id").isJsonPrimitive()&&object.get("process_id").getAsJsonPrimitive().isNumber(),"Process identity has exact numeric wire type");
            String text=new String(body,StandardCharsets.UTF_8);check(text.startsWith("{\"artifact\":")&&!text.contains(" "),"Canonical JSON sorted and compact");
        }
    }
    private static void narrowClassifications(){
        check(com.sun.jna.Pointer.nativeValue(WindowsSelfDebuggerProbe.currentProcessHandle())==-1L,
            "Current-process pseudo-handle preserves all 64 bits; int overload is invalid on x64");
        for(boolean direct:new boolean[]{false,true})for(boolean succeeded:new boolean[]{false,true})for(boolean remote:new boolean[]{false,true}){
            var result=WindowsSelfDebuggerProbe.classify(direct,succeeded,remote);
            check(result==(direct||succeeded&&remote?WindowsSelfDebuggerProbe.Result.CONFIRMED:succeeded?WindowsSelfDebuggerProbe.Result.CLEAR:WindowsSelfDebuggerProbe.Result.UNAVAILABLE),"Only direct self-debugger facts qualify");
        }
        var mismatch=new dev.c4lam.celeste.beta.memory.RetainedIntegrityException("a".repeat(64),"b".repeat(64));
        check(mismatch.expected().equals("a".repeat(64))&&mismatch.observed().equals("b".repeat(64)),"Typed mismatch retains exact compared hashes");
        try{new dev.c4lam.celeste.beta.memory.RetainedIntegrityException("a".repeat(64),"a".repeat(64));throw new AssertionError("Equal hash");}
        catch(IllegalArgumentException expected){assertions++;}
    }
}
