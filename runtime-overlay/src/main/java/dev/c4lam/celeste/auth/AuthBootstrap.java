package dev.c4lam.celeste.auth;

import dev.c4lam.celeste.license.LicenseIdentity;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;

public final class AuthBootstrap {
    private static volatile ScheduledExecutorService WORKER = executor("cst-license-gateway");
    private static final ScheduledExecutorService SUPERVISOR = executor("cst-license-supervisor");
    private static final ScheduledExecutorService SECURITY_WORKER = executor("cst-security-observer");
    private static ScheduledFuture<?> heartbeatTask;
    private static int workerRecoveries;
    private static final int MAX_WORKER_RECOVERIES = 3;
    private static CredentialStore STORE = new CredentialStore();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final AtomicBoolean AUTHORIZED = new AtomicBoolean();
    private static final AtomicBoolean LOCKED = new AtomicBoolean();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean STOPPED = new AtomicBoolean();
    private static final AtomicBoolean QUARANTINED = new AtomicBoolean();
    private static final AtomicBoolean INCIDENT_STARTING = new AtomicBoolean();
    private static final java.security.SecureRandom INCIDENT_RANDOM=new java.security.SecureRandom();
    private static volatile long authorizationGeneration;
    private static volatile SecurityIncidentProtocol.Scope incidentScope;
    private static volatile IncidentContext incident;
    private static volatile String status = "Waiting for license";
    private static volatile AuthConfig config;
    private static volatile AuthServiceClient service;
    private static volatile String expectedBetaCatalog;
    private static volatile HardwareFingerprint hardware;
    private static volatile InstallCredential sessionCredential;
    private static volatile InstallCredential pendingCredential;
    private static volatile String sessionGrant;
    private static volatile long sessionDeadlineNanos;
    private static volatile dev.c4lam.celeste.beta.delivery.ModuleDeliveryClient.Session moduleSession;
    private static volatile AuthenticatedModulePayloadProvider modulePayloads;
    private static Runnable authorizedInitializer;
    private static Runnable authorizedShutdown;
    private static volatile Consumer<Throwable> lateFailureObserver;
    private static volatile boolean lateInstallation;
    private static CompletableFuture<Void> lateStopCompletion;

    private AuthBootstrap() {
    }

    private static ScheduledExecutorService executor(String name) {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    public static void install(Runnable initializer, Runnable shutdown) {
        install(initializer, shutdown, false);
    }

    /** Same authentication as normal startup, for a host whose CLIENT_STARTED event already fired. */
    public static void installAfterClientStarted(Runnable initializer, Runnable shutdown) {
        install(initializer, shutdown, true);
    }

    /** Same required authentication, with failure observation for the late runtime host. */
    public static void installAfterClientStarted(Runnable initializer, Runnable shutdown, Consumer<Throwable> failureObserver) {
        install(initializer, shutdown, true, Objects.requireNonNull(failureObserver, "failureObserver"));
    }

    /** Request late-runtime authorization shutdown and observe actual worker termination. */
    public static synchronized CompletionStage<Void> stopAfterClientStarted() {
        if (!lateInstallation) return CompletableFuture.<Void>completedFuture(null).minimalCompletionStage();
        stop(MinecraftClient.getInstance());
        if (lateStopCompletion == null) {
            lateStopCompletion = CompletableFuture.runAsync(() -> {
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    if (!SUPERVISOR.awaitTermination(30, TimeUnit.SECONDS)
                        || !WORKER.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                        || !SECURITY_WORKER.awaitTermination(Math.max(0, deadline-System.nanoTime()),TimeUnit.NANOSECONDS)) {
                        throw new IllegalStateException("Authorization workers did not finish stopping within 30 seconds");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Authorization shutdown observation was interrupted", failure);
                }
            });
        }
        return lateStopCompletion.minimalCompletionStage();
    }

    /** Called by the beta host after verifying the server-signed artifact catalog, before installation. */
    public static synchronized void configureBetaRuntime(String catalogSha256) {
        if (INSTALLED.get() || expectedBetaCatalog != null) {
            throw new IllegalStateException("Beta runtime context must be selected once before installation");
        }
        if (catalogSha256 == null || !catalogSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Verified beta catalog hash is invalid");
        }
        STORE = new CredentialStore("celeste_plus");
        expectedBetaCatalog = catalogSha256;
    }

    private static synchronized void install(Runnable initializer, Runnable shutdown, boolean alreadyStarted) {
        install(initializer, shutdown, alreadyStarted, null);
    }

    private static synchronized void install(Runnable initializer, Runnable shutdown, boolean alreadyStarted,
                                             Consumer<Throwable> failureObserver) {
        Objects.requireNonNull(initializer, "initializer");
        Objects.requireNonNull(shutdown, "shutdown");
        if (!INSTALLED.compareAndSet(false, true)) {
            throw new IllegalStateException("Client authorization is already installed");
        }
        authorizedInitializer = initializer;
        authorizedShutdown = shutdown;
        lateInstallation = alreadyStarted;
        lateFailureObserver = failureObserver;
        if (alreadyStarted) {
            BetaSubscriptions.register(ClientLifecycleEvents.CLIENT_STOPPING, "celeste:auth/stopping",
                ClientLifecycleEvents.ClientStopping.class, AuthBootstrap::stop, ClientLifecycleEvents.CLIENT_STOPPING::register);
        } else {
            ClientLifecycleEvents.CLIENT_STOPPING.register(AuthBootstrap::stop);
        }
        heartbeatTask = WORKER.scheduleWithFixedDelay(AuthBootstrap::heartbeat, 30, 30, TimeUnit.SECONDS);
        SUPERVISOR.scheduleWithFixedDelay(AuthBootstrap::superviseHeartbeat, 1, 1, TimeUnit.SECONDS);
        SECURITY_WORKER.scheduleWithFixedDelay(AuthBootstrap::inspectSecurity,1,1,TimeUnit.SECONDS);
        if (alreadyStarted) {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> start(client));
        } else {
            ClientLifecycleEvents.CLIENT_STARTED.register(AuthBootstrap::start);
        }
    }

