# Celeste+ small Windows launcher

Run `Celeste+Setup.exe`. It downloads the current full Celeste+ loader into a private Windows temporary folder and opens it automatically. No installation or extra login is needed. The full loader handles activation, licensing and Minecraft loading normally.

The small launcher shows download progress and a Cancel button. It verifies a fresh server-signed download manifest and the complete file checksum before starting the downloaded EXE. It accepts only the configured Celeste+ HTTPS service and download path. No license, HWID, stored activation or game data is sent by this small launcher.

The temporary copy stays available while the full loader or its cleanup helper is running. Normal exit removes the owned EXE and empty folder. Canceling or failing a download removes its partial file. If Windows or Task Manager forcibly terminates this small launcher, or a file remains locked for 24 hours after the main loader exits, the private temporary folder can remain for later Windows temp cleanup. It never terminates the full loader, its helpers, or Minecraft.

## Build on Windows x64

Use stable Rust's MSVC toolchain and Visual Studio C++ build tools. Run Cargo **from this directory** so its local configuration statically links the C runtime; recipients do not need a separate Visual C++ runtime for the small launcher.

```powershell
Push-Location beta-lab/mini-launcher
cargo test --locked --release
cargo build --locked --release
Pop-Location
```

Output: `target/x86_64-pc-windows-msvc/release/CelestePlusSetup.exe`. The distributed filename is `Celeste+Setup.exe`. Add `--offline` when the dependencies are cached.

## Scope and release

This is a download and launch helper. The existing main loader keeps its VMProtect protection and independent server authentication, hardware binding, environment checks, certificate policy, self-integrity and payload verification. The small launcher is not a registered authentication executable and does not need the main loader's native publication policy changed. Its source contains only the public download origin and public verification key. It is not additionally VMP-packed, keeping the file small.

The public service provides `/v1/launcher/manifest?platform=windows-x64` and a signed hash-specific EXE URL. Each run requests a fresh random nonce. The response is limited to 16 KiB; downloads are streamed, limited to 256 MiB and 300 seconds, and verified before launch. If approval expires during a slow download, a fresh manifest must approve the same file before it opens. Files are created with a current-user/SYSTEM ACL, and a read handle prevents replacement while the full loader runs.

Windows Authenticode signing remains unavailable under the accepted Unknown publisher release policy. A successful small-launcher test does not establish Minecraft injection or gameplay results. The main loader is downloaded from its own endpoint and continues receiving payload updates separately.
