"""Opt-in Celeste+ delivery; production artifacts and release identity stay separate.

The release directory is operator-staged, separate from production, and contains
beta-release.json plus precisely named catalog entries. The on-disk catalog is
never served directly. Each request validates all four artifacts; downloads
return the same bytes whose digest was checked (not a later FileResponse read).

Catalog v2 exact top-level keys: schema="celeste-beta-release-2", release_id,
client_version, runtime_sha256, artifacts. Runtime SHA-256 is the canonical
client-integrity digest, not either transport artifact's digest. The signed
public catalog contains those identities plus four id/role/sha256/size/url
entries; catalog_sha256 hashes its canonical JSON (without catalog_sha256).

Beta auth/start and session/check require beta_catalog_sha256 from the verified
manifest. Auth challenge scope is beta:<catalog_sha256>. Runtime client/session
grants retain their existing audience but add beta_catalog_sha256, release and
client_version; they contain no production loader/blob authorization. Beta
session signatures append newline + release_id + newline + client_version +
newline + beta_catalog_sha256 to the unchanged production session transcript.

Manifest requests require a fresh client-generated 24-byte base64url nonce in
X-Celeste-Request-Nonce. The signed request_nonce claim binds the response to
that request; clients must compare it before accepting the manifest.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import re
import threading
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from cryptography.exceptions import InvalidSignature
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse

from .config import Settings
from .database import Database
from .products import PLUS, require_entitlement, require_claim_product
from .security import GrantSigner, canonical_json
from .dpop import access_token
from .token_revocation import require_unrevoked
from .artifact_envelope import encrypt_chunks, recipient, OVERHEAD_BYTES
from .artifact_transfer import ArtifactResponse, plain_chunks
from .auth_errors import failure
from .auth_lifecycle import require_artifact_hardware


RELEASE_SCHEMA = "celeste-beta-release-2"
MANIFEST_AUDIENCE = "celeste-beta-manifest"
MANIFEST_TTL_SECONDS = 120
MAX_ARTIFACT_BYTES = 64 * 1024 * 1024
MAX_RELEASE_BYTES = 128 * 1024 * 1024
MAX_CATALOG_BYTES = 16_384
TRANSFER_WAIT_SECONDS = 2
ARTIFACT_ROLES = {
    "bootstrap-a": "bootstrap",
    "bootstrap-b": "bootstrap",
    "payload-a": "payload",
    "payload-b": "payload",
}
ARTIFACT_FILENAMES = {
    "bootstrap-a": "bootstrap-a.jar",
    "bootstrap-b": "bootstrap-b.bin",
    "payload-a": "payload-a.bin",
    "payload-b": "payload-b.bin",
}
_IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}\Z")
_FILENAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,119}\Z")
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_REQUEST_NONCE = re.compile(r"[A-Za-z0-9_-]{32}\Z")


def _unavailable() -> HTTPException:
    return HTTPException(503, "beta release unavailable")


def _strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate catalog key")
        result[key] = value
    return result


def _safe_file(root: Path, filename: str) -> Path:
    if not isinstance(filename, str) or not _FILENAME.fullmatch(filename):
        raise ValueError("invalid artifact filename")
    path = root / filename
    if (path.is_symlink() or path.resolve(strict=True).parent != root
            or not path.is_file() or path.stat().st_nlink != 1):
        raise ValueError("artifact outside beta release")
    return path


def _read_release(settings: Settings, selected: str | None = None) -> tuple[dict, bytes | None]:
    """Validate the entire catalog and return a checked snapshot of selected bytes."""
    try:
        configured_root = Path(settings.beta_release_dir)
        if not configured_root.is_absolute():
            raise ValueError("beta release directory must be absolute")
        root = configured_root.resolve(strict=True)
        production = Path(settings.artifact_dir).resolve()
        if root == production or root in production.parents or production in root.parents:
            raise ValueError("beta and production directories must be separate")
        manifest_path = _safe_file(root, "beta-release.json")
        with manifest_path.open("rb") as stream:
            encoded = stream.read(MAX_CATALOG_BYTES + 1)
        if len(encoded) > MAX_CATALOG_BYTES:
            raise ValueError("oversized beta catalog")
        catalog = json.loads(encoded, object_pairs_hook=_strict_object)
        if not isinstance(catalog, dict) or set(catalog) != {
            "schema", "release_id", "client_version", "runtime_sha256", "artifacts"
        }:
            raise ValueError("invalid beta catalog")
        release_id = catalog["release_id"]
        if (catalog["schema"] != RELEASE_SCHEMA or not isinstance(release_id, str)
                or not _IDENTIFIER.fullmatch(release_id) or release_id == settings.release_id):
            raise ValueError("invalid beta release identity")
        client_version, runtime_sha256 = catalog["client_version"], catalog["runtime_sha256"]
        if (not isinstance(client_version, str) or not 1 <= len(client_version) <= 40
                or client_version != client_version.strip() or not client_version.isascii()
                or any(ord(character) < 33 or ord(character) > 126 for character in client_version)
                or not isinstance(runtime_sha256, str) or not _SHA256.fullmatch(runtime_sha256)
                or runtime_sha256 == settings.release_sha256):
            raise ValueError("invalid beta runtime identity")
        entries = catalog["artifacts"]
        if not isinstance(entries, list) or len(entries) != 4:
            raise ValueError("beta release requires four artifacts")
        public_entries = []
        seen_ids: set[str] = set()
        seen_files: set[str] = set()
        selected_bytes = None
        release_size = 0
        for entry in entries:
            if not isinstance(entry, dict) or set(entry) != {"id", "role", "filename", "sha256", "size"}:
                raise ValueError("invalid beta artifact")
            artifact_id, role, filename, digest, size = (
                entry["id"], entry["role"], entry["filename"], entry["sha256"], entry["size"]
            )
            if (not isinstance(artifact_id, str) or artifact_id not in ARTIFACT_ROLES
                    or role != ARTIFACT_ROLES[artifact_id] or artifact_id in seen_ids
                    or not isinstance(filename, str) or filename.casefold() in seen_files
                    or filename != ARTIFACT_FILENAMES[artifact_id]
                    or not isinstance(digest, str) or not _SHA256.fullmatch(digest)
                    or type(size) is not int or not 0 < size <= MAX_ARTIFACT_BYTES):
                raise ValueError("invalid beta artifact metadata")
            release_size += size
            if release_size > MAX_RELEASE_BYTES:
                raise ValueError("beta release exceeds size limit")
            path = _safe_file(root, filename)
            seen_ids.add(artifact_id)
            seen_files.add(filename.casefold())
            actual_digest = hashlib.sha256()
            actual_size = 0
            with path.open("rb") as stream:
                if artifact_id == selected:
                    # One bounded immutable allocation; Response reuses these
                    # bytes, avoiding bytearray-to-bytes copies on a small VM.
                    selected_bytes = stream.read(size + 1)
                    actual_size = len(selected_bytes)
                    actual_digest.update(selected_bytes)
                else:
                    while chunk := stream.read(1024 * 1024):
                        actual_size += len(chunk)
                        if actual_size > size:
                            raise ValueError("beta artifact size mismatch")
                        actual_digest.update(chunk)
            if actual_size != size or not hmac.compare_digest(actual_digest.hexdigest(), digest):
                raise ValueError("beta artifact digest mismatch")
            public_entries.append({
                "id": artifact_id,
                "role": role,
                "sha256": digest,
                "size": size,
                "url": f"/v1/beta/artifacts/{release_id}/{artifact_id}",
            })
        public_entries.sort(key=lambda item: item["id"])
        return {"schema": RELEASE_SCHEMA, "release_id": release_id, "client_version": client_version,
                "runtime_sha256": runtime_sha256, "artifacts": public_entries}, selected_bytes
    except (OSError, ValueError, TypeError, RuntimeError) as exc:
        raise _unavailable() from exc


def beta_enabled(settings: Settings) -> bool:
    return settings.plus_enabled and bool(settings.beta_release_dir)


def beta_runtime_catalog(settings: Settings, license_uid: int) -> dict:
    """Resolve the enabled Celeste+ release for a currently entitled license."""
    if not beta_enabled(settings):
        raise HTTPException(409, "unsupported release")
    with Database(settings.database_path).connect() as connection:
        require_entitlement(connection, license_uid, PLUS)
    catalog, _ = _read_release(settings)
    return {**catalog, "catalog_sha256": hashlib.sha256(canonical_json(catalog)).hexdigest()}


def create_beta_router(
    settings: Settings,
    database: Database,
    signer: GrantSigner,
    require_active_artifact_device: Callable[[dict, Request, str, str], str],
    apply_limit: Callable[[Request, str, int, int], None],
) -> APIRouter:
    router = APIRouter(prefix="/v1/beta")
    # Per process (the deployment runs one worker). Admission precedes the
    # selected immutable allocation and remains held through ASGI backpressure.
    transfer_slots = threading.BoundedSemaphore(2)

    def enabled() -> None:
        if not beta_enabled(settings):
            raise HTTPException(404, "not found")

    def verified(token: str) -> dict[str, Any]:
        if not token or len(token) > 16_384:
            raise HTTPException(401, "invalid grant")
        try:
            claims = signer.verify(token)
        except (InvalidSignature, ValueError, TypeError, AttributeError, OverflowError):
            claims = None
        if not isinstance(claims, dict):
            raise HTTPException(401, "invalid grant")
        return claims

    def plus_installer(request: Request, *, touch: bool = True) -> tuple[str, dict]:
        enabled()
        token = access_token(request)
        claims = verified(token)
        # Match the existing installer/bootstrap gate without changing production.
        digest = claims.get("client_sha256")
        if (claims.get("aud") != "celeste-installer"
                or not settings.accepts_installer_identity(claims.get("release"), digest)
                or claims.get("payload_release") != settings.release_id
                or claims.get("payload_sha256") != settings.release_sha256
                or claims.get("bootstrap") != settings.bootstrap_id
                or claims.get("bootstrap_sha256") != settings.bootstrap_sha256
                or type(claims.get("exp")) is not int
                or not isinstance(claims.get("jti"), str)
                or not 16 <= len(claims["jti"]) <= 128):
            raise HTTPException(401, "invalid grant")
        require_claim_product(claims, PLUS)
        if settings.plus_auth_protocol == 2 and claims.get("auth_protocol") != 2:
            raise failure("reactivation_required", "Update this client and verify its installation again")
        subject = claims.get("sub")
        if not isinstance(subject, str) or not subject.isascii() or not subject.isdecimal() or not 0 < int(subject) < 2**63 or str(int(subject)) != subject:
            raise HTTPException(401, "invalid grant")
        license_uid = int(subject)
        device_id = claims.get("device")
        if not isinstance(device_id, str) or not 32 <= len(device_id) <= 48:
            raise HTTPException(401, "invalid grant")
        # The production helper checks live status/expiry; beta additionally binds
        # the grant subject to the immutable device license, never the display label.
        with database.connect() as connection:
            row = connection.execute(
                """SELECT d.license_uid,d.hwid_sha256,d.hardware_core_hash,
                          d.status AS device_status,l.status AS license_status,l.expires_at
                   FROM devices d JOIN licenses l ON l.uid=d.license_uid
                   WHERE d.device_id=? AND d.license_uid=?""",
                (device_id, license_uid),
            ).fetchone()
            require_entitlement(connection, license_uid, PLUS)
            require_unrevoked(connection, claims)
        now = int(time.time())
        if (row is None or row["device_status"] != "active" or row["license_status"] != "active"
                or (row["expires_at"] is not None and int(row["expires_at"]) <= now)
                or claims["exp"] <= now):
            raise HTTPException(401, "invalid grant")
        if touch:
            require_active_artifact_device(claims, request, request.url.path, PLUS)
        elif request.url.path.startswith("/v1/beta/artifacts/"):
            require_artifact_hardware(row, request)
        return token, claims

    @router.get("/manifest")
    def beta_manifest(request: Request):
        enabled()
        apply_limit(request, "beta-delivery", 30, 60)
        installer_token, installer = plus_installer(request)
        nonces = request.headers.getlist("x-celeste-request-nonce")
        if len(nonces) != 1 or not _REQUEST_NONCE.fullmatch(nonces[0]):
            raise HTTPException(400, "invalid manifest request nonce")
        catalog, _ = _read_release(settings)
        plus_installer(request, touch=False)
        now = int(time.time())
        expires_at = min(installer["exp"], now + MANIFEST_TTL_SECONDS)
        if expires_at <= now:
            raise HTTPException(401, "invalid grant")
        claims = {
            "aud": MANIFEST_AUDIENCE,
            "product": PLUS,
            "sub": installer["sub"],
            "device": installer["device"],
            "cnf": installer["cnf"],
            "iat": now,
            "nbf": now,
            "exp": expires_at,
            "installer_jti": installer["jti"],
            "installer_sha256": hashlib.sha256(installer_token.encode("ascii")).hexdigest(),
            "request_nonce": nonces[0],
            "catalog_sha256": hashlib.sha256(canonical_json(catalog)).hexdigest(),
            **catalog,
        }
        return JSONResponse({"manifest": signer.sign(claims)}, headers={"Cache-Control": "no-store"})

    @router.get("/artifacts/{release_id}/{artifact_id}")
    def beta_artifact(release_id: str, artifact_id: str, request: Request):
        enabled()
        apply_limit(request, "beta-delivery", 30, 60)
        installer_token, installer = plus_installer(request)
        manifest = verified(request.headers.get("x-celeste-beta-manifest", ""))
        now = int(time.time())
        if (manifest.get("aud") != MANIFEST_AUDIENCE
                or manifest.get("product") != PLUS
                or manifest.get("sub") != installer["sub"]
                or manifest.get("device") != installer["device"]
                or manifest.get("cnf") != installer["cnf"]
                or manifest.get("installer_jti") != installer["jti"]
                or manifest.get("installer_sha256") != hashlib.sha256(installer_token.encode("ascii")).hexdigest()
                or type(manifest.get("iat")) is not int or type(manifest.get("exp")) is not int
                or manifest["iat"] > now or not 0 < manifest["exp"] - manifest["iat"] <= MANIFEST_TTL_SECONDS
                or manifest["exp"] > installer["exp"]):
            raise HTTPException(401, "invalid grant")
        if not _IDENTIFIER.fullmatch(release_id) or artifact_id not in ARTIFACT_ROLES:
            raise HTTPException(404, "beta artifact not found")
        if not transfer_slots.acquire(timeout=TRANSFER_WAIT_SECONDS):
            raise HTTPException(503, "beta artifact transfers are busy", headers={"Retry-After": "1"})
        try:
            # Initial entitlement/HWID/DPoP validation happened before admission.
            # Waiting must not let a now-revoked or expired grant allocate bytes.
            plus_installer(request, touch=False)
            if min(installer["exp"], manifest["exp"]) <= int(time.time()):
                raise HTTPException(401, "invalid grant")
            catalog, content = _read_release(settings, artifact_id)
            if (manifest.get("catalog_sha256") != hashlib.sha256(canonical_json(catalog)).hexdigest()
                    or any(manifest.get(key) != value for key, value in catalog.items())):
                raise HTTPException(401, "invalid grant")
            if release_id != catalog["release_id"] or content is None:
                raise HTTPException(404, "beta artifact not found")
            # Do not allow an expired grant to finish a slow integrity-validation pass.
            if min(installer["exp"], manifest["exp"]) <= int(time.time()):
                raise HTTPException(401, "invalid grant")
            plus_installer(request, touch=False)
            length = len(content)
            if settings.plus_auth_protocol == 2:
                client_key, request_nonce = recipient(request)
                chunks = encrypt_chunks(content, installer_token, request.url.path, client_key, request_nonce)
                length += OVERHEAD_BYTES
                plus_installer(request, touch=False)
            else:
                chunks = plain_chunks(content)
            return ArtifactResponse(chunks, length, transfer_slots)
        except BaseException:
            transfer_slots.release()
            raise


    return router
