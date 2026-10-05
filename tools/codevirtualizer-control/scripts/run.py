"""Use the existing dedicated MCP runtime; never installs dependencies automatically."""
import os
from pathlib import Path

runtime = Path.home() / ".local/share/vmprotect-mcp/venv/Scripts/python.exe"
configuration = Path.home() / ".local/share/codevirtualizer-mcp/config.json"
server = Path(__file__).resolve().parents[1] / "server.py"
if not runtime.is_file() or not configuration.is_file():
    raise SystemExit("Reviewed Code Virtualizer configuration or dedicated MCP runtime is missing.")
os.execv(str(runtime), [str(runtime), str(server), "--config", str(configuration)])
