#!/usr/bin/env python3
"""Compile the supplied production FreecamFeature against narrow Java fixtures.

This exercises production lifecycle/motion code. It does not test Minecraft's
renderer, Sodium integration, mixin injection, or visual chunk visibility.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    source = args.source.resolve(strict=True)
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    fixtures = Path(__file__).resolve().parent / "fixtures"
    copied = out / "source/dev/c4lam/celeste/module/impl/movement/FreecamFeature.java"
    copied.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, copied)
    classes = out / "classes"
    classes.mkdir(exist_ok=True)
    suffix = ".exe" if sys.platform == "win32" else ""
    javac = args.java_home / "bin" / ("javac" + suffix)
    java = args.java_home / "bin" / ("java" + suffix)
    summary = {
        "scope": "production Java source with Minecraft fixtures; no in-game proof",
        "source": str(source),
        "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    }
    compilation = subprocess.run(
        [str(javac), "-encoding", "UTF-8", "-d", str(classes),
         str(copied), *map(str, sorted(fixtures.rglob("*.java")))],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    (out / "compile.log").write_text(compilation.stdout + compilation.stderr, encoding="utf-8")
    summary["compile_exit"] = compilation.returncode
    if compilation.returncode:
        summary["status"] = "compile-failed"
        (out / "result.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        print(compilation.stdout + compilation.stderr)
        return compilation.returncode
    execution = subprocess.run(
        [str(java), "-ea", "-cp", str(classes),
         "dev.c4lam.celeste.module.impl.movement.FreecamLifecycleFixture"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    output = execution.stdout + execution.stderr
    (out / "run.log").write_text(output, encoding="utf-8")
    summary.update({
        "run_exit": execution.returncode,
        "status": "passed" if execution.returncode == 0 else "failed",
        "passed_cases": [line[5:] for line in output.splitlines() if line.startswith("PASS ")],
        "failed_cases": [line[5:] for line in output.splitlines() if line.startswith("FAIL ")],
    })
    (out / "result.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(output, end="")
    print("Evidence: " + str(out / "result.json"))
    return execution.returncode


if __name__ == "__main__":
    raise SystemExit(main())
