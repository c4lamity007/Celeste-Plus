"""Anonymous delivery of one explicitly selected, registered Plus launcher.

The public launcher still performs its normal authentication. None of these
routes authorizes or serves game artifacts. A download manifest is deliberately
a different token audience from both access grants and native self-integrity.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile
import threading
import time
from collections.abc import Callable

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse

from .config import Settings
from .database import Database
from .native_manifest import HEX, IDENTIFIER, NONCE
from .security import GrantSigner

ORIGIN = "https://qb0f90cf11f4d1d52.fly.dev"
PRODUCT = "celeste_plus"
PLATFORM = "windows-x64"
MAX_BYTES = 256 * 1024 * 1024
CHUNK_BYTES = 64 * 1024
TRANSFER_TIMEOUT_SECONDS = 300


def download_path(digest: str) -> str:
    return f"/v1/launcher/windows-x64/{digest}/Celeste+.exe"


def unavailable() -> HTTPException:
    return HTTPException(503, "public launcher is unavailable")


class NativeSnapshot:
    """Private, delete-on-close file, never written again after verification.

    This preserves the checked bytes if the staging path changes without keeping
    an executable-sized Python allocation. The OS may reclaim its file cache.
    """

    def __init__(self, file, size: int):
        self._file = file
        self._size = size
        self._lock = threading.Lock()

    def __len__(self):
        return self._size

    def read_at(self, offset: int) -> bytes:
        if not 0 <= offset <= self._size:
            raise ValueError("invalid snapshot offset")
        count = min(CHUNK_BYTES, self._size - offset)
        # Independent offsets for concurrent readers, including Windows where
        # os.pread is unavailable. No lock is held during network backpressure.
        with self._lock:
            self._file.seek(offset)
            data = self._file.read(count)
        if len(data) != count:
            raise OSError("verified snapshot became unavailable")
        return data


class DownloadResponse(StreamingResponse):
    """Release the transfer slot even when ASGI send fails or is cancelled."""

    def __init__(self, data: NativeSnapshot, slots: threading.BoundedSemaphore):
        async def chunks():
            for offset in range(0, len(data), CHUNK_BYTES):
                yield memoryview(data.read_at(offset))

        super().__init__(chunks(), media_type="application/octet-stream", headers={
            "Content-Length": str(len(data)),
            "Content-Disposition": 'attachment; filename="Celeste+.exe"',
            "Cache-Control": "no-store",
            "X-Content-Type-Options": "nosniff",
            "Accept-Ranges": "none",
        })
        self._slots = slots

    async def __call__(self, scope, receive, send):
        try:
            # Slow/disconnected consumers cannot occupy every slot forever.
            async with asyncio.timeout(TRANSFER_TIMEOUT_SECONDS):
                await super().__call__(scope, receive, send)
        finally:
            self._slots.release()


class PublicNativeStore:
    def __init__(self, settings: Settings, database: Database):
        self.settings = settings
        self.database = database
        # All downloads share one checked private file snapshot. Neither the
        # staging pathname nor executable-sized heap buffers are kept open.
        self._data: NativeSnapshot | None = None
        self._lock = threading.Lock()
        self._slots = threading.BoundedSemaphore(2)

    def selected(self) -> tuple[int, dict]:
        digest = self.settings.plus_download_sha256
        if (not self.settings.public_native_dir or not HEX.fullmatch(digest)
                or type(self.settings.public_native_max_bytes) is not int
                or not 0 < self.settings.public_native_max_bytes <= MAX_BYTES):
            raise unavailable()
        with self.database.connect() as connection:
            row = connection.execute(
                "SELECT sequence,binaries_json FROM native_release_policies WHERE product=? AND platform=?",
                (PRODUCT, PLATFORM),
            ).fetchone()
        try:
            if row is None or type(row["sequence"]) is not int or not 0 < row["sequence"] < 2**63:
                raise ValueError("unregistered")
            entries = json.loads(row["binaries_json"])
            if not isinstance(entries, list) or not 1 <= len(entries) <= 4:
                raise ValueError("invalid registry")
            matches = [entry for entry in entries if isinstance(entry, dict) and entry.get("sha256") == digest]
            if len(matches) != 1:
                raise ValueError("selected launcher withdrawn")
            entry = matches[0]
            if (set(entry) != {"sha256", "size", "release_id", "protection_receipt_sha256"}
                    or type(entry["size"]) is not int or not 0 < entry["size"] <= self.settings.public_native_max_bytes
                    or not isinstance(entry["release_id"], str) or not IDENTIFIER.fullmatch(entry["release_id"])
                    or not isinstance(entry["protection_receipt_sha256"], str)
                    or not HEX.fullmatch(entry["protection_receipt_sha256"])):
                raise ValueError("invalid selected launcher")
            return row["sequence"], entry
        except (ValueError, TypeError, KeyError, OverflowError, RecursionError) as exc:
            raise unavailable() from exc

    def _read_snapshot(self, entry: dict) -> NativeSnapshot:
        try:
            root = Path(self.settings.public_native_dir)
            if not root.is_absolute() or root.resolve(strict=True) != root or not root.is_dir():
                raise ValueError("invalid public root")
            # A configuration mistake must not turn a protected artifact tree
            # into a public download directory, in either direction.
            for configured in (self.settings.artifact_dir, self.settings.beta_release_dir):
                if configured:
                    protected = Path(configured).resolve()
                    if root.is_relative_to(protected) or protected.is_relative_to(root):
                        raise ValueError("overlapping artifact roots")
            path = root / (entry["sha256"] + ".exe")
            if path.is_symlink() or path.resolve(strict=True) != path:
                raise ValueError("linked public file")
            staged = path.lstat()
            if not stat.S_ISREG(staged.st_mode) or staged.st_nlink != 1 or staged.st_size != entry["size"]:
                raise ValueError("invalid public file")
            descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_BINARY", 0)
                                 | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0))
            with os.fdopen(descriptor, "rb") as source:
                before = os.fstat(source.fileno())
                if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_size != entry["size"]:
                    raise ValueError("invalid public file")
                snapshot = tempfile.TemporaryFile(mode="w+b")
                try:
                    digest, size = hashlib.sha256(), 0
                    while chunk := source.read(min(CHUNK_BYTES, entry["size"] + 1 - size)):
                        size += len(chunk)
                        if size > entry["size"]:
                            raise ValueError("public file grew")
                        digest.update(chunk)
                        snapshot.write(chunk)
                    after = os.fstat(source.fileno())
                    if (size != entry["size"] or after.st_size != before.st_size
                            or after.st_mtime_ns != before.st_mtime_ns
                            or digest.hexdigest() != entry["sha256"]):
                        raise ValueError("public file changed or invalid")
                    snapshot.flush()
                    return NativeSnapshot(snapshot, size)
                except BaseException:
                    snapshot.close()
                    raise
        except (OSError, ValueError) as exc:
            raise unavailable() from exc

    def ready(self) -> tuple[NativeSnapshot, int, dict]:
        _, entry = self.selected()
        with self._lock:
            if self._data is None:
                self._data = self._read_snapshot(entry)
            data = self._data
        # Hashing/staging can be slow. Recheck live approval after that work,
        # including on warm-cache requests; a withdrawn loader stops serving.
        sequence, current = self.selected()
        if len(data) != current["size"]:
            raise unavailable()
        return data, sequence, current

    def response(self, digest: str) -> DownloadResponse:
        if not HEX.fullmatch(digest) or digest != self.settings.plus_download_sha256:
            raise HTTPException(404, "public launcher not found")
        if not self._slots.acquire(blocking=False):
            raise HTTPException(429, "public launcher transfers are busy", headers={"Retry-After": "1"})
        try:
            data, _, _ = self.ready()
            return DownloadResponse(data, self._slots)
        except BaseException:
            self._slots.release()
            raise


def create_native_download_router(settings: Settings, database: Database, signer: GrantSigner,
                                  apply_limit: Callable[[Request, str, int, int], None]) -> APIRouter:
    router = APIRouter()
    store = PublicNativeStore(settings, database)

    @router.get("/v1/launcher/manifest")
    def manifest(request: Request, platform: str):
        apply_limit(request, "public-native-manifest", 60, 60)
        nonces = request.headers.getlist("x-celeste-request-nonce")
        if platform != PLATFORM or len(nonces) != 1 or not NONCE.fullmatch(nonces[0]):
            raise HTTPException(400, "invalid launcher manifest request")
        _, sequence, entry = store.ready()
        now = int(time.time())
        token = signer.sign({
            "aud": "celeste-launcher-download", "iat": now, "nbf": now, "exp": now + 120,
            "request_nonce": nonces[0], "product": PRODUCT, "platform": PLATFORM,
            "sequence": sequence, "release_id": entry["release_id"],
            "sha256": entry["sha256"], "size": entry["size"],
            "url": ORIGIN + download_path(entry["sha256"]),
        })
        return JSONResponse({"manifest": token}, headers={"Cache-Control": "no-store"})

    @router.get("/v1/launcher/windows-x64/{digest}/Celeste+.exe")
    def download(request: Request, digest: str):
        apply_limit(request, "public-native-download", 60, 3600)
        if "range" in request.headers:
            raise HTTPException(416, "public launcher ranges are not supported")
        return store.response(digest)

    return router
