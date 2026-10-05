"""Server-owned product entitlement rules; installation certificates only identify devices."""
from __future__ import annotations

import json
import secrets
import time
from fastapi import HTTPException

from .security import hash_license

CELESTE = "celeste"
PLUS = "celeste_plus"
PRODUCTS = frozenset({CELESTE, PLUS})
PRODUCT_LABELS = {CELESTE: "Celeste", PLUS: "Celeste+", "both": "Both"}
DURATIONS = {"day": 86400, "month": 30 * 86400, "three_months": 90 * 86400,
             "year": 365 * 86400, "lifetime": None}


def selected_products(selection: str) -> tuple[str, ...]:
    if selection == "both":
        return (CELESTE, PLUS)
    if selection in PRODUCTS:
        return (selection,)
    raise ValueError("invalid license product")


def require_entitlement(connection, license_uid: int, product: str) -> None:
    if product not in PRODUCTS:
        raise HTTPException(403, "invalid product")
    if connection.execute("SELECT 1 FROM license_entitlements WHERE license_uid=? AND product=?",
                          (license_uid, product)).fetchone() is None:
        raise HTTPException(403, f"license does not include {PRODUCT_LABELS[product]}")


def require_claim_product(claims: dict, expected: str) -> None:
    if claims.get("product") != expected:
        raise HTTPException(401, "grant is for a different product")


def issue_license(connection, pepper: bytes, selection: str, label: str = "",
                  duration: str = "lifetime", max_devices: int = 1) -> tuple[int, str]:
    products = selected_products(selection)
    if duration not in DURATIONS or type(max_devices) is not int or not 1 <= max_devices <= 8:
        raise ValueError("invalid license duration or device limit")
    if len(label) > 80:
        raise ValueError("license label is too long")
    raw = secrets.token_hex(20).upper()
    prefix = {CELESTE: "CST", PLUS: "CSP", "both": "CSB"}[selection]
    display = prefix + "-" + "-".join(raw[index:index + 5] for index in range(0, len(raw), 5))
    now = int(time.time())
    cursor = connection.execute(
        """INSERT INTO licenses(key_hash,key_prefix,label,status,max_devices,created_at,duration_kind,duration_seconds)
           VALUES(?,?,?,'active',?,?,?,?)""",
        (hash_license(pepper, display), display[:11], label.strip(), max_devices, now, duration, DURATIONS[duration]))
    uid = int(cursor.lastrowid)
    connection.executemany("INSERT INTO license_entitlements(license_uid,product,created_at) VALUES(?,?,?)",
                           [(uid, product, now) for product in products])
    connection.execute("INSERT INTO audit_log(occurred_at,event,license_uid,subject,details) VALUES(?,?,?,?,?)",
                       (now, "license_created", uid, label.strip(), json.dumps(
                           {"products": products, "duration": duration, "max_devices": max_devices}, separators=(",", ":"))))
    return uid, display
