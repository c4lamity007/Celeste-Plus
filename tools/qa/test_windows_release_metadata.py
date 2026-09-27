from __future__ import annotations

import importlib.util
import struct
import tempfile
import unittest
from pathlib import Path


SPEC = importlib.util.spec_from_file_location("windows_release_metadata", Path(__file__).resolve().parents[1] / "packaging/windows_release_metadata.py")
metadata = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(metadata)


def fixture(debug_type=0, suffix=b""):
    data = bytearray(1024)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 60, 128)
    data[128:132] = b"PE\0\0"
    struct.pack_into("<HHIIIHH", data, 132, 0x8664, 1, 0, 0, 0, 240, 2)
    struct.pack_into("<H", data, 152, 0x20B)
    struct.pack_into("<I", data, 212, 512)  # SizeOfHeaders
    struct.pack_into("<I", data, 260, 16)
    struct.pack_into("<IIII", data, 400, 512, 4096, 512, 512)
    struct.pack_into("<I", data, 428, 0x40000040)  # Read-only initialized data.
    if debug_type:
        struct.pack_into("<II", data, 312, 4096, 28)
        struct.pack_into("<IIII", data, 524, debug_type, 32, 4160, 576)
        data[576:580] = b"RSDS"
    return bytes(data) + suffix


