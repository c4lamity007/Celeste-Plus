from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated, Literal

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ed25519
from cryptography.exceptions import InvalidSignature
from fastapi import FastAPI, Form, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, Field, field_validator
from starlette.middleware.trustedhost import TrustedHostMiddleware

from .beta_delivery import beta_enabled, beta_runtime_catalog, create_beta_router
from .api_responses import ApiResponseMiddleware
from .native_manifest import create_native_manifest_router
from .token_revocation import require_unrevoked
from .config import Settings
from .database import Database
from .dpop import DPoPVerifier, access_token, confirmation
from .products import CELESTE, PLUS, PRODUCT_LABELS, issue_license, require_entitlement, require_claim_product
from .request_limits import RequestLimitMiddleware
from .security import (
    GrantSigner,
    b64d,
    b64e,
    canonical_json,
    csrf_token,
    hash_license,
    issue_token_seed,
    keyed_digest,
    parse_session_cookie,
    session_cookie,
    valid_csrf,
    verify_admin_password,
    verify_ed25519,
)


ROOT = Path(__file__).resolve().parent
COOKIE_NAME = "__Host-cst-admin"


def _hex_digest(value: str) -> str:
    normalized = value.strip().lower()
    if len(normalized) != 64 or any(c not in "0123456789abcdef" for c in normalized):
        raise ValueError("expected SHA-256 hex")
    return normalized


class HardwareProfile(BaseModel):
    system: str
    board: str
    processor: str
    monitors: list[str] = Field(min_length=1, max_length=8)

    @field_validator("system", "board", "processor")
    @classmethod
    def validate_component(cls, value: str) -> str:
        return _hex_digest(value)

    @field_validator("monitors")
    @classmethod
    def validate_monitors(cls, values: list[str]) -> list[str]:
        return sorted({_hex_digest(value) for value in values})


