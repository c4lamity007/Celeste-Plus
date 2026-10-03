"""Verify actual scoped asset ownership and retirement without launching Minecraft."""
from pathlib import Path
import argparse
import json
import os
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    classes = output / 'classes'
    classes.mkdir()
    java = args.java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    javac = args.java_home / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')
    source = ROOT / 'runtime-overlay/src/main/java/dev/c4lam/celeste'
    inputs = [source / 'module/delivery/ModuleScope.java', source / 'module/delivery/ModuleResourceAssets.java',
              ROOT / 'tools/qa/module-delivery/ModuleResourceAssetsCheck.java']
    environment = {name: value for name, value in os.environ.items()
                   if name not in {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH'}}
    commands = [('compile', [javac, '--release', '21', '-encoding', 'UTF-8', '-d', classes, *inputs]),
                ('test', [java, '-ea', '-Xverify:all', '-cp', classes, 'ModuleResourceAssetsCheck'])]
    for name, command in commands:
        result = subprocess.run(list(map(str, command)), env=environment, capture_output=True, text=True,
                                encoding='utf-8', timeout=60)
        (output / (name + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
        if result.returncode:
            raise SystemExit(result.returncode)
    report = {'status': 'pass', 'cases': 12, 'game_verification': 'not-run',
              'boundary': 'Actual resident ModuleScope and ModuleResourceAssets; no game stubs or credentials',
              'result': result.stdout.strip()}
    (output / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
