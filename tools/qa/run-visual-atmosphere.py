"""Focused source-level GUI/lifecycle checks with explicit game/GPU boundary stubs."""
import argparse
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--project-root', type=Path, required=True)
parser.add_argument('--overlay-root', type=Path)
parser.add_argument('--output-root', type=Path, required=True)
parser.add_argument('--jdk', type=Path, default=Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'))
args = parser.parse_args()
stubroot = args.output_root / 'stubs'
classes = args.output_root / 'classes'
classes.mkdir(parents=True, exist_ok=True)
stubs = {
    'net/minecraft/client/MinecraftClient.java': 'public class MinecraftClient { public net.minecraft.client.world.ClientWorld world; public Object player, interactionManager; }',
    'net/minecraft/client/world/ClientWorld.java': 'public class ClientWorld {}',
    'net/minecraft/util/Identifier.java': 'public record Identifier(String name) { public static Identifier of(String value) { return new Identifier(value); } }',
    'org/lwjgl/glfw/GLFW.java': 'public class GLFW { public static final int GLFW_KEY_UNKNOWN=-1,GLFW_KEY_N=78,GLFW_KEY_G=71,GLFW_KEY_V=86,GLFW_KEY_B=66; }',
    'dev/c4lam/celeste/hud/DynamicIslandEvents.java': 'public class DynamicIslandEvents { public static void publishModuleToggle(String name,boolean enabled) {} }',
    'dev/c4lam/celeste/hud/ModuleToggleSoundQueue.java': 'public class ModuleToggleSoundQueue { public static ModuleToggleSoundQueue getInstance(){return new ModuleToggleSoundQueue();} public void publish(boolean enabled) {} }',
    'dev/c4lam/celeste/module/impl/visual/BlockEspSettings.java': 'public class BlockEspSettings { public static dev.c4lam.celeste.gui.model.GuiModule storage(){ return new dev.c4lam.celeste.gui.model.GuiModule("Storage ESP","stub",dev.c4lam.celeste.gui.model.CelestePage.VISUALS,false); } public static dev.c4lam.celeste.gui.model.GuiModule blocks(){ return new dev.c4lam.celeste.gui.model.GuiModule("Block ESP","stub",dev.c4lam.celeste.gui.model.CelestePage.VISUALS,false); } }',
    'dev/c4lam/celeste/gui/HandModelsEditorScreen.java': 'public class HandModelsEditorScreen { public static void open() {} }',
    'dev/c4lam/celeste/gui/AmbientEffectsEditorScreen.java': 'public class AmbientEffectsEditorScreen { public static void open() {} }',
    'dev/c4lam/celeste/gui/ModelImportScreen.java': 'public class ModelImportScreen { public static void open() {} }',
    'dev/c4lam/celeste/render/model/BundledPlayerModel.java': 'public class BundledPlayerModel { public static boolean available() { return false; } }',
    'dev/c4lam/celeste/module/impl/visual/PlayerModelsFeature.java': 'public class PlayerModelsFeature { public static void loadSahur() {} }',
    'dev/c4lam/celeste/module/impl/visual/expansion/HandShadersRenderer.java': 'public class HandShadersRenderer { public static void resetFailure(){} public static void observeEnvironment(){} public static void releaseResources(){} }',
    'dev/c4lam/celeste/module/impl/visual/expansion/ShaderFogRenderer.java': 'public class ShaderFogRenderer { public static int resets; public static void reset(){ resets++; } }',
}
sources = []
for relative, body in stubs.items():
    path = stubroot / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    package = relative.rsplit('/', 1)[0].replace('/', '.')
    path.write_text(f'package {package};\n{body}\n', encoding='utf-8')
    sources.append(path)
relative_sources = [
    'gui/model/GuiCatalog.java', 'gui/model/GuiSetting.java', 'gui/model/GuiModule.java', 'gui/model/CelestePage.java',
    'gui/model/AmbientEffectsSettings.java',
    'module/ModuleContext.java', 'module/ModuleFeature.java',
    *('module/impl/visual/expansion/' + name + '.java' for name in ['VisualAtmosphere', 'AtmosphereFeature', 'CustomFogFeature', 'CustomFogMath', 'HandShadersFeature']),
]
for relative in relative_sources:
    relative = 'src/main/java/dev/c4lam/celeste/' + relative
    staged = args.overlay_root / relative if args.overlay_root else None
    sources.append(staged if staged and staged.is_file() else args.project_root / relative)
sources.append(Path(__file__).with_name('VisualAtmosphereCheck.java'))
for label, command in [
    ('compile', [args.jdk / 'bin/javac.exe', '--release', '21', '-proc:none', '-d', classes, *sources]),
    ('check', [args.jdk / 'bin/java.exe', '-cp', classes, 'dev.c4lam.celeste.module.impl.visual.expansion.VisualAtmosphereCheck', args.output_root / 'palette-values.csv']),
]:
    result = subprocess.run([str(v) for v in command], capture_output=True, text=True)
    (args.output_root / f'{label}.log').write_text(result.stdout + result.stderr, encoding='utf-8')
    if result.returncode:
        raise SystemExit(result.stdout + result.stderr)
    print(result.stdout, end='')
