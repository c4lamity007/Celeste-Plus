from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import subprocess
import threading
import time
import uuid

PINNED_EXECUTABLE = "e80ca1ca979cdc455cbe7b9d1ee1ee1661eea74127f7d12ad0d34cd47450cdbd"
MAX_BINARY = 128 * 1024 * 1024
MAX_MAP = 16 * 1024 * 1024
MAX_PROJECT = 2 * 1024 * 1024
MAX_LOG = 64 * 1024
EXIT_CODES = {0: "success", 1: "invalid-project", 2: "cannot-open-input",
              3: "no-protection-blocks", 4: "invalid-block", 5: "protection-error",
              6: "cannot-write-output", 7: "incompatible-PE"}
HEX = re.compile(r"[0-9a-f]{64}\Z")
JOB = re.compile(r"[0-9a-f]{32}\Z")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def parse(data):
    def unique(pairs):
        result = {}
        for name, value in pairs:
            require(name not in result, "Duplicate JSON field")
            result[name] = value
        return result
    result = json.loads(data.decode("utf-8-sig"), object_pairs_hook=unique)
    require(isinstance(result, dict), "JSON object required")
    return result


def safe_path(value, *, exists=True, directory=False):
    require(isinstance(value, (str, Path)), "Filesystem path required")
    text = str(value)
    require(text and not text.startswith(("\\\\", "//")) and "\x00" not in text,
            "UNC, device and empty paths are not supported")
    path = Path(text)
    require(path.is_absolute() and ".." not in path.parts, "Absolute normalized path required")
    require(":" not in text[len(path.drive):], "Alternate streams are not supported")
    for part in (*reversed(path.parents), path):
        require(not part.is_symlink() and not getattr(part, "is_junction", lambda: False)(),
                "Symlinks and junctions are forbidden")
    require(path.resolve(strict=exists) == path, "Noncanonical path refused")
    if exists:
        info = path.stat()
        require(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode),
                "Expected a directory" if directory else "Expected a regular file")
        if not directory:
            require(info.st_nlink == 1, "Hard-linked files are forbidden")
    return path


def read(path, maximum):
    path = safe_path(path)
    before = path.stat()
    require(0 < before.st_size <= maximum, "File size is outside the permitted bound")
    with path.open("rb") as stream:
        opened = os.fstat(stream.fileno())
        require((before.st_dev, before.st_ino) == (opened.st_dev, opened.st_ino), "Input identity changed")
        data = stream.read(maximum + 1)
        after = os.fstat(stream.fileno())
    final = path.stat()
    identity = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_nlink)
    require(len(data) == before.st_size and identity(before) == identity(after) == identity(final),
            "Input changed while reading")
    return data


def pe(data):
    require(len(data) >= 256 and data[:2] == b"MZ", "Native PE input required")
    offset = struct.unpack_from("<I", data, 0x3c)[0]
    require(64 <= offset <= len(data) - 24 and data[offset:offset + 4] == b"PE\0\0", "Invalid PE header")
    machine, count = struct.unpack_from("<HH", data, offset + 4)
    optional, flags = struct.unpack_from("<HH", data, offset + 20)
    require(machine == 0x8664 and 1 <= count <= 96 and optional >= 112
            and offset + 24 + optional + count * 40 <= len(data)
            and struct.unpack_from("<H", data, offset + 24)[0] == 0x20b,
            "Only bounded Windows x64 PE files are supported")
    require(flags & 0x2000, "This scoped integration accepts DLLs only")
    return {"architecture": "x64", "dll": True, "sections": count}


def selections(mapping, crm):
    text = mapping.decode("utf-8-sig")
    lines = crm.decode("utf-8-sig").splitlines()
    require(1 <= len(lines) <= 128, "CRM must select between one and 128 functions")
    tokens = set(text.split())
    result = []
    for line in lines:
        fields = line.split("|")
        require(len(fields) == 4 and fields[0] == "" and fields[2:] == ["1", "1"],
                "Only documented exact-symbol CRM records are accepted")
        symbol = fields[1]
        require(0 < len(symbol) <= 4096 and symbol.isascii()
                and not any(c.isspace() or ord(c) < 32 for c in symbol)
                and symbol in tokens, "CRM symbol is absent from MAP or invalid")
        require(symbol not in result, "Duplicate CRM symbol")
        result.append(symbol)
    return result


