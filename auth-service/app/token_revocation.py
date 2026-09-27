"""Operator service for revoking one signed access grant; no public revoke route."""
from __future__ import annotations

import hashlib
import time

from fastapi import HTTPException
from .auth_lifecycle import require_current_grant
from .auth_errors import failure


def require_unrevoked(connection, claims):
    require_current_grant(connection, claims)
    jti = claims.get("jti")
    if isinstance(jti, str) and connection.execute(
        "SELECT 1 FROM revoked_tokens WHERE jti=? AND expires_at>?", (jti, int(time.time()))
    ).fetchone():
        raise HTTPException(401, "token revoked")
    if claims.get("product") == "celeste_plus":
        conflict = connection.execute("SELECT conflicted_at FROM runtime_bindings WHERE device_id=?",
                                      (claims.get("device"),)).fetchone()
        if conflict is not None and conflict["conflicted_at"] is not None:
            raise failure("concurrent_session_rejected", "Verify this installation again after the session conflict", 409)


def revoke_access_token(database, signer, token: str, reason: str) -> dict:
    claims = signer.verify(token)
    if (claims is None or claims.get("aud") not in {"celeste-client", "celeste-installer", "celeste-stage-two",
                                                   "celeste-stage-three", "celeste-stage-four"}
            or not isinstance(claims.get("jti"), str)):
        raise ValueError("A valid signed access grant is required")
    reason = reason.strip()
    if not reason or len(reason) > 200:
        raise ValueError("A reason of 1 to 200 characters is required")
    now = int(time.time())
    token_hash = hashlib.sha256(token.encode("ascii")).hexdigest()
    with database.connect() as connection:
        connection.execute("BEGIN IMMEDIATE")
        connection.execute("DELETE FROM revoked_tokens WHERE expires_at<=?", (now,))
        connection.execute("""INSERT INTO revoked_tokens(jti,token_sha256,revoked_at,expires_at,reason)
            VALUES(?,?,?,?,?) ON CONFLICT(jti) DO UPDATE SET expires_at=MAX(expires_at,excluded.expires_at)""",
            (claims["jti"], token_hash, now, claims["exp"], reason))
        connection.execute("UPDATE active_tokens SET status='revoked',changed_at=? WHERE jti=?", (now, claims["jti"]))
        connection.execute("INSERT INTO audit_log(occurred_at,event,license_uid,subject,details) VALUES(?,?,?,?,?)",
                           (now, "access_token_revoked", int(claims["sub"]), token_hash, reason))
    return {"token_sha256": token_hash, "expires_at": claims["exp"], "status": "revoked"}
