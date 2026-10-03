"""Actual module/beta guards retire before releasing their two transfer slots."""
import asyncio
import inspect
import threading

import anyio
import pytest
from fastapi import HTTPException, Request

import app.artifact_transfer as transfer
import app.module_delivery as module_delivery
import app.beta_delivery as beta_delivery
from test_dpop import env
from test_protocol_v2 import v2
from test_module_delivery import modules, authorize, request as module_request
from test_artifact_streaming import request_for


def prepare_response(e,kind):
    if kind=='module':
        capability=authorize(e)['capabilities'][0]
        _,headers,_=module_request(e,capability['url'],capability=capability['capability'])
        path=capability['url']
        template='/v1/modules/artifacts/{release_id}/{artifact_id}'
    else:
        _,_,path,_,headers=request_for(e)
        template='/v1/beta/artifacts/{release_id}/{artifact_id}'
    endpoint=next(route.endpoint for route in e.app.routes if getattr(route,'path','')==template)
    request=Request({'type':'http','http_version':'1.1','method':'GET','scheme':'https','path':path,
        'raw_path':path.encode(),'query_string':b'','server':('testserver',443),'client':('testclient',123),
        'headers':[(name.lower().encode(),value.encode()) for name,value in headers.items()]})
    release_id,artifact_id=path.rsplit('/',2)[-2:]
    if inspect.iscoroutinefunction(endpoint):
        return asyncio.run(endpoint(request=request,release_id=release_id,artifact_id=artifact_id))
    return endpoint(request=request,release_id=release_id,artifact_id=artifact_id)


@pytest.mark.parametrize('kind',['module','beta'])
@pytest.mark.parametrize('mode',['cancel','timeout','disconnect'])
def test_cancelled_route_holds_slot_until_actual_guard_worker_finishes(modules,monkeypatch,kind,mode):
    target=module_delivery if kind=='module' else beta_delivery
    entered=threading.Event();release=threading.Event();finished=threading.Event()
    real=transfer.run_authorization_guard
    async def blocked(check):
        def work():
            entered.set()
            try:
                assert release.wait(3), 'test did not release guard worker'
                return check()
            finally:finished.set()
        await real(work)
    monkeypatch.setattr(target,'run_authorization_guard',blocked)
    response=prepare_response(modules,kind)
    slots=response._slots
    assert slots.acquire(False) and not slots.acquire(False)
    if mode=='timeout':monkeypatch.setattr(transfer,'TRANSFER_TIMEOUT_SECONDS',.03)
    body=[]
    async def exercise():
        disconnect=asyncio.Event()
        async def receive():
            await disconnect.wait()
            return {'type':'http.disconnect'}
        async def send(message):
            if message['type']=='http.response.body':body.append(message.get('body',b''))
        scope={'type':'http','asgi':{'spec_version':'2.3' if mode=='disconnect' else '2.4'}}
        task=asyncio.create_task(response(scope,receive,send))
        assert await asyncio.to_thread(entered.wait,2)
        try:
            if mode=='cancel':task.cancel()
            elif mode=='disconnect':disconnect.set()
            await asyncio.sleep(.06)
            assert not task.done() and not finished.is_set()
            assert not slots.acquire(False), 'cancelled response released a still-running worker slot'
        finally:
            release.set()
        if mode=='cancel':
            with pytest.raises(asyncio.CancelledError):await task
        elif mode=='timeout':
            with pytest.raises(TimeoutError):await task
        else:await task
        assert finished.is_set() and not any(body)
        assert slots.acquire(False) and not slots.acquire(False)
        slots.release();slots.release()
    asyncio.run(exercise())


@pytest.mark.parametrize('kind',['module','beta'])
def test_actual_guard_not_starved_by_full_admission_worker_pool(modules,kind):
    response=prepare_response(modules,kind)
    async def exercise():
        limiter=anyio.to_thread.current_default_thread_limiter()
        original=limiter.total_tokens
        limiter.total_tokens=1
        await limiter.acquire()
        seen=[]
        async def receive():await asyncio.Event().wait()
        async def send(message):
            if message['type']=='http.response.body':seen.append(message.get('body',b''))
        try:
            await asyncio.wait_for(response({'type':'http','asgi':{'spec_version':'2.4'}},receive,send),2)
            assert sum(map(len,seen))==int(response.headers['content-length'])
        finally:
            limiter.release();limiter.total_tokens=original
        assert response._slots.acquire(False) and response._slots.acquire(False) and not response._slots.acquire(False)
        response._slots.release();response._slots.release()
    asyncio.run(exercise())


@pytest.mark.parametrize('kind',['module','beta'])
def test_product_block_during_actual_stream_withholds_final_tag(modules,monkeypatch,kind):
    target=module_delivery if kind=='module' else beta_delivery
    original=target.encrypt_chunks
    def revoked(*args):
        pieces=list(original(*args))
        yield from pieces[:-1]
        with modules.app.state.database.connect() as connection:
            connection.execute("INSERT INTO security_product_blocks VALUES(?,'celeste_plus','test-block',1)",(modules.uid,))
        yield pieces[-1]
    monkeypatch.setattr(target,'encrypt_chunks',revoked)
    response=prepare_response(modules,kind)
    async def exercise():
        seen=[]
        async def receive():await asyncio.Event().wait()
        async def send(message):
            if message['type']=='http.response.body':seen.append(message.get('body',b''))
        with pytest.raises(HTTPException) as denied:
            await response({'type':'http','asgi':{'spec_version':'2.4'}},receive,send)
        assert denied.value.status_code==403
        assert sum(map(len,seen))<int(response.headers['content-length'])
        assert response._slots.acquire(False) and response._slots.acquire(False) and not response._slots.acquire(False)
        response._slots.release();response._slots.release()
    asyncio.run(exercise())
