"""Explicitly configured Celeste+ incident reporting with fresh dual proofs.

Only a new authenticated observation after a lease-bound warning may apply the
selected policy. Diagnostic decisions never authorize delivery or gameplay.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import hashlib
import hmac
import json
import re
import secrets
import sqlite3
import time
from typing import Callable, Mapping

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from fastapi import APIRouter, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse

from .auth_lifecycle import revoke_device_grants
from .dpop import access_token, confirmation
from .products import PLUS, require_entitlement
from .security import b64d, b64e, canonical_json
from .token_revocation import require_unrevoked

PATH = "/v1/security/incidents"
MAX_BODY = 8192
WARNING_SECONDS = 5
CHALLENGE_SECONDS = 90
OUTCOME_SECONDS = 600
FRESH_SECONDS = 30
SKEW_SECONDS = 5
_HEX = re.compile(r"[0-9a-f]{64}\Z")
_OPAQUE = re.compile(r"[A-Za-z0-9_-]+\Z")
_BINDING = ("sub", "device", "process_id", "session_id", "release", "beta_catalog_sha256", "client_sha256")
_HARDWARE = ("hardware_sha256", "monitor_sha256", "hwid_sha256")
_SIGNAL = ("signal", "artifact", "expected_sha256", "observed_sha256")
_FIELDS = frozenset((*_BINDING, *_HARDWARE, *_SIGNAL, "protocol", "product", "scope", "phase", "incident_id",
                     "request_nonce", "timestamp_ms", "first_observation_id", "observation_id", "observed_at_ms", "challenge_id"))


class Scope(str, Enum):
    PLUS = "celeste_plus"
    LICENSE = "license"


class SecondSignalPolicy(str, Enum):
    SAME_SIGNAL_ARTIFACT = "same_signal_artifact"


@dataclass(frozen=True)
class Policy:
    # Both arguments are mandatory: an unset policy cannot enable punishment.
    scope: Scope
    second_signal: SecondSignalPolicy

    def __post_init__(self):
        if not isinstance(self.scope, Scope) or not isinstance(self.second_signal, SecondSignalPolicy):
            raise ValueError("An explicit reviewed incident scope and signal policy are required")


@dataclass(frozen=True)
class ApprovedRelease:
    release: str
    catalog: str
    client: str
    version: str
    # artifact -> (resident_mutation|module_mutation, approved exact retained-byte hash)
    artifacts: Mapping[str, tuple[str, str]]



def _deny(detail="invalid security incident request", code=401):
    raise HTTPException(code, detail)


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate member")
        result[key] = value
    return result


def _opaque(value, low=22, high=64):
    return isinstance(value, str) and low <= len(value) <= high and _OPAQUE.fullmatch(value)


def _hex(value):
    return isinstance(value, str) and _HEX.fullmatch(value)


def _key(value):
    # Never bytes(untrusted_int): corrupt DB types cannot allocate arbitrary memory.
    if not isinstance(value, (bytes, bytearray, memoryview)) or len(value) != 32:
        _deny()
    return bytes(value)


def _parse(raw: bytes, now_ms: int):
    try:
        if not isinstance(raw, bytes) or not 1 <= len(raw) <= MAX_BODY:
            _deny(code=413)
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_object)
        if not isinstance(value, dict) or set(value) != _FIELDS or canonical_json(value) != raw:
            _deny()
        if (value["protocol"] != "celeste-security-incident-1" or value["product"] != PLUS
                or value["phase"] not in {"begin", "confirm", "outcome"}
                or value["scope"] not in {s.value for s in Scope}
                or not isinstance(value["sub"], str) or not re.fullmatch(r"[1-9][0-9]{0,18}", value["sub"])
                or int(value["sub"]) >= 2**63 or type(value["process_id"]) is not int
                or not 0 < value["process_id"] < 2**63
                or not _opaque(value["device"], 32, 48) or not _opaque(value["session_id"], 32, 32)
                or not _opaque(value["release"], 1, 128)
                or any(not _hex(value[k]) for k in (*_HARDWARE, "beta_catalog_sha256", "client_sha256"))
                or any(not _opaque(value[k]) for k in ("incident_id", "request_nonce", "first_observation_id", "observation_id"))
                or type(value["timestamp_ms"]) is not int
                or not now_ms - FRESH_SECONDS * 1000 <= value["timestamp_ms"] <= now_ms + SKEW_SECONDS * 1000
                or type(value["observed_at_ms"]) is not int or value["observed_at_ms"] < 0):
            _deny()
        if value["phase"] == "begin":
            if value["challenge_id"] != "" or value["first_observation_id"] != value["observation_id"]:
                _deny()
        elif not _opaque(value["challenge_id"]):
            _deny()
        if value["signal"] == "self_debugger":
            if value["artifact"] != "process" or value["expected_sha256"] != "" or value["observed_sha256"] != "":
                _deny()
        elif (value["signal"] not in {"resident_mutation", "module_mutation"}
                or not _opaque(value["artifact"], 1, 128) or not _hex(value["expected_sha256"])
                or not _hex(value["observed_sha256"]) or value["expected_sha256"] == value["observed_sha256"]):
            _deny()
        return value
    except (ValueError, TypeError, UnicodeError, RecursionError):
        _deny()


def _binding(body):
    return {key: body[key] for key in (*_BINDING, *_HARDWARE, "scope")}


def _observation(body):
    return {key: body[key] for key in (*_SIGNAL, "observation_id", "observed_at_ms")}


class IncidentService:
    def __init__(self, *, database, signer, dpop, policy: Policy | None,
                 release: Callable[[int, str], ApprovedRelease], now_ms=None):
        if policy is not None and not isinstance(policy, Policy) or not callable(release):
            raise ValueError("Explicit incident policy and current approved-release resolver required")
        self.database, self.signer, self.dpop = database, signer, dpop
        self.policy, self.release = policy, release
        self.now_ms = now_ms or (lambda: time.time_ns() // 1_000_000)

    def _live(self, connection, body, token, now):
        claims = self.signer.verify(token)
        if (not isinstance(claims, dict) or claims.get("aud") != "celeste-session"
                or claims.get("product") != PLUS or claims.get("status") != "active"
                or claims.get("security_incident_scope") != body["scope"]
                or any(claims.get(k) != body[k] for k in _BINDING)
                or any(claims.get(k) != body[k] for k in ("hardware_sha256", "hwid_sha256"))):
            _deny("invalid runtime lease")
        row = connection.execute("""SELECT d.*,l.status AS license_status,l.expires_at
            FROM devices d JOIN licenses l ON l.uid=d.license_uid
            WHERE d.device_id=? AND d.license_uid=?""", (body["device"], int(body["sub"]))).fetchone()
        if (row is None or row["status"] != "active" or row["license_status"] != "active"
                or row["expires_at"] is not None and row["expires_at"] <= now):
            _deny("inactive installation")
        require_entitlement(connection, int(body["sub"]), PLUS)
        require_unrevoked(connection, claims)
        install, token_key = _key(row["install_signing_key"]), _key(row["token_signing_key"])
        if (claims.get("cnf") != confirmation(install)
                or _key(row["hardware_core_hash"]).hex() != body["hardware_sha256"]
                or _key(row["monitor_hash"]).hex() != body["monitor_sha256"]
                or row["hwid_sha256"] != body["hwid_sha256"]):
            _deny("device evidence changed")
        session = connection.execute("""SELECT * FROM runtime_sessions
            WHERE device_id=? AND process_id=? AND session_id=?""",
            (body["device"], body["process_id"], body["session_id"])).fetchone()
        conflict = connection.execute("SELECT conflicted_at FROM runtime_bindings WHERE device_id=?",
                                      (body["device"],)).fetchone()
        if (session is None or session["status"] != "active" or session["hardware_sha256"] != body["hardware_sha256"]
                or session["last_seen_at"] < now - CHALLENGE_SECONDS
                or conflict is None or conflict["conflicted_at"] is not None):
            _deny("inactive runtime session")
        approved = self.release(int(body["sub"]), body["device"])
        if (not isinstance(approved, ApprovedRelease) or approved.release != body["release"]
                or approved.catalog != body["beta_catalog_sha256"] or approved.client != body["client_sha256"]
                or approved.version != claims.get("client_version")):
            _deny("unsupported runtime release", 409)
        if body["signal"] != "self_debugger" and approved.artifacts.get(body["artifact"]) != (
                body["signal"], body["expected_sha256"]):
            _deny("unknown approved artifact")
        lease_expiry = min(claims["exp"], row["expires_at"] if row["expires_at"] is not None else claims["exp"])
        return install, token_key, claims, lease_expiry

    def _proof(self, connection, request, raw, body, token, install, token_key, claims, now):
        signatures = request.headers.getlist("x-celeste-incident-token-proof")
        proofs = request.headers.getlist("dpop")
        try:
            if len(signatures) != 1 or len(signatures[0]) != 86 or len(proofs) != 1 or not 1 <= len(proofs[0]) <= 4096:
                _deny()
            signature = b64d(signatures[0], 64)
            if b64e(signature) != signatures[0]:
                _deny()
            Ed25519PublicKey.from_public_bytes(token_key).verify(signature,
                ("celeste-incident-proof-v1\n" + proofs[0]).encode("ascii"))
        except (ValueError, TypeError, UnicodeError, InvalidSignature):
            _deny()
        # Both signatures, exact body/context, live state (for mutations), and
        # freshness pass before any incident row or punitive state can change.
        self.dpop.require(connection, request, install, token=token, claims=claims,
                          hardware=body["hwid_sha256"], body_sha256=b64e(hashlib.sha256(raw).digest()))
        if connection.execute("SELECT 1 FROM security_incident_nonces WHERE device=? AND nonce=?",
                              (body["device"], body["request_nonce"])).fetchone():
            _deny("replayed security incident request")
        if connection.execute("""SELECT COUNT(*) FROM (SELECT 1 FROM security_incident_nonces
            WHERE device=? AND expires_at>=? LIMIT 60)""", (body["device"], now)).fetchone()[0] >= 60:
            _deny("security incident proof limit", 429)
        connection.execute("INSERT INTO security_incident_nonces VALUES(?,?,?)",
                           (body["device"], body["request_nonce"], now + FRESH_SECONDS + SKEW_SECONDS + 1))

    def handle(self, request, raw: bytes):
        now_ms = self.now_ms()
        now = now_ms // 1000
        body = _parse(raw, now_ms)
        if body["phase"] != "outcome" and self.policy is None:
            _deny("security incident reporting is not configured", 503)
        if body["phase"] != "outcome" and body["scope"] != self.policy.scope.value:
            _deny("incident policy scope mismatch", 409)
        token = access_token(request)
        with self.database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            # SQLite may wait for another writer. Authorization clocks and the
            # full warning interval start after ownership of this transaction.
            now_ms = self.now_ms()
            now = now_ms // 1000
            if not now_ms - FRESH_SECONDS * 1000 <= body["timestamp_ms"] <= now_ms + SKEW_SECONDS * 1000:
                _deny("stale security incident request")
            row = connection.execute("SELECT * FROM security_incidents WHERE device=? AND incident_id=?",
                                     (body["device"], body["incident_id"])).fetchone()
            if body["phase"] == "outcome":
                result = self._outcome(connection, request, raw, body, token, row, now)
            else:
                install, token_key, claims, lease_expiry = self._live(connection, body, token, now)
                self._proof(connection, request, raw, body, token, install, token_key, claims, now)
                if body["phase"] == "begin":
                    result = self._begin(connection, body, row, install, token_key, now_ms, lease_expiry)
                else:
                    result = self._confirm(connection, body, row, install, token_key, now_ms)
            # Bounded pruning only after authentication and a valid operation.
            for table, field in (("security_incident_nonces", "expires_at"),
                                 ("security_incident_observations", "expires_at"),
                                 ("security_incidents", "outcome_expires_at")):
                connection.execute(f"DELETE FROM {table} WHERE rowid IN (SELECT rowid FROM {table} "
                                   f"WHERE {field}<? ORDER BY {field} LIMIT 128)", (now,))
        return result

    def _fresh_observation(self, body, now_ms):
        if not now_ms - FRESH_SECONDS * 1000 <= body["observed_at_ms"] <= min(
                now_ms + SKEW_SECONDS * 1000, body["timestamp_ms"]):
            _deny("a fresh observation is required")

    def _record_observation(self, connection, body, expires):
        try:
            connection.execute("INSERT INTO security_incident_observations VALUES(?,?,?,?,?)",
                (body["device"], body["observation_id"], body["incident_id"], body["phase"], expires))
        except sqlite3.IntegrityError:
            _deny("observation already used")

    def _begin(self, connection, body, row, install, token_key, now_ms, lease_expiry):
        now = now_ms // 1000
        if row is not None:
            self._same_binding(body, row, install, token_key)
            if (row["confirmed_at"] is not None or row["expires_at"] <= now
                    or bytes(row["first_json"]) != canonical_json(_observation(body))):
                _deny("incident already exists", 409)
            return self._decision(body, row, "warning", issued=row["created_ms"] // 1000)
        self._fresh_observation(body, now_ms)
        if connection.execute("""SELECT 1 FROM security_incidents WHERE device=? AND process_id=? AND session_id=?
            AND confirmed_at IS NULL AND expires_at>?""",
            (body["device"], body["process_id"], body["session_id"], now)).fetchone():
            _deny("a warning is already pending for this session", 409)
        if connection.execute("SELECT COUNT(*) FROM security_incidents WHERE license_uid=? AND created_ms>?",
                              (int(body["sub"]), now_ms - 60000)).fetchone()[0] >= 6:
            _deny("security incident reporting limit", 429)
        # Round upward: a truncated wall second must never shorten the 5 s wait.
        confirm_after = (now_ms + WARNING_SECONDS * 1000 + 999) // 1000
        expires, outcome_expires = min(now + CHALLENGE_SECONDS, lease_expiry), now + OUTCOME_SECONDS
        if expires <= confirm_after:
            _deny("refresh the runtime lease before beginning a warning", 409)
        self._record_observation(connection, body, outcome_expires)
        connection.execute("""INSERT INTO security_incidents(device,incident_id,license_uid,process_id,session_id,scope,
            binding_json,first_json,install_key,token_key,challenge_id,created_ms,confirm_after,expires_at,outcome_expires_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (body["device"], body["incident_id"], int(body["sub"]), body["process_id"], body["session_id"], body["scope"],
             canonical_json(_binding(body)), canonical_json(_observation(body)), install, token_key,
             secrets.token_urlsafe(24), now_ms, confirm_after, expires, outcome_expires))
        row = connection.execute("SELECT * FROM security_incidents WHERE device=? AND incident_id=?",
                                 (body["device"], body["incident_id"])).fetchone()
        return self._decision(body, row, "warning", issued=now)

    def _same_binding(self, body, row, install=None, token_key=None):
        if (row is None or bytes(row["binding_json"]) != canonical_json(_binding(body))
                or json.loads(row["first_json"])["observation_id"] != body["first_observation_id"]
                or install is not None and not hmac.compare_digest(install, _key(row["install_key"]))
                or token_key is not None and not hmac.compare_digest(token_key, _key(row["token_key"]))):
            _deny("incident context changed")

    def _confirm(self, connection, body, row, install, token_key, now_ms):
        now = now_ms // 1000
        self._same_binding(body, row, install, token_key)
        if (row["challenge_id"] != body["challenge_id"] or row["expires_at"] <= now
                or row["confirmed_at"] is not None):
            _deny("invalid or completed warning; query outcome", 409)
        if now_ms < row["confirm_after"] * 1000:
            _deny("warning interval has not elapsed", 409)
        self._fresh_observation(body, now_ms)
        first = json.loads(row["first_json"])
        if (body["observation_id"] == first["observation_id"]
                or body["observed_at_ms"] < row["confirm_after"] * 1000
                or any(body[k] != first[k] for k in ("signal", "artifact", "expected_sha256"))):
            _deny("a new matching observation after the warning is required")
        self._record_observation(connection, body, row["outcome_expires_at"])
        self._apply_policy(connection, body, now)
        connection.execute("UPDATE security_incidents SET confirm_json=?,confirmed_at=? WHERE device=? AND incident_id=?",
            (canonical_json(_observation(body)), now, body["device"], body["incident_id"]))
        return self._decision(body, row, "revoked", issued=now)

    def _apply_policy(self, connection, body, now):
        uid = int(body["sub"])
        reason = "confirmed_security_incident:" + body["incident_id"]
        # Capture the authenticated scope, never allow a hot policy replacement
        # to turn a request approved for Plus into a whole-license mutation.
        scope = Scope(body["scope"])
        if self.policy is None or scope is not self.policy.scope:
            _deny("incident policy changed", 409)
        if scope is Scope.PLUS:
            connection.execute("INSERT INTO security_product_blocks VALUES(?,?,?,?)",
                               (uid, PLUS, body["incident_id"], now))
            product = PLUS
        else:
            connection.execute("UPDATE licenses SET status='revoked',revoked_at=?,revoked_reason=? WHERE uid=?",
                               (now, reason, uid))
            product = None
        # Affect every installation of the explicitly selected account/product,
        # not just the reporting device, while leaving other accounts unchanged.
        devices = connection.execute("SELECT device_id FROM devices WHERE license_uid=?", (uid,)).fetchall()
        for device in devices:
            revoke_device_grants(connection, device[0], reason, now, product=product)
        connection.execute("""UPDATE challenges SET consumed_at=? WHERE license_uid=? AND consumed_at IS NULL
            AND (? IS NULL OR product=?)""", (now, uid, product, product))
        connection.execute("INSERT INTO audit_log(occurred_at,event,license_uid,subject,details) VALUES(?,?,?,?,?)",
            (now, "security_incident_confirmed", uid, body["incident_id"], canonical_json({
                "scope": scope.value, "signal": body["signal"], "artifact": body["artifact"]}).decode()))

    def _outcome(self, connection, request, raw, body, token, row, now):
        self._same_binding(body, row)
        if row["outcome_expires_at"] <= now or row["challenge_id"] != body["challenge_id"]:
            _deny("incident outcome unavailable")
        if now < row["confirm_after"]:
            _deny("warning interval has not elapsed", 409)
        install, token_key = _key(row["install_key"]), _key(row["token_key"])
        # This dedicated operation is proof-of-possession of the ORIGINAL public
        # keys only. It does not accept a bearer grant as authorization, validate
        # or renew a revoked/expired lease, or expose anything beyond this incident.
        self._proof(connection, request, raw, body, token, install, token_key, {"cnf": confirmation(install)}, now)
        observation = _observation(body)
        if row["confirmed_at"] is None:
            # A pending query references the proposed confirmation, but never
            # submits it as a second event. Atomically cancel that exact attempt
            # before saying 'pending', so a delayed original confirm cannot
            # commit after the client has begun a different fresh attempt.
            first = json.loads(row["first_json"])
            if (observation["observation_id"] == first["observation_id"]
                    or observation["observed_at_ms"] < row["confirm_after"] * 1000
                    or any(observation[k] != first[k] for k in ("signal", "artifact", "expected_sha256"))):
                _deny("outcome evidence context changed")
            previous = connection.execute("SELECT incident_id,phase FROM security_incident_observations WHERE device=? AND observation_id=?",
                                          (body["device"], body["observation_id"])).fetchone()
            if previous is not None:
                if previous["incident_id"] != body["incident_id"] or previous["phase"] != "cancelled":
                    _deny("outcome attempt context changed")
            else:
                connection.execute("INSERT INTO security_incident_observations VALUES(?,?,?,?,?)",
                    (body["device"], body["observation_id"], body["incident_id"], "cancelled", row["outcome_expires_at"]))
            return self._decision(body, row, "pending", issued=now)
        if canonical_json(observation) != bytes(row["confirm_json"]):
            _deny("outcome does not match the committed confirmation")
        return self._decision(body, row, "revoked", issued=now)

    def _decision(self, body, row, status, *, issued):
        expires = min(issued + CHALLENGE_SECONDS, row["expires_at"] if status == "warning" else row["outcome_expires_at"])
        return {"decision": self.signer.sign({
            "aud": "celeste-security-incident", "product": PLUS,
            **{key: body[key] for key in (*_BINDING, *_SIGNAL, "scope", "incident_id", "request_nonce", "phase",
                                         "first_observation_id", "observation_id", "observed_at_ms")},
            "iat": issued, "nbf": issued, "exp": expires, "challenge_id": row["challenge_id"],
            "confirm_after": row["confirm_after"], "status": status})}


