"""Check exact host/runtime source conservation; --sync is an explicit copy operation."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DELIVERY = "dev/c4lam/celeste/beta/delivery/"
MEMORY = "dev/c4lam/celeste/beta/memory/ModuleMemoryClassLoader.java"
AUTH = "dev/c4lam/celeste/auth/DpopProofSigner.java"
PAIRS = tuple(("beta-lab/delivery-client/src/" + DELIVERY + name + ".java",
               "runtime-overlay/src/main/java/" + DELIVERY + name + ".java")
              for name in ("ModuleDeliveryClient", "ModuleContainer", "ModuleCiphertextCache",
                           "ArtifactEnvelope", "BetaDeliveryClient", "HttpsTransport")) + (
    ("beta-lab/memory-runtime/src/main/java/" + MEMORY, "runtime-overlay/src/main/java/" + MEMORY),
    ("beta-lab/shared-auth/src/main/java/" + AUTH, "runtime-overlay/src/main/java/" + AUTH),
)


def check(root=ROOT, *, sync=False, require_all=True):
    root = root.resolve(strict=True)
    result = {}
    for source, target in PAIRS:
        source_path, target_path = root / source, root / target
        if not source_path.is_file() or not target_path.is_file() and not sync:
            if require_all:
                raise ValueError("Missing paired source: " + source + " -> " + target)
            continue
        for path in (source_path, target_path):
            for ancestor in (path, *path.parents):
                if ancestor == root.parent:
                    break
                if ancestor.is_symlink() or getattr(ancestor, "is_junction", lambda: False)():
                    raise ValueError("Linked paired source")
        original = source_path.read_bytes()
        if sync:
            target_path.parent.mkdir(parents=True, exist_ok=True)
            target_path.write_bytes(original)
        if original != target_path.read_bytes():
            raise ValueError("Paired delivery source differs: " + source + " -> " + target)
        result[source] = {"runtime": target, "sha256": hashlib.sha256(original).hexdigest()}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--sync", action="store_true", help="Intentionally overwrite runtime copies from canonical host sources")
    args = parser.parse_args()
    try:
        result = check(args.root.resolve(strict=True), sync=args.sync)
    except (ValueError, OSError) as error:
        parser.exit(1, "Paired source check refused: " + str(error) + "\n")
    print(json.dumps({"status": "paired-source-exact", "synchronized": args.sync, "pairs": result}, indent=2))


if __name__ == "__main__":
    main()
