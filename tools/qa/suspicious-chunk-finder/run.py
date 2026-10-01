#!/usr/bin/env python3
"""Compile actual Suspicious Chunk Finder core sources and deterministic fixtures.

This covers pure detection/scoring and bounded geometry. It is not evidence of
Minecraft chunk collection, rendering, GUI integration or real-world accuracy.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", action="append", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    sources = [p.resolve(strict=True) for p in args.source]
    if len(sources) != len(set(sources)):
        parser.error("Production sources must be unique")
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    copied = []
    evidence = {"scope": "production pure Java core with deterministic terrain fixtures; no in-game proof",
                "sources": {str(p): digest(p) for p in sources},
                "runner_sha256": digest(Path(__file__))}
    fixture = Path(__file__).with_name("SuspiciousChunkFinderCheck.java")
    evidence["fixture_sha256"] = digest(fixture)
    for source in [*sources, fixture]:
        package = re.search(r"^package\s+([\w.]+)\s*;", source.read_text(encoding="utf-8"), re.M)
        if not package:
            raise ValueError("Expected packaged Java source: " + str(source))
        destination = out / "source" / package.group(1).replace(".", "/") / source.name
        if destination.exists():
            raise ValueError("Duplicate Java class source: " + str(destination))
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        copied.append(destination)
    classes = out / "classes"
    classes.mkdir()
    suffix = ".exe" if sys.platform == "win32" else ""
    java = args.java_home / "bin" / ("java" + suffix)
    javac = args.java_home / "bin" / ("javac" + suffix)
    commands = [
        ("compile", [str(javac), "--release", "21", "-proc:none", "-encoding", "UTF-8",
                     "-d", str(classes), *map(str, copied)]),
        ("run", [str(java), "-ea", "-cp", str(classes),
                 "dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousChunkFinderCheck"]),
    ]
    status = 0
    output = ""
    for label, command in commands:
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                                errors="replace", timeout=180)
        output = result.stdout + result.stderr
        (out / (label + ".log")).write_text(output, encoding="utf-8")
        evidence[label + "_exit"] = result.returncode
        if result.returncode:
            status = result.returncode
            evidence["status"] = label + "-failed"
            break
    if not status:
        evidence["status"] = "passed"
    evidence["passed_cases"] = [s[5:] for s in output.splitlines() if s.startswith("PASS ")]
    evidence["failed_cases"] = [s[5:] for s in output.splitlines() if s.startswith("FAIL ")]
    (out / "result.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(output, end="")
    print("Evidence: " + str(out / "result.json"))
    return status


if __name__ == "__main__":
    raise SystemExit(main())
