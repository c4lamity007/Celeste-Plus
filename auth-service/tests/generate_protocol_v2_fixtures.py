"""Public deterministic interoperability vectors. Seeds here are synthetic, never production keys."""
from pathlib import Path
import json
import sys
import tempfile
import hashlib
from unittest.mock import patch
from cryptography.hazmat.primitives.asymmetric import x25519, ed25519
from cryptography.hazmat.primitives import serialization
from fastapi import FastAPI
from fastapi.testclient import TestClient
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.security import GrantSigner, b64d, b64e, canonical_json
from app.artifact_envelope import encrypt
from app.transport_policy import create_transport_policy_router

NOW = 2_000_000_000
ROOT = Path(__file__).parent / 'fixtures'
REPOSITORY = Path(__file__).resolve().parents[2]


def delivery_vector():
    from app.beta_delivery import create_beta_router, ARTIFACT_FILENAMES, ARTIFACT_ROLES
    from app.database import Database
    from app.auth_lifecycle import record_grant
    from app.config import Settings
    from app.dpop import DPoPVerifier, access_token
    original = json.loads((REPOSITORY / 'beta-lab/delivery-client/fixtures/server-schema2-golden.json').read_bytes())
    signer = GrantSigner(hashlib.sha256(b'CELESTE PUBLIC OFFLINE GOLDEN TEST SEED v1').digest())
    assert signer.public_key_b64() == original['public_key']
    claims = {**original['installer_claims'], 'auth_protocol': 2}
    token = signer.sign(claims)
    install = ed25519.Ed25519PrivateKey.from_private_bytes(b64d(original['installation_test_seed'], 32))
    public = b64d(original['installation_public_key'], 32)
    with tempfile.TemporaryDirectory(prefix='celeste-public-v2-fixture-') as temporary:
        root = Path(temporary)
        directory = root / 'plus'
        directory.mkdir()
        previous_catalog = json.loads(original['canonical_catalog'])
        catalog = {k: previous_catalog[k] for k in ('schema', 'release_id', 'client_version', 'runtime_sha256')}
        catalog['artifacts'] = []
        for artifact in previous_catalog['artifacts']:
            name = ARTIFACT_FILENAMES[artifact['id']]
            content = b64d(original['artifact_bytes'][artifact['id']])
            (directory / name).write_bytes(content)
            catalog['artifacts'].append({k: artifact[k] for k in ('id', 'role', 'sha256', 'size')} | {'filename': name})
        (directory / 'beta-release.json').write_bytes(canonical_json(catalog))
        class FixtureSettings(SimpleNamespace):
            accepts_installer_identity = Settings.accepts_installer_identity
        settings = FixtureSettings(plus_enabled=True, plus_auth_protocol=2, beta_release_dir=str(directory),
            artifact_dir=str(root / 'unused'), release_id=claims['payload_release'], release_sha256=claims['payload_sha256'],
            installer_channel_id=claims['release'], installer_compatible_sha256=frozenset({claims['client_sha256']}),
            installer_allow_runtime_digest=False, bootstrap_id=claims['bootstrap'], bootstrap_sha256=claims['bootstrap_sha256'],
            public_origin='https://offline.invalid')
        database = Database(str(root / 'fixture.sqlite3'))
        database.initialize()
        with database.connect() as c:
            c.execute("INSERT INTO licenses(uid,key_hash,status,max_devices,created_at) VALUES(?,?,'active',1,?)",
                      (original['expected_uid'], bytes(32), original['now']))
            c.execute("INSERT INTO license_entitlements VALUES(?,'celeste_plus',?)", (original['expected_uid'], original['now']))
            c.execute("""INSERT INTO devices(device_id,license_uid,install_signing_key,token_signing_key,hardware_core_hash,
                       monitor_hash,status,created_at) VALUES(?,?,?,?,?,?,'active',?)""",
                      (original['expected_device'], original['expected_uid'], public, bytes(32), bytes(32), bytes(32), original['now']))
            record_grant(c, token, claims)
        verifier = DPoPVerifier(settings)
        def require_proof(verified, request, *_):
            with database.connect() as c:
                c.execute('BEGIN IMMEDIATE')
                verifier.require(c, request, public, token=access_token(request), claims=verified)
            return original['expected_device']
        app = FastAPI()
        app.include_router(create_beta_router(settings, database, signer, require_proof, lambda *args: None))
        proof_header = {'typ': 'dpop+jwt', 'alg': 'EdDSA', 'jwk': {'kty': 'OKP', 'crv': 'Ed25519', 'x': original['installation_public_key']}}
        proof_claims = {'htm': 'GET', 'htu': 'https://offline.invalid/v1/beta/manifest', 'iat': original['now'],
                        'jti': 'public-v2-golden-request-0001', 'ath': b64e(hashlib.sha256(token.encode()).digest())}
        first, second = b64e(canonical_json(proof_header)), b64e(canonical_json(proof_claims))
        proof = first + '.' + second + '.' + b64e(install.sign((first + '.' + second).encode()))
        with patch('time.time', return_value=original['now']), TestClient(app, base_url='https://offline.invalid') as client:
            response = client.get('/v1/beta/manifest', headers={'Authorization': 'DPoP ' + token,
                'DPoP': proof, 'X-Celeste-Request-Nonce': original['expected_request_nonce']})
            assert response.status_code == 200, response.text
            manifest = response.json()['manifest']
            verified = signer.verify(manifest)
            assert verified and verified['catalog_sha256'] == original['catalog_sha256']
    return {**original, 'installer_claims': claims, 'installer_token': token, 'manifest_token': manifest, 'manifest_claims': verified}


