from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

from starlette.responses import JSONResponse
from starlette.types import Message, Receive, Scope, Send


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
                if int(raw_length) > body_limit:
                    await self._reject(scope, receive, send, 413)
                    return
            except ValueError:
                await self._reject(scope, receive, send, 400)
                return
        body = bytearray()
        more = True
        while more:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            if message["type"] != "http.request":
                continue
            body.extend(message.get("body", b""))
            if len(body) > body_limit:
                await self._reject(scope, receive, send, 413)
                return
            more = bool(message.get("more_body", False))
        replayed = False

        async def replay() -> Message:
            nonlocal replayed
            if not replayed:
                replayed = True
                return {"type": "http.request", "body": bytes(body), "more_body": False}
            return {"type": "http.request", "body": b"", "more_body": False}

        await self.app(scope, replay, send)

    async def _reject(self, scope: Scope, receive: Receive, send: Send, status: int) -> None:
        response = JSONResponse({"detail": "request rejected"}, status_code=status)
        await response(scope, receive, send)