def create_router(service: IncidentService, apply_limit=None):
    """Use with the existing bounded/deadlined body middleware."""
    router = APIRouter()

    @router.post(PATH)
    async def incident(request: Request):
        if apply_limit is not None:
            await run_in_threadpool(apply_limit, request, "security-incidents", 120, 60)
        body = bytearray()
        async for chunk in request.stream():
            if len(chunk) > MAX_BODY - len(body):
                _deny(code=413)
            body.extend(chunk)
        raw = bytes(body)
        body.clear()
        result = await run_in_threadpool(service.handle, request, raw)
        return JSONResponse(result, headers={"Cache-Control": "no-store"})

    return router


def create_incident_router(settings, database, signer, dpop, apply_limit):
    """Unset policy denies mutations; existing outcomes remain narrowly readable."""
    settings.validate_incident_policy()
    scope = settings.plus_security_incident_scope
    policy = Policy(Scope(scope), SecondSignalPolicy.SAME_SIGNAL_ARTIFACT) if scope else None

    def approved_release(uid, device):
        from .beta_delivery import beta_runtime_catalog
        from .module_delivery import read_catalog
        from .release_assignment import selected_settings
        if settings.runtime_kill_switch:
            _deny("runtime temporarily disabled", 403)
        runtime = beta_runtime_catalog(settings, uid, device)
        # Invoked inside _live for EACH begin/confirm after its transaction wait
        # and authenticated installation checks. Old signed claims cannot bypass
        # a removed installation/release. Outcomes use stored bindings separately.
        if settings.incident_scope_for(device, runtime["release_id"]) != scope or not scope:
            _deny("installation or release is not eligible for security incidents", 403)
        artifacts = {"resident": ("resident_mutation", runtime["runtime_sha256"])}
        selected = selected_settings(settings, device)
        if selected.module_release_dir:
            catalog, _, _ = read_catalog(selected)
            if (catalog["release_id"] != runtime["release_id"]
                    or catalog["runtime_sha256"] != runtime["runtime_sha256"]):
                _deny("module host mismatch", 409)
            # entries_sha256 uses the exact retained-entry framing verified by
            # ModuleMemoryClassLoader; sha256 is the DIFFERENT transport JAR hash.
            artifacts.update({item["artifact_id"]: ("module_mutation", item["entries_sha256"])
                              for item in catalog["modules"]})
        return ApprovedRelease(runtime["release_id"], runtime["catalog_sha256"], runtime["runtime_sha256"],
                               runtime["client_version"], artifacts)

    service = IncidentService(database=database, signer=signer, dpop=dpop, policy=policy, release=approved_release)
    return create_router(service, apply_limit)
