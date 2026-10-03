package dev.c4lam.celeste.beta.delivery;

import dev.c4lam.celeste.beta.memory.ModuleMemoryClassLoader;
import java.io.IOException;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Deterministic lifetime tests: pause the actual SHA-256 provider, never the loader monitor. */
public final class ModuleRetainedReadConcurrencyCheck {
    private static final String PROVIDER="CelesteModuleBorrowFixture";
    private static volatile Pause pause;
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static void rejected(Action action)throws Exception{
        try{action.run();throw new AssertionError("Retired operation succeeded");}
        catch(IOException|GeneralSecurityException|IllegalStateException|ClassNotFoundException expected){assertions++;}
    }
    private static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError("Fixture condition timed out");Thread.sleep(1);}
    }
    private static Thread thread(String name,Runnable run){Thread result=new Thread(run,name);result.setDaemon(true);return result;}
    private static void join(Thread thread)throws Exception{thread.join(5000);check(!thread.isAlive(),"Fixture thread terminates: "+thread.getName());}
    private static final class Pause {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicBoolean once=new AtomicBoolean();
        volatile Thread thread;
        volatile Runnable inside=()->{};
        volatile boolean fail;
        void visit(){
            if(Thread.currentThread()!=thread || !once.compareAndSet(false,true))return;
            inside.run();entered.countDown();
            try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Paused digest timed out");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ProviderException(interrupted);}
            if(fail)throw new ProviderException("Synthetic hash failure");
        }
    }
    public static final class PausingSha256 extends MessageDigestSpi {
        private final MessageDigest actual;
        public PausingSha256(){try{actual=MessageDigest.getInstance("SHA-256","SUN");}catch(GeneralSecurityException failure){throw new ProviderException(failure);}}
        private static void visit(){Pause current=pause;if(current!=null)current.visit();}
        @Override protected void engineUpdate(byte value){visit();actual.update(value);}
        @Override protected void engineUpdate(byte[] bytes,int start,int count){visit();actual.update(bytes,start,count);}
        @Override protected byte[] engineDigest(){return actual.digest();}
        @Override protected void engineReset(){actual.reset();}
        @Override protected int engineGetDigestLength(){return 32;}
    }
    public static int run(byte[] jar,String digest,String inventory)throws Exception{
        assertions=0;
        Provider provider=new Provider(PROVIDER,"1","Offline module lifetime barrier"){};
        provider.put("MessageDigest.SHA-256",PausingSha256.class.getName());
        check(Security.insertProviderAt(provider,1)==1,"Fixture digest provider installed only for isolated offline test");
        try{readsAndClose(jar,digest,inventory);failureReleasesBorrow(jar,digest,inventory);}
        finally{Pause current=pause;if(current!=null)current.release.countDown();pause=null;Security.removeProvider(PROVIDER);}
        return assertions;
    }
    private static ModuleMemoryClassLoader open(byte[] jar,String digest,String inventory)throws Exception{
        return ModuleMemoryClassLoader.open(ModuleRetainedReadConcurrencyCheck.class.getClassLoader(),jar,digest,inventory);
    }
    @SuppressWarnings("unchecked")
    private static void readsAndClose(byte[] jar,String digest,String inventory)throws Exception{
        ModuleMemoryClassLoader loader=open(jar,digest,inventory);
        var field=ModuleMemoryClassLoader.class.getDeclaredField("entries");field.setAccessible(true);
        Map<String,byte[]> entries=(Map<String,byte[]>)field.get(loader);
        List<byte[]> borrowed=new ArrayList<>(entries.values());byte[] asset=entries.get("assets/celeste/fixture.txt");
        var stream=loader.getResourceAsStream("assets/celeste/fixture.txt");var url=loader.getResource("assets/celeste/fixture.txt");
        Pause barrier=new Pause();pause=barrier;AtomicReference<Throwable> verifyFailure=new AtomicReference<>();
        AtomicBoolean recursiveCloseRejected=new AtomicBoolean(),recursiveVerifyRejected=new AtomicBoolean();
        barrier.inside=()->{
            try{loader.close();throw new AssertionError("Recursive close accepted");}catch(IllegalStateException expected){recursiveCloseRejected.set(true);}
            try{loader.verifyIntegrity();throw new AssertionError("Recursive verification accepted");}catch(GeneralSecurityException expected){recursiveVerifyRejected.set(true);}
        };
        Thread verifier=thread("module-borrow-verifier",()->{try{loader.verifyIntegrity();}catch(Throwable error){verifyFailure.set(error);}});barrier.thread=verifier;
        try{
            verifier.start();check(barrier.entered.await(5,TimeUnit.SECONDS),"Actual entry hash is paused outside loader monitor");
            check(recursiveCloseRejected.get() && recursiveVerifyRejected.get(),"Recursive close/verification rejected without invalidating live borrow");
            FutureTask<Boolean> read=new FutureTask<>(()->{
                Class<?> entry=loader.loadClass("fixture.independent.Entry");
                return entry.getClassLoader()==loader && stream.read()=='v' && url.openStream().read()=='v';
            });Thread game=thread("module-borrow-resource-reader",read);game.start();
            check(read.get(1,TimeUnit.SECONDS),"Lazy class definition and existing stream/URL reads proceed during paused hash");join(game);
            AtomicReference<Throwable> queuedFailure=new AtomicReference<>();AtomicBoolean queuedInterrupted=new AtomicBoolean();
            Thread queued=thread("module-borrow-queued-verifier",()->{try{loader.verifyIntegrity();}catch(Throwable error){queuedFailure.set(error);}finally{queuedInterrupted.set(Thread.currentThread().isInterrupted());}});
            queued.start();await(()->queued.getState()==Thread.State.WAITING);queued.interrupt();join(queued);
            check(queuedFailure.get() instanceof GeneralSecurityException && queuedInterrupted.get(),"Queued verification serializes and preserves interruption");
            AtomicBoolean closeInterrupted=new AtomicBoolean();Thread closer=thread("module-borrow-closer",()->{loader.close();closeInterrupted.set(Thread.currentThread().isInterrupted());});closer.start();
            await(loader::isClosed);await(()->closer.getState()==Thread.State.WAITING);
            check(closer.isAlive() && asset[0]=='v',"Retirement revokes immediately but cannot wipe borrowed bytes");
            check(loader.getResourceAsStream("assets/celeste/fixture.txt")==null && loader.getResource("assets/celeste/fixture.txt")==null,"New resource handles fail during retirement wait");
            rejected(stream::read);rejected(()->url.openStream().read());rejected(()->loader.loadClass("fixture.independent.Entry"));
            rejected(loader::assetSnapshot);rejected(loader::verifyIntegrity);
            closer.interrupt();await(()->closer.getState()==Thread.State.WAITING);
            check(asset[0]=='v',"Interrupted closer still preserves active borrowed bytes");
            barrier.release.countDown();join(verifier);join(closer);
            check(verifyFailure.get()==null,"Complete hash uses original bytes during concurrent retirement");
            check(closeInterrupted.get(),"Close restores interruption only after wipe completes");
            check(entries.isEmpty() && borrowed.stream().allMatch(bytes->{for(byte value:bytes)if(value!=0)return false;return true;}),"Returning the borrow permits exact owned-array wipe and map release");
            loader.close();check(loader.isClosed(),"Completed close remains idempotent");
        }finally{barrier.release.countDown();verifier.join(5000);loader.close();pause=null;}
    }
    private static void failureReleasesBorrow(byte[] jar,String digest,String inventory)throws Exception{
        try(var loader=open(jar,digest,inventory)){
            Pause barrier=new Pause();barrier.thread=Thread.currentThread();barrier.fail=true;barrier.release.countDown();pause=barrier;
            try{loader.verifyIntegrity();throw new AssertionError("Synthetic hash failure ignored");}catch(ProviderException expected){assertions++;}
            finally{pause=null;}
            loader.verifyIntegrity();check(!loader.isClosed(),"Hash failure releases borrow; a fresh complete verification still succeeds");
        }
    }
}
