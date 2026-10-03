"""Disposable migration/reset fixtures; no live license changes."""
import sqlite3
import pytest

from app.database import Database, SCHEMA
from app.license_reset import preview, reset_with_backup
from test_license_reset import catalog


def legacy_schema():
    return SCHEMA.replace("license_uid INTEGER NOT NULL REFERENCES licenses(uid) ON DELETE CASCADE,\n product TEXT NOT NULL CHECK(product='celeste_plus')",
                          "license_uid INTEGER NOT NULL REFERENCES licenses(uid),\n product TEXT NOT NULL CHECK(product='celeste_plus')").replace(
        " device TEXT NOT NULL, incident_id TEXT NOT NULL,\n license_uid INTEGER NOT NULL, process_id INTEGER NOT NULL,",
        " device TEXT NOT NULL REFERENCES devices(device_id), incident_id TEXT NOT NULL,\n license_uid INTEGER NOT NULL REFERENCES licenses(uid), process_id INTEGER NOT NULL,")


def install_legacy_incident_tables(database):
    # Keep every unrelated migration current, changing only the two prior tables.
    database.initialize()
    legacy=legacy_schema()
    with database.connect() as c:
        for name in ('security_product_blocks','security_incidents'):
            c.execute('DROP TABLE '+name)
            start=legacy.index('CREATE TABLE IF NOT EXISTS '+name+' (')
            c.execute(legacy[start:legacy.index(';',start)+1])
        for statement in legacy.split(';'):
            if statement.strip().startswith('CREATE INDEX IF NOT EXISTS idx_incident_'):
                c.execute(statement)


def seed_incident(connection, device, uid):
    connection.execute("""INSERT INTO security_incidents(device,incident_id,license_uid,process_id,session_id,scope,
        binding_json,first_json,confirm_json,install_key,token_key,challenge_id,created_ms,confirm_after,expires_at,
        outcome_expires_at,confirmed_at) VALUES(?,'synthetic-incident',?,1,'synthetic-session','celeste_plus',
        ?,?,?,?,?,'synthetic-challenge',1,6,90,600,7)""", (device,uid,b'{}',b'{}',b'{}',bytes(32),bytes(32)))
    connection.execute("INSERT INTO security_product_blocks VALUES(?,'celeste_plus','synthetic-incident',7)", (uid,))


def test_legacy_migration_preserves_rows_indexes_and_is_idempotent(tmp_path):
    database = Database(str(tmp_path/'legacy.sqlite'))
    install_legacy_incident_tables(database)
    with database.connect() as c:
        c.execute("INSERT INTO licenses(uid,key_hash,status,created_at) VALUES(1,?,'active',1)", (bytes(32),))
        c.execute("""INSERT INTO devices(device_id,license_uid,install_signing_key,token_signing_key,
            hardware_core_hash,monitor_hash,status,created_at) VALUES('device',1,?,?,?,?,'active',1)""", [bytes(32)]*4)
        seed_incident(c, 'device', 1)
        before = {name:[tuple(r) for r in c.execute('SELECT * FROM '+name)]
                  for name in ('security_incidents','security_product_blocks')}
    database.initialize();database.initialize()
    with database.connect() as c:
        assert {name:[tuple(r) for r in c.execute('SELECT * FROM '+name)] for name in before} == before
        assert not c.execute('PRAGMA foreign_key_list(security_incidents)').fetchall()
        keys=c.execute('PRAGMA foreign_key_list(security_product_blocks)').fetchall()
        assert len(keys)==1 and keys[0]['table']=='licenses' and keys[0]['on_delete']=='CASCADE'
        assert len(c.execute("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='security_incidents' AND sql IS NOT NULL").fetchall())==3
        assert c.execute('PRAGMA foreign_key_check').fetchone() is None
        c.execute("DELETE FROM devices WHERE device_id='device'")
        assert c.execute('SELECT COUNT(*) FROM security_incidents').fetchone()[0]==1
        assert c.execute('SELECT COUNT(*) FROM security_product_blocks').fetchone()[0]==1
        with pytest.raises(sqlite3.IntegrityError):seed_incident(c, 'device', 1)


@pytest.mark.parametrize('custom',['trigger','index','foreign-reference','column'])
def test_unknown_legacy_schema_is_not_rebuilt_and_prior_table_change_rolls_back(tmp_path,custom):
    database = Database(str(tmp_path/'custom.sqlite'))
    install_legacy_incident_tables(database)
    with database.connect() as c:
        if custom=='trigger':c.execute("CREATE TRIGGER preserve_incident AFTER DELETE ON security_incidents BEGIN SELECT 1; END")
        elif custom=='index':c.execute('CREATE INDEX custom_incident ON security_incidents(challenge_id,scope)')
        elif custom=='foreign-reference':c.execute('CREATE TABLE external_reference(challenge TEXT REFERENCES security_incidents(challenge_id))')
        else:c.execute('ALTER TABLE security_incidents ADD COLUMN custom TEXT')
        before=[tuple(r) for r in c.execute("SELECT type,name,sql FROM sqlite_master ORDER BY type,name")]
    with pytest.raises(RuntimeError,match='incident identity|Incident identity'):
        database.initialize()
    with database.connect() as c:
        after=[tuple(r) for r in c.execute("SELECT type,name,sql FROM sqlite_master ORDER BY type,name")]
        assert after==before


def test_explicit_full_catalog_reset_preserves_incident_history_and_backup_blocks(catalog,tmp_path):
    database,_=catalog
    with database.connect() as c:
        seed_incident(c,'fixture-device',73)
        before=preview(c)
        retained=tuple(c.execute('SELECT * FROM security_incidents').fetchone())
    backup=tmp_path/'incident-backup.sqlite'
    result=reset_with_backup(database.path,str(backup),before['plan_sha256'])
    assert result['result']=='reset'
    with database.connect() as c:
        assert not c.execute('SELECT * FROM licenses').fetchall()
        assert not c.execute('SELECT * FROM security_product_blocks').fetchall()
        assert tuple(c.execute('SELECT * FROM security_incidents').fetchone())==retained
        assert c.execute('PRAGMA foreign_key_check').fetchone() is None
    with sqlite3.connect(backup) as c:
        assert c.execute('SELECT COUNT(*) FROM security_product_blocks').fetchone()[0]==1
        assert c.execute('SELECT COUNT(*) FROM security_incidents').fetchone()[0]==1
