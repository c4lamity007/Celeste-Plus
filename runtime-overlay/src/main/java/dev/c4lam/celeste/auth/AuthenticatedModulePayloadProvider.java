package dev.c4lam.celeste.auth;

import dev.c4lam.celeste.beta.delivery.ModuleDeliveryClient;
import dev.c4lam.celeste.beta.memory.ModuleMemoryClassLoader;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.*;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Resident adapter. Only authenticated, fully verified module bytes reach a child definition domain. */
final class AuthenticatedModulePayloadProvider implements ModulePayloadProvider, AutoCloseable {
    private record Activation(String id,long generation) { }
    private static final class Request {
        final String id;final long generation;final CompletableFuture<ModulePayloadLease> future;
        volatile boolean cancelled;
        boolean started,authorizing,finished;
        FutureTask<Void> task;
        Batch batch;
        Request(String id,long generation,CompletableFuture<ModulePayloadLease> future){this.id=id;this.generation=generation;this.future=future;}
    }
    private record Batch(List<Request> requests,FutureTask<Void> task) {
        boolean cancelled(){return requests.stream().allMatch(request->request.cancelled || request.future.isDone());}
    }
    private final ModuleDeliveryClient delivery;
    private final Consumer<Throwable> authFailure;
    private final ThreadPoolExecutor workers;
    private final ExecutorService authorizations;
    private final ConcurrentLinkedQueue<Request> pending=new ConcurrentLinkedQueue<>();
    private final Set<CompletableFuture<ModulePayloadLease>> outstanding=ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Activation,Request> requests=new ConcurrentHashMap<>();
    private final Set<Lease> installedLeases=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean pumping=new AtomicBoolean();
    private volatile boolean closed;
    private volatile CompletableFuture<Void> stopped=CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> catalogPrefetch;
    private volatile FutureTask<Void> authorizationTask;

