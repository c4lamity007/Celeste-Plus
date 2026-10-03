package dev.c4lam.celeste.auth;

import com.google.gson.*;
import dev.c4lam.celeste.beta.delivery.ProviderFixture;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.*;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Real provider/client/container/child-loader/lifecycle; only the signed server transport is synthetic. */
public final class ProviderIntegrationCheck {
 private static int assertions;
 private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
 private static void waitFor(BooleanSupplier condition,Runnable pump)throws Exception{
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
  while(!condition.getAsBoolean()){pump.run();if(System.nanoTime()>deadline)throw new AssertionError("Timed out waiting for provider lifecycle");Thread.sleep(2);}
  pump.run();
 }
 private static int count(Object provider,String name)throws Exception{var field=provider.getClass().getDeclaredField(name);field.setAccessible(true);Object value=field.get(provider);return value instanceof Map<?,?> map?map.size():((Collection<?>)value).size();}
 private static ModulePayloadLease get(CompletionStage<ModulePayloadLease> stage)throws Exception{return stage.toCompletableFuture().get(15,TimeUnit.SECONDS);}
 private static void stopped(AuthenticatedModulePayloadProvider provider)throws Exception{provider.close();provider.stopped().toCompletableFuture().get(15,TimeUnit.SECONDS);}
 public static void main(String[] args)throws Exception{
  JsonObject golden=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
  warmAndAssets(golden);bounded(golden);cancelGeneration(golden);closeRace(golden);nonBlockingIntegrity(golden);prefetchCatalog(golden);
  workerAssets(golden);diagnosticRetention(golden);
  cancelQueued(golden);cancelBatchSibling(golden);cancelAllRetry(golden);cancelTransferDrain(golden);cancelDeniedTransfer(golden);
  cancelQueuedPreparation(golden);cancelVerifiedPreparation(golden);closeFinalValidation(golden,false);closeFinalValidation(golden,true);
  System.out.println("PASS authenticated module provider: "+assertions+" integration assertions; synthetic server, real crypto/loader/runtime; no game verification");
 }
 private static void cancelQueuedPreparation(JsonObject golden)throws Exception{
  try(var fixture=new ProviderFixture(golden)){
   AtomicReference<Throwable> failure=new AtomicReference<>();var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure::set);
   CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
   fixture.before=path->{if(path.contains("/artifacts/")){entered.countDown();release.await(10,TimeUnit.SECONDS);}};
   var a=provider.acquire("fixture_a",1);var b=provider.acquire("fixture_b",1);check(entered.await(10,TimeUnit.SECONDS),"Both preparation workers occupied before canceling queued third module");
   int before=fixture.authorizations;var c=provider.acquire("fixture_c",1);waitFor(()->fixture.authorizations>before,()->{});
   provider.cancel("fixture_c",1);cancelled(c);
   check(!a.toCompletableFuture().isDone()&&!b.toCompletableFuture().isDone(),"Queued preparation cancellation does not interrupt active worker siblings");
   release.countDown();get(a).close();get(b).close();check(fixture.transfers==2,"Canceled queued preparation never downloads its artifact");
   fixture.before=path->{};var next=get(provider.acquire("fixture_c",2));check(next.authorized(),"Canceled worker queue entry does not block replacement generation");next.close();
   stopped(provider);check(failure.get()==null&&count(provider,"requests")==0,"Preparation queue cancellation releases its exact request index");
  }
 }
 private static final class PreparationBarrier {
  final CountDownLatch entered=new CountDownLatch(1),interrupted=new CountDownLatch(1),release=new CountDownLatch(1);
  final AtomicBoolean once=new AtomicBoolean();volatile byte[] asset,jar;
  void update(byte[] bytes,int offset,int length){
   if(!Thread.currentThread().getName().equals("Celeste module delivery"))return;
   StackTraceElement[] stack=Thread.currentThread().getStackTrace();boolean entries=false,archive=false;
   for(var frame:stack)if(frame.getClassName().endsWith(".ModuleMemoryClassLoader")){entries|=frame.getMethodName().equals("entryDigest");archive|=frame.getMethodName().equals("digest");}
   if(archive&&offset==0&&length==bytes.length)jar=bytes;
   if(!entries||offset!=0||length!=bytes.length||!Arrays.equals(bytes,"verified fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8))||!once.compareAndSet(false,true))return;
   asset=bytes;entered.countDown();
   try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException cancelled){interrupted.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException again){Thread.currentThread().interrupt();}}
  }
 }
 private static volatile PreparationBarrier preparationBarrier;
 public static final class PreparationSha256 extends java.security.MessageDigestSpi {
  final java.security.MessageDigest actual;
  public PreparationSha256(){try{actual=java.security.MessageDigest.getInstance("SHA-256","SUN");}catch(Exception error){throw new java.security.ProviderException(error);}}
  @Override protected void engineUpdate(byte value){actual.update(value);}
  @Override protected void engineUpdate(byte[] bytes,int offset,int count){PreparationBarrier barrier=preparationBarrier;if(barrier!=null)barrier.update(bytes,offset,count);actual.update(bytes,offset,count);}
  @Override protected byte[] engineDigest(){return actual.digest();}
  @Override protected void engineReset(){actual.reset();}
  @Override protected int engineGetDigestLength(){return 32;}
 }
 private static void cancelVerifiedPreparation(JsonObject golden)throws Exception{
  String name="CelesteCanceledPreparationFixture";var digestProvider=new java.security.Provider(name,"1","Offline verified preparation barrier"){};
  digestProvider.put("MessageDigest.SHA-256",PreparationSha256.class.getName());java.security.Security.insertProviderAt(digestProvider,1);
  PreparationBarrier barrier=new PreparationBarrier();preparationBarrier=barrier;
  try(var fixture=new ProviderFixture(golden)){
   AtomicReference<Throwable> failure=new AtomicReference<>();var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure::set);
   var pending=provider.acquire("fixture_a",1);check(barrier.entered.await(10,TimeUnit.SECONDS),"Real expanded-entry verification pauses with owned plaintext buffers");
   provider.cancel("fixture_a",1);check(barrier.interrupted.await(5,TimeUnit.SECONDS)&&!pending.toCompletableFuture().isDone(),"Canceled preparation cannot complete its retirement barrier before verification unwinds");
   check(barrier.asset[0]=='v'&&barrier.jar!=null,"In-progress verification retains its original owned buffers until safe retirement");
   barrier.release.countDown();cancelled(pending);
   check(zeroed(Map.of("asset",barrier.asset,"jar",barrier.jar)),"Canceled verified preparation wipes both expanded plaintext and decrypted archive before completion");
   check(count(provider,"installedLeases")==0&&count(provider,"requests")==0&&count(provider.delivery(),"active")==0,"Canceled verification publishes no lease and releases active preparation accounting");
   stopped(provider);check(failure.get()==null,"Pure cancellation after cryptographic verification never locks authorization");
  }finally{barrier.release.countDown();preparationBarrier=null;java.security.Security.removeProvider(name);}
 }
 private static void cancelled(CompletionStage<ModulePayloadLease> stage)throws Exception{
  try{get(stage);throw new AssertionError("Cancelled activation succeeded");}
  catch(CancellationException expected){assertions++;}
  catch(ExecutionException failure){Throwable cause=failure.getCause();while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();check(cause instanceof CancellationException,"Cancellation completion is distinct from authorization failure");}
 }
 private static void cancelQueued(JsonObject golden)throws Exception{
  try(var fixture=new ProviderFixture(golden)){
   AtomicReference<Throwable> failure=new AtomicReference<>();var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure::set);
   CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
   fixture.before=path->{if(path.endsWith("/catalog")){entered.countDown();release.await(10,TimeUnit.SECONDS);}};
   var catalog=provider.prefetchCatalog();check(entered.await(10,TimeUnit.SECONDS),"Queued cancellation test holds independent catalog prefetch");
   var pending=provider.acquire("fixture_a",1);provider.cancel("fixture_a",1);cancelled(pending);
   check(count(provider,"outstanding")==0,"Unstarted cancellation completes without waiting on unrelated prefetch");
   release.countDown();catalog.toCompletableFuture().get(10,TimeUnit.SECONDS);
   var next=get(provider.acquire("fixture_a",2));check(next.authorized()&&fixture.authorizations==1&&fixture.transfers==1,"Canceled queue entry performs no authorization or transfer; next generation works");
   provider.cancel("fixture_a",1);check(next.authorized(),"Late old-generation cancellation cannot revoke a new delivered generation");next.close();
   stopped(provider);check(failure.get()==null&&count(provider,"outstanding")==0,"Queued cancellation leaves no failures or futures");
  }
 }
 private static void cancelBatchSibling(JsonObject golden)throws Exception{
  try(var fixture=new ProviderFixture(golden)){
   AtomicReference<Throwable> failure=new AtomicReference<>();var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure::set);
   CountDownLatch catalogEntered=new CountDownLatch(1),catalogRelease=new CountDownLatch(1),batchEntered=new CountDownLatch(1),batchRelease=new CountDownLatch(1);
   AtomicBoolean interrupted=new AtomicBoolean();
   fixture.before=path->{if(path.endsWith("/catalog")){catalogEntered.countDown();catalogRelease.await(10,TimeUnit.SECONDS);}if(path.endsWith("/authorize")){batchEntered.countDown();try{batchRelease.await(10,TimeUnit.SECONDS);}catch(InterruptedException error){interrupted.set(true);throw error;}}};
   var prefetch=provider.prefetchCatalog();check(catalogEntered.await(10,TimeUnit.SECONDS),"Batch fixture queues both activations before authorization starts");
   var a=provider.acquire("fixture_a",1);var b=provider.acquire("fixture_b",1);catalogRelease.countDown();prefetch.toCompletableFuture().get(10,TimeUnit.SECONDS);
   check(batchEntered.await(10,TimeUnit.SECONDS),"Shared authorization batch is in flight");provider.cancel("fixture_a",1);
   check(!a.toCompletableFuture().isDone()&&!interrupted.get(),"One canceled member drains shared authorization without interrupting its sibling");
   batchRelease.countDown();cancelled(a);var live=get(b);
   check(fixture.authorizationBatches.size()==1&&new HashSet<>(fixture.authorizationBatches.getFirst()).equals(Set.of("fixture_a","fixture_b")),"Race exercised a real shared signed batch");
   check(live.authorized()&&fixture.transfers==1&&!interrupted.get(),"Only surviving sibling downloads and retains authorization");
   provider.cancel("fixture_a",1);check(live.authorized(),"Canceled sibling never revokes an active module");live.close();stopped(provider);check(failure.get()==null,"Shared-batch cancellation emits no auth failure");
  }
 }
 private static void cancelAllRetry(JsonObject golden)throws Exception{
  try(var fixture=new ProviderFixture(golden)){
   AtomicReference<Throwable> failure=new AtomicReference<>();var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure::set);
   CountDownLatch catalogEntered=new CountDownLatch(1),catalogRelease=new CountDownLatch(1),retryEntered=new CountDownLatch(1),interrupted=new CountDownLatch(1),drain=new CountDownLatch(1);
   fixture.before=path->{if(path.endsWith("/catalog")){catalogEntered.countDown();catalogRelease.await(10,TimeUnit.SECONDS);}};
   fixture.busyPath="/authorize";fixture.busyRemaining=10;
   fixture.retryPause=millis->{retryEntered.countDown();try{new CountDownLatch(1).await(10,TimeUnit.SECONDS);}catch(InterruptedException error){interrupted.countDown();drain.await(10,TimeUnit.SECONDS);throw error;}};
   var prefetch=provider.prefetchCatalog();check(catalogEntered.await(10,TimeUnit.SECONDS),"All-cancel fixture gates the authorization batch");
   var a=provider.acquire("fixture_a",1);var b=provider.acquire("fixture_b",1);catalogRelease.countDown();prefetch.toCompletableFuture().get(10,TimeUnit.SECONDS);
   check(retryEntered.await(10,TimeUnit.SECONDS),"Shared operation is in bounded busy retry");provider.cancel("fixture_a",1);provider.cancel("fixture_b",1);
   check(interrupted.await(5,TimeUnit.SECONDS),"Canceling every member interrupts only its owned authorization task");
   check(!a.toCompletableFuture().isDone()&&!b.toCompletableFuture().isDone(),"Canceled acquisitions remain pending until the running task drains");
   drain.countDown();cancelled(a);cancelled(b);
   fixture.busyRemaining=0;fixture.retryPause=Thread::sleep;fixture.before=path->{};
   var next=get(provider.acquire("fixture_a",2));check(next.authorized()&&fixture.transfers==1,"Authorization executor is reusable with no leftover cancellation interrupt");next.close();
   stopped(provider);check(failure.get()==null&&count(provider,"outstanding")==0,"All-member cancellation leaves no authorization failure or outstanding futures");
  }
 }
 private static void cancelTransferDrain(JsonObject golden)throws Exception{
  try(var fixture=new ProviderFixture(golden)){
   AtomicReference<Throwable> failure=new AtomicReference<>();var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure::set);
   var sibling=get(provider.acquire("fixture_b",1));
   CountDownLatch entered=new CountDownLatch(1),interrupted=new CountDownLatch(1),drain=new CountDownLatch(1);
   fixture.before=path->{if(path.contains("/artifacts/")){entered.countDown();try{new CountDownLatch(1).await(10,TimeUnit.SECONDS);}catch(InterruptedException error){interrupted.countDown();drain.await(10,TimeUnit.SECONDS);throw error;}}};
   var pending=provider.acquire("fixture_a",1);check(entered.await(10,TimeUnit.SECONDS),"Exact generation owns an in-flight artifact request");provider.cancel("fixture_a",1);
   check(interrupted.await(5,TimeUnit.SECONDS)&&!pending.toCompletableFuture().isDone(),"Transfer interruption preserves the drain barrier until its finally exits");
   check(sibling.authorized(),"Canceling a transfer keeps other installed modules authorized");drain.countDown();cancelled(pending);
   fixture.before=path->{};var next=get(provider.acquire("fixture_a",2));provider.cancel("fixture_a",1);
   check(next.authorized()&&sibling.authorized(),"Worker pool recovers and old cancellation cannot affect replacement or siblings");next.close();sibling.close();
   stopped(provider);check(failure.get()==null&&count(provider,"installedLeases")==0&&ModuleResourceAssets.retainedBytes()==0,"Canceled transfer leaves no leases, assets, or auth failure");
  }
 }
 private static void cancelDeniedTransfer(JsonObject golden)throws Exception{
  try(var fixture=new ProviderFixture(golden)){
   AtomicReference<Throwable> failure=new AtomicReference<>();var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure::set);
   var sibling=get(provider.acquire("fixture_b",1));CountDownLatch entered=new CountDownLatch(1);
   fixture.before=path->{if(path.contains("/artifacts/")){entered.countDown();try{new CountDownLatch(1).await(10,TimeUnit.SECONDS);}catch(InterruptedException ignored){/* Response already arriving despite cancellation. */}}};
   var pending=provider.acquire("fixture_a",1);check(entered.await(10,TimeUnit.SECONDS),"Denial race holds a pending transfer after authorization");
   fixture.status=403;provider.cancel("fixture_a",1);cancelled(pending);waitFor(()->failure.get()!=null,()->{});
   check(failure.get() instanceof java.security.GeneralSecurityException&&!sibling.authorized(),"A real server denial remains fail-closed even when its caller cancels concurrently");
   sibling.close();stopped(provider);check(count(provider,"installedLeases")==0,"Denial/cancellation race leaves no installed references");
  }
 }
 @SuppressWarnings("unchecked") private static Map<String,byte[]> assets(ModulePayloadLease lease)throws Exception{
  var field=lease.getClass().getDeclaredField("pendingAssets");field.setAccessible(true);return (Map<String,byte[]>)field.get(lease);
 }
 private static boolean zeroed(Map<String,byte[]> assets){return assets.values().stream().allMatch(bytes->{for(byte b:bytes)if(b!=0)return false;return true;});}
 private static void workerAssets(JsonObject golden)throws Exception{
  try(var fixture=new ProviderFixture(golden)){
   AtomicReference<Throwable> failure=new AtomicReference<>();var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure::set);
   CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
   fixture.before=path->{if(path.contains("/artifacts/")){entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Asset worker barrier expired");}};
   var pending=provider.acquire("fixture_a",1);check(entered.await(10,TimeUnit.SECONDS),"Asset preparation test pauses transfer before worker publication");
   AtomicReference<String> preparationThread=new AtomicReference<>();AtomicReference<Map<String,byte[]>> preparedAssets=new AtomicReference<>();
   var observed=pending.whenComplete((lease,error)->{if(error==null){preparationThread.set(Thread.currentThread().getName());try{preparedAssets.set(assets(lease));}catch(Exception problem){throw new CompletionException(problem);}}});
   release.countDown();var lease=get(observed);Map<String,byte[]> copy=preparedAssets.get();
   check(preparationThread.get().equals("Celeste module delivery") && copy!=null && !copy.isEmpty(),"Owned asset snapshot exists before completion leaves the delivery worker");
   check(ModuleResourceAssets.retainedBytes()==0 && !zeroed(copy),"Prepared assets remain unpublished and intact until owner installation");
   lease.close();check(assets(lease)==null && zeroed(copy),"Cancelled/unconsumed lease wipes every pending asset and drops map reference");
   fixture.before=path->{};
   var rejected=get(provider.acquire("fixture_a",2));Map<String,byte[]> rejectedAssets=assets(rejected);
   var endedScope=new ModuleScope();endedScope.close();endedScope.retired().toCompletableFuture().get(5,TimeUnit.SECONDS);
   try{rejected.create(new ModuleServices("fixture_a",2,endedScope,new ModuleHookRegistry((id,g)->true),()->null));throw new AssertionError("Retired scope accepted asset installation");}
   catch(IllegalStateException expected){assertions++;}
   check(assets(rejected)==null && zeroed(rejectedAssets),"Rejected owner-thread asset installation wipes pending copies");rejected.close();
   var accepted=get(provider.acquire("fixture_a",3));Map<String,byte[]> acceptedAssets=assets(accepted);
   var scope=new ModuleScope();var hooks=new ModuleHookRegistry((id,g)->true);hooks.declare("fixture.value",0);
   try(var context=scope.enter()){accepted.create(new ModuleServices("fixture_a",3,scope,hooks,()->null));}
   check(assets(accepted)==null && !zeroed(acceptedAssets) && ModuleResourceAssets.retainedBytes()>0,"Successful owner installation transfers live buffers to scoped registry");
   accepted.close();check(!zeroed(acceptedAssets),"Loader closure cannot wipe transferred registry-owned assets prematurely");
   scope.close();scope.retired().toCompletableFuture().get(5,TimeUnit.SECONDS);
   check(zeroed(acceptedAssets) && ModuleResourceAssets.retainedBytes()==0,"Scoped resource retirement wipes transferred buffers exactly at ownership end");
   stopped(provider);check(failure.get()==null && count(provider,"installedLeases")==0,"Worker asset lifecycle leaves no authorization rejection or retained leases");
  }
 }
 private static void warmAndAssets(JsonObject golden)throws Exception{
  try(var fixture=new ProviderFixture(golden)){
   AtomicReference<Throwable> authFailure=new AtomicReference<>();var provider=new AuthenticatedModulePayloadProvider(fixture.client(),authFailure::set);
   List<WeakReference<ClassLoader>> retired=new ArrayList<>();
   for(int cycle=0;cycle<4;cycle++){
    int before=fixture.authorizations,transfers=fixture.transfers;
    retired.add(activateAndRetire(provider,cycle+1));
    check(fixture.authorizations==before+1,"Every warm activation receives fresh server permission");
    check(cycle==0?fixture.transfers==transfers+1:fixture.transfers==transfers,"Warm activation reuses only ciphertext");
    check(count(provider,"installedLeases")==0 && count(provider,"outstanding")==0,"Retirement removes provider references");
    check(ModuleResourceAssets.retainedBytes()==0,"All generation-owned assets released");
   }
   stopped(provider);check(authFailure.get()==null,"Valid retirement never triggers authorization lock");
   // Diagnostic only: forced GC exists only in this offline acceptance executable.
   for(int n=0;n<12 && retired.stream().anyMatch(r->r.get()!=null);n++){System.gc();Thread.sleep(25);}
   check(retired.stream().allMatch(r->r.get()==null),"Retired child loaders are unreachable and collectable after repeated cycles");
  }
 }
 private static WeakReference<ClassLoader> activateAndRetire(AuthenticatedModulePayloadProvider provider,long generation)throws Exception{
  var lease=get(provider.acquire("fixture_a",generation));var scope=new ModuleScope();var hooks=new ModuleHookRegistry((id,g)->true);hooks.declare("fixture.value",0);
  check(count(provider,"installedLeases")==1,"Provider tracks only delivered live lease");
  try{lease.create(new ModuleServices("fixture_a",generation+1,scope,hooks,()->null));throw new AssertionError("Wrong generation accepted");}
  catch(SecurityException expected){assertions++;}
  ModuleFeature feature;
  try(var context=scope.enter()){feature=lease.create(new ModuleServices("fixture_a",generation,scope,hooks,()->null));}
  check(feature.getClass().getClassLoader()==lease.definingLoader(),"Feature is defined by independent child loader");
  check(feature.name().equals("fixture"),"Static initializer read its authenticated assets before factory execution");
  check(hooks.invoke("fixture_a","fixture.value","off").equals("active"),"Child hook executes in own live scope");
  provider.verifyRetainedModules();assertions++;
  var weak=new WeakReference<>(lease.definingLoader());scope.close();scope.retired().toCompletableFuture().get(5,TimeUnit.SECONDS);lease.close();
  check(hooks.invoke("fixture_a","fixture.value","off").equals("off"),"Retired child callback detached");return weak;
 }
 private static void bounded(JsonObject golden)throws Exception{
  try(var fixture=new ProviderFixture(golden)){
   var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure->{throw new AssertionError(failure);});
   CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();
   fixture.before=path->{if(path.contains("/artifacts/")){int now=active.incrementAndGet();maximum.accumulateAndGet(now,Math::max);entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Fixture barrier expired");}};
   fixture.after=path->{if(path.contains("/artifacts/"))active.decrementAndGet();};
   var a=provider.acquire("fixture_a",1);var b=provider.acquire("fixture_b",1);var c=provider.acquire("fixture_c",1);
   check(entered.await(10,TimeUnit.SECONDS),"Two independent module transfers start concurrently");
   check(maximum.get()==2,"Transfer workers remain bounded at two");check(!c.toCompletableFuture().isDone(),"Third module waits without blocking owner thread");
   release.countDown();get(a).close();get(b).close();get(c).close();
   check(fixture.transfers==3 && maximum.get()==2,"All queued transfers finish within concurrency limit");
   stopped(provider);check(count(provider,"installedLeases")==0,"No delivered lease retained after explicit closure");
  }
 }
 private static void cancelGeneration(JsonObject golden)throws Exception{
  try(var fixture=new ProviderFixture(golden)){
   var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure->{throw new AssertionError(failure);});
   ConcurrentLinkedQueue<Runnable> queue=new ConcurrentLinkedQueue<>();Runnable pump=()->{for(Runnable task; (task=queue.poll())!=null;)task.run();};
   Map<String,ModuleFeature> features=new HashMap<>();AtomicReference<Throwable> failure=new AtomicReference<>();
   var runtime=new ModulePayloadRuntime(provider,queue::add,id->null,new ModulePayloadRuntime.Listener(){
    public void installed(String id,ModuleFeature feature){features.put(id,feature);}
    public void retiring(String id,ModuleFeature feature){features.remove(id);}
    public void failed(String id,Throwable problem){failure.set(problem);}
   });
   runtime.register("fixture_a");runtime.hooks().declare("fixture.value",0);
   CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
   fixture.before=path->{if(path.contains("/artifacts/")){entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Fixture barrier expired");}};
   runtime.synchronize(Set.of("fixture_a"));check(entered.await(10,TimeUnit.SECONDS),"Fetch suspended before installation");
   runtime.synchronize(Set.of());check(runtime.state("fixture_a")==ModulePayloadRuntime.State.RETIRING,"Disable blocks pending generation immediately");
   runtime.synchronize(Set.of("fixture_a"));release.countDown();
   waitFor(()->runtime.state("fixture_a")==ModulePayloadRuntime.State.OFF,pump);
   check(features.isEmpty() && count(provider,"installedLeases")==0,"Outdated completion closes without defining or installing a feature");
   fixture.before=path->{};runtime.synchronize(Set.of("fixture_a"));
   waitFor(()->runtime.state("fixture_a")==ModulePayloadRuntime.State.AVAILABLE || failure.get()!=null,pump);
   check(failure.get()==null && features.size()==1,"Replacement generation initializes once old generation drains");
   check(runtime.hooks().invoke("fixture_a","fixture.value","off").equals("off"),"Callbacks stay blocked until onEnable completes");
   runtime.started("fixture_a");check(runtime.hooks().invoke("fixture_a","fixture.value","off").equals("active"),"Started generation dispatches");
   WeakReference<ClassLoader> weak=new WeakReference<>(features.get("fixture_a").getClass().getClassLoader());
   runtime.synchronize(Set.of());waitFor(()->runtime.state("fixture_a")==ModulePayloadRuntime.State.OFF,pump);
   runtime.close();pump.run();runtime.stopped().toCompletableFuture().get(5,TimeUnit.SECONDS);stopped(provider);
   check(features.isEmpty() && count(provider,"installedLeases")==0 && ModuleResourceAssets.retainedBytes()==0,"Runtime shutdown removes classloader, resources and callbacks");
   for(int n=0;n<12&&weak.get()!=null;n++){System.gc();Thread.sleep(25);}
   check(weak.get()==null,"Actual runtime retirement leaves no reachable child loader");
  }
 }
 private static void closeRace(JsonObject golden)throws Exception{
  try(var fixture=new ProviderFixture(golden)){
   var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure->{});CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
   fixture.before=path->{if(path.endsWith("/authorize")){entered.countDown();release.await(10,TimeUnit.SECONDS);}};
   List<CompletionStage<ModulePayloadLease>> pending=new ArrayList<>();pending.add(provider.acquire("fixture_a",1));check(entered.await(10,TimeUnit.SECONDS),"Authorization is in flight for close-race test");
   for(int i=0;i<12;i++)pending.add(provider.acquire("fixture_b",i+1));
   provider.close();release.countDown();provider.stopped().toCompletableFuture().get(15,TimeUnit.SECONDS);
   check(pending.stream().allMatch(p->p.toCompletableFuture().isCompletedExceptionally()),"Close rejects in-flight and queued acquisitions");
   check(count(provider,"outstanding")==0 && count(provider,"installedLeases")==0 && fixture.transfers==0,"Closed provider retains no futures/leases and starts no queued downloads");
   check(provider.acquire("fixture_c",1).toCompletableFuture().isCompletedExceptionally(),"Closed provider refuses new work");
  }
 }
 private static void closeFinalValidation(JsonObject golden,boolean atArchive)throws Exception{
  try(var fixture=new ProviderFixture(golden)){
   AtomicReference<Throwable> failure=new AtomicReference<>();var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure::set);
   CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean once=new AtomicBoolean();
   fixture.observeSession=session->{
    boolean prepared=false,preparing=false,readingArchive=false;
    for(var frame:Thread.currentThread().getStackTrace()){
     prepared|=frame.getClassName().endsWith("ModuleDeliveryClient$Prepared")&&frame.getMethodName().equals("authorized");
     readingArchive|=frame.getClassName().endsWith("ModuleDeliveryClient$Prepared")&&frame.getMethodName().equals("jar");
     preparing|=frame.getClassName().endsWith("AuthenticatedModulePayloadProvider")&&frame.getMethodName().equals("prepare");
    }
    if(!prepared||!preparing||readingArchive!=atArchive||!once.compareAndSet(false,true))return session;
    entered.countDown();boolean interrupted=false;
    for(;;)try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Final validation barrier expired");break;}
    catch(InterruptedException expected){interrupted=true;}
    if(interrupted)Thread.currentThread().interrupt();
    return null; // Resident authority disappears as the provider is shut down.
   };
   try{
    var pending=provider.acquire("fixture_a",1);check(entered.await(10,TimeUnit.SECONDS),"Close race reaches prepared lease validation: "+(atArchive?"archive access":"final check"));
    provider.close();release.countDown();provider.stopped().toCompletableFuture().get(15,TimeUnit.SECONDS);cancelled(pending);
    if(failure.get()!=null)throw new AssertionError("Provider shutdown during final local lease validation is cancellation, not an auth rejection",failure.get());
    assertions++;
    check(count(provider,"installedLeases")==0&&count(provider,"requests")==0&&count(provider.delivery(),"active")==0&&ModuleResourceAssets.retainedBytes()==0,"Final validation shutdown drains prepared buffers and publishes no module");
   }finally{release.countDown();stopped(provider);}
  }
 }
 private static void diagnosticRetention(JsonObject golden)throws Exception{
  try(var fixture=new ProviderFixture(golden)){
   var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure->{throw new AssertionError(failure);});
   var lease=get(provider.acquire("fixture_a",1));var sibling=get(provider.acquire("fixture_b",1));
   var loader=(dev.c4lam.celeste.beta.memory.ModuleMemoryClassLoader)lease.definingLoader();
   var siblingLoader=(dev.c4lam.celeste.beta.memory.ModuleMemoryClassLoader)sibling.definingLoader();
   var entriesField=loader.getClass().getDeclaredField("entries");entriesField.setAccessible(true);
   @SuppressWarnings("unchecked") Map<String,byte[]> entries=(Map<String,byte[]>)entriesField.get(loader);
   Map<String,byte[]> retained=new HashMap<>(entries);byte[] bytes=retained.get("assets/celeste/provider-fixture.txt");bytes[0]^=1;
   AuthenticatedModulePayloadProvider.RetainedModuleMismatch diagnostic;
   try{provider.verifyRetainedModules();throw new AssertionError("Expected confirmed retained mismatch");}
   catch(AuthenticatedModulePayloadProvider.RetainedModuleMismatch detected){diagnostic=detected;}
   try{
    provider.quarantine();lease.close();sibling.close();
    check(!lease.authorized()&&lease.definingLoader()==null&&count(provider,"installedLeases")==0,"Mid-tick retirement immediately removes live module access despite diagnostic pin");
    check(siblingLoader.isClosed()&&!loader.isClosed()&&!zeroed(retained),"Only exact mismatching source remains pinned; unrelated modules wipe normally");
    check(provider.acquire("fixture_a",2).toCompletableFuture().isCompletedExceptionally(),"Quarantine never grants a replacement activation");
    var first=diagnostic.observation();var second=diagnostic.sample();
    check(first.equals(second)&&first.artifact().equals("00000000000000000000000000000001"),"Fresh confirmation still hashes exact approved opaque artifact after retirement");
    bytes[0]^=2;var changed=diagnostic.sample();
    check(first.sameFamily(changed)&&!first.observed().equals(changed.observed()),"Diagnostic pin rehashes fresh bytes instead of replaying first mismatch");
   }finally{diagnostic.close();}
   check(loader.isClosed()&&zeroed(retained)&&diagnostic.sample()==null,"Reporter retirement wipes deferred bytes and ends observation rights");
   diagnostic.close();lease.close();stopped(provider);
   check(loader.isClosed()&&count(provider,"installedLeases")==0,"Diagnostic and lease retirement are idempotent");
  }
 }
 private static void nonBlockingIntegrity(JsonObject golden)throws Exception{
  try(var fixture=new ProviderFixture(golden)){
   var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure->{throw new AssertionError(failure);});
   var lease=get(provider.acquire("fixture_a",1));ClassLoader loader=lease.definingLoader();
   var entriesField=loader.getClass().getDeclaredField("entries");entriesField.setAccessible(true);
   @SuppressWarnings("unchecked") Map<String,byte[]> entries=(Map<String,byte[]>)entriesField.get(loader);
   byte[] retained=entries.get("assets/celeste/provider-fixture.txt");retained[0]^=1;
   try{provider.verifyRetainedModules();throw new AssertionError("Corrupt live lease accepted");}
   catch(AuthenticatedModulePayloadProvider.RetainedModuleMismatch expected){expected.close();assertions++;}
   finally{retained[0]^=1;}
   AtomicReference<Throwable> integrityFailure=new AtomicReference<>();
   Thread verifier=new Thread(()->{try{provider.verifyRetainedModules();}catch(Throwable failure){integrityFailure.set(failure);}},"fixture-integrity-blocked");
   verifier.setDaemon(true);
   synchronized(loader){
    verifier.start();waitFor(()->verifier.getState()==Thread.State.BLOCKED,()->{});
    FutureTask<Boolean> dispatch=new FutureTask<>(lease::authorized);Thread game=new Thread(dispatch,"fixture-dispatch");game.setDaemon(true);game.start();
    check(dispatch.get(1,TimeUnit.SECONDS),"Game/render authorization never waits on retained-entry hashing or its lease monitor");
    // Retire reentrantly while the verifier waits for this same loader monitor.
    lease.close();check(!lease.authorized(),"Retirement revokes dispatch while an integrity scan is pending");
   }
   verifier.join(5000);
   check(!verifier.isAlive() && integrityFailure.get()==null,"Normal concurrent retirement does not produce a false integrity rejection");
   stopped(provider);check(count(provider,"installedLeases")==0,"Integrity/retirement race leaves no retained lease");
  }
 }
 private static void prefetchCatalog(JsonObject golden)throws Exception{
  try(var fixture=new ProviderFixture(golden)){
   AtomicReference<Throwable> failure=new AtomicReference<>();var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure::set);
   CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
   fixture.before=path->{if(path.endsWith("/catalog")){entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Prefetch barrier expired");}};
   var first=provider.prefetchCatalog();check(entered.await(10,TimeUnit.SECONDS),"Signed metadata prefetch runs on the authorization worker");
   var repeated=provider.prefetchCatalog();var activation=provider.acquire("fixture_a",1);
   check(fixture.transfers==0&&fixture.authorizations==0,"Prefetch never loads executable code or grants module permissions");
   release.countDown();first.toCompletableFuture().get(10,TimeUnit.SECONDS);repeated.toCompletableFuture().get(10,TimeUnit.SECONDS);
   get(activation).close();
   check(fixture.catalogRequests==1&&fixture.authorizations==1&&fixture.transfers==1,"Prefetch and simultaneous first activation share one catalog request with separate live permission");
   provider.prefetchCatalog().toCompletableFuture().get(10,TimeUnit.SECONDS);
   check(fixture.catalogRequests==1&&failure.get()==null,"Completed prefetch does not repeat network work");stopped(provider);
  }
  try(var fixture=new ProviderFixture(golden)){
   AtomicReference<Throwable> failure=new AtomicReference<>();var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure::set);
   fixture.status=503;
   try{provider.prefetchCatalog().toCompletableFuture().get(10,TimeUnit.SECONDS);throw new AssertionError("Unavailable prefetch succeeded");}
   catch(ExecutionException expected){assertions++;}
   check(failure.get()==null,"Transient catalog prefetch failure leaves authorization retryable");
   fixture.status=200;get(provider.acquire("fixture_a",1)).close();
   check(fixture.catalogRequests==2&&fixture.authorizations==1,"First activation retries unavailable prefetch and still obtains fresh permission");stopped(provider);
  }
  try(var fixture=new ProviderFixture(golden)){
   AtomicReference<Throwable> failure=new AtomicReference<>();var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure::set);
   fixture.status=403;
   try{provider.prefetchCatalog().toCompletableFuture().get(10,TimeUnit.SECONDS);throw new AssertionError("Denied prefetch succeeded");}
   catch(ExecutionException expected){assertions++;}
   check(failure.get() instanceof java.security.GeneralSecurityException && fixture.transfers==0,"Explicit prefetch rejection fails closed without downloading modules");stopped(provider);
  }
  try(var fixture=new ProviderFixture(golden)){
   var provider=new AuthenticatedModulePayloadProvider(fixture.client(),failure->{});CountDownLatch entered=new CountDownLatch(1);
   fixture.before=path->{if(path.endsWith("/catalog")){entered.countDown();new CountDownLatch(1).await(10,TimeUnit.SECONDS);}};
   var prefetch=provider.prefetchCatalog();check(entered.await(10,TimeUnit.SECONDS),"Close test holds a pending catalog request");
   stopped(provider);check(prefetch.toCompletableFuture().isCompletedExceptionally(),"Provider retirement cancels pending prefetch completion");
  }
 }
}
