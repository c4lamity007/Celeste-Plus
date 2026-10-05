from __future__ import annotations

import io
import json
import os
from pathlib import Path
import struct
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from cv_control import core


def dll_bytes():
    data = bytearray(1024)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 0x3c, 0x80)
    data[0x80:0x84] = b"PE\0\0"
    struct.pack_into("<HH", data, 0x84, 0x8664, 1)
    struct.pack_into("<HH", data, 0x94, 240, 0x2000)
    struct.pack_into("<H", data, 0x98, 0x20b)
    return bytes(data)


class Fixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="cv-synthetic-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.inputs = self.root / "inputs"
        self.inputs.mkdir()
        self.jobs = self.root / "jobs"
        self.jobs.mkdir()
        self.exe = self.root / "Virtualizer.exe"
        self.exe.write_bytes(b"synthetic compiler; never executed")
        self.pin = patch.object(core, "PINNED_EXECUTABLE", core.sha(self.exe.read_bytes()))
        self.pin.start()
        self.addCleanup(self.pin.stop)
        self.dll = self.inputs / "native.dll"
        self.dll.write_bytes(dll_bytes())
        self.map = self.inputs / "native.map"
        self.map.write_text("0001:00000000 function_one 0000000140001000 f native.obj\n", encoding="utf-8")
        self.crm = self.inputs / "native.CRM"
        self.crm.write_text("|function_one|1|1\n", encoding="utf-8")
        self.template = self.inputs / "template.cv"
        self.template.write_bytes(b"opaque synthetic GUI project")
        self.config = {"schema": "codevirtualizer-control-1", "executable": str(self.exe),
                       "executable_sha256": core.PINNED_EXECUTABLE, "work_root": str(self.jobs),
                       "allowed_roots": [str(self.inputs)], "templates": {"fixture": {
                           "path": str(self.template), "sha256": core.sha(self.template.read_bytes()), "format": "opaque"}},
                       "timeout_seconds": 10}
        self.calls = []

        def runner(args, cwd, timeout, stop, output=None):
            self.calls.append(args)
            if output is not None:
                output.write_bytes(dll_bytes())
            return {"exit_code": 0, "failure": None, "log": "synthetic success\n", "elapsed_seconds": 0,
                    "log_bytes": 18, "log_truncated": False}
        self.runner = runner
        self.store = core.JobStore(self.config, self.runner)
        self.addCleanup(self.store.close)

    def prepare(self):
        return self.store.prepare(str(self.dll), str(self.map), str(self.crm), "fixture")

    def reviewed_fixture(self):
        # Only tests replace the unknown-format gate. No config option or MCP
        # tool can do this; production compilation remains blocked.
        original = core.inspect_template

        def review(data, spec):
            result = original(data, spec)
            result["compile_allowed"] = True
            result["blockers"] = []
            return result
        patched = patch.object(core, "inspect_template", side_effect=review)
        patched.start()
        self.addCleanup(patched.stop)

    def finish(self):
        self.store.active_thread.join(timeout=3)
        self.assertFalse(self.store.active_thread.is_alive())


