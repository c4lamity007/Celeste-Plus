import hashlib
import json
import plistlib
import struct
import tarfile
import tempfile
import unittest
from pathlib import Path
import sys
import zipfile
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from package_celeste_plus_candidate import executable_format, package
import native_protection_gate as gate
from test_native_protection_gate import ProtectionFixture


class CandidatePackageTest(unittest.TestCase):
    def test_explicit_publication_preserves_runtime_and_signing_caveats(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "CelestePlus"
            binary.write_bytes(b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x0100000C))
            release_id = "celeste-plus-20260920-platforms-01"
            # This test covers archive metadata only; real gate behavior is exercised below.
            with patch("package_celeste_plus_candidate.validate_native_protection", return_value={
                "status": "fixture-only", "binary_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
            }):
                result = package(binary, "macos-arm64", "aarch64-apple-darwin", root / "release",
                                 release_id=release_id)
            self.assertEqual(result["distribution_status"], "published-preview")
            self.assertEqual(result["release_id"], release_id)
            self.assertIn("not signed or notarized", result["limitations"])
            self.assertIn("not tested against a live Minecraft process", result["limitations"])
            self.assertNotIn("not approved for publication", result["limitations"])
            with self.assertRaises(ValueError):
                package(binary, "macos-arm64", "aarch64-apple-darwin", root / "bad",
                        release_id="../bad")

    def test_formats(self):
        elf = bytearray(20)
        elf[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<H", elf, 18, 62)
        self.assertEqual(executable_format(elf), "elf-x86_64")
        self.assertEqual(
            executable_format(b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x0100000C)),
            "macho-arm64",
        )
        self.assertEqual(
            executable_format(b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x01000007)),
            "macho-x86_64",
        )
        pe = bytearray(72)
        pe[:2] = b"MZ"
        struct.pack_into("<I", pe, 0x3C, 64)
        pe[64:68] = b"PE\0\0"
        struct.pack_into("<H", pe, 68, 0x8664)
        self.assertEqual(executable_format(pe), "pe-x86_64")
        with self.assertRaises(ValueError):
            executable_format(b"MZ")

    def test_linux_publication_uses_product_filename(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "input"
            value = bytearray(24)
            value[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<H", value, 18, 62)
            binary.write_bytes(value)
            with patch("package_celeste_plus_candidate.validate_native_protection", return_value={
                "status": "fixture-only", "binary_sha256": hashlib.sha256(value).hexdigest(),
            }):
                result = package(binary, "linux-x64", "x86_64-unknown-linux-gnu", root / "release",
                                 release_id="celeste-plus-20260920-platforms-01")
            self.assertEqual(result["executable"], "Celeste+")
            with tarfile.open(result["archive"], "r:gz") as bundled:
                self.assertEqual(bundled.getmember("Celeste+").mode, 0o755)
                self.assertEqual(bundled.extractfile("Celeste+").read(), value)

    def test_package_preserves_executable_mode_and_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "CelestePlus"
            value = bytearray(24)
            value[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<H", value, 18, 62)
            binary.write_bytes(value)
            report = root / "abi.txt"
            report.write_text("maximum referenced GLIBC symbol: GLIBC_2.35\n")
            result = package(
                binary,
                "linux-x64",
                "x86_64-unknown-linux-gnu",
                root / "candidate",
                report,
            )
            archive = Path(result["archive"])
            with tarfile.open(archive, "r:gz") as bundled:
                executable = bundled.getmember("CelestePlus")
                self.assertEqual(executable.mode, 0o755)
                embedded = json.load(bundled.extractfile("build-manifest.json"))
                self.assertEqual(bundled.extractfile("native-abi.txt").read(), report.read_bytes())
            external = json.loads((archive.parent / "build-manifest.json").read_text())
            self.assertEqual(embedded, external)
            self.assertEqual(embedded["distribution_status"], "unsigned-test-candidate")
            self.assertEqual(embedded["executable_format"], "elf-x86_64")

    def test_platform_target_and_binary_must_agree(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "candidate"
            binary.write_bytes(b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x0100000C))
            with self.assertRaises(ValueError):
                package(binary, "macos-arm64", "x86_64-apple-darwin", root / "wrong-target")
            with self.assertRaises(ValueError):
                package(binary, "macos-x64", "x86_64-apple-darwin", root / "wrong-format")

    def test_macos_candidate_is_an_app_bundle(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "CelestePlus"
            binary.write_bytes(b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x0100000C))
            result = package(
                binary,
                "macos-arm64",
                "aarch64-apple-darwin",
                root / "candidate",
            )
            with tarfile.open(result["archive"], "r:gz") as bundled:
                executable = bundled.getmember(
                    "Celeste+.app/Contents/MacOS/CelestePlus"
                )
                self.assertEqual(executable.mode, 0o755)
                plist = plistlib.loads(
                    bundled.extractfile("Celeste+.app/Contents/Info.plist").read()
                )
                self.assertEqual(plist["CFBundleExecutable"], "CelestePlus")
                self.assertEqual(plist["CFBundleIdentifier"], "dev.celeste.client.plus")
                embedded = json.load(
                    bundled.extractfile(
                        "Celeste+.app/Contents/Resources/build-manifest.json"
                    )
                )
                self.assertEqual(embedded["artifact"], "Celeste+.app")
                self.assertEqual(embedded["executable"], "CelestePlus")

    def test_windows_candidate_uses_zip(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "CelestePlus.exe"
            value = bytearray(72)
            value[:2] = b"MZ"
            struct.pack_into("<I", value, 0x3C, 64)
            value[64:68] = b"PE\0\0"
            struct.pack_into("<H", value, 68, 0x8664)
            binary.write_bytes(value)
            result = package(
                binary,
                "windows-x64",
                "x86_64-pc-windows-msvc",
                root / "candidate",
            )
            self.assertTrue(result["archive"].endswith(".zip"))
            with zipfile.ZipFile(result["archive"]) as bundled:
                self.assertEqual(
                    json.loads(bundled.read("build-manifest.json"))["artifact"],
                    "CelestePlus.exe",
                )

    def test_candidate_and_ci_manifest_cannot_authorize_publication(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "candidate"
            binary.write_bytes(b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x0100000C))
            candidate = package(binary, "macos-arm64", "aarch64-apple-darwin", root / "candidate-output")
            self.assertEqual(candidate["distribution_status"], "unsigned-test-candidate")
            self.assertNotIn("native_protection", candidate)
            for receipt in (None, root / "candidate-output/build-manifest.json"):
                with self.subTest(receipt=receipt), self.assertRaises(ValueError):
                    package(binary, "macos-arm64", "aarch64-apple-darwin", root / "blocked",
                            release_id="celeste-plus-test-protected-01", protection_receipt=receipt)
                self.assertFalse((root / "blocked").exists())
            fixture = ProtectionFixture(root, binary, platform="macos-arm64")
            with patch.object(gate, "source_digest", return_value=fixture.source_sha256):
                # Even a coherent synthetic receipt is unapproved by the actual empty policy.
                with self.assertRaisesRegex(ValueError, "No reviewed protector"):
                    package(binary, "macos-arm64", "aarch64-apple-darwin", root / "blocked",
                            release_id=fixture.record["release_id"], protection_receipt=fixture.receipt)
            self.assertFalse((root / "blocked").exists())

    def test_real_gate_checks_wrapper_identity_and_pins_packaged_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "candidate"
            binary.write_bytes(b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x0100000C))
            fixture = ProtectionFixture(root, binary, platform="macos-arm64")
            with patch.object(gate, "POLICY_FILE", fixture.policy), \
                    patch.object(gate, "source_digest", return_value=fixture.source_sha256):
                for field, value in (("release_id", "celeste-plus-wrong"), ("product", "celeste"),
                                     ("platform", "windows-x64")):
                    original = fixture.record[field]
                    fixture.record[field] = value
                    fixture.write_record()
                    with self.subTest(field=field), self.assertRaisesRegex(ValueError, "another release/product/platform"):
                        package(binary, "macos-arm64", "aarch64-apple-darwin", root / "blocked",
                                release_id="celeste-plus-test-protected-01", protection_receipt=fixture.receipt)
                    self.assertFalse((root / "blocked").exists())
                    fixture.record[field] = original
                fixture.write_record()
                result = package(binary, "macos-arm64", "aarch64-apple-darwin", root / "approved-test",
                                 release_id=fixture.record["release_id"], protection_receipt=fixture.receipt)
            with tarfile.open(result["archive"], "r:gz") as bundled:
                actual = bundled.extractfile("Celeste+.app/Contents/MacOS/CelestePlus").read()
                embedded = json.load(bundled.extractfile("Celeste+.app/Contents/Resources/build-manifest.json"))
            self.assertEqual(actual, binary.read_bytes())
            self.assertEqual(embedded["native_protection"]["binary_sha256"], hashlib.sha256(actual).hexdigest())
            self.assertEqual(embedded["native_protection"]["receipt_sha256"], hashlib.sha256(fixture.receipt.read_bytes()).hexdigest())

    def test_changed_binary_between_archive_read_and_gate_cannot_publish_stale_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = root / "candidate"
            original = b"\xcf\xfa\xed\xfe" + struct.pack("<I", 0x0100000C) + b"old-unverified"
            replacement = original[:-14] + b"new-verified!!"
            binary.write_bytes(replacement)
            fixture = ProtectionFixture(root, binary, platform="macos-arm64")
            binary.write_bytes(original)

            def replace_before_validation(*args, **kwargs):
                binary.write_bytes(replacement)
                return gate.validate(*args, **kwargs)

            with patch.object(gate, "POLICY_FILE", fixture.policy), \
                    patch.object(gate, "source_digest", return_value=fixture.source_sha256), \
                    patch("package_celeste_plus_candidate.validate_native_protection", side_effect=replace_before_validation):
                with self.assertRaises(ValueError):
                    package(binary, "macos-arm64", "aarch64-apple-darwin", root / "blocked",
                            release_id=fixture.record["release_id"], protection_receipt=fixture.receipt)
            self.assertFalse((root / "blocked").exists())


if __name__ == "__main__":
    unittest.main()
