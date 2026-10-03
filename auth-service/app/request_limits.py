from __future__ import annotations

import asyncio
import threading
from collections.abc import Awaitable, Callable
from typing import Any

from starlette.responses import JSONResponse
from starlette.types import Message, Receive, Scope, Send

BODY_READ_TIMEOUT_SECONDS = 15.0
MAX_API_BODY_READS = 32
MAX_CONFIG_BODY_READS = 4


class RequestLimitMiddleware:
    def __init__(
        self,
        app: Callable[..., Awaitable[Any]],
        max_bytes: int = 16_384,
        cloud_config_max_bytes: int = 1_500_000,
    ):
        self.app = app
        self.max_bytes = max_bytes
        self.cloud_config_max_bytes = cloud_config_max_bytes
        # Admission bounds unauthenticated buffering, not downstream requests or
        # downloads. Large config uploads cannot occupy the ordinary API slots.
        self._api_body_slots = threading.BoundedSemaphore(MAX_API_BODY_READS)
        self._config_body_slots = threading.BoundedSemaphore(MAX_CONFIG_BODY_READS)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope.get("method") not in {"POST", "PUT", "PATCH"}:
            await self.app(scope, receive, send)
            return
        raw_headers = {key.lower(): value for key, value in scope.get("headers", [])}
        path = str(scope.get("path", ""))
        body_limit = self.cloud_config_max_bytes if path == "/v1/configs" else self.max_bytes
        content_type = raw_headers.get(b"content-type", b"").decode("latin-1").lower()
        if path.startswith("/v1/") and not content_type.startswith("application/json"):
            await self._reject(scope, receive, send, 415)
            return
        if path.startswith("/admin/") and not (
            content_type.startswith("application/x-www-form-urlencoded")
            or content_type.startswith("multipart/form-data")
        ):
            await self._reject(scope, receive, send, 415)
            return
        raw_length = raw_headers.get(b"content-length")
        if raw_length is not None:
            try:
                declared = int(raw_length)
                if declared < 0:
                    await self._reject(scope, receive, send, 400)
                    return
                if declared > body_limit:
                    await self._reject(scope, receive, send, 413)
                    return
            except ValueError:
                await self._reject(scope, receive, send, 400)
                return
        slots = self._config_body_slots if path == "/v1/configs" else self._api_body_slots
        if not slots.acquire(blocking=False):
            await self._reject(scope, receive, send, 503)
            return
        body = bytearray()
        complete = False
        rejection = None
        try:
            # One absolute deadline; sending a trickle does not extend it.
            async with asyncio.timeout(BODY_READ_TIMEOUT_SECONDS):
                while True:
                    message = await receive()
                    if message["type"] == "http.disconnect":
                        return
                    if message["type"] != "http.request":
                        rejection = 400
                        break
                    chunk = message.get("body", b"")
                    if len(chunk) > body_limit - len(body):
                        rejection = 413
                        break
                    body.extend(chunk)
                    more = message.get("more_body", False)
                    # Release the copied incoming chunk before waiting for more.
                    message = None
                    chunk = None
                    if not more:
                        complete = True
                        break
        except TimeoutError:
            rejection = 408
        finally:
            slots.release()
            # Do not retain the final ASGI chunk across route/response awaits.
            message = None
            chunk = None
            if not complete:
                body.clear()
        if rejection is not None:
            await self._reject(scope, receive, send, rejection)
            return
        replayed = False

        async def replay() -> Message:
            nonlocal replayed
            if not replayed:
                replayed = True
                value = bytes(body)
                body.clear()
                return {"type": "http.request", "body": value, "more_body": False}
            return {"type": "http.request", "body": b"", "more_body": False}

        try:
            await self.app(scope, replay, send)
        finally:
            body.clear()

    async def _reject(self, scope: Scope, receive: Receive, send: Send, status: int) -> None:
        response = JSONResponse({"detail": "request rejected"}, status_code=status,
                                headers={"Retry-After": "1"} if status == 503 else None)
        await response(scope, receive, send)
