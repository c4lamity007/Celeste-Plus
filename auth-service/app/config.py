from __future__ import annotations

import base64
import os
from pathlib import Path
from dataclasses import dataclass, field

from .secret_files import read_secret


def _required(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required setting: {name}")
    return value


def _decode_32(name: str) -> bytes:
    try:
        value = base64.urlsafe_b64decode((read_secret(name) if name in {
            "CELESTE_SIGNING_PRIVATE_KEY", "CELESTE_KEY_PEPPER", "CELESTE_SESSION_SECRET", "CELESTE_MODULE_KEY_ENCRYPTION_KEY"
        } else _required(name)) + "===")
    except ValueError as exc:
        raise RuntimeError(f"Invalid base64 setting: {name}") from exc
    if len(value) != 32:
        raise RuntimeError(f"{name} must decode to exactly 32 bytes")
    return value


@dataclass(frozen=True)
class Settings:
    database_path: str
    signing_private_key: bytes = field(repr=False)
    key_pepper: bytes = field(repr=False)
    session_secret: bytes = field(repr=False)
    admin_password_hash: str = field(repr=False)
    allowed_hosts: tuple[str, ...]
    release_id: str
    release_sha256: str
    client_version: str
    chunk_a_id: str
    chunk_b_id: str
    chunk_a_sha256: str
    chunk_b_sha256: str
    artifact_dir: str
    installer_channel_id: str = ""
    installer_channel_version: str = ""
    installer_compatible_sha256: frozenset[str] = frozenset()
    installer_allow_runtime_digest: bool = False
    cloud_config_dir: str = "/data/cloud-configs"
    bootstrap_id: str = "bootstrap"
    bootstrap_sha256: str = "0" * 64
    bootstrap_filename: str = "celeste-bootstrap.jar"
    loader_id: str = "stage-two"
    loader_sha256: str = "0" * 64
    loader_filename: str = "stage-two.bin"
    loader_three_id: str = "stage-three"
    loader_three_sha256: str = "0" * 64
    loader_three_filename: str = "stage-three.bin"
    loader_four_id: str = "stage-four"
    loader_four_sha256: str = "0" * 64
    loader_four_filename: str = "stage-four.bin"
    grant_ttl_seconds: int = 600
    challenge_ttl_seconds: int = 90
    plus_enabled: bool = False
    beta_release_dir: str = ""
    public_origin: str = ""
    native_release_public_key: bytes | None = None
    runtime_kill_switch: bool = False
    plus_auth_protocol: int = 2
    tls_leaf_sha256: tuple[str, ...] = ()
    public_native_dir: str = ""
    plus_download_sha256: str = ""
    public_native_max_bytes: int = 128 * 1024 * 1024
    module_delivery_required: bool = False
    module_release_dir: str = ""
    plus_qa_beta_release_dir: str = ""
    plus_qa_module_release_dir: str = ""
    plus_qa_installations: frozenset[str] = frozenset()
    module_key_encryption_key: bytes | None = field(default=None, repr=False)

    def accepts_installer_identity(self, release_id: object, digest: object) -> bool:
        if not isinstance(release_id, str) or not isinstance(digest, str):
            return False
        return release_id == (self.installer_channel_id or self.release_id) and (
            digest in self.installer_compatible_sha256
            or (self.installer_allow_runtime_digest and digest == self.release_sha256)
        )

    @classmethod
    def from_env(cls) -> "Settings":
        hosts = tuple(
            host.strip().lower()
            for host in _required("CELESTE_ALLOWED_HOSTS").split(",")
            if host.strip()
        )
        release_id = _required("CELESTE_RELEASE_ID")
        client_version = _required("CELESTE_CLIENT_VERSION")
        installer_channel_id = os.getenv("CELESTE_INSTALLER_CHANNEL_ID", release_id).strip() or release_id
        installer_channel_version = os.getenv("CELESTE_INSTALLER_CHANNEL_VERSION", client_version).strip() or client_version
        plus_enabled = os.getenv("CELESTE_PLUS_ENABLED", "false").strip().lower()
        if plus_enabled not in {"true", "false", "1", "0"}:
            raise RuntimeError("CELESTE_PLUS_ENABLED must be true or false")
        runtime_kill_switch = os.getenv("CELESTE_RUNTIME_KILL_SWITCH", "false").strip().lower()
        if runtime_kill_switch not in {"true", "false", "1", "0"}:
            raise RuntimeError("CELESTE_RUNTIME_KILL_SWITCH must be true or false")
        if len(installer_channel_id) > 80 or len(installer_channel_version) > 40:
            raise RuntimeError("Celeste installer channel identity is invalid")
        release_sha256 = _required("CELESTE_RELEASE_SHA256").lower()
        if len(release_sha256) != 64 or any(c not in "0123456789abcdef" for c in release_sha256):
            raise RuntimeError("CELESTE_RELEASE_SHA256 must be a lowercase SHA-256 hex digest")
        installer_allow_runtime = os.getenv("CELESTE_INSTALLER_ALLOW_RUNTIME_DIGEST", "false").strip().lower()
        if installer_allow_runtime not in {"true", "false", "1", "0"}:
            raise RuntimeError("CELESTE_INSTALLER_ALLOW_RUNTIME_DIGEST must be true or false")
        installer_compatible = set()
        for digest in os.getenv("CELESTE_INSTALLER_COMPAT_SHA256", "").split(","):
            digest = digest.strip().lower()
            if not digest:
                continue
            if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
                raise RuntimeError("CELESTE_INSTALLER_COMPAT_SHA256 contains an invalid SHA-256 digest")
            installer_compatible.add(digest)
        if not installer_compatible and installer_allow_runtime not in {"true", "1"}:
            raise RuntimeError("CELESTE_INSTALLER_COMPAT_SHA256 must explicitly allow a native launcher digest")
        chunk_a_sha256 = _required("CELESTE_CHUNK_A_SHA256").lower()
        chunk_b_sha256 = _required("CELESTE_CHUNK_B_SHA256").lower()
        for name, digest in (("CELESTE_CHUNK_A_SHA256", chunk_a_sha256), ("CELESTE_CHUNK_B_SHA256", chunk_b_sha256)):
            if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
                raise RuntimeError(f"{name} must be a lowercase SHA-256 hex digest")
        loader_sha256 = _required("CELESTE_LOADER_SHA256").lower()
        bootstrap_sha256 = _required("CELESTE_BOOTSTRAP_SHA256").lower()
        loader_three_sha256 = _required("CELESTE_LOADER_THREE_SHA256").lower()
        loader_four_sha256 = _required("CELESTE_LOADER_FOUR_SHA256").lower()
        for name, digest in (
            ("CELESTE_BOOTSTRAP_SHA256", bootstrap_sha256),
            ("CELESTE_LOADER_SHA256", loader_sha256),
            ("CELESTE_LOADER_THREE_SHA256", loader_three_sha256),
            ("CELESTE_LOADER_FOUR_SHA256", loader_four_sha256),
        ):
            if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
                raise RuntimeError(f"{name} must be a lowercase SHA-256 hex digest")
        module_required = os.getenv("CELESTE_PLUS_REQUIRE_MODULE_DELIVERY", "false").strip().lower()
        if module_required not in {"true", "false", "1", "0"}:
            raise RuntimeError("CELESTE_PLUS_REQUIRE_MODULE_DELIVERY must be true or false")
        protocol = os.getenv("CELESTE_PLUS_AUTH_PROTOCOL", "2").strip()
        if protocol not in {"1", "2"}:
            raise RuntimeError("CELESTE_PLUS_AUTH_PROTOCOL must be 1 or 2")
        pins = tuple(value.strip() for value in os.getenv("CELESTE_TLS_LEAF_SHA256", "").split(",") if value.strip())
        if (len(pins) > 4 or len(set(pins)) != len(pins)
                or any(len(pin) != 64 or any(c not in "0123456789abcdef" for c in pin) for pin in pins)):
            raise RuntimeError("CELESTE_TLS_LEAF_SHA256 must contain one to four unique lowercase SHA-256 hashes")
        public_native_dir = os.getenv("CELESTE_PUBLIC_NATIVE_DIR", "").strip()
        plus_download_sha256 = os.getenv("CELESTE_PLUS_DOWNLOAD_SHA256", "").strip()
        if (bool(public_native_dir) != bool(plus_download_sha256)
                or (public_native_dir and not Path(public_native_dir).is_absolute())
                or (plus_download_sha256 and (len(plus_download_sha256) != 64
                    or any(c not in "0123456789abcdef" for c in plus_download_sha256)))):
            raise RuntimeError("Public launcher requires an absolute CELESTE_PUBLIC_NATIVE_DIR and lowercase CELESTE_PLUS_DOWNLOAD_SHA256")
        try:
            public_native_max_bytes = int(os.getenv("CELESTE_PUBLIC_NATIVE_MAX_BYTES", str(128 * 1024 * 1024)))
        except ValueError as exc:
            raise RuntimeError("CELESTE_PUBLIC_NATIVE_MAX_BYTES must be an integer") from exc
        if not 0 < public_native_max_bytes <= 256 * 1024 * 1024:
            raise RuntimeError("CELESTE_PUBLIC_NATIVE_MAX_BYTES must be between 1 and 268435456")
        return cls(
            database_path=os.getenv("CELESTE_DATABASE_PATH", "/data/auth.sqlite3"),
            signing_private_key=_decode_32("CELESTE_SIGNING_PRIVATE_KEY"),
            key_pepper=_decode_32("CELESTE_KEY_PEPPER"),
            session_secret=_decode_32("CELESTE_SESSION_SECRET"),
            admin_password_hash=read_secret("CELESTE_ADMIN_PASSWORD_HASH"),
            allowed_hosts=hosts,
            release_id=release_id,
            release_sha256=release_sha256,
            client_version=client_version,
            chunk_a_id=_required("CELESTE_CHUNK_A_ID"),
            chunk_b_id=_required("CELESTE_CHUNK_B_ID"),
            chunk_a_sha256=chunk_a_sha256,
            chunk_b_sha256=chunk_b_sha256,
            artifact_dir=os.getenv("CELESTE_ARTIFACT_DIR", "/srv/auth/releases"),
            installer_channel_id=installer_channel_id,
            installer_channel_version=installer_channel_version,
            installer_compatible_sha256=frozenset(installer_compatible),
            installer_allow_runtime_digest=installer_allow_runtime in {"true", "1"},
            cloud_config_dir=os.getenv("CELESTE_CLOUD_CONFIG_DIR", "/data/cloud-configs"),
            bootstrap_id=_required("CELESTE_BOOTSTRAP_ID"),
            bootstrap_sha256=bootstrap_sha256,
            bootstrap_filename=os.getenv("CELESTE_BOOTSTRAP_FILENAME", "celeste-bootstrap.jar"),
            loader_id=_required("CELESTE_LOADER_ID"),
            loader_sha256=loader_sha256,
            loader_filename=os.getenv("CELESTE_LOADER_FILENAME", "stage-two.bin"),
            loader_three_id=_required("CELESTE_LOADER_THREE_ID"),
            loader_three_sha256=loader_three_sha256,
            loader_three_filename=os.getenv("CELESTE_LOADER_THREE_FILENAME", "stage-three.bin"),
            loader_four_id=_required("CELESTE_LOADER_FOUR_ID"),
            loader_four_sha256=loader_four_sha256,
            loader_four_filename=os.getenv("CELESTE_LOADER_FOUR_FILENAME", "stage-four.bin"),
            plus_enabled=plus_enabled in {"true", "1"},
            runtime_kill_switch=runtime_kill_switch in {"true", "1"},
            plus_auth_protocol=int(protocol),
            tls_leaf_sha256=pins,
            public_native_dir=public_native_dir,
            plus_download_sha256=plus_download_sha256,
            public_native_max_bytes=public_native_max_bytes,
            module_delivery_required=module_required in {"true", "1"},
            module_release_dir=os.getenv("CELESTE_MODULE_RELEASE_DIR", "").strip(),
            plus_qa_beta_release_dir=os.getenv("CELESTE_PLUS_QA_BETA_RELEASE_DIR", "").strip(),
            plus_qa_module_release_dir=os.getenv("CELESTE_PLUS_QA_MODULE_RELEASE_DIR", "").strip(),
            plus_qa_installations=frozenset(value.strip() for value in os.getenv("CELESTE_PLUS_QA_INSTALLATIONS", "").split(",") if value.strip()),
            module_key_encryption_key=(_decode_32("CELESTE_MODULE_KEY_ENCRYPTION_KEY")
                if os.getenv("CELESTE_MODULE_KEY_ENCRYPTION_KEY", "").strip()
                    or os.getenv("CELESTE_MODULE_KEY_ENCRYPTION_KEY_FILE", "").strip() else None),
            beta_release_dir=os.getenv("CELESTE_BETA_RELEASE_DIR", "").strip(),
            public_origin=os.getenv("CELESTE_PUBLIC_ORIGIN", "").strip(),
            native_release_public_key=(
                _decode_32("CELESTE_NATIVE_RELEASE_PUBLIC_KEY")
                if os.getenv("CELESTE_NATIVE_RELEASE_PUBLIC_KEY", "").strip() else None
            ),
        )
