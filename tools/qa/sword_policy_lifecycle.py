"""Exercise the real resident sword policy broker using bounded Minecraft API fixtures."""
from pathlib import Path
import argparse,json,subprocess
ROOT=Path(__file__).resolve().parents[2]
STUBS={
'dev/c4lam/celeste/hud/HudTargetTracker.java':'''package dev.c4lam.celeste.hud; public class HudTargetTracker { public static HudTargetTracker getInstance(){return new HudTargetTracker();} public void markAttacked(net.minecraft.client.MinecraftClient c,net.minecraft.entity.Entity e){} }''',
'net/minecraft/util/math/Vec3d.java':'''package net.minecraft.util.math; public class Vec3d {}''',
'net/minecraft/util/math/Box.java':'''package net.minecraft.util.math; public class Box { public Vec3d getCenter(){return new Vec3d();} }''',
'net/minecraft/entity/Entity.java':'''package net.minecraft.entity; public class Entity { public boolean isRemoved(){return false;} public net.minecraft.util.math.Box getBoundingBox(){return new net.minecraft.util.math.Box();} }''',
'net/minecraft/entity/LivingEntity.java':'''package net.minecraft.entity; public class LivingEntity extends Entity {}''',
'net/minecraft/item/ItemStack.java':'''package net.minecraft.item; public class ItemStack { public Object get(Object component){return null;} }''',
'net/minecraft/entity/player/PlayerInventory.java':'''package net.minecraft.entity.player; public class PlayerInventory { public int selected; public net.minecraft.item.ItemStack[] items=new net.minecraft.item.ItemStack[9]; public PlayerInventory(){for(int i=0;i<9;i++)items[i]=new net.minecraft.item.ItemStack();} public int getSelectedSlot(){return selected;} public net.minecraft.item.ItemStack getStack(int i){return items[i];} }''',
'net/minecraft/util/Hand.java':'''package net.minecraft.util; public enum Hand { MAIN_HAND, OFF_HAND }''',
'net/minecraft/client/network/ClientPlayerEntity.java':'''package net.minecraft.client.network; public class ClientPlayerEntity { public final net.minecraft.entity.player.PlayerInventory inventory=new net.minecraft.entity.player.PlayerInventory(); public net.minecraft.entity.player.PlayerInventory getInventory(){return inventory;} public void swingHand(net.minecraft.util.Hand hand){} }''',
'net/minecraft/client/MinecraftClient.java':'''package net.minecraft.client; public class MinecraftClient { public static MinecraftClient instance; public net.minecraft.client.network.ClientPlayerEntity player=new net.minecraft.client.network.ClientPlayerEntity(); public Object world=new Object(),currentScreen,crosshairTarget; public Manager interactionManager=new Manager(); public MinecraftClient(){instance=this;} public static MinecraftClient getInstance(){return instance;} public static class Manager { public void attackEntity(net.minecraft.client.network.ClientPlayerEntity player, net.minecraft.entity.Entity target){} } }''',
'net/minecraft/component/DataComponentTypes.java':'''package net.minecraft.component; public class DataComponentTypes { public static final Object PIERCING_WEAPON=new Object(); }''',
'net/minecraft/util/hit/EntityHitResult.java':'''package net.minecraft.util.hit; public class EntityHitResult { public EntityHitResult(net.minecraft.entity.Entity entity,net.minecraft.util.math.Vec3d point){} }''',
'dev/c4lam/celeste/silent/SilentSlotManager.java':'''package dev.c4lam.celeste.silent; public class SilentSlotManager { private static final SilentSlotManager INSTANCE=new SilentSlotManager(); public int slot,restore=-1,released; public static SilentSlotManager getInstance(){return INSTANCE;} public boolean acquire(Object owner,int target,int priority,int ticks){slot=target;return true;} public int beginLocalProjection(net.minecraft.entity.player.PlayerInventory i){i.selected=slot;return 0;} public boolean selectVisibleSlot(net.minecraft.client.MinecraftClient c,int target){c.player.inventory.selected=target;slot=target;return true;} public int requestedSlot(){return slot;} public void endLocalProjection(net.minecraft.entity.player.PlayerInventory i,int token){} public void releaseAfterAction(Object owner){released++;} public void restoreVisibleNextTick(net.minecraft.client.network.ClientPlayerEntity player,int previous,int selected){restore=previous;} }'''
}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--java-home',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);a=parser.parse_args()
    output=a.output.resolve();output.mkdir(parents=True,exist_ok=True);source=output/'stubs';classes=output/'classes';classes.mkdir(exist_ok=True)
    files=[]
    for name,content in STUBS.items():
        path=source/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');files.append(path)
    files += [ROOT/'runtime-overlay/src/main/java/dev/c4lam/celeste/module/delivery/ModuleScope.java',ROOT/'runtime-overlay/src/main/java/dev/c4lam/celeste/action/SwordAttackCoordinator.java',ROOT/'tools/qa/module-delivery/SwordPolicyLifecycleCheck.java']
    compile=subprocess.run([str(a.java_home/'bin/javac.exe'),'--release','21','-proc:none','-encoding','UTF-8','-d',str(classes),*map(str,files)],capture_output=True,text=True)
    (output/'compile.log').write_text(compile.stdout+compile.stderr,encoding='utf-8')
    if compile.returncode:print(compile.stderr[-5000:]);raise SystemExit(compile.returncode)
    result=subprocess.run([str(a.java_home/'bin/java.exe'),'-ea','-cp',str(classes),'fixture.SwordPolicyLifecycleCheck'],capture_output=True,text=True)
    (output/'behavior.log').write_text(result.stdout+result.stderr,encoding='utf-8')
    if result.returncode:print(result.stderr[-5000:]);raise SystemExit(result.returncode)
    report=json.loads(result.stdout);report['game_verification']='not-run';report['boundary']='real policy broker and scope; Minecraft API fixtures'
    (output/'result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report))
if __name__=='__main__':main()
