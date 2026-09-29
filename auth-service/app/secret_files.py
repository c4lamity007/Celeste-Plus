"""Optional restricted secret mounts for the Linux auth service.

Existing environment configuration remains supported. File and environment
sources are mutually exclusive, avoiding silent fallback after misconfiguration.
"""
from __future__ import annotations

import os
import stat
from pathlib import Path


def _check_permissions(info) -> None:
    if os.name != "posix":
        raise RuntimeError("Secret-file configuration requires POSIX permission enforcement")
    if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1
            or stat.S_IMODE(info.st_mode) not in (0o400, 0o600)
            or info.st_uid not in (0, os.geteuid())):
        raise RuntimeError("Secret file must be a private regular file owned by the service user or root")


def read_secret(name: str) -> str:
    direct = os.getenv(name, "").strip()
    filename = os.getenv(name + "_FILE", "").strip()
    if direct and filename:
        raise RuntimeError(f"Configure exactly one of {name} and {name}_FILE")
    if not filename:
        if not direct:
            raise RuntimeError(f"Missing required setting: {name}")
        return direct
    path = Path(filename)
    if not path.is_absolute():
        raise RuntimeError(f"{name}_FILE must be an absolute path")
    if os.name != "posix":
        raise RuntimeError("Secret-file configuration requires POSIX permission enforcement")
    descriptor = None
    try:
        before = path.lstat()
        _check_permissions(before)
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
        with os.fdopen(descriptor, "rb") as stream:
            descriptor = None
            opened = os.fstat(stream.fileno())
            _check_permissions(opened)
            if (before.st_dev, before.st_ino) != (opened.st_dev, opened.st_ino):
                raise RuntimeError("Secret file changed while opening")
            encoded = stream.read(8193)
            after = os.fstat(stream.fileno())
            if (opened.st_size, opened.st_mtime_ns, opened.st_ctime_ns) != (
                    after.st_size, after.st_mtime_ns, after.st_ctime_ns):
                raise RuntimeError("Secret file changed while reading")
        if len(encoded) > 8192:
            raise RuntimeError("Secret file exceeds size limit")
        value = encoded.decode("ascii").strip()
        if not value:
            raise RuntimeError("Secret file is empty")
        return value
    except (OSError, UnicodeError) as exc:
        # Do not include the path or content in startup errors.
        raise RuntimeError(f"Unable to read restricted secret setting: {name}") from None
    finally:
        if descriptor is not None:
            os.close(descriptor)
