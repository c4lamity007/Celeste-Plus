"""Executable synthetic hand-render integration; does not launch Minecraft or replace game files."""
from pathlib import Path
import argparse
import subprocess
import json
import hashlib


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--source', required=True, type=Path)
    ap.add_argument('--plus', required=True, type=Path)
    ap.add_argument('--stage', required=True, type=Path)
    ap.add_argument('--out', required=True, type=Path)
    ap.add_argument('--java-home', required=True, type=Path)
    ap.add_argument('--gradle-cache', type=Path, default=Path.home() / '.gradle/caches')
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    stubs = args.out / 'stubs'
    classes = args.out / 'classes'
    classes.mkdir(exist_ok=True)
    sources = []

    def write(name, body):
        p = stubs / (name.replace('.', '/') + '.java')
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text('package ' + name.rsplit('.', 1)[0] + ';\n' + body, encoding='utf-8')
        return p

    definitions = {
        'net.minecraft.util.Arm': 'public enum Arm {LEFT,RIGHT; public Arm getOpposite(){return this==RIGHT?LEFT:RIGHT;}}',
        'net.minecraft.util.Hand': 'public enum Hand {MAIN_HAND,OFF_HAND}',
        'net.minecraft.util.SwingAnimationType': 'public enum SwingAnimationType {NONE,WHACK,STAB}',
        'net.minecraft.component.type.SwingAnimationComponent': 'public record SwingAnimationComponent(net.minecraft.util.SwingAnimationType type) {}',
        'net.minecraft.item.Item': 'public class Item {}',
        'net.minecraft.item.Items': 'public final class Items {public static final Item CROSSBOW=new Item();}',
        'net.minecraft.item.ItemDisplayContext': 'public enum ItemDisplayContext {FIRST_PERSON_RIGHT_HAND,FIRST_PERSON_LEFT_HAND,THIRD_PERSON_RIGHT_HAND,GUI}',
        'net.minecraft.item.ItemStack': '''public class ItemStack {
            public static final ItemStack EMPTY=new ItemStack(true); public boolean empty,map,sword=true; public Item item=new Item();
            public net.minecraft.util.SwingAnimationType swing=net.minecraft.util.SwingAnimationType.WHACK;
            public ItemStack(){this(false);}public ItemStack(boolean empty){this.empty=empty;}
            public boolean isEmpty(){return empty;}public boolean contains(Object key){return map;}
            public boolean isOf(Item other){return item==other;}public boolean isIn(Object tag){return sword;}
            public net.minecraft.component.type.SwingAnimationComponent getSwingAnimation(){return new net.minecraft.component.type.SwingAnimationComponent(swing);}
        }''',
        'net.minecraft.component.DataComponentTypes': 'public final class DataComponentTypes {public static final Object MAP_ID=new Object();}',
        'net.minecraft.registry.tag.ItemTags': 'public final class ItemTags {public static final Object SWORDS=new Object();}',
        'net.minecraft.entity.LivingEntity': 'public class LivingEntity {}',
        'net.minecraft.entity.ItemEntity': 'public class ItemEntity {}',
        'net.minecraft.client.network.AbstractClientPlayerEntity': '''public class AbstractClientPlayerEntity extends net.minecraft.entity.LivingEntity {
            public net.minecraft.util.Hand preferredHand=net.minecraft.util.Hand.MAIN_HAND;
            public net.minecraft.util.Arm mainArm=net.minecraft.util.Arm.RIGHT;
            public boolean using,spyglass,riptide; public float progress,lastTickProgress;
            public net.minecraft.item.ItemStack inventoryMain=new net.minecraft.item.ItemStack(),inventoryOff=new net.minecraft.item.ItemStack();
            public net.minecraft.util.Arm getMainArm(){return mainArm;}public boolean isUsingItem(){return using;}
            public boolean isUsingSpyglass(){return spyglass;}public boolean isUsingRiptide(){return riptide;}
            public float getHandSwingProgress(float tick){lastTickProgress=tick;return progress;}
            public net.minecraft.item.ItemStack getStackInHand(net.minecraft.util.Hand hand){return hand==net.minecraft.util.Hand.MAIN_HAND?inventoryMain:inventoryOff;}
        }''',
        'net.minecraft.client.network.ClientPlayerEntity': 'public class ClientPlayerEntity extends AbstractClientPlayerEntity {}',
        'net.minecraft.client.util.Window': 'public class Window {public int getFramebufferWidth(){return 1920;}public int getFramebufferHeight(){return 1080;}}',
        'net.minecraft.client.render.Camera': 'public class Camera {public net.minecraft.block.enums.CameraSubmersionType getSubmersionType(){return net.minecraft.block.enums.CameraSubmersionType.NONE;}}',
        'net.minecraft.block.enums.CameraSubmersionType': 'public enum CameraSubmersionType {NONE,WATER,LAVA}',
        'net.minecraft.client.render.GameRenderer': 'public class GameRenderer {public Camera getCamera(){return new Camera();}}',
        'net.minecraft.client.option.GameOptions': 'public class GameOptions {public Value getFovEffectScale(){return new Value();}public static class Value{public Double getValue(){return 1.0;}}}',
        'net.minecraft.client.render.RenderTickCounter': 'public class RenderTickCounter {public float getTickProgress(boolean ignoreFreeze){return ignoreFreeze?.375F:.875F;}}',
        'net.minecraft.client.MinecraftClient': '''public class MinecraftClient {
            private static final MinecraftClient INSTANCE=new MinecraftClient();
            public net.minecraft.client.network.ClientPlayerEntity player=new net.minecraft.client.network.ClientPlayerEntity();
            public Object world=new Object(),interactionManager=new Object(),currentScreen;
            public net.minecraft.client.render.GameRenderer gameRenderer=new net.minecraft.client.render.GameRenderer();
            public net.minecraft.client.option.GameOptions options=new net.minecraft.client.option.GameOptions();
            public static MinecraftClient getInstance(){return INSTANCE;}public boolean isOnThread(){return true;}
            public net.minecraft.client.util.Window getWindow(){return new net.minecraft.client.util.Window();}
            public net.minecraft.client.render.RenderTickCounter getRenderTickCounter(){return new net.minecraft.client.render.RenderTickCounter();}
        }''',
        'net.minecraft.util.math.MathHelper': 'public final class MathHelper {public static float sin(double x){return (float)Math.sin(x);}public static float sqrt(float x){return (float)Math.sqrt(x);}public static float lerp(float t,float a,float b){return a+t*(b-a);}}',
        'dev.c4lam.celeste.gui.HandModelsEditorScreen': 'public class HandModelsEditorScreen {}',
        'dev.c4lam.celeste.gui.model.CelestePage': 'public enum CelestePage {VISUALS;public boolean feature(){return true;}}',
        'dev.c4lam.celeste.hud.DynamicIslandEvents': 'public class DynamicIslandEvents {public static void publishModuleToggle(String n,boolean e){}}',
        'dev.c4lam.celeste.hud.ModuleToggleSoundQueue': 'public class ModuleToggleSoundQueue {public static ModuleToggleSoundQueue getInstance(){return new ModuleToggleSoundQueue();}public void publish(boolean e){}}',
        'dev.c4lam.celeste.CelesteClient': 'public final class CelesteClient {public static boolean authorized=true;public static boolean active(){return authorized;}}',
        'dev.c4lam.celeste.compat.SkyboxHooks': 'public class SkyboxHooks {public static void extract(Object a,Object b,Object c,Object d){}public static boolean draw(Object a){return false;}public static boolean ownsSky(Object a){return false;}public static void clear(){}}',
        'dev.c4lam.celeste.compat.PresentationHooks': 'public class PresentationHooks {public static void flushGui(){}public static boolean hideHud(Object a,Object b,Object c){return false;}public static boolean handleChatMediaClick(Object a){return false;}public static void applyServerRotation(Object a,Object b){}public static boolean replacePlayerModel(Object a){return false;}}',
        'dev.c4lam.celeste.module.impl.visual.expansion.SwingSpeedFeature': 'public class SwingSpeedFeature {public static int durationFor(Object a,int b){return b;}}',
        'dev.c4lam.celeste.module.impl.visual.expansion.HandShadersRenderer': 'public class HandShadersRenderer {public static dev.celestebeta.host.VisualHookBridge.HandPass begin(){return null;}public static void releaseResources(){}}',
        'dev.c4lam.celeste.module.impl.visual.expansion.ItemEspFeature': 'public class ItemEspFeature {public static void applyModelHighlight(Object a,Object b){}}',
        'dev.c4lam.celeste.module.impl.visual.expansion.HandModelsFacing': '''public class HandModelsFacing {
            public static void clear(){}
            public static org.joml.Quaternionfc correction(net.minecraft.item.ItemStack item,net.minecraft.item.ItemDisplayContext context,net.minecraft.client.network.AbstractClientPlayerEntity p){
                return item.sword ? new org.joml.Quaternionf().rotationY((float)(Math.PI*.5)) : null;
            }
        }''',
        'net.minecraft.client.render.item.HeldItemRenderer': '''public class HeldItemRenderer {
            public org.joml.Matrix4f submitted;public org.joml.Matrix3f normal;public org.joml.Vector3f normalX,normalZ;public int submissions;
            public void renderItem(float delta,net.minecraft.client.util.math.MatrixStack m,net.minecraft.client.render.command.OrderedRenderCommandQueue q,net.minecraft.client.network.ClientPlayerEntity p,int light) {}
            public void renderFirstPersonItem(net.minecraft.client.network.AbstractClientPlayerEntity p,float delta,float pitch,net.minecraft.util.Hand hand,float swing,net.minecraft.item.ItemStack item,float equip,net.minecraft.client.util.math.MatrixStack m,net.minecraft.client.render.command.OrderedRenderCommandQueue q,int light) {
                net.minecraft.util.Arm arm=hand==net.minecraft.util.Hand.MAIN_HAND?p.getMainArm():p.getMainArm().getOpposite();
                int side=arm==net.minecraft.util.Arm.RIGHT?1:-1;
                m.push();
                applyEquipOffset(m,arm,equip);
                if(p.isUsingItem())m.multiply(new org.joml.Quaternionf().rotationX(-.9F));
                else swingArm(swing,m,side,arm);
                renderItem(p,item,side==1?net.minecraft.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND:net.minecraft.item.ItemDisplayContext.FIRST_PERSON_LEFT_HAND,m,q,light);
                m.pop();
            }
            public void renderItem(net.minecraft.entity.LivingEntity p,net.minecraft.item.ItemStack item,net.minecraft.item.ItemDisplayContext context,net.minecraft.client.util.math.MatrixStack m,net.minecraft.client.render.command.OrderedRenderCommandQueue q,int light) {
                submitted=new org.joml.Matrix4f(m.peek().getPositionMatrix());normal=new org.joml.Matrix3f(m.peek().getNormalMatrix());submissions++;
                normalX=m.peek().transformNormal(1,0,0,new org.joml.Vector3f());normalZ=m.peek().transformNormal(0,0,1,new org.joml.Vector3f());
            }
            private void applyEquipOffset(net.minecraft.client.util.math.MatrixStack m,net.minecraft.util.Arm a,float f){}
            private void applySwingOffset(net.minecraft.client.util.math.MatrixStack m,net.minecraft.util.Arm a,float f){}
            private void swingArm(float f,net.minecraft.client.util.math.MatrixStack m,int i,net.minecraft.util.Arm a){}
        }''',
    }
    for name, body in definitions.items():
        sources.append(write(name, body))
    package = 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/'
    production = [package+n+'.java' for n in ['SwingAnimationFeature', 'SwingAnimationPose', 'HandModelsFeature', 'HandModelsPose', 'HandModelsLayout']]
    production += ['src/main/java/dev/c4lam/celeste/'+p+'.java' for p in ['beta/visual/BetaVisualHooks', 'module/ModuleFeature', 'module/ModuleContext', 'gui/model/GuiModule', 'gui/model/GuiSetting']]
    production += ['src/main/java/dev/celestebeta/host/VisualHookBridge.java']
    actual = []
    for rel in production:
        staged = args.stage/'runtime-overlay'/rel
        selected = staged if staged.is_file() else args.source/rel
        actual.append(selected)
        sources.append(selected)
    sources.append(args.plus/'beta-lab/visual-hooks/src/main/java/dev/celestebeta/host/instrument/VisualHooksTransformer.java')
    sources.append(args.plus/'beta-lab/combined-hooks/src/main/java/dev/celestebeta/host/instrument/HookArgumentFrames.java')
    sources.append(Path(__file__).with_name('SwingAnimationIntegrationCheck.java'))
    mc = next((args.gradle_cache/'fabric-loom/minecraftMaven/net/minecraft/minecraft-merged').rglob('minecraft-merged-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'))
    libs = [mc, next((args.gradle_cache/'modules-2/files-2.1/org.joml/joml/1.10.8').rglob('*.jar'))]
    libs += [next((args.gradle_cache/'modules-2/files-2.1/org.ow2.asm'/a/'9.9').rglob('*.jar')) for a in ['asm', 'asm-tree', 'asm-analysis']]
    cp = ';'.join(str(x) for x in libs)
    arguments = args.out/'javac.args'
    arguments.write_text('\n'.join('"'+str(x).replace('\\', '/')+'"' for x in ['--release', '21', '-cp', cp, '-d', classes, *sources]), encoding='utf-8')
    compile_run = subprocess.run([str(args.java_home/'bin/javac.exe'), '@'+str(arguments)], capture_output=True, text=True)
    (args.out/'compile.log').write_text(compile_run.stdout+compile_run.stderr, encoding='utf-8')
    if compile_run.returncode:
        print(compile_run.stderr[-10000:])
        raise SystemExit(compile_run.returncode)
    run = subprocess.run([str(args.java_home/'bin/java.exe'), '-cp', str(classes)+';'+cp, 'dev.c4lam.celeste.module.impl.visual.expansion.SwingAnimationIntegrationCheck', str(mc), str(classes)], capture_output=True, text=True)
    (args.out/'run.log').write_text(run.stdout+run.stderr, encoding='utf-8')
    print(run.stdout+run.stderr)
    result = {'kind': 'synthetic JVM integration, not in-game verification', 'passed': run.returncode == 0,
              'production_sources': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in actual}, 'minecraft': str(mc),
              'real_path': ['VisualHooksTransformer', 'VisualHookBridge', 'BetaVisualHooks', 'ModuleContext/GuiSetting', 'HandModelsFeature', 'SwingAnimationFeature', 'MatrixStack'],
              'synthetic_environment': ['Minecraft client/player/item state', 'unrelated visual hooks', 'item-model facing selection'],
              'vanilla_helpers': 'Original 1.21.11 applyEquipOffset, applySwingOffset, swingArm bytecode'}
    (args.out/'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    raise SystemExit(run.returncode)


if __name__ == '__main__':
    main()
