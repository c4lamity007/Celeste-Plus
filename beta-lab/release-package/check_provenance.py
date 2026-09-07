"""Independent conservation check against the original actual input archive."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import struct
import zipfile
from pathlib import Path


def entries(data: bytes) -> dict[str, bytes]:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        files = [entry for entry in archive.infolist() if not entry.is_dir()]
        assert len({entry.filename for entry in files}) == len(files), "Duplicate artifact names"
        return {entry.filename: archive.read(entry) for entry in files}


def check(root: Path) -> None:
    manifest = json.loads((root / "package.json").read_text(encoding="utf-8"))
    source_bytes = Path(manifest["source"]).read_bytes()
    assert hashlib.sha256(source_bytes).hexdigest() == manifest["source_sha256"], "Original source changed"
    source = entries(source_bytes)
    payload = {}
    for item in manifest["payloads"]:
        data = (root / item["filename"]).read_bytes()
        assert hashlib.sha256(data).hexdigest() == item["sha256"] and len(data) == item["size"], "Payload metadata mismatch"
        part = entries(data)
        assert payload.keys().isdisjoint(part), "Cross-part duplicate"
        payload.update(part)
    provenance = {entry["source"]: entry for entry in manifest["provenance"]}
    assert len(provenance) == len(manifest["provenance"]) and provenance.keys() == source.keys(), "Source entry omitted from provenance"
    conserved = 0
    for name, original in source.items():
        entry = provenance[name]
        assert hashlib.sha256(original).hexdigest() == entry["sha256"], "Source provenance digest differs"
        if name == "META-INF/.r/4d7a9c2e":
            assert entry["action"] == "replace mutable auth descriptor"
            continue
        destination = entry["payload"]
        if "!/" in destination:
            container, member = destination.split("!/", 1)
            retained = entries(payload[container])[member]
        else:
            retained = payload[destination]
        assert original == retained, f"Source bytes changed/disappeared: {name}"
        conserved += 1
    digest = hashlib.sha256()
    for name in sorted(payload):
        if name == "META-INF/.r/4d7a9c2e":
            continue
        encoded = name.encode("utf-8")
        digest.update(struct.pack(">I", len(encoded)))
        digest.update(encoded)
        digest.update(struct.pack(">Q", len(payload[name])))
        digest.update(payload[name])
    assert digest.hexdigest() == manifest["runtime_sha256"], "Independent framing disagrees with runtime digest"
    for support in manifest["host_support"]["libraries"]:
        data = (root / "host-support" / support["filename"]).read_bytes()
        assert data == payload[support["payload_backing"]], "Host bytes not covered by exact retained payload"
        assert hashlib.sha256(data).hexdigest() == support["sha256"], "Host support digest mismatch"
    assert not (root / "beta-release.json").exists(), "Preparation accidentally emitted a live server catalog"
    print(json.dumps({"status": "passed", "conserved_source_entries": conserved, "retained_entries": len(payload),
                      "runtime_sha256": digest.hexdigest(), "server_catalog_created": False}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path)
    check(parser.parse_args().package.resolve(strict=True))
