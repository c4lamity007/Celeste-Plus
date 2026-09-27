"""Packaging checks for the paired Windows playback and exit-cleanup libraries."""
import struct
import unittest

from package_beta import validate_media_support, read_zip, zip_bytes

PREFIX = "mediaplayerinfo/natives/win/"
PLAYBACK = PREFIX + "MediaPlayerInfo.dll"
CLEANUP = PREFIX + "CelesteMediaCleanup.dll"


def pe_dll(machine=0x8664):
    data = bytearray(154)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 60, 128)
    data[128:132] = b"PE\x00\x00"
    struct.pack_into("<H", data, 132, machine)
    struct.pack_into("<H", data, 150, 0x2002)
    struct.pack_into("<H", data, 152, 0x20B)
    return bytes(data)


class MediaSupportChecks(unittest.TestCase):
    def test_playback_without_cleanup_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "requires both"):
            validate_media_support({PLAYBACK: pe_dll()})

    def test_cleanup_without_playback_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "requires both"):
            validate_media_support({CLEANUP: pe_dll()})

    def test_paired_resources_are_preserved_exactly_in_support_archive(self):
        media = {PLAYBACK: pe_dll() + b"playback fixture", CLEANUP: pe_dll() + b"cleanup fixture"}
        validate_media_support(media)
        self.assertEqual(read_zip(zip_bytes(media), "fixture support"), media)

    def test_wrong_architecture_and_invalid_resources_are_rejected(self):
        for data in (b"", b"not a DLL", pe_dll(machine=0x14C)):
            for path in (PLAYBACK, CLEANUP):
                with self.subTest(path=path, bytes=len(data)), self.assertRaises(ValueError):
                    media = {PLAYBACK: pe_dll(), CLEANUP: pe_dll()}
                    media[path] = data
                    validate_media_support(media)

    def test_non_windows_support_is_unaffected(self):
        validate_media_support({"dev/redstones/mediaplayerinfo/IMediaSession.class": b"fixture"})


if __name__ == "__main__":
    unittest.main()
