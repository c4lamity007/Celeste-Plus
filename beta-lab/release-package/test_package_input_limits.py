"""The build input may combine two transport parts without relaxing their bounds."""
import argparse
import io
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile

import package_beta as package


def archive(entries, *, compression=zipfile.ZIP_STORED):
    result = io.BytesIO()
    with zipfile.ZipFile(result, 'w', compression=compression) as stream:
        for name, contents in entries:
            stream.writestr(name, contents)
    return result.getvalue()


def fixture_dll():
    data = bytearray(154)
    data[:2] = b'MZ'
    struct.pack_into('<I', data, 60, 128)
    data[128:132] = b'PE\0\0'
    struct.pack_into('<H', data, 132, 0x8664)
    struct.pack_into('<H', data, 150, 0x2002)
    struct.pack_into('<H', data, 152, 0x20B)
    return bytes(data)


class PackageInputLimits(unittest.TestCase):
    def test_production_limits_are_unchanged(self):
        self.assertEqual(package.MAX_PART, 64 * 1024 * 1024)
        self.assertEqual(package.MAX_TOTAL, 128 * 1024 * 1024)
        self.assertEqual(package.native_auth_resources.MAX_ENTRY, 8 * 1024 * 1024)

    def test_part_limit_is_inclusive_and_default_for_every_label_and_role(self):
        contents = {'asset.bin': b'fixture'}
        data = archive(contents.items())
        with mock.patch.object(package, 'MAX_PART', len(data)):
            for label, role in [('client input', 'payload'), ('payload-a', 'payload'),
                                ('payload-b', 'payload'), ('bootstrap-A', 'bootstrap-a'),
                                ('bootstrap-B', 'bootstrap-b'), ('dependency.jar', 'payload')]:
                with self.subTest(label=label):
                    self.assertEqual(package.read_zip(data, label, artifact=role), contents)
                    with self.assertRaisesRegex(ValueError, 'compressed input exceeds'):
                        package.read_zip(data + b' ', label, artifact=role)

    def test_only_explicit_combined_input_accepts_larger_real_zip(self):
        contents = {'one.bin': b'a' * 100, 'two.bin': b'b' * 100}
        data = archive(contents.items())
        with mock.patch.object(package, 'MAX_PART', len(data) - 1):
            with self.assertRaisesRegex(ValueError, 'compressed input exceeds'):
                package.read_zip(data, 'client input')
            self.assertEqual(package.read_zip(data, 'client input', combined_input=True), contents)

    def test_combined_limit_is_inclusive_and_rejects_one_byte_over(self):
        data = archive([('asset.bin', b'fixture')])
        with mock.patch.object(package, 'MAX_PART', len(data) - 1), \
                mock.patch.object(package, 'MAX_TOTAL', len(data)):
            self.assertEqual(package.read_zip(data, 'combined', combined_input=True), {'asset.bin': b'fixture'})
            with self.assertRaisesRegex(ValueError, 'compressed input exceeds'):
                package.read_zip(data + b' ', 'combined', combined_input=True)

    def test_combined_input_does_not_expand_the_uncompressed_budget(self):
        data = archive([('one.bin', b'a' * 512), ('two.bin', b'b' * 512),
                        ('three.bin', b'c' * 512)], compression=zipfile.ZIP_DEFLATED)
        self.assertLess(len(data), 1024)
        with mock.patch.object(package, 'MAX_TOTAL', 1024):
            for combined in (False, True):
                with self.subTest(combined=combined), self.assertRaisesRegex(ValueError, 'expanded input exceeds'):
                    package.read_zip(data, 'fixture', combined_input=combined)

    def test_combined_input_preserves_entry_limit(self):
        data = archive([('asset.bin', bytes(17))])
        with mock.patch.object(package.native_auth_resources, 'MAX_ENTRY', 16):
            with self.assertRaisesRegex(ValueError, 'entry exceeds approved/default integrity limit'):
                package.read_zip(data, 'fixture', combined_input=True)

    def test_combined_input_preserves_exact_native_size_digest_and_role(self):
        resource = 'META-INF/native-fixture.bin'
        dll = fixture_dll()
        # This synthetic Approval exercises resource byte checks only; it is not
        # a protection approval and does not load any executable code.
        approval = package.native_auth_resources.Approval({'resources': [{
            'artifact': 'payload', 'resource': resource, 'size': len(dll),
            'sha256': package.sha(dll)}]}, 'fixture', 'fixture')
        with mock.patch.object(package.native_auth_resources, 'MAX_ENTRY', 64):
            data = archive([(resource, dll)])
            self.assertEqual(package.read_zip(data, 'native', approval, combined_input=True), {resource: dll})
            for altered in (dll[:-1], dll + b' ', dll[:-1] + bytes([dll[-1] ^ 1])):
                with self.subTest(size=len(altered)), self.assertRaises(ValueError):
                    package.read_zip(archive([(resource, altered)]), 'native', approval, combined_input=True)
            for owner in ('bootstrap-a', 'bootstrap-b'):
                with self.subTest(owner=owner), self.assertRaises(ValueError):
                    package.read_zip(data, 'native', approval, owner, combined_input=True)
            with self.assertRaises(ValueError):
                package.read_zip(data, 'native', combined_input=True)

    def test_combined_input_preserves_path_duplicate_and_crc_checks(self):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            duplicate = archive([('same.bin', b'a'), ('same.bin', b'b')])
        for entries in (duplicate, archive([('../escape.bin', b'a')])):
            with self.assertRaises(ValueError):
                package.read_zip(entries, 'fixture', combined_input=True)
        data = bytearray(archive([('crc.bin', b'unique integrity fixture')]))
        data[data.index(b'unique integrity fixture')] ^= 1
        with self.assertRaises(zipfile.BadZipFile):
            package.read_zip(bytes(data), 'fixture', combined_input=True)

    def test_combined_input_preserves_entry_count_limit(self):
        fake = mock.MagicMock()
        fake.__enter__.return_value = fake
        fake.infolist.return_value = [None] * 65535
        with mock.patch.object(package.zipfile, 'ZipFile', return_value=fake):
            with self.assertRaisesRegex(ValueError, 'too many ZIP entries'):
                package.read_zip(b'bounded fixture', 'fixture', combined_input=True)

    def test_prepare_reads_only_the_combined_cap_plus_one_before_rejecting(self):
        class BoundedInput(io.BytesIO):
            def __init__(self):
                super().__init__(b'x' * 100)
                self.requests = []

            def read(self, size=-1):
                self.requests.append(size)
                if size < 0 or size > 43:
                    raise AssertionError('Source disk read was not bounded')
                return super().read(size)

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'source.jar'
            path.write_bytes(b'exists')
            stream = BoundedInput()
            with mock.patch.object(package.native_auth_resources, 'from_args', return_value=None), \
                    mock.patch.object(package, 'MAX_TOTAL', 42), \
                    mock.patch.object(Path, 'open', return_value=stream), \
                    mock.patch.object(Path, 'read_bytes', side_effect=AssertionError('Unbounded disk read')):
                with self.assertRaisesRegex(ValueError, 'compressed input exceeds'):
                    package.prepare(argparse.Namespace(input=path, release_id='fixture'))
            self.assertEqual(stream.requests, [43])
            self.assertTrue(stream.closed)

    def test_prepare_opts_in_only_for_its_source_and_keeps_exact_digest(self):
        contents = {'asset.bin': b'fixture'}
        data = archive(contents.items())
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'source.jar'
            path.write_bytes(data)
            with mock.patch.object(package.native_auth_resources, 'from_args', return_value=None), \
                    mock.patch.object(package, 'MAX_PART', len(data) - 1), \
                    mock.patch.object(package, 'read_zip', wraps=package.read_zip) as reader:
                with self.assertRaisesRegex(ValueError, 'Fabric descriptor'):
                    package.prepare(argparse.Namespace(input=path, release_id='fixture'))
                reader.assert_called_once_with(data, 'client input', None, combined_input=True)
                decoded = reader(data, 'fixture', combined_input=True)
                self.assertEqual(package.runtime_digest(decoded), package.runtime_digest(contents))


if __name__ == '__main__':
    unittest.main()