    AuthenticatedModulePayloadProvider(ModuleDeliveryClient delivery,Consumer<Throwable> authFailure) {
        this.delivery=Objects.requireNonNull(delivery);this.authFailure=Objects.requireNonNull(authFailure);
        workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(512),task->{
            Thread thread=new Thread(task,"Celeste module delivery");thread.setDaemon(true);
            thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());return thread;
        },new ThreadPoolExecutor.AbortPolicy());
        authorizations=Executors.newSingleThreadExecutor(task -> {
            Thread thread=new Thread(task,"Celeste module authorization");thread.setDaemon(true);
            thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());return thread;
        });
    }
    ModuleDeliveryClient delivery(){return delivery;}
    /** Starts only after the resident session is published; never authorizes or loads a module. */
    synchronized CompletionStage<Void> prefetchCatalog() {
        if(closed)return CompletableFuture.failedFuture(new CancellationException("Module delivery stopped"));
        if(catalogPrefetch!=null && !catalogPrefetch.isCompletedExceptionally())return catalogPrefetch.minimalCompletionStage();
        CompletableFuture<Void> result=new CompletableFuture<>();catalogPrefetch=result;
        try { authorizations.execute(()->{
            try {
                if(closed)throw new CancellationException("Module delivery stopped");
                delivery.prepareCatalog();result.complete(null);
            } catch(Exception failure) {
                try { if(!closed)securityFailure(failure); }
                finally { result.completeExceptionally(failure); }
            }
        }); } catch(RejectedExecutionException failure){result.completeExceptionally(failure);}
        return result.minimalCompletionStage();
    }
    /** Called by the heartbeat worker, independently of the fixed resident-runtime digest. */
    void verifyRetainedModules() throws GeneralSecurityException {
        for (Lease lease:installedLeases) lease.verifyRetained();
    }
    @Override public synchronized CompletionStage<ModulePayloadLease> acquire(String id,long generation) {
        CompletableFuture<ModulePayloadLease> result=new CompletableFuture<>();
        if(closed)return CompletableFuture.failedFuture(new IllegalStateException("Module delivery stopped"));
        Request request=new Request(id,generation,result);outstanding.add(result);
        Activation activation=new Activation(id,generation);
        if(requests.putIfAbsent(activation,request)!=null){outstanding.remove(result);return CompletableFuture.failedFuture(new IllegalStateException("Activation generation already requested"));}
        result.whenComplete((value,error)->{outstanding.remove(result);requests.remove(activation,request);});
        pending.add(request);schedulePump();return result.minimalCompletionStage();
    }
    @Override public void cancel(String id,long generation) {
        Request request=requests.get(new Activation(id,generation));if(request==null)return;
        Batch batch;boolean unstarted;
        synchronized(request){
            if(request.finished || request.future.isDone())return;
            request.cancelled=true;batch=request.batch;
            if(request.task!=null)request.task.cancel(true);
            unstarted=!request.started && !request.authorizing;
        }
        pending.remove(request);
        if(request.task!=null)workers.remove(request.task);
        if(batch!=null && batch.cancelled())batch.task.cancel(true);
        if(unstarted)finish(request,null,new CancellationException("Module activation cancelled"));
    }
    private void schedulePump() {
        if(!pumping.compareAndSet(false,true))return;
        try{authorizationTask=new FutureTask<>(()->{pump();return null;});authorizations.execute(authorizationTask);}
        catch(RejectedExecutionException failure){pumping.set(false);failPending(failure);}
    }
    private void pump() {
        List<Request> batch=new ArrayList<>();
        Batch operation=null;
        try {
            while(batch.size()<32) {Request request=pending.poll();if(request==null)break;batch.add(request);}
            if(closed)throw new CancellationException("Module delivery stopped");
            operation=new Batch(List.copyOf(batch),authorizationTask);
            Set<String> ids=new LinkedHashSet<>();
            for(Request request:batch)synchronized(request){
                if(!request.cancelled && !request.future.isDone()){
                    request.authorizing=true;request.batch=operation;ids.add(request.id);
                }
            }
            if(!ids.isEmpty())delivery.authorize(ids,operation::cancelled);
            for(Request request:batch) {
                FutureTask<Void> task;
                synchronized(request){
                    request.authorizing=false;request.batch=null;
                    if(request.cancelled || request.future.isDone()){task=null;}
                    else{task=new FutureTask<>(()->{prepare(request);return null;});request.task=task;}
                }
                if(task==null){finish(request,null,new CancellationException("Module activation cancelled"));continue;}
                try{if(!task.isCancelled()){workers.execute(task);if(task.isCancelled())workers.remove(task);}}
                catch(RejectedExecutionException failure){finish(request,null,failure);}
            }
        }catch(Throwable failure){fail(batch,failure);}
        finally {pumping.set(false);if(!pending.isEmpty() && !closed)schedulePump();}
    }
    private void prepare(Request request) {
        boolean canceledBeforeStart;
        synchronized(request){
            canceledBeforeStart=request.cancelled || request.future.isDone();
            if(!canceledBeforeStart)request.started=true;
        }
        if(canceledBeforeStart){finish(request,null,new CancellationException("Module activation cancelled"));return;}
        ModuleDeliveryClient.Prepared prepared=null;
        ModuleMemoryClassLoader loader=null;
        Map<String,byte[]> assets=null;
        Lease delivered=null;Throwable failure=null;
        try {
            checkCancelled(request);
            prepared=delivery.loadAuthorized(request.id,()->request.cancelled);
            checkCancelled(request);
            byte[] jar;
            try{jar=prepared.jar();}
            catch(SecurityException retired){checkCancelled(request);throw retired;}
            loader=ModuleMemoryClassLoader.open(ModuleFeature.class.getClassLoader(),jar,
                prepared.module().sha256(),prepared.module().entriesSha256());
            prepared.releaseBytes();
            checkCancelled(request);
            // Copy authenticated inert assets on the delivery worker. Publication
            // and game/GPU initialization still belong to the owner thread.
            assets=loader.assetSnapshot();
            checkCancelled(request);
            boolean authorized=prepared.authorized();
            // Local authority can disappear while shutdown/cancellation races
            // this last check. Wire and integrity rejections remain separate.
            checkCancelled(request);
            if(!authorized)throw new GeneralSecurityException("Module authorization ended during preparation");
            delivered=new Lease(prepared,loader,assets,request.id,request.generation,installedLeases);
            installedLeases.add(delivered);
            prepared=null;loader=null;assets=null;
        }catch(Throwable problem){failure=problem;securityFailure(problem);}
        finally{
            try{if(assets!=null)wipeAssets(assets);}catch(Throwable cleanup){failure=cleanupFailure(failure,cleanup);}
            try{if(loader!=null)loader.close();}catch(Throwable cleanup){failure=cleanupFailure(failure,cleanup);}
            try{if(prepared!=null)prepared.close();}catch(Throwable cleanup){failure=cleanupFailure(failure,cleanup);}
            finish(request,delivered,failure);
        }
    }
    private static Throwable cleanupFailure(Throwable failure,Throwable cleanup){
        var unsafe=new ModulePayloadProvider.CleanupFailure("Module preparation cleanup failed",cleanup);
        if(failure!=null)unsafe.addSuppressed(failure);return unsafe;
    }
    private void checkCancelled(Request request){if(closed || request.cancelled)throw new CancellationException("Module activation cancelled");}
    private void finish(Request request,Lease lease,Throwable failure){
        boolean cancelled;
        synchronized(request){
            cancelled=request.cancelled || closed || request.future.isDone();request.authorizing=false;request.batch=null;request.finished=true;
        }
        if(cancelled || failure!=null){
            if(lease!=null)try{lease.close();}catch(Throwable cleanup){var unsafe=new ModulePayloadProvider.CleanupFailure("Canceled module cleanup failed",cleanup);if(failure!=null)unsafe.addSuppressed(failure);failure=unsafe;}
            request.future.completeExceptionally(failure instanceof ModulePayloadProvider.CleanupFailure || failure!=null && !cancelled
                ?failure:new CancellationException("Module activation cancelled"));
        }else if(!request.future.complete(lease) && lease!=null)lease.close();
    }
    private static void wipeAssets(Map<String,byte[]> assets){assets.values().forEach(bytes->Arrays.fill(bytes,(byte)0));}
    private void fail(List<Request> requests,Throwable failure) {
        requests.forEach(request->finish(request,null,failure));securityFailure(failure);
    }
    private void securityFailure(Throwable failure) {
        if(failure instanceof GeneralSecurityException || failure instanceof SecurityException) {
            delivery.close();authFailure.accept(failure);
        }
        if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
    }
    private void failPending(Throwable failure) {
        for(Request request=pending.poll();request!=null;request=pending.poll())request.future.completeExceptionally(failure);
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;delivery.close();
        CancellationException cancellation=new CancellationException("Module delivery stopped");
        if(catalogPrefetch!=null)catalogPrefetch.completeExceptionally(cancellation);
        failPending(cancellation);outstanding.forEach(future->future.completeExceptionally(cancellation));
        workers.shutdownNow();authorizations.shutdownNow();
        stopped=CompletableFuture.runAsync(()->{
            try{
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                if(!workers.awaitTermination(30,TimeUnit.SECONDS)
                    || !authorizations.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS))
                    throw new CompletionException(new IllegalStateException("Module delivery did not terminate"));
            }
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CompletionException(interrupted);}
        });
    }
    CompletionStage<Void> stopped(){return stopped.minimalCompletionStage();}
    private static final class Lease implements ModulePayloadLease {
        private ModuleDeliveryClient.Prepared prepared;
        private ModuleMemoryClassLoader loader;
        private Map<String,byte[]> pendingAssets;
        private final String id;private final long generation;
        private boolean created;
        private Set<Lease> membership;
        Lease(ModuleDeliveryClient.Prepared prepared,ModuleMemoryClassLoader loader,Map<String,byte[]> assets,String id,long generation,Set<Lease> membership) {
            this.prepared=prepared;this.loader=loader;this.pendingAssets=assets;this.id=id;this.generation=generation;this.membership=membership;
        }
        @Override public synchronized ModuleFeature create(ModuleServices services) throws Exception {
            if(created || !authorized() || !id.equals(services.moduleId()) || generation!=services.generation())
                throw new SecurityException("Invalid module activation generation");
            created=true;
            Map<String, byte[]> assets=pendingAssets;
            try { ModuleResourceAssets.install(services.scope(), assets);pendingAssets=null; }
            catch (Throwable failure) {
                pendingAssets=null;wipeAssets(assets);
                throw failure;
            }
            Class<?> type=loader.loadClass(prepared.module().entrypoint());
            if(type.getClassLoader()!=loader || !ModuleEntrypoint.class.isAssignableFrom(type))
                throw new SecurityException("Unapproved module entrypoint");
            ModuleEntrypoint entry=(ModuleEntrypoint)type.getConstructor().newInstance();
            return entry.create(services);
        }
        void verifyRetained() throws GeneralSecurityException {
            ModuleMemoryClassLoader retained;
            synchronized(this){retained=loader;}
            if(retained==null)return;
            // The loader borrows immutable bytes for hashing without holding its
            // read monitor or this lease's game/render authorization monitor.
            try { retained.verifyIntegrity(); }
            catch (GeneralSecurityException failure) {
                synchronized(this){if(loader==retained)throw failure;}
                // This exact lease retired while verification was waiting; its bytes
                // were deliberately wiped and can no longer authorize dispatch.
            }
        }
        @Override public synchronized ClassLoader definingLoader(){return loader;}
        @Override public synchronized boolean authorized(){return loader!=null && prepared!=null && !loader.isClosed() && prepared.authorized();}
        @Override public synchronized void close(){
            if(pendingAssets!=null){wipeAssets(pendingAssets);pendingAssets=null;}
            if(loader!=null){loader.close();loader=null;}
            if(prepared!=null){prepared.close();prepared=null;}
            if(membership!=null){membership.remove(this);membership=null;}
        }
    }
}
