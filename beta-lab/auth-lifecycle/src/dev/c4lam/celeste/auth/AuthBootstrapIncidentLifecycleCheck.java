package dev.c4lam.celeste.auth;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Base64;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import static dev.c4lam.celeste.auth.SecurityIncidentProtocol.*;

/** Real production host/controller; synthetic key, transport, debugger probe and Minecraft executor. */
public final class AuthBootstrapIncidentLifecycleCheck {
    private static final MinecraftClient CLIENT=MinecraftClient.getInstance();
    private AuthBootstrapIncidentLifecycleCheck(){ }
    public static void main(String[] args)throws Exception {
        try {
            String scenario=args[0];
            AuthConfig.digest="a".repeat(64);ArtifactIntegrity.observed=AuthConfig.digest;ArtifactIntegrity.retainedMemory=true;
            AuthServiceClient.scope=scenario.equals("scope-unset")?null:Scope.PLUS;
            AuthBootstrap.configureBetaRuntime("c".repeat(64));
            if(scenario.equals("plus-startup-mismatch"))ArtifactIntegrity.observed="b".repeat(64);
            AuthBootstrap.install(()->{ },()->{ });
            ClientLifecycleEvents.CLIENT_STARTED.fire(CLIENT);barrier();CLIENT.drain();barrier();
            if(scenario.equals("plus-startup-mismatch")) {
                check(!AuthBootstrap.authorized()&&AuthServiceClient.tamperReports.get()==0&&AuthServiceClient.incidents.isEmpty(),"Unverified startup bytes never issue punitive report");
                return;
            }
            check(AuthBootstrap.authorized(),"Fixture host starts authorized");
            // Keep observation cadence deterministic; the supervisor remains the real independent watchdog.
            executor("SECURITY_WORKER").shutdownNow();
            if(scenario.equals("debugger-classification")) {
                WindowsSelfDebuggerProbe.result=WindowsSelfDebuggerProbe.Result.UNAVAILABLE;invoke("inspectSecurity");
                check(AuthBootstrap.authorized()&&AuthServiceClient.incidents.isEmpty(),"Unavailable self-debugger API never reports an incident");
                WindowsSelfDebuggerProbe.result=WindowsSelfDebuggerProbe.Result.CONFIRMED;invoke("inspectSecurity");CLIENT.drain();
                check(!AuthBootstrap.authorized()&&AuthServiceClient.incidents.size()==1
                    &&AuthServiceClient.incidents.getFirst().calls.getFirst().request.observation().kind()==Kind.SELF_DEBUGGER,
                    "Injected confirmed self-debugger signal follows same resident two-observation path");
                check(CLIENT.scheduledStops.get()==0,"First direct detection never stops the game");return;
            }
            if(scenario.equals("quarantine-start-expiry")) {
                ((AtomicBoolean)field("QUARANTINED")).set(true);set("sessionDeadlineNanos",System.nanoTime()-1);
                invoke("superviseHeartbeat");CLIENT.drain();
                check(AuthBootstrap.locked()&&InstallCredential.created.stream().allMatch(InstallCredential::isDestroyed),"Startup quarantine cannot retain keys after lease expires");return;
            }
            if(scenario.equals("warning-starved"))set("sessionDeadlineNanos",System.nanoTime()+TimeUnit.SECONDS.toNanos(1));
            ArtifactIntegrity.observed="b".repeat(64);invoke("heartbeat");CLIENT.drain();
            check(!AuthBootstrap.authorized(),"First confirmed mismatch immediately blocks gameplay");
            check(AuthServiceClient.tamperReports.get()==0,"Plus skips legacy immediate revocation call");
            if(scenario.equals("scope-unset")) {
                check(AuthBootstrap.locked()&&AuthServiceClient.incidents.isEmpty(),"Absent signed scope cannot select punitive policy");return;
            }
            var reporter=AuthServiceClient.incidents.getFirst();var controller=AuthBootstrap.warningController();
            check(controller!=null&&controller.showWarning(),"Resident warning exists independently of gameplay authorization");
            check(!reporter.credential.isDestroyed()&&!reporter.retired,"Diagnostic keys remain live during warning");
            int authentications=AuthServiceClient.authentications.get(),clears=CredentialStore.clears.get();
            AuthBootstrap.retry();AuthBootstrap.submitLicense("synthetic-rebind-attempt-never-real");AuthBootstrap.clearLocalBinding();barrier();
            check(AuthServiceClient.authentications.get()==authentications&&CredentialStore.clears.get()==clears
                &&AuthServiceClient.enrollments.get()==0,"Quarantine cannot replace or erase its live reporter identity");
            var begin=reporter.calls.getFirst();
            if(scenario.equals("warning-retirement")) {
                ClientLifecycleEvents.CLIENT_STOPPING.fire(CLIENT);
                begin.result.complete(response(begin.request,AuthConfig.incidentKey));CLIENT.drain();
                check(reporter.retired&&reporter.credential.isDestroyed()&&CLIENT.scheduledStops.get()==0,"Retired generation ignores late signed response");return;
            }
            if(scenario.equals("warning-untrusted")) {
                begin.result.complete(response(begin.request,java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()));CLIENT.drain();
                check(CLIENT.scheduledStops.get()==0&&!AuthBootstrap.authorized(),"Wrong-key warning cannot schedule game stop");return;
            }
            begin.result.complete(response(begin.request,AuthConfig.incidentKey));
            if(scenario.equals("warning-renderer")) {
                CLIENT.hudHidden=true;CLIENT.menuOpen=true;
                Object frame=SecurityWarningRenderer.beforeFlush();
                check(frame==controller&&dev.c4lam.celeste.render.font.Fonts.draws==1,"Auth-owned renderer submits while gameplay unauthorized, ordinary HUD hidden and menu open");
                check("Stop.".equals(dev.c4lam.celeste.render.font.Fonts.text)
                    &&dev.c4lam.celeste.render.font.Fonts.x==480f&&dev.c4lam.celeste.render.font.Fonts.y==259f
                    &&dev.c4lam.celeste.render.font.Fonts.color.equals(new java.awt.Color(255,48,65,255)),"Warning is centered and red");
                SecurityWarningRenderer.afterFlush(frame);
                CLIENT.focused=false;check(SecurityWarningRenderer.beforeFlush()==null,"Unfocused window never manufactures visible time");CLIENT.focused=true;
                org.lwjgl.glfw.GLFW.iconified=true;check(SecurityWarningRenderer.beforeFlush()==null,"Minimized window never manufactures visible time");org.lwjgl.glfw.GLFW.iconified=false;
                org.lwjgl.glfw.GLFW.visible=false;check(SecurityWarningRenderer.beforeFlush()==null,"Hidden window never manufactures visible time");org.lwjgl.glfw.GLFW.visible=true;
                dev.c4lam.celeste.render.font.Fonts.unavailable=true;check(SecurityWarningRenderer.beforeFlush()==null,"Font failure is contained so ordinary GUI flush can continue");
                ClientLifecycleEvents.CLIENT_STOPPING.fire(CLIENT);SecurityWarningRenderer.afterFlush(frame);
                check(CLIENT.scheduledStops.get()==0&&AuthBootstrap.warningController()==null,"Old rendered frame cannot affect retired generation");return;
            }
            if(scenario.equals("warning-starved")) {
                Thread.sleep(1100);invoke("superviseHeartbeat");CLIENT.drain();
                check(CLIENT.scheduledStops.get()==0&&reporter.retired&&reporter.credential.isDestroyed(),"Unrendered warning expires without a strike and releases keys");return;
            }
            boolean cleared=scenario.equals("warning-cleared");
            if(cleared)ArtifactIntegrity.observed=AuthConfig.digest;
            long start=System.nanoTime();controller.renderedWarningFrame();
            while(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(5200)) {
                Thread.sleep(25);controller.renderedWarningFrame();invoke("inspectSecurity");CLIENT.drain();
            }
            if(cleared) {
                check(reporter.calls.size()==1&&CLIENT.scheduledStops.get()==0,"Freshly cleared retained bytes never confirm");return;
            }
            check(reporter.calls.size()==2&&reporter.calls.getLast().request.phase()==Phase.CONFIRM,"Five visible seconds and fresh hash issue one confirmation");
            var confirm=reporter.calls.getLast();
            check(!confirm.request.sample().id().equals(begin.request.sample().id()),"Second observation identity is fresh");
            Thread callback=new Thread(()->{try{confirm.result.complete(response(confirm.request,AuthConfig.incidentKey));}catch(Exception failure){throw new AssertionError(failure);}},"fixture-response");
            callback.start();callback.join(2000);check(!callback.isAlive(),"Response callback completed");
            check(CLIENT.scheduledStops.get()==0&&reporter.retired&&reporter.credential.isDestroyed(),"Final callback closes keys then queues owner-thread stop");
            CLIENT.drain();check(CLIENT.scheduledStops.get()==1&&AuthBootstrap.locked(),"Only verified current-generation final receipt schedules exact game stop once");
            CLIENT.drain();check(CLIENT.scheduledStops.get()==1,"Repeated queue drain cannot duplicate stop");
        } finally {
            ClientLifecycleEvents.CLIENT_STOPPING.fire(CLIENT);CLIENT.drain();
            for(String name:new String[]{"WORKER","SUPERVISOR","SECURITY_WORKER"})executor(name).shutdownNow();
            System.out.println("Completed synthetic incident lifecycle: "+args[0]);
        }
    }
    private static String response(Request r,KeyPair signer)throws Exception {
        Binding b=r.binding();Observation o=r.observation();JsonObject c=new JsonObject();
        c.addProperty("aud","celeste-security-incident");c.addProperty("product","celeste_plus");c.addProperty("sub",Long.toString(b.account()));
        c.addProperty("device",b.device());c.addProperty("process_id",b.process());c.addProperty("session_id",b.session());
        c.addProperty("release",b.release());c.addProperty("beta_catalog_sha256",b.catalog());c.addProperty("client_sha256",b.hostDigest());
        c.addProperty("scope",b.scope()==Scope.PLUS?"celeste_plus":"license");c.addProperty("incident_id",r.incident());c.addProperty("request_nonce",r.nonce());
        c.addProperty("phase",r.phase().name().toLowerCase(java.util.Locale.ROOT));c.addProperty("signal",o.kind().name().toLowerCase(java.util.Locale.ROOT));
        c.addProperty("first_observation_id",r.firstObservationId());c.addProperty("observation_id",r.sample().id());c.addProperty("observed_at_ms",r.sample().atMillis());
        c.addProperty("artifact",o.artifact());c.addProperty("expected_sha256",o.expected());c.addProperty("observed_sha256",o.observed());
        long now=System.currentTimeMillis()/1000;c.addProperty("iat",now);c.addProperty("nbf",now);c.addProperty("exp",now+30);
        c.addProperty("status",r.phase()==Phase.BEGIN?"warning":"revoked");c.addProperty("confirm_after",now+(r.phase()==Phase.BEGIN?5:0));
        c.addProperty("challenge_id",r.phase()==Phase.BEGIN?"challenge-"+"c".repeat(24):r.challenge());
        String signed=encode("{\"alg\":\"EdDSA\",\"kid\":\"celeste-release-1\",\"typ\":\"CST\"}".getBytes(StandardCharsets.UTF_8))+"."+encode(c.toString().getBytes(StandardCharsets.UTF_8));
        Signature signature=Signature.getInstance("Ed25519");signature.initSign(signer.getPrivate());signature.update(signed.getBytes(StandardCharsets.US_ASCII));
        return signed+"."+encode(signature.sign());
    }
    private static String encode(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static Object field(String name)throws Exception {Field f=AuthBootstrap.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static void set(String name,Object value)throws Exception {Field f=AuthBootstrap.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
    private static ScheduledExecutorService executor(String name)throws Exception{return (ScheduledExecutorService)field(name);}
    private static void barrier()throws Exception{executor("WORKER").submit(()->{ }).get(3,TimeUnit.SECONDS);}
    private static void invoke(String name)throws Exception{Method m=AuthBootstrap.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(null);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
