# Windows loader Ultra profile

This profile is separate from the historical Virtualization/type-0 approvals.
Use `run_vmprotect_mcp.ps1 -Profile auth-v2-14 -ProjectTemplate <this .vmp>`
with a fresh reviewed native candidate. A new output requires new actual
coverage evidence and runtime checks; this template is not a protection receipt.

## Observed settings

| Setting | Value | Evidence |
| --- | --- | --- |
| Selected procedures | The 14 `auth-v2-14` aliases | Exact alias inventory; no addresses in this file |
| Procedure mode | Ultra (Mutation + Virtualization), XML `CompilationType="2"` | Registered GUI and bundled manual enum |
| VM complexity | 100% | Registered GUI displays the supplied value |
| VM version / instances | Default | Preserved existing settings |
| Memory / resource protection / packing | Yes | Observed GUI; retained from the prior project |
| Import protection | Yes | Enabled through the real scoped MCP GUI action |
| Debugger detection | User-mode + Kernel-mode | Observed No → User-mode → User-mode + Kernel-mode |
| Virtualization tools detection | Yes | Enabled through the real scoped MCP GUI action |
| Protection Options | `466688` | Actual GUI-saved mask; not calculated from undocumented bits |

The early TLS callback and timing sampler are excluded from the 14 selections.
UI/render work and bulk framework/cryptographic loops are not selected. Increased
complexity can increase output size; no universal performance optimum or complete
resistance to analysis is claimed. Import/packing/debugger/VM checks must still be
tested on the actual final binary and supported physical Windows environment.

## Provenance

Observed on 2026-09-27 with licensed VMProtect Lite 3.10.4.2668, executable
SHA-256 `138d140584e3d703c3592cc3cd15124b428daee75629e25710b65228103ff817`.
The installed official `Help/en.qch` hash is
`90e9c80c8466e6132bb3d28d17e0abf29d9e88c9ff986f84a64e0f9bbcee9e83`.
Relevant bundled pages are `project_options.htm`, `project_functions.htm`, and
`script_classes.htm`; see also the [official manual](https://vmpsoft.com/vmprotect/user-manual)
and [official scripting reference](https://vmpsoft.com/support/user-manual-v3/using-scripts/).

The successful managed options save is recorded privately in
`build/vmp-settings-20260927/strong-options-observation-07.json`, SHA-256
`f9d81b23a7ce6b7db8f12b37f08b180ab5ecc052d971202d76c54500c14c8287`.
That saved project hash is
`8b6527ba113f0ec87bdc1169591967ca2cbfb6ff7d9858e8259048ceb746c81b`.
The generic template hash is
`53dfd07a236dc65021327467243e87b8070758f80c6d2494f443798fc478876e`.
Export only replaces the two local input/output paths with generic filenames and
restores explicit `IncludedInCompilation="true"` attributes that this GUI omits as
its default. All aliases, modes and other attributes are preserved. Private MAPs,
addresses and machine paths remain outside Git.

The options extension was tested with 117 tests (115 passed, two platform skips)
and reinstalled as `vmprotect-control` version `0.2.0+codex.20260927222713`.
It saves an inspection-only job, validates that input/MAP and all other XML fields
are unchanged, and requires a fresh job before compilation. The compiler driver
still requires the exact Execute prompt to be declined and two stable PE/hash
observations. Neither GUI success nor that driver substitutes for the separate
artifact review.
