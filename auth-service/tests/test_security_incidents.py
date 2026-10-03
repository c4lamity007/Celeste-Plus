"""Synthetic HTTP/Ed25519/DPoP/SQLite protocol; no real accounts or deployment."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import hashlib
import json
import secrets
import time
from types import SimpleNamespace

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient

from app.database import Database
from app.dpop import DPoPVerifier, confirmation
from app.products import require_entitlement
from app.security import GrantSigner, b64e, b64d, canonical_json
from dpop_client import proof
from app.security_incidents import (ApprovedRelease, IncidentService, PATH, Policy, Scope, SecondSignalPolicy,
                                create_router)
from app.products import require_product_not_blocked


def public(key):
    return key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)


@pytest.fixture
def env(tmp_path, monkeypatch):
    clock = SimpleNamespace(ms=1_800_000_000_123)
    monkeypatch.setattr(time, 'time', lambda: clock.ms / 1000)
    database = Database(str(tmp_path / 'synthetic.sqlite'))
    database.initialize()
    install, token_key = Ed25519PrivateKey.generate(), Ed25519PrivateKey.generate()
    signer = GrantSigner(secrets.token_bytes(32))
    device = 'd' * 32
    now = clock.ms // 1000
    with database.connect() as c:
        for uid in (1, 2):
            c.execute("INSERT INTO licenses(uid,key_hash,status,created_at) VALUES(?,?,'active',?)", (uid, bytes([uid]) * 32, now))
            for product in ('celeste', 'celeste_plus'):
                c.execute('INSERT INTO license_entitlements VALUES(?,?,?)', (uid, product, now))
        for name, uid in ((device, 1), ('e' * 32, 1), ('f' * 32, 2)):
            c.execute("""INSERT INTO devices(device_id,license_uid,install_signing_key,token_signing_key,
                hardware_core_hash,monitor_hash,status,created_at,hwid_sha256) VALUES(?,?,?,?,?,?,'active',?,?)""",
                (name, uid, public(install) if name == device else secrets.token_bytes(32), public(token_key),
                 bytes.fromhex('1'*64), bytes.fromhex('2'*64), now, '3'*64))
            for product in ('celeste', 'celeste_plus'):
                jti = name + product
                c.execute("""INSERT INTO active_tokens VALUES(?,?,?,?,?,?,?,?,'active',NULL,?)""",
                    (jti, '9'*64, uid, name, product, 'celeste-client', now, now+600, now))
        c.execute("INSERT INTO runtime_sessions VALUES(?,?,?,?,?,'active')", (device, 1234, 's'*32, '1'*64, now))
        c.execute("INSERT INTO runtime_bindings VALUES(?,?,?,?,?,NULL)", (device, '1'*64, 1234, 's'*32, now))
    body = {'protocol':'celeste-security-incident-1', 'product':'celeste_plus', 'scope':'celeste_plus',
            'sub':'1', 'device':device, 'process_id':1234, 'session_id':'s'*32, 'release':'synthetic-release',
            'beta_catalog_sha256':'4'*64, 'client_sha256':'5'*64, 'hardware_sha256':'1'*64,
            'monitor_sha256':'2'*64, 'hwid_sha256':'3'*64, 'phase':'begin', 'incident_id':secrets.token_urlsafe(24),
            'request_nonce':secrets.token_urlsafe(24), 'timestamp_ms':clock.ms,
            'observation_id':secrets.token_urlsafe(24), 'observed_at_ms':clock.ms, 'challenge_id':'',
            'signal':'resident_mutation', 'artifact':'resident', 'expected_sha256':'6'*64, 'observed_sha256':'7'*64}
    body['first_observation_id'] = body['observation_id']
    claims = {k:body[k] for k in ('sub','device','process_id','session_id','release','beta_catalog_sha256','client_sha256',
                                 'hardware_sha256','hwid_sha256','product')}
    claims.update(aud='celeste-session', status='active', security_incident_scope='celeste_plus', cnf=confirmation(public(install)), iat=now, nbf=now-5,
                  exp=now+90, nonce=secrets.token_urlsafe(24), client_version='1')
    approved = ApprovedRelease(body['release'], body['beta_catalog_sha256'], body['client_sha256'], '1',
                               {'resident':('resident_mutation','6'*64),'module-A':('module_mutation','8'*64)})
    e = SimpleNamespace(database=database, install=install, token_key=token_key, signer=signer, clock=clock,
                        body=body, claims=claims, token=signer.sign(claims), approved=approved)
    e.service = IncidentService(database=database, signer=signer,
        dpop=DPoPVerifier(SimpleNamespace(public_origin='https://testserver', allowed_hosts=['testserver'])),
        policy=Policy(Scope.PLUS, SecondSignalPolicy.SAME_SIGNAL_ARTIFACT), release=lambda uid,dev:e.approved,
        now_ms=lambda:clock.ms)
    app = FastAPI()
    app.include_router(create_router(e.service))
    with TestClient(app, base_url='https://testserver') as client:
        e.client = client
        yield e


def wire(e, body=None, *, token=None, install=None, token_key=None, extensions=None, raw=None):
    body = body or e.body
    raw = raw if raw is not None else canonical_json(body)
    token = token if token is not None else e.token
    dpop = proof(install or e.install, 'POST', 'https://testserver'+PATH, token,
                 payload_updates={'bht':b64e(hashlib.sha256(raw).digest()), 'hwid':body['hwid_sha256'], **(extensions or {})})
    return raw, {'Authorization':'DPoP '+token, 'DPoP':dpop, 'Content-Type':'application/json',
                 'X-Celeste-Incident-Token-Proof':b64e((token_key or e.token_key).sign(('celeste-incident-proof-v1\n'+dpop).encode()))}


def send(e, body=None, **kwargs):
    raw, headers = wire(e, body, **kwargs)
    return e.client.post(PATH, content=raw, headers=headers)


def decode(e, response):
    assert response.status_code == 200, response.text
    compact = response.json()['decision']
    first, second, signature = compact.split('.')
    e.signer._private.public_key().verify(b64d(signature), (first+'.'+second).encode())
    assert json.loads(b64d(first)) == {'alg':'EdDSA','kid':'celeste-release-1','typ':'CST'}
    return json.loads(b64d(second))


def fresh(e, body, **updates):
    return {**body, 'request_nonce':secrets.token_urlsafe(24), 'timestamp_ms':e.clock.ms, **updates}


def begin(e):
    return decode(e, send(e))


def confirm_body(e, warning):
    e.clock.ms = warning['confirm_after'] * 1000
    return fresh(e, e.body, phase='confirm', challenge_id=warning['challenge_id'],
                 observation_id=secrets.token_urlsafe(24), observed_at_ms=e.clock.ms)


def snapshot(e):
    with e.database.connect() as c:
        tables = ['licenses','devices','active_tokens','revoked_tokens','challenges','audit_log','security_product_blocks',
                  'security_incidents','security_incident_nonces','security_incident_observations','dpop_replays']
        return {table:[tuple(row) for row in c.execute('SELECT * FROM '+table+' ORDER BY rowid')] for table in tables}


def test_no_default_policy_and_no_implicit_schema():
    with pytest.raises(TypeError): Policy()
    with pytest.raises(ValueError): Policy('license', SecondSignalPolicy.SAME_SIGNAL_ARTIFACT)
    with pytest.raises(ValueError): Policy(Scope.PLUS, None)


def test_full_plus_flow_and_standard_unchanged(env):
    e=env
    before=snapshot(e)
    warning=begin(e)
    assert warning['status']=='warning' and warning['confirm_after']*1000-e.clock.ms>=5000
    assert warning['first_observation_id']==e.body['observation_id']
    for table in ('licenses','devices','active_tokens','revoked_tokens','security_product_blocks'):
        assert snapshot(e)[table]==before[table]
    body=confirm_body(e,warning)
    decision=decode(e,send(e,body))
    assert decision['status']=='revoked' and decision['scope']=='celeste_plus'
    assert decision['observation_id']==body['observation_id'] and decision['first_observation_id']==e.body['observation_id']
    with e.database.connect() as c:
        assert c.execute("SELECT status FROM licenses WHERE uid=1").fetchone()[0]=='active'
        assert c.execute("SELECT COUNT(*) FROM devices WHERE status='active'").fetchone()[0]==3
        assert c.execute("SELECT COUNT(*) FROM active_tokens WHERE product='celeste_plus' AND license_uid=1 AND status='revoked'").fetchone()[0]==2
        assert c.execute("SELECT COUNT(*) FROM active_tokens WHERE product='celeste' AND status='active'").fetchone()[0]==3
        assert c.execute("SELECT COUNT(*) FROM active_tokens WHERE license_uid=2 AND status='active'").fetchone()[0]==2
        assert c.execute('SELECT COUNT(*) FROM license_entitlements').fetchone()[0]==4
        require_entitlement(c,1,'celeste')
        require_product_not_blocked(c,1,'celeste')
        with pytest.raises(HTTPException): require_product_not_blocked(c,1,'celeste_plus')
        assert c.execute('SELECT COUNT(*) FROM audit_log').fetchone()[0]==1


def test_explicit_whole_license_scope_only(env):
    e=env
    e.service.policy=Policy(Scope.LICENSE,SecondSignalPolicy.SAME_SIGNAL_ARTIFACT)
    before=snapshot(e)
    assert send(e).status_code==409
    assert snapshot(e)==before
    e.body['scope']='license'
    e.token=e.signer.sign({**e.claims,'security_incident_scope':'license'})
    warning=begin(e)
    assert decode(e,send(e,confirm_body(e,warning)))['scope']=='license'
    with e.database.connect() as c:
        assert c.execute('SELECT status FROM licenses WHERE uid=1').fetchone()[0]=='revoked'
        assert c.execute("SELECT COUNT(*) FROM active_tokens WHERE license_uid=1 AND status='revoked'").fetchone()[0]==4
        assert c.execute("SELECT COUNT(*) FROM active_tokens WHERE license_uid=2 AND status='active'").fetchone()[0]==2
        assert c.execute('SELECT COUNT(*) FROM security_product_blocks').fetchone()[0]==0


@pytest.mark.parametrize('kind',['dpop','nonce','observation'])
def test_replay_rejected_before_any_mutation(env,kind):
    e=env
    raw,headers=wire(e)
    warning=decode(e,e.client.post(PATH,content=raw,headers=headers))
    before=snapshot(e)
    if kind=='dpop': response=e.client.post(PATH,content=raw,headers=headers)
    elif kind=='nonce': response=send(e)
    else:
        body=confirm_body(e,warning)
        body['observation_id']=e.body['observation_id']
        response=send(e,body)
    assert response.status_code==401
    assert snapshot(e)==before


@pytest.mark.parametrize('case',['early','old-observation','future','same-observation','wrong-challenge','wrong-first','changed-signal','changed-artifact','changed-expected'])
def test_invalid_confirm_no_business_or_replay_mutation(env,case):
    e=env; warning=begin(e); body=confirm_body(e,warning)
    if case=='early': e.clock.ms-=1; body['timestamp_ms']=body['observed_at_ms']=e.clock.ms
    elif case=='old-observation': body['observed_at_ms']=e.body['observed_at_ms']
    elif case=='future': body['observed_at_ms']=e.clock.ms+6000
    elif case=='same-observation': body['observation_id']=e.body['observation_id']
    elif case=='wrong-challenge': body['challenge_id']='c'*32
    elif case=='wrong-first': body['first_observation_id']='o'*32
    elif case=='changed-signal': body.update(signal='self_debugger',artifact='process',expected_sha256='',observed_sha256='')
    elif case=='changed-artifact': body.update(signal='module_mutation',artifact='module-A',expected_sha256='8'*64)
    elif case=='changed-expected': body['expected_sha256']='9'*64
    before=snapshot(e)
    assert send(e,body).status_code in (401,409)
    assert snapshot(e)==before


def test_new_matching_observation_can_have_changed_digest(env):
    e=env; body=confirm_body(e,begin(e)); body['observed_sha256']='a'*64
    assert decode(e,send(e,body))['status']=='revoked'


@pytest.mark.parametrize('case',['install','token','body','dpop-body','dpop-hwid','expired-token','standard-token','wrong-audience'])
def test_proof_bound_and_plus_only(env,case):
    e=env; options={}; body=dict(e.body)
    if case=='install': options['install']=Ed25519PrivateKey.generate()
    elif case=='token': options['token_key']=Ed25519PrivateKey.generate()
    elif case=='dpop-body': options['extensions']={'bht':'bad'}
    elif case=='dpop-hwid': options['extensions']={'hwid':'0'*64}
    elif case=='expired-token': options['token']=e.signer.sign({**e.claims,'exp':e.clock.ms//1000})
    elif case=='standard-token': options['token']=e.signer.sign({**e.claims,'product':'celeste'})
    elif case=='wrong-audience': options['token']=e.signer.sign({**e.claims,'aud':'celeste-installation'})
    before=snapshot(e)
    raw,headers=wire(e,body,**options)
    if case=='body': raw=raw.replace(b'"observed_sha256":"777',b'"observed_sha256":"aaa')
    assert e.client.post(PATH,content=raw,headers=headers).status_code==401
    assert snapshot(e)==before


@pytest.mark.parametrize('case',['device','license','entitlement','product-block','monitor','hwid','core','install-key','token-key','corrupt-key','session-retired','session-stale','session-hardware','conflict','release'])
def test_live_state_rechecked_at_confirmation(env,case):
    e=env; body=confirm_body(e,begin(e))
    with e.database.connect() as c:
        if case=='device': c.execute("UPDATE devices SET status='revoked' WHERE device_id=?",(e.body['device'],))
        elif case=='license': c.execute("UPDATE licenses SET status='revoked' WHERE uid=1")
        elif case=='entitlement': c.execute("DELETE FROM license_entitlements WHERE license_uid=1 AND product='celeste_plus'")
        elif case=='product-block': c.execute("INSERT INTO security_product_blocks VALUES(1,'celeste_plus','operator-block',?)",(e.clock.ms//1000,))
        elif case in ('monitor','core','install-key','token-key','corrupt-key'):
            column={'monitor':'monitor_hash','core':'hardware_core_hash','install-key':'install_signing_key','token-key':'token_signing_key','corrupt-key':'token_signing_key'}[case]
            c.execute('UPDATE devices SET '+column+'=? WHERE device_id=?',(123456789 if case=='corrupt-key' else b'x'*32,e.body['device']))
        elif case=='hwid': c.execute("UPDATE devices SET hwid_sha256=? WHERE device_id=?",('0'*64,e.body['device']))
        elif case=='session-retired': c.execute("UPDATE runtime_sessions SET status='retired'")
        elif case=='session-stale': c.execute("UPDATE runtime_sessions SET last_seen_at=0")
        elif case=='session-hardware': c.execute("UPDATE runtime_sessions SET hardware_sha256=?",('0'*64,))
        elif case=='conflict': c.execute("UPDATE runtime_bindings SET conflicted_at=1")
        elif case=='release': e.approved=replace(e.approved,release='new-release')
    before=snapshot(e)
    assert send(e,body).status_code in (401,403,409)
    assert snapshot(e)==before


def test_begin_response_loss_preserves_original_clock_and_challenge(env):
    e=env; warning=begin(e); e.clock.ms+=2000
    retry=decode(e,send(e,fresh(e,e.body)))
    for key in ('challenge_id','confirm_after','iat','exp','first_observation_id'):
        assert retry[key]==warning[key]
    assert retry['request_nonce']!=warning['request_nonce']
    with e.database.connect() as c:
        assert c.execute('SELECT COUNT(*) FROM security_incidents').fetchone()[0]==1


def test_lost_confirmation_outcome_read_after_revocation_and_expired_lease(env):
    e=env; warning=begin(e); body=confirm_body(e,warning)
    assert decode(e,send(e,body))['status']=='revoked'
    e.clock.ms+=100000
    outcome=fresh(e,body,phase='outcome')
    before=snapshot(e)
    decision=decode(e,send(e,outcome))
    assert decision['phase']=='outcome' and decision['status']=='revoked'
    assert decision['observation_id']==body['observation_id']
    assert decision['observed_at_ms']==body['observed_at_ms']
    after=snapshot(e)
    for table in ('licenses','devices','active_tokens','revoked_tokens','audit_log','security_product_blocks','security_incidents'):
        assert before[table]==after[table]
    assert decode(e,send(e,fresh(e,outcome)))['status']=='revoked'
    assert send(e,fresh(e,body)).status_code==401  # Mutation path never gets an expired-lease exception.


def test_outcome_does_not_submit_new_confirmation(env):
    e=env; body=confirm_body(e,begin(e)); before=snapshot(e)
    decision=decode(e,send(e,fresh(e,body,phase='outcome')))
    assert decision['status']=='pending'
    after=snapshot(e)
    for table in ('licenses','active_tokens','audit_log','security_product_blocks','security_incidents'):
        assert before[table]==after[table]
    with e.database.connect() as c:
        assert c.execute('SELECT phase FROM security_incident_observations WHERE observation_id=?',(body['observation_id'],)).fetchone()[0]=='cancelled'


@pytest.mark.parametrize('case',['changed-id','changed-digest','wrong-first','wrong-key','wrong-context','expired-outcome','replay'])
def test_outcome_is_exact_bounded_and_replay_protected(env,case):
    e=env; body=confirm_body(e,begin(e)); send(e,body)
    outcome=fresh(e,body,phase='outcome'); options={}
    if case=='changed-id': outcome['observation_id']='x'*32
    elif case=='changed-digest': outcome['observed_sha256']='a'*64
    elif case=='wrong-first': outcome['first_observation_id']='f'*32
    elif case=='wrong-key': options['token_key']=Ed25519PrivateKey.generate()
    elif case=='wrong-context': outcome['session_id']='s'*31+'x'
    elif case=='expired-outcome': e.clock.ms+=600000; outcome=fresh(e,outcome)
    elif case=='replay': assert send(e,outcome).status_code==200
    before=snapshot(e)
    assert send(e,outcome,**options).status_code==401
    assert snapshot(e)==before


def test_concurrent_confirmation_mutates_exactly_once(env):
    e=env; body=confirm_body(e,begin(e))
    requests=[wire(e,fresh(e,body)) for _ in range(2)]
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses=list(pool.map(lambda pair:e.client.post(PATH,content=pair[0],headers=pair[1]),requests))
    assert sorted(r.status_code for r in responses)==[200,403]
    with e.database.connect() as c:
        assert c.execute('SELECT COUNT(*) FROM audit_log').fetchone()[0]==1
        assert c.execute('SELECT COUNT(*) FROM security_product_blocks').fetchone()[0]==1


@pytest.mark.parametrize('case',['unknown','noncanonical','duplicate','oversized','boolean-pid','unknown-signal','unapproved-artifact','equal-hashes'])
def test_strict_body_and_typed_positive_signals(env,case):
    e=env; body=dict(e.body); raw=None
    if case=='unknown': body['extension']='no'
    elif case=='noncanonical': raw=json.dumps(body).encode()
    elif case=='duplicate': raw=canonical_json(body)[:-1]+b',"product":"celeste_plus"}'
    elif case=='oversized': raw=b'x'*8193
    elif case=='boolean-pid': body['process_id']=True
    elif case=='unknown-signal': body['signal']='timeout'
    elif case=='unapproved-artifact': body['artifact']='not-approved'
    elif case=='equal-hashes': body['observed_sha256']=body['expected_sha256']
    before=snapshot(e)
    assert send(e,body,raw=raw).status_code in (401,413)
    assert snapshot(e)==before


def test_self_debugger_signal_has_no_process_inventory(env):
    e=env; e.body.update(signal='self_debugger',artifact='process',expected_sha256='',observed_sha256='')
    warning=begin(e)
    assert decode(e,send(e,confirm_body(e,warning)))['status']=='revoked'


def test_warning_expires_without_punishment(env):
    e=env; warning=begin(e); body=confirm_body(e,warning)
    e.clock.ms=(warning['exp']+1)*1000
    body=fresh(e,body,observed_at_ms=e.clock.ms)
    e.token=e.signer.sign({**e.claims,'iat':e.clock.ms//1000,'nbf':e.clock.ms//1000,'exp':e.clock.ms//1000+90})
    with e.database.connect() as c:
        c.execute('UPDATE runtime_sessions SET last_seen_at=?',(e.clock.ms//1000,))
    before=snapshot(e)
    assert send(e,body).status_code==409
    assert snapshot(e)==before


def test_schema_migration_idempotent_and_pruning_is_bounded(env):
    e=env; before=snapshot(e); e.database.initialize(); assert snapshot(e)==before
    with e.database.connect() as c:
        c.executemany('INSERT INTO security_incident_nonces VALUES(?,?,?)',[(e.body['device'],str(i),0) for i in range(300)])
        plan=' '.join(str(tuple(r)) for r in c.execute('EXPLAIN QUERY PLAN SELECT rowid FROM security_incident_nonces WHERE expires_at<? ORDER BY expires_at LIMIT 128',(1,)))
        assert 'idx_incident_nonce_expiry' in plan
    begin(e)
    with e.database.connect() as c:
        assert c.execute('SELECT COUNT(*) FROM security_incident_nonces WHERE expires_at=0').fetchone()[0]==172


def test_device_rate_limit_rolls_back_proof_and_never_revokes(env):
    e=env
    with e.database.connect() as c:
        c.executemany('INSERT INTO security_incident_nonces VALUES(?,?,?)',
                      [(e.body['device'],str(i),e.clock.ms//1000+36) for i in range(60)])
    before=snapshot(e)
    assert send(e).status_code==429
    assert snapshot(e)==before


@pytest.mark.parametrize('scope',[Scope.PLUS,Scope.LICENSE])
def test_outcome_after_selected_revocation_scope_is_idempotent(env,scope):
    e=env; e.service.policy=Policy(scope,SecondSignalPolicy.SAME_SIGNAL_ARTIFACT); e.body['scope']=scope.value
    e.token=e.signer.sign({**e.claims,'security_incident_scope':scope.value})
    body=confirm_body(e,begin(e)); send(e,body)
    before=snapshot(e)
    result=decode(e,send(e,fresh(e,body,phase='outcome')))
    assert result['status']=='revoked' and result['scope']==scope.value
    after=snapshot(e)
    for table in ('licenses','active_tokens','security_product_blocks','audit_log'):
        assert before[table]==after[table]


@pytest.mark.parametrize('field,value',[('sub','2'),('device','e'*32),('process_id',2345),('session_id','z'*32),
    ('release','another-release'),('beta_catalog_sha256','b'*64),('client_sha256','c'*64),('scope','license')])
def test_exact_request_context_cannot_cross_account_or_runtime(env,field,value):
    e=env; body=dict(e.body); body[field]=value; before=snapshot(e)
    assert send(e,body).status_code in (401,409)
    assert snapshot(e)==before


def test_module_artifact_requires_approved_exact_identity(env):
    e=env; e.body.update(signal='module_mutation',artifact='module-A',expected_sha256='8'*64)
    assert decode(e,send(e,confirm_body(e,begin(e))))['status']=='revoked'


def test_warning_is_bounded_by_original_lease_not_refreshed_lease(env):
    e=env; now=e.clock.ms//1000
    e.token=e.signer.sign({**e.claims,'exp':now+12})
    warning=begin(e)
    assert warning['exp']==now+12
    e.clock.ms=(now+13)*1000
    e.token=e.signer.sign({**e.claims,'iat':now+13,'exp':now+90})
    body=fresh(e,e.body,phase='confirm',challenge_id=warning['challenge_id'],
               observation_id=secrets.token_urlsafe(24),observed_at_ms=e.clock.ms)
    before=snapshot(e)
    assert send(e,body).status_code==409
    assert snapshot(e)==before


def test_short_lease_cannot_start_unfinishable_warning(env):
    e=env; e.token=e.signer.sign({**e.claims,'exp':e.clock.ms//1000+5}); before=snapshot(e)
    assert send(e).status_code==409
    assert snapshot(e)==before


def test_pending_outcome_tombstone_blocks_delayed_confirm_then_allows_fresh_sample(env):
    e=env; body=confirm_body(e,begin(e))
    delayed=wire(e,body)
    assert decode(e,send(e,fresh(e,body,phase='outcome')))['status']=='pending'
    before=snapshot(e)
    assert e.client.post(PATH,content=delayed[0],headers=delayed[1]).status_code==401
    assert snapshot(e)==before
    retry=fresh(e,body,observation_id=secrets.token_urlsafe(24),observed_at_ms=e.clock.ms)
    assert decode(e,send(e,retry))['status']=='revoked'


def test_concurrent_outcome_confirm_is_serialized_into_one_authoritative_result(env):
    e=env; body=confirm_body(e,begin(e))
    pairs=[wire(e,body),wire(e,fresh(e,body,phase='outcome'))]
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses=list(pool.map(lambda pair:e.client.post(PATH,content=pair[0],headers=pair[1]),pairs))
    outcome=decode(e,responses[1])
    with e.database.connect() as c:
        audits=c.execute('SELECT COUNT(*) FROM audit_log').fetchone()[0]
    if outcome['status']=='pending':
        assert responses[0].status_code==401 and audits==0
    else:
        assert outcome['status']=='revoked' and responses[0].status_code==200 and audits==1


def test_committed_outcome_survives_later_operator_scope_change(env):
    e=env; body=confirm_body(e,begin(e)); send(e,body)
    e.service.policy=Policy(Scope.LICENSE,SecondSignalPolicy.SAME_SIGNAL_ARTIFACT)
    result=decode(e,send(e,fresh(e,body,phase='outcome')))
    assert result['status']=='revoked' and result['scope']=='celeste_plus'
    with e.database.connect() as c:
        assert c.execute('SELECT status FROM licenses WHERE uid=1').fetchone()[0]=='active'


def test_policy_replacement_during_confirmation_cannot_escalate_scope(env):
    e=env; body=confirm_body(e,begin(e))
    def replace_policy(uid,device):
        e.service.policy=Policy(Scope.LICENSE,SecondSignalPolicy.SAME_SIGNAL_ARTIFACT)
        return e.approved
    e.service.release=replace_policy
    before=snapshot(e)
    assert send(e,body).status_code==409
    assert snapshot(e)==before



def advance_after_write_lock(e, monkeypatch, milliseconds):
    """Deterministically model SQLite writer wait without sleeping or real accounts."""
    from contextlib import contextmanager
    connect = e.database.connect
    pending = [milliseconds]
    class DelayedConnection:
        def __init__(self, connection): self.connection = connection
        def execute(self, sql, *args):
            result = self.connection.execute(sql, *args)
            if sql == "BEGIN IMMEDIATE" and pending:
                e.clock.ms += pending.pop()
            return result
        def __getattr__(self, name): return getattr(self.connection, name)
    @contextmanager
    def delayed():
        with connect() as connection:
            yield DelayedConnection(connection)
    monkeypatch.setattr(e.database, 'connect', delayed)


def test_warning_interval_starts_after_waiting_for_write_lock(env, monkeypatch):
    e = env
    advance_after_write_lock(e, monkeypatch, 4900)
    warning = begin(e)
    assert warning['confirm_after'] * 1000 - e.clock.ms >= 5000
    early = confirm_body(e, warning)
    e.clock.ms -= 1
    early['timestamp_ms'] = early['observed_at_ms'] = e.clock.ms
    before = snapshot(e)
    assert send(e, early).status_code == 409
    assert snapshot(e) == before


def test_request_freshness_rechecked_after_waiting_for_write_lock(env, monkeypatch):
    e = env
    e.body['timestamp_ms'] -= 29000
    e.body['observed_at_ms'] -= 29000
    before = snapshot(e)
    advance_after_write_lock(e, monkeypatch, 4000)
    assert send(e).status_code == 401
    assert snapshot(e) == before


def test_license_expiry_rechecked_after_waiting_for_write_lock(env, monkeypatch):
    e = env
    body = confirm_body(e, begin(e))
    with e.database.connect() as connection:
        connection.execute('UPDATE licenses SET expires_at=? WHERE uid=1', (e.clock.ms // 1000 + 2,))
    before = snapshot(e)
    advance_after_write_lock(e, monkeypatch, 4000)
    assert send(e, body).status_code == 401
    assert snapshot(e) == before


def test_original_warning_expiry_rechecked_after_waiting_for_write_lock(env, monkeypatch):
    e = env
    warning = begin(e)
    e.clock.ms = (warning['exp'] - 1) * 1000
    e.token = e.signer.sign({**e.claims, 'iat': e.clock.ms // 1000, 'nbf': e.clock.ms // 1000,
                             'exp': e.clock.ms // 1000 + 90})
    with e.database.connect() as connection:
        connection.execute('UPDATE runtime_sessions SET last_seen_at=?', (e.clock.ms // 1000,))
    body = fresh(e, e.body, phase='confirm', challenge_id=warning['challenge_id'],
                 observation_id=secrets.token_urlsafe(24), observed_at_ms=e.clock.ms)
    before = snapshot(e)
    advance_after_write_lock(e, monkeypatch, 4000)
    assert send(e, body).status_code == 409
    assert snapshot(e) == before
