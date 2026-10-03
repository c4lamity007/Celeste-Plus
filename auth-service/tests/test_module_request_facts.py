"""Request-local crypto facts preserve live authorization and proof boundaries."""
import time

import pytest
from app import module_delivery as delivery
from app.security import GrantSigner
from test_dpop import env
from test_protocol_v2 import v2
from test_module_delivery import modules, authorize, call, request


def test_request_crypto_is_once_per_request_and_not_shared_across_requests(modules, monkeypatch):
    counts = {'grant': 0, 'recipient': 0, 'evidence': 0}
    original_verify = GrantSigner.verify
    original_recipient = delivery.recipient
    original_evidence = delivery.require_request_evidence
    def verify(self, token):
        if token == modules.session_token:
            counts['grant'] += 1
        return original_verify(self, token)
    def recipient(*args):
        counts['recipient'] += 1
        return original_recipient(*args)
    def evidence(*args):
        counts['evidence'] += 1
        return original_evidence(*args)
    monkeypatch.setattr(GrantSigner, 'verify', verify)
    monkeypatch.setattr(delivery, 'recipient', recipient)
    monkeypatch.setattr(delivery, 'require_request_evidence', evidence)
    ids = [item['id'] for item in modules.catalog['modules']]
    for count in (1, 2):
        authorize(modules, ids)
        assert counts == dict.fromkeys(counts, count)
    # A new request must consume a fresh proof even though keys and grants match.
    path = '/v1/modules/catalog'
    _, headers, _ = request(modules, path)
    assert modules.client.get(path, headers=headers).status_code == 200
    assert modules.client.get(path, headers=headers).status_code == 401
    assert counts == dict.fromkeys(counts, 4)


@pytest.mark.parametrize('change', ['method', 'path', 'recipient', 'evidence', 'proof', 'token'])
def test_mid_request_input_changes_never_reuse_verified_facts(modules, monkeypatch, change):
    observed = []
    original_evidence = delivery.require_request_evidence
    original_personalize = delivery.personalize
    def evidence(request, *args):
        observed.append(request)
        return original_evidence(request, *args)
    def personalize(*args):
        result = original_personalize(*args)
        request = observed[0]
        if change == 'method':
            request.scope['method'] = 'GET'
        elif change == 'path':
            request.scope['raw_path'] = b'/v1/modules/other'
        else:
            header = {'recipient': b'x-celeste-delivery-key', 'evidence': b'x-celeste-module-evidence', 'proof': b'x-celeste-module-token-proof', 'token': b'authorization'}[change]
            raw = request.scope["headers"]
            for index, (name, value) in enumerate(raw):
                if name == header:
                    raw[index] = (name, value + b'a')
                    break
            else:
                raise AssertionError('header not present')
        return result
    monkeypatch.setattr(delivery, 'require_request_evidence', evidence)
    monkeypatch.setattr(delivery, 'personalize', personalize)
    result, _ = call(modules, '/v1/modules/authorize', method='POST', value={'catalog_sha256': modules.digest, 'modules': ['Auto Tool']})
    assert result.status_code == 401


@pytest.mark.parametrize('field', ['install_signing_key', 'token_signing_key', 'monitor_hash', 'hardware_core_hash', 'hwid_sha256'])
def test_changed_live_proof_binding_aborts_stream_before_final_tag(modules, monkeypatch, field):
    granted = authorize(modules)['capabilities'][0]
    _, headers, _ = request(modules, granted['url'], capability=granted['capability'])
    original = delivery.encrypt_chunks
    def changed(*args):
        chunks = list(original(*args))
        yield from chunks[:-1]
        with modules.app.state.database.connect() as connection:
            connection.execute('UPDATE devices SET ' + field + '=?', ('0' * 64 if field == 'hwid_sha256' else bytes(32),))
        yield chunks[-1]
    monkeypatch.setattr(delivery, 'encrypt_chunks', changed)
    with pytest.raises(RuntimeError, match='response already started'):
        modules.client.get(granted['url'], headers=headers)


def test_clock_rollback_rechecks_cached_claim_freshness(modules, monkeypatch):
    issued = modules.app.state.signer.verify(modules.session_token)['iat']
    original = delivery.encrypt
    def rollback(*args):
        result = original(*args)
        monkeypatch.setattr(time, 'time', lambda: issued - 6)
        return result
    monkeypatch.setattr(delivery, 'encrypt', rollback)
    result, _ = call(modules, '/v1/modules/catalog')
    assert result.status_code == 401


@pytest.mark.parametrize("field", ["install_signing_key", "token_signing_key", "monitor_hash", "hardware_core_hash"])
@pytest.mark.parametrize("corrupt", ["invalid stored text", 2**31])
def test_corrupt_stored_proof_types_fail_closed_without_integer_allocation(modules, field, corrupt):
    with modules.app.state.database.connect() as connection:
        connection.execute("UPDATE devices SET " + field + "=?", (corrupt,))
    result, _ = call(modules, "/v1/modules/catalog")
    assert result.status_code == 401
