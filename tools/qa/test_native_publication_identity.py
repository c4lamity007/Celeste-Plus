"""Synthetic identity tests: no real identity, trust-store or server changes."""
from __future__ import annotations

import contextlib
import ctypes
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/packaging"))
import native_publication_identity as identity
import register_native_release as registration

SEED = bytes(range(32))  # Published synthetic test material, never a production key.
CIPHERTEXT = b"synthetic DPAPI ciphertext; deliberately contains no key material"


class NativePublicationIdentityTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.path = self.directory / "publication-identity.json"
        self.dpapi = patch.object(identity, "_dpapi", side_effect=self.fake_dpapi).start()
        self.generator = patch.object(identity, "_new_seed", return_value=SEED).start()
        self.addCleanup(patch.stopall)

    @staticmethod
    def fake_dpapi(value, *, protect):
        if protect and value == SEED:
            return CIPHERTEXT
        if not protect and value == CIPHERTEXT:
            return SEED
        raise ValueError("Synthetic DPAPI refusal")

    def read(self):
        return json.loads(self.path.read_bytes())

    def write(self, value):
        self.path.write_text(json.dumps(value), encoding="utf-8")

    def test_init_writes_ciphertext_only_and_shows_matching_public_metadata(self):
        metadata = identity.initialize(self.path)
        record = self.read()
        self.assertEqual("windows-dpapi-current-user", record["protection"])
        self.assertEqual(CIPHERTEXT, identity._decode(record["encrypted_seed"]))
        self.assertNotIn(identity._b64(SEED), self.path.read_text())
        self.assertEqual(metadata, identity.show_public(self.path))
        self.assertEqual(SEED, identity.load_private_seed(self.path))
        self.assertEqual("CELESTE_NATIVE_RELEASE_PUBLIC_KEY", metadata["server_setting"])
        self.assertEqual(identity._public(SEED), identity._decode(metadata["public_key"]))
        self.assertNotIn("encrypted_seed", metadata)
        self.assertNotIn(identity._b64(SEED), json.dumps(metadata))

    def test_existing_identity_is_never_overwritten_or_rotated(self):
        identity.initialize(self.path)
        original = self.path.read_bytes()
        self.generator.reset_mock()
        with self.assertRaisesRegex(ValueError, "overwriting"):
            identity.initialize(self.path)
        self.generator.assert_not_called()
        self.assertEqual(original, self.path.read_bytes())

    def test_exclusive_create_preserves_a_file_created_during_initialization(self):
        calls = 0
        def raced_dpapi(value, *, protect):
            nonlocal calls
            calls += 1
            if calls == 2:
                self.path.write_bytes(b"concurrent initializer")
            return self.fake_dpapi(value, protect=protect)
        self.dpapi.side_effect = raced_dpapi
        with self.assertRaises(FileExistsError):
            identity.initialize(self.path)
        self.assertEqual(b"concurrent initializer", self.path.read_bytes())

    def test_dpapi_failure_or_bad_roundtrip_never_creates_a_record(self):
        self.dpapi.side_effect = ValueError("Cannot unlock")
        with self.assertRaises(ValueError):
            identity.initialize(self.path)
        self.assertFalse(self.path.exists())
        self.dpapi.side_effect = lambda value, protect: CIPHERTEXT if protect else b"x" * 32
        with self.assertRaisesRegex(ValueError, "round-trip"):
            identity.initialize(self.path)
        self.assertFalse(self.path.exists())

    def test_plaintext_dpapi_result_is_rejected(self):
        self.dpapi.side_effect = lambda value, protect: value
        with self.assertRaisesRegex(ValueError, "encrypted identity storage"):
            identity.initialize(self.path)
        self.assertFalse(self.path.exists())

    def test_git_checkout_and_worktree_locations_are_refused(self):
        for name, directory_marker in (("checkout", True), ("worktree", False)):
            directory = self.directory / name
            directory.mkdir()
            marker = directory / ".git"
            if directory_marker:
                marker.mkdir()
            else:
                marker.write_text("gitdir: elsewhere")
            nested = directory / "private"
            nested.mkdir()
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "outside Git"):
                identity.initialize(nested / "identity.json")
            self.assertFalse((nested / "identity.json").exists())
        self.generator.assert_not_called()

    def test_relative_and_missing_parent_locations_are_refused(self):
        for path in (Path("relative-private-key.json"), self.directory / "missing" / "identity.json"):
            with self.subTest(path=str(path)), self.assertRaises(ValueError):
                identity.initialize(path)
        self.generator.assert_not_called()

    def test_public_metadata_and_decrypted_key_must_agree(self):
        identity.initialize(self.path)
        record = self.read()
        other_public = identity._public(b"x" * 32)
        record["public_key"] = identity._b64(other_public)
        record["public_key_sha256"] = identity.hashlib.sha256(other_public).hexdigest()
        self.write(record)
        with self.assertRaisesRegex(ValueError, "does not match"):
            identity.load_private_seed(self.path)
        with self.assertRaisesRegex(ValueError, "does not match"):
            identity.show_public(self.path)

    def test_changed_context_unknown_fields_and_public_digest_are_refused(self):
        identity.initialize(self.path)
        original = self.read()
        for field, value in (("product", "celeste"), ("protection", "windows-dpapi-machine"),
                             ("schema", "other"), ("algorithm", "RSA"), ("extra", True),
                             ("public_key_sha256", "0" * 64), ("public_key", original["public_key"] + "=")):
            self.write(original | {field: value})
            with self.subTest(field=field), self.assertRaises(ValueError):
                identity.show_public(self.path)

    def test_missing_corrupt_duplicate_and_oversized_records_are_refused(self):
        with self.assertRaises(OSError):
            identity.show_public(self.path)
        for raw in (b"", b"[]", b"null", b"{", b'{"schema":1,"schema":2}',
                    b"x" * (identity.MAX_RECORD + 1)):
            self.path.write_bytes(raw)
            with self.subTest(size=len(raw)), self.assertRaises(ValueError):
                identity.show_public(self.path)

    def test_unlock_failure_and_wrong_seed_length_are_refused_without_file_change(self):
        identity.initialize(self.path)
        original = self.path.read_bytes()
        for result in (ValueError("Different Windows account"), b"short"):
            self.dpapi.side_effect = result if isinstance(result, Exception) else None
            self.dpapi.return_value = result
            with self.subTest(result=type(result).__name__), self.assertRaises(ValueError):
                identity.load_private_seed(self.path)
            self.assertEqual(original, self.path.read_bytes())

    def test_cli_init_and_show_never_print_seed_or_ciphertext(self):
        for command in ("init", "show-public"):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                identity.main([command, "--identity", str(self.path)])
            record = json.loads(output.getvalue())
            self.assertEqual("celeste_plus", record["product"])
            self.assertNotIn(identity._b64(SEED), output.getvalue())
            self.assertNotIn(identity._b64(CIPHERTEXT), output.getvalue())
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            identity.main(["export-private", "--identity", str(self.path)])

    def test_cli_sanitizes_errors_even_if_provider_exception_contains_private_data(self):
        self.dpapi.side_effect = ValueError(identity._b64(SEED))
        error = io.StringIO()
        with contextlib.redirect_stderr(error), self.assertRaises(SystemExit):
            identity.main(["init", "--identity", str(self.path)])
        self.assertNotIn(identity._b64(SEED), error.getvalue())

    def test_load_is_compatible_with_existing_registration_key_format(self):
        metadata = identity.initialize(self.path)
        # Synthetic key only. Production callers must supply a child-only env.
        with patch.dict(os.environ, {"CELESTE_NATIVE_RELEASE_PRIVATE_KEY": identity._b64(identity.load_private_seed(self.path))}):
            key = registration.publication_key()
        message = b"synthetic native publication compatibility test"
        Ed25519PublicKey.from_public_bytes(identity._decode(metadata["public_key"])).verify(key.sign(message), message)


