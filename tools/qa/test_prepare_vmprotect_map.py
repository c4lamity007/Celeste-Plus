"""MAP selection/provenance tests; no protected binary is executed or approved."""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "packaging"))
import prepare_vmprotect_map as mapping


def fixture():
    data = bytearray(0xe00)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 60, 128)
    data[128:132] = b"PE\0\0"
    struct.pack_into("<HHIIIHH", data, 132, 0x8664, 3, 1, 0, 0, 240, 0x2022)
    struct.pack_into("<H", data, 152, 0x20b)
    struct.pack_into("<Q", data, 176, 0x140000000)
    struct.pack_into("<II", data, 208, 0x4000, 512)
    struct.pack_into("<I", data, 260, 16)
    struct.pack_into("<II", data, 288, 0x2000, len(mapping.TARGETS) * 12)
    for index, (name, rva, offset, size, flags) in enumerate((
            (b".text", 0x1000, 0x200, 0x600, 0x60000020),
            (b".pdata", 0x2000, 0x800, 0x200, 0x40000040),
            (b".xdata", 0x3000, 0xa00, 0x400, 0x40000040))):
        at = 392 + index * 40
        data[at:at + len(name)] = name
        struct.pack_into("<IIII", data, at + 8, size, rva, size, offset)
        struct.pack_into("<I", data, at + 36, flags)
    lines = [" fixture", " Preferred load address is 0000000140000000", "", " Address         Publics by Value              Rva+Base       Lib:Object"]
    for index, (suffix, _) in enumerate(mapping.TARGETS):
        offset = index * 32
        struct.pack_into("<III", data, 0x800 + index * 12, 0x1000 + offset, 0x1010 + offset, 0x3000 + index * 8)
        data[0xa00 + index * 8] = 1
        data[0x200 + offset:0x210 + offset] = b"\x90" * 15 + b"\xc3"
        symbol = "_RNvNtCsFixture_11CelestePlus" + suffix
        lines.append(f" 0001:{offset:08x}       {symbol} 000000014000{0x1000 + offset:04x} f   fixture.o")
    lines += [" 0002:00000000       ordinary_data 0000000140002000     fixture.o", "", " Unchanged footer"]
    return bytes(data), ("\r\n".join(lines) + "\r\n").encode("ascii")


class MapFixture:
    def __init__(self, root):
        self.root = root
        self.binary = root / "Celeste+.exe"
        self.original = root / "Celeste+.map"
        self.candidate = root / "candidate.json"
        binary, original = fixture()
        self.binary.write_bytes(binary)
        self.original.write_bytes(original)
        self.bind()
        self.attempt = 0

    def bind(self):
        self.record = {"schema": "celeste-windows-metadata-candidate-1", "product": "celeste_plus",
                       "target": "x86_64-pc-windows-msvc", "artifact": "Celeste+.exe",
                       "status": "unsigned-local-candidate", "metadataCheck": "passed",
                       "size": self.binary.stat().st_size,
                       "sha256": hashlib.sha256(self.binary.read_bytes()).hexdigest(),
                       "privateMapSha256": hashlib.sha256(self.original.read_bytes()).hexdigest()}
        self.write_record()

    def write_record(self):
        self.candidate.write_text(json.dumps(self.record), encoding="utf-8")

    def prepare(self):
        self.attempt += 1
        self.output = self.root / f"mapped-{self.attempt}"
        return mapping.prepare(self.binary, self.original, self.candidate, self.output)

    def validate(self):
        return mapping.validate_alias_map(self.output / "Celeste+.exe", self.output / "Celeste+.original.map",
                                          self.output / "Celeste+.map", self.output / "mapping.json",
                                          self.output / "candidate.json")

    def binary_change(self, offset, fmt, *values):
        data = bytearray(self.binary.read_bytes())
        struct.pack_into(fmt, data, offset, *values)
        self.binary.write_bytes(data)
        self.bind()


class PrepareVmprotectMapTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.fixture = MapFixture(Path(temporary.name))

    def test_fresh_bundle_binds_exact_bytes_and_all_seven_ranges(self):
        f = self.fixture
        before = {p: p.read_bytes() for p in (f.binary, f.original, f.candidate)}
        result = f.prepare()
        self.assertEqual(result, f.validate())
        self.assertEqual("mapping-only-not-protection-evidence", result["status"])
        self.assertEqual(7, len(result["functions"]))
        self.assertEqual([alias for _, alias in mapping.TARGETS], [row["alias"] for row in result["functions"]])
        for index, row in enumerate(result["functions"]):
            self.assertEqual(16, row["size"])
            self.assertEqual(0x1000 + 32 * index, int(row["rva"], 16))
            self.assertEqual(0x140001000 + 32 * index, int(row["address"], 16))
            self.assertEqual(hashlib.sha256(before[f.binary][0x200 + 32 * index:0x210 + 32 * index]).hexdigest(), row["region_sha256"])
        for path, data in before.items():
            self.assertEqual(data, path.read_bytes())
        self.assertEqual(before[f.binary], (f.output / "Celeste+.exe").read_bytes())
        self.assertEqual(before[f.original], (f.output / "Celeste+.original.map").read_bytes())
        self.assertEqual(before[f.candidate], (f.output / "candidate.json").read_bytes())

    def test_only_seven_symbol_fields_change_with_crlf_and_columns_preserved(self):
        f = self.fixture
        result = f.prepare()
        expected = f.original.read_bytes()
        for row in result["functions"]:
            label = row["symbol"].encode()
            self.assertEqual(1, expected.count(label))
            expected = expected.replace(label, row["alias"].encode().ljust(len(label)))
        self.assertEqual(expected, (f.output / "Celeste+.map").read_bytes())
        self.assertEqual(len(expected), f.original.stat().st_size)

    def test_existing_output_is_never_reused(self):
        f = self.fixture
        output = f.root / "existing"
        output.mkdir()
        sentinel = output / "keep.txt"
        sentinel.write_bytes(b"untouched")
        with self.assertRaises(FileExistsError):
            mapping.prepare(f.binary, f.original, f.candidate, output)
        self.assertEqual(b"untouched", sentinel.read_bytes())
        self.assertEqual([sentinel], list(output.iterdir()))

    def test_wrong_binary_and_wrong_original_map_hashes_are_rejected_before_output(self):
        for field in ("sha256", "privateMapSha256"):
            f = self.fixture
            f.bind()
            f.record[field] = "0" * 64
            f.write_record()
            with self.subTest(field=field), self.assertRaises(ValueError):
                f.prepare()
            self.assertFalse(f.output.exists())

    def test_wrong_candidate_product_type_and_status_are_rejected(self):
        f = self.fixture
        for field, bad in (("schema", "other"), ("product", "celeste"), ("target", "linux-x64"),
                           ("artifact", "other.exe"), ("status", "signed"), ("metadataCheck", "failed"),
                           ("size", True), ("size", 1)):
            f.bind()
            f.record[field] = bad
            f.write_record()
            with self.subTest(field=field, bad=bad), self.assertRaises(ValueError):
                f.prepare()

    def test_invalid_json_duplicate_fields_and_nonobjects_are_rejected(self):
        f = self.fixture
        for value in (b"[]", b"{", b'{"schema":1,"schema":2}', b'{"extra":NaN}', b"\xff"):
            f.candidate.write_bytes(value)
            with self.subTest(value=value), self.assertRaises(ValueError):
                f.prepare()

    def test_missing_duplicate_or_nonfunction_target_is_rejected(self):
        f = self.fixture
        original = f.original.read_bytes()
        entry = next(mapping.SYMBOL.finditer(original))
        line = original[entry.start():entry.end()]
        for data in (original.replace(line, b""), original + line + b"\r\n",
                     original.replace(line, line.replace(b" f ", b"   "))):
            f.original.write_bytes(data)
            f.bind()
            with self.assertRaises(ValueError):
                f.prepare()

    def test_preexisting_alias_is_rejected(self):
        f = self.fixture
        f.original.write_bytes(f.original.read_bytes() + b" celeste_verify_native\r\n")
        f.bind()
        with self.assertRaisesRegex(ValueError, "Alias already"):
            f.prepare()

    def test_map_base_segment_and_va_must_agree_with_pe(self):
        f = self.fixture
        original = f.original.read_bytes()
        changes = ((b"Preferred load address is 0000000140000000", b"Preferred load address is 0000000150000000"),
                   (b"0001:00000000", b"0002:00000000"),
                   (b"0001:00000000", b"0001:00000001"),
                   (b"0000000140001000", b"0000000140001001"))
        for old, new in changes:
            f.original.write_bytes(original.replace(old, new, 1))
            f.bind()
            with self.subTest(old=old), self.assertRaises(ValueError):
                f.prepare()

    def test_alias_and_interior_symbol_ambiguity_is_rejected(self):
        f = self.fixture
        original = f.original.read_bytes()
        for offset in (0, 1, 15):
            f.original.write_bytes(original + f" 0001:{offset:08x} alias_other 000000014000{0x1000 + offset:04x} f fixture.o\r\n".encode())
            f.bind()
            with self.subTest(offset=offset), self.assertRaisesRegex(ValueError, "aliased or interior"):
                f.prepare()

    def test_map_target_must_start_at_a_pdata_entry(self):
        f = self.fixture
        original = f.original.read_bytes().replace(b"0001:00000000", b"0001:00000001", 1).replace(b"0000000140001000", b"0000000140001001", 1)
        f.original.write_bytes(original)
        f.bind()
        with self.assertRaisesRegex(ValueError, "begin a PE"):
            f.prepare()

    def test_signed_truncated_or_wrong_architecture_pe_is_rejected(self):
        f = self.fixture
        original = f.binary.read_bytes()
        for offset, fmt, values in ((132, "<H", (0x14c,)), (296, "<II", (0xd00, 16)),
                                    (60, "<I", (0x100000,)), (152, "<H", (0x10b,))):
            f.binary.write_bytes(original)
            f.binary_change(offset, fmt, *values)
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                f.prepare()
        f.binary.write_bytes(original[:500])
        f.bind()
        with self.assertRaises(ValueError):
            f.prepare()

    def test_section_ranges_flags_and_pdata_ranges_are_checked(self):
        f = self.fixture
        original = f.binary.read_bytes()
        for offset, fmt, values in ((412, "<I", (128,)), (444, "<I", (0x1000,)),
                                    (452, "<I", (0x200,)), (428, "<I", (0xe0000020,)),
                                    (428, "<I", (0x40000040,)), (288, "<II", (0x2000, 13)),
                                    (288, "<II", (0, 0)), (0x804, "<I", (0x1040,)),
                                    (0x804, "<I", (0x1000,)), (0x808, "<I", (0x8000,))):
            f.binary.write_bytes(original)
            f.binary_change(offset, fmt, *values)
            with self.subTest(offset=offset, values=values), self.assertRaises(ValueError):
                f.prepare()

    def test_chained_handlers_and_truncated_unwind_records_are_rejected(self):
        f = self.fixture
        original = f.binary.read_bytes()
        for value in (2, 9, 33):
            f.binary.write_bytes(original)
            f.binary_change(0xa00, "<B", value)
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "unreviewed"):
                f.prepare()
        f.binary.write_bytes(original)
        f.binary_change(0x808, "<I", 0x33fc)
        f.binary_change(0xdfc, "<BBBB", 1, 0, 10, 0)
        with self.assertRaises(ValueError):
            f.prepare()

    def test_exception_directory_count_is_bounded_before_range_parsing(self):
        f = self.fixture
        f.binary_change(292, "<I", (mapping.MAX_FUNCTIONS + 1) * 12)
        with self.assertRaisesRegex(ValueError, "malformed PE exception directory"):
            f.prepare()
        self.assertFalse(f.output.exists())

    def test_any_extra_map_edit_is_rejected_even_when_record_hash_is_updated(self):
        f = self.fixture
        f.prepare()
        aliased = f.output / "Celeste+.map"
        record = f.output / "mapping.json"
        aliased.write_bytes(aliased.read_bytes().replace(b"Unchanged footer", b"Modified footer!"))
        modified = json.loads(record.read_text())
        modified["aliased_map_sha256"] = hashlib.sha256(aliased.read_bytes()).hexdigest()
        record.write_text(json.dumps(modified))
        with self.assertRaisesRegex(ValueError, "beyond the seven"):
            f.validate()

    def test_mapping_record_cannot_invent_coverage_addresses_or_ranges(self):
        f = self.fixture
        expected = f.prepare()
        path = f.output / "mapping.json"
        for field, value in (("status", "verified-protected"), ("candidate_sha256", "0" * 64),
                             ("functions", []), ("binary_size", float(expected["binary_size"]))):
            record = copy.deepcopy(expected)
            record[field] = value
            path.write_text(json.dumps(record))
            with self.subTest(field=field), self.assertRaises(ValueError):
                f.validate()
        for field, value in (("symbol", "different"), ("alias", "different"), ("address", "0000000140002000"),
                             ("end_rva", "00005000"), ("size", 16.0), ("region_sha256", "f" * 64)):
            record = copy.deepcopy(expected)
            record["functions"][0][field] = value
            path.write_text(json.dumps(record))
            with self.subTest(field=field), self.assertRaises(ValueError):
                f.validate()

    def test_bundle_executable_and_candidate_remain_bound_on_revalidation(self):
        f = self.fixture
        f.prepare()
        binary = f.output / "Celeste+.exe"
        original = binary.read_bytes()
        binary.write_bytes(original[:-1] + b"x")
        with self.assertRaises(ValueError):
            f.validate()
        binary.write_bytes(original)
        candidate = f.output / "candidate.json"
        record = json.loads(candidate.read_text())
        record["extra"] = "different candidate metadata"
        candidate.write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError, "Mapping record differs"):
            f.validate()

    def test_real_local_candidate_map_when_available(self):
        source = Path(__file__).resolve().parents[2] / "build/protection-20260927/native-input"
        if not all((source / name).is_file() for name in ("Celeste+.exe", "Celeste+.map", "candidate.json")):
            self.skipTest("Local compiler candidate is unavailable; synthetic parser regressions remain active")
        original = (source / "Celeste+.exe").read_bytes()
        output = self.fixture.root / "actual-candidate"
        record = mapping.prepare(source / "Celeste+.exe", source / "Celeste+.map", source / "candidate.json", output)
        self.assertEqual(7, len(record["functions"]))
        self.assertEqual(original, (output / "Celeste+.exe").read_bytes())
        self.assertEqual(original, (source / "Celeste+.exe").read_bytes())
        self.assertEqual(record, mapping.validate_alias_map(output / "Celeste+.exe", output / "Celeste+.original.map",
                                                           output / "Celeste+.map", output / "mapping.json", output / "candidate.json"))


if __name__ == "__main__":
    unittest.main()