def inspect_template(data, specification):
    require(sha(data) == specification["sha256"], "Reviewed template changed")
    # Opaque projects are explicitly non-executable until an actual GUI-created
    # project format has an independently reviewed parser. Hash pins alone do
    # not establish absence of external files, hooks or post-build execution.
    require(specification.get("format") == "opaque", "Unsupported project format")
    return {"sha256": sha(data), "bytes": len(data), "format": "opaque",
            "compile_allowed": False,
            "blockers": ["Project format and external references have not been reviewed"],
            "content_executed": False}


class Configuration:
    def __init__(self, value):
        require(set(value) == {"schema", "executable", "executable_sha256", "work_root",
                              "allowed_roots", "templates", "timeout_seconds"}, "Unknown configuration field")
        require(value["schema"] == "codevirtualizer-control-1", "Unsupported configuration")
        require(value["executable_sha256"] == PINNED_EXECUTABLE, "Unreviewed compiler identity")
        self.executable = safe_path(value["executable"])
        require(self.executable.name.lower() == "virtualizer.exe", "Expected Virtualizer.exe")
        self.work_root = safe_path(value["work_root"], directory=True)
        roots = value["allowed_roots"]
        require(isinstance(roots, list) and 1 <= len(roots) <= 16, "Explicit allowed roots required")
        self.allowed_roots = tuple(safe_path(p, directory=True) for p in roots)
        require(len(set(self.allowed_roots)) == len(self.allowed_roots)
                and all(p != Path(p.anchor) and p != Path.home() for p in self.allowed_roots),
                "Duplicate or excessively broad input roots refused")
        self.templates = value["templates"]
        require(isinstance(self.templates, dict) and len(self.templates) <= 8, "Invalid template inventory")
        for name, spec in self.templates.items():
            require(re.fullmatch(r"[a-z0-9_-]{1,48}", name)
                    and isinstance(spec, dict) and set(spec) == {"path", "sha256", "format"}
                    and HEX.fullmatch(spec["sha256"]) and spec["format"] == "opaque", "Invalid template entry")
            self.allowed(spec["path"])
        self.timeout = value["timeout_seconds"]
        require(type(self.timeout) is int and 10 <= self.timeout <= 1800, "Timeout must be 10–1800 seconds")
        self.digest = sha(canonical(value))

    def allowed(self, value):
        path = safe_path(value)
        require(any(path.is_relative_to(root) for root in self.allowed_roots), "Input is outside allowed roots")
        require(not path.is_relative_to(self.work_root), "Managed outputs cannot be selected as original inputs")
        return path

    def compiler(self):
        require(sha(read(self.executable, MAX_BINARY)) == PINNED_EXECUTABLE, "Compiler executable changed")


