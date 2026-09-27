"""Check the actual Windows media support JAR in bounded, owned JVMs.

No playback commands, game attachment, process discovery, or media metadata
logging. Each run reads the session count and cycle state, then calls the
existing cleanup bridge directly, from a shutdown hook, and across a same-JVM
cleanup/reinitialization cycle. Only exact stage
markers, numeric counts and exception class names reach the report. This is a
local JNI lifecycle smoke check, not a Minecraft UI or release approval gate.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import time
import zipfile

FIXTURE = Path(__file__).with_name("MediaBridgeSmoke.java")
RESOURCE = "mediaplayerinfo/natives/win/"
MAX_JAR = 32 * 1024 * 1024
TIMEOUT_SECONDS = 15
STAGES = {"before_load", "loaded", "enumeration_complete", "before_cleanup", "cleanup_returned", "before_exit",
          "before_reload", "reloaded", "reload_enumeration_complete"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def plain(path):
    path = Path(os.path.abspath(path))
    for part in (path, *path.parents):
        if part.exists() or part.is_symlink():
            info = part.lstat()
            require(not stat.S_ISLNK(info.st_mode) and not getattr(info, "st_file_attributes", 0) & 0x400,
                    "Smoke paths cannot traverse links or reparse points")
    return path


def run_owned(command, directory):
    """subprocess.run kills only its own child on timeout; never matches PIDs by name."""
    started = time.monotonic()
    try:
        result = subprocess.run(command, cwd=directory, capture_output=True, timeout=TIMEOUT_SECONDS,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return result.returncode, False, time.monotonic() - started, result.stdout
    except subprocess.TimeoutExpired as error:
        return None, True, time.monotonic() - started, error.stdout or b""


def safe_markers(output):
    # Discard all stderr and non-protocol stdout, including native diagnostics.
    markers = []
    for line in output.decode("utf-8", errors="replace").splitlines():
        match = re.fullmatch(r"CELESTE_MEDIA_SMOKE (stage|cleanup_available|sessions|cycle_reads|reload_sessions|reload_cycle_reads|error)=(.*)", line)
        if not match:
            continue
        key, value = match.groups()
        if key == "stage" and value in STAGES:
            markers.append({key: value})
        elif key == "cleanup_available" and value in ("true", "false"):
            markers.append({key: value == "true"})
        elif key in ("sessions", "cycle_reads", "reload_sessions", "reload_cycle_reads") and re.fullmatch(r"[0-9]{1,6}", value):
            markers.append({key: int(value)})
        elif key == "error" and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$.]{0,200}", value):
            markers.append({key: value})
    return markers


def case_passed(case):
    values = case["markers"]
    stages = [row["stage"] for row in values if "stage" in row]
    wanted = ["before_load", "loaded", "enumeration_complete"]
    if case["mode"] == "reload":
        wanted += ["before_cleanup", "cleanup_returned", "before_reload", "reloaded", "reload_enumeration_complete",
                   "before_cleanup", "cleanup_returned", "before_exit"]
    else:
        wanted += (["before_cleanup", "cleanup_returned", "before_exit"] if case["mode"] == "direct"
                   else ["before_exit", "before_cleanup", "cleanup_returned"])
    sessions = [row["sessions"] for row in values if "sessions" in row]
    cycles = [row["cycle_reads"] for row in values if "cycle_reads" in row]
    reload_sessions = [row["reload_sessions"] for row in values if "reload_sessions" in row]
    reload_cycles = [row["reload_cycle_reads"] for row in values if "reload_cycle_reads" in row]
    reload_valid = (len(reload_sessions) == 1 and reload_sessions == reload_cycles if case["mode"] == "reload"
                    else not reload_sessions and not reload_cycles)
    return (case["exit_code"] == 0 and not case["timed_out"] and stages == wanted and reload_valid
            and [row["cleanup_available"] for row in values if "cleanup_available" in row] == [True]
            and len(sessions) == 1 and sessions == cycles and not any("error" in row for row in values))


def smoke(support_jar, java_home, output):
    require(os.name == "nt", "This smoke check executes the Windows-only media bridge")
    support_jar, java_home, output = map(plain, (support_jar, java_home, output))
    require(support_jar.is_file() and 0 < support_jar.stat().st_size <= MAX_JAR, "Support JAR missing or oversized")
    require(not output.exists(), "Use a fresh smoke output directory")
    java, javac = java_home / "bin/java.exe", java_home / "bin/javac.exe"
    require(java.is_file() and javac.is_file(), "Explicit Java home must contain java.exe and javac.exe")
    data, source = support_jar.read_bytes(), FIXTURE.read_bytes()
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        entries = jar.infolist()
        require(len(entries) <= 128 and len({row.filename for row in entries}) == len(entries), "Invalid support JAR entries")
        require(sum(row.file_size for row in entries) <= MAX_JAR, "Expanded support JAR exceeds bounds")
        require(RESOURCE + "MediaPlayerInfo.dll" in jar.namelist()
                and RESOURCE + "CelesteMediaCleanup.dll" in jar.namelist(), "Both Windows media and cleanup DLL resources are required")
        resource_hashes = {name: sha(jar.read(name)) for name in (RESOURCE + "MediaPlayerInfo.dll", RESOURCE + "CelesteMediaCleanup.dll")}
    output.mkdir(parents=True)
    classes = output / "classes"
    classes.mkdir()
    snapshot = output / "support.jar"
    snapshot.write_bytes(data)
    fixture = output / FIXTURE.name
    fixture.write_bytes(source)
    report = {"schema": "celeste-media-bridge-smoke-1", "status": "failed", "platform": "windows-x64",
              "support_jar_sha256": sha(data), "fixture_sha256": sha(source), "native_resources": resource_hashes,
              "java_sha256": sha(java.read_bytes()), "javac_sha256": sha(javac.read_bytes()),
              "per_child_timeout_seconds": TIMEOUT_SECONDS, "cases": [],
              "scope": "Separate owned JVMs: session count, cycle read and native cleanup/exit only. No media controls, track metadata, game interaction, or release approval."}
    code, timeout, elapsed, _ = run_owned([str(javac), "-encoding", "UTF-8", "--release", "21", "-cp", str(snapshot),
                                         "-d", str(classes), str(fixture)], output)
    report["compile"] = {"exit_code": code, "timed_out": timeout, "seconds": round(elapsed, 3)}
    if code == 0 and not timeout:
        for mode in ("direct", "shutdown-hook", "reload"):
            code, timeout, elapsed, stdout = run_owned([str(java), "-cp", str(classes) + os.pathsep + str(snapshot),
                                                        "MediaBridgeSmoke", mode], output)
            case = {"mode": mode, "exit_code": code, "timed_out": timeout, "seconds": round(elapsed, 3),
                    "markers": safe_markers(stdout)}
            case["passed"] = case_passed(case)
            report["cases"].append(case)
    require(support_jar.read_bytes() == data and snapshot.read_bytes() == data and FIXTURE.read_bytes() == source,
            "Smoke input changed during the check")
    if len(report["cases"]) == 3 and all(case["passed"] for case in report["cases"]):
        report["status"] = "passed-owned-jvm-lifecycle"
    (output / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("support-jar", "java-home", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        report = smoke(args.support_jar, args.java_home, args.output)
        print(json.dumps({"status": report["status"], "support_jar_sha256": report["support_jar_sha256"], "cases": report["cases"]}))
        return 0 if report["status"] == "passed-owned-jvm-lifecycle" else 1
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Media smoke refused: {error}\n")


if __name__ == "__main__":
    raise SystemExit(main())
