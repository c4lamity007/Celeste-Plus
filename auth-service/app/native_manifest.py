"""Signed native executable allowlists, with a separate CI publication authority.

This detects modification in the unmodified launcher. It is not remote machine
attestation: a patched process can lie about its bytes. License authorization
continues to require the independent installation proofs and live server checks.
"""
from __future__ import annotations

import hashlib
import json
import re
import time
from collections.abc import Callable

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

from .config import Settings
from .database import Database
from .security import GrantSigner, b64d, b64e, canonical_json

PRODUCTS = {"celeste", "celeste_plus"}
PLATFORMS = {"windows-x64", "linux-x64", "macos-x64", "macos-arm64"}
HEX = re.compile(r"[0-9a-f]{64}\Z")
NONCE = re.compile(r"[A-Za-z0-9_-]{32}\Z")
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}\Z")


class PublicationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    publication: str = Field(min_length=1, max_length=12_288)


def strict_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate publication field")
        value[key] = item
    return value


def decode(value: str) -> bytes:
    if not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError("invalid publication encoding")
    result = b64d(value)
    if b64e(result) != value:
        raise ValueError("noncanonical publication encoding")
    return result


def publication_claims(token: str, public_key: bytes, now: int) -> dict:
    """No untrusted token-selected algorithm or URL; only the configured CI key."""
    try:
        first, second, signature = token.split(".")
        Ed25519PublicKey.from_public_bytes(public_key).verify(
            decode(signature), (first + "." + second).encode("ascii"))
        header = json.loads(decode(first), object_pairs_hook=strict_object)
        claims = json.loads(decode(second), object_pairs_hook=strict_object)
        if header != {"alg": "EdDSA", "typ": "CST"}:
            raise ValueError("invalid publication type")
        if not isinstance(claims, dict) or set(claims) != {
            "aud", "iat", "nbf", "exp", "product", "platform", "sequence", "binaries"
        }:
            raise ValueError("invalid publication claims")
        if (claims["aud"] != "celeste-native-publication"
                or claims["product"] not in PRODUCTS or claims["platform"] not in PLATFORMS
                or any(type(claims[name]) is not int for name in ("iat", "nbf", "exp", "sequence"))
                or claims["nbf"] != claims["iat"] or not now - 300 <= claims["iat"] <= now + 5
                or not now < claims["exp"] <= claims["iat"] + 300
                or not 0 < claims["sequence"] < 2**63):
            raise ValueError("invalid publication context")
        binaries = claims["binaries"]
        if not isinstance(binaries, list) or not 1 <= len(binaries) <= 4:
            raise ValueError("invalid binary count")
        seen = set()
        for binary in binaries:
            if not isinstance(binary, dict) or set(binary) != {
                "sha256", "size", "release_id", "protection_receipt_sha256"
            }:
                raise ValueError("invalid binary metadata")
            if (not isinstance(binary["sha256"], str) or not HEX.fullmatch(binary["sha256"])
                    or binary["sha256"] in seen
                    or not isinstance(binary["protection_receipt_sha256"], str)
                    or not HEX.fullmatch(binary["protection_receipt_sha256"])
                    or not isinstance(binary["release_id"], str) or not IDENTIFIER.fullmatch(binary["release_id"])
                    or type(binary["size"]) is not int or not 0 < binary["size"] <= 256 * 1024 * 1024):
                raise ValueError("invalid binary metadata")
            seen.add(binary["sha256"])
        return claims
    except (ValueError, TypeError, KeyError, InvalidSignature, UnicodeError, OverflowError, RecursionError) as exc:
        raise HTTPException(401, "invalid native publication") from exc


def create_native_manifest_router(settings: Settings, database: Database, signer: GrantSigner,
                                  apply_limit: Callable[[Request, str, int, int], None]) -> APIRouter:
    router = APIRouter()

    @router.get("/v1/client-manifest")
    def manifest(request: Request, product: str, platform: str):
        apply_limit(request, "native-manifest", 60, 60)
        nonces = request.headers.getlist("x-celeste-request-nonce")
        if product not in PRODUCTS or platform not in PLATFORMS or len(nonces) != 1 or not NONCE.fullmatch(nonces[0]):
            raise HTTPException(400, "invalid native manifest request")
        with database.connect() as connection:
            row = connection.execute(
                "SELECT sequence,binaries_json FROM native_release_policies WHERE product=? AND platform=?",
                (product, platform),
            ).fetchone()
        if row is None:
            raise HTTPException(503, "native release is not registered")
        now = int(time.time())
        token = signer.sign({
            "aud": "celeste-native-manifest", "iat": now, "nbf": now, "exp": now + 120,
            "request_nonce": nonces[0], "product": product, "platform": platform,
            "sequence": row["sequence"], "sha256": [item["sha256"] for item in json.loads(row["binaries_json"])],
        })
        return JSONResponse({"manifest": token}, headers={"Cache-Control": "no-store"})

    @router.post("/v1/client-manifest")
    def publish(body: PublicationRequest, request: Request):
        apply_limit(request, "native-publication", 10, 60)
        if settings.native_release_public_key is None:
            raise HTTPException(503, "native publication is not configured")
        now = int(time.time())
        claims = publication_claims(body.publication, settings.native_release_public_key, now)
        digest = hashlib.sha256(body.publication.encode("ascii")).hexdigest()
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            prior = connection.execute(
                "SELECT sequence,publication_sha256 FROM native_release_policies WHERE product=? AND platform=?",
                (claims["product"], claims["platform"]),
            ).fetchone()
            if prior is not None and prior["sequence"] >= claims["sequence"]:
                # Exact retries are safe; an old signed policy cannot roll back a newer one.
                if prior["sequence"] == claims["sequence"] and prior["publication_sha256"] == digest:
                    return {"registered": True, "sequence": claims["sequence"]}
                raise HTTPException(409, "native publication sequence is stale")
            connection.execute(
                """INSERT INTO native_release_policies(product,platform,sequence,publication_sha256,binaries_json,published_at)
                   VALUES(?,?,?,?,?,?) ON CONFLICT(product,platform) DO UPDATE SET sequence=excluded.sequence,
                   publication_sha256=excluded.publication_sha256,binaries_json=excluded.binaries_json,published_at=excluded.published_at""",
                (claims["product"], claims["platform"], claims["sequence"], digest,
                 canonical_json(claims["binaries"]).decode("utf-8"), now),
            )
            connection.execute(
                "INSERT INTO audit_log(occurred_at,event,subject,details) VALUES(?,?,?,?)",
                (now, "native_release_registered", claims["product"] + ":" + claims["platform"],
                 "sequence=" + str(claims["sequence"]) + ";publication_sha256=" + digest),
            )
        return {"registered": True, "sequence": claims["sequence"]}

    return router
