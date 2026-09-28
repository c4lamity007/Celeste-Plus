"""Validate supplied Celeste+ payload bytes offline; no auth, game startup or deployment."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, default=Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
    parser.add_argument('--parent-classpath', type=Path)
    args = parser.parse_args()
    package = args.package.resolve(strict=True)
    manifest = json.loads((package / 'package.json').read_text(encoding='utf-8'))
    suffix = '.exe' if os.name == 'nt' else ''
    def tool(name):
        result = str(args.java_home / 'bin' / (name + suffix)) if args.java_home else shutil.which(name)
        if not result or not Path(result).is_file():
            parser.error('A Java 21 JDK is required; set JAVA_HOME or --java-home')
        return result
    sources = [ROOT / path for path in (
        'beta-lab/release-package/src/dev/c4lam/celeste/auth/ActualPayloadCheck.java',
        'beta-lab/memory-runtime/src/main/java/dev/c4lam/celeste/beta/memory/TwoPartMemoryClassLoader.java',
        'beta-lab/memory-runtime/src/main/java/dev/c4lam/celeste/beta/memory/NativeResourceBudget.java',
        'beta-lab/integrity-adapter/src/test/java/dev/c4lam/celeste/CelesteClient.java',
        'beta-lab/bootstrap-b/fixtures/core/dev/c4lam/celeste/auth/ArtifactIntegrity.java',
        'beta-lab/bootstrap-b/fixtures/core/dev/c4lam/celeste/auth/Crypto.java')]
    output = ROOT / 'build/module-qa/validators'
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='package-', dir=output) as temporary:
        subprocess.run([tool('javac'), '--release', '21', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', temporary, *map(str, sources)], check=True)
        command = [tool('java'), '-ea', '-Xverify:all', '-Xmx384m', '-cp', temporary, 'dev.c4lam.celeste.auth.ActualPayloadCheck', str(package), manifest['payloads'][0]['sha256'], manifest['payloads'][1]['sha256'], manifest['runtime_sha256']]
        if args.parent_classpath:
            command.append(str(args.parent_classpath.resolve(strict=True)))
        subprocess.run(command, check=True)
if __name__ == '__main__':
    main()
