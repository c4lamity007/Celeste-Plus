#!/usr/bin/env python3
"""Check actual compiled Suspicious Chunk Finder history without starting Minecraft."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import sys


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def quoted(value):
    return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"') + '"'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    classpath_file = args.classpath_file.resolve(strict=True)
    fixture = Path(__file__).with_name('SuspiciousFeatureCheck.java').resolve(strict=True)
    classes = (runtime / 'build/classes/java/main').resolve(strict=True)
    names = ['SuspiciousActivity', 'SuspiciousAnalysis', 'SuspiciousGeometry', 'SuspiciousSnapshots']
    relative = 'dev/c4lam/celeste/module/impl/visual'
    source_names = [relative + '/SuspiciousChunkFinderFeature.java']
    source_names += [relative + '/suspicious/' + name + '.java' for name in names]
    sources = [runtime / 'src/main/java' / name for name in source_names]
    class_files = list((classes / relative).glob('SuspiciousChunkFinderFeature*.class'))
    for name in names:
        class_files.extend((classes / relative / 'suspicious').glob(name + '*.class'))
    for name in source_names:
        if not (classes / name.replace('.java', '.class')).is_file():
            raise ValueError('Runtime is missing a compiled production class: ' + name)
    jars = sorted((runtime / 'build/libs').glob('*.jar'))
    jars = [path for path in jars if not path.name.endswith('-sources.jar')]
    if len(jars) != 1:
        raise ValueError('Expected one built runtime JAR')
    tracked = sources + class_files + [fixture, classpath_file, jars[0]]
    before = {str(path): digest(path) for path in tracked}
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    compiled_fixture = out / 'classes'
    compiled_fixture.mkdir()
    # Production output comes before dependencies. The fixture verifies the
    # code-source directory of all five production classes at runtime too.
    classpath = os.pathsep.join([str(classes), str(runtime / 'build/resources/main'),
                                classpath_file.read_text(encoding='utf-8').strip()])
    suffix = '.exe' if sys.platform == 'win32' else ''
    java_bin = args.java_home / 'bin'
    steps = [('compile', java_bin / ('javac' + suffix), ['--release', '21', '-proc:none', '-encoding', 'UTF-8',
             '-classpath', classpath, '-d', compiled_fixture, fixture]),
             ('SuspiciousFeatureCheck', java_bin / ('java' + suffix), ['-ea', '-cp',
             str(compiled_fixture) + os.pathsep + classpath,
             'dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousFeatureCheck', classes])]
    report = {'scope': 'actual compiled Feature and analysis classes; reflection history fixtures',
              'in_game_verified': False, 'minecraft_or_auth_started': False,
              'runtime': str(runtime), 'inputs': before, 'steps': [], 'passed': True}
    for name, executable, tokens in steps:
        argument_file = out / (name + '.args')
        argument_file.write_text('\n'.join(quoted(token) for token in tokens) + '\n', encoding='utf-8')
        result = subprocess.run([str(executable), '@' + str(argument_file)], capture_output=True,
                                encoding='utf-8', errors='replace', timeout=180)
        output = result.stdout + result.stderr
        (out / (name + '.log')).write_text(output, encoding='utf-8')
        report['steps'].append({'name': name, 'exit_code': result.returncode,
                                'summary': output.splitlines()[-4:]})
        if result.returncode:
            report['passed'] = False
            break
    report['inputs_unchanged'] = before == {str(path): digest(path) for path in tracked}
    report['passed'] &= report['inputs_unchanged']
    (out / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': report['passed'], 'steps': report['steps'], 'report': str(out / 'result.json')}))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
