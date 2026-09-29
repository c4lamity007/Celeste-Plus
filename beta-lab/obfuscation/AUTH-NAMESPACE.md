# Auth helper names

The payload release pipeline maps the private credential-helper implementation
and its nested classes to short names in `org.w3c.dom.utility.misc`. This is a
separate package beneath the XML namespace: it does not replace JDK DOM classes.
Names make inspection less convenient; they do not authorize requests or add
cryptographic protection. Existing encrypted-string passes still run afterward.
No new JNI is introduced by this stage.

Public loader, protocol, and existing native entrypoint class/method names stay
stable. The pass rejects externally exposed helper descriptors, native helper
methods, a JDK package collision, duplicate archive entries and already-native
inputs. Real DOM types remain owned by the JDK. Payload package ownership includes
the exact generated package, with no change to loader delegation rules.

## Verification chain

1. `runtime-input.jar` -> `runtime-auth-aliases.jar`: `auth-namespace.json`
   records input/output hashes, every alias, and exact inverse-normalized class
   equality. Every resource is retained. The pass is included in source pins.
2. `runtime-auth-aliases.jar` -> `payload-protected.jar`: the existing stable
   boundary checker remains byte-for-byte strict, including `InstallCredential`.
   `payload-boundaries.log` and its hash are recorded in the release receipt.
3. Native continuation binds the Java checkpoint receipt and payload hash.

For boundary rechecks, use the retained **aliased** input as `--source`.
Comparing raw input directly to the protected output intentionally fails because
`InstallCredential` contains a private reference to the renamed helper. Never
remove that class from the stable boundary or waive mismatches. Keep both input
artifacts and both receipts when auditing a release.

Run `tools/qa/run-auth-namespace.py --help` for the offline compatibility check.
After credential code changes, regenerate existing native auth artifacts and
complete the usual protection gates before a release. Old protected DLLs or
previous release receipts do not establish protection for new source.
