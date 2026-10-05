# Code Virtualizer Control

Five MCP tools for a local, pinned Code Virtualizer **demo** installation:

- `cv_status`: compiler hash and configured roots; no execution.
- `cv_inspect_project`: inspect one configured template identity.
- `cv_prepare_job`: copy a DLL/MAP/CRM/template into a new UUID directory.
- `cv_compile`: one bounded asynchronous compile; refuses unsafe or unknown project formats.
- `cv_job_result`: identity-checked job metadata and a bounded untrusted log tail.

This integration never launches produced DLLs. It has no arbitrary shell or GUI action, license operation, installer, signing authority, secret access, or publication command. The package contains no proprietary tool binaries. A compiler success code and `/checkprotected` are auxiliary observations, **not** proof that selected functions were transformed or that a final release is approved.

## Current project gate

The first GUI-created `.cv` format has not yet been reviewed. Exact-hash `opaque` templates may be inspected and copied; **compilation is blocked**. A format-specific review must establish every setting, external reference and hook before enabling compilation. Do not bypass this with a config flag. A hash identifies a project; it does not prove the project is safe.

## Configure

The external per-user configuration is `~/.local/share/codevirtualizer-mcp/config.json`. Start from `config.example.json`, create the dedicated jobs directory, and review the exact allowed input roots. Do not use a drive root or the complete home directory. A template entry has the shape `"reviewed-id": {"path": "absolute/template.cv", "sha256": "exact lowercase SHA-256", "format": "opaque"}`.

Keep configuration outside this source package. Changes require restarting the server. The sole compiler identity is version 3.2.4.35, SHA-256 `e80ca1ca979cdc455cbe7b9d1ee1ee1661eea74127f7d12ad0d34cd47450cdbd`; upgrades require source review. The launcher uses the existing dedicated `~/.local/share/vmprotect-mcp/venv/Scripts/python.exe` runtime with MCP 2.2.0. Nothing is installed automatically.

Direct server command: `python server.py --config ABSOLUTE_EXTERNAL_CONFIG.json`.

## Job boundaries

Inputs must be regular, nonlinked files within configured roots. The native input is a Windows x64 DLL. MAP and CRM basenames match; CRM entries use exact MAP symbols in documented `|symbol|1|1` form. Files, selected symbols, the configuration, compiler and template are pinned in the job. Input limits are 128 MiB DLL, 16 MiB MAP, 1 MiB CRM and 2 MiB template.

Compiler arguments are constructed internally with `/protect`, `/inputfile`, `/outputfile` and `/shareconsole`; neither shell syntax nor caller-supplied options are accepted. Each job has a fresh, separate output directory. A work-root lock serializes compilation across server instances. A stale lock after a crash requires operator review; jobs are never retried automatically. Deadline is configurable within 10–1800 seconds. Logs retain at most 64 KiB, with an 8 KiB response tail; process environment omits inherited tokens. On shutdown or timeout the owned compiler process is stopped. No output is ever passed as an executable command.

Documented exits: 0 success, 1 invalid project, 2 inaccessible input, 3 no marked code, 4 malformed block, 5 protection error, 6 output failure, 7 incompatible PE. Unknown exits fail. After success, the same pinned compiler may inspect the produced DLL using `/checkprotected`; output hash/PE identity is rechecked. Final state is `compiled-not-approved`. If a descendant retains the compiler's output pipe after the owned process stops, the job fails and retains its admission lock for operator review; it cannot silently start another compiler.

## Demo and composition

Oreans lists light virtualization for the demo, with mutation, string encryption and VM compression unavailable. The installed SDK/examples do not remove those restrictions. The vendor recommends Code Virtualizer first when combining protectors. Retain its VM section/relocations and obtain fresh transformation, JNI, lifecycle and performance evidence for the final combined DLL. A second pass on an entry stub is not proof that both products independently virtualized an entire original function.

Primary references: [CLI](https://www.oreans.com/help/cv/hm_protecting-through-the-command.htm), [MAP/CRM](https://www.oreans.com/help/cv/hm_faq_is-any-possibility-to-say-cv-t.htm), [combining protectors](https://www.oreans.com/help/cv/hm_faq_im-trying-to-get-code-virtual.htm), [demo restrictions](https://www.oreans.com/downloads.php/CodeVirtualizer.php).

## Synthetic verification

Run `python -m unittest discover -s tests -v` with the dedicated MCP runtime. Tests use temporary native-header fixtures, mocked compiler processes and in-memory MCP dispatch. They neither start Code Virtualizer nor load a generated native library. Actual CLI/project compatibility remains a separate, explicitly reviewed test.
