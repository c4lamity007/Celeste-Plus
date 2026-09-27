"""Stable API error codes without disclosing customer or hardware identifiers."""
from fastapi import HTTPException


CODES = frozenset({
    "device_mismatch", "subscription_inactive", "token_revoked", "token_rotated",
    "concurrent_session_rejected", "reactivation_required", "rebind_cooldown",
    "invalid_delivery_key", "invalid_grant", "runtime_disabled",
})


def failure(code: str, message: str, status: int = 401, retry_after: int = 300) -> HTTPException:
    if code not in CODES or type(retry_after) is not int or retry_after < 0:
        raise ValueError("Invalid authentication error configuration")
    return HTTPException(status, {"error_code": code, "message": message,
                                  "retry_after_seconds": retry_after})
