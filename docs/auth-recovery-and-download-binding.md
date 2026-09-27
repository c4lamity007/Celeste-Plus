# Windows authentication follow-up

This revision changes the Plus loader, Bootstrap A, runtime and shared backend.
It is a source change until a coordinated release is recorded. The existing
published protocol 2 loader does not send the new artifact hardware proof.

## Artifact hardware binding

Every Plus artifact GET requires exactly one lowercase SHA-256 `X-Device-Id`
header. The installation-key DPoP signs that value as `hwid`, together with the
request's token and encrypted-delivery recipient binding. The server compares it
with the currently registered hardware identity before releasing bytes and
checks again after artifact processing. Missing, duplicate, malformed, stale or
mismatched identities are denied. Legacy Plus mode also requires this proof;
Standard routes keep their existing behavior.

Native delivery uses the same identity as authentication. Bootstrap A reads the
Windows identity through an isolated helper that uses Minecraft's existing JNA
classes. It does not bundle another JNA copy or change the runtime installation
signer's public method descriptor.

Deploy the updated native loader, Bootstrap A and strict backend together. A
backend-only rollout would reject downloads from the previously published loader.
Rollback must restore the compatible client/backend set together.

## Heartbeat recovery

A separate daemon supervises the heartbeat scheduler once per second. It stops
the old worker and waits for actual termination before creating a replacement,
so a cancelled request cannot overlap a new heartbeat. A replacement immediately
requests server verification. Three consecutive recoveries are allowed; a valid
server response resets that counter.

Restarting a worker never extends the independent server-issued session lease.
Expired, locked and stopped sessions remain disabled. Shutdown stops and awaits
both workers under the existing bounded deadline.

## Temporary Bootstrap A files

The durable cache remains encrypted. Before writing a plaintext JAR, the loader
starts an acknowledged hidden copy of itself in cleanup-only mode. That process
holds the selected game's original process handle and waits for its exit even
after the visible loader has closed. It makes no authentication requests and
does not attach to or terminate the game.

Cleanup accepts only an account-protected ownership receipt under the derived
Local AppData process directory. New receipts bind the Windows file identity,
expected digest and size. An interrupted write can therefore be removed without
mistaking a replacement at the same path for the owned file. Completed files
must still match the digest. Deletion uses the verified file handle; unknown
files, links and ambiguous ownership are preserved.

One launcher reuses its live waiter for the same game process. Separate launcher
instances can create additional waiters; each leaves when that game exits.

Exit cleanup retries temporary file locks for up to ten seconds. The existing
next-start cleanup remains a fallback if Windows terminates the helper or files
remain locked. This is process-lifetime cleanup, not a promise that files can be
deleted after an OS crash or under every external file lock.

## Windows fallback and early checks

Native and Java hardware collection can read bounded SMBIOS firmware structures
when WMI is unavailable. Existing identity fields and normalization are retained;
the fallback matched the WMI system, board, processor and monitor hashes on the
development PC. Environment checks can also use firmware manufacturer/model and
native adapter enumeration. Missing or malformed facts still deny startup. VM
and sandbox policy is unchanged, including physical Hyper-V/VBS host handling.

The Windows loader now registers an early TLS callback. Under the Windows loader
lock it uses only the PEB, a debugger API and an atomic result flag; the ordinary
startup path performs the more complex checks. The callback and timing probe
must remain outside VMP virtualization. Startup requires evidence that the
callback ran. Authentication endpoints, proof fields, credential schema strings
and project-owned authorization errors have expanded string obfuscation; this
does not claim to remove every Windows import or third-party library string.

## Verification boundaries

Focused backend, isolated JVM and Windows child-process checks are separate from
Minecraft verification. A fresh native build requires fresh VMProtect output and
protection review; earlier release receipts cannot approve changed bytes.
No subscription automation, billing setup or renewal UI is included here.