class ReleaseRequest(BaseModel):
    release_id: str = Field(min_length=1, max_length=80)
    client_version: str = Field(min_length=1, max_length=40)
    client_sha256: str
    game_uuid: str = Field(min_length=32, max_length=36)
    game_name: str = Field(min_length=1, max_length=32)

    @field_validator("client_sha256")
    @classmethod
    def validate_sha(cls, value: str) -> str:
        return _hex_digest(value)

    @field_validator("game_uuid")
    @classmethod
    def validate_game_uuid(cls, value: str) -> str:
        try:
            return str(uuid.UUID(value))
        except ValueError as exception:
            raise ValueError("invalid game UUID") from exception

    @field_validator("game_name")
    @classmethod
    def validate_game_name(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized or any(ord(character) < 32 for character in normalized):
            raise ValueError("invalid game name")
        return normalized


class EnrollStartRequest(ReleaseRequest):
    product: Literal["celeste", "celeste_plus"] = "celeste"
    license_key: str = Field(min_length=20, max_length=96)
    install_signing_key: str = Field(min_length=40, max_length=64)
    install_encryption_key: str = Field(min_length=40, max_length=64)
    hardware: HardwareProfile


class AuthStartRequest(ReleaseRequest):
    license_key: str | None = Field(default=None, min_length=20, max_length=96)
    product: Literal["celeste", "celeste_plus"] = "celeste"
    device_id: str = Field(min_length=32, max_length=48)
    hardware: HardwareProfile
    beta_catalog_sha256: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")


class CompleteRequest(BaseModel):
    challenge_id: str = Field(min_length=16, max_length=64)
    install_signature: str = Field(min_length=80, max_length=100)
    token_signature: str | None = Field(default=None, min_length=80, max_length=100)


class SessionCheckRequest(ReleaseRequest):
    product: Literal["celeste", "celeste_plus"] | None = None
    device_id: str = Field(min_length=32, max_length=48)
    timestamp: int
    nonce: str = Field(min_length=16, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
    observed_sha256: str
    event: Literal["heartbeat", "integrity_mismatch"]
    install_signature: str = Field(min_length=80, max_length=100)
    token_signature: str = Field(min_length=80, max_length=100)
    beta_catalog_sha256: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")

    @field_validator("observed_sha256")
    @classmethod
    def validate_observed_sha(cls, value: str) -> str:
        return _hex_digest(value)


class CloudConfigPublishRequest(BaseModel):
    name: str = Field(min_length=1, max_length=48)
    image: str = Field(min_length=16, max_length=1_400_000)
    modules: list[str] = Field(default_factory=list, max_length=128)

    @field_validator("name")
    @classmethod
    def validate_name(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized or any(ord(character) < 32 for character in normalized):
            raise ValueError("invalid config name")
        return normalized

    @field_validator("modules")
    @classmethod
    def validate_modules(cls, values: list[str]) -> list[str]:
        result: list[str] = []
        for value in values:
            normalized = value.strip()
            if not normalized or len(normalized) > 64 or any(ord(character) < 32 for character in normalized):
                raise ValueError("invalid module name")
            if normalized not in result:
                result.append(normalized)
        return result


def _hardware(profile: HardwareProfile) -> tuple[bytes, bytes]:
    core = canonical_json(
        {
            "board": profile.board,
            "processor": profile.processor,
            "system": profile.system,
        }
    )
    monitor = canonical_json({"monitors": profile.monitors})
    return hashlib.sha256(core).digest(), hashlib.sha256(monitor).digest()


def _message(row) -> bytes:
    return canonical_json(
        {
            "challenge": str(row["challenge_id"]),
            "client_sha256": str(row["client_sha256"]),
            "device": str(row["device_id"] or ""),
            "hardware": b64e(bytes(row["hardware_core_hash"])),
            "game_name": str(row["game_name"]),
            "game_uuid": str(row["game_uuid"]),
            "kind": str(row["kind"]),
            "scope": str(row["scope"]),
            "product": str(row["product"]),
            "nonce": b64e(bytes(row["nonce"])),
            "protocol": "cst-auth-1",
            "release": str(row["release_id"]),
        }
    )


def _session_message(body: SessionCheckRequest) -> bytes:
    message = (
        "celeste-session-v1\n"
        f"{body.device_id}\n{body.timestamp}\n{body.nonce}\n"
        f"{body.client_sha256}\n{body.observed_sha256}\n{body.event}\n"
        f"{body.game_uuid}\n{body.game_name}"
    )
    if body.beta_catalog_sha256 is not None:
        message += f"\n{body.release_id}\n{body.client_version}\n{body.beta_catalog_sha256}"
    return message.encode("utf-8")


def _display_time(value) -> str:
    if value is None or int(value) <= 0:
        return "Never"
    return datetime.fromtimestamp(int(value), timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")


def _config_slug(value: str) -> str:
    slug = re.sub(r"[^a-z0-9_-]+", "-", value.strip().lower()).strip("-")
    return slug[:48] or "config"


def _decode_config_image(encoded: str) -> bytes:
    try:
        image = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
    except (ValueError, TypeError) as exception:
        raise HTTPException(422, "invalid config image") from exception
    if len(image) < 33 or len(image) > 1_048_576 or not image.startswith(b"\x89PNG\r\n\x1a\n"):
        raise HTTPException(422, "invalid config image")
    if image[12:16] != b"IHDR":
        raise HTTPException(422, "invalid config image")
    width = int.from_bytes(image[16:20], "big")
    height = int.from_bytes(image[20:24], "big")
    if width < 1 or height < 1 or width > 2048 or height > 2048:
        raise HTTPException(422, "invalid config image dimensions")
    return image


def _validate_release_artifacts(settings: Settings) -> None:
    """Refuse to start when signed metadata and files are out of sync."""
    root = Path(settings.artifact_dir)
    artifacts = (
        ("bootstrap", settings.bootstrap_filename, settings.bootstrap_sha256),
        ("chunk-a", "chunk-a.bin", settings.chunk_a_sha256),
        ("chunk-b", "chunk-b.bin", settings.chunk_b_sha256),
        ("stage-two", settings.loader_filename, settings.loader_sha256),
        ("stage-three", settings.loader_three_filename, settings.loader_three_sha256),
        ("stage-four", settings.loader_four_filename, settings.loader_four_sha256),
    )
    for label, filename, expected in artifacts:
        path = root / filename
        if not path.is_file():
            raise RuntimeError(f"Missing release artifact for {label}: {filename}")
        with path.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if not hmac.compare_digest(actual, expected):
            raise RuntimeError(
                f"Release artifact digest mismatch for {label}: "
                f"expected {expected}, got {actual}"
            )


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    _validate_release_artifacts(settings)
    database = Database(settings.database_path)
    database.initialize()
    cloud_config_root = Path(settings.cloud_config_dir).resolve()
    cloud_config_root.mkdir(parents=True, exist_ok=True)
    signer = GrantSigner(settings.signing_private_key, grant_ttl_seconds=settings.grant_ttl_seconds)
    dpop = DPoPVerifier(settings)
    templates = Jinja2Templates(directory=str(ROOT / "templates"))
    templates.env.filters["timestamp"] = _display_time

    app = FastAPI(
        title="License Gateway",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=list(settings.allowed_hosts))
    app.add_middleware(RequestLimitMiddleware, max_bytes=16_384)
    app.add_middleware(ApiResponseMiddleware)
    app.mount("/assets", StaticFiles(directory=str(ROOT / "static")), name="assets")
    app.state.settings = settings
    app.state.database = database
    app.state.signer = signer

    @app.middleware("http")
    async def security_headers(request: Request, call_next):
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
        response.headers["Content-Security-Policy"] = "default-src 'none'; style-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"
        response.headers["Cross-Origin-Resource-Policy"] = "same-origin"
        if request.url.path.startswith(("/v1/", "/admin")):
            response.headers["Cache-Control"] = "no-store"
        if os.getenv("FLY_APP_NAME"):
            response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
        return response

    def client_ip(request: Request) -> str:
        if os.getenv("FLY_APP_NAME"):
            candidate = request.headers.get("fly-client-ip", "").strip()
            if candidate:
                return candidate[:64]
        return (request.client.host if request.client else "unknown")[:64]

    def apply_limit(request: Request, domain: str, limit: int, window: int) -> None:
        bucket = keyed_digest(settings.key_pepper, f"rate:{domain}", client_ip(request))
        with database.connect() as connection:
            retry = database.rate_limit(connection, bucket, limit, window)
        if retry:
            raise HTTPException(429, "temporarily rate limited", headers={"Retry-After": str(retry)})

    def check_limit(request: Request, domain: str, window: int) -> None:
        bucket = keyed_digest(settings.key_pepper, f"rate:{domain}", client_ip(request))
        with database.connect() as connection:
            retry = database.rate_limit_retry_after(connection, bucket, window)
        if retry:
            raise HTTPException(429, "temporarily rate limited", headers={"Retry-After": str(retry)})

    def clear_limit(request: Request, domain: str) -> None:
        bucket = keyed_digest(settings.key_pepper, f"rate:{domain}", client_ip(request))
        with database.connect() as connection:
            database.clear_rate_limit(connection, bucket)

    def validate_release(request: ReleaseRequest) -> None:
        if not (
            hmac.compare_digest(request.release_id, settings.release_id)
            and hmac.compare_digest(request.client_version, settings.client_version)
            and hmac.compare_digest(request.client_sha256, settings.release_sha256)
        ):
            raise HTTPException(409, "unsupported release")

    def require_runtime_enabled() -> None:
        if settings.runtime_kill_switch:
            raise HTTPException(403, "runtime temporarily disabled")

    def runtime_release(body: AuthStartRequest | SessionCheckRequest) -> dict | None:
        """Each runtime identity requires a live device and its matching product entitlement."""
        require_runtime_enabled()
        if body.release_id == settings.release_id or not beta_enabled(settings):
            validate_release(body)
            if body.beta_catalog_sha256 is not None:
                raise HTTPException(409, "unsupported release")
            if body.product not in (None, CELESTE):
                raise HTTPException(403, "runtime product does not match release")
            return None
        with database.connect() as connection:
            row = connection.execute(
                """SELECT d.license_uid,d.status,l.status AS license_status,l.expires_at
                   FROM devices d JOIN licenses l ON l.uid=d.license_uid WHERE d.device_id=?""",
                (body.device_id,),
            ).fetchone()
        if (row is None or row["status"] != "active" or row["license_status"] != "active"
                or (row["expires_at"] is not None and int(row["expires_at"]) <= int(time.time()))):
            raise HTTPException(401, "authentication denied")
        catalog = beta_runtime_catalog(settings, int(row["license_uid"]))
        if (body.release_id != catalog["release_id"] or body.client_version != catalog["client_version"]
                or body.client_sha256 != catalog["runtime_sha256"]
                or body.beta_catalog_sha256 != catalog["catalog_sha256"]):
            raise HTTPException(409, "unsupported release")
        if body.product not in (None, PLUS):
            raise HTTPException(403, "runtime product does not match release")
        return catalog

    def validate_launcher_release(request: ReleaseRequest) -> None:
        """Validate the stable native-launcher channel, not the moving payload digest."""
        channel_version = settings.installer_channel_version or settings.client_version
        if not (
            settings.accepts_installer_identity(request.release_id, request.client_sha256)
            and hmac.compare_digest(request.client_version, channel_version)
        ):
            raise HTTPException(409, "unsupported release")

    def audit(connection, event: str, license_uid: int | None = None, subject: str = "", details: str = "") -> None:
        connection.execute(
            "INSERT INTO audit_log(occurred_at,event,license_uid,subject,details) VALUES(?,?,?,?,?)",
            (int(time.time()), event, license_uid, subject[:80], details[:500]),
        )

    def security_event(
        connection,
        event_type: str,
        severity: str,
        license_uid: int | None,
        device_id: str,
        details: str,
        request: Request,
    ) -> None:
        connection.execute(
            """
            INSERT INTO security_events(
                occurred_at,license_uid,device_id,severity,event_type,details,client_ip
            ) VALUES(?,?,?,?,?,?,?)
            """,
            (
                int(time.time()),
                license_uid,
                device_id[:48],
                severity[:16],
                event_type[:64],
                details[:500],
                client_ip(request),
            ),
        )

    def touch_device(device_id: str, endpoint: str, request: Request, integrity: str = "") -> None:
        now = int(time.time())
        with database.connect() as connection:
            connection.execute(
                """
                UPDATE devices
                SET last_call_at=?,last_call_endpoint=?,last_ip=?,
                    last_integrity_sha256=CASE WHEN ?='' THEN last_integrity_sha256 ELSE ? END,
                    last_integrity_at=CASE WHEN ?='' THEN last_integrity_at ELSE ? END
                WHERE device_id=?
                """,
                (now, endpoint[:80], client_ip(request), integrity, integrity, integrity, now, device_id),
            )

    def active_cloud_identity(request: Request):
        token = access_token(request)
        claims = signer.verify(token)
        if claims is None or claims.get("aud") not in {"celeste-client", "celeste-session"}:
            raise HTTPException(401, "invalid grant")
        beta_claim = claims.get("beta_catalog_sha256")
        product = CELESTE if beta_claim is None else PLUS
        require_claim_product(claims, product)
        if beta_claim is None and (
            claims.get("client_sha256") != settings.release_sha256
            or (claims.get("release") is not None and claims.get("release") != settings.release_id)
        ):
            raise HTTPException(401, "invalid grant")
        device_id = str(claims.get("device", ""))
        try:
            license_uid = int(claims.get("sub", 0))
        except (TypeError, ValueError):
            raise HTTPException(401, "invalid grant") from None
        now = int(time.time())
        with database.connect() as connection:
            row = connection.execute(
                """
                SELECT d.device_id,d.license_uid,d.install_signing_key,d.status,d.last_game_name,
                       l.status AS license_status,l.expires_at
                FROM devices d JOIN licenses l ON l.uid=d.license_uid
                WHERE d.device_id=? AND d.license_uid=?
                """,
                (device_id, license_uid),
            ).fetchone()
        if (
            row is None
            or row["status"] != "active"
            or row["license_status"] != "active"
            or (row["expires_at"] is not None and int(row["expires_at"]) <= now)
        ):
            raise HTTPException(401, "invalid grant")
        if claims.get("sub") != str(license_uid):
            raise HTTPException(401, "invalid grant")
        with database.connect() as connection:
            require_entitlement(connection, license_uid, product)
        if beta_claim is not None:
            catalog = beta_runtime_catalog(settings, license_uid)
            if (beta_claim != catalog["catalog_sha256"] or claims.get("release") != catalog["release_id"]
                    or claims.get("client_version") != catalog["client_version"]
                    or claims.get("client_sha256") != catalog["runtime_sha256"]):
                raise HTTPException(401, "invalid grant")
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """SELECT d.device_id,d.license_uid,d.install_signing_key,d.status,d.last_game_name,
                          l.status AS license_status,l.expires_at
                   FROM devices d JOIN licenses l ON l.uid=d.license_uid
                   WHERE d.device_id=? AND d.license_uid=?""", (device_id, license_uid)
            ).fetchone()
            now = int(time.time())
            if (row is None or row["status"] != "active" or row["license_status"] != "active"
                    or (row["expires_at"] is not None and int(row["expires_at"]) <= now)
                    or type(claims.get("exp")) is not int or claims["exp"] <= now):
                raise HTTPException(401, "invalid grant")
            require_entitlement(connection, license_uid, product)
            require_unrevoked(connection, claims)
            dpop.require(connection, request, bytes(row["install_signing_key"]), token=token, claims=claims)
        return claims, row

    def revoke_for_tamper(
        connection,
        license_uid: int,
        device_id: str,
        observed_sha256: str,
        request: Request,
    ) -> None:
        now = int(time.time())
        reason = f"integrity mismatch ({observed_sha256[:12]})"
        connection.execute(
            "UPDATE licenses SET status='revoked',revoked_at=?,revoked_reason=? WHERE uid=?",
            (now, reason, license_uid),
        )
        connection.execute(
            "UPDATE devices SET status='revoked',tamper_count=tamper_count+1 WHERE license_uid=?",
            (license_uid,),
        )
        security_event(
            connection,
            "client_integrity_mismatch",
            "critical",
            license_uid,
            device_id,
            json.dumps({"observed": observed_sha256, "expected": settings.release_sha256}, separators=(",", ":")),
            request,
        )
        audit(connection, "license_auto_revoked", license_uid, device_id, reason)

    @app.get("/healthz")
    def healthz():
        return {"ok": True}

    @app.get("/v1/info")
    def public_info():
        return {
            "protocol": "cst-auth-1",
            "release": settings.release_id,
            "signing_key": signer.public_key_b64(),
        }

    @app.post("/v1/enroll/start")
    def enroll_start(body: EnrollStartRequest, request: Request):
        apply_limit(request, "enroll", 5, 300)
        validate_launcher_release(body)
        try:
            license_hash = hash_license(settings.key_pepper, body.license_key)
            install_signing_key = b64d(body.install_signing_key, 32)
            install_encryption_key = b64d(body.install_encryption_key, 32)
        except ValueError:
            raise HTTPException(401, "enrollment denied") from None
        hardware_core, monitor_hash = _hardware(body.hardware)
        now = int(time.time())
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("DELETE FROM challenges WHERE expires_at <= ?", (now,))
            license_row = connection.execute(
                "SELECT * FROM licenses WHERE key_hash=?",
                (license_hash,),
            ).fetchone()
            if (
                license_row is None
                or license_row["status"] != "active"
                or (license_row["expires_at"] is not None and int(license_row["expires_at"]) <= now)
            ):
                raise HTTPException(401, "enrollment denied")
            require_entitlement(connection, int(license_row["uid"]), body.product)
            existing = connection.execute(
                "SELECT device_id FROM devices WHERE license_uid=? AND install_signing_key=? AND status='active'",
                (license_row["uid"], install_signing_key),
            ).fetchone()
            if existing is not None:
                raise HTTPException(409, "installation already enrolled")
            device_count = connection.execute(
                "SELECT COUNT(*) AS count FROM devices WHERE license_uid=? AND status='active'",
                (license_row["uid"],),
            ).fetchone()["count"]
            if int(device_count) >= int(license_row["max_devices"]):
                raise HTTPException(409, "device limit reached")
            challenge_id = secrets.token_urlsafe(20)
            connection.execute(
                """
                INSERT INTO challenges(
                    challenge_id,kind,product,license_uid,device_id,nonce,install_signing_key,
                    install_encryption_key,hardware_core_hash,monitor_hash,release_id,
                    client_sha256,game_uuid,game_name,created_at,expires_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    challenge_id,
                    "enroll",
                    body.product,
                    license_row["uid"],
                    None,
                    secrets.token_bytes(32),
                    install_signing_key,
                    install_encryption_key,
                    hardware_core,
                    monitor_hash,
                    body.release_id,
                    body.client_sha256,
                    body.game_uuid,
                    body.game_name,
                    now,
                    now + settings.challenge_ttl_seconds,
                ),
            )
            row = connection.execute("SELECT * FROM challenges WHERE challenge_id=?", (challenge_id,)).fetchone()
            audit(connection, "enroll_challenge", int(license_row["uid"]), challenge_id)
        return {"challenge_id": challenge_id, "message": b64e(_message(row)), "expires_at": row["expires_at"]}

    @app.post("/v1/enroll/complete")
    def enroll_complete(body: CompleteRequest, request: Request):
        apply_limit(request, "enroll-complete", 8, 300)
        now = int(time.time())
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """
                SELECT c.*,l.status AS license_status,l.expires_at AS license_expires,
                       l.duration_seconds,l.activated_at,l.max_devices
                FROM challenges c JOIN licenses l ON l.uid=c.license_uid
                WHERE c.challenge_id=? AND c.kind='enroll'
                """,
                (body.challenge_id,),
            ).fetchone()
            if (
                row is None
                or row["consumed_at"] is not None
                or int(row["expires_at"]) <= now
                or row["license_status"] != "active"
                or (row["license_expires"] is not None and int(row["license_expires"]) <= now)
            ):
                raise HTTPException(401, "challenge rejected")
            require_entitlement(connection, int(row["license_uid"]), str(row["product"]))
            if not settings.accepts_installer_identity(row["release_id"], row["client_sha256"]):
                raise HTTPException(409, "unsupported release")
            # Admission is rechecked in the completion transaction: two pending challenges
            # cannot consume the last slot concurrently or bypass a changed entitlement.
            count = connection.execute("SELECT COUNT(*) FROM devices WHERE license_uid=? AND status='active'",
                                       (row["license_uid"],)).fetchone()[0]
            duplicate = connection.execute("SELECT 1 FROM devices WHERE license_uid=? AND install_signing_key=?",
                                           (row["license_uid"], row["install_signing_key"])).fetchone()
            if duplicate is not None:
                raise HTTPException(409, "installation already enrolled")
            if count >= int(row["max_devices"]):
                raise HTTPException(409, "device limit reached")
            message = _message(row)
            if not verify_ed25519(bytes(row["install_signing_key"]), message, body.install_signature):
                audit(connection, "enroll_bad_signature", int(row["license_uid"]), body.challenge_id)
                raise HTTPException(401, "challenge rejected")
            dpop.require(connection, request, bytes(row["install_signing_key"]))
            token_seed, envelope = issue_token_seed(
                bytes(row["install_encryption_key"]),
                bytes(row["nonce"]),
                message,
            )
            token_public = ed25519.Ed25519PrivateKey.from_private_bytes(token_seed).public_key().public_bytes(
                serialization.Encoding.Raw,
                serialization.PublicFormat.Raw,
            )
            device_id = str(uuid.uuid4())
            if row["activated_at"] is None:
                duration_seconds = int(row["duration_seconds"]) if row["duration_seconds"] is not None else None
                connection.execute(
                    "UPDATE licenses SET activated_at=?,expires_at=? WHERE uid=?",
                    (now, now + duration_seconds if duration_seconds is not None else None, row["license_uid"]),
                )
            connection.execute(
                """
                INSERT INTO devices(
                    device_id,license_uid,install_signing_key,token_signing_key,
                    hardware_core_hash,monitor_hash,status,created_at,last_seen_at
                    ,last_game_uuid,last_game_name,activation_ip,last_ip,last_call_at,
                    last_call_endpoint,last_integrity_sha256,last_integrity_at
                ) VALUES(?,?,?,?,?,?,'active',?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    device_id,
                    row["license_uid"],
                    row["install_signing_key"],
                    token_public,
                    row["hardware_core_hash"],
                    row["monitor_hash"],
                    now,
                    None,
                    row["game_uuid"],
                    row["game_name"],
                    client_ip(request),
                    client_ip(request),
                    now,
                    "/v1/enroll/complete",
                    row["client_sha256"],
                    now,
                ),
            )
            connection.execute("UPDATE challenges SET consumed_at=? WHERE challenge_id=?", (now, body.challenge_id))
            audit(connection, "device_enrolled", int(row["license_uid"]), device_id)
            certificate = signer.sign(
                {
                    "aud": "celeste-installation",
                    "device": device_id,
                    "exp": now + 3650 * 86400,
                    "iat": now,
                    "install_key": b64e(bytes(row["install_signing_key"])),
                    "cnf": confirmation(bytes(row["install_signing_key"])),
                    "token_key": b64e(token_public),
                    "sub": str(row["license_uid"]),
                }
            )
        return {
            "device_id": device_id,
            "uid": int(row["license_uid"]),
            "token_envelope": envelope,
            "device_certificate": certificate,
        }

    @app.post("/v1/auth/start")
    def auth_start(body: AuthStartRequest, request: Request):
        apply_limit(request, "auth", 20, 60)
        runtime = runtime_release(body)
        hardware_core, monitor_hash = _hardware(body.hardware)
        now = int(time.time())
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """
                SELECT d.*, l.status AS license_status, l.expires_at AS license_expires
                FROM devices d JOIN licenses l ON l.uid=d.license_uid
                WHERE d.device_id=?
                """,
                (body.device_id,),
            ).fetchone()
            if (
                row is None
                or row["status"] != "active"
                or row["license_status"] != "active"
                or (row["license_expires"] is not None and int(row["license_expires"]) <= now)
                or not hmac.compare_digest(bytes(row["hardware_core_hash"]), hardware_core)
                or not hmac.compare_digest(bytes(row["monitor_hash"]), monitor_hash)
            ):
                raise HTTPException(401, "authentication denied")
            product = CELESTE if runtime is None else PLUS
            require_entitlement(connection, int(row["license_uid"]), product)
            challenge_id = secrets.token_urlsafe(20)
            connection.execute(
                """
                INSERT INTO challenges(
                    challenge_id,kind,scope,product,license_uid,device_id,nonce,install_signing_key,
                    hardware_core_hash,monitor_hash,release_id,client_sha256,game_uuid,game_name,created_at,expires_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    challenge_id,
                    "auth",
                    "client" if runtime is None else "beta:" + runtime["catalog_sha256"],
                    product,
                    row["license_uid"],
                    row["device_id"],
                    secrets.token_bytes(32),
                    row["install_signing_key"],
                    hardware_core,
                    monitor_hash,
                    body.release_id,
                    body.client_sha256,
                    body.game_uuid,
                    body.game_name,
                    now,
                    now + settings.challenge_ttl_seconds,
                ),
            )
            challenge = connection.execute("SELECT * FROM challenges WHERE challenge_id=?", (challenge_id,)).fetchone()
            audit(connection, "auth_challenge", int(row["license_uid"]), challenge_id)
        return {
            "challenge_id": challenge_id,
            "message": b64e(_message(challenge)),
            "expires_at": challenge["expires_at"],
            "monitor_changed": False,
        }

    @app.post("/v1/auth/complete")
    def auth_complete(body: CompleteRequest, request: Request):
        apply_limit(request, "auth-complete", 30, 60)
        require_runtime_enabled()
        if body.token_signature is None:
            raise HTTPException(401, "authentication denied")
        now = int(time.time())
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """
                SELECT c.*, d.token_signing_key, d.status AS device_status,
                       d.license_uid AS device_license_uid,
                       l.status AS license_status, l.expires_at AS license_expires
                FROM challenges c
                JOIN devices d ON d.device_id=c.device_id
                JOIN licenses l ON l.uid=c.license_uid
                WHERE c.challenge_id=? AND c.kind='auth' AND (c.scope='client' OR c.scope LIKE 'beta:%')
                """,
                (body.challenge_id,),
            ).fetchone()
            if (
                row is None
                or row["consumed_at"] is not None
                or int(row["expires_at"]) <= now
                or row["device_status"] != "active"
                or row["license_status"] != "active"
                or (row["license_expires"] is not None and int(row["license_expires"]) <= now)
            ):
                raise HTTPException(401, "authentication denied")
            runtime = None
            product = CELESTE if row["scope"] == "client" else PLUS
            if row["product"] != product:
                raise HTTPException(401, "authentication denied")
            require_entitlement(connection, int(row["license_uid"]), product)
            if int(row["device_license_uid"]) != int(row["license_uid"]):
                raise HTTPException(401, "authentication denied")
            if row["scope"] != "client":
                if int(row["device_license_uid"]) != int(row["license_uid"]):
                    raise HTTPException(401, "authentication denied")
                runtime = beta_runtime_catalog(settings, int(row["license_uid"]))
                now = int(time.time())
                if int(row["expires_at"]) <= now or (
                    row["license_expires"] is not None and int(row["license_expires"]) <= now
                ):
                    raise HTTPException(401, "authentication denied")
                if (row["scope"] != "beta:" + runtime["catalog_sha256"]
                        or row["release_id"] != runtime["release_id"]
                        or row["client_sha256"] != runtime["runtime_sha256"]):
                    raise HTTPException(409, "unsupported release")
            message = _message(row)
            try:
                install_ok = verify_ed25519(bytes(row["install_signing_key"]), message, body.install_signature)
                token_ok = verify_ed25519(bytes(row["token_signing_key"]), message, body.token_signature)
            except InvalidSignature:
                if runtime is None:
                    raise
                install_ok = token_ok = False
            if not install_ok or not token_ok:
                audit(connection, "auth_bad_proof", int(row["license_uid"]), body.challenge_id)
                raise HTTPException(401, "authentication denied")
            dpop.require(connection, request, bytes(row["install_signing_key"]))
            connection.execute("UPDATE challenges SET consumed_at=? WHERE challenge_id=?", (now, body.challenge_id))
            connection.execute(
                """
                UPDATE devices SET last_seen_at=?,last_game_uuid=?,last_game_name=?,
                    last_call_at=?,last_call_endpoint=?,last_ip=?,last_integrity_sha256=?,last_integrity_at=?
                WHERE device_id=?
                """,
                (
                    now,
                    row["game_uuid"],
                    row["game_name"],
                    now,
                    "/v1/auth/complete",
                    client_ip(request),
                    row["client_sha256"],
                    now,
                    row["device_id"],
                ),
            )
            audit(
                connection,
                "auth_success",
                int(row["license_uid"]),
                str(row["device_id"]),
                json.dumps({"uuid": row["game_uuid"], "username": row["game_name"]}, separators=(",", ":")),
            )
        if runtime is not None:
            grant = signer.sign({
                "aud": "celeste-client", "product": PLUS, "client_sha256": runtime["runtime_sha256"],
                "cnf": confirmation(bytes(row["install_signing_key"])),
                "device": row["device_id"], "exp": now + settings.grant_ttl_seconds, "iat": now,
                "jti": secrets.token_urlsafe(18), "nbf": now - 5, "release": runtime["release_id"],
                "client_version": runtime["client_version"], "beta_catalog_sha256": runtime["catalog_sha256"],
                "sub": str(row["license_uid"]),
            })
            return {"grant": grant, "uid": int(row["license_uid"]), "expires_at": now + settings.grant_ttl_seconds,
                    "runtime": {"release": runtime["release_id"], "sha256": runtime["runtime_sha256"],
                                "client_version": runtime["client_version"], "catalog_sha256": runtime["catalog_sha256"]}}
        grant = signer.sign(
            {
                "aud": "celeste-client",
                "cnf": confirmation(bytes(row["install_signing_key"])),
                "product": CELESTE,
                "chunks": [settings.chunk_a_id, settings.chunk_b_id],
                "client_sha256": settings.release_sha256,
                "device": row["device_id"],
                "exp": now + settings.grant_ttl_seconds,
                "iat": now,
                "jti": secrets.token_urlsafe(18),
                "nbf": now - 5,
                "release": settings.release_id,
                "stage2": settings.loader_id,
                "stage2_sha256": settings.loader_sha256,
                "sub": str(row["license_uid"]),
            }
        )
        return {
            "grant": grant,
            "uid": int(row["license_uid"]),
            "expires_at": now + settings.grant_ttl_seconds,
            "artifact": {
                "release": settings.release_id,
                "sha256": settings.release_sha256,
                "chunks": [
                    {"url": f"/v1/blob/{settings.chunk_a_id}", "sha256": settings.chunk_a_sha256},
                    {"url": f"/v1/blob/{settings.chunk_b_id}", "sha256": settings.chunk_b_sha256},
                ],
            },
            "loader": {
                "url": f"/v1/loader/{settings.loader_id}",
                "sha256": settings.loader_sha256,
            },
        }

    def scoped_auth_start(
        body: AuthStartRequest,
        request: Request,
        scope: str,
        label: str,
        launcher_compatible: bool = False,
    ):
        apply_limit(request, label, 20, 60)
        product = body.product if scope == "installer" else CELESTE
        if launcher_compatible:
            validate_launcher_release(body)
        else:
            validate_release(body)
        if scope != "installer" and body.product != CELESTE:
            raise HTTPException(403, "stage product does not match release")
        hardware_core, monitor_hash = _hardware(body.hardware)
        now = int(time.time())
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """
                SELECT d.*, l.key_hash AS license_key_hash, l.status AS license_status, l.expires_at AS license_expires
                FROM devices d JOIN licenses l ON l.uid=d.license_uid
                WHERE d.device_id=?
                """,
                (body.device_id,),
            ).fetchone()
            if (
                row is None
                or row["status"] != "active"
                or row["license_status"] != "active"
                or (row["license_expires"] is not None and int(row["license_expires"]) <= now)
                or not hmac.compare_digest(bytes(row["hardware_core_hash"]), hardware_core)
                or not hmac.compare_digest(bytes(row["monitor_hash"]), monitor_hash)
            ):
                raise HTTPException(401, f"{label} authentication denied")
            if scope == "installer" and body.license_key is not None:
                try:
                    supplied_hash = hash_license(settings.key_pepper, body.license_key)
                except ValueError:
                    raise HTTPException(401, "installer authentication denied") from None
                if not hmac.compare_digest(bytes(row["license_key_hash"]), supplied_hash):
                    raise HTTPException(401, "installer authentication denied")
            require_entitlement(connection, int(row["license_uid"]), product)
            challenge_id = secrets.token_urlsafe(20)
            connection.execute(
                """
                INSERT INTO challenges(
                    challenge_id,kind,scope,product,license_uid,device_id,nonce,install_signing_key,
                    hardware_core_hash,monitor_hash,release_id,client_sha256,game_uuid,game_name,
                    created_at,expires_at
                ) VALUES(?,'auth',?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    challenge_id,
                    scope,
                    product,
                    row["license_uid"],
                    row["device_id"],
                    secrets.token_bytes(32),
                    row["install_signing_key"],
                    hardware_core,
                    monitor_hash,
                    body.release_id,
                    body.client_sha256,
                    body.game_uuid,
                    body.game_name,
                    now,
                    now + settings.challenge_ttl_seconds,
                ),
            )
            challenge = connection.execute("SELECT * FROM challenges WHERE challenge_id=?", (challenge_id,)).fetchone()
            audit(connection, f"{label}_challenge", int(row["license_uid"]), challenge_id)
        return {
            "challenge_id": challenge_id,
            "message": b64e(_message(challenge)),
            "expires_at": challenge["expires_at"],
        }

    def scoped_auth_complete(body: CompleteRequest, request: Request, scope: str, label: str):
        apply_limit(request, f"{label}-complete", 30, 60)
        if body.token_signature is None:
            raise HTTPException(401, f"{label} authentication denied")
        now = int(time.time())
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """
                SELECT c.*,d.token_signing_key,d.status AS device_status,d.license_uid AS device_license_uid,
                       l.status AS license_status,l.expires_at AS license_expires
                FROM challenges c
                JOIN devices d ON d.device_id=c.device_id
                JOIN licenses l ON l.uid=c.license_uid
                WHERE c.challenge_id=? AND c.kind='auth' AND c.scope=?
                """,
                (body.challenge_id, scope),
            ).fetchone()
            if (
                row is None
                or row["consumed_at"] is not None
                or int(row["expires_at"]) <= now
                or row["device_status"] != "active"
                or row["license_status"] != "active"
                or (row["license_expires"] is not None and int(row["license_expires"]) <= now)
            ):
                raise HTTPException(401, f"{label} authentication denied")
            if int(row["device_license_uid"]) != int(row["license_uid"]) or (scope != "installer" and row["product"] != CELESTE):
                raise HTTPException(401, f"{label} authentication denied")
            if scope == "installer" and not settings.accepts_installer_identity(row["release_id"], row["client_sha256"]):
                raise HTTPException(409, "unsupported release")
            require_entitlement(connection, int(row["license_uid"]), str(row["product"]))
            message = _message(row)
            if (
                not verify_ed25519(bytes(row["install_signing_key"]), message, body.install_signature)
                or not verify_ed25519(bytes(row["token_signing_key"]), message, body.token_signature)
            ):
                audit(connection, f"{label}_bad_proof", int(row["license_uid"]), body.challenge_id)
                raise HTTPException(401, f"{label} authentication denied")
            dpop.require(connection, request, bytes(row["install_signing_key"]))
            connection.execute("UPDATE challenges SET consumed_at=? WHERE challenge_id=?", (now, body.challenge_id))
            connection.execute(
                """
                UPDATE devices SET last_seen_at=?,last_game_uuid=?,last_game_name=?,last_call_at=?,
                    last_call_endpoint=?,last_ip=? WHERE device_id=?
                """,
                (
                    now,
                    row["game_uuid"],
                    row["game_name"],
                    now,
                    f"/v1/{label}/complete",
                    client_ip(request),
                    row["device_id"],
                ),
            )
            audit(connection, f"{label}_success", int(row["license_uid"]), str(row["device_id"]))
        return row, now

    def scoped_grant(row, now: int, audience: str, client_sha256: str | None = None, **claims):
        return signer.sign(
            {
                "aud": audience,
                "cnf": confirmation(bytes(row["install_signing_key"])),
                "product": str(row["product"]),
                "client_sha256": client_sha256 or settings.release_sha256,
                "device": row["device_id"],
                "exp": now + settings.grant_ttl_seconds,
                "iat": now,
                "jti": secrets.token_urlsafe(18),
                "nbf": now - 5,
                "release": settings.release_id,
                "sub": str(row["license_uid"]),
                **claims,
            }
        )

    @app.post("/v1/installer/start")
    def installer_start(body: AuthStartRequest, request: Request):
        return scoped_auth_start(body, request, "installer", "installer", launcher_compatible=True)

    @app.post("/v1/installer/complete")
    def installer_complete(body: CompleteRequest, request: Request):
        row, now = scoped_auth_complete(body, request, "installer", "installer")
        grant = scoped_grant(
            row,
            now,
            "celeste-installer",
            client_sha256=str(row["client_sha256"]),
            release=str(row["release_id"]),
            payload_release=settings.release_id,
            payload_sha256=settings.release_sha256,
            bootstrap=settings.bootstrap_id,
            bootstrap_sha256=settings.bootstrap_sha256,
        )
        return {
            "grant": grant,
            "uid": int(row["license_uid"]),
            "expires_at": now + settings.grant_ttl_seconds,
            "bootstrap": {
                "url": f"/v1/bootstrap/{settings.bootstrap_id}",
                "sha256": settings.bootstrap_sha256,
            },
        }

    @app.post("/v1/installer/consume")
    def installer_consume(request: Request):
        """Consume the native verifier handoff exactly once at game startup."""
        apply_limit(request, "installer-consume", 30, 60)
        claims = signer.verify(access_token(request))
        if (
            claims is None
            or claims.get("aud") != "celeste-installer"
            or not settings.accepts_installer_identity(claims.get("release"), claims.get("client_sha256"))
            or claims.get("payload_release") != settings.release_id
            or claims.get("payload_sha256") != settings.release_sha256
            or claims.get("bootstrap") != settings.bootstrap_id
            or claims.get("bootstrap_sha256") != settings.bootstrap_sha256
        ):
            raise HTTPException(401, "invalid grant")
        device_id = require_active_artifact_device(claims, request, "/v1/installer/consume")
        jti = str(claims.get("jti", ""))
        try:
            license_uid = int(claims.get("sub", 0))
            expires_at = int(claims.get("exp", 0))
        except (TypeError, ValueError):
            raise HTTPException(401, "invalid grant") from None
        if len(jti) < 16 or len(jti) > 128 or expires_at <= int(time.time()):
            raise HTTPException(401, "invalid grant")

        now = int(time.time())
        accepted = False
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT license_uid FROM devices WHERE device_id=?",
                (device_id,),
            ).fetchone()
            if row is None or int(row["license_uid"]) != license_uid:
                raise HTTPException(401, "invalid grant")
            connection.execute("DELETE FROM installer_ticket_uses WHERE expires_at<=?", (now,))
            inserted = connection.execute(
                "INSERT OR IGNORE INTO installer_ticket_uses(jti,device_id,consumed_at,expires_at) VALUES(?,?,?,?)",
                (jti, device_id, now, expires_at),
            )
            accepted = inserted.rowcount == 1
            audit(
                connection,
                "installer_ticket_consumed" if accepted else "installer_ticket_replayed",
                license_uid,
                device_id,
                jti,
            )
            if not accepted:
                security_event(
                    connection,
                    "installer_ticket_replayed",
                    "high",
                    license_uid,
                    device_id,
                    "A native launch handoff was reused",
                    request,
                )
        if not accepted:
            raise HTTPException(401, "invalid grant")
        return {"accepted": True}

    @app.post("/v1/stage2/start")
    def stage_two_start(body: AuthStartRequest, request: Request):
        return scoped_auth_start(body, request, "stage2", "stage2")

    @app.post("/v1/stage2/complete")
    def stage_two_complete(body: CompleteRequest, request: Request):
        row, now = scoped_auth_complete(body, request, "stage2", "stage2")
        grant = scoped_grant(
            row,
            now,
            "celeste-stage-two",
            stage3=settings.loader_three_id,
            stage3_sha256=settings.loader_three_sha256,
        )
        return {
            "grant": grant,
            "uid": int(row["license_uid"]),
            "expires_at": now + settings.grant_ttl_seconds,
            "loader": {
                "url": f"/v1/loader/{settings.loader_three_id}",
                "sha256": settings.loader_three_sha256,
            },
        }

    @app.post("/v1/stage3/start")
    def stage_three_start(body: AuthStartRequest, request: Request):
        return scoped_auth_start(body, request, "stage3", "stage3")

    @app.post("/v1/stage3/complete")
    def stage_three_complete(body: CompleteRequest, request: Request):
        row, now = scoped_auth_complete(body, request, "stage3", "stage3")
        grant = scoped_grant(
            row,
            now,
            "celeste-stage-three",
            stage4=settings.loader_four_id,
            stage4_sha256=settings.loader_four_sha256,
        )
        return {
            "grant": grant,
            "uid": int(row["license_uid"]),
            "expires_at": now + settings.grant_ttl_seconds,
            "loader": {
                "url": f"/v1/loader/{settings.loader_four_id}",
                "sha256": settings.loader_four_sha256,
            },
        }

    @app.post("/v1/stage4/start")
    def stage_four_start(body: AuthStartRequest, request: Request):
        return scoped_auth_start(body, request, "stage4", "stage4")

    @app.post("/v1/stage4/complete")
    def stage_four_complete(body: CompleteRequest, request: Request):
        row, now = scoped_auth_complete(body, request, "stage4", "stage4")
        grant = scoped_grant(
            row,
            now,
            "celeste-stage-four",
            chunks=[settings.chunk_a_id, settings.chunk_b_id],
        )
        return {
            "grant": grant,
            "uid": int(row["license_uid"]),
            "expires_at": now + settings.grant_ttl_seconds,
            "artifact": {
                "release": settings.release_id,
                "sha256": settings.release_sha256,
                "chunks": [
                    {"url": f"/v1/blob/{settings.chunk_a_id}", "sha256": settings.chunk_a_sha256},
                    {"url": f"/v1/blob/{settings.chunk_b_id}", "sha256": settings.chunk_b_sha256},
                ],
            },
        }

    @app.post("/v1/session/check")
    def session_check(body: SessionCheckRequest, request: Request):
        apply_limit(request, "session-check", 120, 60)
        runtime = runtime_release(body)
        expected_digest = settings.release_sha256 if runtime is None else runtime["runtime_sha256"]
        now = int(time.time())
        if abs(now - body.timestamp) > 60:
            raise HTTPException(401, "session verification denied")
        message = _session_message(body)
        response_token = None
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """
                SELECT d.*,l.status AS license_status,l.expires_at AS license_expires
                FROM devices d JOIN licenses l ON l.uid=d.license_uid
                WHERE d.device_id=?
                """,
                (body.device_id,),
            ).fetchone()
            if runtime is not None and (
                row is None
                or row["status"] != "active" or row["license_status"] != "active"
                or (row["license_expires"] is not None and int(row["license_expires"]) <= now)
            ):
                raise HTTPException(401, "session verification denied")
            if row is not None:
                require_entitlement(connection, int(row["license_uid"]), CELESTE if runtime is None else PLUS)
                dpop.require(connection, request, bytes(row["install_signing_key"]))
                try:
                    install_ok = verify_ed25519(bytes(row["install_signing_key"]), message, body.install_signature)
                    token_ok = verify_ed25519(bytes(row["token_signing_key"]), message, body.token_signature)
                except InvalidSignature:
                    if runtime is None:
                        raise
                    install_ok = token_ok = False
                if not install_ok or not token_ok:
                    security_event(
                        connection,
                        "invalid_session_proof",
                        "high",
                        int(row["license_uid"]),
                        body.device_id,
                        "A session check failed dual-signature verification",
                        request,
                    )
                else:
                    replayed = False
                    connection.execute("DELETE FROM session_nonces WHERE expires_at<=?", (now,))
                    try:
                        connection.execute(
                            "INSERT INTO session_nonces(device_id,nonce,expires_at) VALUES(?,?,?)",
                            (body.device_id, body.nonce, now + 120),
                        )
                    except sqlite3.IntegrityError:
                        replayed = True
                        security_event(
                            connection,
                            "replayed_session_check",
                            "high",
                            int(row["license_uid"]),
                            body.device_id,
                            "A signed session nonce was reused",
                            request,
                        )
                    tampered = body.event == "integrity_mismatch" or not hmac.compare_digest(
                        body.observed_sha256, expected_digest
                    )
                    license_active = (
                        row["status"] == "active"
                        and row["license_status"] == "active"
                        and (row["license_expires"] is None or int(row["license_expires"]) > now)
                    )
                    if tampered:
                        revoke_for_tamper(
                            connection,
                            int(row["license_uid"]),
                            body.device_id,
                            body.observed_sha256,
                            request,
                        )
                    elif not replayed and license_active:
                        connection.execute(
                            """UPDATE devices SET last_seen_at=?,last_game_uuid=?,last_game_name=?,last_call_at=?,
                                last_call_endpoint=?,last_ip=?,last_integrity_sha256=?,last_integrity_at=?
                            WHERE device_id=?""",
                            (now, body.game_uuid, body.game_name, now, "/v1/session/check", client_ip(request),
                             body.observed_sha256, now, body.device_id),
                        )
                        response_token = signer.sign(
                            {
                                "aud": "celeste-session",
                                "cnf": confirmation(bytes(row["install_signing_key"])),
                                "product": CELESTE if runtime is None else PLUS,
                                "client_sha256": expected_digest,
                                "device": body.device_id,
                                "exp": now + 90,
                                "iat": now,
                                "nbf": now - 5,
                                "nonce": body.nonce,
                                "status": "active",
                                "sub": str(row["license_uid"]),
                                **({"release": runtime["release_id"], "client_version": runtime["client_version"],
                                    "beta_catalog_sha256": runtime["catalog_sha256"]} if runtime is not None else {}),
                            }
                        )
                    elif not replayed:
                        security_event(
                            connection,
                            "revoked_or_expired_session",
                            "medium",
                            int(row["license_uid"]),
                            body.device_id,
                            "A revoked or expired installation attempted to continue a session",
                            request,
                        )
        if response_token is None:
            raise HTTPException(401, "session verification denied")
        return {"status": "active", "heartbeat_after": 30, "session_token": response_token}

    @app.get("/v1/configs")
    def cloud_configs(request: Request):
        _, identity = active_cloud_identity(request)
        license_uid = int(identity["license_uid"])
        with database.connect() as connection:
            rows = connection.execute(
                """
                SELECT config_id,owner_license_uid,name,author_name,image_sha256,image_size,
                       modules_json,created_at,updated_at,downloads
                FROM cloud_configs
                WHERE published=1
                ORDER BY updated_at DESC, config_id ASC
                LIMIT 250
                """
            ).fetchall()
        touch_device(str(identity["device_id"]), "/v1/configs", request)
        configs = []
        for row in rows:
            try:
                modules = json.loads(str(row["modules_json"]))
            except (TypeError, json.JSONDecodeError):
                modules = []
            configs.append(
                {
                    "id": str(row["config_id"]),
                    "name": str(row["name"]),
                    "author": str(row["author_name"]),
                    "created_at": int(row["created_at"]),
                    "updated_at": int(row["updated_at"]),
                    "downloads": int(row["downloads"]),
                    "modules": modules if isinstance(modules, list) else [],
                    "image_url": f"/v1/configs/{row['config_id']}/image",
                    "image_sha256": str(row["image_sha256"]),
                    "image_size": int(row["image_size"]),
                    "owned": int(row["owner_license_uid"]) == license_uid,
                }
            )
        return {"configs": configs}

    @app.post("/v1/configs")
    def publish_cloud_config(body: CloudConfigPublishRequest, request: Request):
        apply_limit(request, "cloud-publish", 20, 60)
        _, identity = active_cloud_identity(request)
        image = _decode_config_image(body.image)
        digest = hashlib.sha256(image).hexdigest()
        now = int(time.time())
        license_uid = int(identity["license_uid"])
        slug = _config_slug(body.name)
        author = str(identity["last_game_name"] or "Celeste user")[:32]

        with database.connect() as connection:
            existing = connection.execute(
                "SELECT config_id,image_filename,created_at FROM cloud_configs WHERE owner_license_uid=? AND slug=?",
                (license_uid, slug),
            ).fetchone()
        if existing is None:
            config_id = "c" + secrets.token_urlsafe(17)
            image_filename = config_id + ".png"
            created_at = now
        else:
            config_id = str(existing["config_id"])
            image_filename = str(existing["image_filename"])
            created_at = int(existing["created_at"])

        if Path(image_filename).name != image_filename:
            raise HTTPException(500, "cloud storage metadata is invalid")
        target = cloud_config_root / image_filename
        temporary = cloud_config_root / (image_filename + "." + secrets.token_hex(6) + ".part")
        temporary.write_bytes(image)
        os.replace(temporary, target)

        with database.connect() as connection:
            connection.execute(
                """
                INSERT INTO cloud_configs(
                    config_id,owner_license_uid,slug,name,author_name,image_filename,
                    image_sha256,image_size,modules_json,created_at,updated_at,published
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,1)
                ON CONFLICT(owner_license_uid,slug) DO UPDATE SET
                    name=excluded.name,
                    author_name=excluded.author_name,
                    image_filename=excluded.image_filename,
                    image_sha256=excluded.image_sha256,
                    image_size=excluded.image_size,
                    modules_json=excluded.modules_json,
                    updated_at=excluded.updated_at,
                    published=1
                """,
                (
                    config_id,
                    license_uid,
                    slug,
                    body.name,
                    author,
                    image_filename,
                    digest,
                    len(image),
                    json.dumps(body.modules, separators=(",", ":")),
                    created_at,
                    now,
                ),
            )
            audit(connection, "cloud_config_published", license_uid, config_id, body.name)
        touch_device(str(identity["device_id"]), "/v1/configs", request)
        return {
            "id": config_id,
            "name": body.name,
            "updated_at": now,
            "image_sha256": digest,
            "image_size": len(image),
        }

    @app.get("/v1/configs/{config_id}/image")
    def cloud_config_image(config_id: str, request: Request):
        if not re.fullmatch(r"c[A-Za-z0-9_-]{16,40}", config_id):
            raise HTTPException(404, "config not found")
        _, identity = active_cloud_identity(request)
        with database.connect() as connection:
            row = connection.execute(
                """
                SELECT config_id,name,image_filename,image_sha256
                FROM cloud_configs WHERE config_id=? AND published=1
                """,
                (config_id,),
            ).fetchone()
            if row is None:
                raise HTTPException(404, "config not found")
            connection.execute(
                "UPDATE cloud_configs SET downloads=downloads+1 WHERE config_id=?",
                (config_id,),
            )
        filename = str(row["image_filename"])
        if Path(filename).name != filename:
            raise HTTPException(404, "config not found")
        path = cloud_config_root / filename
        if not path.is_file():
            raise HTTPException(503, "config image unavailable")
        touch_device(str(identity["device_id"]), f"/v1/configs/{config_id}/image", request)
        return FileResponse(
            path,
            media_type="image/png",
            filename=_config_slug(str(row["name"])) + ".png",
            headers={"ETag": f'"{row["image_sha256"]}"'},
        )

    @app.get("/v1/blob/{chunk_id}")
    def artifact_chunk(chunk_id: str, request: Request):
        claims = signer.verify(access_token(request))
        if (
            claims is None
            or claims.get("aud") != "celeste-stage-four"
            or claims.get("release") != settings.release_id
            or chunk_id not in {settings.chunk_a_id, settings.chunk_b_id}
            or chunk_id not in claims.get("chunks", [])
        ):
            raise HTTPException(401, "invalid grant")
        device_id = require_active_artifact_device(claims, request, f"/v1/blob/{chunk_id}")
        filename = "chunk-a.bin" if chunk_id == settings.chunk_a_id else "chunk-b.bin"
        path = Path(settings.artifact_dir) / filename
        if not path.is_file():
            raise HTTPException(503, "release unavailable")
        return FileResponse(path, media_type="application/octet-stream", filename=None)

    def require_active_artifact_device(claims, request: Request, endpoint: str, product: str = CELESTE) -> str:
        require_runtime_enabled()
        require_claim_product(claims, product)
        device_id = str(claims.get("device", ""))
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                """
                SELECT d.license_uid,d.install_signing_key,d.status AS device_status,l.status AS license_status,l.expires_at
                FROM devices d JOIN licenses l ON l.uid=d.license_uid
                WHERE d.device_id=?
                """,
                (device_id,),
            ).fetchone()
            if row is not None:
                if claims.get("sub") != str(row["license_uid"]):
                    raise HTTPException(401, "invalid grant")
                require_entitlement(connection, int(row["license_uid"]), product)
            now = int(time.time())
            if (
                row is None
                or row["device_status"] != "active"
                or row["license_status"] != "active"
                or (row["expires_at"] is not None and int(row["expires_at"]) <= now)
                or type(claims.get("exp")) is not int or claims["exp"] <= now
            ):
                raise HTTPException(401, "invalid grant")
            require_unrevoked(connection, claims)
            dpop.require(connection, request, bytes(row["install_signing_key"]), token=access_token(request), claims=claims)
            if endpoint.startswith(("/v1/bootstrap/", "/v1/loader/", "/v1/blob/", "/v1/beta/artifacts/")):
                token_bucket = keyed_digest(settings.key_pepper, "artifact-token", claims["jti"])
                ip_bucket = keyed_digest(settings.key_pepper, "artifact-ip", client_ip(request))
                retry = database.consume_artifact_allowance(connection, token_bucket, ip_bucket)
                if retry:
                    raise HTTPException(429, "temporarily rate limited", headers={"Retry-After": str(retry)})
        touch_device(device_id, endpoint, request)
        return device_id

    @app.get("/v1/bootstrap/{bootstrap_id}")
    def bootstrap_loader(bootstrap_id: str, request: Request):
        claims = signer.verify(access_token(request))
        if (
            claims is None
            or claims.get("aud") != "celeste-installer"
            or not settings.accepts_installer_identity(claims.get("release"), claims.get("client_sha256"))
            or claims.get("payload_release") != settings.release_id
            or claims.get("payload_sha256") != settings.release_sha256
            or bootstrap_id != settings.bootstrap_id
            or claims.get("bootstrap") != bootstrap_id
            or claims.get("bootstrap_sha256") != settings.bootstrap_sha256
        ):
            raise HTTPException(401, "invalid grant")
        require_active_artifact_device(claims, request, f"/v1/bootstrap/{bootstrap_id}")
        path = Path(settings.artifact_dir) / settings.bootstrap_filename
        if not path.is_file():
            raise HTTPException(503, "bootstrap unavailable")
        return FileResponse(path, media_type="application/octet-stream", filename=None)

    @app.get("/v1/loader/{loader_id}")
    def staged_loader(loader_id: str, request: Request):
        claims = signer.verify(access_token(request))
        loaders = {
            settings.loader_id: (
                "celeste-client",
                "stage2",
                "stage2_sha256",
                settings.loader_sha256,
                settings.loader_filename,
            ),
            settings.loader_three_id: (
                "celeste-stage-two",
                "stage3",
                "stage3_sha256",
                settings.loader_three_sha256,
                settings.loader_three_filename,
            ),
            settings.loader_four_id: (
                "celeste-stage-three",
                "stage4",
                "stage4_sha256",
                settings.loader_four_sha256,
                settings.loader_four_filename,
            ),
        }
        loader = loaders.get(loader_id)
        if claims is None or loader is None or claims.get("release") != settings.release_id:
            raise HTTPException(401, "invalid grant")
        audience, id_claim, digest_claim, digest, filename = loader
        if (
            claims.get("aud") != audience
            or claims.get(id_claim) != loader_id
            or claims.get(digest_claim) != digest
        ):
            raise HTTPException(401, "invalid grant")
        require_active_artifact_device(claims, request, f"/v1/loader/{loader_id}")
        path = Path(settings.artifact_dir) / filename
        if not path.is_file():
            raise HTTPException(503, "loader unavailable")
        return FileResponse(path, media_type="application/octet-stream", filename=None)

    def admin_session(request: Request) -> str | None:
        value = request.cookies.get(COOKIE_NAME, "")
        session_id = parse_session_cookie(settings.session_secret, value)
        if session_id is None:
            return None
        now = int(time.time())
        ip_hash = keyed_digest(settings.session_secret, "admin-session-ip", client_ip(request))
        user_agent_hash = keyed_digest(
            settings.session_secret,
            "admin-session-agent",
            request.headers.get("user-agent", "")[:300],
        )
        with database.connect() as connection:
            row = connection.execute(
                "SELECT session_id,ip_hash,user_agent_hash FROM admin_sessions WHERE session_id=? AND expires_at>?",
                (session_id, now),
            ).fetchone()
            if row is not None and (
                row["ip_hash"] is None
                or row["user_agent_hash"] is None
                or not hmac.compare_digest(bytes(row["ip_hash"]), ip_hash)
                or not hmac.compare_digest(bytes(row["user_agent_hash"]), user_agent_hash)
            ):
                connection.execute("DELETE FROM admin_sessions WHERE session_id=?", (session_id,))
                audit(connection, "admin_session_binding_rejected", None, client_ip(request))
                row = None
        return session_id if row is not None else None

    @app.get("/admin/login", response_class=HTMLResponse)
    def admin_login_page(request: Request):
        if admin_session(request) is not None:
            return RedirectResponse("/admin", status_code=303)
        return templates.TemplateResponse(request, "login.html", {"error": ""})

    @app.post("/admin/login")
    def admin_login(request: Request, password: Annotated[str, Form(min_length=1, max_length=256)]):
        check_limit(request, "admin-login", 900)
        if not verify_admin_password(settings.admin_password_hash, password):
            apply_limit(request, "admin-login", 5, 900)
            with database.connect() as connection:
                security_event(
                    connection,
                    "admin_login_failed",
                    "high",
                    None,
                    "",
                    "An invalid admin password was submitted",
                    request,
                )
            return templates.TemplateResponse(request, "login.html", {"error": "Login failed"}, status_code=401)
        clear_limit(request, "admin-login")
        now = int(time.time())
        session_id = secrets.token_urlsafe(32)
        ip_hash = keyed_digest(settings.session_secret, "admin-session-ip", client_ip(request))
        user_agent_hash = keyed_digest(
            settings.session_secret,
            "admin-session-agent",
            request.headers.get("user-agent", "")[:300],
        )
        with database.connect() as connection:
            connection.execute("DELETE FROM admin_sessions")
            connection.execute(
                """
                INSERT INTO admin_sessions(session_id,created_at,expires_at,ip_hash,user_agent_hash)
                VALUES(?,?,?,?,?)
                """,
                (session_id, now, now + 3600, ip_hash, user_agent_hash),
            )
            audit(connection, "admin_login", None, client_ip(request))
        response = RedirectResponse("/admin", status_code=303)
        response.set_cookie(
            COOKIE_NAME,
            session_cookie(settings.session_secret, session_id, now),
            max_age=3600,
            secure=True,
            httponly=True,
            samesite="strict",
            path="/",
        )
        return response

    def dashboard_context(session_id: str, new_key: str = "", product_filter: str = "") -> dict:
        if product_filter not in {"", CELESTE, PLUS, "both"}:
            raise HTTPException(422, "invalid product filter")
        now = int(time.time())
        with database.connect() as connection:
            licenses = connection.execute(
                """
                SELECT l.*, (SELECT group_concat(product) FROM license_entitlements e WHERE e.license_uid=l.uid) AS product_codes,
                       COUNT(d.device_id) AS devices,
                       SUM(CASE WHEN d.status='active' THEN 1 ELSE 0 END) AS active_devices,
                       MAX(d.last_call_at) AS last_call_at,
                       COALESCE(SUM(d.tamper_count),0) AS tamper_count
                FROM licenses l LEFT JOIN devices d ON d.license_uid=l.uid
                WHERE (?='' OR (?='both' AND (SELECT COUNT(*) FROM license_entitlements e WHERE e.license_uid=l.uid)=2)
                       OR EXISTS(SELECT 1 FROM license_entitlements e WHERE e.license_uid=l.uid AND e.product=?))
                GROUP BY l.uid ORDER BY l.uid DESC LIMIT 200
                """, (product_filter, product_filter, product_filter)
            ).fetchall()
            audit_rows = connection.execute(
                "SELECT occurred_at,event,license_uid,subject,details FROM audit_log ORDER BY id DESC LIMIT 50"
            ).fetchall()
            security_rows = connection.execute(
                """
                SELECT occurred_at,severity,event_type,license_uid,device_id,details,client_ip
                FROM security_events ORDER BY id DESC LIMIT 50
                """
            ).fetchall()
            stats = connection.execute(
                """
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN status='active' AND (expires_at IS NULL OR expires_at>?) THEN 1 ELSE 0 END) AS active,
                       SUM(CASE WHEN status='revoked' THEN 1 ELSE 0 END) AS revoked
                FROM licenses
                """,
                (now,),
            ).fetchone()
            online = connection.execute(
                "SELECT COUNT(*) AS count FROM devices WHERE status='active' AND last_call_at>?",
                (now - 90,),
            ).fetchone()["count"]
        return {
            "licenses": licenses,
            "product_labels": PRODUCT_LABELS,
            "product_filter": product_filter,
            "audit_rows": audit_rows,
            "security_rows": security_rows,
            "stats": stats,
            "online": online,
            "csrf": csrf_token(settings.session_secret, session_id),
            "new_key": new_key,
            "now": now,
        }

    def users_context(session_id: str) -> dict:
        now = int(time.time())
        online_after = now - 90
        with database.connect() as connection:
            raw_users = connection.execute(
                """
                SELECT d.device_id,d.license_uid,d.status,d.created_at,d.last_seen_at,
                       d.last_call_at,d.last_call_endpoint,d.last_game_uuid,d.last_game_name,
                       d.tamper_count,l.label AS license_label,l.status AS license_status,
                       l.expires_at,
                       (SELECT COUNT(*) FROM security_events se WHERE se.device_id=d.device_id) AS flag_count
                FROM devices d JOIN licenses l ON l.uid=d.license_uid
                ORDER BY COALESCE(d.last_seen_at,d.last_call_at,d.created_at) DESC
                LIMIT 500
                """
            ).fetchall()
            totals = connection.execute(
                """
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN d.status='active' AND l.status='active'
                                    AND (l.expires_at IS NULL OR l.expires_at>?) THEN 1 ELSE 0 END) AS active,
                       SUM(CASE WHEN d.status='active' AND l.status='active'
                                    AND (l.expires_at IS NULL OR l.expires_at>?)
                                    AND d.last_seen_at>? THEN 1 ELSE 0 END) AS online,
                       SUM(CASE WHEN EXISTS(
                           SELECT 1 FROM security_events se WHERE se.device_id=d.device_id
                       ) THEN 1 ELSE 0 END) AS flagged
                FROM devices d JOIN licenses l ON l.uid=d.license_uid
                """,
                (now, now, online_after),
            ).fetchone()
        users = []
        for row in raw_users:
            user = dict(row)
            last_seen = int(user["last_seen_at"] or 0)
            user["online"] = (
                user["status"] == "active"
                and user["license_status"] == "active"
                and (user["expires_at"] is None or int(user["expires_at"]) > now)
                and last_seen > online_after
            )
            user["last_active_at"] = user["last_seen_at"] or user["last_call_at"] or user["created_at"]
            users.append(user)
        return {
            "users": users,
            "stats": totals,
            "online_window": 90,
            "csrf": csrf_token(settings.session_secret, session_id),
            "now": now,
        }

    def flags_context(session_id: str) -> dict:
        with database.connect() as connection:
            flagged_users = connection.execute(
                """
                SELECT se.license_uid,se.device_id,
                       COALESCE(NULLIF(d.last_game_name,''),'Unknown player') AS game_name,
                       COALESCE(d.last_game_uuid,'') AS game_uuid,
                       COALESCE(l.label,'') AS license_label,
                       COUNT(*) AS flag_count,MAX(se.occurred_at) AS last_flag_at,
                       MAX(CASE se.severity WHEN 'critical' THEN 4 WHEN 'high' THEN 3
                                            WHEN 'medium' THEN 2 ELSE 1 END) AS severity_rank
                FROM security_events se
                LEFT JOIN devices d ON d.device_id=se.device_id
                LEFT JOIN licenses l ON l.uid=se.license_uid
                WHERE se.license_uid IS NOT NULL
                GROUP BY se.license_uid,se.device_id
                ORDER BY last_flag_at DESC
                LIMIT 200
                """
            ).fetchall()
            user_flags = connection.execute(
                """
                SELECT se.occurred_at,se.severity,se.event_type,se.license_uid,se.device_id,
                       se.details,se.client_ip,
                       COALESCE(NULLIF(d.last_game_name,''),'Unknown player') AS game_name
                FROM security_events se
                LEFT JOIN devices d ON d.device_id=se.device_id
                WHERE se.license_uid IS NOT NULL
                ORDER BY se.id DESC LIMIT 200
                """
            ).fetchall()
            website_security = [dict(row) for row in connection.execute(
                """
                SELECT occurred_at,severity,event_type,details,client_ip
                FROM security_events
                WHERE license_uid IS NULL
                ORDER BY id DESC LIMIT 200
                """
            ).fetchall()]
            website_audit = connection.execute(
                """
                SELECT occurred_at,event AS event_type,details,subject AS client_ip
                FROM audit_log
                WHERE license_uid IS NULL AND event IN ('admin_session_binding_rejected')
                ORDER BY id DESC LIMIT 200
                """
            ).fetchall()
            for row in website_audit:
                item = dict(row)
                item["severity"] = "high"
                website_security.append(item)
            website_security.sort(key=lambda row: int(row["occurred_at"]), reverse=True)
            website_flags = website_security[:200]
            counts = connection.execute(
                """
                SELECT
                    (SELECT COUNT(*) FROM security_events WHERE license_uid IS NOT NULL) AS user_events,
                    (SELECT COUNT(*) FROM security_events WHERE license_uid IS NULL) +
                    (SELECT COUNT(*) FROM audit_log WHERE license_uid IS NULL
                        AND event IN ('admin_session_binding_rejected')) AS website_events,
                    (SELECT COUNT(*) FROM security_events
                        WHERE severity IN ('high','critical')) AS severe_events
                """
            ).fetchone()
        return {
            "flagged_users": flagged_users,
            "user_flags": user_flags,
            "website_flags": website_flags,
            "counts": counts,
            "csrf": csrf_token(settings.session_secret, session_id),
        }

    def cloud_configs_context(session_id: str) -> dict:
        with database.connect() as connection:
            raw_configs = connection.execute(
                """
                SELECT c.*,l.label AS license_label,l.status AS license_status
                FROM cloud_configs c
                LEFT JOIN licenses l ON l.uid=c.owner_license_uid
                ORDER BY c.updated_at DESC,c.config_id ASC
                LIMIT 500
                """
            ).fetchall()
            stats = connection.execute(
                """
                SELECT COUNT(*) AS total,
                       COUNT(DISTINCT owner_license_uid) AS publishers,
                       COALESCE(SUM(downloads),0) AS downloads,
                       COALESCE(SUM(image_size),0) AS storage_bytes
                FROM cloud_configs
                WHERE published=1
                """
            ).fetchone()
        configs = []
        for row in raw_configs:
            item = dict(row)
            try:
                modules = json.loads(str(row["modules_json"]))
            except (TypeError, json.JSONDecodeError):
                modules = []
            item["modules"] = modules if isinstance(modules, list) else []
            item["image_kib"] = max(0.1, int(row["image_size"]) / 1024.0)
            filename = str(row["image_filename"])
            image_path = (cloud_config_root / filename).resolve()
            item["image_available"] = (
                Path(filename).name == filename
                and image_path.parent == cloud_config_root
                and image_path.is_file()
            )
            configs.append(item)
        return {
            "configs": configs,
            "stats": stats,
            "csrf": csrf_token(settings.session_secret, session_id),
        }

    @app.get("/admin", response_class=HTMLResponse)
    def admin_dashboard(request: Request, product_filter: str = ""):
        session_id = admin_session(request)
        if session_id is None:
            return RedirectResponse("/admin/login", status_code=303)
        return templates.TemplateResponse(
            request,
            "dashboard.html",
            dashboard_context(session_id, product_filter=product_filter),
        )

    @app.get("/admin/users", response_class=HTMLResponse)
    def admin_users(request: Request):
        session_id = admin_session(request)
        if session_id is None:
            return RedirectResponse("/admin/login", status_code=303)
        return templates.TemplateResponse(request, "users.html", users_context(session_id))

    @app.get("/admin/flags", response_class=HTMLResponse)
    def admin_flags(request: Request):
        session_id = admin_session(request)
        if session_id is None:
            return RedirectResponse("/admin/login", status_code=303)
        return templates.TemplateResponse(request, "flags.html", flags_context(session_id))

    @app.get("/admin/configs", response_class=HTMLResponse)
    def admin_cloud_configs(request: Request):
        session_id = admin_session(request)
        if session_id is None:
            return RedirectResponse("/admin/login", status_code=303)
        return templates.TemplateResponse(request, "cloud_configs.html", cloud_configs_context(session_id))

    @app.get("/admin/configs/{config_id}/image")
    def admin_cloud_config_image(config_id: str, request: Request):
        if admin_session(request) is None:
            return RedirectResponse("/admin/login", status_code=303)
        if not re.fullmatch(r"c[A-Za-z0-9_-]{16,40}", config_id):
            raise HTTPException(404, "config not found")
        with database.connect() as connection:
            row = connection.execute(
                "SELECT image_filename,image_sha256 FROM cloud_configs WHERE config_id=?",
                (config_id,),
            ).fetchone()
        if row is None:
            raise HTTPException(404, "config not found")
        filename = str(row["image_filename"])
        path = (cloud_config_root / filename).resolve()
        if Path(filename).name != filename or path.parent != cloud_config_root or not path.is_file():
            raise HTTPException(404, "config image unavailable")
        return FileResponse(
            path,
            media_type="image/png",
            headers={
                "Content-Disposition": "inline",
                "ETag": f'"{row["image_sha256"]}"',
            },
        )

    @app.get("/admin/licenses/{uid}", response_class=HTMLResponse)
    def license_detail(uid: int, request: Request):
        session_id = admin_session(request)
        if session_id is None:
            return RedirectResponse("/admin/login", status_code=303)
        with database.connect() as connection:
            license_row = connection.execute("SELECT * FROM licenses WHERE uid=?", (uid,)).fetchone()
            if license_row is None:
                raise HTTPException(404, "license not found")
            products = [PRODUCT_LABELS[row["product"]] for row in connection.execute(
                "SELECT product FROM license_entitlements WHERE license_uid=? ORDER BY product", (uid,))]
            raw_devices = connection.execute(
                "SELECT * FROM devices WHERE license_uid=? ORDER BY created_at DESC",
                (uid,),
            ).fetchall()
            devices = []
            for row in raw_devices:
                item = dict(row)
                item["hardware_core_hash"] = bytes(row["hardware_core_hash"]).hex()
                item["monitor_hash"] = bytes(row["monitor_hash"]).hex()
                item["install_key_fingerprint"] = hashlib.sha256(bytes(row["install_signing_key"])).hexdigest()
                item["token_key_fingerprint"] = hashlib.sha256(bytes(row["token_signing_key"])).hexdigest()
                devices.append(item)
            security_rows = connection.execute(
                """
                SELECT occurred_at,severity,event_type,device_id,details,client_ip
                FROM security_events WHERE license_uid=? ORDER BY id DESC LIMIT 200
                """,
                (uid,),
            ).fetchall()
            audit_rows = connection.execute(
                """
                SELECT occurred_at,event,subject,details
                FROM audit_log WHERE license_uid=? ORDER BY id DESC LIMIT 200
                """,
                (uid,),
            ).fetchall()
        return templates.TemplateResponse(
            request,
            "license_detail.html",
            {
                "license": license_row,
                "products": products,
                "devices": devices,
                "security_rows": security_rows,
                "audit_rows": audit_rows,
                "csrf": csrf_token(settings.session_secret, session_id),
                "now": int(time.time()),
            },
        )

    @app.post("/admin/licenses", response_class=HTMLResponse)
    def create_license(
        request: Request,
        csrf: Annotated[str, Form()],
        product: Annotated[str, Form(max_length=16)],
        label: Annotated[str, Form(max_length=80)] = "",
        max_devices: Annotated[int, Form(ge=1, le=8)] = 1,
        duration: Annotated[str, Form(max_length=16)] = "lifetime",
    ):
        session_id = admin_session(request)
        if session_id is None or not valid_csrf(settings.session_secret, session_id, csrf):
            raise HTTPException(403, "forbidden")
        try:
            with database.connect() as connection:
                connection.execute("BEGIN IMMEDIATE")
                _, display = issue_license(connection, settings.key_pepper, product, label, duration, max_devices)
        except ValueError as error:
            raise HTTPException(422, str(error)) from None
        return templates.TemplateResponse(
            request,
            "dashboard.html",
            dashboard_context(session_id, display),
        )

    @app.post("/admin/licenses/{uid}/revoke")
    def revoke_license(uid: int, request: Request, csrf: Annotated[str, Form()]):
        session_id = admin_session(request)
        if session_id is None or not valid_csrf(settings.session_secret, session_id, csrf):
            raise HTTPException(403, "forbidden")
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            now = int(time.time())
            connection.execute(
                "UPDATE licenses SET status='revoked',revoked_at=?,revoked_reason='admin revoked' WHERE uid=?",
                (now, uid),
            )
            connection.execute("UPDATE devices SET status='revoked' WHERE license_uid=?", (uid,))
            audit(connection, "license_revoked", uid)
        return RedirectResponse(f"/admin/licenses/{uid}", status_code=303)

    @app.post("/admin/licenses/{uid}/reset")
    def reset_license_binding(uid: int, request: Request, csrf: Annotated[str, Form()]):
        session_id = admin_session(request)
        if session_id is None or not valid_csrf(settings.session_secret, session_id, csrf):
            raise HTTPException(403, "forbidden")
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            exists = connection.execute("SELECT uid FROM licenses WHERE uid=?", (uid,)).fetchone()
            if exists is None:
                raise HTTPException(404, "license not found")
            connection.execute("DELETE FROM challenges WHERE license_uid=?", (uid,))
            connection.execute("DELETE FROM devices WHERE license_uid=?", (uid,))
            connection.execute(
                "UPDATE licenses SET status='active',revoked_at=NULL,revoked_reason='' WHERE uid=?",
                (uid,),
            )
            audit(connection, "license_binding_reset", uid)
        return RedirectResponse(f"/admin/licenses/{uid}", status_code=303)

    @app.post("/admin/logout")
    def admin_logout(request: Request, csrf: Annotated[str, Form()]):
        session_id = admin_session(request)
        if session_id is None or not valid_csrf(settings.session_secret, session_id, csrf):
            raise HTTPException(403, "forbidden")
        with database.connect() as connection:
            connection.execute("DELETE FROM admin_sessions WHERE session_id=?", (session_id,))
        response = RedirectResponse("/admin/login", status_code=303)
        response.delete_cookie(COOKIE_NAME, path="/")
        return response

    app.include_router(create_beta_router(settings, database, signer, require_active_artifact_device, apply_limit))
    app.include_router(create_native_manifest_router(settings, database, signer, apply_limit))
    return app
