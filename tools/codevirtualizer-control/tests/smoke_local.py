"""Read-only check of the actual local stdio server; no compiler or output runs."""
import asyncio
import json
import os
from pathlib import Path
import sys

from mcp import Client, StdioServerParameters


async def main():
    package = Path(__file__).resolve().parents[1]
    config = Path.home()/'.local/share/codevirtualizer-mcp/config.json'
    environment = {key:value for key,value in os.environ.items() if key.upper() in {
        'SYSTEMROOT','WINDIR','TEMP','TMP','USERPROFILE','LOCALAPPDATA','APPDATA','PROGRAMDATA'}}
    environment['PYTHONDONTWRITEBYTECODE'] = '1'
    parameters = StdioServerParameters(command=sys.executable,
        args=[str(package/'server.py'),'--config',str(config)],env=environment)
    async with asyncio.timeout(20):
        async with Client(parameters) as client:
            tools = (await client.list_tools()).tools
            expected = {'cv_status','cv_inspect_project','cv_prepare_job','cv_compile','cv_job_result'}
            assert {tool.name for tool in tools} == expected
            response = await client.call_tool('cv_status')
            assert not response.is_error
            data = response.structured_content or json.loads(''.join(c.text for c in response.content if c.type=='text'))
            assert data['output_execution_supported'] is False
            assert data['protection_verified'] is False
            assert data['executable_sha256']=='e80ca1ca979cdc455cbe7b9d1ee1ee1661eea74127f7d12ad0d34cd47450cdbd'
            print(json.dumps({'tools':sorted(expected),'configuration_sha256':data['configuration_sha256'],
                'templates':data['templates'],'compiler_started':False,'output_executed':False}))


if __name__=='__main__':
    asyncio.run(main())
