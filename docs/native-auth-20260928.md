# Native authentication payload — September 28, 2026

## Deployed release

`celeste-plus-20260928-native-auth-02` has completed protection, independent native review, final packaging and the matched live rollout. Existing Java string protection runs first. The native-only pass converts 16 selected methods: eight in `AuthServiceClient`, three in `DpopProofSigner`, and five in `AuthBootstrap`. Each class passes unselected-method parity; existing Java-protected helpers are retained. This is a selected-method conversion, not a claim that every method or entire class was virtualized.

All three final DLLs compiled with Virtualization (`CompilationType=0`), complexity 10 and two VM instances. Independent exact-byte reviews traced every selected entry to a VM opcode read, pointer advance, decode, rolling-key update and signed-offset dispatch, including computed branches. This confirms actual virtualization at those entries; it is not a full control-flow/semantic-equivalence proof or an unpatchability guarantee. The reviewed approval is bound to the final DLL, generated loader, custody and producer bytes. **Deployment is complete and verified.** The main Windows launcher remains the already published `31ce0fd052af152e5fe340847eece882bceeb11dd65414a70ac0452ab2dfd743`; its max-protection profile and native allowlist sequence 3 do not change.

| Frozen input | SHA-256 |
| --- | --- |
| Fresh checkpoint | `accc48898bca9fbcc9439d64b394fd753c0a5dda0639a97ebef4ebdb8fbdcb6d` |
| Runtime | `b62f0c30edbb4586e5bdaa28e081468e9bfd1077995c822d8f7a529c7a102311` |
| Java-protected merged payload | `33128d3a464cd62a5475a0658ff79dd555f69ab84c0d80bce001e27591a17598` |
| Native producer bundle | `b558f2e5f5741d5541ee80048052a95545708965103081b51856dd4732437369` |

## Final results

The successful second resume is under `build/auth-native-release-20260928-02-final-02`. It passed **41 final native JVM checks** (933 protocol assertions, 23 DPoP assertions and 40 lifecycle scenarios) and **15 Java continuation stages**, including three baseline and three protected cold-bootstrap checks. The scheduled-heartbeat case used the real 30-second timer. These are isolated artifact/fixture checks with synthetic identities and transport; no Minecraft UI, real subscription or live injection QA is claimed.

| Final evidence | SHA-256 |
| --- | --- |
| Final result | `58eec8fa3e719d79da6911de0ed86664c3d0d0173879309f45d7d061c3aa4635` |
| Java continuation receipt | `cd15146db1f24c7a1820fd2e93f0ab047af87046b23783f2c62bcb4a294f14a1` |
| Approved resource inventory (`approval-02`) | `849f12ba5e8d88db6550929f2daf52c5e935497aef962d8277e38777a3b4990b` |
| Approved evidence index (`approval-02`) | `966e41473b3a8ce2c0cb504b544d1d70dc0e049e406a5e69d947f3bb3933bd9e` |
| Corrected final verification plan | `acdb3ed005c1511e60ee3648bf0ba0b2c8abeb242244b734cfb8fe9a76898b07` |
| Final merged native payload | `7aa4972ff65ee3046d152de69383d1629e2ffa1682118cf96ac181e82df86b3c` |
| Final catalog | `c117015bf49f6e859300607b938a0b489346d394e08e99105101ec5637225f84` |

### Lifecycle fixture correction

The first resume failed `heartbeat-recovery-exhausted` because the test observed `locked()` before the supervisor had queued game-thread cleanup. An immediate fixture queue drain could therefore return too early. Authorization had already closed. The corrected fixture adds a bounded wait for queue publication before draining and retains every authorization, secret-clearing, displayed-identity, cloud-denial, retry-count and cleanup assertion. Only the test class changed; the protected payload, all three DLLs and sealed production sources remained byte-identical. The affected protected case and then all 41 cases passed before the successful second resume.

