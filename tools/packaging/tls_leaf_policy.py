"""Prepare and validate explicit TLS leaf pins; observation never changes production."""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import socket
import ssl
import subprocess

from cryptography import x509

ORIGIN = "https://qb0f90cf11f4d1d52.fly.dev"
HOST = "qb0f90cf11f4d1d52.fly.dev"
APP = "qb0f90cf11f4d1d52"
MAX_RECORD = 128 * 1024


def certificate(der: bytes, *, now=None) -> dict:
    now = now or datetime.now(timezone.utc)
    value = x509.load_der_x509_certificate(der)
    if value.not_valid_before_utc > now or value.not_valid_after_utc <= now + timedelta(days=7):
        raise ValueError("Leaf certificate is not current or has less than seven days remaining")
    names = value.extensions.get_extension_for_class(x509.SubjectAlternativeName).value.get_values_for_type(x509.DNSName)
    if HOST not in names and "*.fly.dev" not in names:
        raise ValueError("Certificate does not identify the configured API hostname")
    return {"sha256": hashlib.sha256(der).hexdigest(),
            "not_before": value.not_valid_before_utc.isoformat(), "not_after": value.not_valid_after_utc.isoformat(),
            "subject": value.subject.rfc4514_string(), "issuer": value.issuer.rfc4514_string(),
            "der_base64": base64.b64encode(der).decode("ascii")}


def observe() -> dict:
    context = ssl.create_default_context()
    certificates = {}
    errors = []
    addresses = list(dict.fromkeys(item[4][0] for item in socket.getaddrinfo(HOST, 443, type=socket.SOCK_STREAM)))
    for address in addresses[:8]:
        try:
            with socket.create_connection((address, 443), timeout=10) as raw:
                with context.wrap_socket(raw, server_hostname=HOST) as peer:
                    entry = certificate(peer.getpeercert(binary_form=True))
            certificates[entry["sha256"]] = entry
        except OSError:
            errors.append(address)
    if not certificates or len(certificates) > 4:
        raise ValueError("Could not observe a bounded set of valid production certificates")
    return {"schema": "celeste-tls-leaf-review-1", "origin": ORIGIN,
            "observed_utc": datetime.now(timezone.utc).isoformat(),
            "certificates": list(certificates.values()), "unreachable_addresses": errors,
            "status": "candidate-requires-review", "production_changed": False}


def reviewed(path: Path, expected: str, *, now=None) -> list[str]:
    with path.open("rb") as stream:
        raw = stream.read(MAX_RECORD + 1)
    if len(raw) > MAX_RECORD or hashlib.sha256(raw).hexdigest() != expected:
        raise ValueError("Pin review does not match its explicitly reviewed SHA-256")
    record = json.loads(raw)
    now = now or datetime.now(timezone.utc)
    observed = datetime.fromisoformat(record["observed_utc"])
    if (record.get("schema") != "celeste-tls-leaf-review-1" or record.get("origin") != ORIGIN
            or observed.tzinfo is None or not now - timedelta(hours=24) <= observed <= now + timedelta(minutes=1)):
        raise ValueError("Pin review is stale or belongs to another origin")
    entries = record.get("certificates")
    if not isinstance(entries, list) or not 1 <= len(entries) <= 4:
        raise ValueError("Pin policy requires one to four reviewed certificates")
    pins = []
    for entry in entries:
        verified = certificate(base64.b64decode(entry["der_base64"], validate=True), now=now)
        if entry != verified or verified["sha256"] in pins:
            raise ValueError("Certificate evidence is inconsistent or duplicated")
        pins.append(verified["sha256"])
    return pins


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    capture = commands.add_parser("observe", help="Read PKIX-verified current leaf certificates; no credentials or mutation")
    capture.add_argument("--output", type=Path, required=True)
    for name in ("export", "stage-fly"):
        command = commands.add_parser(name)
        command.add_argument("--review", type=Path, required=True)
        command.add_argument("--review-sha256", required=True)
        if name == "stage-fly":
            command.add_argument("--fly", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "observe":
            value = observe()
            with args.output.open("x", encoding="utf-8") as stream:
                json.dump(value, stream, indent=2)
                stream.write("\n")
            print(json.dumps({"review": str(args.output), "review_sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
                              "pins": [item["sha256"] for item in value["certificates"]], "production_changed": False}))
        else:
            pins = reviewed(args.review, args.review_sha256)
            setting = "CELESTE_TLS_LEAF_SHA256=" + ",".join(pins)
            if args.command == "export":
                print(setting)
            else:
                # Staging does not replace the current machines; deploy with the matched client/server release.
                result = subprocess.run([str(args.fly.resolve(strict=True)), "secrets", "import", "--stage", "--app", APP],
                                        input=setting + "\n", text=True, capture_output=True, timeout=40, check=False)
                if result.returncode:
                    raise ValueError("Fly rejected the staged transport policy; no diagnostics containing credentials are exposed")
                print(json.dumps({"status": "staged", "app": APP, "pins": pins, "deployment_required": True}))
    except (ValueError, KeyError, TypeError, OSError, subprocess.TimeoutExpired) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
