import asyncio

import pytest

from app import request_limits as limits


def scope(path="/v1/heartbeat", method="POST", headers=()):
    return {"type": "http", "path": path, "method": method,
            "headers": [(b"content-type", b"application/json"), *headers]}


async def invoke(middleware, selected=None, receive=None):
    messages = []

    async def ready():
        return {"type": "http.request", "body": b"{}", "more_body": False}

    async def send(message):
        messages.append(message)

    await middleware(selected or scope(), receive or ready, send)
    return messages


async def echo(selected, receive, send):
    first = await receive()
    second = await receive()
    assert first["more_body"] is False
    assert second == {"type": "http.request", "body": b"", "more_body": False}
    await send({"type": "http.response.start", "status": 200, "headers": []})
    await send({"type": "http.response.body", "body": first["body"]})


def test_stalled_config_uploads_have_bounded_separate_admission():
    async def scenario():
        middleware = limits.RequestLimitMiddleware(echo)
        entered = [asyncio.Event() for _ in range(limits.MAX_CONFIG_BODY_READS)]
        tasks = []
        for event in entered:
            async def receive(event=event, state=[False]):
                if not state[0]:
                    state[0] = True
                    return {"type": "http.request", "body": b"x" * 1_000_000, "more_body": True}
                event.set()
                await asyncio.Future()
            tasks.append(asyncio.create_task(invoke(middleware, scope("/v1/configs"), receive)))
        try:
            await asyncio.wait_for(asyncio.gather(*(event.wait() for event in entered)), 2)
            rejected = await invoke(middleware, scope("/v1/configs"))
            assert rejected[0]["status"] == 503
            assert (b"retry-after", b"1") in rejected[0]["headers"]
            assert (await invoke(middleware))[0]["status"] == 200
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
        assert (await invoke(middleware, scope("/v1/configs")))[0]["status"] == 200
    asyncio.run(scenario())


def test_trickled_body_cannot_restart_absolute_deadline(monkeypatch):
    monkeypatch.setattr(limits, "BODY_READ_TIMEOUT_SECONDS", 0.06)

    async def scenario():
        count = 0

        async def trickle():
            nonlocal count
            await asyncio.sleep(0.01)
            count += 1
            return {"type": "http.request", "body": b"x", "more_body": True}

        middleware = limits.RequestLimitMiddleware(echo)
        messages = await asyncio.wait_for(invoke(middleware, receive=trickle), 1)
        assert messages[0]["status"] == 408
        assert 0 < count < 20
        assert (await invoke(middleware))[0]["status"] == 200
    asyncio.run(scenario())


@pytest.mark.parametrize("termination", ["disconnect", "exception", "invalid"])
def test_incomplete_body_releases_admission(monkeypatch, termination):
    monkeypatch.setattr(limits, "MAX_API_BODY_READS", 1)

    async def scenario():
        middleware = limits.RequestLimitMiddleware(echo)
        count = 0

        async def receive():
            nonlocal count
            count += 1
            if count == 1:
                return {"type": "http.request", "body": b"partial", "more_body": True}
            if termination == "exception":
                raise OSError("synthetic receive failure")
            return {"type": "http.disconnect" if termination == "disconnect" else "invalid"}

        if termination == "exception":
            with pytest.raises(OSError):
                await invoke(middleware, receive=receive)
        else:
            result = await invoke(middleware, receive=receive)
            assert result == [] if termination == "disconnect" else result[0]["status"] == 400
        assert (await invoke(middleware))[0]["status"] == 200
    asyncio.run(scenario())


@pytest.mark.parametrize("length,status", [(b"16385", 413), (b"-1", 400), (b"bad", 400)])
def test_declared_length_rejected_without_read(length, status):
    async def forbidden():
        raise AssertionError("Rejected declared body must not be read")
    result = asyncio.run(invoke(limits.RequestLimitMiddleware(echo),
                                scope(headers=[(b"content-length", length)]), forbidden))
    assert result[0]["status"] == status


def test_streamed_limit_and_exact_replay(monkeypatch):
    monkeypatch.setattr(limits, "MAX_API_BODY_READS", 1)

    async def scenario():
        middleware = limits.RequestLimitMiddleware(echo, max_bytes=8)
        chunks = iter([b"1234", b"56789"])

        async def oversized():
            return {"type": "http.request", "body": next(chunks), "more_body": True}
        assert (await invoke(middleware, receive=oversized))[0]["status"] == 413
        chunks = iter([b"1234", b"5678"])

        async def exact():
            chunk = next(chunks)
            return {"type": "http.request", "body": chunk, "more_body": chunk != b"5678"}
        result = await invoke(middleware, receive=exact)
        assert result[0]["status"] == 200
        assert result[1]["body"] == b"12345678"
    asyncio.run(scenario())


def test_downstream_response_does_not_hold_body_admission(monkeypatch):
    monkeypatch.setattr(limits, "MAX_API_BODY_READS", 1)

    async def scenario():
        reached = asyncio.Queue()
        release = asyncio.Event()

        async def slow_response(selected, receive, send):
            assert (await receive())["body"] == b"{}"
            await reached.put(True)
            await release.wait()
            await send({"type": "http.response.start", "status": 200, "headers": []})
        middleware = limits.RequestLimitMiddleware(slow_response)
        first = asyncio.create_task(invoke(middleware))
        second = None
        try:
            await asyncio.wait_for(reached.get(), 2)
            second = asyncio.create_task(invoke(middleware))
            await asyncio.wait_for(reached.get(), 2)
        finally:
            release.set()
            await asyncio.gather(*(t for t in (first, second) if t is not None))
    asyncio.run(scenario())


def test_api_reader_saturation_cancellation_and_get_passthrough(monkeypatch):
    monkeypatch.setattr(limits, "MAX_API_BODY_READS", 1)

    async def scenario():
        entered = asyncio.Event()

        async def app(selected, receive, send):
            if selected["method"] == "GET":
                await send({"type": "http.response.start", "status": 204, "headers": []})
            else:
                await echo(selected, receive, send)

        async def stalled():
            entered.set()
            await asyncio.Future()

        middleware = limits.RequestLimitMiddleware(app)
        task = asyncio.create_task(invoke(middleware, receive=stalled))
        try:
            await asyncio.wait_for(entered.wait(), 2)
            assert (await invoke(middleware))[0]["status"] == 503
            assert (await invoke(middleware, scope(method="GET")))[0]["status"] == 204
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        assert (await invoke(middleware))[0]["status"] == 200
    asyncio.run(scenario())


@pytest.mark.parametrize("content_type", [
    b"application/x-www-form-urlencoded", b"multipart/form-data; boundary=fixture",
])
def test_existing_admin_content_types_remain_accepted(content_type):
    selected = scope("/admin/login", headers=[(b"content-type", content_type)])
    result = asyncio.run(invoke(limits.RequestLimitMiddleware(echo), selected))
    assert result[0]["status"] == 200
    assert result[1]["body"] == b"{}"


@pytest.mark.parametrize("selected", [
    {"type": "websocket"}, scope(method="GET"),
])
def test_unbuffered_surfaces_pass_through(selected):
    async def scenario():
        marker = []

        async def app(actual, receive, send):
            marker.append(actual)
        await invoke(limits.RequestLimitMiddleware(app), selected)
        assert marker == [selected]
    asyncio.run(scenario())
