package dev.c4lam.celeste.module.delivery;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the actual resident bindings, with no Minecraft process or substituted implementation. */
public final class ScopedActionOwnersCheck {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        ScopedActionOwners owners=new ScopedActionOwners();
        AtomicBoolean permitted=new AtomicBoolean(true);
        ModuleScope scope=new ModuleScope(permitted::get);
        Object childOwner=new Object();AtomicInteger cleanup=new AtomicInteger();
        try(var ignored=scope.enter()){
            check(owners.track(childOwner,cleanup::incrementAndGet),"owner acquired while active");
            check(owners.track(childOwner,cleanup::incrementAndGet),"same owner coalesces");
        }
        check(owners.size()==1,"one binding per owner");
        permitted.set(false);
        check(!owners.allowed(childOwner),"revocation rejects pending work immediately");
        try(var ignored=scope.enter()){check(!owners.track(new Object(),()->{}),"expired scope cannot acquire another owner");}
        scope.close();scope.retired().toCompletableFuture().join();
        check(cleanup.get()==1 && owners.size()==0,"disable cleans once and drops parent map references");

        ModuleScope reuse=new ModuleScope();
        try(var ignored=reuse.enter()){
            for(int i=0;i<10000;i++){
                Object key=new Object();owners.track(key,()->{throw new AssertionError("released owner retired twice");});
                owners.release(key);
            }
        }
        check(owners.size()==0,"repeated actions do not grow registry");
        var field=ModuleScope.class.getDeclaredField("owned");field.setAccessible(true);
        check(((java.util.List<?>)field.get(reuse)).isEmpty(),"completed operations do not accumulate in activation scope");
        reuse.close();reuse.retired().toCompletableFuture().join();

        ModuleScope first=new ModuleScope(),second=new ModuleScope();
        AtomicInteger firstCleanup=new AtomicInteger(),secondCleanup=new AtomicInteger();
        Object sharedBroker=new Object();
        try(var ignored=first.enter()){owners.track(sharedBroker,firstCleanup::incrementAndGet);}
        try(var ignored=second.enter()){owners.track(sharedBroker,secondCleanup::incrementAndGet);}
        first.close();first.retired().toCompletableFuture().join();
        check(firstCleanup.get()==0 && owners.size()==1,"old generation cannot release new shared broker owner");
        second.close();second.retired().toCompletableFuture().join();
        check(secondCleanup.get()==1 && owners.size()==0,"new generation retains its own cleanup");

        ModuleScope frame=new ModuleScope();
        CompletableFuture<Void> running=new CompletableFuture<>();AtomicInteger gpuReleased=new AtomicInteger();
        frame.ownTransient(()->{},running);
        frame.releaseAfterCallbacks(gpuReleased::incrementAndGet);
        frame.close();
        check(!frame.retired().toCompletableFuture().isDone() && gpuReleased.get()==0,"running pass blocks GPU retirement");
        running.complete(null);frame.retired().toCompletableFuture().join();
        check(gpuReleased.get()==1,"GPU cleanup follows pass completion");

        ModuleScope failed=new ModuleScope();Object failOwner=new Object();
        try(var ignored=failed.enter()){owners.track(failOwner,()->{throw new IllegalStateException("fixture failure");});}
        failed.close();
        check(failed.retired().toCompletableFuture().isCompletedExceptionally(),"cleanup failure remains visible");
        check(owners.size()==0,"failed callback is not retained by resident registry");
        System.out.println("Scoped action ownership: "+checks+" assertions passed");
    }
}