    public static String status() {
        return status;
    }

    public static boolean busy() {
        return BUSY.get();
    }

    public static void configureModules(dev.c4lam.celeste.module.ModuleManager modules) {
        AuthenticatedModulePayloadProvider provider=modulePayloads;
        if(provider!=null) {
            if(!authorized())throw new SecurityException("Module installation requires authorization");
            modules.configurePayloads(provider);
        }
    }
    private static dev.c4lam.celeste.beta.delivery.ModuleDeliveryClient.Session moduleSessionSnapshot()
            throws GeneralSecurityException {
        if(!authorized() || moduleSession==null)throw new GeneralSecurityException("Runtime module lease expired");
        return moduleSession;
    }
    public static CompletionStage<Void> moduleDeliveryStopped() {
        AuthenticatedModulePayloadProvider provider=modulePayloads;
        return provider==null?CompletableFuture.completedFuture(null):provider.stopped();
    }
    private static void closeModules(AuthenticatedModulePayloadProvider provider) { if(provider!=null)provider.close(); }

    public static boolean locked() {
        return LOCKED.get();
    }

    public static boolean authorized() {
        if (!AUTHORIZED.get() || LOCKED.get() || STOPPED.get() || QUARANTINED.get()) return false;
        // Gameplay checks this even if the network worker is delayed or stops.
        // A cached boolean must never outlive the verified server session.
        if (System.nanoTime() - sessionDeadlineNanos >= 0) {
            lockSession("Authorization expired; reconnect to verify this session");
            return false;
        }
        return true;
    }

    public static boolean hasLocalCredential() {
        return STORE.exists();
    }

    public record CloudResponse(String contentType, byte[] body) {
    }

    public static CloudResponse cloudRequest(String method, String path, String contentType, byte[] body) throws Exception {
        AuthServiceClient currentService = service;
        String currentGrant = sessionGrant;
        if (!authorized() || currentService == null || currentGrant == null || currentGrant.isBlank()) {
            throw new IOException("Cloud configs require an active license session");
        }
        AuthServiceClient.CloudResponse response = currentService.cloudRequest(
            method,
            path,
            contentType,
            body,
            currentGrant
        );
        return new CloudResponse(response.contentType(), response.body());
    }

