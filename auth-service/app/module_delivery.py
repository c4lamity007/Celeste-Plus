"""Lease-bound Celeste+ module delivery. No client key unlocks a release.

The operator-approved catalog fixes executable bytes; personalization adds only
container metadata and inert records. Stored content keys are KEK-wrapped and
never persisted in client responses outside the recipient-encrypted envelope.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import hmac
import json
import re
import secrets
import struct
import threading
import time
from pathlib import Path

from cryptography.exceptions import InvalidTag, InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import Response
from starlette.concurrency import run_in_threadpool

from .artifact_envelope import encrypt, encrypt_chunks, recipient, OVERHEAD_BYTES
from .artifact_transfer import ArtifactResponse, run_authorization_guard
from .auth_lifecycle import require_artifact_hardware
from .beta_delivery import beta_runtime_catalog
from .products import PLUS, require_entitlement
from .security import b64d, b64e, canonical_json
from .token_revocation import require_unrevoked
from .dpop import access_token, confirmation
from .release_assignment import selected_settings, validate_qa_assignment

SCHEMA = "celeste-module-release-1"
HOST_ABI = 1
MAX_MODULES = 512
MAX_MODULE_BYTES = 16 * 1024 * 1024
MAX_RELEASE_BYTES = 256 * 1024 * 1024
MAX_CATALOG_BYTES = 512 * 1024
MAX_REQUEST_BYTES = 16384
LEASE_SECONDS = 90
MAGIC = b"CSTMC1"
_SHA = re.compile(r"[0-9a-f]{64}\Z")
_OPAQUE = re.compile(r"[0-9a-f]{32}\Z")
_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_. -]{0,79}\Z")
_CLASS = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+\Z")
_MODULE_KEYS = {"id", "version", "artifact_id", "entrypoint", "filename", "sha256", "size", "entries_sha256"}
_SESSION_KEYS = ("sub", "device", "cnf", "product", "release", "client_version", "client_sha256",
                 "beta_catalog_sha256", "hardware_sha256", "process_id", "session_id")


def _unavailable():
    return HTTPException(503, "module release unavailable")


def _strict(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate member")
        result[key] = value
    return result


def _object(encoded: bytes):
    return json.loads(encoded, object_pairs_hook=_strict,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("invalid constant")))


def _safe_file(root: Path, name: str) -> Path:
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,120}", name):
        raise ValueError("invalid file")
    path = root / name
    if path.is_symlink() or path.resolve(strict=True).parent != root or not path.is_file() or path.stat().st_nlink != 1:
        raise ValueError("unsafe module file")
    return path


def executable_root(catalog: dict) -> str:
    value = {"schema": "celeste-module-executables-1", "product": PLUS, "release_id": catalog["release_id"],
             "host_abi": catalog["host_abi"], "modules": catalog["modules"]}
    return hashlib.sha256(canonical_json(value)).hexdigest()


def read_catalog(settings) -> tuple[dict, str, Path]:
    try:
        configured = Path(settings.module_release_dir)
        if not configured.is_absolute():
            raise ValueError("absolute module release path required")
        root = configured.resolve(strict=True)
        with _safe_file(root, "module-release.json").open("rb") as stream:
            encoded = stream.read(MAX_CATALOG_BYTES + 1)
        if len(encoded) > MAX_CATALOG_BYTES:
            raise ValueError("oversized catalog")
        catalog = _object(encoded)
        if not isinstance(catalog, dict) or set(catalog) != {
            "schema", "product", "release_id", "host_abi", "runtime_sha256", "executable_root_sha256", "modules"
        }:
            raise ValueError("invalid catalog")
        if (catalog["schema"] != SCHEMA or catalog["product"] != PLUS or type(catalog["host_abi"]) is not int
                or catalog["host_abi"] != HOST_ABI or not _ID.fullmatch(catalog["release_id"])
                or not _SHA.fullmatch(catalog["runtime_sha256"]) or not _SHA.fullmatch(catalog["executable_root_sha256"])):
            raise ValueError("invalid host identity")
        artifact_root = root / "modules"
        if artifact_root.is_symlink() or not artifact_root.is_dir() or artifact_root.resolve(strict=True).parent != root:
            raise ValueError("unsafe module artifact directory")
        modules = catalog["modules"]
        if not isinstance(modules, list) or not 1 <= len(modules) <= MAX_MODULES:
            raise ValueError("invalid module roster")
        ids, artifacts, total = set(), set(), 0
        for item in modules:
            if (not isinstance(item, dict) or set(item) != _MODULE_KEYS or not _ID.fullmatch(item["id"])
                    or not _ID.fullmatch(item["version"]) or not _OPAQUE.fullmatch(item["artifact_id"])
                    or not _CLASS.fullmatch(item["entrypoint"]) or not _SHA.fullmatch(item["sha256"])
                    or not _SHA.fullmatch(item["entries_sha256"]) or type(item["size"]) is not int
                    or not 0 < item["size"] <= MAX_MODULE_BYTES or item["id"] in ids
                    or item["artifact_id"] in artifacts or item["filename"] != item["artifact_id"] + ".jar"):
                raise ValueError("invalid module entry")
            ids.add(item["id"])
            artifacts.add(item["artifact_id"])
            total += item["size"]
            if _safe_file(artifact_root, item["filename"]).stat().st_size != item["size"]:
                raise ValueError("module size mismatch")
        if total > MAX_RELEASE_BYTES or catalog["executable_root_sha256"] != executable_root(catalog):
            raise ValueError("unapproved executable catalog")
        return catalog, hashlib.sha256(canonical_json(catalog)).hexdigest(), artifact_root
    except (OSError, ValueError, TypeError, KeyError, RecursionError) as exc:
        raise _unavailable() from exc


def require_modular_runtime(settings, runtime_catalog: dict) -> None:
    """Cutover gate: Plus bootstrap may serve only the approved resident module host.

    No request can opt out. A rollback must stage matching approved modular host
    and module catalogs; the Standard product never calls this Plus-only gate.
    """
    if not settings.module_delivery_required:
        return
    if (not settings.module_release_dir or settings.module_key_encryption_key is None
            or not settings.plus_enabled or settings.plus_auth_protocol != 2):
        raise HTTPException(503, "modular runtime unavailable")
    catalog, _, _ = read_catalog(settings)
    if (catalog["release_id"] != runtime_catalog.get("release_id")
            or catalog["runtime_sha256"] != runtime_catalog.get("runtime_sha256")):
        raise HTTPException(503, "modular runtime unavailable")


def content_aad(catalog, digest, item):
    return canonical_json({"schema": "celeste-module-aead-1", "product": PLUS,
        "release_id": catalog["release_id"], "module_id": item["id"],
        "artifact_id": item["artifact_id"], "catalog_sha256": digest})


def personalize(catalog, digest, item, approved_jar: bytes):
    """Never rewrite an executable entry. The approved JAR is one opaque record."""
    if len(approved_jar) != item["size"] or hashlib.sha256(approved_jar).hexdigest() != item["sha256"]:
        raise _unavailable()
    real_record = secrets.token_hex(16)
    metadata = {"schema": "celeste-module-container-1", "release_id": catalog["release_id"],
        "host_abi": catalog["host_abi"], "module_id": item["id"], "version": item["version"],
        "artifact_id": item["artifact_id"], "executable_sha256": item["sha256"],
        "executable_size": item["size"], "entries_sha256": item["entries_sha256"],
        "watermark": secrets.token_hex(16), "real_record": real_record, "transform_version": 1}
    records = [(bytes.fromhex(real_record), approved_jar)]
    # Framing counts towards the decoy/padding budget; fixed metadata does not.
    remaining = len(approved_jar) // 10
    decoy_bytes = 0
    for _ in range(3):
        if remaining <= 20:
            break
        count = min(4096, remaining - 20)
        record_id = secrets.token_bytes(16)
        while any(record_id == old_id for old_id, _ in records):
            record_id = secrets.token_bytes(16)
        records.append((record_id, secrets.token_bytes(count)))
        remaining -= count + 20
        decoy_bytes += count + 20
    secrets.SystemRandom().shuffle(records)
    metadata["decoy_bytes"] = decoy_bytes
    encoded = canonical_json(metadata)
    # Frame and hash the same container without copying the executable into
    # several full-size plaintext buffers. GCM writes into one ciphertext
    # allocation; the final 16-byte tag also supplies update_into's spare space.
    parts = [MAGIC, struct.pack(">I", len(encoded)), encoded, struct.pack(">I", len(records))]
    for identity, data in records:
        parts.extend((identity, struct.pack(">I", len(data)), data))
    container_size = sum(len(part) for part in parts)
    key, nonce = secrets.token_bytes(32), secrets.token_bytes(12)
    ciphertext = bytearray(12 + container_size + 16)
    ciphertext[:12] = nonce
    encryptor = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
    encryptor.authenticate_additional_data(content_aad(catalog, digest, item))
    container_hash = hashlib.sha256()
    offset = 12
    with memoryview(ciphertext) as output:
        for part in parts:
            container_hash.update(part)
            offset += encryptor.update_into(part, output[offset:])
        tail = encryptor.finalize()
        output[offset:offset + len(tail)] = tail
        offset += len(tail)
        if offset != 12 + container_size:
            raise _unavailable()
        output[offset:] = encryptor.tag
    manifest = {**metadata, "schema": "celeste-module-personalization-1",
        "container_sha256": container_hash.hexdigest(), "container_size": container_size,
        "ciphertext_sha256": hashlib.sha256(ciphertext).hexdigest(), "ciphertext_size": len(ciphertext)}
    return key, ciphertext, manifest


def require_request_evidence(request, row, claims) -> bytes:
    """Validate a fresh privacy-preserving profile and independent token-key proof.

    Installation DPoP authenticates the evidence hash separately. The token key
    countersigns that exact DPoP, including its nonce, body, recipient and token.
    """
    try:
        values = request.headers.getlist("x-celeste-module-evidence")
        if len(values) != 1 or not 1 <= len(values[0]) <= 5462:
            raise ValueError("missing module evidence")
        encoded = b64d(values[0])
        if len(encoded) > 4096 or b64e(encoded) != values[0]:
            raise ValueError("invalid evidence encoding")
        evidence = _object(encoded)
        if (not isinstance(evidence, dict) or set(evidence) != {"schema", "hardware", "hwid_sha256", "observed_sha256"}
                or evidence["schema"] != "celeste-module-evidence-1" or canonical_json(evidence) != encoded):
            raise ValueError("invalid evidence")
        hardware = evidence["hardware"]
        if not isinstance(hardware, dict) or set(hardware) != {"system", "board", "processor", "monitors"}:
            raise ValueError("incomplete hardware")
        if any(not isinstance(hardware[k], str) or not _SHA.fullmatch(hardware[k]) for k in ("system", "board", "processor")):
            raise ValueError("invalid hardware")
        monitors = hardware["monitors"]
        if (not isinstance(monitors, list) or not 1 <= len(monitors) <= 8
                or any(not isinstance(value, str) or not _SHA.fullmatch(value) for value in monitors)
                or monitors != sorted(set(monitors))):
            raise ValueError("invalid monitors")
        core = hashlib.sha256(canonical_json({k: hardware[k] for k in ("system", "board", "processor")})).digest()
        monitor = hashlib.sha256(canonical_json({"monitors": monitors})).digest()
        if (not hmac.compare_digest(core, bytes(row["hardware_core_hash"]))
                or not hmac.compare_digest(monitor, bytes(row["monitor_hash"]))
                or not row["hwid_sha256"] or claims.get("hwid_sha256") != row["hwid_sha256"]
                or evidence["hwid_sha256"] != row["hwid_sha256"]
                or evidence["observed_sha256"] != claims["client_sha256"]
                or claims.get("cnf") != confirmation(bytes(row["install_signing_key"]))):
            raise ValueError("device or runtime changed")
        proofs = request.headers.getlist("dpop")
        signatures = request.headers.getlist("x-celeste-module-token-proof")
        if len(proofs) != 1 or not 1 <= len(proofs[0]) <= 4096 or len(signatures) != 1 or len(signatures[0]) != 86:
            raise ValueError("missing token-key proof")
        signature = b64d(signatures[0], 64)
        if b64e(signature) != signatures[0]:
            raise ValueError("invalid token proof encoding")
        Ed25519PublicKey.from_public_bytes(bytes(row["token_signing_key"])).verify(
            signature, ("celeste-module-proof-v1\n" + proofs[0]).encode("ascii"))
        return encoded
    except (ValueError, TypeError, KeyError, UnicodeError, RecursionError, InvalidSignature) as exc:
        raise HTTPException(401, "invalid module device evidence") from exc


@dataclass(frozen=True)
class _VerifiedModuleRequest:
    # Immutable facts privately owned by exactly one route invocation.
    binding: tuple
    claims_json: bytes
    delivery: tuple[str, str]
    evidence: bytes
    device_keys_and_hardware: tuple


def _module_request_binding(request, body: bytes) -> tuple:
    return (request.method, request.url.path, request.scope.get("raw_path"),
            tuple(request.headers.raw), body)


def _device_proof_binding(row) -> tuple:
    fields = tuple(row[name] for name in
                   ("install_signing_key", "token_signing_key", "hardware_core_hash", "monitor_hash"))
    # SQLite is dynamically typed: do not let bytes(integer) allocate an
    # attacker-sized buffer if a stored value is corrupt. Fail closed instead.
    if any(not isinstance(value, (bytes, bytearray, memoryview))
           or (value.nbytes if isinstance(value, memoryview) else len(value)) != 32 for value in fields):
        raise HTTPException(401, "invalid module device evidence")
    return tuple(bytes(value) for value in fields) + (row["hwid_sha256"],)


def create_module_router(settings, database, signer, dpop, apply_limit):
    router = APIRouter(prefix="/v1/modules")
    qa_settings = validate_qa_assignment(settings)
    configured = bool(settings.module_release_dir or qa_settings is not None)
    if settings.module_delivery_required and (not configured or not settings.beta_release_dir):
        raise RuntimeError("Required modular delivery needs approved module and resident release directories")
    if configured != (settings.module_key_encryption_key is not None):
        raise RuntimeError("Module delivery requires both release directory and persistent key encryption key")
    if configured and (not settings.plus_enabled or settings.plus_auth_protocol != 2
                       or len(settings.module_key_encryption_key) != 32):
        raise RuntimeError("Module delivery requires Plus protocol 2 and a 32-byte key encryption key")
    if settings.module_delivery_required:
        from .beta_delivery import _read_release
        try:
            runtime, _ = _read_release(settings)
            require_modular_runtime(settings, runtime)
        except HTTPException as failure:
            raise RuntimeError("Required modular host and module release do not match") from failure
    if configured:
        with database.connect() as connection:
            connection.execute("""CREATE TABLE IF NOT EXISTS module_personalizations (
                license_uid INTEGER NOT NULL REFERENCES licenses(uid) ON DELETE CASCADE,
                catalog_sha256 TEXT NOT NULL, module_id TEXT NOT NULL,
                watermark TEXT NOT NULL, manifest_json BLOB NOT NULL,
                wrapped_key BLOB NOT NULL, ciphertext BLOB NOT NULL, created_at INTEGER NOT NULL,
                PRIMARY KEY(license_uid,catalog_sha256,module_id))""")
    # Downloads hold their permits for the entire response lifetime. Keep
    # permission issuance independent so slow transfers cannot starve live
    # activation/renewal requests until their 90-second leases expire.
    preparation_slots = threading.BoundedSemaphore(2)
    transfer_slots = threading.BoundedSemaphore(2)
    locks = [threading.Lock() for _ in range(64)]

    def enabled():
        if not configured:
            raise HTTPException(404, "not found")
        if settings.runtime_kill_switch:
            raise HTTPException(403, "runtime temporarily disabled")

    def verify_session(request, body: bytes, verification: list, *, consume: bool, check_release: bool = True):
        enabled()
        token = access_token(request)
        request_binding = _module_request_binding(request, body)
        fact = verification[0] if verification and not consume else None
        if fact is not None and fact.binding != request_binding:
            raise HTTPException(401, "module request changed")
        # Reuse cryptographic work only for this exact request. Parse immutable
        # claims anew; live database authorization is never cached.
        claims = _object(fact.claims_json) if fact is not None else signer.verify(token)
        if (not isinstance(claims, dict) or claims.get("aud") != "celeste-session" or claims.get("product") != PLUS
                or any(key not in claims for key in _SESSION_KEYS)
                or not isinstance(claims.get("sub"), str) or not re.fullmatch(r"[1-9][0-9]{0,18}", claims["sub"])
                or not isinstance(claims.get("device"), str) or not 32 <= len(claims["device"]) <= 48
                or not isinstance(claims.get("client_sha256"), str) or not _SHA.fullmatch(claims["client_sha256"])
                or not isinstance(claims.get("hwid_sha256"), str) or not _SHA.fullmatch(claims["hwid_sha256"])
                or not isinstance(claims.get("hardware_sha256"), str) or not _SHA.fullmatch(claims["hardware_sha256"])
                or type(claims.get("process_id")) is not int or claims["process_id"] <= 0
                or not isinstance(claims.get("session_id"), str) or not 16 <= len(claims["session_id"]) <= 128
                or not isinstance(claims.get("beta_catalog_sha256"), str) or not _SHA.fullmatch(claims["beta_catalog_sha256"])):
            raise HTTPException(401, "invalid runtime lease")
        delivery = fact.delivery if fact is not None else recipient(request)
        if check_release:
            runtime = beta_runtime_catalog(settings, int(claims["sub"]), claims["device"])
            if (claims.get("release") != runtime["release_id"] or claims.get("client_version") != runtime["client_version"]
                    or claims.get("client_sha256") != runtime["runtime_sha256"]
                    or claims.get("beta_catalog_sha256") != runtime["catalog_sha256"]):
                raise HTTPException(409, "unsupported runtime release")
        with database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE" if consume else "BEGIN")
            row = connection.execute("""SELECT d.*, l.status AS license_status,l.expires_at
                FROM devices d JOIN licenses l ON l.uid=d.license_uid
                WHERE d.device_id=? AND d.license_uid=?""", (claims["device"], int(claims["sub"]))).fetchone()
            now = int(time.time())
            if (row is None or row["status"] != "active" or row["license_status"] != "active"
                    or row["expires_at"] is not None and row["expires_at"] <= now or claims["exp"] <= now
                    or claims["iat"] > now + 5 or claims["nbf"] > now + 5):
                raise HTTPException(401, "inactive runtime lease")
            require_entitlement(connection, int(claims["sub"]), PLUS)
            require_unrevoked(connection, claims)
            runtime_binding = connection.execute(
                "SELECT hardware_sha256,process_id,session_id,status FROM runtime_sessions WHERE device_id=? AND process_id=? AND session_id=?",
                (claims["device"], claims["process_id"], claims["session_id"])).fetchone()
            if (runtime_binding is None or runtime_binding["status"] != "active" or any(runtime_binding[field] != claims[field]
                    for field in ("hardware_sha256", "process_id", "session_id"))):
                raise HTTPException(401, "runtime session replaced")
            hardware = require_artifact_hardware(row, request)
            proof_binding = _device_proof_binding(row)
            if fact is not None:
                if fact.device_keys_and_hardware != proof_binding:
                    raise HTTPException(401, "invalid module device evidence")
                evidence = fact.evidence
            else:
                evidence = require_request_evidence(request, row, claims)
            if (claims.get("hardware_sha256") != bytes(row["hardware_core_hash"]).hex()
                    or claims["hwid_sha256"] != hardware):
                raise HTTPException(401, "runtime hardware mismatch")
            if consume:
                dpop.require(connection, request, bytes(row["install_signing_key"]), token=token, claims=claims,
                    delivery=delivery, hardware=hardware, body_sha256=b64e(hashlib.sha256(body).digest()),
                    evidence_sha256=b64e(hashlib.sha256(evidence).digest()))
            expires = min(claims["exp"], now + LEASE_SECONDS,
                          row["expires_at"] if row["expires_at"] is not None else claims["exp"])
        if consume:
            # The replay/proof transaction must commit before publishing facts.
            verification[:] = [_VerifiedModuleRequest(request_binding, canonical_json(claims),
                               delivery, evidence, proof_binding)]
        return token, {**claims, "evidence_sha256": hashlib.sha256(evidence).hexdigest()}, delivery, expires

    def current_catalog(claims):
        selected = selected_settings(settings, claims["device"])
        if not selected.module_release_dir:
            raise HTTPException(404, "not found")
        catalog, digest, root = read_catalog(selected)
        if catalog["release_id"] != claims["release"] or catalog["runtime_sha256"] != claims["client_sha256"]:
            raise HTTPException(409, "module host mismatch")
        return catalog, digest, root

    def binding(token, claims, delivery, catalog, digest, expires, audience):
        now = int(time.time())
        if expires <= now:
            raise HTTPException(401, "expired runtime lease")
        return {**{key: claims[key] for key in _SESSION_KEYS},
            **({"hwid_sha256": claims["hwid_sha256"]} if "hwid_sha256" in claims else {}),
            "aud": audience, "iat": now, "nbf": now - 5, "exp": expires,
            "host_abi": catalog["host_abi"], "catalog_sha256": digest,
            "executable_root_sha256": catalog["executable_root_sha256"],
            "session_token_sha256": hashlib.sha256(token.encode("ascii")).hexdigest(), "request_nonce": delivery[1],
            "evidence_sha256": claims["evidence_sha256"]}

    def sealed_response(request, token, delivery, value):
        content = encrypt(canonical_json(value), token, request.url.path, *delivery)
        return Response(content, media_type="application/octet-stream", headers={"Cache-Control": "no-store"})

    def key_aad(uid, digest, module_id, manifest):
        return canonical_json({"schema": "celeste-module-key-wrap-1", "account": str(uid),
            "catalog_sha256": digest, "module_id": module_id,
            "manifest_sha256": hashlib.sha256(canonical_json(manifest)).hexdigest()})

    def cached(catalog, digest, root, item, uid, *, create):
        lock = locks[int(hashlib.sha256(f"{uid}:{digest}:{item['id']}".encode()).hexdigest()[:8], 16) % len(locks)]
        if not lock.acquire(timeout=2):
            raise HTTPException(503, "module preparation busy", headers={"Retry-After": "1"})
        try:
            def lookup(connection):
                # Renewals read bounded metadata, not megabytes of ciphertext.
                columns = "watermark,manifest_json,wrapped_key,length(ciphertext) AS stored_ciphertext_size"
                if not create:
                    columns += ",ciphertext"
                return connection.execute("SELECT " + columns + " FROM module_personalizations WHERE license_uid=? AND catalog_sha256=? AND module_id=?",
                                          (uid, digest, item["id"])).fetchone()
            with database.connect() as connection:
                row = lookup(connection)
            if row is None:
                if not create:
                    raise HTTPException(401, "module capability unavailable")
                try:
                    with _safe_file(root, item["filename"]).open("rb") as stream:
                        jar = stream.read(item["size"] + 1)
                    key, ciphertext, manifest = personalize(catalog, digest, item, jar)
                    del jar  # Only encrypted module bytes are needed while SQLite stores the record.
                    nonce = secrets.token_bytes(12)
                    wrapped = nonce + AESGCM(settings.module_key_encryption_key).encrypt(nonce, key, key_aad(uid, digest, item["id"], manifest))
                    with database.connect() as connection:
                        connection.execute("""INSERT OR IGNORE INTO module_personalizations VALUES(?,?,?,?,?,?,?,?)""",
                            (uid, digest, item["id"], manifest["watermark"], canonical_json(manifest), wrapped, ciphertext, int(time.time())))
                        row = lookup(connection)
                except (OSError, ValueError, TypeError) as exc:
                    raise _unavailable() from exc
            try:
                manifest = _object(bytes(row["manifest_json"]))
                ciphertext = None if create else bytes(row["ciphertext"])
                wrapped = bytes(row["wrapped_key"])
                key = AESGCM(settings.module_key_encryption_key).decrypt(wrapped[:12], wrapped[12:], key_aad(uid, digest, item["id"], manifest))
                if (len(key) != 32 or manifest["module_id"] != item["id"] or manifest["version"] != item["version"]
                        or manifest["artifact_id"] != item["artifact_id"] or manifest["executable_sha256"] != item["sha256"]
                        or manifest["executable_size"] != item["size"] or manifest["entries_sha256"] != item["entries_sha256"]
                        or manifest["watermark"] != row["watermark"] or manifest["release_id"] != catalog["release_id"]
                        or manifest["host_abi"] != catalog["host_abi"] or manifest["ciphertext_size"] != row["stored_ciphertext_size"]
                        or ciphertext is not None and manifest["ciphertext_sha256"] != hashlib.sha256(ciphertext).hexdigest()):
                    raise ValueError("cached container differs")
                return key, ciphertext, manifest
            except (KeyError, TypeError, ValueError, InvalidTag) as exc:
                raise _unavailable() from exc
        finally:
            lock.release()

    def checked_again(request, body, original_digest, verification, *, check_catalog=True):
        # Intermediate batch checks still validate live entitlement, revocation,
        # installation keys, hardware and session state. Re-read release files
        # at the request boundaries, not the entire module roster for each item.
        latest = verify_session(request, body, verification, consume=False, check_release=check_catalog)
        if check_catalog and current_catalog(latest[1])[1] != original_digest:
            raise HTTPException(409, "module release changed")
        return latest

    def get_catalog(request):
        enabled()
        verification = []
        apply_limit(request, "module-catalog", 120, 60)
        token, claims, delivery, expires = verify_session(request, b"", verification, consume=True)
        catalog, digest, _ = current_catalog(claims)
        token, claims, delivery, expires = checked_again(request, b"", digest, verification)
        proof = signer.sign(binding(token, claims, delivery, catalog, digest, expires, "celeste-module-catalog"))
        response = sealed_response(request, token, delivery, {"catalog": catalog, "proof": proof})
        if checked_again(request, b"", digest, verification)[3] < expires:
            raise HTTPException(401, "runtime lease changed")
        return response

    def authorize(request, body):
        enabled()
        verification = []
        apply_limit(request, "module-authorize", 240, 60)
        token, claims, delivery, expires = verify_session(request, body, verification, consume=True)
        catalog, digest, root = current_catalog(claims)
        try:
            value = _object(body)
            ids = value["modules"]
            if (set(value) != {"catalog_sha256", "modules"} or value["catalog_sha256"] != digest
                    or not isinstance(ids, list) or not 1 <= len(ids) <= len(catalog["modules"])
                    or any(not isinstance(identity, str) for identity in ids) or len(set(ids)) != len(ids)):
                raise ValueError("invalid activation batch")
            by_id = {item["id"]: item for item in catalog["modules"]}
            items = [by_id[identity] for identity in ids]
        except (KeyError, TypeError, ValueError, RecursionError) as exc:
            raise HTTPException(400, "invalid module activation request") from exc
        if not preparation_slots.acquire(timeout=2):
            raise HTTPException(503, "module preparation busy", headers={"Retry-After": "1"})
        try:
            prepared = []
            for item in items:
                # Revalidate live authority for every preparation, but avoid an
                # O(module-count squared) filesystem scan on bulk activation.
                checked_again(request, body, digest, verification, check_catalog=False)
                key, _, manifest = cached(catalog, digest, root, item, int(claims["sub"]), create=True)
                prepared.append((item, key, manifest))
            token, claims, delivery, expires = checked_again(request, body, digest, verification)
            authority = binding(token, claims, delivery, catalog, digest, expires, "celeste-module-capability")
            result = []
            for item, key, manifest in prepared:
                capability = signer.sign({**authority, "module_id": item["id"], "manifest": manifest})
                result.append({"module_id": item["id"], "capability": capability, "key": b64e(key), "manifest": manifest,
                               "url": f"/v1/modules/artifacts/{catalog['release_id']}/{item['artifact_id']}"})
            response = sealed_response(request, token, delivery, {"catalog_sha256": digest, "expires_at": expires,
                                                                  "capabilities": result})
            if checked_again(request, body, digest, verification)[3] < expires:
                raise HTTPException(401, "runtime lease changed")
            return response
        finally:
            preparation_slots.release()

    def artifact(request, release_id, artifact_id):
        enabled()
        verification = []
        apply_limit(request, "module-artifacts", 2048, 60)
        token, claims, delivery, _ = verify_session(request, b"", verification, consume=True)
        catalog, digest, root = current_catalog(claims)
        capabilities = request.headers.getlist("x-celeste-module-capability")
        capability = signer.verify(capabilities[0]) if len(capabilities) == 1 else None
        item = next((item for item in catalog["modules"] if item["artifact_id"] == artifact_id), None)
        if (item is None or release_id != catalog["release_id"] or capability is None
                or capability.get("aud") != "celeste-module-capability" or capability.get("module_id") != item["id"]
                or capability.get("catalog_sha256") != digest or capability.get("host_abi") != catalog["host_abi"]
                or capability.get("exp", 0) > claims["exp"]
                or any(capability.get(field) != claims.get(field) for field in (*_SESSION_KEYS, "hwid_sha256", "evidence_sha256"))):
            raise HTTPException(401, "invalid module capability")
        if not transfer_slots.acquire(timeout=2):
            raise HTTPException(503, "module transfer busy", headers={"Retry-After": "1"})
        try:
            checked_again(request, b"", digest, verification)
            _, ciphertext, manifest = cached(catalog, digest, root, item, int(claims["sub"]), create=False)
            if capability.get("manifest") != manifest:
                raise HTTPException(401, "module identity mismatch")
            chunks = encrypt_chunks(ciphertext, token, request.url.path, *delivery)
            checked_again(request, b"", digest, verification)
            if capability["exp"] <= int(time.time()):
                raise HTTPException(401, "expired module capability")
            def live_transfer():
                checked_again(request, b"", digest, verification)
                if capability["exp"] <= int(time.time()):
                    raise HTTPException(401, "expired module capability")

            async def guard():
                await run_authorization_guard(live_transfer)

            return ArtifactResponse(chunks, len(ciphertext) + OVERHEAD_BYTES, transfer_slots, authorization_guard=guard)
        except BaseException:
            transfer_slots.release()
            raise

    @router.get("/catalog")
    async def catalog_route(request: Request):
        return await run_in_threadpool(get_catalog, request)

    @router.post("/authorize")
    async def authorize_route(request: Request):
        body = await request.body()
        if len(body) > MAX_REQUEST_BYTES:
            raise HTTPException(413, "module request too large")
        return await run_in_threadpool(authorize, request, body)

    @router.get("/artifacts/{release_id}/{artifact_id}")
    async def artifact_route(request: Request, release_id: str, artifact_id: str):
        return await run_in_threadpool(artifact, request, release_id, artifact_id)

    return router
