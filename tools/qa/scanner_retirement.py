"""Check the real scanner retirement against a named compiled runtime; no world/GPU."""
from pathlib import Path
import argparse, json, os, subprocess
ROOT = Path(__file__).resolve().parents[2]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-core", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    runtime, output = args.runtime_core.resolve(strict=True), args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    classes = output / "classes"
    classes.mkdir()
    parents = [line for line in (runtime / "build/module-audit-classpath.txt").read_text(encoding="utf-8").splitlines() if line.strip()]
    artifact = runtime / "build/devlibs/celeste-client-1.0.0-dev.jar"
    if not artifact.is_file():
        raise ValueError("Compile the named module runtime first")
    classpath = os.pathsep.join([str(artifact), *parents])
    commands = [
        ("compile", "javac", ["--release", "21", "-proc:none", "-encoding", "UTF-8", "-classpath", classpath,
                              "-d", classes, ROOT / "tools/qa/module-delivery/ScannerRetirementCheck.java"]),
        ("check", "java", ["-ea", "-Xverify:all", "-classpath", str(classes) + os.pathsep + classpath,
                            "fixture.ScannerRetirementCheck"])
    ]
    for name, tool, arguments in commands:
        argfile = output / (name + ".args")
        argfile.write_text("\n".join('"' + str(value).replace("\\", "/") + '"' for value in arguments), encoding="utf-8")
        binary = args.java_home / "bin" / (tool + (".exe" if os.name == "nt" else ""))
        result = subprocess.run([str(binary), "@" + str(argfile)], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=40)
        (output / (name + ".log")).write_text(result.stdout + result.stderr, encoding="utf-8")
        if result.returncode:
            raise RuntimeError("Scanner retirement " + name + " failed; inspect its local log")
        if name == "check":
            report = json.loads(result.stdout.strip().splitlines()[-1])
            report["boundary"] = "actual scanner, controlled worker exit; no world/GPU or game verification"
            (output / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(report))

if __name__ == "__main__":
    main()
