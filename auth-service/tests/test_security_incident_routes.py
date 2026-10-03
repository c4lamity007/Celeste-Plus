"""Actual create_app routes: signed policy, live artifact identity and product scope."""
from contextlib import contextmanager
from dataclasses import replace
import hashlib
import secrets
import time
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.auth_lifecycle import record_grant
from app.main import create_app, _session_message
from app.security import b64e, canonical_json
from test_dpop import env, grant, headers
from test_protocol_v2 import v2, post_session, session, auth
from test_module_delivery import modules, call, authorize
from test_security_incidents import begin, confirm_body, decode, fresh, send, snapshot, wire
from test_protocol import hardware


@pytest.fixture
def configured(modules,monkeypatch):
    clock=SimpleNamespace(ms=int(time.time()*1000))
    monkeypatch.setattr(time,'time',lambda:clock.ms/1000)
    monkeypatch.setattr(time,'time_ns',lambda:clock.ms*1_000_000)
    @contextmanager
    def build(scope='celeste_plus', *, base=None, overrides=None):
        base=base or modules
        changes=dict(plus_security_incident_scope=scope,
            plus_security_incident_installations=frozenset({base.enrolled['device_id']}) if scope else frozenset(),
            plus_security_incident_releases=frozenset({base.catalog['release_id']}) if scope else frozenset())
        changes.update(overrides or {})
        config=replace(base.config,**changes)
        app=create_app(config)
        with TestClient(app,base_url='https://testserver') as client:
            delivery=SimpleNamespace(**{**vars(base),'config':config,'app':app,'client':client})
            body=session(delivery,identity=base.session_body['session_id'])
            result=post_session(delivery,body)
            assert result.status_code==200,result.text
            delivery.session_token=result.json()['session_token']
            delivery.session_body=body
            claims=app.state.signer.verify(delivery.session_token)
            request={k:claims[k] for k in ('sub','device','process_id','session_id','release','beta_catalog_sha256',
                'client_sha256','hardware_sha256','hwid_sha256','product')}
            request.update(protocol='celeste-security-incident-1',scope=scope or 'celeste_plus',phase='begin',
                incident_id=secrets.token_urlsafe(24),request_nonce=secrets.token_urlsafe(24),timestamp_ms=clock.ms,
                observation_id=secrets.token_urlsafe(24),observed_at_ms=clock.ms,challenge_id='',signal='resident_mutation',
                artifact='resident',expected_sha256=claims['client_sha256'],observed_sha256=hashlib.sha256(b'mutated').hexdigest(),
                monitor_sha256=hashlib.sha256(canonical_json({'monitors':hardware()['monitors']})).hexdigest())
            request['first_observation_id']=request['observation_id']
            yield SimpleNamespace(database=app.state.database,signer=app.state.signer,body=request,claims=claims,
                token=delivery.session_token,token_key=delivery.token,install=delivery.install,clock=clock,
                client=client,delivery=delivery,config=config)
    return build


@pytest.mark.parametrize('scope',['','celeste_plus','license'])
def test_policy_only_in_plus_signed_lease_and_no_destructive_default(configured,scope):
    with configured(scope) as e:
        assert e.claims.get('security_incident_scope')==(scope or None)
        if not scope:
            before=snapshot(e)
            assert send(e).status_code==503
            assert snapshot(e)==before
        else:
            assert begin(e)['scope']==scope


@pytest.mark.parametrize('field',['resident','module'])
def test_actual_approved_retained_digest_not_jar_digest(configured,field):
    with configured() as e:
        if field=='module':
            item=e.delivery.catalog['modules'][0]
            e.body.update(signal='module_mutation',artifact=item['artifact_id'],expected_sha256=item['sha256'])
            before=snapshot(e)
            assert send(e).status_code==401
            assert snapshot(e)==before
            e.body['expected_sha256']=item['entries_sha256']
        warning=begin(e)
        assert decode(e,send(e,confirm_body(e,warning)))['status']=='revoked'


def test_plus_block_enforced_on_all_new_auth_and_cached_module_paths_standard_survives(configured):
    with configured() as e:
        standard=grant(e.delivery,scope='auth',product='celeste')
        capability=authorize(e.delivery)['capabilities'][0]
        heartbeat=session(e.delivery,identity=e.body['session_id'])
        body=confirm_body(e,begin(e))
        assert send(e,body).status_code==200
        assert auth(e.delivery).status_code==403
        assert post_session(e.delivery,heartbeat).status_code==403
        assert call(e.delivery,'/v1/modules/catalog')[0].status_code==403
        assert call(e.delivery,capability['url'],capability=capability['capability'])[0].status_code==403
        # Standard's own authenticated manifest remains available on the same paid license.
        response=e.client.get('/v1/configs',headers=headers(e.delivery,'GET','/v1/configs',standard))
        assert response.status_code==200,response.text
        assert 'security_incident_scope' not in e.signer.verify(standard)
        assert decode(e,send(e,fresh(e,body,phase='outcome')))['status']=='revoked'