    public static void submitLicense(String licenseKey) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (LOCKED.get() || QUARANTINED.get() || STOPPED.get()) {
            show(client, "Restart Minecraft before authorizing again");
            return;
        }
        if (licenseKey == null || licenseKey.strip().length() < 20 || !BUSY.compareAndSet(false, true)) {
            return;
        }
        status = "Binding this installation";
        WORKER.execute(() -> {
            InstallCredential credential = null;
            try {
                ensureReady();
                credential = service.enroll(licenseKey, hardware).credential();
                STORE.save(credential);
                AuthServiceClient.Authenticated authenticated = service.authenticate(credential, hardware);
                authorize(client, authenticated, credential);
                credential = null; // authorize owns the queued/active lifetime.
            } catch (Exception exception) {
                fail(client, friendlyMessage(exception), exception);
            } finally {
                discardCredential(credential);
                BUSY.set(false);
            }
        });
    }

    public static void retry() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (LOCKED.get() || QUARANTINED.get() || STOPPED.get()) {
            show(client, "Restart Minecraft before authorizing again");
            return;
        }
        if (!STORE.exists()) {
            show(client, "Enter a license to bind this installation");
            return;
        }
        authenticateStored(client);
    }

    public static void clearLocalBinding() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (BUSY.get() || LOCKED.get() || QUARANTINED.get() || STOPPED.get()) {
            return;
        }
        try {
            STORE.clear();
            show(client, "Local binding cleared; enter a license");
        } catch (IOException exception) {
            show(client, "Could not clear the local binding");
        }
    }

    private static void start(MinecraftClient client) {
        if (STOPPED.get() || !STARTED.compareAndSet(false, true)) {
            return;
        }
        if (STORE.exists()) {
            authenticateStored(client);
        } else {
            show(client, "Enter a license to bind this installation");
            WORKER.execute(() -> {
                try {
                    ensureReady();
                } catch (Exception exception) {
                    fail(client, friendlyMessage(exception), exception);
                }
            });
        }
    }

    private static void authenticateStored(MinecraftClient client) {
        if(LOCKED.get()||QUARANTINED.get()||STOPPED.get())return;
        if (!BUSY.compareAndSet(false, true)) {
            return;
        }
        status = "Verifying this installation";
        show(client, status);
        WORKER.execute(() -> {
            InstallCredential credential = null;
            try {
                ensureReady();
                credential = STORE.load();
                AuthServiceClient.Authenticated authenticated = service.authenticate(credential, hardware);
                authorize(client, authenticated, credential);
                credential = null;
            } catch (Exception exception) {
                fail(client, friendlyMessage(exception), exception);
            } finally {
                discardCredential(credential);
                BUSY.set(false);
            }
        });
    }

    private static synchronized void ensureReady() throws Exception {
        if (service != null && hardware != null) {
            return;
        }
        AuthConfig loaded = AuthConfig.load();
        if (expectedBetaCatalog != null && !ArtifactIntegrity.hasRetainedMemorySource()) {
            throw new SecurityException("Beta initialization requires its retained verified payload");
        }
        String observedDigest = ArtifactIntegrity.digest();
        if (!loaded.releaseDigest().equals(observedDigest)) {
            reportStartupIntegrityFailure(loaded, observedDigest);
            throw new SecurityException("Client integrity verification failed");
        }
        HardwareFingerprint observedHardware = HardwareFingerprint.collect();
        config = loaded;
        hardware = observedHardware;
        service = new AuthServiceClient(loaded, GameIdentity.collect(), expectedBetaCatalog);
    }

    private static void reportStartupIntegrityFailure(AuthConfig loaded, String observedDigest) {
        // A rejected startup is not a mutation of an already verified live Plus source.
        // New Plus reports require a live lease and the two-observation incident flow.
        if(expectedBetaCatalog!=null)return;
        if (!STORE.exists()) {
            return;
        }
        try (AuthServiceClient reporter = new AuthServiceClient(loaded, GameIdentity.collect(), expectedBetaCatalog);
             InstallCredential credential = STORE.load()) {
            reporter.checkSession(credential, observedDigest, true);
        } catch (Exception ignored) {
            // The local client still fails closed even if the signed report cannot be delivered.
        }
    }

    private static void authorize(
        MinecraftClient client,
        AuthServiceClient.Authenticated authenticated,
        InstallCredential credential
    ) throws Exception {
        if (STOPPED.get() || LOCKED.get()) { discardCredential(credential); return; }
        // Grants authorize delivery; a fresh nonce-bound response authorizes
        // activation. Replaying a captured startup grant cannot satisfy this.
        String verifiedSession = service.checkSession(credential, config.releaseDigest(), false);
        SecurityIncidentProtocol.Scope selectedIncidentScope=expectedBetaCatalog==null?null:service.incidentScope(verifiedSession);
        long deadline = System.nanoTime() + service.sessionValidityNanos(verifiedSession);
        var delivery=service.moduleDelivery(AuthBootstrap::moduleSessionSnapshot);
        var candidateSession=delivery==null?null:dev.c4lam.celeste.beta.delivery.ModuleDeliveryClient.verifiedSession(
            verifiedSession,deadline,config.serverKey());
        AuthenticatedModulePayloadProvider candidate=delivery==null?null:new AuthenticatedModulePayloadProvider(delivery,
            failure->lockSession("Module authorization failed; reconnect to verify this session",failure));
        synchronized (AuthBootstrap.class) {
            if (STOPPED.get() || LOCKED.get()) { discardCredential(credential); closeModules(candidate); return; }
            if (pendingCredential != null) discardCredential(pendingCredential);
            pendingCredential = credential;
        }
        try { client.execute(() -> {
            // A response queued before shutdown/revocation must never reactivate the client.
            synchronized (AuthBootstrap.class) {
            if (pendingCredential != credential) { discardCredential(credential); closeModules(candidate); return; }
            pendingCredential = null;
            if (STOPPED.get() || LOCKED.get() || AUTHORIZED.get()) {
                discardCredential(credential);
                closeModules(candidate);
                return;
            }
            if (System.nanoTime() - deadline >= 0) {
                discardCredential(credential);
                closeModules(candidate);
                fail(client, "Authorization expired before initialization");
                return;
            }
            sessionCredential = credential;
            sessionGrant = verifiedSession;
            sessionDeadlineNanos = deadline;
            moduleSession=candidateSession;
            modulePayloads=candidate;
            incidentScope=selectedIncidentScope;
            authorizationGeneration++;
            // Publish the complete session before the worker can observe it.
            if (!AUTHORIZED.compareAndSet(false, true)) { discardCredential(credential); closeModules(candidate); return; }
            if (STOPPED.get() || LOCKED.get()) {
                AUTHORIZED.set(false);
                sessionCredential = null;
                discardCredential(credential);
                sessionGrant = null;
                sessionDeadlineNanos = 0;
                moduleSession=null;closeModules(candidate);
                return;
            }
            }
            try {
                LicenseIdentity.accept(authenticated.uid());
                authorizedInitializer.run();
                if (authorized()) {
                    if(candidate!=null)candidate.prefetchCatalog();
                    status = "Authorized";
                }
            } catch (RuntimeException | LinkageError exception) {
                lockSession("Client initialization failed; restart Minecraft before trying again", exception);
            }
        }); } catch (RuntimeException | Error failure) {
            synchronized (AuthBootstrap.class) { if (pendingCredential == credential) pendingCredential = null; }
            discardCredential(credential);
            closeModules(candidate);
            throw failure;
        }
    }

    private static void heartbeat() {
        if (!authorized()) {
            return;
        }
        AuthServiceClient currentService = service;
        InstallCredential credential = sessionCredential;
        AuthConfig currentConfig = config;
        if (currentService == null || credential == null || currentConfig == null) {
            lockSession("Session verification failed");
            return;
        }
        try {
            String observedDigest = ArtifactIntegrity.digest();
            if (!currentConfig.releaseDigest().equals(observedDigest)) {
                if(expectedBetaCatalog!=null) {
                    beginIncident(new SecurityIncidentProtocol.Observation(SecurityIncidentProtocol.Kind.RESIDENT_MUTATION,
                        "resident",currentConfig.releaseDigest(),observedDigest),AuthBootstrap::residentObservation);
                    return;
                }
                try {
                    currentService.checkSession(credential, observedDigest, true);
                } finally {
                    lockSession("Client integrity verification failed");
                }
                return;
            }
            AuthenticatedModulePayloadProvider retainedModules=modulePayloads;
            if(retainedModules!=null)retainedModules.verifyRetainedModules();
            String refreshedGrant = currentService.checkSession(credential, observedDigest, false);
            SecurityIncidentProtocol.Scope refreshedIncidentScope=expectedBetaCatalog==null?null:currentService.incidentScope(refreshedGrant);
            long deadline = System.nanoTime() + currentService.sessionValidityNanos(refreshedGrant);
            AuthenticatedModulePayloadProvider modules=modulePayloads;
            var renewedModuleSession=modules==null?null:dev.c4lam.celeste.beta.delivery.ModuleDeliveryClient.verifiedSession(
                refreshedGrant,deadline,currentConfig.serverKey());
            synchronized (AuthBootstrap.class) {
                if (authorized()) {
                    sessionDeadlineNanos = deadline;
                    sessionGrant = refreshedGrant;
                    moduleSession=renewedModuleSession;
                    workerRecoveries = 0;
                    incidentScope=refreshedIncidentScope;
                }
            }
            if(modules!=null && authorized())modules.delivery().renewActive();
        } catch (AuthenticatedModulePayloadProvider.RetainedModuleMismatch mismatch) {
            beginIncident(mismatch.observation(),new ObservationSource(){
                public SecurityIncidentProtocol.Observation sample()throws Exception{return mismatch.sample();}
                public void close(){mismatch.close();}
            });
        } catch (AuthServiceClient.AuthRejectedException exception) {
            if (exception.statusCode() == 401 || exception.statusCode() == 403 || exception.statusCode() == 409) {
                lockSession(exception.getMessage(), exception);
            } else {
                lockAfterHeartbeatGrace();
            }
        } catch (SecurityException | GeneralSecurityException exception) {
            lockSession(exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            lockAfterHeartbeatGrace();
        }
    }

    private static synchronized void superviseHeartbeat() {
        try {
            IncidentContext security=incident;
            if(security!=null) {
                security.controller.watchdog();
                if(security.controller.state()==SecurityIncidentController.State.EXPIRED)
                    lockSession("Security verification ended; restart Minecraft before trying again");
                if(QUARANTINED.get())return;
            }
            if(QUARANTINED.get()) {
                if(System.nanoTime()-sessionDeadlineNanos>=0)
                    lockSession("Security verification expired; restart Minecraft before trying again");
                return;
            }
            AuthenticatedModulePayloadProvider modules=modulePayloads;
            if(modules!=null)modules.delivery().expire();
            if (!authorized()) return;
            if (heartbeatTask != null && !heartbeatTask.isDone() && !WORKER.isShutdown()) return;
            // A cancelled future can still own a running network request. Never
            // start its replacement until the old executor has actually ended.
            WORKER.shutdownNow();
            if (!WORKER.isTerminated()) return;
            if (workerRecoveries >= MAX_WORKER_RECOVERIES) {
                lockSession("Session verification could not recover; reconnect to verify this session");
                return;
            }
            if (!authorized()) return;
            workerRecoveries++;
            WORKER = executor("cst-license-gateway");
            heartbeatTask = WORKER.scheduleWithFixedDelay(AuthBootstrap::heartbeat, 0, 30, TimeUnit.SECONDS);
        } catch (Throwable failure) {
            // The gameplay lease remains an independent guard even if the
            // supervisor itself encounters a failure while replacing a worker.
            lockSession("Session verification could not recover; reconnect to verify this session", failure);
        }
    }

    private static void lockAfterHeartbeatGrace() {
        // Transport errors cannot extend the server-issued lease. Gameplay
        // also calls authorized(), independently of this network worker.
        authorized();
    }

    private static void discardCredential(InstallCredential credential) {
        if (credential == null) return;
        AuthServiceClient current = service;
        if (current != null) current.releaseCredential(credential);
        credential.close();
    }

    private static void closeSessionMaterial() {
        authorizationGeneration++;
        IncidentContext security=incident;incident=null;
        if(security!=null)security.close();
        incidentScope=null;
        moduleSession=null;
        closeModules(modulePayloads);
        InstallCredential active = sessionCredential, pending = pendingCredential;
        sessionCredential = null; pendingCredential = null;
        discardCredential(active);
        if (pending != active) discardCredential(pending);
        AuthServiceClient current = service;
        if (current != null) current.close();
    }

    private static void lockSession(String message) {
        lockSession(message, new SecurityException(message));
    }

    private static synchronized void lockSession(String message, Throwable failure) {
        if (!AUTHORIZED.compareAndSet(true, false)) {
            return;
        }
        LOCKED.set(true);
        closeSessionMaterial();
        sessionGrant = null;
        sessionDeadlineNanos = 0;
        status = message;
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            try {
                Consumer<Throwable> observer = lateFailureObserver;
                if (observer != null) observer.accept(failure);
            } finally {
                try { authorizedShutdown.run(); }
                finally { LicenseIdentity.clear(); }
            }
        });
    }

    private static synchronized void stop(MinecraftClient client) {
        if (!STOPPED.compareAndSet(false, true)) {
            return;
        }
        LOCKED.set(true);
        AUTHORIZED.set(false);
        closeSessionMaterial();
        sessionGrant = null;
        sessionDeadlineNanos = 0;
        SUPERVISOR.shutdownNow();
        WORKER.shutdownNow();
        SECURITY_WORKER.shutdownNow();
        client.execute(() -> {
            try {
                authorizedShutdown.run();
            } finally {
                LicenseIdentity.clear();
            }
        });
    }

    private static void fail(MinecraftClient client, String message) {
        fail(client, message, new SecurityException(message));
    }

    private static void fail(MinecraftClient client, String message, Throwable failure) {
        status = message;
        show(client, message);
        Consumer<Throwable> observer = lateFailureObserver;
        if (observer != null && !STOPPED.get()) {
            client.execute(() -> { if (!STOPPED.get()) observer.accept(failure); });
        }
    }

    // Remote grant/protocol errors do not revoke the installation signing identity.
    // lockSession clears transient authorization; the existing keys are needed for
    // fresh dual-proof authentication. Only explicit clearLocalBinding removes them.
    private static void show(MinecraftClient client, String message) {
        status = message;
    }

    @FunctionalInterface private interface ObservationSource extends AutoCloseable {
        SecurityIncidentProtocol.Observation sample() throws Exception;
        @Override default void close(){ }
    }

    /** Observers execute independently of network heartbeats and never on the render thread. */
    private static void inspectSecurity() {
        if(STOPPED.get())return;
        IncidentContext current=incident;
        if(current!=null) {
            SecurityIncidentController.State state=current.controller.state();
            if(state==SecurityIncidentController.State.REVOKED||state==SecurityIncidentController.State.EXPIRED
                    ||state==SecurityIncidentController.State.CLOSED)return;
            SecurityIncidentProtocol.Observation sample=null;
            try {sample=current.source.sample();}
            catch(Exception|LinkageError unavailable){/* Unavailable is not a strike. */}
            current.controller.poll(sample);
            return;
        }
        if(expectedBetaCatalog==null||incidentScope==null||!authorized())return;
        if(WindowsSelfDebuggerProbe.read()==WindowsSelfDebuggerProbe.Result.CONFIRMED)
            beginIncident(SecurityIncidentProtocol.Observation.debugger(),()->
                WindowsSelfDebuggerProbe.read()==WindowsSelfDebuggerProbe.Result.CONFIRMED
                    ?SecurityIncidentProtocol.Observation.debugger():null);
    }

    private static SecurityIncidentProtocol.Observation residentObservation() throws Exception {
        AuthConfig current=config;if(current==null||!ArtifactIntegrity.hasRetainedMemorySource())return null;
        String observed=ArtifactIntegrity.digest();
        return current.releaseDigest().equals(observed)?null:new SecurityIncidentProtocol.Observation(
            SecurityIncidentProtocol.Kind.RESIDENT_MUTATION,"resident",current.releaseDigest(),observed);
    }

    private static void beginIncident(SecurityIncidentProtocol.Observation observation,ObservationSource source) {
        if(!AUTHORIZED.get()||STOPPED.get()||LOCKED.get()||incident!=null||!INCIDENT_STARTING.compareAndSet(false,true)){
            source.close();return;
        }
        QUARANTINED.set(true);
        AuthenticatedModulePayloadProvider modules=modulePayloads;if(modules!=null)modules.quarantine();
        AuthServiceClient.IncidentTransport transport=null;
        boolean sourceInstalled=false;
        try {
            AuthServiceClient currentService=service;InstallCredential credential=sessionCredential;
            String grant=sessionGrant;long deadline=sessionDeadlineNanos,generation=authorizationGeneration;
            if(expectedBetaCatalog==null||incidentScope==null||currentService==null||credential==null
                    ||grant==null||System.nanoTime()-deadline>=0) {
                lockSession("Security verification failed; restart Minecraft before trying again");return;
            }
            transport=currentService.incidentTransport(credential,grant,deadline);
            IncidentContext candidate=new IncidentContext(generation,source,transport,deadline);
            synchronized(AuthBootstrap.class) {
                if(STOPPED.get()||LOCKED.get()||generation!=authorizationGeneration||service!=currentService
                        ||sessionCredential!=credential||incident!=null){candidate.close();return;}
                incident=candidate;transport=null;sourceInstalled=true;
            }
            candidate.controller.poll(observation);
        } catch(Exception|LinkageError failure) {
            lockSession("Security verification could not start; restart Minecraft before trying again",failure);
        } finally {
            try{if(transport!=null)transport.close();if(!sourceInstalled)source.close();}
            finally{INCIDENT_STARTING.set(false);}
        }
    }

    static SecurityIncidentController warningController() {
        IncidentContext current=incident;
        return current==null||STOPPED.get()?null:current.controller;
    }

    private static String incidentId(){byte[] bytes=new byte[24];INCIDENT_RANDOM.nextBytes(bytes);return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}

    private static final class IncidentContext implements AutoCloseable {
        final long generation;
        final ObservationSource source;
        final AuthServiceClient.IncidentTransport transport;
        final SecurityIncidentController controller;
        IncidentContext(long generation,ObservationSource source,AuthServiceClient.IncidentTransport transport,long deadline) throws GeneralSecurityException {
            this.generation=generation;this.source=source;this.transport=transport;
            controller=new SecurityIncidentController(transport.binding(),Crypto.ed25519Public(config.serverKey()),new SecurityIncidentController.Clock(){
                public long nanos(){return System.nanoTime();}public long epochMillis(){return System.currentTimeMillis();}
            },AuthBootstrap::incidentId,transport,new SecurityIncidentController.Effects(){
                public void enqueue(Runnable action){MinecraftClient.getInstance().execute(action);}
                public void quarantine(){if(incident==IncidentContext.this)status="Stop.";}
                public void scheduleStop(){
                    synchronized(AuthBootstrap.class){
                        if(incident!=IncidentContext.this||authorizationGeneration!=generation||STOPPED.get())return;
                        MinecraftClient.getInstance().scheduleStop();
                        lockSession("Security authorization revoked");
                    }
                }
                public void releaseReporter(){
                    try{source.close();}finally{releaseMaterial();}
                }
                private void releaseMaterial(){
                    transport.close();
                    // Keep the small final warning/stop controller alive until its host action runs,
                    // but release all its shared live credentials after bounded reporting ends.
                    synchronized(AuthBootstrap.class) {
                        if(incident!=IncidentContext.this)return;
                        moduleSession=null;closeModules(modulePayloads);
                        InstallCredential active=sessionCredential;sessionCredential=null;discardCredential(active);
                        sessionGrant=null;
                        AuthServiceClient current=service;if(current!=null)current.close();
                    }
                }
                public void failed(Throwable failure){if(incident==IncidentContext.this)status="Security verification could not finish";}
            },generation,()->authorizationGeneration,deadline);
        }
        @Override public void close(){controller.close();transport.close();}
    }

    private static String friendlyMessage(Exception exception) {
        if (exception instanceof AuthServiceClient.AuthRejectedException) {
            return exception.getMessage();
        }
        if (exception instanceof SecurityException) {
            return exception.getMessage();
        }
        if (exception instanceof IOException) {
            return "Authentication service is unavailable";
        }
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return "Authentication was interrupted";
        }
        return "This installation could not be verified";
    }
}
