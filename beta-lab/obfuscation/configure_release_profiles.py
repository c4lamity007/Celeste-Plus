"""Generate portable protection configs from explicit local build inputs; runs no external tool."""
from pathlib import Path
import argparse
import json
import re

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-id', required=True)
    parser.add_argument('--scope', choices=['bootstrap', 'payload', 'all'], default='bootstrap')
    parser.add_argument('--parent-classpath', type=Path, help='One required game/library JAR path per line; required for payload protection')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}', args.release_id):
        parser.error('Use an alphanumeric release ID with hyphens/underscores')
    parents = []
    if args.scope in ('payload', 'all'):
        if args.parent_classpath is None:
            parser.error('Payload protection requires --parent-classpath; no local cache layout is assumed')
        parents = [str(Path(line.strip()).resolve(strict=True)) for line in args.parent_classpath.read_text(encoding='utf-8').splitlines() if line.strip()]
        if not parents or any(not Path(path).is_file() or Path(path).suffix != '.jar' for path in parents):
            parser.error('Parent classpath must contain actual JAR files')
    root = Path(__file__).resolve().parents[2]
    out = root / 'build/module-qa/obfuscation' / args.release_id
    out.mkdir(parents=True, exist_ok=False)
    definitions = []
    if args.scope in ('payload', 'all'):
        definitions.append(('04-payload-protected-v2.json', 'payload-stage', 'payload-thin.jar', 'payload-implementation.jar'))
    if args.scope in ('bootstrap', 'all'):
        definitions.extend([('02-bootstrap-a-implementation.json', 'a-stage', 'bootstrap-a-thin.jar', 'bootstrap-a-implementation.jar'), ('03-bootstrap-b-compatible.json', None, 'bootstrap-b.bin', 'bootstrap-b.bin')])
    for name, stage, input_name, output_name in definitions:
        config = json.loads((Path(__file__).parent / 'profiles' / name).read_text(encoding='utf-8'))
        config['inputJar'] = str(out / stage / input_name) if stage else str(root / 'build/module-qa/bootstrap-b/bootstrap-b.bin')
        config['outputJar'] = str(out / output_name)
        config['dependencyJars'] = [str(out / (stage or 'a-stage') / ('payload-stable.jar' if stage == 'payload-stage' else 'bootstrap-a-stable.jar'))]
        if stage == 'payload-stage': config['dependencyJars'].extend(parents)
        (out / name).write_text(json.dumps(config, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': 'configured', 'directory': str(out)}))
if __name__ == '__main__':
    main()
