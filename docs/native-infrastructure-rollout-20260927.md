# Native manifest infrastructure rollout — 2026-09-27

**Historical stage-one record: deployed and verified at version 128.** The
scope and results below describe the initial additive deployment. It was
followed by the full backend rollout and Java repair at version 130. The exact
protected launcher is now registered at native sequence 1, and the user confirmed
“Loads successfully” after a full Minecraft restart. GitHub publication is ready
but still draft at this record's freeze. See the [final run record](protection-run-20260927.md)
for final hashes and the resolved injection regression.

## Deployed scope

The staging source was built from the captured live service, then changed only
to add the native manifest router, its optional publication public key setting,
and the additive `native_release_policies` table. Three existing files changed;
the remaining 17 captured files, including the old Plus delivery logic, DPoP,
generic signer and production entrypoint, remained byte-identical.

The image inherits the previous deployed image and copies the staged app into
it. This preserves the actual artifact trees, Python environment, service user
and entrypoint. The generic repository Dockerfile was not used to rebuild the
service with an empty release directory.

| Deployment | Value |
| --- | --- |
| Fly app | `qb0f90cf11f4d1d52` |
| Previous image tag | `deployment-01M3GHAXYC3ESZNS30B9WVAPKS` |
| New image tag | `deployment-01M3HM66803DKT17NVJ7D037YX` |
| New image digest | `sha256:c88516096fe44ed7d4ee4322c26530de6a5c5cb47daf8a96261263b95f4191f6` |
| Machine / version | `85d426c4429798` / 128 |
| Health checks | 1/1 healthy |
| App file verification | All 19 deployed file hashes match the tested staging copy |
| Existing artifacts | All 13 files, 64,308,523 bytes, unchanged |
| Native policies | Zero registered rows |

The Standard artifact directory remains
`/srv/auth/celeste/celeste-20260922-bootstrap-verifyfix-01`; the Plus directory
remains `/srv/auth/celeste-plus/celeste-plus-20260926-anchor-packets-01`.
The existing installer channel, identities, service response-signing key and
single authoritative SQLite volume were preserved.

## Tests and live checks

Local tests exercised the captured deployed source plus the additive patch:
**210 passed, one existing Windows symlink-permission skip** in 57.95 seconds.
The focused native/compatibility run passed 24 tests. Raw legacy Plus requests
without the new catalog nonce still obtained the old manifest and all four
artifact parts; Standard delivery passed. Public native manifest tokens were
rejected on all eight tested Standard/Plus protected resource paths. The tests
used disposable licenses, keys and files.

| Live request | Observed result |
| --- | --- |
| `/healthz` | 200 |
| Native manifest without request nonce | 400 |
| Native manifest with fresh valid nonce | 503, native release is not registered |
| Forged native publication | 401 |
| Existing Plus manifest without authentication | 401 |

The native endpoint keeps its strict nonce and separate publication-signature
checks. The existing Plus catalog protocol was preserved at this initial stage.
The later full backend rollout enabled the mandatory catalog nonce and other
auth changes with the matched new client; old Plus clients must update.

## Publication authority

A separate Ed25519 publication identity was created and its real Windows
current-user DPAPI encrypt/decrypt round trip passed. Only its public half is
configured on Fly as the public setting `CELESTE_NATIVE_RELEASE_PUBLIC_KEY`:

- Public key: `k-1MUgu_UW4jbwwY98vlmjTMfBaBTEIyTXOcHMZscfc`.
- Public-key SHA-256: `304cd202609df65a35d9bddb929563cc14cb70079f43fe62158c5b6354afb65e`.

The private identity is encrypted outside Git and was not placed on the server.
The helper prints public metadata only and refuses overwrite/implicit rotation.
See [key helper usage](../tools/packaging/README.md#separate-native-publication-identity).
This authority is not a Windows publisher certificate or a protection approval.

## Evidence and rollback

Ignored local evidence is under `build/fly-rollout-20260927/`:

- `snapshot.json` and `deployed-source/`: exact pre-deployment source/config snapshot.
- `native-infrastructure/`, its patch, provenance and verification records: tested additive staging copy.
- `live-backup.json`: artifact inventory and pre-deployment SQLite backup result.
- `live-verification.json`: deployed source hashes, unchanged artifacts and HTTP results.

Live verification SHA-256:
`109b9111a192d9cb7915689f62a9a5ad522d229497eb45a41771f1b5f70fb013`.
Local staging verification SHA-256:
`1175fefbe190ee10fba5f82febecb7743f619f2c1f24bb4f817a54f6d459ec63`.

The consistent server backup at
`/data/backups/native-infrastructure-20260927/auth.sqlite3` passed SQLite's
integrity check. Roll back application code to the retained previous image if
needed while preserving current customer/license/revocation data; the additive
table can remain. Do not restore an older database merely to reverse the code
update. Retain the existing artifact paths and exact bytes.

The exact-artifact verifier has since verified seven reviewed functions reaching
VM decoding/dispatch. The user explicitly accepted an Authenticode-unsigned
Windows release with Unknown publisher and server verification. This does not
waive protection evidence or native allowlist registration, and does not make
the executable Windows-signed. The exact protection tuple is now approved.
The final receipt, exact executable registration and coordinated payload/backend
rollout subsequently passed. Version 130's live verification is
`build/fly-rollout-20260927/full-fix-live-verification.json`, SHA-256
`307ba96fe5121e59d620fff0b869d25e91f964e668200ba59631e8d16af4be6d`.
It matches all deployed source and 23 artifact files (18 preserved and five
repaired files). The user's successful Load confirmation is separate from these
unauthenticated service probes. No game performance result is claimed.
