"""Verify precise cache retirement with real game types in isolated maps; never opens a GL context."""
from pathlib import Path
import argparse
import json
import subprocess

ROOT = Path(__file__).resolve().parents[2]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    classes = out / 'classes'
    classes.mkdir()
    classpath = args.classpath_file.read_text(encoding='utf-8').strip()
    src = ROOT / 'runtime-overlay/src/main/java/dev/c4lam/celeste/module/delivery'
    sources = [src / name for name in ('ModuleScope.java', 'ModuleResourceAssets.java', 'ModulePipelineResources.java')]
    sources.append(ROOT / 'tools/qa/module-delivery/ModuleGpuOwnershipCheck.java')

    def run(name, binary, arguments):
        argfile = out / (name + '.args')
        argfile.write_text('\n'.join('"' + str(value).replace('\\', '/') + '"' for value in arguments), encoding='utf-8')
        result = subprocess.run([str(args.java_home / 'bin' / (binary + '.exe')), '@' + str(argfile)], capture_output=True, text=True)
        (out / (name + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
        if result.returncode:
            print(result.stdout + result.stderr)
            raise SystemExit(result.returncode)
        return result

    run('compile', 'javac', ['--release', '21', '-proc:none', '-encoding', 'UTF-8', '-classpath', classpath, '-d', classes, *sources])
    result = run('test', 'java', ['-classpath', str(classes) + ';' + classpath, 'dev.c4lam.celeste.module.delivery.ModuleGpuOwnershipCheck'])
    report = json.loads(result.stdout)
    report['boundary'] = 'actual Minecraft types, isolated cache maps, injected close counters; no live rendering'
    (out / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report))

if __name__ == '__main__':
    main()
