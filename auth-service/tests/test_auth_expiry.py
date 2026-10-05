"""Bounded auth housekeeping and the inclusive session-nonce replay boundary."""
import sqlite3
import time

import pytest

from app.database import Database, SCHEMA, AUTH_EXPIRY_PRUNE_BATCH
from test_dpop import env
from test_protocol_v2 import v2, auth, session, post_session

TABLES = ("challenges", "session_nonces", "active_tokens")


def insert_record(c, table, identity, expiry, status="active"):
    if table == "challenges":
        c.execute("""INSERT INTO challenges(challenge_id,kind,license_uid,nonce,
            install_signing_key,hardware_core_hash,monitor_hash,release_id,
            client_sha256,created_at,expires_at) VALUES(?,'auth',1,?,?,?,?,?,?,1,?)""",
            (identity, b"n", b"i", b"h", b"m", "fixture", "a"*64, expiry))
    elif table == "session_nonces":
        c.execute("INSERT INTO session_nonces(device_id,nonce,expires_at) VALUES(?,?,?)",
                  ("fixture-device", identity, expiry))
    else:
        c.execute("""INSERT INTO active_tokens(jti,token_sha256,license_uid,device_id,
            product,audience,issued_at,expires_at,status,changed_at)
            VALUES(?,?,1,?,'celeste_plus','celeste-client',1,?,?,1)""",
            (identity, "b"*64, "fixture-device", expiry, status))


@pytest.mark.parametrize("table", TABLES)
def test_expiry_prune_is_indexed_bounded_and_keeps_boundary(table):
    with sqlite3.connect(":memory:") as c:
        c.executescript(SCHEMA)
        # This isolated row-lifetime fixture has no real accounts.
        c.execute("PRAGMA foreign_keys=OFF")
        now = 1000
        for index in range(300):
            insert_record(c, table, f"expired-{index}", now-1)
        insert_record(c, table, "boundary", now)
        insert_record(c, table, "future", now+1)
        plan = " ".join(row[3] for row in c.execute(
            f"EXPLAIN QUERY PLAN SELECT rowid FROM {table} WHERE expires_at < ? "
            "ORDER BY expires_at LIMIT ?", (now, AUTH_EXPIRY_PRUNE_BATCH)))
        assert f"idx_{table}_expiry" in plan
        assert Database.prune_expired_auth(c, table, now) == 128
        assert c.execute(f"SELECT COUNT(*) FROM {table} WHERE expires_at < ?",
                         (now,)).fetchone()[0] == 172
        assert Database.prune_expired_auth(c, table, now) == 128
        assert Database.prune_expired_auth(c, table, now) == 44
        assert Database.prune_expired_auth(c, table, now) == 0
        assert [row[0] for row in c.execute(
            f"SELECT expires_at FROM {table} ORDER BY expires_at")] == [now, now+1]
        assert Database.prune_expired_auth(c, table, now+1) == 1
        assert c.execute(f"SELECT expires_at FROM {table}").fetchone()[0] == now+1
        with pytest.raises(ValueError):
            Database.prune_expired_auth(c, "licenses", now)


@pytest.mark.parametrize("status", ["active", "revoked", "rotated"])
def test_grant_status_markers_live_through_signed_expiry(status):
    with sqlite3.connect(":memory:") as c:
        c.executescript(SCHEMA)
        insert_record(c, "active_tokens", "grant", 1000, status)
        assert Database.prune_expired_auth(c, "active_tokens", 999) == 0
        assert Database.prune_expired_auth(c, "active_tokens", 1000) == 0
        assert c.execute("SELECT status FROM active_tokens").fetchone()[0] == status
        assert Database.prune_expired_auth(c, "active_tokens", 1001) == 1


def test_existing_database_gains_expiry_indexes_idempotently(tmp_path):
    database = Database(str(tmp_path/"legacy.sqlite3"))
    database.initialize()
    with database.connect() as c:
        for table in TABLES:
            c.execute(f"DROP INDEX idx_{table}_expiry")
        insert_record(c, "active_tokens", "preserved", 1000, "revoked")
    database.initialize()
    database.initialize()
    with database.connect() as c:
        for table in TABLES:
            assert f"idx_{table}_expiry" in {
                row[1] for row in c.execute(f"PRAGMA index_list({table})")}
        assert c.execute("SELECT status FROM active_tokens WHERE jti=?",
                         ("preserved",)).fetchone()[0] == "revoked"


def test_future_timestamp_nonce_remains_consumed_through_final_valid_second(v2, monkeypatch):
    assert auth(v2).status_code == 200
    received_at = int(time.time())
    monkeypatch.setattr(time, "time", lambda: received_at+60)
    body = session(v2)
    monkeypatch.setattr(time, "time", lambda: received_at)
    assert post_session(v2, body).status_code == 200
    # Same body/token-key signature; helper signs only a fresh installation DPoP.
    for offset in (119, 120, 121):
        monkeypatch.setattr(time, "time", lambda offset=offset: received_at+offset)
        response = post_session(v2, body)
        assert response.status_code == 401, (offset, response.text)
