"""Signed explicit TLS leaf pins. Never discovers or trusts a peer certificate."""
import time
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse
from .artifact_envelope import canonical_bytes
from .products import PRODUCTS


def create_transport_policy_router(settings, signer, apply_limit, origin):
    router = APIRouter()

    @router.get("/v1/transport-policy")
    def policy(request: Request, product: str):
        apply_limit(request, "transport-policy", 60, 60)
        nonces = request.headers.getlist("x-celeste-request-nonce")
        try:
            if product not in PRODUCTS or len(nonces) != 1:
                raise ValueError("Invalid policy request")
            canonical_bytes(nonces[0], 24)
        except (ValueError, TypeError):
            raise HTTPException(400, "invalid transport policy request") from None
        pins = settings.tls_leaf_sha256
        if (not 1 <= len(pins) <= 4 or len(set(pins)) != len(pins)
                or any(len(pin) != 64 or any(c not in "0123456789abcdef" for c in pin) for pin in pins)):
            raise HTTPException(503, "transport policy unavailable")
        now = int(time.time())
        return JSONResponse({"policy": signer.sign({"aud": "celeste-transport-policy", "product": product,
            "origin": origin, "request_nonce": nonces[0], "iat": now, "nbf": now, "exp": now + 120,
            "leaf_sha256": list(pins)})}, headers={"Cache-Control": "no-store"})

    return router
