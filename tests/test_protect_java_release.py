"""Offline release guard tests. Synthetic producer files are never executed."""
from pathlib import Path
from types import SimpleNamespace
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
SPEC = importlib.util.spec_from_file_location('java_release', ROOT / 'tools/protect_java_release.py')
tool = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tool)


class ProtectionRunnerCheck(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.jar = self.root / 'producer.jar'
        self.dependency = self.root / 'lib/dependency.jar'
        self.dependency.parent.mkdir()
        self.dependency.write_bytes(b'fake dependency; never executed')
        self.producer()

    def producer(self, dependency='lib/dependency.jar'):
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nMain-Class: dev.local.obfuscator.App\r\nClass-Path: ' + dependency + '\r\n\r\n')

    def test_bundle_pin_covers_all_manifest_dependencies_and_exact_bytes(self):
        files, first = tool.producer_bundle(self.jar)
        self.assertEqual(set(files), {'producer.jar', 'lib/dependency.jar'})
        self.dependency.write_bytes(b'changed dependency')
        _, changed = tool.producer_bundle(self.jar)
        self.assertNotEqual(first['bundle_sha256'], changed['bundle_sha256'])

    def test_replaced_archive_path_cannot_change_manifest_snapshot(self):
        expected = tool.producer_bundle(self.jar)[1]
        original_zip = zipfile.ZipFile
        def replace(source, *args, **kwargs):
            self.jar.write_bytes(b'changed after reading pinned bytes')
            return original_zip(source, *args, **kwargs)
        with patch.object(tool.zipfile, 'ZipFile', side_effect=replace):
            _, actual = tool.producer_bundle(self.jar)
        self.assertEqual(expected, actual)

    def test_unsafe_manifest_references_are_rejected(self):
        for name in ('../elsewhere.jar', '/absolute.jar', 'https://example.invalid/tool.jar', 'lib\\dependency.jar'):
            self.producer(name)
            with self.subTest(name=name), self.assertRaises(ValueError):
                tool.producer_bundle(self.jar)

    def test_wrong_producer_pin_prevents_output_and_execution(self):
        args = SimpleNamespace(output=self.root/'out', release_id='test-release', obfuscator=self.jar, tool_sha256='0'*64)
        with patch.object(subprocess, 'run') as process, self.assertRaisesRegex(ValueError, 'reviewed pin'):
            tool.run(args)
        process.assert_not_called()
        self.assertFalse(args.output.exists())

    def test_existing_output_is_preserved(self):
        output=self.root/'out';output.mkdir();(output/'keep').write_bytes(b'existing')
        with self.assertRaisesRegex(ValueError, 'fresh'):
            tool.run(SimpleNamespace(output=output))
        self.assertEqual((output/'keep').read_bytes(), b'existing')

    def test_reviewed_profiles_keep_loader_and_payload_contracts(self):
        for name in ('02-bootstrap-a-implementation.json', '03-bootstrap-b-compatible.json', '04-payload-protected-v2.json'):
            result=tool.checked_profile(name, self.root/'input.jar',self.root/'output.jar',[self.jar])
            self.assertEqual(result['dependencyJars'], [str(self.jar)])
            self.assertFalse(result['nativeTranspile'])
        payload=tool.checked_profile('04-payload-protected-v2.json',self.jar,self.jar,[])
        self.assertFalse(payload['renameClasses']);self.assertFalse(payload['renamePrivateMembers'])
        a=tool.checked_profile('02-bootstrap-a-implementation.json',self.jar,self.jar,[])
        self.assertIn('dev.celestebeta.bootstrap.external.BootstrapHost',a['keepPatterns'])

    def test_modified_profile_cannot_silently_enable_native_or_payload_renaming(self):
        name='04-payload-protected-v2.json'
        original=json.loads((tool.PROFILES/name).read_bytes())
        for key in ('nativeTranspile', 'targetedNativeVirtualization', 'crazyObfuscation', 'relocateAssets', 'renameClasses', 'renamePrivateMembers'):
            (self.root/name).write_text(json.dumps(original|{key:True}))
            with patch.object(tool,'PROFILES',self.root),self.subTest(key=key),self.assertRaises(ValueError):
                tool.checked_profile(name,self.jar,self.jar,[])

    def test_duplicate_or_missing_parent_dependencies_fail(self):
        manifest=self.root/'parents.txt'
        for content in ('', str(self.jar)+'\n'+str(self.jar), str(self.root/'missing.jar')):
            manifest.write_text(content)
            with self.subTest(content=content),self.assertRaises((ValueError, FileNotFoundError)):
                tool.checked_parents(manifest)

    def test_failed_stage_leaves_failed_receipt_and_no_release(self):
        parents=self.root/'parents.txt';parents.write_text(str(self.jar)+'\n')
        descriptor=self.root/'descriptor.py';descriptor.write_text('# owned fixture')
        java_home=self.root/'jdk';(java_home/'bin').mkdir(parents=True)
        (java_home/'bin'/('java.exe' if tool.os.name=='nt' else 'java')).write_bytes(b'never executed')
        args=SimpleNamespace(output=self.root/'failed-output',release_id='test-release',obfuscator=self.jar,
            tool_sha256=tool.producer_bundle(self.jar)[1]['bundle_sha256'],parent_classpath=parents,
            input=self.jar,descriptor_module=descriptor,java_home=java_home,offline=True)
        with patch.object(tool,'source_inputs',return_value={}), \
             patch.object(subprocess,'run',return_value=SimpleNamespace(returncode=17)), \
             self.assertRaisesRegex(ValueError,'auth-namespace-compile'):
            tool.run(args)
        receipt=json.loads((args.output/'receipt.json').read_bytes())
        self.assertEqual(receipt['status'],'failed-not-published')
        self.assertEqual(receipt['steps'][0]['exit_code'],17)
        self.assertFalse(receipt['deployed'])
        self.assertFalse((args.output/'release').exists())


if __name__ == '__main__':
    unittest.main()