The minimal patch, reproducible one-class rebuild helper and source hashes are preserved in [lifecycle-fixture-queue](../tools/packaging/native-auth-producer/lifecycle-fixture-queue/README.md). Historical failed output and the old plan remain separate evidence. No fixture JAR is committed.

The checkpoint is under the local build workspace's `auth-native-release-20260928-02`. It seals source, producer, dependencies, exact method selections, CPP, MAP, original DLLs, archive paths and generated loader identities. The prior `...-01` checkpoint is historical and cannot be resumed with the new budget sources. The foreign Obfuscator checkout is unchanged.

## Prepare and resume

Use [protect_auth_native_release.py](../tools/protect_auth_native_release.py). The regular Java workflow remains unchanged. Begin with `prepare-native-candidate` using a fresh runtime, the pinned producer bundle, verified parent classpath, existing server public key and a fresh output directory. Do not rerun randomized Java protection between preparation and resume.

Protect copies of the three exported DLLs, retaining their CPP and MAP. Each resource requires exact independently approved input/output hashes, size, path, producer, method selection, custody/loader rewrite and a reviewed protection receipt. A launcher receipt cannot approve a payload DLL, and a random resource extension does not bypass PE detection.

```powershell
python tools/protect_auth_native_release.py resume-with-approved-index `
  --checkpoint '<workspace>/build/auth-native-release-20260928-02/checkpoint.json' `
  --checkpoint-sha256 accc48898bca9fbcc9439d64b394fd753c0a5dda0639a97ebef4ebdb8fbdcb6d `
  --native-auth-inventory '<workspace>/build/auth-native-final-review-20260928-02/approval-02/inventory.json' `
  --native-auth-inventory-sha256 849f12ba5e8d88db6550929f2daf52c5e935497aef962d8277e38777a3b4990b `
  --native-auth-evidence '<workspace>/build/auth-native-final-review-20260928-02/approval-02/evidence-index.json' `
  --native-auth-evidence-sha256 966e41473b3a8ce2c0cb504b544d1d70dc0e049e406a5e69d947f3bb3933bd9e `
  --verification-plan '<workspace>/build/auth-native-lifecycle-fix-20260928-01/verified-plan/verification-plan.json' `
  --verification-plan-sha256 acdb3ed005c1511e60ee3648bf0ba0b2c8abeb242244b734cfb8fe9a76898b07 `
  --output '<fresh-final-output>'
