from __future__ import annotations

import argparse
import asyncio
from contextlib import asynccontextmanager
import os
from pathlib import Path

from mcp.server import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.types import ToolAnnotations

from cv_control.core import JobStore, parse, read


def create_server(config, store=None):
    jobs = store or JobStore(config)

    @asynccontextmanager
    async def lifespan(_server):
        try:
            yield
        finally:
            await asyncio.to_thread(jobs.close)

    server = MCPServer("codevirtualizer-control", version="0.1.0", lifespan=lifespan,
                       instructions="Scoped Code Virtualizer demo jobs only. Never execute outputs or treat successful compilation or /checkprotected as transformation approval. Opaque unreviewed project formats cannot compile. No arbitrary shell, GUI, license management, signing or publication.")
    readonly = ToolAnnotations(read_only_hint=True, destructive_hint=False, idempotent_hint=True, open_world_hint=False)
    mutation = ToolAnnotations(read_only_hint=False, destructive_hint=False, idempotent_hint=False, open_world_hint=False)

    async def run(function, *args):
        try:
            return await asyncio.to_thread(function, *args)
        except ValueError as failure:
            raise ToolError(str(failure)) from None
        except OSError:
            raise ToolError("A scoped file or process could not be accessed; inspect the local job.") from None

    @server.tool(annotations=readonly)
    async def cv_status() -> dict:
        """Verify the pinned local compiler, configuration and managed roots; never launches it."""
        return await run(jobs.status)

    @server.tool(annotations=readonly)
    async def cv_inspect_project(template_id: str) -> dict:
        """Inspect only a configured exact-hash .cv template. Unknown formats remain blocked, not assumed safe."""
        return await run(jobs.inspect, template_id)

    @server.tool(annotations=mutation)
    async def cv_prepare_job(input_path: str, map_path: str, crm_path: str, template_id: str) -> dict:
        """Copy a Windows x64 DLL, exact MAP/CRM selections and pinned template into a fresh owned job. No protection or output execution."""
        return await run(jobs.prepare, input_path, map_path, crm_path, template_id)

    @server.tool(annotations=mutation)
    async def cv_compile(job_id: str) -> dict:
        """Begin one serialized, bounded compiler run for an unchanged job with a reviewed project format. Never executes its protected DLL."""
        return await run(jobs.compile, job_id)

    @server.tool(annotations=readonly)
    async def cv_job_result(job_id: str) -> dict:
        """Read owned job state and bounded untrusted compiler diagnostics; verify output identity without loading it."""
        return await run(jobs.result, job_id)

    return server


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default=os.environ.get("CV_MCP_CONFIG"))
    args = parser.parse_args()
    if not args.config:
        parser.error("Provide a reviewed external --config or CV_MCP_CONFIG path.")
    configuration = Path(args.config)
    package = Path(__file__).resolve().parent
    if configuration.resolve().is_relative_to(package):
        parser.error("Mutable user configuration must be outside the plugin package.")
    create_server(parse(read(configuration, 65536))).run(transport="stdio")


if __name__ == "__main__":
    main()