def vectors():
    signer = GrantSigner(bytes(range(32)))
    nonce = b64e(bytes(range(24)))
    app = FastAPI()
    app.include_router(create_transport_policy_router(SimpleNamespace(tls_leaf_sha256=('a' * 64, 'b' * 64)),
        signer, lambda *args: None, 'https://qb0f90cf11f4d1d52.fly.dev'))
    with patch('app.transport_policy.time.time', return_value=NOW), TestClient(app) as client:
        response = client.get('/v1/transport-policy?product=celeste_plus', headers={'X-Celeste-Request-Nonce': nonce})
        assert response.status_code == 200
    policy = {'schema': 'celeste-transport-policy-golden-1', 'now': NOW, 'public_key': signer.public_key_b64(),
              'request_nonce': nonce, 'origin': 'https://qb0f90cf11f4d1d52.fly.dev', 'product': 'celeste_plus',
              'leaf_sha256': ['a' * 64, 'b' * 64], 'response': response.json()}
    client_seed, server_seed = bytes(range(32, 64)), bytes(range(64, 96))
    private = x25519.X25519PrivateKey.from_private_bytes(client_seed)
    public = b64e(private.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw))
    token = signer.sign({'aud': 'celeste-installer', 'product': 'celeste_plus', 'device': '00000000-0000-4000-8000-000000000001',
                         'sub': '1', 'iat': NOW, 'nbf': NOW, 'exp': NOW + 600, 'jti': 'public-fixture-token-0001',
                         'auth_protocol': 2, 'cnf': {'jkt': b64e(bytes(range(32)))}})
    path = '/v1/beta/artifacts/public-fixture-release/bootstrap-a'
    plaintext = b'Public fixture artifact only. No real customer or release data.\x00\xff'
    with patch('app.artifact_envelope.x25519.X25519PrivateKey.generate', return_value=x25519.X25519PrivateKey.from_private_bytes(server_seed)), \
            patch('app.artifact_envelope.secrets.token_bytes', return_value=bytes(range(12))):
        envelope = encrypt(plaintext, token, path, public, nonce)
    artifact = {'schema': 'celeste-artifact-envelope-golden-1', 'client_private_key': b64e(client_seed),
                'server_private_key': b64e(server_seed), 'client_public_key': public, 'request_nonce': nonce,
                'request_path': path, 'installer_token': token, 'plaintext': b64e(plaintext), 'envelope': b64e(envelope)}
    return {'transport-policy-v1.json': policy, 'artifact-envelope-v1.json': artifact}


if __name__ == '__main__':
    check = sys.argv[1:] == ['--check']
    ROOT.mkdir(exist_ok=True)
    for name, value in vectors().items():
        encoded = (json.dumps(value, indent=2, sort_keys=True) + '\n').encode()
        path = ROOT / name
        if check:
            assert path.read_bytes() == encoded, name + ' differs from actual server vector'
        else:
            path.write_bytes(encoded)
    path = REPOSITORY / 'beta-lab/delivery-client/fixtures/server-schema2-v2-golden.json'
    encoded = (json.dumps(delivery_vector(), indent=2, sort_keys=True) + '\n').encode()
    if check:
        assert path.read_bytes() == encoded, 'V2 delivery fixture differs from current server'
    else:
        path.write_bytes(encoded)
    print('Public protocol-v2 vectors verified' if check else 'Public protocol-v2 vectors generated')
