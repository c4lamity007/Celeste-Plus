"""Exercise config favourites against real profile/PNG persistence, fake network/game boundaries.

python check_config_favourites.py --runtime PREPARED_RUNTIME --output NEW_DIR [--stage OVERLAY]
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--runtime', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--stage', type=Path)
p.add_argument('--jdk', type=Path, default=Path('C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'))
p.add_argument('--gson', type=Path)
a = p.parse_args()
if a.stage is None:
    a.stage = Path(__file__).resolve().parents[2]/'runtime-overlay'
a.output.mkdir(parents=True, exist_ok=False)
classes = a.output/'classes'
classes.mkdir()
if a.gson is None:
    jars = sorted((Path.home()/'.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson').glob('*/*/gson-*.jar'))
    if not jars: raise SystemExit('Cached Gson jar missing; pass --gson')
    a.gson = jars[-1]
stubs = {
 'net/fabricmc/loader/api/FabricLoader.java': '''package net.fabricmc.loader.api;
public final class FabricLoader {
 private static final FabricLoader INSTANCE = new FabricLoader();
 public static FabricLoader getInstance() { return INSTANCE; }
 public java.nio.file.Path getConfigDir() { return java.nio.file.Path.of(System.getProperty("celeste.fixture.config")); }
}''',
 'org/slf4j/Logger.java': '''package org.slf4j; public interface Logger {
 default void error(String s, Object... args) { }
 default void warn(String s, Object... args) { }
}''',
 'org/slf4j/LoggerFactory.java': 'package org.slf4j; public final class LoggerFactory {}',
 'dev/c4lam/celeste/compat/RuntimeLogging.java': '''package dev.c4lam.celeste.compat;
public final class RuntimeLogging { public static org.slf4j.Logger getLogger(String name) { return new org.slf4j.Logger() {}; } }''',
 'dev/c4lam/celeste/gui/model/GuiModule.java': 'package dev.c4lam.celeste.gui.model; public record GuiModule(String name) {}',
 'dev/c4lam/celeste/gui/model/GuiCatalog.java': '''package dev.c4lam.celeste.gui.model;
public final class GuiCatalog { public static java.util.List<GuiModule> modules() { return java.util.List.of(new GuiModule("Test")); } }''',
 'dev/c4lam/celeste/module/ModuleManager.java': '''package dev.c4lam.celeste.module;
public final class ModuleManager {
 public static int imports; private static final ModuleManager INSTANCE = new ModuleManager();
 public static ModuleManager getInstance() { return INSTANCE; } public void saveNow() { }
 public java.util.Properties exportState() { return new java.util.Properties(); }
 public void importState(java.util.Properties p) { imports++; }
}''',
 'dev/c4lam/celeste/hud/HudManager.java': '''package dev.c4lam.celeste.hud;
public final class HudManager {
 private static final HudManager INSTANCE = new HudManager();
 public static HudManager getInstance() { return INSTANCE; } public void saveLayout() { }
 public java.util.Properties exportLayout() { return new java.util.Properties(); }
 public void importLayout(java.util.Properties p) { } public void synchronizeModuleBackedElements() { }
}''',
 'dev/c4lam/celeste/config/InterfaceConfig.java': '''package dev.c4lam.celeste.config;
public final class InterfaceConfig {
 public static java.util.Properties exportState() { return new java.util.Properties(); }
 public static void importState(java.util.Properties p) { }
}''',
 'dev/c4lam/celeste/config/CloudConfigClient.java': '''package dev.c4lam.celeste.config;
import java.util.List; import java.util.concurrent.CountDownLatch;
final class CloudConfigClient {
 record RemoteConfig(String id, String name, String author, long updatedAt, int downloads, List<String> modules, boolean owned) { }
 static volatile byte[] image; static volatile List<RemoteConfig> cards;
 static volatile int downloads; static volatile boolean failure, pause;
 static final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
 List<RemoteConfig> list() { return cards; }
 String publish(String name, byte[] image, List<String> modules) { return name; }
 byte[] download(String id) throws Exception {
  downloads++; entered.countDown(); if (pause) release.await();
  if (failure) throw new java.io.IOException("Fixture network unavailable");
  return image;
 }
}''',
}
sources = []
for name, text in stubs.items():
    target = a.output/'stubs'/name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf-8')
    sources.append(target)
production = [a.stage/'src/main/java/dev/c4lam/celeste/config/ConfigLibrary.java']
production += [a.runtime/'src/main/java/dev/c4lam/celeste/config'/name for name in ('ConfigProfileManager.java', 'ConfigImageCodec.java')]
fixture = Path(__file__).with_name('ConfigFavouriteCheck.java')
sources += production + [fixture]
def run(name, exe, args):
    result = subprocess.run([str(exe), *map(str,args)], capture_output=True, text=True)
    (a.output/(name+'.log')).write_text(result.stdout+result.stderr, encoding='utf-8')
    print(result.stdout+result.stderr, end='')
    if result.returncode: raise SystemExit(result.returncode)
run('compile', a.jdk/'bin/javac.exe', ['--release','21','-encoding','UTF-8','-proc:none','-classpath',a.gson,'-d',classes,*sources])
run('check', a.jdk/'bin/java.exe', ['-Djava.awt.headless=true','-classpath',str(classes)+';'+str(a.gson),'dev.c4lam.celeste.config.ConfigFavouriteCheck',a.output/'config'])
(a.output/'result.json').write_text(json.dumps({'result':'pass','runtime':'fixture-only','sources':{str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in production+[fixture]}},indent=2)+'\n')
