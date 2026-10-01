#!/usr/bin/env python3
"""Exercise production Suspicious Chunk Finder analysis, path cells and activity estimation."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    project = args.project.resolve(strict=True)
    root = project / 'runtime-overlay/src/main/java/dev/c4lam/celeste/module/impl/visual/suspicious'
    checks = ['SuspiciousChunkFinderCheck', 'SuspiciousGeometryOutlineCheck', 'SuspiciousActivityCheck']
    sources = [root / (name + '.java') for name in
               ['SuspiciousAnalysis', 'SuspiciousGeometry', 'SuspiciousSnapshots', 'SuspiciousActivity']]
    sources += [Path(__file__).with_name(name + '.java') for name in checks]
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=False)
    copied = output / 'source'
    copied.mkdir()
    classes = output / 'classes'
    classes.mkdir()
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    hashes = {str(path): digest(path) for path in sources}
    for path in sources:
        (copied / path.name).write_bytes(path.read_bytes())
    suffix = '.exe' if sys.platform == 'win32' else ''
    bin_path = args.java_home / 'bin'
    commands = [('compile', [str(bin_path / ('javac' + suffix)), '--release', '21', '-proc:none',
                            '-encoding', 'UTF-8', '-d', str(classes), *map(str, copied.glob('*.java'))])]
    commands += [(name, [str(bin_path / ('java' + suffix)), '-ea', '-cp', str(classes),
                         'dev.c4lam.celeste.module.impl.visual.suspicious.' + name]) for name in checks]
    report = {'scope': 'actual production Java sources with deterministic fixtures',
              'in_game_verified': False, 'sources': hashes, 'steps': [], 'passed': True}
    for name, command in commands:
        result = subprocess.run(command, capture_output=True, encoding='utf-8', errors='replace', timeout=180)
        text = result.stdout + result.stderr
        (output / (name + '.log')).write_text(text, encoding='utf-8')
        report['steps'].append({'name': name, 'exit_code': result.returncode, 'summary': text.splitlines()[-4:]})
        if result.returncode:
            report['passed'] = False
            break
    report['sources_unchanged'] = hashes == {str(path): digest(path) for path in sources}
    report['passed'] &= report['sources_unchanged']
    (output / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': report['passed'], 'steps': report['steps'], 'report': str(output / 'result.json')}))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
