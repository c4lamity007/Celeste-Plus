# Modular Plus rollout preparation

Status: local tooling and fixtures only. No deployment, live QA assignment, or production approval is made by this document.

## Reviewed tools and authority

Use tools/stage_modular_server.py for the independent-module release. The old beta-lab/release-package/stage_server.py is not a complete modular deployment workflow.

The new tool runs offline. It verifies the protected Java receipt, resident/module catalog binding, exact artifact bytes, loader publication signatures, complete backend file inventory, and retained rollback bytes. It emits one canonical deployment-input.json covering every staged file. An aggregate approval from the existing service response-signing authority binds that exact document. No signing private key enters the stager, context, or repository.

Native executable approval continues to use the existing separate CI publication authority through tools/packaging/register_native_release.py. Preserve all currently approved loader metadata in the new publication, including the selected old loader; its normal retention option does this. A changed loader requires a new policy with a greater sequence. The historical signed publication is retained as evidence, not replayed at rollback. Publications have a short submission window; prepare/register fresh native approval through the existing controlled workflow at the appropriate rollout step.

When the existing approved launcher is compatible, keep its exact bytes and signed policy. Reuse additionally requires a fresh server-signed current native manifest, bound to a 24-byte request nonce, confirming the same policy sequence and complete approved hash list. This branch performs no native registration and copies no replacement executable into the image. It cannot reuse a historical policy after the server has advanced to a different sequence.

## Operator prerequisites

- Complete protected candidate: beta-release.json, resident bootstrap/payload parts, module-release.json, every modules/<opaque>.jar, and the final exact protection result receipt.
- Fresh final native/VMP protection evidence and matching loader binary, plus its signed native publication.
- Existing signed production beta manifest, exact prior host artifacts and loader, historical native policy, immutable deployed image digest, and reviewed production fly.toml.
- An explicitly verified enrolled installation ID for owner QA. Account name or license key alone is insufficient. The stager rejects an empty QA assignment.
- Persistent CELESTE_MODULE_KEY_ENCRYPTION_KEY (or its existing _FILE secret reference), 32 decoded bytes, installed through the existing secret-management workflow. Do not generate an ephemeral startup key. Existing signing, session, pepper, and administrator credentials remain server secrets.
- One backend configuration at a time during assignment/cutover: drain the old instance before routing requests to a changed release assignment. No mixed old/new assignment fleet.

No secret value or administrator password hash belongs in fly.toml, a staging plan, shell output, or the build context. Secret-file paths are references and may remain in server configuration.

## Pinned-image preparation

The preferred plan for the current service uses base_image equal to rollback_image, both the exact immutable deployed registry digest. base_context is the reviewed prior thin Docker context; base_inventory pins every included file with SHA-256 and size. The image retains the existing Standard release, prior Plus release, and native files.

The tool installs the complete new backend under /srv/auth/module-backend with an explicit entrypoint and working directory. This avoids old backend files shadowing the reviewed app. The candidate resident and module files go into an isolated /srv/auth/module-candidates/<release> directory.

Plan schema: celeste-plus-modular-stage-plan-1. Required inputs:

- mode: qa or cutover.
- base_context, base_inventory, base_inventory_sha256, base_image.
- candidate_dir, protection_receipt, protection_receipt_sha256.
- loader_binary, native_publication, native_publication_sha256.
- rollback_loader_binary, rollback_native_publication, rollback_native_publication_sha256.
- rollback_manifest, rollback_manifest_sha256.
- rollback_image, rollback_contract: legacy-full-client or independent-modules.
- qa_installations: explicit installation IDs for qa; empty at public cutover.
- For identical-loader reuse only: current_native_manifest, current_native_manifest_sha256, current_native_request_nonce. native_publication and rollback_native_publication must be the identical signed policy; loader_binary and rollback_loader_binary must be byte-identical.

Paths point to existing reviewed local inputs. All input digests are computed from exact file bytes. Output must be a fresh child of the Plus checkout's build/module-qa directory. The pinned plan itself is the review boundary; do not invent installation IDs, manifests, signatures, or final protection receipts.

Preparation commands (replace placeholders only after final inputs exist):

    python tools/stage_modular_server.py prepare --plan PLAN.json --plan-sha256 REVIEWED_PLAN_SHA256 --output build/module-qa/CANDIDATE

This outputs prepared-unsigned-not-deployable and the exact aggregate claims for the existing controlled service-key signing workflow. Keep signing inside that controlled environment; do not export its private key.

    python tools/stage_modular_server.py seal --context build/module-qa/CANDIDATE --approval APPROVAL.json
    python tools/stage_modular_server.py verify --context build/module-qa/CANDIDATE

Sealing only verifies and records the approval. It does not deploy. Changed files, extra files, wrong signing authority, mismatched native policy, or mixed host/module identities fail verification.

## QA sequence

1. Prepare and seal the complete candidate against the actual deployed base image/config.
2. Verify/install the persistent module KEK through server secret management without revealing it.
3. If a changed native loader is required, register the fresh policy through the existing controlled publication workflow, preserving every old approved loader. For the compatible byte-identical loader, verify the fresh signed current-policy proof and keep the existing policy without registration.
4. Deploy the sealed context to the existing approved app using the controlled replacement process. Confirm readiness and the default Plus/Standard identities before starting QA.
5. Use the verified compatible existing loader, or the separately verified candidate loader if required, on the explicitly assigned owner installation. Fully restart Minecraft before loading a different resident release: an existing JVM correctly refuses replacement of its pinned Bootstrap A/release/catalog. The ordinary public download stays on its prior loader during QA.
6. Run actual game QA using normal login/entitlement checks. Verify disabled class absence, toggling, mixed modules, worker/render cleanup, lease expiry and disconnect behavior. Report actual results separately from fixtures.
7. Only after QA passes, prepare and seal a cutover context. It sets the default resident/module pair, enables CELESTE_PLUS_REQUIRE_MODULE_DELIVERY, clears the QA assignment, and selects the verified public loader (unchanged for identical-loader reuse). Revalidate the then-current deployed base first.

