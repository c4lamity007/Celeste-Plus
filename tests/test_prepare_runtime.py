"""Offline owned fixtures; the media check copies resources with Gradle, never runs a DLL or game."""
from pathlib import Path
import importlib.util
import io
import json
import os
import shutil
import subprocess
import tarfile
import tempfile
import unittest
import zlib

REPO = Path(__file__).resolve().parents[1]
QA = REPO/'build/module-qa/runtime-preparation'
QA.mkdir(parents=True, exist_ok=True)
SPEC = importlib.util.spec_from_file_location('prepare_runtime', REPO/'tools/prepare_runtime.py')
tool = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tool)
A = 'src/main/java/example/A.java'
B = 'src/main/java/example/B.java'
UNCHANGED = 'src/main/resources/unchanged.json'
ORIGINAL = b'package example; class A {}\n'
OVERLAY = b'package example; class A { int version = 2; }\n'
PNG = 'src/main/resources/assets/celeste/textures/hud/glyphs/effects.png'


def png(width=1, height=1):
    def chunk(kind, data):
        return len(data).to_bytes(4, 'big') + kind + data + zlib.crc32(kind + data).to_bytes(4, 'big')
    header = width.to_bytes(4, 'big') + height.to_bytes(4, 'big') + bytes([8, 6, 0, 0, 0])
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(bytes(5))) + chunk(b'IEND', b'')


def dll(machine=0x8664, characteristics=0x2002):
    data = bytearray(154)
    data[:2] = b'MZ'
    data[60:64] = (128).to_bytes(4, 'little')
    data[128:132] = b'PE\x00\x00'
    data[132:134] = machine.to_bytes(2, 'little')
    data[150:152] = characteristics.to_bytes(2, 'little')
    data[152:154] = b'\x0b\x02'
    return bytes(data)


class PrepareChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = Path(tempfile.mkdtemp(prefix='owned-fixtures-', dir=QA)).resolve()
        assert cls.root.is_relative_to(QA.resolve())
        cls.core = cls.root/'core'
        cls.core.mkdir()
        def git(*args):
            return subprocess.check_output(['git', *args], cwd=cls.core, stderr=subprocess.PIPE)
        git('init')
        git('config', 'core.autocrlf', 'false')
        (cls.core/A).parent.mkdir(parents=True)
        (cls.core/A).write_bytes(ORIGINAL)
        (cls.core/UNCHANGED).parent.mkdir(parents=True)
        (cls.core/UNCHANGED).write_bytes(b'{"fixture":true}\n')
        git('add', '--all')
        git('-c', 'user.name=Owned fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-m', 'Owned offline fixture')
        cls.commit = git('rev-parse', 'HEAD').decode().strip()
        # Mirrors the actual Windows failure: archive must not inherit this setting.
        git('config', 'core.autocrlf', 'true')
        # Preparation must use the pinned object, not dirty checkout files.
        (cls.core/A).write_bytes(b'Uncommitted fixture content must not be copied.\n')

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp(prefix='repo-', dir=self.root))
        self.entries = [
            {'path': A, 'sha256': tool.sha(OVERLAY), 'baseSha256': tool.sha(ORIGINAL)},
            {'path': B, 'sha256': tool.sha(b'package example; class B {}\n'), 'baseSha256': None}]
        for name, content in ((A, OVERLAY), (B, b'package example; class B {}\n')):
            p=self.repo/'runtime-overlay'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(content)
        self.write_contract()

    def write_contract(self, commit=None):
        manifest={'schema':tool.OVERLAY_SCHEMA,'sourceState':'current-development-source-not-published-payload','files':self.entries}
        encoded=(json.dumps(manifest,indent=2)+'\n').encode()
        (self.repo/'runtime-overlay/manifest.json').write_bytes(encoded)
        self.lock={'schema':tool.LOCK_SCHEMA,'repository':tool.REPOSITORY,'commit':self.commit if commit is None else commit,'overlayManifestSha256':tool.sha(encoded)}
        (self.repo/'core.lock.json').write_text(json.dumps(self.lock),encoding='utf-8')

    def add_overlay(self, name, data):
        path = self.repo/'runtime-overlay'/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        self.entries.append({'path': name, 'sha256': tool.sha(data), 'baseSha256': None})
        self.write_contract()

    def test_success_ignores_autocrlf_and_dirty_checkout_preserving_unrelated_source(self):
        output=tool.prepare(self.repo,self.core)
        self.assertEqual(output,self.repo/'build/runtime-core')
        self.assertEqual((output/A).read_bytes(),OVERLAY)
        self.assertEqual((output/B).read_bytes(),b'package example; class B {}\n')
        self.assertEqual((output/UNCHANGED).read_bytes(),b'{"fixture":true}\n')
        report=json.loads((output/'.celeste-plus-runtime.json').read_text())
        self.assertEqual(report['coreCommit'],self.commit)
        self.assertTrue(report['complete'])
        self.assertEqual((self.core/A).read_bytes(),b'Uncommitted fixture content must not be copied.\n')

    def test_existing_output_is_not_reused_or_overwritten(self):
        output=tool.prepare(self.repo,self.core)
        sentinel=output/'keep.txt';sentinel.write_bytes(b'keep existing output')
        with self.assertRaisesRegex(ValueError,'already exists'):
            tool.prepare(self.repo,self.core)
        self.assertEqual(sentinel.read_bytes(),b'keep existing output')
        self.assertEqual((output/A).read_bytes(),OVERLAY)

    def test_overlay_hash_mismatch_fails_before_output(self):
        (self.repo/'runtime-overlay'/A).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'Overlay source hash mismatch'):
            tool.prepare(self.repo,self.core)
        self.assertFalse((self.repo/'build').exists())

    def test_correctly_hashed_crlf_source_is_rejected_before_git_can_normalize_it(self):
        encoded = OVERLAY.replace(b'\n', b'\r\n')
        (self.repo/'runtime-overlay'/A).write_bytes(encoded)
        self.entries[0]['sha256'] = tool.sha(encoded)
        self.write_contract()
        with self.assertRaisesRegex(ValueError, 'must use LF line endings'):
            tool.prepare(self.repo, self.core)
        self.assertFalse((self.repo/'build').exists())

    def test_manifest_hash_mismatch_fails_before_output(self):
        with (self.repo/'runtime-overlay/manifest.json').open('ab') as stream:stream.write(b' ')
        with self.assertRaisesRegex(ValueError,'manifest hash differs'):
            tool.prepare(self.repo,self.core)
        self.assertFalse((self.repo/'build').exists())

    def test_base_hash_mismatch_applies_nothing(self):
        self.entries[0]['baseSha256']='0'*64;self.write_contract()
        with self.assertRaisesRegex(ValueError,'Base source hash mismatch'):
            tool.prepare(self.repo,self.core)
        self.assertFalse((self.repo/'build/runtime-core/src').exists())
        self.assertFalse((self.repo/'build/runtime-core/.celeste-plus-runtime.json').exists())

    def test_addition_cannot_replace_an_existing_base_file(self):
        self.entries[0]['baseSha256']=None;self.write_contract()
        with self.assertRaisesRegex(ValueError,'addition already exists'):
            tool.prepare(self.repo,self.core)
        self.assertFalse((self.repo/'build/runtime-core/src').exists())

    def test_modified_entry_requires_existing_base_file(self):
        self.entries[1]['baseSha256']='0'*64;self.write_contract()
        with self.assertRaisesRegex(ValueError,'base source is absent'):
            tool.prepare(self.repo,self.core)
        self.assertFalse((self.repo/'build/runtime-core/src').exists())

    def test_path_traversal_absolute_and_windows_special_paths_rejected(self):
        for name in ('../escape.java','src/main/../../escape.java','/outside.java','C:/outside.java',
                     'src/main/java/../A.java','src/main/java/CON.java','src/main/java/A.java:stream','src\\main\\A.java'):
            with self.subTest(name=name),self.assertRaises(ValueError):
                tool.relative_path(name)

    def test_case_collision_and_binary_overlay_rejected(self):
        self.entries.append({**self.entries[0],'path':A.replace('/example/','/EXAMPLE/')});self.write_contract()
        with self.assertRaisesRegex(ValueError,'case-colliding'):tool.prepare(self.repo,self.core)
        self.entries.pop();self.entries[0]['path']='src/main/resources/model.glb';self.write_contract()
        with self.assertRaisesRegex(ValueError,'source text'):tool.prepare(self.repo,self.core)
        self.assertFalse((self.repo/'build').exists())

    def test_font_and_hud_assets_and_attribution_preserve_exact_bytes(self):
        assets = {
            PNG: png(),
            'src/main/resources/assets/celeste/fonts/test/atlas.png': png(),
            'src/main/resources/assets/celeste/textures/hud/glyphs/source/effects.svg': b'<svg xmlns="http://www.w3.org/2000/svg"/>\n',
            'src/main/resources/META-INF/licenses/celeste/Hud-Glyphs-Provenance.txt': b'Authored glyph source and license attribution.\n',
        }
        for name, data in assets.items():
            self.add_overlay(name, data)
        output = tool.prepare(self.repo, self.core)
        for name, data in assets.items():
            self.assertEqual((output/name).read_bytes(), data)

    def test_png_corruption_or_unbounded_dimensions_fail_before_output(self):
        for data in (b'MZnot-an-image', png()[:-1], png() + b'trailing payload', png(4097), png(0),
                     png()[:40] + bytes([png()[40] ^ 1]) + png()[41:]):
            with self.subTest(size=len(data)):
                self.add_overlay(PNG, data)
                with self.assertRaisesRegex(ValueError, 'PNG'):
                    tool.prepare(self.repo, self.core)
                self.entries.pop()
                self.assertFalse((self.repo/'build').exists())

    def test_png_asset_hash_mismatch_fails_before_output(self):
        self.add_overlay(PNG, png())
        (self.repo/'runtime-overlay'/PNG).write_bytes(png(2))
        with self.assertRaisesRegex(ValueError, 'source hash mismatch'):
            tool.prepare(self.repo, self.core)
        self.assertFalse((self.repo/'build').exists())

    def test_assets_cannot_expand_overlay_to_executables_or_unreviewed_locations(self):
        for name in ('src/main/java/icon.png', 'src/main/resources/icon.png',
                     'src/main/resources/assets/celeste/textures/hud/glyphs/run.dll',
                     'src/main/resources/unreviewed.svg', 'src/main/resources/config.txt'):
            self.add_overlay(name, png())
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'reviewed font/HUD'):
                tool.prepare(self.repo, self.core)
            self.entries.pop()
            self.assertFalse((self.repo/'build').exists())

    def test_oversized_asset_is_rejected_before_output(self):
        self.add_overlay(PNG, png() + bytes(4 * 1024 * 1024))
        with self.assertRaisesRegex(ValueError, 'exceeds bounds'):
            tool.prepare(self.repo, self.core)
        self.assertFalse((self.repo/'build').exists())

    def test_unpinned_or_wrong_repository_refused(self):
        for update in ({'commit':None},{'commit':'main'},{'repository':'https://example.invalid/unreviewed.git'}):
            changed={**self.lock,**update}
            (self.repo/'core.lock.json').write_text(json.dumps(changed),encoding='utf-8')
            with self.subTest(update=list(update)),self.assertRaises(ValueError):tool.prepare(self.repo,self.core)
        self.assertFalse((self.repo/'build').exists())

    def test_archive_links_and_escapes_rejected_without_extraction(self):
        for name,kind in (('linked',tarfile.SYMTYPE),('linked',tarfile.LNKTYPE),('../escape',tarfile.REGTYPE),('.prepare/inject',tarfile.REGTYPE),('NUL.txt',tarfile.REGTYPE)):
            data=io.BytesIO()
            with tarfile.open(fileobj=data,mode='w') as tar:
                info=tarfile.TarInfo(name);info.type=kind;info.linkname='../../outside';tar.addfile(info)
            data.seek(0)
            with tarfile.open(fileobj=data,mode='r:') as tar,self.subTest(name=name,kind=kind),self.assertRaises(ValueError):
                tool.validate_archive(tar,{'files':[]})

    def test_preexisting_build_link_is_rejected(self):
        outside=self.root/'outside-owned-fixture';outside.mkdir(exist_ok=True)
        try:(self.repo/'build').symlink_to(outside,target_is_directory=True)
        except OSError:self.skipTest('Host does not permit symlink fixture creation')
        with self.assertRaisesRegex(ValueError,'Links and reparse'):
            tool.prepare(self.repo,self.core)
        self.assertFalse((outside/'runtime-core').exists())

    def test_overlay_cannot_create_case_alias_of_locked_core(self):
        data=io.BytesIO()
        with tarfile.open(fileobj=data,mode='w') as tar:
            info=tarfile.TarInfo(A);info.size=len(ORIGINAL);tar.addfile(info,io.BytesIO(ORIGINAL))
        data.seek(0)
        with tarfile.open(fileobj=data,mode='r:') as tar,self.assertRaisesRegex(ValueError,'path case differs'):
            tool.validate_archive(tar,{'files':[{'path':A.replace('/A.java','/a.java'),'baseSha256':None}]})

    def test_gradle_command_handles_posix_mode_and_windows_paths_with_spaces(self):
        destination=Path('source with spaces')
        self.assertEqual(tool.gradle_command(destination,windows=False),['sh',str(destination/'gradlew'),'--no-daemon',':build'])
        self.assertEqual(tool.gradle_command(destination,windows=True),[str(destination/'gradlew.bat'),'--no-daemon',':build'])

    def test_media_bridge_rejects_wrong_platform_format_or_architecture(self):
        bridge = self.repo/'selected.dll'
        bridge.write_bytes(dll())
        self.assertEqual(tool.media_bridge(bridge, windows=True), bridge.resolve())
        with self.assertRaisesRegex(ValueError, 'Windows-only'):
            tool.media_bridge(bridge, windows=False)
        for data in (b'not a DLL', bytes(154), dll(machine=0x14c), dll(characteristics=2)):
            bridge.write_bytes(data)
            with self.subTest(data=data[:2]), self.assertRaises(ValueError):
                tool.media_bridge(bridge, windows=True)
        with self.assertRaises(ValueError):
            tool.media_bridge(self.repo, windows=True)

    def test_media_override_replaces_only_root_media_resource_without_editing_core(self):
        if not shutil.which('java'):
            self.skipTest('Java unavailable for offline Gradle resource fixture')
        destination = self.repo/'media fixture'
        destination.mkdir()
        (destination/'.prepare').mkdir()
        selected = self.repo/'explicit bridge.dll'
        selected.write_bytes(dll())
        default = destination/'legacy/MediaPlayerInfo.dll'
        default.parent.mkdir()
        default.write_bytes(b'old media must not be packaged')
        resource = destination/'src/main/resources'
        resource.mkdir(parents=True)
        (resource/'keep.txt').write_bytes(b'keep unrelated runtime resources')
        (resource/'mediaplayerinfo/natives/win').mkdir(parents=True)
        (resource/'mediaplayerinfo/natives/win/CelesteMediaCleanup.dll').write_bytes(b'keep the distinct cleanup bridge')
        child = destination/'child'
        (child/'src/main/resources/mediaplayerinfo/natives/win').mkdir(parents=True)
        child_media = child/'src/main/resources/mediaplayerinfo/natives/win/MediaPlayerInfo.dll'
        child_media.write_bytes(b'child resource must stay unchanged')
        (child/'build.gradle').write_text("plugins { id 'java' }\n", encoding='utf-8')
        (destination/'settings.gradle').write_text("rootProject.name = 'owned-media-fixture'\ninclude 'child'\n", encoding='utf-8')
        build_source = "plugins { id 'java' }\nprocessResources { from('legacy/MediaPlayerInfo.dll') { into 'mediaplayerinfo/natives/win' } }\n"
        (destination/'build.gradle').write_text(build_source, encoding='utf-8')
        init = tool.media_override(destination)
        command = ([str(REPO/'gradlew.bat')] if os.name == 'nt' else ['sh', str(REPO/'gradlew')])
        command += ['--no-daemon', '--offline', '-p', str(destination), '--init-script', str(init), ':processResources', ':child:processResources']
        environment = os.environ.copy()
        environment['MEDIA_PLAYER_INFO_DLL'] = str(tool.media_bridge(selected, windows=True))
        result = subprocess.run(command, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
        self.assertEqual(result.returncode, 0, result.stdout.decode(errors='replace')[-6000:])
        self.assertEqual((destination/'build/resources/main/mediaplayerinfo/natives/win/MediaPlayerInfo.dll').read_bytes(), selected.read_bytes())
        self.assertEqual((destination/'build/resources/main/keep.txt').read_bytes(), b'keep unrelated runtime resources')
        self.assertEqual((destination/'build/resources/main/mediaplayerinfo/natives/win/CelesteMediaCleanup.dll').read_bytes(), b'keep the distinct cleanup bridge')
        self.assertEqual((child/'build/resources/main/mediaplayerinfo/natives/win/MediaPlayerInfo.dll').read_bytes(), child_media.read_bytes())
        self.assertEqual((destination/'build.gradle').read_text(), build_source)
        with self.assertRaises(FileExistsError):
            tool.media_override(destination)


if __name__=='__main__':
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(PrepareChecks))
    report={'status':'PASS' if result.wasSuccessful() else 'FAIL','tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped),'live_actions':False,'helper_sha256':tool.sha((REPO/'tools/prepare_runtime.py').read_bytes())}
    (QA/'fixture-result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    raise SystemExit(0 if result.wasSuccessful() else 1)
