"""Isolated Windows cleanup-mode smoke tests; never open the launcher UI or attach.

Supply a freshly built executable that implements --owned-bootstrap-cleanup.
Every process and plaintext byte belongs to this synthetic fixture. No license,
authentication request, Minecraft process or pre-existing cache is used.
"""
from __future__ import annotations

import argparse
import ctypes as c
from ctypes import wintypes as w
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import uuid

MODE = "--owned-bootstrap-cleanup"
PREFIX = ".owned-bootstrap-"
CREATE_NO_WINDOW = 0x08000000
PAYLOAD = b"Celeste cleanup fixture: synthetic bytes, not an executable JAR.\n"


def require(value, message):
    if not value:
        raise RuntimeError(message)


class Blob(c.Structure):
    _fields_ = [("size", w.DWORD), ("data", c.POINTER(c.c_ubyte))]


class FileInformation(c.Structure):
    _fields_ = [("attributes", w.DWORD), ("created", w.FILETIME), ("accessed", w.FILETIME),
                ("written", w.FILETIME), ("volume", w.DWORD), ("size_high", w.DWORD),
                ("size_low", w.DWORD), ("links", w.DWORD), ("index_high", w.DWORD), ("index_low", w.DWORD)]


def winapi():
    require(os.name == "nt", "This smoke test requires Windows")
    system = Path(os.environ.get("SystemRoot", "C:/Windows")) / "System32"
    kernel = c.WinDLL(str(system / "kernel32.dll"), use_last_error=True)
    kernel.OpenProcess.argtypes = [w.DWORD, w.BOOL, w.DWORD]
    kernel.OpenProcess.restype = w.HANDLE
    kernel.GetProcessTimes.argtypes = [w.HANDLE] + [c.POINTER(w.FILETIME)] * 4
    kernel.GetProcessTimes.restype = w.BOOL
    kernel.CloseHandle.argtypes = [w.HANDLE]
    kernel.WaitForSingleObject.argtypes = [w.HANDLE, w.DWORD]
    kernel.WaitForSingleObject.restype = w.DWORD
    kernel.TerminateProcess.argtypes = [w.HANDLE, w.UINT]
    kernel.GetExitCodeProcess.argtypes = [w.HANDLE, c.POINTER(w.DWORD)]
    kernel.QueryFullProcessImageNameW.argtypes = [w.HANDLE, w.DWORD, w.LPWSTR, c.POINTER(w.DWORD)]
    kernel.LocalFree.argtypes = [c.c_void_p]
    kernel.PeekNamedPipe.argtypes = [w.HANDLE, c.c_void_p, w.DWORD, c.c_void_p, c.POINTER(w.DWORD), c.c_void_p]
    kernel.CreateFileW.argtypes = [w.LPCWSTR, w.DWORD, w.DWORD, c.c_void_p, w.DWORD, w.DWORD, w.HANDLE]
    kernel.CreateFileW.restype = w.HANDLE
    kernel.GetFileInformationByHandle.argtypes = [w.HANDLE, c.POINTER(FileInformation)]
    return kernel, system


def file_identity(kernel, path):
    handle = kernel.CreateFileW(str(path), 0x80, 7, None, 3, 0, None)
    require(handle not in (None, w.HANDLE(-1).value), "Cannot open fixture file identity")
    try:
        info = FileInformation()
        require(kernel.GetFileInformationByHandle(handle, c.byref(info)), "Cannot inspect fixture file identity")
        return {"volume": info.volume, "index_high": info.index_high, "index_low": info.index_low,
                "created_high": info.created.dwHighDateTime, "created_low": info.created.dwLowDateTime}
    finally:
        kernel.CloseHandle(handle)


def started(kernel, handle):
    created, exited, cpu, user = (w.FILETIME() for _ in range(4))
    require(kernel.GetProcessTimes(handle, c.byref(created), c.byref(exited), c.byref(cpu), c.byref(user)),
            "Cannot verify owned process creation time")
    return (((created.dwHighDateTime << 32) | created.dwLowDateTime) - 116444736000000000) // 10000


