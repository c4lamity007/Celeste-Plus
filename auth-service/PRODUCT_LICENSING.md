# Product licensing contract

The dashboard requires a deliberate **Celeste**, **Celeste+**, or **Both** selection. The `license_entitlements` rows enforce access; labels, numeric UID, and key prefixes convey no privilege. One license row can have both entitlements. No licenses or entitlements are created automatically during migration or startup.

Enrollment and installer starts use `product: celeste` or `celeste_plus` (omission defaults to Celeste for existing normal clients). Challenges bind that product into the existing opaque signed message. Installation certificates remain generic device identities. Both launchers can reuse a shared saved activation for a Both key at one device slot. An optional `license_key` on installer/start must match the selected device's license hash; mismatch returns 401 before creating a challenge or updating the device. A missing entitlement returns 403.

Runtime requests infer the product from the exact normal release or exact Plus catalog. An explicit request product must match that inference. Client, installer, stage, session, and Plus manifest grants carry a signed product. All artifact and cloud authorization rechecks the current database entitlement. The existing session transcript is unchanged; the Plus catalog suffix already binds its runtime identity. `CELESTE_PLUS_ENABLED=true` enables the Plus release configured by `CELESTE_BETA_RELEASE_DIR`. There is no Owner UID setting or exemption.

From the deployed service working directory, operator-only key creation is available with:

```sh
python -m app.license_operator create --product both --label 'Customer' --max-devices 1
```

This is an explicit mutation and prints the full new key once. Do not run it while merely validating a deployment, and do not redirect its output to shared logs. The dashboard also displays a newly created key once.

The requested clean-slate rollout uses a separate, explicit operation. First stop admission or use a maintenance interval so the reviewed state remains stable, then obtain the sanitized plan:

```sh
python -m app.license_operator preview-reset --database /data/auth.sqlite3
```

Review its exact counts and plan digest. Apply only with the explicit user authorization and a new private backup filename in an existing directory:

```sh
python -m app.license_operator reset --database /data/auth.sqlite3 --confirmation DELETE-ALL-LICENSES --expected-plan-sha256 REVIEWED_DIGEST --backup /data/PRIVATE_NEW_BACKUP.sqlite3
```

Apply holds an immediate transaction, verifies the reviewed plan, creates a SQLite backup without overwriting anything, verifies its integrity and identity, archives cloud metadata and security/license associations, and removes all current licenses, activations, challenges, nonces, installer ticket uses, and entitlements. Existing cloud image files, audit/security history, admin sessions, and the UID sequence remain. Any failure rolls back the reset; an already verified backup remains available. The operation supports the existing schema before the new entitlement table is deployed. It never runs from app startup or an HTTP route.

After apply, verify all active-catalog counts are zero and the dashboard is empty. Deploy with the product modules included and the Plus availability flag; create no sample keys unless separately requested. Old encrypted local activation files become unusable because their device identities no longer exist, and new keys must successfully enroll before replacing local activation state.

Offline verification only: no live reset, license creation, authentication, downloads, installation, or deployment was performed by the backend implementation task.