The qa assignment is resolved from the authenticated installation for catalogs, bootstrap, runtime auth, heartbeats, cloud configuration, and module routes. A request cannot choose a different release. Unassigned users and Standard remain on their existing release.

## Rollback

Retain the exact previous image/config, signed host manifest and artifacts, loader bytes, and signed native policy evidence under evidence/. These files are excluded from the image build context; the aggregate approval covers them.

For first cutover, an inactive signed legacy host is a valid emergency rollback contract. Restore the approved prior immutable image and exact prior configuration as one coordinated replacement and drain the modular instance. Keep the newer native policy that still contains the old loader. Do not submit the older publication sequence and do not add a per-request legacy fallback.

For a later modular rollback, restore its exact matched resident/module catalog and keep mandatory modular delivery enabled. Never combine one release's host with another's modules.

The persistent database may retain forward-compatible new tables; they confer no access when the old backend is restored. Keep persistent key storage and database backups intact. A subsequent new attempt rechecks the deployed image, config, native sequence, final artifacts, and QA assignment.

## Local verification

- tools/qa/test_stage_modular_server.py: disposable offline staging, image inheritance, signature, byte pinning, secret rejection, and rollback fixtures.
- auth-service/tests/test_module_qa_assignment.py: exact installation scoping and matching release checks.
- auth-service/tests/test_module_delivery.py, test_protocol_v2.py, test_beta_delivery.py, test_artifact_streaming.py: authorization, per-PID replacement, stream revocation, and existing product contracts.

These checks do not establish actual in-game behavior or authorize a production rollout.

## Ciphertext storage and maintenance

Personalizations are created lazily, once per account, catalog digest, and used module. Repeated toggles and renewals reuse that row. There is no automatic old-catalog pruning or database size ceiling in version 1; changing releases alone does not remove prior rows. Deleting the owning license cascades its rows through the existing database foreign key.

For an approved JAR of B bytes, the stored ciphertext is exactly B + decoy_bytes + metadata_json_bytes + 62 bytes. The current implementation caps decoy_bytes at min(floor(B/10), 12,348), including decoy framing. Each row additionally stores its manifest, 60-byte wrapped content key, opaque watermark, identifiers, and SQLite/index overhead. SQLite WAL and backup storage are additional. Different installations of the same account reuse the account's container for that release; every activation still checks the individual installation and session.

Capacity should therefore be planned from the sum of actually used modules per account across retained catalogs, not from concurrently enabled modules. There is no artificial limit on enabled modules. The protected candidate inventory contains 86 modules totaling 11,333,649 JAR bytes. Applying the current container framing gives 12,042,546 ciphertext bytes (11.485 MiB) per account that uses all 86 modules in that catalog, plus 65,440 manifest bytes and 5,160 wrapped-key bytes before row/index/page overhead. At 10,000 such accounts, ciphertext alone is 112.155 GiB per retained catalog. Accounts using fewer modules consume less.

This is sizing for the candidate module-preparation.json with SHA-256 ea5592d66e4abe07109cc1bb2ac567e0ac8ce826bd446115b995a02298a5df3e, not a claim that this release has been deployed. Recalculate if final artifact metadata changes.

Read-only per-catalog inventory, run through the existing trusted database connection without printing account identifiers, keys, watermarks, or ciphertext:

    SELECT catalog_sha256, COUNT(*) AS module_rows,
           COUNT(DISTINCT license_uid) AS accounts,
           SUM(length(ciphertext)) AS ciphertext_bytes,
           SUM(length(manifest_json) + length(wrapped_key)) AS metadata_and_wrapped_key_bytes
    FROM module_personalizations
    GROUP BY catalog_sha256;

Before release retention grows without bound, use this narrow operator maintenance procedure:

1. Back up the database together with its existing KEK through the normal protected backup workflow.
2. Record an explicit retain set containing every active default catalog, staged/assigned QA catalog, and approved rollback catalog. Use the canonical catalog digest used by the service, not the hash of arbitrarily formatted JSON. Retain their signed host/module artifacts separately as well.
3. Mark other catalogs retired only after all backend instances stop selecting them. Wait at least five minutes after the final instance is drained. This exceeds both the 90-second maximum capability and 30-second protected artifact-transfer timeout.
4. Produce a dry-run count/byte report for only the explicitly retired catalogs. Never infer retirement from a row's created_at: an old row may still back a current capability.
5. During a controlled maintenance window, delete only the reviewed retired catalog rows in bounded transactions, initially at most 100 rows per transaction. Keep the current/QA/rollback retain set fixed throughout that operation. Stop if the observed active configuration or retirement record changes.
6. Verify the retained catalog counts and authorization health before closing maintenance. SQLite can reuse freed pages; shrinking the database file requires a separate planned checkpoint/vacuum with sufficient free disk and downtime. Do not vacuum inside request handling.

This is a proposed controlled pruning procedure, not an implemented automatic cleanup job. It does not weaken current-session authorization or delete signed rollback artifacts. If a previously retired catalog is ever approved again after pruning, fresh authorization creates new personalized containers; stale cached ciphertext will not match the new signed manifest and must be replaced.
