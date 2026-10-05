"""Check or explicitly reproduce reviewed independent private helper sources."""
from pathlib import Path
import argparse
import json
import module_release

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sync', action='store_true', help='Write targets from exact pinned originals; never updates source pins')
    args = parser.parse_args()
    try:
        _, generated = module_release.source_copy_plan(ROOT)
        _, resources, _ = module_release.resource_copy_plan(ROOT)
        if args.sync:
            for target, expected in generated + resources:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(expected)
        module_release.check_source_copies(ROOT)
        print(json.dumps({'status': 'verified', 'private_source_copies': len(generated), 'private_resource_copies': len(resources), 'written': args.sync}))
    except (ValueError, OSError, KeyError, UnicodeError) as failure:
        parser.exit(1, 'Private module source generation refused: ' + str(failure) + '\n')


if __name__ == '__main__':
    main()