def local_app_data(system):
    shell = c.WinDLL(str(system / "shell32.dll"))
    ole = c.WinDLL(str(system / "ole32.dll"))
    shell.SHGetKnownFolderPath.argtypes = [c.c_void_p, w.DWORD, w.HANDLE, c.POINTER(w.LPWSTR)]
    shell.SHGetKnownFolderPath.restype = c.c_long
    ole.CoTaskMemFree.argtypes = [c.c_void_p]
    guid = c.create_string_buffer(uuid.UUID("f1b32785-6fba-4fcf-9d55-7b8e7f157091").bytes_le)
    result = w.LPWSTR()
    require(shell.SHGetKnownFolderPath(guid, 0, None, c.byref(result)) == 0, "Local AppData unavailable")
    try:
        return Path(result.value)
    finally:
        ole.CoTaskMemFree(result)


def protect(system, kernel, plain):
    crypt = c.WinDLL(str(system / "crypt32.dll"), use_last_error=True)
    crypt.CryptProtectData.argtypes = [c.POINTER(Blob), w.LPCWSTR, c.c_void_p, c.c_void_p,
                                      c.c_void_p, w.DWORD, c.POINTER(Blob)]
    storage = (c.c_ubyte * len(plain)).from_buffer_copy(plain)
    incoming, outgoing = Blob(len(plain), storage), Blob()
    require(crypt.CryptProtectData(c.byref(incoming), None, None, None, None, 1, c.byref(outgoing)),
            "Synthetic ownership DPAPI protection failed")
    try:
        return c.string_at(outgoing.data, outgoing.size)
    finally:
        kernel.LocalFree(outgoing.data)


def private_directory(system, kernel, path):
    """Protect only our newly created fixture directory; never modify ancestor ACLs."""
    advapi = c.WinDLL(str(system / "advapi32.dll"), use_last_error=True)
    advapi.OpenProcessToken.argtypes = [w.HANDLE, w.DWORD, c.POINTER(w.HANDLE)]
    advapi.GetTokenInformation.argtypes = [w.HANDLE, c.c_int, c.c_void_p, w.DWORD, c.POINTER(w.DWORD)]
    advapi.ConvertSidToStringSidW.argtypes = [c.c_void_p, c.POINTER(w.LPWSTR)]
    advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = [w.LPCWSTR, w.DWORD, c.POINTER(c.c_void_p), c.c_void_p]
    advapi.SetFileSecurityW.argtypes = [w.LPCWSTR, w.DWORD, c.c_void_p]
    token, sid, descriptor = w.HANDLE(), w.LPWSTR(), c.c_void_p()
    require(advapi.OpenProcessToken(w.HANDLE(-1), 8, c.byref(token)), "Cannot obtain fixture account identity")
    try:
        size = w.DWORD()
        advapi.GetTokenInformation(token, 1, None, 0, c.byref(size))
        require(0 < size.value < 65536, "Invalid account identity size")
        data = c.create_string_buffer(size.value)
        require(advapi.GetTokenInformation(token, 1, data, size, c.byref(size)), "Cannot read fixture account identity")
        require(advapi.ConvertSidToStringSidW(c.cast(data, c.POINTER(c.c_void_p))[0], c.byref(sid)), "Cannot format fixture identity")
        require(advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW(
            f"D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;{sid.value})", 1, c.byref(descriptor), None), "Cannot create private fixture ACL")
        require(advapi.SetFileSecurityW(str(path), 0x80000004, descriptor), "Cannot protect fixture directory")
    finally:
        if descriptor:
            kernel.LocalFree(descriptor)
        if sid:
            kernel.LocalFree(sid)
        kernel.CloseHandle(token)


def read_ack(kernel, stream, timeout=10):
    import msvcrt
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        count = w.DWORD()
        require(kernel.PeekNamedPipe(w.HANDLE(msvcrt.get_osfhandle(stream.fileno())), None, 0,
                                    None, c.byref(count), None), "Cleanup helper closed before acknowledgement")
        if count.value:
            require(stream.read(1) == b"R", "Unexpected cleanup acknowledgement")
            return
        time.sleep(0.02)
    raise RuntimeError("Cleanup helper acknowledgement timed out")