class GuardTests(Fixture):
    def test_status_never_runs_compiler(self):
        self.assertFalse(self.store.status()["output_execution_supported"])
        self.assertEqual([], self.calls)

    def test_unknown_project_is_not_a_security_approval(self):
        result = self.store.inspect("fixture")
        self.assertFalse(result["compile_allowed"])
        self.assertTrue(result["blockers"])

    def test_opaque_prepare_copies_and_blocks_execution(self):
        before = self.dll.read_bytes()
        job = self.prepare()
        self.assertEqual("prepared", job["state"])
        self.assertEqual(before, self.dll.read_bytes())
        self.assertFalse(Path(job["output_path"]).exists())
        with self.assertRaisesRegex(ValueError, "blocked"):
            self.store.compile(job["job_id"])
        self.assertEqual([], self.calls)

    def test_nonallowlisted_input_is_refused(self):
        foreign = self.root / "foreign.dll"
        foreign.write_bytes(dll_bytes())
        with self.assertRaisesRegex(ValueError, "outside"):
            self.store.prepare(str(foreign), str(self.map), str(self.crm), "fixture")
        self.assertEqual([], list(self.jobs.iterdir()))

    def test_changed_compiler_and_template_are_refused(self):
        self.exe.write_bytes(b"replaced")
        with self.assertRaisesRegex(ValueError, "Compiler"):
            self.store.status()
        self.template.write_bytes(b"altered template")
        with self.assertRaisesRegex(ValueError, "template changed"):
            self.store.inspect("fixture")

    def test_config_rejects_unknown_keys_or_arbitrary_executable_pin(self):
        for delta in [{"command": "cmd.exe"}, {"executable_sha256": "0" * 64}]:
            with self.assertRaises(ValueError):
                core.Configuration({**self.config, **delta})

    def test_path_escape_device_and_relative_paths_refused(self):
        for path in ["../native.dll", str(self.inputs / ".." / "native.dll"), "\\\\server\\share\\x", "C:/file:stream"]:
            with self.assertRaises(ValueError):
                self.store.config.allowed(path)
        for name in ["..", "../../x", "", "C:/job", "f" * 33]:
            with self.assertRaises(ValueError):
                self.store.result(name)

    def test_hardlink_input_refused(self):
        alias = self.inputs / "alias.dll"
        os.link(self.dll, alias)
        with self.assertRaisesRegex(ValueError, "Hard-linked"):
            self.prepare()

    def test_symlink_refused_if_platform_allows_creation(self):
        link = self.inputs / "alias.dll"
        try:
            link.symlink_to(self.dll)
        except OSError:
            self.skipTest("This Windows account cannot create test symlinks")
        with self.assertRaisesRegex(ValueError, "Symlinks"):
            self.store.config.allowed(link)

    def test_pe_architecture_and_dll_contract(self):
        for data in [b"not PE", dll_bytes()[:300]]:
            with self.assertRaises(ValueError):
                core.pe(data)
        data = bytearray(dll_bytes())
        struct.pack_into("<H", data, 0x84, 0x14c)
        with self.assertRaisesRegex(ValueError, "x64"):
            core.pe(data)
        data = bytearray(dll_bytes())
        struct.pack_into("<H", data, 0x96, 0)
        with self.assertRaisesRegex(ValueError, "DLLs"):
            core.pe(data)

    def test_crm_requires_exact_unique_map_symbols_and_documented_fields(self):
        mapping = self.map.read_bytes()
        for data in [b"|missing|1|1", b"|function_one|1|1\n|function_one|1|1",
                     b"|function_one|1|1|shell", b"|function_one|2|3", b""]:
            with self.assertRaises(ValueError):
                core.selections(mapping, data)

    def test_changed_owned_file_or_job_identity_is_refused(self):
        job = self.prepare()
        owned = self.jobs / job["job_id"]
        (owned / "input.dll").write_bytes(dll_bytes() + b"changed")
        with self.assertRaisesRegex(ValueError, "input changed"):
            self.store.result(job["job_id"])

    def test_project_format_cannot_be_approved_by_config(self):
        config = json.loads(json.dumps(self.config))
        config["templates"]["fixture"]["format"] = "trusted-execute"
        with self.assertRaises(ValueError):
            core.Configuration(config)

    def test_unreviewed_sidecar_blocks_managed_job(self):
        job = self.prepare()
        (self.jobs / job["job_id"] / "extra.ini").write_text("unreviewed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Unreviewed file"):
            self.store.result(job["job_id"])