```

Resume requires unchanged sealed/current source, exact resource approval and the actual final native fixtures: 933 protocol assertions, 23 DPoP assertions, and 40 isolated lifecycle cases across 41 JVM invocations. The Java continuation also runs payload/class checks, bootstrap compatibility and assembly. Prior raw-DLL or earlier-checkpoint fixture passes do not substitute for final protected-artifact results.

## Resource limits and custody

Ordinary entries retain the **8 MiB** limit. Only independently approved native resource paths receive **32 MiB per DLL**, **64 MiB aggregate**, at most four resources. The canonical approval-derived index is at most 16 KiB. Authenticated bootstrap B supplies that index; payload part A must contain identical bytes. A payload's self-declared index alone grants no larger allowance. The runtime verifies exact size, SHA-256 and x64 PE DLL identity; all declared resources must exist. Delivery parts remain at most 64 MiB and expanded archives/releases at most 128 MiB.

Focused budget tests passed 41 new assertions plus 16 existing memory-loader and 54 retained-integrity assertions; 47 packaging tests passed. These include a synthetic 20,523,520-byte PE accepted only through exact approval, with default/self-declared, tampered, missing, wrong-PE and aggregate-limit negatives. The tests did not execute that synthetic DLL or grant protection approval.

Bootstrap-owned `AuthNativeBridge` creates private verified DLL files, holds Windows no-write/no-delete guards and retains only weak references to disposable payload owners. `System.load` remains owned by the payload loader. Cleanup retries after collection and Windows unload; authenticated receipts support later-start cleanup. It does not call `FreeLibrary` or change media/support custody. Restart Minecraft when the canonical resident bridge changes.

## Matched backend and verified rollout

The staged backend passed 208 focused tests with two named Windows symlink-privilege skips. Its frozen evidence SHA-256 is `708874b67085df20b0b7b562c5a6920160806c893900642c30a38a00d042af71`. It serves a separately configured public copy of the already approved launcher. The loader still authenticates normally; game artifacts retain DPoP, per-fetch HWID, entitlement and recipient encryption. The public snapshot uses a private temporary file, while Plus responses encrypt incrementally with two bounded transfer slots and the same CSTAE1 format.

The matched catalog/backend verification passed **82 tests**, with one named Windows symlink-privilege skip and 68 actual-catalog fixture copies. The Windows long-path adjustment applied only to the fixture temporary directory. No production code or artifact changed for that adjustment.

The deployed image is `sha256:39d3d526ea054a5f041aa0e37d475101a5d009d52766b9cb176555b5de4ea550`, machine version `01M3KB49KVQS3JXV9RR7B5MC53`. Live verification matched all **28 backend source hashes** and **48 game artifacts**: the 43 prior files plus five final catalog files. Standard identity/artifacts, database schema, native policy sequence 3, keys and launcher bytes were preserved. The new catalog directory is selected by `CELESTE_BETA_RELEASE_DIR`; no payload registration POST was needed.

The fresh signed public manifest and the complete 68,233,216-byte launcher download both verified against `31ce0fd052af152e5fe340847eece882bceeb11dd65414a70ac0452ab2dfd743`. Protected game routes still rejected anonymous requests. A private online database backup passed its integrity check before cutover. These live checks used public metadata, hashes, schema and anonymous requests; they did not authenticate a real license or load Minecraft.

| Rollout evidence | SHA-256 |
| --- | --- |
| Live verification (`live-after.json`) | `8938b8563d6673a0ab8d0ff44312b402d292568aea576059cf57219d7ff1f4f2` |
| Matched local verification (`local-verification-02/result.json`) | `797c60f3d24849b3a6beca623b6a4cb5f3b4c07750f13e9e461610cdfb0e9403` |
| Private backup receipt | `3c92bba50f6262c8e1306db2aac68922d62df4784af0acbe005f86aefe1de040` |

Local Windows/Python 3.14 memory evidence reached 189.8 MiB peak working set with two 64 MiB beta streams plus two public streams. It excludes real HTTP/TLS, Linux behavior and concurrent admin hashing; it is not a 256 MiB VPS guarantee. No billing change is part of this work.

The small launcher's storage/execution architecture still needs the user's choice. No mini-launcher EXE has been built; memory-only execution is not claimed.

## Source publication and line endings

The source publication inventory contains 61 source/documentation files, including the four lifecycle fixture recipe files. It excludes generated DLLs, JARs, MAPs, private review evidence, keys and the foreign producer checkout. The private inventory records raw working-file SHA-256, canonical LF SHA-256 and Git clean blob IDs; the live rollout is complete, while the source commit/push is a separate publication step.

Two sealed inputs have different raw and Git-normalized hashes. Do not rewrite sealed working bytes to make the receipts match a clean checkout. Git's existing text attributes normalize them to LF; retain both identities in provenance and perform fresh preparation when rebuilding from normalized source.

| Input | Raw working SHA-256 | Canonical LF SHA-256 |
| --- | --- | --- |
| `beta-lab/obfuscation/native-auth-producer.json` | `bc5fbc80d4c474bde21aa86532a509ae8d547b07cc7557bc1466801109c200e3` | `3e32e09b228f9e183eafeb8ec5d6277f5120ec2ae98c378b19c4ee059935420d` |
| `core.lock.json` | `3594a2f4ccec1dff4cef6a6a1a074e2f88d2b6212e05cf040e5411248ddcf7cb` | `31042202a2721df123d5c6d3d14fdb550657ac929b4366f6cd7f480637f440ce` |