class WindowsReleaseMetadataTest(unittest.TestCase):
    def test_clean_x64_passes_without_claiming_signature_verification(self):
        result = metadata.inspect_bytes(fixture())
        self.assertTrue(result["passed"])
        self.assertFalse(result["authenticode_verified"])

    def test_codeview_and_other_debug_metadata_fail(self):
        for kind in (1, 2, 3, 13):
            with self.subTest(kind=kind):
                self.assertIn("debug_directory_present", metadata.inspect_bytes(fixture(kind))["findings"])

    def test_reproducible_build_marker_does_not_imply_debug_symbols(self):
        self.assertTrue(metadata.inspect_bytes(fixture(16))["passed"])

    def test_user_paths_in_ascii_and_utf16_fail(self):
        for value in (r"C:\Users\developer\build\source.rs", "/home/runner/work/project/source.rs", "/Users/developer/project/file.rs"):
            for encoding in ("utf-8", "utf-16le"):
                with self.subTest(value=value, encoding=encoding):
                    self.assertIn("absolute_user_build_path_present", metadata.inspect_bytes(fixture(suffix=value.encode(encoding)))["findings"])

    def test_user_path_roots_have_os_case_rules_and_boundaries(self):
        for value in (r"c:\uSeRs\developer\build", "/home/runner/work", "/Users/developer/work"):
            for encoding in ("utf-8", "utf-16le"):
                with self.subTest(value=value, encoding=encoding):
                    self.assertFalse(metadata.inspect_bytes(fixture(suffix=value.encode(encoding)))["passed"])
        for value in ("Contents/Home/releaseContents/Home", "prefix/home/runner/work", "./home/runner/work", "/HOME/runner/work", "prefix/Users/developer/work"):
            for encoding in ("utf-8", "utf-16le"):
                with self.subTest(value=value, encoding=encoding):
                    self.assertTrue(metadata.inspect_bytes(fixture(suffix=value.encode(encoding)))["passed"])

    def test_nul_removal_does_not_manufacture_a_path(self):
        self.assertTrue(metadata.inspect_bytes(fixture(suffix=b"/ho\0me/user/work"))["passed"])

    def test_utf16_paths_on_either_byte_alignment_are_detected(self):
        for prefix in (b"\0", b"\0\0"):
            self.assertFalse(metadata.inspect_bytes(fixture(suffix=prefix + "/home/runner/work".encode("utf-16le")))["passed"])

    def test_explicit_build_prefix_is_case_and_separator_independent(self):
        result = metadata.inspect_bytes(fixture(suffix=b"D:/BUILD/WORK/source.rs"), (r"d:\build\work",))
        self.assertEqual([0], result["matched_prefix_indexes"])

    def test_coff_symbols_are_rejected(self):
        data = bytearray(fixture())
        struct.pack_into("<II", data, 140, 900, 1)
        self.assertIn("coff_symbols_present", metadata.inspect_bytes(bytes(data))["findings"])

    def test_truncated_and_wrong_architecture_fail(self):
        for data in (b"", fixture()[:500], b"NO" + fixture()[2:]):
            with self.assertRaises(ValueError):
                metadata.inspect_bytes(data)
        data = bytearray(fixture())
        struct.pack_into("<H", data, 132, 0x14C)
        with self.assertRaises(ValueError):
            metadata.inspect_bytes(bytes(data))

    def test_bad_debug_rva_size_or_payload_fails(self):
        for offset, value in ((312, 8192), (316, 27), (536, 1000000)):
            data = bytearray(fixture(2))
            struct.pack_into("<I", data, offset, value)
            with self.assertRaises(ValueError):
                metadata.inspect_bytes(bytes(data))

    def test_certificate_presence_is_not_a_valid_signature_claim(self):
        data = bytearray(fixture())
        struct.pack_into("<II", data, 296, 992, 32)
        result = metadata.inspect_bytes(bytes(data))
        self.assertTrue(result["certificate_table_present"])
        self.assertFalse(result["authenticode_verified"])

    def test_relative_or_broad_forbidden_prefix_is_rejected(self):
        for prefix in ("", "/", "C:\\", "relative/path"):
            with self.subTest(prefix=prefix), self.assertRaises(ValueError):
                metadata.inspect_bytes(fixture(), (prefix,))

    def test_truncated_certificate_table_is_rejected(self):
        data = bytearray(fixture())
        struct.pack_into("<II", data, 296, 1000, 200)
        with self.assertRaises(ValueError):
            metadata.inspect_bytes(bytes(data))

    def test_directory_count_cannot_escape_optional_header(self):
        data = bytearray(fixture())
        struct.pack_into("<I", data, 260, 10000)
        with self.assertRaises(ValueError):
            metadata.inspect_bytes(bytes(data))

    def test_reviewed_debug_records_are_erased_and_checker_stays_strict(self):
        for kind in (2, 13, 16):
            original = fixture(kind)
            cleaned = metadata.strip_debug_bytes(original)
            self.assertEqual(len(original), len(cleaned))
            self.assertEqual([], metadata.inspect_bytes(cleaned)["debug_types"])
            self.assertTrue(metadata.inspect_bytes(cleaned)["passed"])
            self.assertEqual(bytes(28), cleaned[512:540])
            self.assertEqual(bytes(32), cleaned[576:608])
            changed = {index for index, pair in enumerate(zip(original, cleaned)) if pair[0] != pair[1]}
            allowed = set(range(216, 220)) | set(range(312, 320)) | set(range(512, 540)) | set(range(576, 608))
            self.assertTrue(changed <= allowed, changed - allowed)
            self.assertEqual(cleaned, metadata.strip_debug_bytes(cleaned))
        self.assertFalse(metadata.inspect_bytes(fixture(13))["passed"])

    def test_sanitizer_refuses_signed_unknown_and_symbol_bearing_files(self):
        signed = bytearray(fixture(13))
        struct.pack_into("<II", signed, 296, 992, 32)
        symbols = bytearray(fixture(13))
        struct.pack_into("<II", symbols, 140, 900, 1)
        for data in (signed, symbols, fixture(1), fixture(20), fixture(999)):
            with self.subTest(data_type=type(data)), self.assertRaises(ValueError):
                metadata.strip_debug_bytes(bytes(data))

    def test_sanitizer_refuses_invalid_and_overlapping_layouts(self):
        modifications = (
            ((312, 8192),),  # Unmapped directory.
            ((316, 27),),  # Incomplete directory record.
            ((536, 1000000),),  # Out-of-file payload.
            ((536, 512), (532, 4096)),  # Payload overlaps its directory.
            ((532, 0),),  # Unmapped overlay payload.
            ((532, 4200),),  # RVA/file pointer disagreement.
            ((212, 128),),  # Headers cut through section table.
            ((412, 128),),  # Raw section overlaps header.
            ((404, 128),),  # Virtual section overlaps header.
            ((428, 0x60000040),),  # Executable metadata section.
            ((428, 0xc0000040),),  # Writable metadata section.
            ((416, 800),),  # COFF section relocations remain.
            ((264, 4160), (268, 32)),  # Export directory shares debug payload.
            ((264, 4096), (268, 28)),  # Export directory shares debug directory.
            ((532, 0), (528, 0)),  # Empty payload with nonempty offset.
        )
        for changes in modifications:
            data = bytearray(fixture(13))
            for offset, value in changes:
                struct.pack_into("<I", data, offset, value)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                metadata.strip_debug_bytes(bytes(data))

    def test_sanitizer_refuses_shared_debug_payloads(self):
        data = bytearray(fixture(13))
        struct.pack_into("<I", data, 316, 56)
        data[540:568] = data[512:540]
        with self.assertRaises(ValueError):
            metadata.strip_debug_bytes(bytes(data))

    def test_multiple_reviewed_records_clear_every_payload(self):
        data = bytearray(fixture(13))
        struct.pack_into("<I", data, 316, 56)
        struct.pack_into("<IIII", data, 552, 2, 32, 4224, 640)
        data[640:672] = b"RSDS" + b"private-debug-record".ljust(28, b"\0")
        cleaned = metadata.strip_debug_bytes(bytes(data))
        self.assertEqual(bytes(56), cleaned[512:568])
        self.assertEqual(bytes(32), cleaned[576:608])
        self.assertEqual(bytes(32), cleaned[640:672])
        self.assertTrue(metadata.inspect_bytes(cleaned)["passed"])

    def test_sanitizer_preserves_separate_code_section_and_rejects_aliases(self):
        data = bytearray(fixture(13) + bytes(range(256)) * 2)
        struct.pack_into("<H", data, 134, 2)
        struct.pack_into("<IIII", data, 440, 512, 8192, 512, 1024)
        struct.pack_into("<I", data, 468, 0x60000020)
        self.assertEqual(bytes(data[1024:]), metadata.strip_debug_bytes(bytes(data))[1024:])
        for offset, value in ((444, 4096), (452, 512)):
            alias = bytearray(data)
            struct.pack_into("<I", alias, offset, value)
            with self.assertRaises(ValueError):
                metadata.strip_debug_bytes(bytes(alias))
        code_payload = bytearray(data)
        struct.pack_into("<II", code_payload, 532, 8192, 1024)
        with self.assertRaises(ValueError):
            metadata.strip_debug_bytes(bytes(code_payload))

    def test_forbidden_prefix_gate_still_rejects_after_debug_removal(self):
        original = fixture(13, suffix=b"D:/BUILD/WORK/source.rs")
        cleaned = metadata.strip_debug_bytes(original)
        result = metadata.inspect_bytes(cleaned, (r"d:\build\work",))
        self.assertEqual([0], result["matched_prefix_indexes"])
        self.assertFalse(result["passed"])

    def test_atomic_file_strip_does_not_modify_rejected_input(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "candidate.exe"
            original = fixture(13)
            binary.write_bytes(original)
            receipt = metadata.strip_debug(binary)
            self.assertNotEqual(receipt["before_sha256"], receipt["sha256"])
            self.assertEqual(metadata.strip_debug_bytes(original), binary.read_bytes())
            signed = bytearray(original)
            struct.pack_into("<II", signed, 296, 992, 32)
            binary.write_bytes(signed)
            with self.assertRaises(ValueError):
                metadata.strip_debug(binary)
            self.assertEqual(bytes(signed), binary.read_bytes())
            self.assertEqual([binary], list(Path(directory).iterdir()))


if __name__ == "__main__":
    unittest.main()
