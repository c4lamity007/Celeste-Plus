"""Read-only checks for preserved source and operator-supplied baseline inputs."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent

def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def require(value, message):
    if not value:
        raise ValueError(message)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-source",type=Path)
    parser.add_argument("--toolchain",type=Path)
    parser.add_argument("--adapted-source",type=Path)
    args=parser.parse_args()
    manifest=json.loads((ROOT/"source-manifest.json").read_text(encoding="utf-8"))
    require(manifest["schema"]=="celeste-native-auth-producer-source-1","Wrong source manifest")
    for entry in manifest["files"]:
        path=Path(entry["path"])
        require(not path.is_absolute() and ".." not in path.parts,"Invalid preserved source path")
        require(digest(ROOT/path)==entry["sha256"],"Preserved file changed: "+entry["path"])
    baseline=json.loads((ROOT/"provenance/producer-provenance.json").read_text(encoding="utf-8"))
    adapted=json.loads((ROOT/"provenance/adaptation-provenance.json").read_text(encoding="utf-8"))
    checked={"preserved":len(manifest["files"]),"baseline_sources":0,"toolchain":0,"adapted_sources":0}
    if args.baseline_source:
        for entry in baseline["verified_source_files"]:
            require(digest(args.baseline_source/entry["path"])==entry["sha256"],"Baseline source mismatch: "+entry["path"])
            checked["baseline_sources"]+=1
    if args.toolchain:
        for name,expected in adapted["toolchain_files"].items():
            require(digest(args.toolchain/Path(name.replace("\\","/")))==expected,"Toolchain mismatch: "+name)
            checked["toolchain"]+=1
    if args.adapted_source:
        for name,expected in manifest["reconstructed_sources"].items():
            require(digest(args.adapted_source/name)==expected["lf_sha256"],"Reconstructed source mismatch: "+name)
            checked["adapted_sources"]+=1
    print(json.dumps({"status":"source-inputs-verified-not-a-protection-approval","checked":checked}))

if __name__=="__main__":
    main()
