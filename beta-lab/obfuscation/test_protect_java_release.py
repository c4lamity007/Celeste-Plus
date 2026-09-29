"""Source receipt regressions; no Java build, account, or generated runtime needed."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
import protect_java_release as subject


class SourceReceiptCheck(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='celeste-java-receipt-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.name = 'src/main/java/dev/c4lam/celeste/auth/Fixture.java'
        self.source = self.root / 'runtime-overlay' / self.name
        self.source.parent.mkdir(parents=True)
        self.source.write_bytes(b'package dev.c4lam.celeste.auth; class Fixture {}\n')
        for name in ('build.gradle', 'gradle.properties', 'settings.gradle',
                     'tools/protect_java_release.py', 'tools/packaging/AuthNamespacePass.java', 'tools/prepare_runtime.py', 'tools/validate_package.py', 'tools/build_media_cleanup.py', 'tools/native_auth_resources.py',
                     'beta-lab/obfuscation/check.init.gradle', 'beta-lab/obfuscation/prepare_obfuscation.py',
                     'beta-lab/release-package/package_beta.py', 'beta-lab/example/src/Host.java',
                     'beta-lab/obfuscation/profiles/test.json'):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'fixture input\n')
        self.entries = [{'path': self.name, 'sha256': subject.digest(self.source.read_bytes()), 'baseSha256': None}]
        self.write_contract()
        root_patch = patch.object(subject, 'ROOT', self.root)
        profile_patch = patch.object(subject, 'PROFILES', self.root / 'beta-lab/obfuscation/profiles')
        root_patch.start(); profile_patch.start()
        self.addCleanup(root_patch.stop); self.addCleanup(profile_patch.stop)

    def write_contract(self):
        manifest = json.dumps({'schema': subject.prepare_runtime.OVERLAY_SCHEMA,
            'sourceState': 'current-development-source-not-published-payload', 'files': self.entries}).encode()
        (self.root / 'runtime-overlay/manifest.json').write_bytes(manifest)
        (self.root / 'core.lock.json').write_text(json.dumps({'schema': subject.prepare_runtime.LOCK_SCHEMA,
            'repository': subject.prepare_runtime.REPOSITORY, 'commit': '1' * 40,
            'overlayManifestSha256': subject.digest(manifest)}), encoding='utf-8')

    def test_receipt_binds_actual_overlay_and_build_inputs(self):
        receipt = subject.source_inputs()
        self.assertEqual(receipt['runtime-overlay/' + self.name], subject.digest(self.source.read_bytes()))
        self.assertIn('tools/prepare_runtime.py', receipt)
        self.assertIn('tools/packaging/AuthNamespacePass.java', receipt)
        self.assertIn('tools/build_media_cleanup.py', receipt)
        self.assertIn('gradle.properties', receipt)
        self.assertIn('settings.gradle', receipt)
        self.assertIn('beta-lab/example/src/Host.java', receipt)
        self.assertTrue(all('\\' not in name for name in receipt))

    def test_modified_source_with_unchanged_manifest_is_rejected(self):
        self.source.write_bytes(b'class Changed {}\n')
        with self.assertRaisesRegex(ValueError, 'Overlay source hash mismatch'):
            subject.source_inputs()

    def test_reviewed_source_update_changes_receipt(self):
        before = subject.source_inputs()
        self.source.write_bytes(b'class ReviewedChange {}\n')
        self.entries[0]['sha256'] = subject.digest(self.source.read_bytes())
        self.write_contract()
        self.assertNotEqual(before, subject.source_inputs())

    def test_unpinned_manifest_is_rejected(self):
        with (self.root / 'runtime-overlay/manifest.json').open('ab') as target:
            target.write(b' ')
        with self.assertRaisesRegex(ValueError, 'differs from core lock'):
            subject.source_inputs()

    def test_duplicate_or_escaping_overlay_path_is_rejected(self):
        for name in (self.name, '../outside.java', 'src/main/java/../../outside.java'):
            with self.subTest(name=name):
                self.entries = [self.entries[0], {'path': name, 'sha256': '0' * 64, 'baseSha256': None}]
                self.write_contract()
                with self.assertRaises(ValueError):
                    subject.source_inputs()


if __name__ == '__main__':
    unittest.main()
