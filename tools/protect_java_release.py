"""Protect and verify a local Java release candidate. Never signs, uploads, or deploys.

Inputs are explicit: a remapped runtime, matching game/library classpath, an exact
producer bundle pin, and the existing public service trust. Fresh outputs only.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import statistics
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROFILES = ROOT / 'beta-lab/obfuscation/profiles'
HEX = re.compile(r'[0-9a-f]{64}\Z')


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def bounded(path, limit=128 * 1024 * 1024):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= limit, 'Missing, linked, or oversized input: ' + str(path))
    data = path.read_bytes()
    require(len(data) <= limit, 'Input changed beyond its bound')
    return data


def producer_bundle(jar):
    jar = jar.resolve(strict=True)
    data = bounded(jar)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        require(archive.namelist().count('META-INF/MANIFEST.MF') == 1, 'Producer requires one manifest')
        manifest = archive.read('META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n', '\n').replace('\n ', '')
    fields = dict(line.split(': ', 1) for line in manifest.splitlines() if ': ' in line)
    require(fields.get('Main-Class') == 'dev.local.obfuscator.App', 'Unrecognized Java protection entry point')
    files = {jar.name: data}
    for name in fields.get('Class-Path', '').split():
        relative = PurePosixPath(name)
        require(not relative.is_absolute() and relative.as_posix() == name and '..' not in relative.parts
                and '\\' not in name and ':' not in name and relative.suffix == '.jar', 'Unsafe producer dependency')
        path = (jar.parent / name).resolve(strict=True)
        require(path.is_relative_to(jar.parent) and name not in files, 'Producer dependency escapes or collides')
        files[name] = bounded(path)
    require(sum(map(len, files.values())) <= 256 * 1024 * 1024, 'Producer bundle exceeds bounds')
    hashes = {name: digest(value) for name, value in sorted(files.items())}
    return files, {'entry': jar.name, 'files': hashes, 'bundle_sha256': digest(canonical(hashes))}


def checked_profile(name, source, output, dependencies):
    profile = json.loads(bounded(PROFILES / name, 1024 * 1024))
    require(profile.get('schemaVersion') == 1 and profile.get('encryptStrings') is True
            and profile.get('doubleStringProtection') is True and profile.get('compositeStringModeOnly') is True,
            'Unreviewed Java string protection profile')
    for flag in ('nativeTranspile', 'nativeVirtualization', 'targetedNativeVirtualization',
                 'virtualizeExistingNativeLibraries', 'crazyObfuscation', 'relocateAssets', 'relocateMetadataClasses'):
        require(profile.get(flag) is False, 'Profile changes native, hot-path, or resource compatibility: ' + flag)
    if name.startswith('04-'):
        require(profile.get('renameClasses') is False and profile.get('renamePrivateMembers') is False,
                'Payload names must stay compatible with untouched callers')
    profile.update(inputJar=str(source), outputJar=str(output), dependencyJars=[str(path) for path in dependencies])
    return profile


def checked_parents(path):
    paths = [Path(line.strip()).resolve(strict=True) for line in bounded(path, 1024 * 1024).decode('utf-8-sig').splitlines() if line.strip()]
    require(paths and len(paths) <= 512 and len(set(paths)) == len(paths), 'Parent classpath is empty, duplicated, or excessive')
    require(all(p.suffix.lower() == '.jar' and p.is_file() for p in paths), 'Parent dependencies must be JAR files')
    return paths


def source_inputs():
    paths = list((ROOT / 'beta-lab').rglob('*.java')) + list(PROFILES.glob('*.json'))
    paths += [ROOT / path for path in ('build.gradle', 'core.lock.json', 'runtime-overlay/manifest.json',
        'tools/protect_java_release.py', 'tools/validate_package.py',
        'beta-lab/obfuscation/check.init.gradle', 'beta-lab/obfuscation/prepare_obfuscation.py',
        'beta-lab/release-package/package_beta.py')]
    return {str(p.relative_to(ROOT)): digest(bounded(p)) for p in sorted(set(paths))}


def run(args):
    output = args.output.resolve()
    require(not output.exists(), 'Use a fresh Java release output directory')
    require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', args.release_id), 'Invalid release ID')
    files, producer = producer_bundle(args.obfuscator)
    require(HEX.fullmatch(args.tool_sha256) and producer['bundle_sha256'] == args.tool_sha256, 'Producer bundle differs from reviewed pin')
    parents = checked_parents(args.parent_classpath)
    parent_hashes = {str(p): digest(bounded(p)) for p in parents}
    runtime = bounded(args.input)
    descriptor = args.descriptor_module.resolve(strict=True)
    require(descriptor.is_file(), 'Pinned descriptor codec is missing')
    java_home = args.java_home.resolve(strict=True)
    java = java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    require(java.is_file(), 'Java 21 JDK is unavailable')
    output.mkdir(parents=True)
    toolchain = output / 'toolchain'
    for name, data in files.items():
        target = toolchain / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    source = output / 'runtime-input.jar'
    source.write_bytes(runtime)
    dependencies = output / 'parent-classpath.txt'
    dependencies.write_text('\n'.join(map(str, parents)) + '\n', encoding='utf-8')
    report = {'schema': 'celeste-plus-java-protection-run-1', 'status': 'running', 'release_id': args.release_id,
              'producer': producer, 'input_sha256': digest(runtime), 'parent_dependencies': parent_hashes,
              'descriptor_sha256': digest(bounded(descriptor)), 'source_inputs': source_inputs(), 'profiles': {}, 'steps': [], 'deployed': False,
              'limits': ['No Minecraft launch, live authentication, FPS measurement, or native protection claim.',
                         'Cold verification timing includes JVM startup and is not a gameplay benchmark.']}
    environment = os.environ.copy()
    environment['JAVA_HOME'] = str(java_home)
    def save():
        (output / 'receipt.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    def step(name, command):
        started = time.perf_counter()
        with (output / (name + '.log')).open('xb') as log:
            result = subprocess.run(list(map(str, command)), cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT)
        elapsed = time.perf_counter() - started
        report['steps'].append({'name': name, 'exit_code': result.returncode, 'seconds': elapsed})
        save()
        require(result.returncode == 0, 'Java release step failed: ' + name + '; inspect its local log')
        print('PASS ' + name, flush=True)
        return elapsed
    python = sys.executable
    staging = ROOT / 'beta-lab/obfuscation/prepare_obfuscation.py'
    packaging = ROOT / 'beta-lab/release-package/package_beta.py'
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    gradle = ([str(wrapper)] if os.name == 'nt' else ['sh', str(wrapper)]) + ['--no-daemon', '--console=plain']
    if args.offline:
        gradle.append('--offline')
    def protect(name, template, source, target, refs):
        config = checked_profile(template, source, target, refs)
        profile = output / (name + '.json')
        profile.write_text(json.dumps(config, indent=2) + '\n', encoding='utf-8')
        report['profiles'][profile.name] = digest(bounded(profile))
        step(name, [java, '-Xmx2g', '-jar', toolchain / producer['entry'], '--config', profile])
    try:
        step('payload-stage', [python, staging, 'stage-payload', '--source', source, '--output', output / 'payload-stage'])
        protect('payload-protection', '04-payload-protected-v2.json', output / 'payload-stage/payload-thin.jar',
                output / 'payload-implementation.jar', [output / 'payload-stage/payload-stable.jar', *parents])
        step('payload-merge', [python, staging, 'merge-payload', '--stage', output / 'payload-stage', '--protected', output / 'payload-implementation.jar',
                              '--mapping', output / 'payload-implementation.jar.mapping.json', '--output', output / 'payload-protected.jar'])
        package = output / 'package'
        step('payload-split', [python, packaging, 'prepare', '--input', output / 'payload-protected.jar', '--descriptor-module', descriptor,
              '--output', package, '--release-id', args.release_id, '--client-version', args.client_version, '--base-url', args.base_url, '--server-key', args.server_key])
        step('payload-validation', [python, ROOT / 'tools/validate_package.py', '--package', package, '--java-home', java_home, '--parent-classpath', dependencies])
        check_cp = output / 'check-classpath.txt'
        step('bootstrap-build', [*gradle, '-I', ROOT / 'beta-lab/obfuscation/check.init.gradle',
              '-PbetaPackageDirectory=' + str(package), '-PbetaLayoutFile=' + str(package / 'runtime-layout.json'),
              '-PobfuscationCheckClasspath=' + str(check_cp), 'betaBootstrapAJar', 'betaBootstrapBJar',
              'betaObfuscationCompatibilityCheck', 'writeObfuscationCheckClasspath'])
        raw_a, raw_b = output / 'bootstrap-a-raw.jar', output / 'bootstrap-b-raw.bin'
        shutil.copyfile(ROOT / 'build/module-qa/bootstrap-a/bootstrap-a.jar', raw_a)
        shutil.copyfile(ROOT / 'build/module-qa/bootstrap-b/bootstrap-b.bin', raw_b)
        check = [java, '-ea', '-Xverify:all', '--add-modules', 'jdk.attach', '-cp', check_cp.read_text(),
                 'dev.celestebeta.bootstrap.external.ProtectedBootstrapArtifactCheck']
        package_record = json.loads(bounded(package / 'package.json'))
        step('payload-class-verification', [java, '-ea', '-Xverify:all', '-Xmx768m', '-cp', check_cp.read_text(),
             'dev.celestebeta.bootstrap.external.ProtectedPayloadClassCheck', package,
             package_record['payloads'][0]['sha256'], package_record['payloads'][1]['sha256'], dependencies])
        baseline = [step('baseline-cold-' + str(n), [*check, raw_a, raw_b]) for n in range(3)]
        step('bootstrap-a-stage', [python, staging, 'stage-a', '--source', raw_a, '--output', output / 'a-stage'])
        stable = output / 'a-stage/bootstrap-a-stable.jar'
        protect('bootstrap-a-protection', '02-bootstrap-a-implementation.json', output / 'a-stage/bootstrap-a-thin.jar', output / 'bootstrap-a-implementation.jar', [stable])
        step('bootstrap-a-merge', [python, staging, 'merge-a', '--stage', output / 'a-stage', '--protected', output / 'bootstrap-a-implementation.jar', '--output', output / 'bootstrap-a.jar'])
        protect('bootstrap-b-protection', '03-bootstrap-b-compatible.json', raw_b, output / 'bootstrap-b.bin', [stable])
        protected = [step('protected-cold-' + str(n), [*check, output / 'bootstrap-a.jar', output / 'bootstrap-b.bin']) for n in range(3)]
        step('assemble', [python, packaging, 'assemble', '--package', package, '--bootstrap-a', output / 'bootstrap-a.jar', '--bootstrap-b', output / 'bootstrap-b.bin', '--output', output / 'release'])
        require(digest(bounded(args.input)) == report['input_sha256'], 'Runtime input changed during protection')
        require(digest(bounded(descriptor)) == report['descriptor_sha256'], 'Descriptor codec changed during protection')
        require(all(digest(bounded(Path(name))) == value for name, value in parent_hashes.items()), 'Parent dependency changed during protection')
        require(all(digest(bounded(toolchain / name)) == value for name, value in producer['files'].items()), 'Producer changed during protection')
        require(source_inputs() == report['source_inputs'], 'Java build or release sources changed during protection')
        require(all(digest(bounded(output / name)) == value for name, value in report['profiles'].items()), 'Protection profile changed during execution')
        report['artifacts'] = {p.name: {'sha256': digest(bounded(p)), 'bytes': p.stat().st_size} for p in (output / 'release').iterdir() if p.is_file()}
        report['cold_fixture_seconds'] = {'baseline': baseline, 'protected': protected,
             'baseline_median': statistics.median(baseline), 'protected_median': statistics.median(protected)}
        report['status'] = 'protected-verified-candidate-not-published'
        save()
        print(json.dumps({'status': report['status'], 'directory': str(output), 'receipt': str(output / 'receipt.json')}))
    except BaseException:
        report['status'] = 'failed-not-published'
        save()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    inspect = sub.add_parser('inspect-tool')
    inspect.add_argument('--obfuscator', type=Path, required=True)
    release = sub.add_parser('run')
    for name in ('input', 'obfuscator', 'parent-classpath', 'java-home', 'output'):
        release.add_argument('--' + name, type=Path, required=True)
    release.add_argument('--descriptor-module', type=Path, default=ROOT / 'build/runtime-core/tools/package_final_release.py')
    for name in ('tool-sha256', 'release-id', 'client-version', 'base-url', 'server-key'):
        release.add_argument('--' + name, required=True)
    release.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    try:
        if args.command == 'inspect-tool':
            print(json.dumps(producer_bundle(args.obfuscator)[1], indent=2))
        else:
            run(args)
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as failure:
        parser.exit(1, 'Java protection refused: ' + str(failure) + '\n')


if __name__ == '__main__':
    main()
