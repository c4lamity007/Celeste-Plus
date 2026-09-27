"""Keep a separate native-publication Ed25519 seed encrypted with Windows DPAPI.

Commands: init --identity ABSOLUTE_FRESH_PATH; show-public --identity PATH.
The parent directory must already exist outside Git. Neither command prints a
private seed, changes a trust store, configures Fly, or publishes a release.

Authorized Python callers may use load_private_seed(path), encode its result as
unpadded base64url, and put it in CELESTE_NATIVE_RELEASE_PRIVATE_KEY in a fresh
child-only environment for the existing register_native_release.py. Never put
that value in argv, logs, a plaintext file, or os.environ. This module deliberately
has no export-private command and no arbitrary-command or publication wrapper.
DPAPI protects storage, not against code already running as this Windows user.
Python cannot guarantee erasure of every temporary in-memory key copy.
"""
from __future__ import annotations

import argparse
import base64
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

SCHEMA = "celeste-native-publication-identity-1"
PRODUCT = "celeste_plus"
MAX_RECORD = 16_384
ENTROPY = b"Celeste+ separate native publication identity v1"
FIELDS = {"schema", "product", "algorithm", "protection", "public_key", "public_key_sha256", "encrypted_seed"}


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _decode(value: str) -> bytes:
    _require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_-]+", value), "Invalid identity encoding")
    try:
        decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except ValueError:
        raise ValueError("Invalid identity encoding") from None
    _require(_b64(decoded) == value, "Noncanonical identity encoding")
    return decoded


def _checked_path(value: Path) -> Path:
    path = Path(value)
    _require(path.is_absolute(), "Provide an absolute identity path outside Git")
    path = Path(os.path.abspath(path))
    _require(path.name and ":" not in path.name and path.parent.is_dir(), "Identity parent directory must already exist")
    for item in (path, *path.parents):
        if item.exists() or item.is_symlink():
            info = item.lstat()
            _require(not stat.S_ISLNK(info.st_mode) and not getattr(info, "st_file_attributes", 0) & 0x400,
                     "Identity path must not traverse links or reparse points")
        if item != path:
            _require(not (item / ".git").exists(), "Publication identities must be stored outside Git")
    return path


def _dpapi(value: bytes, *, protect: bool) -> bytes:
    _require(sys.platform == "win32", "Windows DPAPI is required; there is no plaintext fallback")
    _require(isinstance(value, bytes) and 0 < len(value) <= MAX_RECORD, "Invalid DPAPI input size")

    class Blob(ctypes.Structure):
        _fields_ = [("size", ctypes.c_uint32), ("data", ctypes.POINTER(ctypes.c_ubyte))]

    crypt32 = ctypes.WinDLL("crypt32.dll", use_last_error=True, winmode=0x800)
    kernel32 = ctypes.WinDLL("kernel32.dll", use_last_error=True, winmode=0x800)
    function = crypt32.CryptProtectData if protect else crypt32.CryptUnprotectData
    function.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.POINTER(Blob),
                         ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint32, ctypes.POINTER(Blob)]
    function.restype = ctypes.c_int
    kernel32.LocalFree.argtypes = [ctypes.c_void_p]
    kernel32.LocalFree.restype = ctypes.c_void_p
    input_buffer = (ctypes.c_ubyte * len(value)).from_buffer_copy(value)
    entropy_buffer = (ctypes.c_ubyte * len(ENTROPY)).from_buffer_copy(ENTROPY)
    input_blob = Blob(len(value), input_buffer)
    entropy_blob = Blob(len(ENTROPY), entropy_buffer)
    output_blob = Blob()
    try:
        # UI_FORBIDDEN only: never CRYPTPROTECT_LOCAL_MACHINE. A description and
        # prompt are unnecessary, and no platform-selected UI may reveal input.
        succeeded = function(ctypes.byref(input_blob), None, ctypes.byref(entropy_blob),
                             None, None, 0x1, ctypes.byref(output_blob))
        _require(succeeded and bool(output_blob.data) and 0 < output_blob.size <= MAX_RECORD,
                 "Windows DPAPI could not protect or unlock this identity")
        return ctypes.string_at(output_blob.data, output_blob.size)
    finally:
        ctypes.memset(input_buffer, 0, len(value))
        if output_blob.data:
            ctypes.memset(output_blob.data, 0, output_blob.size)
            kernel32.LocalFree(output_blob.data)


