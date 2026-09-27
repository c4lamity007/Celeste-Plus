"""Request correlation and a uniform API failure envelope, without logging secrets."""
from __future__ import annotations

import json
import logging
import os
import uuid

from starlette.responses import JSONResponse
from .auth_errors import CODES

LOGGER = logging.getLogger("celeste.requests")
ERROR_CODES = {400: "invalid_request", 401: "unauthorized", 403: "forbidden", 404: "not_found",
               405: "method_not_allowed", 413: "request_too_large", 415: "unsupported_media_type",
               422: "invalid_request", 429: "rate_limited", 500: "internal_error", 503: "service_unavailable"}


class ApiResponseMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        request_id = uuid.uuid4().hex
        scope.setdefault("state", {})["request_id"] = request_id
        api = scope.get("path", "").startswith("/v1/")
        start = None
        body = bytearray()
        sent = False
        status = 500

        async def error_response(detail, headers):
            nonlocal sent
            message = detail if isinstance(detail, str) else "Request validation failed"
            code = "token_revoked" if detail == "token revoked" else ERROR_CODES.get(status, "request_failed")
            retry = 300 if status in (401, 403) else 0
            if (isinstance(detail, dict) and set(detail) == {"error_code", "message", "retry_after_seconds"}
                    and detail["error_code"] in CODES and isinstance(detail["message"], str)
                    and type(detail["retry_after_seconds"]) is int and detail["retry_after_seconds"] >= 0):
                code, message, retry = detail["error_code"], detail["message"], detail["retry_after_seconds"]
                detail = message
            for key, value in headers:
                if key.lower() == b"retry-after":
                    try:
                        retry = max(0, int(value))
                    except ValueError:
                        pass
            response = JSONResponse({"detail": detail, "request_id": request_id, "error_code": code,
                                     "message": message, "retry_after_seconds": retry}, status_code=status)
            response.raw_headers += [(key, value) for key, value in headers
                                     if key.lower() not in (b"content-length", b"content-type", b"x-request-id")]
            response.headers["X-Request-Id"] = request_id
            response.headers["Cache-Control"] = "no-store"
            response.headers["X-Content-Type-Options"] = "nosniff"
            if scope.get("scheme") == "https" or os.getenv("FLY_APP_NAME"):
                response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
            sent = True
            await response(scope, receive, send)

        async def wrapped(message):
            nonlocal start, status, sent
            if message["type"] == "http.response.start":
                status = message["status"]
                headers = [(key, value) for key, value in message.get("headers", []) if key.lower() != b"x-request-id"]
                headers.append((b"x-request-id", request_id.encode("ascii")))
                start = {**message, "headers": headers}
                if not api or status < 400:
                    sent = True
                    await send(start)
            elif message["type"] == "http.response.body" and api and status >= 400:
                body.extend(message.get("body", b"")[:max(0, 65537 - len(body))])
                if not message.get("more_body", False):
                    detail = "Request rejected"
                    if len(body) <= 65536:
                        try:
                            original = json.loads(body)
                            if isinstance(original, dict):
                                detail = original.get("detail", detail)
                        except (ValueError, UnicodeError):
                            pass
                    await error_response(detail, start["headers"])
            else:
                await send(message)

        try:
            await self.app(scope, receive, wrapped)
        except Exception:
            if sent or not api:
                raise
            status = 500
            await error_response("Internal server error", [])
        finally:
            # No headers, query strings, credentials, body, or exception text.
            LOGGER.info(json.dumps({"request_id": request_id, "method": scope.get("method", ""),
                                    "path": scope.get("path", "")[:200], "status": status}, separators=(",", ":")))
