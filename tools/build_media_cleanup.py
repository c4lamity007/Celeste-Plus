"""Compile the reviewed existing Windows media cleanup shim into a fresh directory.

Requires installed MSVC x64 tools and JDK headers. Does not install tools, alter
the prepared runtime, execute the DLL, or approve/release any artifact.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

from prepare_runtime import media_bridge, plain_path, require

CORE_COMMIT = "25b69e69add42462eb88640fad13a725a4a4144d"
SOURCE_SHA256 = "efbb192a7f89e650e0c1aa1e9c194534e176b7af8f1bae773dc0c2567c90047d"
SOURCE = "src/main/native/CelesteMediaCleanup.cpp"
EXPORT = "Java_dev_redstones_mediaplayerinfo_impl_win_WindowsMediaPlayerInfo_cleanup"


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pinned_source(runtime_core: Path) -> Path:
    runtime_core = runtime_core.resolve(strict=True)
    record = json.loads(plain_path(runtime_core, ".celeste-plus-runtime.json").read_bytes())
    require(record.get("schema") == "celeste-plus-prepared-runtime-1" and record.get("complete") is True
            and record.get("coreCommit") == CORE_COMMIT, "Cleanup source requires the reviewed prepared core commit")
    source = plain_path(runtime_core, SOURCE)
    require(sha(source) == SOURCE_SHA256, "Cleanup C++ bytes differ from the reviewed pinned source")
    return source


def compiler_environment(msvc: Path) -> tuple[dict, Path]:
    vcvars = msvc.resolve(strict=True) / "VC/Auxiliary/Build/vcvars64.bat"
    require(vcvars.is_file(), "Installed MSVC x64 environment script is missing")
    # This one fixed cmd invocation only obtains build variables. Refuse cmd
    # metacharacters in the sole interpolated path; never print inherited env.
    require(not any(char in str(vcvars) for char in '\r\n"&|<>^%!'), "Unsupported MSVC script path")
    # A fixed batch avoids cmd /s /c's nested quoting rules for paths with spaces.
    with tempfile.TemporaryDirectory(prefix="celeste-media-msvc-") as temporary:
        script = Path(temporary) / "environment.cmd"
        script.write_text('@call "' + str(vcvars) + '" >nul\n@if errorlevel 1 exit /b 1\n'
                          '@set PATH\n@set INCLUDE\n@set LIB\n', encoding="utf-8")
        result = subprocess.run([str(Path(os.environ.get("SystemRoot", "C:/Windows")) / "System32/cmd.exe"),
                                 "/d", "/c", str(script)], capture_output=True, text=True, timeout=45)
    require(result.returncode == 0, "MSVC environment setup failed; diagnostics withheld")
    environment = os.environ.copy()
    # Windows environment names are case-insensitive; avoid duplicate Path/PATH.
    for name in tuple(environment):
        if name.upper() in {"PATH", "INCLUDE", "LIB", "LIBPATH"}:
            del environment[name]
    found = {}
    for line in result.stdout.splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            if key.upper() in {"PATH", "INCLUDE", "LIB", "LIBPATH"}:
                found[key.upper()] = value
    require({"PATH", "INCLUDE", "LIB"} <= set(found), "MSVC build environment is incomplete")
    environment.update(found)
    return environment, vcvars


def build(runtime_core: Path, java_home: Path, output: Path, msvc: Path) -> dict:
    require(os.name == "nt", "Media cleanup build requires Windows and installed MSVC")
    source = pinned_source(runtime_core)
    java_home = java_home.resolve(strict=True)
    jni = plain_path(java_home, "include/jni.h")
    platform_jni = plain_path(java_home, "include/win32/jni_md.h")
    output = output.absolute()
    require(not output.exists(), "Use a fresh cleanup output directory; existing files are never overwritten")
    environment, vcvars = compiler_environment(msvc)
    # Resolve cl from the explicit vcvars64 PATH, not an unrelated user compiler.
    candidates = [Path(part) / "cl.exe" for part in environment["PATH"].split(os.pathsep) if part]
    compiler = next((path.resolve() for path in candidates if path.is_file()), None)
    require(compiler is not None and compiler.is_relative_to(msvc.resolve(strict=True)), "Selected compiler is outside installed MSVC")
    linker, dumpbin = compiler.with_name("link.exe"), compiler.with_name("dumpbin.exe")
    require(linker.is_file() and dumpbin.is_file(), "MSVC linker/export inspector is missing")
    inputs = {str(path): sha(path) for path in (source, jni, platform_jni, compiler, linker, dumpbin, vcvars)}
    output.mkdir(parents=True, exist_ok=False)
    binary = output / "CelesteMediaCleanup.dll"
    arguments = [str(compiler), "/nologo", "/std:c++20", "/EHsc", "/O2", "/W4", "/WX", "/MT", "/LD",
                 "/I" + str(jni.parent), "/I" + str(platform_jni.parent),
                 "/Fo" + str(output / "CelesteMediaCleanup.obj"), "/Fe:" + str(binary), str(source),
                 "/link", "/MACHINE:X64", "/INCREMENTAL:NO"]
    compiled = subprocess.run(arguments, cwd=output, env=environment, capture_output=True, text=True, timeout=90)
    (output / "compile.log").write_text(compiled.stdout + compiled.stderr, encoding="utf-8", newline="\n")
    require(compiled.returncode == 0, "Cleanup compilation failed; inspect the local compile.log")
    media_bridge(binary, windows=True)
    exports = subprocess.run([str(dumpbin), "/nologo", "/exports", str(binary)], cwd=output,
                             env=environment, capture_output=True, text=True, timeout=20)
    require(exports.returncode == 0 and any(line.split()[-1:] == [EXPORT] for line in exports.stdout.splitlines()),
            "Compiled DLL lacks the exact JNI cleanup export")
    require(all(sha(Path(name)) == digest for name, digest in inputs.items()), "Build inputs changed during compilation")
    report = {"schema": "celeste-media-cleanup-build-1", "status": "compiled-static-verified-not-executed",
              "core_commit": CORE_COMMIT, "source_sha256": SOURCE_SHA256, "input_files": inputs,
              "compiler": str(compiler), "compiler_sha256": inputs[str(compiler)],
              "output": str(binary), "output_sha256": sha(binary), "output_bytes": binary.stat().st_size,
              "jni_export": EXPORT, "architecture": "windows-x64", "command": arguments,
              "compile_log_sha256": sha(output / "compile.log"), "executed": False, "published": False}
    with (output / "receipt.json").open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(report, stream, indent=2)
        stream.write("\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-core", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="Fresh output directory; never an existing DLL or directory")
    parser.add_argument("--msvc", type=Path, default=Path(os.environ.get("ProgramFiles(x86)", "C:/Program Files (x86)")) / "Microsoft Visual Studio/2022/BuildTools",
                        help="Existing Visual Studio BuildTools root; tools are never installed automatically")
    args = parser.parse_args()
    try:
        report = build(args.runtime_core, args.java_home, args.output, args.msvc)
        print(json.dumps({name: report[name] for name in ("status", "source_sha256", "compiler_sha256", "output", "output_sha256", "output_bytes")}))
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        parser.exit(1, "Media cleanup build refused: " + str(error) + "\n")


if __name__ == "__main__":
    main()
