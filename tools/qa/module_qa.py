"""Focused Celeste checks and honest screenshot evidence. Python standard library only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import time
import zlib
from datetime import datetime, timezone
from uuid import uuid4


MAX_EVIDENCE_BYTES = 64 * 1024 * 1024
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def utc():
    return datetime.now(timezone.utc).isoformat()


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def source_digest(project):
    """No credentials, local config, auth state, or build output in the fingerprint."""
    files = [p for name in ("src", "tools/qa", "gradle")
             for p in (project / name).rglob("*")
             if p.is_file() and "__pycache__" not in p.parts]
    files += [project / name for name in ("build.gradle", "settings.gradle", "gradle.properties",
                                          "gradlew", "gradlew.bat") if (project / name).is_file()]
    result = hashlib.sha256()
    for path in sorted(files):
        result.update(path.relative_to(project).as_posix().encode())
        result.update(bytes.fromhex(digest(path)))
    return result.hexdigest()


def save(run, data):
    temporary = run / "result.tmp"
    temporary.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    temporary.replace(run / "result.json")


def load(run):
    return json.loads((run / "result.json").read_text(encoding="utf-8"))


def read_png(path):
    """Bounded PNG structural/CRC validation; visual inspection is still required."""
    with Path(path).open("rb") as stream:
        stat = os.fstat(stream.fileno())
        if stat.st_size > MAX_EVIDENCE_BYTES:
            raise ValueError("Evidence PNG exceeds the 64 MiB size limit")
        image = stream.read(MAX_EVIDENCE_BYTES + 1)
    if len(image) > MAX_EVIDENCE_BYTES:
        raise ValueError("Evidence PNG exceeds the 64 MiB size limit")
    if len(image) < 33 or image[:8] != PNG_SIGNATURE:
        raise ValueError("Evidence must be a complete PNG screenshot or GPU output")
    offset = 8
    dimensions = None
    color_type = None
    palette = False
    seen_idat = False
    ended_idat = False
    idat_bytes = 0
    while offset < len(image):
        if offset + 12 > len(image):
            raise ValueError("Truncated PNG chunk")
        size = struct.unpack_from(">I", image, offset)[0]
        end = offset + 12 + size
        if end > len(image):
            raise ValueError("Truncated PNG chunk")
        kind = image[offset + 4:offset + 8]
        if not all(65 <= byte <= 90 or 97 <= byte <= 122 for byte in kind):
            raise ValueError("Invalid PNG chunk type")
        payload = image[offset + 8:end - 4]
        expected_crc = struct.unpack_from(">I", image, end - 4)[0]
        if zlib.crc32(image[offset + 4:end - 4]) & 0xFFFFFFFF != expected_crc:
            raise ValueError("PNG chunk CRC mismatch")
        if dimensions is None and kind != b"IHDR":
            raise ValueError("PNG must begin with IHDR")
        if kind == b"IHDR":
            if dimensions is not None or size != 13:
                raise ValueError("Invalid PNG IHDR")
            width, height, depth, color_type, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            depths = {0: (1, 2, 4, 8, 16), 2: (8, 16), 3: (1, 2, 4, 8), 4: (8, 16), 6: (8, 16)}
            if not (1 <= width <= 32768 and 1 <= height <= 32768):
                raise ValueError("Invalid PNG dimensions")
            if depth not in depths.get(color_type, ()) or compression or filtering or interlace not in (0, 1):
                raise ValueError("Invalid PNG image format")
            dimensions = (width, height)
        elif kind == b"PLTE":
            if palette or seen_idat or color_type in (0, 4) or size < 3 or size > 768 or size % 3:
                raise ValueError("Invalid PNG palette")
            palette = True
        elif kind == b"IDAT":
            if ended_idat or (color_type == 3 and not palette):
                raise ValueError("Invalid PNG IDAT ordering")
            seen_idat = True
            idat_bytes += size
        elif kind == b"IEND":
            if size or not seen_idat or not idat_bytes or end != len(image):
                raise ValueError("PNG needs image data and a final empty IEND")
            return image, dimensions, stat.st_mtime
        elif not kind[0] & 0x20:
            raise ValueError("Unsupported critical PNG chunk")
        if seen_idat and kind != b"IDAT":
            ended_idat = True
        offset = end
    raise ValueError("PNG is missing IEND")


def verify_evidence(run, data):
    """Do not hand off missing or replaced pictures as the recorded evidence."""
    for evidence in data.get("evidence", []):
        filename = evidence["file"]
        path = (run / filename).resolve()
        if Path(filename).name != filename or path.parent != run.resolve():
            raise ValueError("Evidence path must stay inside its run directory")
        if not path.is_file():
            raise ValueError("Recorded evidence is missing; restore it or create a fresh QA run")
        image, dimensions, _ = read_png(path)
        if hashlib.sha256(image).hexdigest() != evidence["sha256"]:
            raise ValueError("Recorded evidence changed; create a fresh QA run")
        if dimensions != (evidence["width"], evidence["height"]):
            raise ValueError("Recorded evidence dimensions no longer match")


def output(run, data):
    print(json.dumps({"status": data["status"], "modules": data["modules"],
                      "checks": [{"name": c["name"], "status": c["status"]} for c in data["checks"]],
                      "runtime": data.get("runtime", {}).get("status", "pending"),
                      "report": str(run / "result.json"),
                      "images": [{"kind": x["kind"], "path": str(run / x["file"])}
                                 for x in data.get("evidence", [])],
                      "note": data.get("note", "")}, separators=(",", ":")))


def run_step(run, name, command, cwd, env, timeout):
    start = time.monotonic()
    log = run / (name + ".log")
    with log.open("w", encoding="utf-8") as stream:
        # Full output stays local; callers only receive structured status, never a log dump.
        try:
            process = subprocess.Popen(command, cwd=cwd, env=env, stdout=stream,
                                       stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
            try:
                code = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                # Stop only the process tree started for this check, not unrelated Java/game processes.
                result = {"name": name, "status": "fail", "reason": "timeout", "log": log.name}
                try:
                    if os.name == "nt":
                        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
                    else:
                        process.kill()
                    process.wait(timeout=15)
                except (OSError, subprocess.TimeoutExpired) as error:
                    stream.write(f"Timeout cleanup unconfirmed ({type(error).__name__}); "
                                 f"check process {process.pid} may still be running.\n")
                    result.update(reason="timeout; process cleanup could not be confirmed",
                                  cleanup="unconfirmed", pid=process.pid)
                return result
        except OSError as error:
            stream.write(str(error))
            return {"name": name, "status": "fail", "reason": "could not start check", "log": log.name}
    return {"name": name, "status": "pass" if code == 0 else "fail", "exit_code": code,
            "seconds": round(time.monotonic() - start, 2), "log": log.name}


def check(args):
    project = args.project.resolve()
    if not (project / "gradlew.bat").is_file():
        raise ValueError("Project must be the Celeste source root containing gradlew.bat")
    if args.suite in ("combat", "shield-break", "pearl-catches", "trajectories", "ambient-vfx", "sky-lyrics", "sky-lyrics-sword", "player-visuals", "crystal-visuals", "atmosphere", "fog-compatibility") and not args.java_home:
        raise ValueError(f"The {args.suite} suite requires --java-home pointing to JDK 21")
    root = args.output.resolve() if args.output else project / "build/module-qa"
    run = root / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + uuid4().hex[:8])
    run.mkdir(parents=True, exist_ok=False)
    data = {"schema": 1, "project": str(project), "started": utc(), "started_epoch": time.time(),
            "modules": args.module, "suite": args.suite, "status": "running",
            "source_sha256": source_digest(project), "checks": [], "evidence": [],
            "runtime": {"status": "pending"}}
    save(run, data)
    env = os.environ.copy()
    if args.java_home:
        env["JAVA_HOME"] = str(args.java_home.resolve())
        env["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + env.get("PATH", "")
    command = [str(project / "gradlew.bat"), "--no-daemon", "--console=plain", ":build"]
    init_script = getattr(args, "gradle_init_script", None)
    if init_script is not None:
        init_script = init_script.resolve(strict=True)
        if not init_script.is_file() or init_script.suffix != ".gradle":
            raise ValueError("Gradle init input must be an existing .gradle file")
        data["gradle_init_script"] = {"path": str(init_script), "sha256": digest(init_script)}
        command += ["--init-script", str(init_script)]
    data["checks"].append(run_step(run, "build", command, project, env, args.timeout))
    save(run, data)
    if args.suite != "build":
        plan = [("jump" if args.suite == "jump" else "visuals", "run-visuals.ps1",
                 ["-Suite", "Animation" if args.suite == "jump" else "All"])]
        if args.suite in ("player-visuals", "crystal-visuals", "atmosphere"):
            plan += [("chams", "run-chams-aurora.ps1", []),
                     ("prism", "run-prism-material.ps1", []),
                     ("unified-volume", "run-unified-volume.ps1", []),
                     ("local-damage", "check_local_damage.ps1", [])]
        if args.suite == "crystal-visuals":
            plan += [("block-highlight", "run-block-highlight.ps1", []),
                     ("auras", "check_auras.ps1", [])]
        if args.suite == "atmosphere":
            plan += [("atmosphere", "run-atmosphere.ps1", [])]
        if args.suite == "fog-compatibility":
            compatibility_args = ["-SodiumJar", str(args.sodium_jar.resolve())] if args.sodium_jar else []
            plan = [("atmosphere", "run-atmosphere.ps1", []),
                    ("fog-compatibility", "run-fog-compatibility.ps1", compatibility_args),
                    ("shader-fog", "run-shader-fog.ps1", [])]
        if args.suite == "combat":
            plan = [("auras", "check_auras.ps1", []),
                    ("horizontal-pearl", "check_horizontal_pearl.ps1", [])]
        if args.suite == "shield-break":
            plan = [("shield-break", "run-shield-break.ps1", []),
                    ("sword-aura-range", "run-sword-aura-range.ps1", []),
                    ("mace-selection", "check_mace_selection.ps1", [])]
        if args.suite == "pearl-catches":
            plan = [("pearl-catches", "check_horizontal_pearl.ps1", []),
                    ("elytra-silent-aim", "check_elytra_silent_aim.ps1", [])]
        if args.suite == "trajectories":
            plan = [("trajectories", "run-trajectories.ps1", [])]
        if args.suite in ("sky-lyrics", "sky-lyrics-sword"):
            plan = [("sky-lyrics-timeline", "run-sky-lyrics.ps1", []),
                    ("sky-lyrics-shader", "run-sky-lyrics-shader.ps1", [])]
            if args.suite == "sky-lyrics-sword":
                plan.append(("sword-aura-range", "run-sword-aura-range.ps1", []))
        if args.suite == "ambient-vfx":
            plan = [("trajectories", "run-trajectories.ps1", []),
                    ("falling-leaves", "run-falling-leaves.ps1", ["-Gpu"]),
                    ("sky-lines", "run-sky-lines.ps1", []),
                    ("atmosphere-rules", "run-atmosphere.ps1", ["-Suite", "Math"])]
        for name, script, extra in plan:
            if data["checks"][-1]["status"] != "pass":
                break
            runner = Path(__file__).with_name(script)
            command = [shutil.which("pwsh.exe") or "powershell.exe", "-NoProfile", "-File", str(runner),
                       "-ProjectRoot", str(project), "-OutputRoot", str(run / name)] + extra
            if args.java_home:
                command += ["-JdkHome", str(args.java_home.resolve())]
            data["checks"].append(run_step(run, name, command, project, env, args.timeout))
            save(run, data)
    data["artifacts"] = [{"path": str(p), "sha256": digest(p)}
                         for p in sorted((project / "build/libs").glob("*.jar"))]
    if source_digest(project) != data["source_sha256"]:
        data["checks"].append({"name": "source-stability", "status": "fail",
                               "reason": "Source changed during checks; rerun after edits finish"})
    if init_script is not None and digest(init_script) != data["gradle_init_script"]["sha256"]:
        data["checks"].append({"name": "build-input-stability", "status": "fail",
                               "reason": "Gradle init input changed during checks"})
    failed = any(c["status"] != "pass" for c in data["checks"])
    data["status"] = "fail" if failed else "checks-passed"
    data["note"] = "See the failing check's local log." if failed else "In-game smoke test and screenshot still pending."
    data["checked_at"] = utc()
    save(run, data)
    output(run, data)
    return int(failed)


def attach(args):
    run = args.run.resolve()
    data = load(run)
    source = args.image.resolve()
    image, (width, height), captured_mtime = read_png(source)
    if captured_mtime < data["started_epoch"]:
        raise ValueError("Image predates this run; capture fresh evidence instead of reusing an old picture")
    if len(data["evidence"]) >= 3:
        raise ValueError("This run already has three evidence images; keep the handoff focused")
    filename = "evidence-" + str(len(data["evidence"]) + 1) + ".png"
    destination = run / filename
    # Copy the captured pixels unchanged. No AI replacement or effect retouching.
    with destination.open("xb") as stream:
        stream.write(image)
    data["evidence"].append({"kind": args.kind, "file": filename, "sha256": digest(destination),
                             "width": width, "height": height, "observation": args.observation,
                             "source": str(source), "captured_file_mtime": captured_mtime,
                             "added_at": utc()})
    save(run, data)
    output(run, data)
    return 0


def finish(args):
    run = args.run.resolve()
    data = load(run)
    verify_evidence(run, data)
    if source_digest(Path(data["project"])) != data["source_sha256"]:
        raise ValueError("Source changed since checks. Create a new run before marking runtime results.")
    if args.runtime == "pass":
        if not data["checks"] or any(c["status"] != "pass" for c in data["checks"]):
            raise ValueError("A failed build/test cannot be marked passed")
        if not any(x["kind"] == "game" for x in data["evidence"]):
            raise ValueError("Runtime pass needs a fresh, inspected in-game screenshot, not a GPU preview")
        if not args.loaded_build or not args.case:
            raise ValueError("Record how the loaded build was identified and the actual smoke-test observations")
    data["runtime"] = {"status": args.runtime, "note": args.note, "observed_cases": args.case or [],
                        "loaded_build": args.loaded_build, "recorded_at": utc()}
    failed = any(c["status"] != "pass" for c in data["checks"]) or args.runtime == "fail"
    data["status"] = "fail" if failed else "pass" if args.runtime == "pass" else "partial"
    data["note"] = args.note
    save(run, data)
    output(run, data)
    return 1 if failed else 2 if args.runtime == "blocked" else 0


def status(args):
    run = args.run.resolve()
    data = load(run)
    verify_evidence(run, data)
    output(run, data)


def parser():
    root = argparse.ArgumentParser(description=__doc__)
    sub = root.add_subparsers(dest="action", required=True)
    p = sub.add_parser("check", help="Incremental build plus relevant regression suite; full logs stay local")
    p.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument("--module", action="append", required=True, help="Repeat for several changed modules")
    p.add_argument("--suite", choices=("build", "combat", "shield-break", "pearl-catches", "trajectories", "ambient-vfx", "sky-lyrics", "sky-lyrics-sword", "jump", "visuals", "player-visuals", "crystal-visuals", "atmosphere", "fog-compatibility"), default="build")
    p.add_argument("--output", type=Path)
    p.add_argument("--java-home", type=Path)
    p.add_argument("--gradle-init-script", type=Path, help="Explicit build overrides, such as reviewed Windows media DLL inputs")
    p.add_argument("--sodium-jar", type=Path, help="Installed Sodium artifact for the fog-compatibility regression suite")
    p.add_argument("--timeout", type=int, default=300, help="Per-check timeout in seconds")
    p.set_defaults(func=check)
    p = sub.add_parser("attach", help="Attach fresh PNG evidence after visually inspecting it")
    p.add_argument("--run", type=Path, required=True)
    p.add_argument("--image", type=Path, required=True)
    p.add_argument("--kind", choices=("game", "gpu-preview"), required=True)
    p.add_argument("--observation", required=True, help="What the inspected image actually demonstrates")
    p.set_defaults(func=attach)
    p = sub.add_parser("finish", help="Record observed runtime result; never infer a game pass from compilation")
    p.add_argument("--run", type=Path, required=True)
    p.add_argument("--runtime", choices=("pass", "fail", "blocked"), required=True)
    p.add_argument("--note", required=True)
    p.add_argument("--case", action="append", help="Observed smoke-test result, repeat as needed")
    p.add_argument("--loaded-build", help="Actual build identity evidence; don't guess from launcher appearance")
    p.set_defaults(func=finish)
    p = sub.add_parser("status", help="Print compact summary without opening logs")
    p.add_argument("--run", type=Path, required=True)
    p.set_defaults(func=status)
    return root


if __name__ == "__main__":
    try:
        arguments = parser().parse_args()
        sys.exit(arguments.func(arguments) or 0)
    except (ValueError, OSError, KeyError, json.JSONDecodeError) as error:
        print(json.dumps({"status": "error", "error": str(error)}, separators=(",", ":")))
        sys.exit(1)
