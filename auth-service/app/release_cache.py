"""Bounded metadata reuse for immutable, operator-staged runtime releases.

Never caches authorization or artifact response bytes. Every reuse checks file
identity, link count, size, mode and modification/change times, and the small
manifest's actual bytes. Full verification expires after five minutes as well.
"""
from __future__ import annotations

import copy
import hashlib
import os
import threading
import time
from collections import OrderedDict
from pathlib import Path

from fastapi import HTTPException

MAX_AGE_SECONDS = 300
_LOCK = threading.Lock()
_ENTRIES: OrderedDict[tuple, tuple] = OrderedDict()


def _change_time(stream, info) -> int:
    if os.name != "nt":
        return info.st_ctime_ns
    # Windows st_ctime is creation time. Query the file's ChangeTime so a
    # same-size overwrite followed by restored mtime cannot reuse stale hashes.
    import ctypes
    import msvcrt
    from ctypes import wintypes

    class FileBasicInfo(ctypes.Structure):
        _fields_ = [(name, ctypes.c_longlong) for name in
                    ("creation", "access", "write", "change")] + [("attributes", wintypes.DWORD)]

    query = ctypes.WinDLL("kernel32", use_last_error=True).GetFileInformationByHandleEx
    query.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
    query.restype = wintypes.BOOL
    basic = FileBasicInfo()
    if not query(msvcrt.get_osfhandle(stream.fileno()), 0, ctypes.byref(basic), ctypes.sizeof(basic)):
        raise OSError("cannot inspect release change time")
    return basic.change


def release_stamp(settings, filenames, safe_file) -> tuple:
    configured = Path(settings.beta_release_dir)
    if not configured.is_absolute():
        raise ValueError("release path must be absolute")
    root = configured.resolve(strict=True)
    production = Path(settings.artifact_dir).resolve()
    if root == production or root in production.parents or production in root.parents:
        raise ValueError("release directories overlap")
    result = [str(root)]
    for filename in ("beta-release.json", *sorted(filenames)):
        path = safe_file(root, filename)
        with path.open("rb") as stream:
            info = os.fstat(stream.fileno())
            changed = _change_time(stream, info)
            stamp = (info.st_dev, info.st_ino, info.st_mode, info.st_nlink,
                     info.st_size, info.st_mtime_ns, changed)
            if info.st_nlink != 1:
                raise ValueError("release file has multiple links")
            # Tiny manifest bytes are cheap to verify independently of timestamps.
            digest = None
            if filename == "beta-release.json":
                encoded = stream.read(16_385)
                if len(encoded) > 16_384:
                    raise ValueError("oversized release manifest")
                digest = hashlib.sha256(encoded).digest()
            after = os.fstat(stream.fileno())
            if (after.st_dev, after.st_ino, after.st_mode, after.st_nlink,
                    after.st_size, after.st_mtime_ns, _change_time(stream, after)) != stamp:
                raise ValueError("release changed while inspecting")
        # Detect path replacement while its former descriptor remained open.
        after = safe_file(root, filename).stat()
        if (after.st_dev, after.st_ino, after.st_mode, after.st_nlink,
                after.st_size, after.st_mtime_ns) != stamp[:6]:
            raise ValueError("release path changed while inspecting")
        result.append((filename, stamp, digest))
    return tuple(result)


def _key(settings) -> tuple:
    # No credentials or Settings objects retained by this process-wide cache.
    return (settings.beta_release_dir, settings.artifact_dir, settings.release_id, settings.release_sha256)


def verified_catalog(settings, filenames, safe_file, read_release) -> dict:
    if not _LOCK.acquire(timeout=2):
        raise HTTPException(503, "release verification busy", headers={"Retry-After": "1"})
    key = _key(settings)
    try:
        before = release_stamp(settings, filenames, safe_file)
        cached = _ENTRIES.get(key)
        if cached is not None and cached[0] == before and time.monotonic() - cached[1] < MAX_AGE_SECONDS:
            _ENTRIES.move_to_end(key)
            return copy.deepcopy(cached[2])
        _ENTRIES.pop(key, None)
        catalog, _ = read_release(settings)
        after = release_stamp(settings, filenames, safe_file)
        if before != after:
            raise ValueError("release changed during verification")
        _ENTRIES[key] = (after, time.monotonic(), copy.deepcopy(catalog))
        while len(_ENTRIES) > 8:
            _ENTRIES.popitem(last=False)
        return catalog
    except (OSError, ValueError, TypeError, RuntimeError) as exc:
        _ENTRIES.pop(key, None)
        raise HTTPException(503, "beta release unavailable") from exc
    finally:
        _LOCK.release()


def require_current(settings, filenames, safe_file, catalog, *, wait: bool = False) -> None:
    """Check current stamps without hashing artifacts.

    Transaction callers must retain the nonblocking default. Download preparation
    outside a database transaction may wait briefly for another verifier instead
    of rejecting a valid parallel download solely because the cache is occupied.
    """
    acquired = _LOCK.acquire(timeout=2) if wait else _LOCK.acquire(blocking=False)
    if not acquired:
        raise HTTPException(503, "release verification busy", headers={"Retry-After": "1"})
    try:
        cached = _ENTRIES.get(_key(settings))
        if (cached is None or time.monotonic() - cached[1] >= MAX_AGE_SECONDS
                or cached[2] != catalog or cached[0] != release_stamp(settings, filenames, safe_file)):
            raise ValueError("runtime release changed")
    except (OSError, ValueError, TypeError, RuntimeError) as exc:
        raise HTTPException(503, "beta release unavailable") from exc
    finally:
        _LOCK.release()
