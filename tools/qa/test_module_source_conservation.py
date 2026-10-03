"""The explicit host/runtime mirror is exact and never silently synchronized."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import sync_module_delivery_sources as subject


class ConservationCheck(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="celeste-paired-delivery-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for source, _ in subject.PAIRS:
            path = self.root / source
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"// exact paired source\r\n")

    def test_check_never_creates_missing_copies(self):
        with self.assertRaisesRegex(ValueError, "Missing paired"):
            subject.check(self.root)
        self.assertFalse((self.root / "runtime-overlay").exists())

    def test_explicit_sync_then_all_eight_pairs_are_exact(self):
        self.assertEqual(len(subject.check(self.root, sync=True)), 8)
        self.assertEqual(subject.check(self.root), subject.check(self.root, sync=True))

    def test_mutated_runtime_copy_is_refused_and_unchanged(self):
        subject.check(self.root, sync=True)
        target = self.root / subject.PAIRS[0][1]
        target.write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "differs"):
            subject.check(self.root)
        self.assertEqual(target.read_bytes(), b"changed")

    def test_changed_canonical_source_requires_explicit_resync(self):
        subject.check(self.root, sync=True)
        source = self.root / subject.PAIRS[-1][0]
        source.write_bytes(b"new signer")
        with self.assertRaisesRegex(ValueError, "differs"):
            subject.check(self.root)
        subject.check(self.root, sync=True)
        self.assertEqual((self.root / subject.PAIRS[-1][1]).read_bytes(), b"new signer")


if __name__ == "__main__":
    unittest.main()