def fixture_launcher():
    """Own child launcher exits after acknowledgement; the actual helper must survive."""
    kernel, _ = winapi()
    value = json.loads(sys.stdin.buffer.readline(65536))
    process = subprocess.Popen([value["binary"], MODE, value["identity"], value["directory"]],
                               stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               creationflags=CREATE_NO_WINDOW)
    try:
        read_ack(kernel, process.stdout)
        print(json.dumps({"pid": process.pid, "started": started(kernel, w.HANDLE(process._handle))}), flush=True)
    except BaseException:
        process.kill()
        process.wait(timeout=5)
        raise
    finally:
        process.stdout.close()


def opened_helper(kernel, binary, pid, creation):
    handle = kernel.OpenProcess(0x00100000 | 0x1000 | 1, False, pid)
    require(handle, "Acknowledged helper did not survive its launcher")
    try:
        require(started(kernel, handle) == creation, "Helper process identity changed")
        size = w.DWORD(32768)
        image = c.create_unicode_buffer(size.value)
        require(kernel.QueryFullProcessImageNameW(handle, 0, image, c.byref(size)), "Cannot verify helper executable")
        require(Path(image.value).resolve() == binary, "Helper executable differs")
        require(kernel.WaitForSingleObject(handle, 0) == 258, "Helper exited before its target")
        return handle
    except BaseException:
        kernel.CloseHandle(handle)
        raise