class ProcessRunner:
    """Only called with arguments constructed internally; outputs are never launched."""
    def __call__(self, args, cwd, timeout, stop, output=None):
        env = {key: value for key, value in os.environ.items()
               if key.upper() in {"SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE",
                                  "LOCALAPPDATA", "APPDATA", "PROGRAMDATA"}}
        if os.name == "nt":
            env["PATH"] = str(Path(env.get("SYSTEMROOT", "C:/Windows")) / "System32")
        process = subprocess.Popen(args, cwd=cwd, env=env, shell=False, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        log = bytearray()
        total = 0
        lock = threading.Lock()

        def consume():
            nonlocal total
            try:
                while True:
                    block = process.stdout.read(4096)
                    if not block:
                        break
                    with lock:
                        total += len(block)
                        log.extend(block)
                        if len(log) > MAX_LOG:
                            del log[:-MAX_LOG]
            finally:
                process.stdout.close()

        reader = threading.Thread(target=consume, name="cv-log-reader", daemon=True)
        reader.start()
        start = time.monotonic()
        reason = None
        try:
            while process.poll() is None:
                if stop.is_set():
                    reason = "server-stopped"
                elif time.monotonic() - start >= timeout:
                    reason = "deadline-exceeded"
                elif output is not None and output.exists() and output.stat().st_size > MAX_BINARY:
                    reason = "output-size-exceeded"
                if reason:
                    process.kill()
                    break
                stop.wait(0.05)
            process.wait(timeout=10)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
            reader.join(timeout=2)
        # A descendant retaining the pipe must not make stdout.close() block the
        # caller indefinitely. The reader owns closing it. Retain the admission
        # lock for explicit operator review if the pipe has not terminated.
        pending_pipe = reader.is_alive()
        if pending_pipe:
            reason = reason or "compiler-log-pipe-did-not-close"
        with lock:
            text = bytes(log).decode("utf-8", errors="replace")
        return {"exit_code": process.returncode, "failure": reason, "log": text,
                "log_bytes": total, "log_truncated": total > MAX_LOG,
                "operator_cleanup_required": pending_pipe,
                "elapsed_seconds": round(time.monotonic() - start, 3)}


class JobStore:
    def __init__(self, config, runner=None):
        self.config = config if isinstance(config, Configuration) else Configuration(config)
        self.runner = runner or ProcessRunner()
        self.stop = threading.Event()
        self.state_lock = threading.RLock()
        self.active_thread = None

    def status(self):
        self.config.compiler()
        return {"executable": str(self.config.executable), "executable_sha256": PINNED_EXECUTABLE,
                "version_from_review": "3.2.4.35", "edition": "demo", "configuration_sha256": self.config.digest,
                "work_root": str(self.config.work_root), "allowed_roots": list(map(str, self.config.allowed_roots)),
                "templates": sorted(self.config.templates), "compile_busy": (self.config.work_root / ".compiler.lock").exists(),
                "output_execution_supported": False, "protection_verified": False}

    def inspect(self, template_id):
        require(template_id in self.config.templates, "Unknown reviewed template")
        spec = self.config.templates[template_id]
        path = self.config.allowed(spec["path"])
        require(path.suffix.lower() == ".cv", "Expected .cv template")
        return {"template_id": template_id, **inspect_template(read(path, MAX_PROJECT), spec)}

    def prepare(self, input_path, map_path, crm_path, template_id):
        self.config.compiler()
        review = self.inspect(template_id)
        supplied = {"input.dll": self.config.allowed(input_path), "input.map": self.config.allowed(map_path),
                    "input.CRM": self.config.allowed(crm_path),
                    "project.cv": self.config.allowed(self.config.templates[template_id]["path"])}
        require([supplied[n].suffix.lower() for n in supplied] == [".dll", ".map", ".crm", ".cv"], "Unexpected input extension")
        require(supplied["input.map"].stem == supplied["input.CRM"].stem, "MAP and CRM basenames must match")
        limits = {"input.dll": MAX_BINARY, "input.map": MAX_MAP, "input.CRM": 1024 * 1024, "project.cv": MAX_PROJECT}
        data = {name: read(path, limits[name]) for name, path in supplied.items()}
        metadata = pe(data["input.dll"])
        chosen = selections(data["input.map"], data["input.CRM"])
        inspect_template(data["project.cv"], self.config.templates[template_id])
        job_id = uuid.uuid4().hex
        directory = safe_path(self.config.work_root / job_id, exists=False)
        directory.mkdir(mode=0o700)
        for name, content in data.items():
            with (directory / name).open("xb") as stream:
                stream.write(content)
        record = {"schema": "codevirtualizer-job-1", "job_id": job_id, "state": "prepared",
                  "configuration_sha256": self.config.digest, "template_id": template_id,
                  "input_pe": metadata, "selected_functions": chosen,
                  "inputs": {name: {"sha256": sha(content), "bytes": len(content)} for name, content in data.items()},
                  "review": review, "output": "output/protected.dll", "protection_verified": False,
                  "output_executed": False}
        (directory / "output").mkdir()
        self.save(directory, record)
        return self.result(job_id)

    def save(self, directory, record):
        target = safe_path(directory / "job.json", exists=(directory / "job.json").exists())
        temporary = safe_path(directory / "job.tmp", exists=False)
        with temporary.open("xb") as stream:
            stream.write(canonical(record))
        os.replace(temporary, target)

    def verify(self, job_id):
        require(isinstance(job_id, str) and JOB.fullmatch(job_id), "Invalid managed job identifier")
        directory = safe_path(self.config.work_root / job_id, directory=True)
        record = parse(read(directory / "job.json", MAX_PROJECT))
        require(record.get("schema") == "codevirtualizer-job-1" and record.get("job_id") == job_id
                and record.get("configuration_sha256") == self.config.digest
                and record.get("output") == "output/protected.dll", "Job identity changed")
        require(set(record["inputs"]) == {"input.dll", "input.map", "input.CRM", "project.cv"}, "Unexpected job inputs")
        expected = set(record["inputs"]) | {"job.json", "compiler.log", "output"}
        require(all(path.name in expected for path in directory.iterdir()), "Unreviewed file in managed job")
        for name, expected in record["inputs"].items():
            content = read(directory / name, MAX_BINARY if name == "input.dll" else MAX_MAP)
            require({"sha256": sha(content), "bytes": len(content)} == expected, "Prepared input changed")
        spec = self.config.templates.get(record["template_id"])
        require(spec is not None, "Job template is no longer configured")
        review = inspect_template(read(directory / "project.cv", MAX_PROJECT), spec)
        require(record["review"] == {"template_id": record["template_id"], **review}, "Template review changed")
        require(pe(read(directory / "input.dll", MAX_BINARY)) == record["input_pe"], "Input architecture changed")
        require(selections(read(directory / "input.map", MAX_MAP), read(directory / "input.CRM", 1024 * 1024))
                == record["selected_functions"], "Function selection changed")
        safe_path(directory / "output", directory=True)
        require(all(path.name == "protected.dll" for path in (directory / "output").iterdir()), "Unexpected compiler output file")
        return directory, record

    def compile(self, job_id):
        with self.state_lock:
            directory, record = self.verify(job_id)
            self.config.compiler()
            require(not self.stop.is_set(), "Server is stopping")
            require(record["state"] == "prepared", "Compilation is one-shot; prepare a new job")
            require(record["review"]["compile_allowed"], "Compilation blocked: project format/external references need review")
            output = safe_path(directory / record["output"], exists=False)
            require(not output.exists(), "Output must not already exist")
            lock_path = safe_path(self.config.work_root / ".compiler.lock", exists=False)
            try:
                fd = os.open(lock_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            except FileExistsError:
                raise ValueError("Compiler is busy or a previous job needs operator review") from None
            with os.fdopen(fd, "wb") as stream:
                stream.write(job_id.encode())
            record["state"] = "running"
            try:
                self.save(directory, record)
                thread = threading.Thread(target=self._compile, args=(directory, record, lock_path),
                                          name="cv-owned-compiler", daemon=True)
                self.active_thread = thread
                thread.start()
            except BaseException:
                if read(lock_path, 64) == job_id.encode():
                    lock_path.unlink()
                raise
            return {"job_id": job_id, "state": "running", "protection_verified": False, "output_executed": False}

    def _compile(self, directory, record, lock_path):
        try:
            self.verify(record["job_id"])
            self.config.compiler()
            output = safe_path(directory / record["output"], exists=False)
            require(not output.exists(), "Output appeared before compilation")
            argv = [str(self.config.executable), "/protect", str(directory / "project.cv"),
                    "/inputfile", str(directory / "input.dll"), "/outputfile", str(output), "/shareconsole"]
            result = self.runner(argv, directory, self.config.timeout, self.stop, output)
            record["compiler"] = {k: v for k, v in result.items() if k != "log"}
            record["compiler"]["exit_meaning"] = EXIT_CODES.get(result["exit_code"], "unknown-exit-code")
            log = result.get("log", "").encode("utf-8")[-MAX_LOG:]
            (directory / "compiler.log").write_bytes(log)
            self.verify(record["job_id"])
            require(result["exit_code"] == 0 and not result["failure"], "Compiler did not complete successfully")
            content = read(output, MAX_BINARY)
            pe(content)
            record["output_file"] = {"sha256": sha(content), "bytes": len(content)}
            self.config.compiler()
            check = self.runner([str(self.config.executable), "/checkprotected", str(output)],
                                directory, min(self.config.timeout, 30), self.stop)
            record["auxiliary_check"] = {k: v for k, v in check.items() if k != "log"}
            require(check["exit_code"] == 0 and not check["failure"], "Auxiliary protection marker check failed")
            require(sha(read(output, MAX_BINARY)) == record["output_file"]["sha256"], "Output changed during inspection")
            self.verify(record["job_id"])
            record["state"] = "compiled-not-approved"
        except Exception as failure:
            record["state"] = "failed"
            record["failure"] = str(failure)[:512]
        finally:
            with self.state_lock:
                self.save(directory, record)
                needs_cleanup = any(record.get(name, {}).get("operator_cleanup_required")
                                    for name in ("compiler", "auxiliary_check"))
                if not needs_cleanup and read(lock_path, 64) == record["job_id"].encode():
                    lock_path.unlink()

    def result(self, job_id):
        with self.state_lock:
            directory, record = self.verify(job_id)
            output = directory / record["output"]
            result = dict(record)
            result["output_path"] = str(output)
            if "output_file" in record:
                content = read(output, MAX_BINARY)
                require({"sha256": sha(content), "bytes": len(content)} == record["output_file"], "Output changed")
            log = directory / "compiler.log"
            if log.exists() and log.stat().st_size:
                result["log_tail"] = read(log, MAX_LOG)[-8192:].decode("utf-8", errors="replace")
                result["log_is_untrusted"] = True
            if record["state"] == "running" and (self.active_thread is None or not self.active_thread.is_alive()):
                result["process_state"] = "unknown-after-restart-do-not-retry"
            return result

    def close(self):
        self.stop.set()
        thread = self.active_thread
        if thread is not None:
            thread.join(timeout=5)
