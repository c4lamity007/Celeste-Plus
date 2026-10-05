"""Offline native-resource bounds/lifetime tests; never loads a DLL or uses a game."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        raise ValueError('Use a fresh output directory')
    sources = [ROOT / name for name in (
        'tools/qa/NativeResourceBudgetCheck.java',
        'beta-lab/memory-runtime/src/main/java/dev/c4lam/celeste/beta/memory/NativeResourceBudget.java',
        'beta-lab/memory-runtime/src/main/java/dev/c4lam/celeste/beta/memory/TwoPartMemoryClassLoader.java',
        'beta-lab/bootstrap-b/fixtures/core/dev/c4lam/celeste/auth/ArtifactIntegrity.java',
        'beta-lab/bootstrap-b/fixtures/core/dev/c4lam/celeste/auth/Crypto.java',
        'beta-lab/integrity-adapter/src/test/java/dev/c4lam/celeste/CelesteClient.java',
        'beta-lab/memory-runtime/src/test/java/dev/c4lam/celeste/beta/memory/TwoPartMemoryClassLoaderTest.java',
        'beta-lab/integrity-adapter/src/test/java/dev/c4lam/celeste/auth/ArtifactIntegrityTest.java')]
    integrity = ROOT / 'runtime-overlay/src/main/java/dev/c4lam/celeste/auth/ArtifactIntegrity.java'
    helper = ROOT / 'runtime-overlay/src/main/java/dev/c4lam/celeste/beta/memory/NativeResourceBudget.java'
    fixture_helper = ROOT / 'beta-lab/bootstrap-b/fixtures/core/dev/c4lam/celeste/beta/memory/NativeResourceBudget.java'
    if integrity.read_bytes() != sources[3].read_bytes() or not (helper.read_bytes() == sources[1].read_bytes() == fixture_helper.read_bytes()):
        raise ValueError('Actual overlay, host and standalone fixture sources must be identical')
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    pins = {str(path.relative_to(ROOT)): sha(path) for path in [*sources, integrity, helper, fixture_helper]}
    output.mkdir(parents=True)
    classes = output / 'classes'
    classes.mkdir()
    java = args.java_home / 'bin/java.exe'
    javac = args.java_home / 'bin/javac.exe'
    records = []
    def run(name, command, marker=None):
        started = time.monotonic()
        with (output / (name + '.log')).open('xb') as stream:
            result = subprocess.run(list(map(str, command)), stdin=subprocess.DEVNULL, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=90)
        text = (output / (name + '.log')).read_text(encoding='utf-8', errors='replace')
        if result.returncode or marker is not None and marker not in text:
            raise RuntimeError('Failed ' + name + '; inspect the bounded local log')
        records.append({'name': name, 'exit_code': result.returncode, 'log_sha256': sha(output / (name + '.log')),
                        'seconds': round(time.monotonic() - started, 3)})
        print('PASS ' + name)
    run('compile', [javac, '--release', '21', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', classes, *sources])
    prefix = [java, '-ea', '-Xverify:all', '-Xmx384m', '-cp', classes]
    run('native-budget', [*prefix, 'dev.c4lam.celeste.auth.NativeResourceBudgetCheck'], 'PASS native resource budget:')
    run('memory-loader', [*prefix, 'dev.c4lam.celeste.beta.memory.TwoPartMemoryClassLoaderTest'], '16 focused memory-runtime checks')
    run('retained-integrity', [*prefix, 'dev.c4lam.celeste.auth.ArtifactIntegrityTest', output / 'integrity', 'full'],
        'PASS: ArtifactIntegrity full (54 assertions)')
    if any(sha(ROOT / name) != digest for name, digest in pins.items()):
        raise ValueError('Tested input changed during verification')
    (output / 'result.json').write_text(json.dumps({'schema': 'celeste-native-budget-check-1', 'status': 'passed',
        'source_sha256': pins, 'checks': records, 'java_home': str(args.java_home.resolve()),
        'limits': ['Synthetic PE resource bounds and actual loader/integrity lifetime; no DLL execution, authentication, game or deployment.']},
        indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
