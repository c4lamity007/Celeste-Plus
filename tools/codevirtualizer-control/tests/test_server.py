import json
import unittest

from mcp import Client
from server import create_server


class FakeStore:
    def __init__(self):
        self.calls = []
    def status(self):
        self.calls.append("status")
        return {"output_execution_supported": False}
    def inspect(self, template_id):
        if template_id != "reviewed":
            raise ValueError("Unknown template")
        return {"compile_allowed": False}
    def prepare(self, *args):
        self.calls.append(("prepare", args))
        return {"job_id": "owned", "state": "prepared"}
    def compile(self, job_id):
        if job_id != "owned":
            raise ValueError("Unknown job")
        raise ValueError("Unknown project format remains blocked")
    def result(self, job_id):
        if job_id != "owned":
            raise ValueError("Unknown job")
        return {"job_id": "owned", "state": "prepared", "output_executed": False}
    def close(self):
        self.calls.append("close")


class ProtocolTests(unittest.IsolatedAsyncioTestCase):
    async def test_exact_tools_annotations_and_status_do_not_execute(self):
        store = FakeStore()
        async with Client(create_server({}, store=store)) as client:
            listed = (await client.list_tools()).tools
            self.assertEqual({"cv_status", "cv_inspect_project", "cv_prepare_job", "cv_compile", "cv_job_result"},
                             {t.name for t in listed})
            for tool in listed:
                self.assertFalse(tool.annotations.open_world_hint)
                self.assertFalse(tool.annotations.destructive_hint)
                self.assertEqual(tool.name not in {"cv_prepare_job", "cv_compile"}, tool.annotations.read_only_hint)
            result = await client.call_tool("cv_status")
            self.assertFalse(result.is_error)
        self.assertEqual(["status", "close"], store.calls)

    async def test_invalid_jobs_and_unknown_template_cannot_compile(self):
        async with Client(create_server({}, store=FakeStore())) as client:
            for name, params in [("cv_compile", {"job_id": "../../foreign"}),
                                 ("cv_compile", {"job_id": "owned"}),
                                 ("cv_inspect_project", {"template_id": "foreign"})]:
                result = await client.call_tool(name, params)
                self.assertTrue(result.is_error)
            result = await client.call_tool("cv_job_result", {"job_id": "owned"})
            self.assertFalse(result.is_error)
            data = result.structured_content or json.loads("".join(c.text for c in result.content if c.type == "text"))
            self.assertFalse(data["output_executed"])


if __name__ == "__main__":
    unittest.main()