def _new_seed() -> bytes:
    return os.urandom(32)


def _public(seed: bytes) -> bytes:
    _require(isinstance(seed, bytes) and len(seed) == 32, "Identity seed must contain exactly 32 bytes")
    return Ed25519PrivateKey.from_private_bytes(seed).public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def _metadata(record: dict) -> dict:
    return {"schema": "celeste-native-publication-public-1", "product": PRODUCT, "algorithm": "Ed25519",
            "public_key": record["public_key"], "public_key_sha256": record["public_key_sha256"],
            "server_setting": "CELESTE_NATIVE_RELEASE_PUBLIC_KEY"}


def _load(path: Path) -> tuple[dict, bytes]:
    path = _checked_path(path)
    with path.open("rb") as source:
        before = os.fstat(source.fileno())
        _require(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= MAX_RECORD, "Missing or oversized identity record")
        raw = source.read(MAX_RECORD + 1)
        after = os.fstat(source.fileno())
    _require(len(raw) == before.st_size == after.st_size and before.st_mtime_ns == after.st_mtime_ns,
             "Identity changed while reading")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            _require(key not in result, "Duplicate identity field")
            result[key] = value
        return result
    try:
        record = json.loads(raw, object_pairs_hook=unique)
    except (UnicodeError, json.JSONDecodeError):
        raise ValueError("Malformed identity record") from None
    _require(isinstance(record, dict) and set(record) == FIELDS, "Unsupported identity fields")
    _require((record["schema"], record["product"], record["algorithm"], record["protection"]) ==
             (SCHEMA, PRODUCT, "Ed25519", "windows-dpapi-current-user"), "Unsupported identity context")
    public = _decode(record["public_key"])
    _require(len(public) == 32 and hashlib.sha256(public).hexdigest() == record["public_key_sha256"],
             "Identity public metadata is inconsistent")
    seed = _dpapi(_decode(record["encrypted_seed"]), protect=False)
    _require(_public(seed) == public, "Unlocked identity does not match its public metadata")
    return record, seed


def initialize(path: Path) -> dict:
    """Create only encrypted private storage; return public metadata only."""
    path = _checked_path(path)
    _require(not path.exists(), "Identity already exists; overwriting or implicit rotation is forbidden")
    seed = _new_seed()
    public = _public(seed)
    encrypted = _dpapi(seed, protect=True)
    _require(encrypted != seed and 0 < len(encrypted) <= MAX_RECORD, "DPAPI did not produce valid encrypted identity storage")
    _require(_dpapi(encrypted, protect=False) == seed, "DPAPI identity round-trip failed")
    record = {"schema": SCHEMA, "product": PRODUCT, "algorithm": "Ed25519",
              "protection": "windows-dpapi-current-user", "public_key": _b64(public),
              "public_key_sha256": hashlib.sha256(public).hexdigest(), "encrypted_seed": _b64(encrypted)}
    raw = (json.dumps(record, indent=2) + "\n").encode("utf-8")
    _require(len(raw) <= MAX_RECORD, "Identity record exceeds size limit")
    # Exclusive creation prevents overwriting even if another initializer raced
    # our earlier check. Only encrypted data is ever written to this file.
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "wb") as target:
        target.write(raw)
        target.flush()
        os.fsync(target.fileno())
    return show_public(path)


def show_public(path: Path) -> dict:
    """Read-only output; decrypt first to verify the stored public identity."""
    record, _ = _load(path)
    return _metadata(record)


def load_private_seed(path: Path) -> bytes:
    """In-memory API for the authorized registration caller; never print it."""
    _, seed = _load(path)
    return seed


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("init", "show-public"))
    parser.add_argument("--identity", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = initialize(args.identity) if args.command == "init" else show_public(args.identity)
        print(json.dumps(result, sort_keys=True))
    except (OSError, ValueError, TypeError):
        parser.exit(1, "Native publication identity operation failed; check the path, record and Windows user. No private key was printed.\n")


if __name__ == "__main__":
    main()