def test_unset_policy_cannot_be_enabled_by_signed_old_lease_or_request(configured):
    with configured() as enabled:
        token=enabled.token
    with configured('') as disabled:
        assert send(disabled,token=token).status_code==503


def test_missing_signed_scope_cannot_report_mutation(configured):
    with configured() as e:
        claims=dict(e.claims);claims.pop('security_incident_scope')
        e.token=e.signer.sign(claims)
        before=snapshot(e)
        assert send(e).status_code==401
        assert snapshot(e)==before


@pytest.mark.parametrize('route',['/v1/heartbeat','/v1/session/check'])
@pytest.mark.parametrize('replayed',[False,True])
def test_legacy_plus_tamper_report_cannot_bypass_warning_or_mutate_binding(configured,route,replayed):
    with configured() as e:
        body=session(e.delivery,identity=e.body['session_id'],nonce=e.delivery.session_body['nonce'] if replayed else None)
        body.update(event='integrity_mismatch',observed_sha256=hashlib.sha256(b'changed').hexdigest())
        transcript=_session_message(SimpleNamespace(**body))
        body.update(install_signature=b64e(e.install.sign(transcript)),token_signature=b64e(e.token_key.sign(transcript)))
        before=snapshot(e)
        response=e.client.post(route,json=body,headers=headers(e.delivery,'POST',route))
        assert response.status_code==401
        assert snapshot(e)==before


def test_bounded_outcome_available_after_operator_unsets_policy(configured):
    with configured() as e:
        confirm=confirm_body(e,begin(e));assert send(e,confirm).status_code==200
        app=create_app(replace(e.config,plus_security_incident_scope=''))
        with TestClient(app,base_url='https://testserver') as client:
            e.client=client
            assert decode(e,send(e,fresh(e,confirm,phase='outcome')))['status']=='revoked'


@pytest.mark.parametrize('value',['all','true','both','CELESTE_PLUS',' '])
def test_programmatic_invalid_policy_rejected_at_startup(modules,value):
    with pytest.raises(RuntimeError,match='incident scope'):
        create_app(replace(modules.config,plus_security_incident_scope=value))


def test_environment_policy_validation(monkeypatch):
    values={'CELESTE_ALLOWED_HOSTS':'testserver','CELESTE_RELEASE_ID':'release','CELESTE_CLIENT_VERSION':'1',
        'CELESTE_RELEASE_SHA256':'a'*64,'CELESTE_INSTALLER_COMPAT_SHA256':'a'*64,'CELESTE_CHUNK_A_ID':'a','CELESTE_CHUNK_B_ID':'b',
        'CELESTE_CHUNK_A_SHA256':'b'*64,'CELESTE_CHUNK_B_SHA256':'c'*64,'CELESTE_LOADER_SHA256':'d'*64,
        'CELESTE_BOOTSTRAP_SHA256':'e'*64,'CELESTE_LOADER_THREE_SHA256':'f'*64,'CELESTE_LOADER_FOUR_SHA256':'0'*64,
        'CELESTE_SIGNING_PRIVATE_KEY':b64e(bytes(32)),'CELESTE_KEY_PEPPER':b64e(bytes(32)),
        'CELESTE_SESSION_SECRET':b64e(bytes(32)),'CELESTE_ADMIN_PASSWORD_HASH':'synthetic',
        'CELESTE_PLUS_ENABLED':'true','CELESTE_PLUS_AUTH_PROTOCOL':'2',
        'CELESTE_BOOTSTRAP_ID':'boot','CELESTE_LOADER_ID':'two','CELESTE_LOADER_THREE_ID':'three','CELESTE_LOADER_FOUR_ID':'four'}
    for key,value in values.items():monkeypatch.setenv(key,value)
    monkeypatch.delenv('CELESTE_PLUS_SECURITY_INCIDENT_SCOPE',raising=False)
    for suffix in ('INSTALLATIONS','RELEASES'):
        monkeypatch.delenv('CELESTE_PLUS_SECURITY_INCIDENT_'+suffix,raising=False)
    default=Settings.from_env()
    assert default.plus_security_incident_scope==''
    assert not default.plus_security_incident_installations and not default.plus_security_incident_releases
    for value in ('celeste_plus','license'):
        monkeypatch.setenv('CELESTE_PLUS_SECURITY_INCIDENT_SCOPE',value)
        for suffix in ('INSTALLATIONS','RELEASES'):
            monkeypatch.delenv('CELESTE_PLUS_SECURITY_INCIDENT_'+suffix,raising=False)
        with pytest.raises(RuntimeError,match='explicit eligible'):Settings.from_env()
        monkeypatch.setenv('CELESTE_PLUS_SECURITY_INCIDENT_INSTALLATIONS','d'*32)
        with pytest.raises(RuntimeError,match='explicit eligible'):Settings.from_env()
        monkeypatch.setenv('CELESTE_PLUS_SECURITY_INCIDENT_RELEASES','owner-release')
        parsed=Settings.from_env()
        assert parsed.plus_security_incident_scope==value
        assert parsed.plus_security_incident_installations==frozenset({'d'*32})
        assert parsed.plus_security_incident_releases==frozenset({'owner-release'})
        monkeypatch.delenv('CELESTE_PLUS_SECURITY_INCIDENT_INSTALLATIONS')
        with pytest.raises(RuntimeError,match='explicit eligible'):Settings.from_env()
    monkeypatch.setenv('CELESTE_PLUS_SECURITY_INCIDENT_SCOPE','both')
    with pytest.raises(RuntimeError,match='SECURITY_INCIDENT_SCOPE'):Settings.from_env()


