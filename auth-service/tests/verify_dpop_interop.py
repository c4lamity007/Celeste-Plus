"""Offline Java/Rust synthetic-proof interoperability check against real server code.

Run from auth-service: .venv/Scripts/python.exe tests/verify_dpop_interop.py FILE
FILE is public test JSON: {public_key, token, method, url, proof, iat}.
The declared fixture timestamp freezes the clock so saved fixtures stay replayable
in this isolated in-memory database. No production state or network is used.
"""
from __future__ import annotations

import json
import sqlite3
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi import HTTPException, Request
from app.database import SCHEMA
from app.dpop import DPoPVerifier, confirmation
from app.security import b64d


def verify_fixture(fixture):
    url = urlsplit(fixture["url"])
    origin = f"{url.scheme}://{url.netloc}"
    token = fixture["token"]
    request = Request({
        "type": "http", "method": fixture["method"], "scheme": url.scheme,
        "path": url.path, "raw_path": url.path.encode("ascii"),
        "query_string": url.query.encode("ascii"), "server": (url.hostname, url.port or 443),
        "headers": [(b"dpop", fixture["proof"].encode("ascii"))],
    })
    key = b64d(fixture["public_key"], 32)
    verifier = DPoPVerifier(SimpleNamespace(public_origin=origin, allowed_hosts=(url.hostname,)))
    with sqlite3.connect(":memory:") as connection, patch("app.dpop.time.time", return_value=fixture["iat"]):
        connection.executescript(SCHEMA)
        verifier.require(connection, request, key, token=token, claims={"cnf": confirmation(key)})
        connection.commit()
        try:
            verifier.require(connection, request, key, token=token, claims={"cnf": confirmation(key)})
        except HTTPException as exc:
            if exc.status_code != 401:
                raise
        else:
            raise AssertionError("replayed proof was accepted")
    return {"first_request": "accepted", "replayed_request": "rejected"}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: verify_dpop_interop.py PUBLIC_TEST_FIXTURE.json")
    fixture = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    print(json.dumps(verify_fixture(fixture), sort_keys=True))
