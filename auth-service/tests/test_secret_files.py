"""Secret-source policy tests use disposable strings, never live credentials."""
import os
import stat
from types import SimpleNamespace

import pytest

import app.secret_files as secrets
from app.config import _decode_32
from app.security import b64e

NAME = "CELESTE_SIGNING_PRIVATE_KEY"


def test_environment_source_remains_compatible(monkeypatch):
    monkeypatch.setenv(NAME, b64e(bytes(range(32))))
    monkeypatch.delenv(NAME + "_FILE", raising=False)
    assert _decode_32(NAME) == bytes(range(32))


def test_two_sources_rejected_without_reading_file(monkeypatch):
    monkeypatch.setenv(NAME, "synthetic-env-secret")
    monkeypatch.setenv(NAME + "_FILE", "/synthetic/no-read")
    with pytest.raises(RuntimeError, match="exactly one") as caught:
        secrets.read_secret(NAME)
    assert "synthetic-env-secret" not in str(caught.value)


def test_relative_secret_mount_rejected(monkeypatch):
    monkeypatch.delenv(NAME, raising=False)
    monkeypatch.setenv(NAME + "_FILE", "relative/private")
    with pytest.raises(RuntimeError, match="absolute"):
        secrets.read_secret(NAME)


@pytest.mark.parametrize("mode,uid,links,allowed", [
    (stat.S_IFREG | 0o600, 42, 1, True), (stat.S_IFREG | 0o400, 0, 1, True),
    (stat.S_IFREG | 0o640, 42, 1, False), (stat.S_IFREG | 0o604, 42, 1, False),
    (stat.S_IFREG | 0o600, 99, 1, False), (stat.S_IFREG | 0o600, 42, 2, False),
    (stat.S_IFLNK | 0o600, 42, 1, False), (stat.S_IFDIR | 0o700, 42, 1, False),
])
def test_posix_permission_policy(monkeypatch, mode, uid, links, allowed):
    # Policy-level fixture; real POSIX filesystem behavior is tested separately.
    monkeypatch.setattr(secrets, "os", SimpleNamespace(name="posix", geteuid=lambda: 42))
    info = SimpleNamespace(st_mode=mode, st_uid=uid, st_nlink=links)
    if allowed:
        secrets._check_permissions(info)
    else:
        with pytest.raises(RuntimeError, match="private regular file"):
            secrets._check_permissions(info)


@pytest.mark.skipif(os.name != "posix", reason="real POSIX secret-mount permissions require Linux/macOS")
def test_real_private_mount_and_unsafe_files(tmp_path, monkeypatch):
    path = tmp_path / "private"
    path.write_text(b64e(bytes(range(32))) + "\n")
    path.chmod(0o600)
    monkeypatch.delenv(NAME, raising=False)
    monkeypatch.setenv(NAME + "_FILE", str(path))
    assert _decode_32(NAME) == bytes(range(32))
    path.chmod(0o640)
    with pytest.raises(RuntimeError):
        secrets.read_secret(NAME)
    path.chmod(0o600)
    link = tmp_path / "link"
    link.symlink_to(path)
    monkeypatch.setenv(NAME + "_FILE", str(link))
    with pytest.raises(RuntimeError):
        secrets.read_secret(NAME)


@pytest.mark.skipif(os.name == "posix", reason="non-POSIX fail-closed behavior only")
def test_mount_rejected_without_posix_permissions(tmp_path, monkeypatch):
    path = tmp_path / "private"
    path.write_text("synthetic-secret")
    monkeypatch.delenv(NAME, raising=False)
    monkeypatch.setenv(NAME + "_FILE", str(path))
    with pytest.raises(RuntimeError, match="POSIX"):
        secrets.read_secret(NAME)
