"""Bounded lifetime for one checked artifact snapshot and its transfer slot."""
from __future__ import annotations

import asyncio
import time

from fastapi.responses import StreamingResponse

CHUNK_BYTES = 64 * 1024
TRANSFER_TIMEOUT_SECONDS = 30


def plain_chunks(content: bytes):
    view = memoryview(content)
    for offset in range(0, len(view), CHUNK_BYTES):
        yield view[offset:offset + CHUNK_BYTES]


class ArtifactResponse(StreamingResponse):
    def __init__(self, chunks, length: int, slots, *, authorization_guard=None):
        self._chunks = chunks
        self._slots = slots

        async def stream():
            if authorization_guard is None:
                for chunk in chunks:
                    yield chunk
                return
            # Hold the final chunk (the envelope tag) until the final live check.
            # The supplied guard performs its blocking checks in a worker thread.
            await authorization_guard()
            checked_at = time.monotonic()
            pending = None
            for chunk in chunks:
                if pending is not None:
                    if time.monotonic() - checked_at >= 1:
                        await authorization_guard()
                        checked_at = time.monotonic()
                    yield pending
                pending = chunk
            if pending is not None:
                await authorization_guard()
                yield pending

        super().__init__(stream(), media_type="application/octet-stream", headers={
            "Content-Length": str(length), "Cache-Control": "no-store",
        })

    async def __call__(self, scope, receive, send):
        try:
            async with asyncio.timeout(TRANSFER_TIMEOUT_SECONDS):
                await super().__call__(scope, receive, send)
        finally:
            # Drop the plaintext snapshot even if cancellation happened before
            # the iterator started, or the response object is retained in logs.
            try:
                await self.body_iterator.aclose()
            finally:
                try:
                    self._chunks.close()
                finally:
                    self._slots.release()
