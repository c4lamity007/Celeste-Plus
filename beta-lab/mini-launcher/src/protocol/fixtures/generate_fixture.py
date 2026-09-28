"""Generate public test data using the real anonymous backend router/signing code.

Only a disposable SQLite database and public synthetic seed are used. No socket,
real identity, executable launch or production configuration is accessed.
"""
from pathlib import Path
import argparse
import base64
import hashlib
import json
import sys
import tempfile
from types import SimpleNamespace
from unittest.mock import patch


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend',type=Path,required=True)
    parser.add_argument('--check',action='store_true')
    args=parser.parse_args()
    backend=args.backend.resolve(strict=True)
    sys.path.insert(0,str(backend))
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from app.database import Database
    from app.native_download import create_native_download_router,ORIGIN
    from app.security import GrantSigner,b64e
    seed=bytes(range(32));nonce=b64e(bytes(range(24)));now=2_000_000_000
    payload=b'Public Celeste mini-launcher fixture; never executed.\n'
    digest=hashlib.sha256(payload).hexdigest()
    with tempfile.TemporaryDirectory(prefix='celeste-mini-public-fixture-')as temp:
        root=Path(temp);public=root/'public';public.mkdir()
        (public/(digest+'.exe')).write_bytes(payload)
        db=Database(str(root/'fixture.sqlite3'));db.initialize()
        entry={'sha256':digest,'size':len(payload),'release_id':'public-test-loader-3','protection_receipt_sha256':'d'*64}
        with db.connect()as connection:
            connection.execute('INSERT INTO native_release_policies(product,platform,sequence,publication_sha256,binaries_json,published_at) VALUES(?,?,?,?,?,?)',
                ('celeste_plus','windows-x64',3,'e'*64,json.dumps([entry]),now))
        settings=SimpleNamespace(public_native_dir=str(public),plus_download_sha256=digest,
            public_native_max_bytes=256*1024*1024,artifact_dir=str(root/'standard'),beta_release_dir=str(root/'plus'))
        signer=GrantSigner(seed);app=FastAPI();limits=[]
        app.include_router(create_native_download_router(settings,db,signer,lambda req,*args:limits.append(args)))
        with TestClient(app)as client:
            with patch('app.native_download.time.time',return_value=now):
                response=client.get('/v1/launcher/manifest?platform=windows-x64',headers={'X-Celeste-Request-Nonce':nonce})
            assert response.status_code==200
            body=response.json();claims=json.loads(base64.urlsafe_b64decode(body['manifest'].split('.')[1]+'==='))
            downloaded=client.get(claims['url'].removeprefix(ORIGIN))
            assert downloaded.status_code==200 and downloaded.content==payload
        fixture={'schema':'celeste-mini-public-download-fixture-1','scope':'Actual anonymous backend router and GrantSigner, public synthetic key/data only.',
            'now':now,'nonce':nonce,'public_key':signer.public_key_b64(),'public_test_seed':b64e(seed),
            'response':body,'claims':claims,'payload_utf8':payload.decode(),
            'source_sha256':{n:hashlib.sha256((backend/n).read_bytes()).hexdigest()for n in ('app/native_download.py','app/security.py')}}
    raw=(json.dumps(fixture,indent=2,sort_keys=True)+'\n').encode()
    target=Path(__file__).with_name('server-manifest.json')
    if args.check:
        if target.read_bytes()!=raw:raise ValueError('Server fixture changed')
    else:target.write_bytes(raw)
    print('PASS actual public router fixture; no real auth/network/execution')

if __name__=='__main__':main()
