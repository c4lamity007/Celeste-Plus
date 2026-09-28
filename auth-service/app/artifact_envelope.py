"""Per-request recipient encryption. A signed token alone cannot derive this key."""
from __future__ import annotations

import hashlib
import secrets

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import x25519
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from .auth_errors import failure
from .security import b64d, b64e


MAGIC = b"CSTAE1"
OVERHEAD_BYTES = len(MAGIC) + 32 + 12 + 16
CHUNK_BYTES = 64 * 1024


def canonical_bytes(value: str, length: int) -> bytes:
    if not isinstance(value, str) or not 1 <= len(value) <= 128:
        raise ValueError("Invalid envelope encoding")
    result = b64d(value, length)
    if b64e(result) != value:
        raise ValueError("Invalid envelope encoding")
    return result


def recipient(request) -> tuple[str, str]:
    keys, nonces = request.headers.getlist("x-celeste-delivery-key"), request.headers.getlist("x-celeste-request-nonce")
    try:
        if len(keys) != 1 or len(nonces) != 1:
            raise ValueError("Missing recipient")
        key = canonical_bytes(keys[0], 32)
        canonical_bytes(nonces[0], 24)
        # Reject low-order points before authenticating/charging any request.
        x25519.X25519PrivateKey.generate().exchange(x25519.X25519PublicKey.from_public_bytes(key))
    except (ValueError, TypeError):
        raise failure("invalid_delivery_key", "A fresh encrypted delivery request is required", 400, 0) from None
    return keys[0], nonces[0]


def _parameters(token: str, path: str, client_key: str, request_nonce: str):
    ephemeral = x25519.X25519PrivateKey.generate()
    peer = x25519.X25519PublicKey.from_public_bytes(canonical_bytes(client_key, 32))
    info = ("celeste-artifact-v1\n" + path + "\n" + request_nonce + "\n" + client_key).encode("utf-8")
    key = HKDF(algorithm=hashes.SHA256(), length=32, salt=hashlib.sha256(token.encode("ascii")).digest(),
               info=info).derive(ephemeral.exchange(peer))
    nonce = secrets.token_bytes(12)
    public = ephemeral.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return key, nonce, info, MAGIC + public + nonce


def encrypt(content: bytes, token: str, path: str, client_key: str, request_nonce: str) -> bytes:
    key, nonce, info, header = _parameters(token, path, client_key, request_nonce)
    return header + AESGCM(key).encrypt(nonce, content, info)


def encrypt_chunks(content: bytes, token: str, path: str, client_key: str, request_nonce: str):
    """Same CSTAE1 envelope, with bounded ciphertext allocations.

    Set up/validate before returning a response. The final authentication tag is
    emitted only after all plaintext; interruption cannot yield a valid envelope.
    """
    key, nonce, info, header = _parameters(token, path, client_key, request_nonce)
    encryptor = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
    encryptor.authenticate_additional_data(info)

    def chunks():
        yield header
        view = memoryview(content)
        for offset in range(0, len(view), CHUNK_BYTES):
            yield encryptor.update(view[offset:offset + CHUNK_BYTES])
        tail = encryptor.finalize()
        if tail:
            yield tail
        yield encryptor.tag

    return chunks()
