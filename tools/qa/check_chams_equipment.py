"""Compile chams code and exercise real cached Minecraft geometry; no Gradle/game/network.

python check_chams_equipment.py --runtime PATH --output NEW_PATH [--stage PATH]
Requires an already-built named Minecraft 1.21.11 runtime and local Gradle dependencies.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--runtime', required=True, type=Path)
default_stage = Path(__file__).resolve().parents[2]
if (default_stage/'runtime-overlay').is_dir():
    default_stage /= 'runtime-overlay'
p.add_argument('--stage', type=Path, default=default_stage)
p.add_argument('--output', required=True, type=Path)
p.add_argument('--jdk', type=Path, default=Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'))
p.add_argument('--cache', type=Path, default=Path.home()/'.gradle/caches/modules-2/files-2.1')
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=False)
classes = a.output/'classes'
classes.mkdir()
mc = list((a.runtime/'.gradle/loom-cache/minecraftMaven/net/minecraft').rglob('minecraft-merged-*.jar'))
mc = [x for x in mc if not x.name.endswith('-sources.jar')]
if len(mc) != 1:
    raise SystemExit(f'Expected exactly one named Minecraft include jar, found {len(mc)}')
dependency_jars = sorted(x for x in a.cache.rglob('*.jar') if not x.name.endswith(('-sources.jar', '-javadoc.jar')))
mc_info = a.cache.parents[1]/'fabric-loom/1.21.11/mojang_minecraft_info.json'
exact_dependencies = []
for library in json.loads(mc_info.read_text(encoding='utf-8'))['libraries']:
    group, name, version, *classifier = library['name'].split(':')
    if classifier and classifier != ['natives-windows']:
        continue
    filename = '-'.join([name, version, *classifier])+'.jar'
    exact_dependencies.extend((a.cache/group/name/version).glob('*/'+filename))
remapped = sorted(x for x in (a.runtime/'.gradle/loom-cache/remapped_mods').rglob('*.jar') if not x.name.endswith('-sources.jar'))
cp = ';'.join(str(x.resolve()) for x in [*mc, a.runtime/'build/classes/java/main', *remapped, *exact_dependencies, *dependency_jars])
prefix = a.stage/'src/main/java/dev/c4lam/celeste'
sources = [prefix/'compat/ChamsEquipmentAccess.java', prefix/'gui/model/GuiCatalog.java']
sources += [prefix/'module/impl/visual/expansion'/name for name in (
    'ChamsFeature.java', 'EquipmentCaptureQueue.java', 'PlayerEchoRenderer.java',
    'PlayerModelSnapshot.java', 'PlayerPoseTransforms.java', 'UnifiedChamsRenderer.java')]
sources.append(Path(__file__).with_name('ChamsEquipmentCheck.java'))
def quoted(value):
    return '"' + str(value).replace('\\', '/').replace('"', '\\"') + '"'
def run(name, executable, arguments):
    argfile = a.output/(name+'.args')
    argfile.write_text('\n'.join(quoted(x) for x in arguments), encoding='utf-8')
    result = subprocess.run([str(executable), '@'+str(argfile.resolve())], capture_output=True, text=True)
    (a.output/(name+'.log')).write_text(result.stdout+result.stderr, encoding='utf-8')
    print(result.stdout + result.stderr, end='')
    if result.returncode:
        raise SystemExit(result.returncode)
run('compile', a.jdk/'bin/javac.exe', ['--release', '21', '-encoding', 'UTF-8', '-proc:none', '-classpath', cp, '-d', classes, *sources])
run('geometry', a.jdk/'bin/java.exe', ['-Djava.awt.headless=true', '-classpath', str(classes.resolve())+';'+cp, 'dev.c4lam.celeste.module.impl.visual.expansion.ChamsEquipmentCheck'])
(a.output/'result.json').write_text(json.dumps({'result':'pass', 'runtime':'fixture-only', 'sources':{str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in sources}}, indent=2)+'\n')
print(f'PASS; evidence: {a.output.resolve()}')
