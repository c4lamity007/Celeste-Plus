"""Proof-bound validation and atomic rotation of existing short-lived grants."""
from datetime import datetime, timezone
import hashlib
import hmac
import re
import secrets
import time
from fastapi import APIRouter, Request
from .auth_errors import failure
from .auth_lifecycle import record_grant, require_current_grant
from .beta_delivery import beta_runtime_catalog
from .dpop import access_token
from .products import PLUS, require_entitlement
from .token_revocation import require_unrevoked


AUDIENCES = {"celeste-client", "celeste-installer", "celeste-stage-two", "celeste-stage-three", "celeste-stage-four"}


def create_grant_router(settings, database, signer, dpop, apply_limit):
    router = APIRouter()

    def execute(request, rotate):
        apply_limit(request, "grant-rotation" if rotate else "grant-validation", 30, 60)
        if settings.runtime_kill_switch:
            raise failure("runtime_disabled", "Runtime is temporarily disabled", 403)
        token = access_token(request)
        claims = signer.verify(token)
        if not claims or claims.get("aud") not in AUDIENCES:
            raise failure("invalid_grant", "Grant is invalid or expired")
        product = claims["product"]
        if product == PLUS and settings.plus_auth_protocol == 2 and claims.get("auth_protocol") != 2:
            raise failure("reactivation_required", "Verify this installation again")
        if claims["aud"] == "celeste-installer":
            current = (settings.accepts_installer_identity(claims.get("release"), claims.get("client_sha256"))
                       and claims.get("payload_release") == settings.release_id
                       and claims.get("payload_sha256") == settings.release_sha256
                       and claims.get("bootstrap_sha256") == settings.bootstrap_sha256)
        elif product == PLUS:
            catalog = beta_runtime_catalog(settings, int(claims["sub"]), claims["device"])
            current = (claims.get("release") == catalog["release_id"]
                       and claims.get("client_sha256") == catalog["runtime_sha256"]
                       and claims.get("beta_catalog_sha256") == catalog["catalog_sha256"])
        else:
            current = claims.get("release") == settings.release_id and claims.get("client_sha256") == settings.release_sha256
        if not current:
            raise failure("invalid_grant", "Grant belongs to an unavailable release")
        devices = request.headers.getlist("x-device-id")
        if len(devices) != 1 or not re.fullmatch(r"[0-9a-f]{64}", devices[0]):
            raise failure("device_mismatch", "A device binding is required")
        result_token = None
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            now = int(time.time())
            row = connection.execute("""SELECT d.*,l.status AS license_status,l.expires_at AS license_expires
                FROM devices d JOIN licenses l ON l.uid=d.license_uid
                WHERE d.device_id=? AND d.license_uid=?""", (claims["device"], int(claims["sub"]))).fetchone()
            if row is None or row["status"] != "active":
                raise failure("device_mismatch", "This installation is not active")
            if row["license_status"] != "active" or row["license_expires"] is not None and row["license_expires"] <= now:
                raise failure("subscription_inactive", "This license is not active")
            if claims["exp"] <= now:
                raise failure("invalid_grant", "Grant is expired")
            expected = (row["hwid_sha256"] if product == PLUS else None) or bytes(row["hardware_core_hash"]).hex()
            if not hmac.compare_digest(expected, devices[0]):
                raise failure("device_mismatch", "This installation belongs to a different device")
            require_entitlement(connection, int(claims["sub"]), product)
            require_unrevoked(connection, claims)
            require_current_grant(connection, claims, token)
            dpop.require(connection, request, bytes(row["install_signing_key"]), token=token, claims=claims,
                         hardware=devices[0])
            conflict = connection.execute("SELECT conflicted_at FROM runtime_bindings WHERE device_id=?", (row["device_id"],)).fetchone()
            if product == PLUS and conflict is not None and conflict["conflicted_at"] is not None:
                raise failure("concurrent_session_rejected", "Verify this installation again after the session conflict", 409)
            if rotate:
                if connection.execute("SELECT 1 FROM installer_ticket_uses WHERE jti=?", (claims["jti"],)).fetchone():
                    raise failure("invalid_grant", "A consumed launch ticket cannot be rotated")
                existing = connection.execute("SELECT 1 FROM active_tokens WHERE jti=?", (claims["jti"],)).fetchone()
                if existing is None:
                    record_grant(connection, token, claims)
                renewed = {**claims, "iat": now, "nbf": now - 5, "exp": now + settings.grant_ttl_seconds,
                           "jti": secrets.token_urlsafe(18)}
                result_token = signer.sign(renewed)
                record_grant(connection, result_token, renewed)
                connection.execute("UPDATE active_tokens SET status='rotated',rotated_to=?,changed_at=? WHERE jti=? AND status='active'",
                                   (renewed["jti"], now, claims["jti"]))
                connection.execute("INSERT INTO audit_log(occurred_at,event,license_uid,subject,details) VALUES(?,?,?,?,?)",
                    (now, "access_token_rotated", int(claims["sub"]), hashlib.sha256(token.encode()).hexdigest(),
                     hashlib.sha256(result_token.encode()).hexdigest()))
                claims = renewed
        result = {"status": "valid", "expires_at": datetime.fromtimestamp(claims["exp"], timezone.utc).isoformat(),
                  "expires_epoch": claims["exp"]}
        if result_token:
            result["grant"] = result_token
        return result

    @router.post("/v1/validate")
    def validate(request: Request):
        return execute(request, False)

    @router.post("/v1/rotate")
    def rotate(request: Request):
        return execute(request, True)

    return router
