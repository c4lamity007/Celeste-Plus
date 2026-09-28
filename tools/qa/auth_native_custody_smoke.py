"""Bounded Windows auth-native custody test; no user/game/auth interactions."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import time


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=pathlib.Path, required=True)
    parser.add_argument("--native-dll", type=pathlib.Path, required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    base = pathlib.Path(__file__).resolve().parents[2]
    output = (base / args.output).resolve()
    if not output.is_relative_to(base / "build") or output.exists():
        parser.error("--output must be a fresh directory below this checkout build directory")
    native = args.native_dll.resolve()
    output.mkdir(parents=True)
    commands = []

    def execute(label, argv, timeout):
        started = time.monotonic()
        result = subprocess.run([str(value) for value in argv], cwd=base,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout,
                                creationflags=subprocess.CREATE_NO_WINDOW)
        (output / (label + ".log")).write_bytes(result.stdout)
        commands.append({"name": label, "exit_code": result.returncode,
                         "seconds": round(time.monotonic() - started, 3),
                         "log_sha256": digest(output / (label + ".log"))})
        if result.returncode:
            raise RuntimeError(label + " failed; inspect its bounded local log")
        return result.stdout.decode("utf-8", errors="replace").strip()

    java = args.java_home.resolve() / "bin"
    sources = [base / "runtime-overlay/src/main/java/dev/celestebeta/host/AuthNativeBridge.java",
               base / "tools/qa/auth-native-custody/dev/celestebeta/host/CustodyCheck.java"]
    fixture = base / "tools/qa/auth-native-custody/fixture/NativeOwner.java"
    execute("compile-parent", [java / "javac.exe", "-Xlint:all", "-Werror", "-d", output / "classes", *sources], 30)
    execute("compile-child", [java / "javac.exe", "-Xlint:all", "-Werror", "-d", output / "fixture-classes", fixture], 30)
    target = output / "junction-target"
    target.mkdir()
    (target / "unowned.txt").write_text("preserve", encoding="utf-8")
    junction = output / "junction-root"
    quote = lambda path: "'" + str(path).replace("'", "''") + "'"
    execute("junction-fixture", ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
             "New-Item -ItemType Junction -Path " + quote(junction) + " -Target " + quote(target) + " -ErrorAction Stop | Out-Null"], 15)
    text = execute("fixture", [java / "java.exe", "-Xcheck:jni",
                               "-Xbootclasspath/a:" + str(output / "classes"), "-cp", output / "classes",
                               "dev.celestebeta.host.CustodyCheck", output / "cases",
                               native, output / "fixture-classes", junction], 60)
    if not text.startswith("PASS checks=") or not text.endswith("actual_native=true"):
        raise RuntimeError("Fixture did not report completed native lifecycle checks")
    report = {"status": "auth-native-custody-bootstrap-fixture-pass", "native_dll_sha256": digest(native),
              "commands": commands, "result": text,
              "sources": {str(path.relative_to(base)): digest(path) for path in [*sources, fixture, pathlib.Path(__file__)]},
              "limits": ["Canonical bootstrap-defined parent with synthetic local ownership data; no authentication, network, Minecraft, or user process access.",
                         "Real generated JNI DLL loaded/unloaded in three disposable classloaders; translated auth methods are tested separately by the producer fixture.",
                         "Windows JDK21 sharing guard prevents writes/replacement during owner lifetime; basic attributes lack fileKey, so stale cleanup uses creation/modified timestamps, size and SHA256.",
                         "Private current-user root is not a boundary against malicious code executing as the same Windows user; Java path deletion is not native delete-by-handle.",
                         "Explicit GC is only in this bounded test. Production daemon does not force GC and does not promise immediate native unloading."]}
    (output / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
