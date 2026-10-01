#!/usr/bin/env python3
"""Compile and execute the actual pure Block ESP scheduler fixtures with JDK 21+."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def jdk_program(name: str, explicit: Path | None) -> Path:
    suffix = ".exe" if os.name == "nt" else ""
    homes = [explicit, Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None]
    for home in homes:
        if home is not None:
            candidate = home / "bin" / (name + suffix)
            if candidate.is_file():
                return candidate
    found = shutil.which(name)
    if found:
        return Path(found)
    if os.name == "nt":
        for home in sorted(Path("C:/Program Files/Eclipse Adoptium").glob("jdk-21*"), reverse=True):
            candidate = home / "bin" / (name + suffix)
            if candidate.is_file():
                return candidate
    raise RuntimeError("JDK 21+ required; pass --jdk-home or configure JAVA_HOME")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--source", type=Path)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--jdk-home", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source = (args.source or args.repo / "runtime-overlay/src/main/java/dev/c4lam/celeste/module/impl/visual/BlockEspScan.java").resolve()
    fixture = (args.fixture or args.repo / "tools/qa/BlockEspScanCheck.java").resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    classes = output / "classes"
    classes.mkdir(exist_ok=True)
    result = {
        "status": "failed", "kind": "production-pure-java-fixtures", "in_game_verified": False,
        "sources": [{"path": str(path), "sha256": sha256(path)} for path in (source, fixture)],
        "steps": [],
    }
    try:
        commands = [
            [str(jdk_program("javac", args.jdk_home)), "-encoding", "UTF-8", "--release", "21", "-d", str(classes), str(source), str(fixture)],
            [str(jdk_program("java", args.jdk_home)), "-cp", str(classes), "BlockEspScanCheck"],
        ]
        for index, command in enumerate(commands):
            completed = subprocess.run(command, text=True, encoding="utf-8", errors="replace", stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
            log = output / ("compile.log" if index == 0 else "fixtures.log")
            log.write_text(completed.stdout, encoding="utf-8")
            result["steps"].append({"command": command, "returncode": completed.returncode, "log": str(log), "sha256": sha256(log)})
            if completed.returncode:
                raise RuntimeError(f"{log.name} failed with exit {completed.returncode}")
            if index == 1:
                match = re.search(r"Block ESP loaded-data index: (\d+) assertions passed", completed.stdout)
                if not match:
                    raise RuntimeError("Fixture process did not emit its completion marker")
                result["assertions"] = int(match.group(1))
        result["status"] = "passed"
    except Exception as exc:
        result["error"] = str(exc)
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": result["status"], "assertions": result.get("assertions", 0), "result": str(output / "result.json")}, indent=2))
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