def smoke(binary: Path, output: Path):
    kernel, system = winapi()
    require(binary.is_file() and binary.suffix.lower() == ".exe", "Supply the actual cleanup-capable executable")
    require(not output.exists(), "Use a fresh output directory")
    output.mkdir(parents=True)
    binary_sha256 = hashlib.sha256(binary.read_bytes()).hexdigest()
    base = local_app_data(system) / "Celeste" / "Plus"
    require(base.is_dir() and not base.is_symlink(), "Expected existing local Plus cache root is unavailable")
    results = []
    for case in ("parent-exit", "duplicate-helper", "unrelated-preserved", "missing-receipt", "forged-receipt",
                 "wrong-start", "partial-owned-write", "replaced-file-preserved"):
        target = subprocess.Popen([sys.executable, "-I", "-c", "import sys; sys.stdin.buffer.read(1)"],
                                  stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                  creationflags=CREATE_NO_WINDOW)
        helpers, root, directory = [], None, None
        root_created = directory_created = False
        try:
            timestamp = started(kernel, w.HANDLE(target._handle)) + (1 if case == "wrong-start" else 0)
            root = base / f"{target.pid}-{timestamp}"
            root.mkdir(exist_ok=False)
            root_created = True
            private_directory(system, kernel, root)
            directory = root / (PREFIX + secrets.token_hex(24))
            directory.mkdir()
            directory_created = True
            owner = {"schema": 2, "product": "celeste_plus", "root": "\\\\?\\" + str(root.resolve()),
                     "directory": directory.name, "sha256": hashlib.sha256(PAYLOAD).hexdigest(),
                     "size": len(PAYLOAD), "pid": target.pid, "started": timestamp}
            jar = directory / "bootstrap-a.jar"
            expected_bytes = PAYLOAD[:17] if case in {"partial-owned-write", "replaced-file-preserved"} else PAYLOAD
            jar.write_bytes(expected_bytes)
            if case in {"partial-owned-write", "replaced-file-preserved"}:
                owner.update(schema=3, file_identity=file_identity(kernel, jar), write_complete=False)
            receipt = directory / "owner.cst"
            if case != "missing-receipt":
                receipt.write_bytes(b"forged fixture bytes" if case == "forged-receipt" else
                                    protect(system, kernel, json.dumps(owner, separators=(",", ":")).encode()))
            extra = directory / "unrelated.txt"
            if case == "unrelated-preserved":
                extra.write_bytes(b"Preserve this unrelated fixture file.")
            negative = case in {"missing-receipt", "forged-receipt", "wrong-start"}
            if negative:
                command = subprocess.Popen([str(binary), MODE, root.name, directory.name], stdin=subprocess.DEVNULL,
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=CREATE_NO_WINDOW)
                try:
                    stdout, stderr = command.communicate(timeout=12)
                except BaseException:
                    command.kill(); command.wait(timeout=5)
                    raise
                require(command.returncode != 0 and stdout != b"R", "Invalid ownership was acknowledged")
                require(jar.read_bytes() == PAYLOAD, "Invalid request changed fixture bytes")
            else:
                for number in range(2 if case == "duplicate-helper" else 1):
                    launcher = subprocess.Popen([sys.executable, "-I", str(Path(__file__).resolve()), "--fixture-launcher"],
                                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                                creationflags=CREATE_NO_WINDOW)
                    request = {"binary": str(binary), "identity": root.name, "directory": directory.name}
                    try:
                        stdout, stderr = launcher.communicate((json.dumps(request) + "\n").encode(), timeout=15)
                    except BaseException:
                        launcher.kill(); launcher.wait(timeout=5)
                        raise
                    (output / f"{case}-launcher-{number}.log").write_bytes(stderr)
                    require(launcher.returncode == 0, "Fixture launcher failed; no cleanup ownership confirmed")
                    identity = json.loads(stdout)
                    helpers.append(opened_helper(kernel, binary, identity["pid"], identity["started"]))
                require(target.poll() is None and jar.read_bytes() == expected_bytes, "Helper removed data while target was alive")
                if case == "replaced-file-preserved":
                    replacement = directory / "replacement.tmp"
                    replacement.write_bytes(PAYLOAD)
                    require(file_identity(kernel, replacement) != owner["file_identity"], "Replacement fixture needs a distinct file")
                    jar.unlink()
                    replacement.rename(jar)
            target.stdin.close()
            target.wait(timeout=5)
            if negative:
                require(jar.read_bytes() == PAYLOAD, "Rejected cleanup later changed bytes")
            else:
                for handle in helpers:
                    require(kernel.WaitForSingleObject(handle, 15000) == 0, "Cleanup helper failed to end after target exit")
                    code = w.DWORD()
                    require(kernel.GetExitCodeProcess(handle, c.byref(code)), "Cannot inspect helper exit status")
                    require((code.value != 0) if case == "replaced-file-preserved" else code.value == 0,
                            "Cleanup helper returned an unexpected status")
                if case == "replaced-file-preserved":
                    require(jar.read_bytes() == PAYLOAD and receipt.is_file(), "Cleanup deleted a replaced file")
                else:
                    require(not jar.exists() and not receipt.exists(), "Owned plaintext or receipt survived target exit")
                if case == "unrelated-preserved":
                    require(extra.read_bytes() == b"Preserve this unrelated fixture file.", "Unrelated bytes changed")
            results.append({"case": case, "status": "passed", "helpers": len(helpers)})
        finally:
            if target.poll() is None:
                target.kill(); target.wait(timeout=5)
            for handle in helpers:
                if kernel.WaitForSingleObject(handle, 0) == 258:
                    kernel.TerminateProcess(handle, 1)
                    kernel.WaitForSingleObject(handle, 5000)
                kernel.CloseHandle(handle)
            # Exact own fixture paths only. Never recurse through existing caches.
            if directory_created:
                require(root.resolve().parent == base.resolve() and directory.parent == root
                        and not root.is_symlink() and not directory.is_symlink()
                        and directory.resolve().parent == root.resolve(), "Fixture scope changed")
                for name in ("bootstrap-a.jar", "owner.cst", "unrelated.txt", "replacement.tmp"):
                    (directory / name).unlink(missing_ok=True)
                if directory.exists():
                    directory.rmdir()
            if root_created and root.exists():
                root.rmdir()
    require(hashlib.sha256(binary.read_bytes()).hexdigest() == binary_sha256, "Executable changed during smoke test")
    report = {"schema": "celeste-bootstrap-cleanup-smoke-1", "status": "passed",
              "binary_sha256": binary_sha256, "cases": results,
              "scope": "actual cleanup executable; own synthetic processes/DPAPI receipts only",
              "game_or_auth_exercised": False, "protected_artifact_approval": False}
    (output / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps(report))


if __name__ == "__main__":
    if sys.argv[1:] == ["--fixture-launcher"]:
        fixture_launcher()
    else:
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument("--binary", type=Path, required=True)
        parser.add_argument("--output", type=Path, required=True)
        arguments = parser.parse_args()
        smoke(arguments.binary.resolve(strict=True), arguments.output.absolute())