class CompilerTests(Fixture):
    def test_scoped_argv_auxiliary_check_and_one_shot(self):
        self.reviewed_fixture()
        job = self.prepare()
        self.store.compile(job["job_id"])
        self.finish()
        result = self.store.result(job["job_id"])
        self.assertEqual("compiled-not-approved", result["state"])
        self.assertFalse(result["output_executed"])
        self.assertFalse(result["protection_verified"])
        self.assertEqual(2, len(self.calls))
        self.assertTrue(all(argv[0] == str(self.exe) for argv in self.calls))
        self.assertEqual("/protect", self.calls[0][1])
        self.assertEqual("/checkprotected", self.calls[1][1])
        self.assertEqual(result["output_path"], self.calls[1][2])
        with self.assertRaisesRegex(ValueError, "one-shot"):
            self.store.compile(job["job_id"])
        self.assertFalse((self.jobs / ".compiler.lock").exists())

    def test_stale_lock_is_not_deleted_or_bypassed(self):
        self.reviewed_fixture()
        job = self.prepare()
        lock = self.jobs / ".compiler.lock"
        lock.write_bytes(b"unknown-owned-process")
        with self.assertRaisesRegex(ValueError, "busy"):
            self.store.compile(job["job_id"])
        self.assertEqual(b"unknown-owned-process", lock.read_bytes())
        self.assertEqual([], self.calls)

    def test_compilers_serialize_and_result_can_poll_while_running(self):
        self.reviewed_fixture()
        entered, release = threading.Event(), threading.Event()

        def runner(*args):
            entered.set()
            release.wait(2)
            return self.runner(*args)
        self.store.runner = runner
        first, second = self.prepare(), self.prepare()
        self.store.compile(first["job_id"])
        self.assertTrue(entered.wait(1))
        self.assertEqual("running", self.store.result(first["job_id"])["state"])
        with self.assertRaisesRegex(ValueError, "busy"):
            self.store.compile(second["job_id"])
        release.set()
        self.finish()

    def test_compiler_failure_is_not_retried_and_preserves_bounded_log(self):
        self.reviewed_fixture()

        def runner(*_args):
            return {"exit_code": 5, "failure": None, "log": "x" * (core.MAX_LOG * 3),
                    "log_bytes": core.MAX_LOG * 3, "log_truncated": True, "elapsed_seconds": 0}
        self.store.runner = runner
        job = self.prepare()
        self.store.compile(job["job_id"])
        self.finish()
        result = self.store.result(job["job_id"])
        self.assertEqual("failed", result["state"])
        self.assertEqual("protection-error", result["compiler"]["exit_meaning"])
        self.assertEqual(core.MAX_LOG, (self.jobs / job["job_id"] / "compiler.log").stat().st_size)
        self.assertEqual(8192, len(result["log_tail"]))

    def test_success_without_output_is_failure(self):
        self.reviewed_fixture()
        self.store.runner = lambda *_: {"exit_code": 0, "failure": None, "log": "", "log_bytes": 0}
        job = self.prepare()
        self.store.compile(job["job_id"])
        self.finish()
        self.assertEqual("failed", self.store.result(job["job_id"])["state"])

    def test_unclosed_process_pipe_retains_lock_for_operator(self):
        self.reviewed_fixture()
        self.store.runner = lambda *_: {"exit_code": 0, "failure": "compiler-log-pipe-did-not-close",
                                        "log": "", "log_bytes": 0, "operator_cleanup_required": True}
        job = self.prepare()
        self.store.compile(job["job_id"])
        self.finish()
        self.assertEqual("failed", self.store.result(job["job_id"])["state"])
        self.assertTrue((self.jobs / ".compiler.lock").exists())

    def test_existing_output_is_never_overwritten(self):
        self.reviewed_fixture()
        job = self.prepare()
        output = Path(job["output_path"])
        output.write_bytes(b"do not overwrite")
        with self.assertRaisesRegex(ValueError, "already exist"):
            self.store.compile(job["job_id"])
        self.assertEqual(b"do not overwrite", output.read_bytes())

    def test_changed_output_refused_by_result(self):
        self.reviewed_fixture()
        job = self.prepare()
        self.store.compile(job["job_id"])
        self.finish()
        Path(job["output_path"]).write_bytes(dll_bytes() + b"tamper")
        with self.assertRaisesRegex(ValueError, "Output changed"):
            self.store.result(job["job_id"])


class ProcessTests(unittest.TestCase):
    def test_deadline_kills_owned_process_and_does_not_inherit_tokens(self):
        class Process:
            def __init__(self):
                self.stdout = io.BytesIO(b"x" * (core.MAX_LOG * 2))
                self.returncode = None
                self.killed = False
            def poll(self):
                return self.returncode
            def kill(self):
                self.killed = True
                self.returncode = -1
            def wait(self, timeout):
                return self.returncode
        process = Process()
        with patch.object(core.subprocess, "Popen", return_value=process) as launch, \
                patch.object(core.time, "monotonic", side_effect=[0, 11, 11]), \
                patch.dict(os.environ, {"FLY_API_TOKEN": "synthetic-never-forward", "SECRET_KEY": "synthetic"}):
            result = core.ProcessRunner()(["pinned-compiler", "/protect", "owned.cv"], Path.cwd(), 10, threading.Event())
        self.assertTrue(process.killed)
        self.assertEqual("deadline-exceeded", result["failure"])
        self.assertTrue(result["log_truncated"])
        self.assertLessEqual(len(result["log"]), core.MAX_LOG)
        kwargs = launch.call_args.kwargs
        self.assertFalse(kwargs["shell"])
        self.assertNotIn("FLY_API_TOKEN", kwargs["env"])
        self.assertNotIn("SECRET_KEY", kwargs["env"])


if __name__ == "__main__":
    unittest.main()
