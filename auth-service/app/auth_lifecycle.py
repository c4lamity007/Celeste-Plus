"""Transactional grant history and runtime identity checks; never stores bearer tokens."""
from __future__ import annotations
import hashlib
import hmac
import time
from .auth_errors import failure


def record_grant(connection, token: str, claims: dict) -> None:
    now = int(time.time())
    connection.execute("""INSERT INTO active_tokens(jti,token_sha256,license_uid,device_id,product,audience,
        issued_at,expires_at,status,changed_at) VALUES(?,?,?,?,?,?,?,?,'active',?)""",
        (claims["jti"], hashlib.sha256(token.encode("ascii")).hexdigest(), int(claims["sub"]), claims["device"],
         claims["product"], claims["aud"], claims["iat"], claims["exp"], now))


def require_current_grant(connection, claims: dict, token: str | None = None) -> None:
    row = connection.execute("SELECT * FROM active_tokens WHERE jti=?", (claims.get("jti", ""),)).fetchone()
    if row is None:
        if claims.get("auth_protocol") == 2:
            raise failure("invalid_grant", "Grant registration is unavailable")
        return  # Explicit v1 transition compatibility. No v2 grant may take this path.
    if row["status"] == "rotated":
        raise failure("token_rotated", "This grant has been replaced")
    if row["status"] != "active":
        raise failure("token_revoked", "token revoked")
    if (row["device_id"] != claims.get("device") or str(row["license_uid"]) != claims.get("sub")
            or row["product"] != claims.get("product") or row["audience"] != claims.get("aud")
            or row["expires_at"] != claims.get("exp")
            or token is not None and not hmac.compare_digest(row["token_sha256"], hashlib.sha256(token.encode("ascii")).hexdigest())):
        raise failure("invalid_grant", "Grant registration does not match")


def revoke_device_grants(connection, device_id: str, reason: str, now: int, *, product: str | None = None) -> None:
    connection.execute("""INSERT OR IGNORE INTO revoked_tokens(jti,token_sha256,revoked_at,expires_at,reason)
        SELECT jti,token_sha256,?,expires_at,? FROM active_tokens
        WHERE device_id=? AND expires_at>? AND status='active' AND (? IS NULL OR product=?)""",
                       (now, reason, device_id, now, product, product))
    connection.execute("""UPDATE active_tokens SET status='revoked',changed_at=?
        WHERE device_id=? AND status='active' AND (? IS NULL OR product=?)""", (now, device_id, product, product))


def require_hwid(row, candidate: str | None) -> None:
    stored = row["hwid_sha256"]
    if stored and (not candidate or not hmac.compare_digest(stored, candidate)):
        raise failure("device_mismatch", "This installation belongs to a different device")


def complete_hardware_auth(connection, challenge, now: int) -> None:
    """Call only AFTER both proofs and fresh DPoP have been verified in this transaction."""
    device = connection.execute("SELECT * FROM devices WHERE device_id=?", (challenge["device_id"],)).fetchone()
    if device is None:
        raise failure("device_mismatch", "This installation is no longer active")
    require_hwid(device, challenge["hwid_sha256"])
    if not hmac.compare_digest(bytes(device["hardware_core_hash"]), bytes(challenge["hardware_core_hash"])):
        raise failure("device_mismatch", "This installation belongs to a different device")
    if not device["hwid_sha256"] and challenge["hwid_sha256"]:
        connection.execute("UPDATE devices SET hwid_sha256=? WHERE device_id=? AND hwid_sha256 IS NULL",
                           (challenge["hwid_sha256"], device["device_id"]))
    connection.execute("UPDATE runtime_bindings SET conflicted_at=NULL WHERE device_id=?", (device["device_id"],))


def check_runtime_binding(connection, device, body, now: int):
    """Return a denial for the caller to raise AFTER commit, retaining conflict revocations."""
    prior = connection.execute("SELECT * FROM runtime_bindings WHERE device_id=?", (body.device_id,)).fetchone()
    matches = hmac.compare_digest(bytes(device["hardware_core_hash"]).hex(), body.hardware_sha256)
    if device["hwid_sha256"]:
        matches &= bool(body.hwid_sha256) and hmac.compare_digest(device["hwid_sha256"], body.hwid_sha256)
    elif body.hwid_sha256:
        return failure("reactivation_required", "Verify this device again before starting the runtime")
    if not matches:
        if prior is not None and prior["last_seen_at"] >= now - 3600:
            revoke_device_grants(connection, body.device_id, "concurrent hardware conflict", now, product="celeste_plus")
            # A captured completion prepared before this conflict must not clear it.
            connection.execute("UPDATE challenges SET consumed_at=? WHERE device_id=? AND product='celeste_plus' AND consumed_at IS NULL",
                               (now, body.device_id))
            connection.execute("UPDATE runtime_bindings SET conflicted_at=? WHERE device_id=?", (now, body.device_id))
            connection.execute("INSERT INTO audit_log(occurred_at,event,license_uid,subject,details) VALUES(?,?,?,?,?)",
                (now, "runtime_hardware_conflict", device["license_uid"], body.device_id, "Fresh signed hardware conflicts with recent trusted session"))
            return failure("concurrent_session_rejected", "Device session conflict; verify this installation again", 409)
        return failure("device_mismatch", "This installation belongs to a different device")
    if prior is not None and prior["conflicted_at"] is not None:
        return failure("concurrent_session_rejected", "Verify this installation again after the session conflict", 409)
    connection.execute("""INSERT INTO runtime_bindings(device_id,hardware_sha256,process_id,session_id,last_seen_at)
        VALUES(?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET hardware_sha256=excluded.hardware_sha256,
        process_id=excluded.process_id,session_id=excluded.session_id,last_seen_at=excluded.last_seen_at""",
        (body.device_id, body.hardware_sha256, body.process_id, body.session_id, now))
    return None


def eligible_rebinding(connection, license_uid: int, requested: str | None, now: int):
    rows = connection.execute("SELECT * FROM devices WHERE license_uid=? AND status='active' ORDER BY created_at,device_id",
                              (license_uid,)).fetchall()
    selected = next((row for row in rows if row["device_id"] == requested), None) if requested else (rows[0] if len(rows) == 1 else None)
    if selected is None:
        raise failure("rebind_cooldown", "Select an eligible previous installation or contact support", 409)
    activated = int(selected["created_at"])
    seen = max(activated, int(selected["last_seen_at"] or activated))
    # Strictly more than seven days, or thirty complete days without trusted activity.
    available = min(activated + 7 * 86400 + 1, seen + 30 * 86400)
    if now < available:
        raise failure("rebind_cooldown", "This installation cannot be rebound yet; contact support for an override", 409, available - now)
    reason = "seven-day cooldown" if now > activated + 7 * 86400 else "thirty-day inactivity"
    return selected, reason