@pytest.mark.parametrize('stage',['begin','confirm'])
@pytest.mark.parametrize('revocation',['revoked-table','revoked-registration','rotated-registration'])
def test_live_registered_runtime_revocation_rejected_before_mutation(configured,stage,revocation):
    with configured() as e:
        claims={**e.claims,'jti':secrets.token_urlsafe(24),'auth_protocol':2}
        e.token=e.signer.sign(claims)
        with e.database.connect() as connection:record_grant(connection,e.token,claims)
        body=e.body if stage=='begin' else confirm_body(e,begin(e))
        with e.database.connect() as connection:
            if revocation=='revoked-table':
                connection.execute('INSERT INTO revoked_tokens VALUES(?,?,?,?,?)',
                    (claims['jti'],hashlib.sha256(e.token.encode()).hexdigest(),int(time.time()),claims['exp'],'synthetic revoke'))
            else:
                connection.execute('UPDATE active_tokens SET status=? WHERE jti=?',
                    ('revoked' if revocation=='revoked-registration' else 'rotated',claims['jti']))
        before=snapshot(e)
        assert send(e,body).status_code==401
        assert snapshot(e)==before


@pytest.mark.parametrize('confirmed',[False,True])
def test_admin_binding_reset_preserves_incident_history_and_plus_block(configured,confirmed):
    import re
    from fastapi import HTTPException
    from app.products import require_entitlement
    with configured() as e:
        warning=begin(e)
        confirmation=confirm_body(e,warning)
        if confirmed:assert send(e,confirmation).status_code==200
        with e.database.connect() as c:
            before=tuple(c.execute('SELECT * FROM security_incidents').fetchone())
        login=e.client.post('/admin/login',data={'password':'correct horse battery staple'},follow_redirects=True)
        assert login.status_code==200,login.text
        csrf=re.search(r'name="csrf" value="([^"]+)"',login.text).group(1)
        reset=e.client.post('/admin/licenses/'+e.body['sub']+'/reset',data={'csrf':csrf},follow_redirects=False)
        assert reset.status_code==303,reset.text
        with e.database.connect() as c:
            assert c.execute('SELECT COUNT(*) FROM devices WHERE license_uid=?',(int(e.body['sub']),)).fetchone()[0]==0
            assert tuple(c.execute('SELECT * FROM security_incidents').fetchone())==before
            require_entitlement(c,int(e.body['sub']),'celeste')
            if confirmed:
                with pytest.raises(HTTPException) as blocked:require_entitlement(c,int(e.body['sub']),'celeste_plus')
                assert blocked.value.status_code==403
            else:require_entitlement(c,int(e.body['sub']),'celeste_plus')
        if confirmed:
            assert decode(e,send(e,fresh(e,confirmation,phase='outcome')))['status']=='revoked'
        else:
            assert send(e,confirmation).status_code==401