class DpapiBindingTest(unittest.TestCase):
    def test_native_binding_uses_current_user_no_ui_system_dlls_and_frees_buffers(self):
        calls = []
        output_buffers = []
        input_buffers = []
        class Function:
            def __init__(self, protect):
                self.protect = protect
            def __call__(self, source, description, entropy, reserved, prompt, flags, destination):
                input_buffers.append(source._obj)
                calls.append((self.protect, description, reserved, prompt, flags,
                              ctypes.string_at(entropy._obj.data, entropy._obj.size)))
                result = CIPHERTEXT if self.protect else SEED
                buffer = (ctypes.c_ubyte * len(result)).from_buffer_copy(result)
                output_buffers.append(buffer)
                destination._obj.size = len(result)
                destination._obj.data = buffer
                return 1
        free_calls = []
        class LocalFree:
            def __call__(self, value):
                free_calls.append(value)
        crypt = types.SimpleNamespace(CryptProtectData=Function(True), CryptUnprotectData=Function(False))
        kernel = types.SimpleNamespace(LocalFree=LocalFree())
        with patch.object(identity.sys, "platform", "win32"), patch.object(identity.ctypes, "WinDLL", create=True) as dll:
            dll.side_effect = lambda name, **kwargs: crypt if name == "crypt32.dll" else kernel
            self.assertEqual(CIPHERTEXT, identity._dpapi(SEED, protect=True))
            self.assertEqual(SEED, identity._dpapi(CIPHERTEXT, protect=False))
        self.assertTrue(all(call.kwargs == {"use_last_error": True, "winmode": 0x800} for call in dll.call_args_list))
        self.assertEqual([(True, None, None, None, 1, identity.ENTROPY),
                          (False, None, None, None, 1, identity.ENTROPY)], calls)
        self.assertEqual(2, len(free_calls))
        self.assertTrue(all(bytes(buffer) == b"\0" * len(buffer) for buffer in output_buffers))

    def test_non_windows_has_no_plaintext_fallback(self):
        with patch.object(identity.sys, "platform", "linux"), self.assertRaisesRegex(ValueError, "Windows DPAPI"):
            identity._dpapi(SEED, protect=True)


if __name__ == "__main__":
    unittest.main()
