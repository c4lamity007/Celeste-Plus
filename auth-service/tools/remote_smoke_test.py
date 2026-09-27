from __future__ import annotations

import argparse
import base64
import hashlib
import json

import httpx
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


def b64e(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


def b64d(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "===")


def raw_public(key) -> str:
    return b64e(key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--license", required=True)
    parser.add_argument("--manifest", required=True)
    args = parser.parse_args()
    manifest = json.loads(open(args.manifest, encoding="utf-8").read())
    release = {
        "release_id": manifest["release"],
        "client_version": "1.0.0",
        "client_sha256": manifest["protectedDigest"],
        "game_uuid": "01020304-0506-4708-890a-0b0c0d0e0f10",
        "game_name": "DeploymentAudit",
    }
    hardware = {
        "system": hashlib.sha256(b"deployment-system").hexdigest(),
        "board": hashlib.sha256(b"deployment-board").hexdigest(),
        "processor": hashlib.sha256(b"deployment-processor").hexdigest(),
        "monitors": [hashlib.sha256(b"deployment-monitor-edid").hexdigest()],
    }
    install_signing = ed25519.Ed25519PrivateKey.generate()
    install_encryption = x25519.X25519PrivateKey.generate()
    with httpx.Client(base_url=args.base_url, timeout=15.0, follow_redirects=False) as client:
        response = client.post(
            "/v1/enroll/start",
            json={
                **release,
                "license_key": args.license,
                "install_signing_key": raw_public(install_signing),
                "install_encryption_key": raw_public(install_encryption),
                "hardware": hardware,
            },
        )
        response.raise_for_status()
        start = response.json()
        message = b64d(start["message"])
        response = client.post(
            "/v1/enroll/complete",
            json={
                "challenge_id": start["challenge_id"],
                "install_signature": b64e(install_signing.sign(message)),
            },
        )
        response.raise_for_status()
        enrolled = response.json()
        envelope = enrolled["token_envelope"]
        shared = install_encryption.exchange(x25519.X25519PublicKey.from_public_bytes(b64d(envelope["ephemeral_key"])))
        nonce = b64d(json.loads(message)["nonce"])
        wrapping_key = HKDF(
            algorithm=hashes.SHA256(),
            length=32,
            salt=nonce,
            info=b"celeste-install-token-v1",
        ).derive(shared)
        token_seed = AESGCM(wrapping_key).decrypt(
            b64d(envelope["nonce"]),
            b64d(envelope["ciphertext"]),
            message,
        )
        token_key = ed25519.Ed25519PrivateKey.from_private_bytes(token_seed)

        response = client.post(
            "/v1/auth/start",
            json={**release, "device_id": enrolled["device_id"], "hardware": hardware},
        )
        response.raise_for_status()
        challenge = response.json()
        message = b64d(challenge["message"])
        response = client.post(
            "/v1/auth/complete",
            json={
                "challenge_id": challenge["challenge_id"],
                "install_signature": b64e(install_signing.sign(message)),
                "token_signature": b64e(token_key.sign(message)),
            },
        )
        response.raise_for_status()
        session = response.json()
        payload = bytearray()
        for chunk in session["artifact"]["chunks"]:
            downloaded = client.get(chunk["url"], headers={"Authorization": f"Bearer {session['grant']}"})
            downloaded.raise_for_status()
            assert hashlib.sha256(downloaded.content).hexdigest() == chunk["sha256"]
            payload.extend(downloaded.content)
        assert hashlib.sha256(payload).hexdigest() == manifest["jarSha256"]
        print(json.dumps({"uid": session["uid"], "device_id": enrolled["device_id"], "bytes": len(payload)}))


if __name__ == "__main__":
    main()

