package fixture;

import dev.c4lam.celeste.module.delivery.ModuleScope;
import dev.c4lam.celeste.action.SwordAttackCoordinator;
import dev.c4lam.celeste.action.SwordAttackCoordinator.AttackPolicy;
import dev.c4lam.celeste.action.SwordAttackCoordinator.SwapIntent;
import dev.c4lam.celeste.silent.SilentSlotManager;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;

public final class SwordPolicyLifecycleCheck {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] ignored)throws Exception{
        authorization(); retirement(); ordering(); exceptionCleanup();
        System.out.println("{\"checks\":"+checks+",\"status\":\"pass\"}");
    }
    private static void authorization(){
        AtomicBoolean allowed=new AtomicBoolean(true);AtomicInteger calls=new AtomicInteger();
        ModuleScope scope=new ModuleScope(allowed::get);
        SwordAttackCoordinator.Registration registration;
        try(var context=scope.enter()){
            registration=SwordAttackCoordinator.register(new Object(),new AttackPolicy(){
                public boolean queue(MinecraftClient client,LivingEntity target){
                    check(ModuleScope.current()==scope,"policy callback carries owner scope");calls.incrementAndGet();return true;
                }
            });
        }
        check(SwordAttackCoordinator.queueAutomaticMaceStun(null,null),"authorized policy invoked");
        allowed.set(false);
        check(!SwordAttackCoordinator.queueAutomaticMaceStun(null,null),"expired policy skipped immediately");
        check(calls.get()==1,"lease failure performs no callback");
        allowed.set(true);check(SwordAttackCoordinator.queueAutomaticMaceStun(null,null),"same generation can resume a still-valid scope");
        scope.close();check(registration.detached().toCompletableFuture().isDone(),"scope detaches registration");
        check(scope.retired().toCompletableFuture().isDone(),"retirement completes without retained callback");
        check(!SwordAttackCoordinator.queueAutomaticMaceStun(null,null),"disabled policy unavailable");
        check(ModuleScope.current()==null,"ownership restored after callback");
    }
    private static void retirement()throws Exception{
        ModuleScope scope=new ModuleScope();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        SwordAttackCoordinator.Registration registration;
        try(var context=scope.enter()){
            registration=SwordAttackCoordinator.register(new Object(),new AttackPolicy(){
                public boolean queue(MinecraftClient client,LivingEntity target){entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}return true;}
            });
        }
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<Boolean> callback=worker.submit(()->SwordAttackCoordinator.queueAutomaticMaceStun(null,null));
            check(entered.await(3,TimeUnit.SECONDS),"callback entered");scope.close();
            check(!registration.detached().toCompletableFuture().isDone(),"in-flight callback delays detach completion");
            check(!scope.retired().toCompletableFuture().isDone(),"unload waits for active callback");
            check(!SwordAttackCoordinator.queueAutomaticMaceStun(null,null),"retiring callback cannot be reentered");
            release.countDown();check(callback.get(3,TimeUnit.SECONDS),"in-flight callback can finish");
            scope.retired().toCompletableFuture().get(3,TimeUnit.SECONDS);
            check(registration.detached().toCompletableFuture().isDone(),"drained callback detached");
        }finally{release.countDown();worker.shutdownNow();}
    }
    private static void ordering(){
        MinecraftClient client=new MinecraftClient();Object low=new Object(),high=new Object();AtomicInteger finished=new AtomicInteger();
        try(var lowReg=SwordAttackCoordinator.register(low,new AttackPolicy(){public SwapIntent select(MinecraftClient c){return new SwapIntent(low,1,100,false);}});
            var highReg=SwordAttackCoordinator.register(high,new AttackPolicy(){public SwapIntent select(MinecraftClient c){return new SwapIntent(high,2,200,false);}public void finished(MinecraftClient c,net.minecraft.client.network.ClientPlayerEntity p,boolean retained){finished.incrementAndGet();}})){
            var attack=SwordAttackCoordinator.beginVanillaAttack(client);
            check(attack.proceed(),"selected physical attack proceeds");check(client.player.inventory.selected==2,"higher priority module owns slot");
            check(SwordAttackCoordinator.mainHandForVanillaClick(null)==client.player.inventory.items[2],"click observes selected item");
            SwordAttackCoordinator.finishVanillaAttack(client,attack);
            check(SilentSlotManager.getInstance().restore==0,"visible slot restores original next tick");check(finished.get()==1,"only selected owner finishes");
            check(SwordAttackCoordinator.mainHandForVanillaClick(null)==null,"transient vanilla click released");
            try(var veto=SwordAttackCoordinator.register(new Object(),new AttackPolicy(){public boolean cancel(MinecraftClient c,boolean validated){return validated;}})){
                client.player.inventory.selected=0;attack=SwordAttackCoordinator.beginVanillaAttack(client);
                check(!attack.proceed()&&client.player.inventory.selected==0,"policy veto happens before any swap");SwordAttackCoordinator.finishVanillaAttack(client,attack);
            }
        }
        check(!SwordAttackCoordinator.queueAutomaticMaceStun(null,null),"all policy registrations retired");
    }
    private static void exceptionCleanup(){
        ModuleScope scope=new ModuleScope();
        try(var context=scope.enter()){
            SwordAttackCoordinator.register(new Object(),new AttackPolicy(){public boolean queue(MinecraftClient c,LivingEntity e){throw new IllegalStateException("fixture");}});
        }
        try{SwordAttackCoordinator.queueAutomaticMaceStun(null,null);throw new AssertionError("expected fixture failure");}catch(IllegalStateException expected){checks++;}
        scope.close();check(scope.retired().toCompletableFuture().isDone(),"throwing callback still drains");check(ModuleScope.current()==null,"throwing callback clears ownership");
    }
}
