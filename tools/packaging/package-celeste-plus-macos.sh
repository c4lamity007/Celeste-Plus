#!/usr/bin/env bash
set -euo pipefail

# Native Mac CI packaging only. Ad-hoc signing seals the bundle; it is not a
# Developer ID identity, notarization, or a reason to disable Gatekeeper.
test "$(uname -s)" = Darwin
test "${GITHUB_ACTIONS:-}" = true
test "${RUNNER_ENVIRONMENT:-}" = github-hosted
test "${CELESTE_KEYCHAIN_TEST_STATUS:-}" = passed-with-test-reader-authorization
platform="${1:?macos-arm64 or macos-x64}"
binary="${2:?protected binary path}"
output="${3:?fresh output directory}"
release_id="${4:?publication release ID}"
protection_receipt="${5:?protection receipt for the final signed executable}"
case "$release_id" in
  celeste-plus-*) ;;
  *) echo 'Invalid Celeste+ publication identity' >&2; exit 1 ;;
esac
case "$platform" in
  macos-arm64) architecture=arm64 ;;
  macos-x64) architecture=x86_64 ;;
  *) echo 'Unsupported Mac architecture' >&2; exit 1 ;;
esac
test -f "$binary"
test ! -e "$output"
test "$(lipo -archs "$binary")" = "$architecture"
mkdir -p "$output/Celeste+.app/Contents/"{MacOS,Resources}
app="$output/Celeste+.app"
install -m 755 "$binary" "$app/Contents/MacOS/CelestePlus"

python - "$app" <<'PY'
import plistlib, sys
from pathlib import Path
app = Path(sys.argv[1])
plist = {
    'CFBundleExecutable': 'CelestePlus',
    'CFBundleIdentifier': 'dev.celeste.client.plus',
    'CFBundleName': 'Celeste+', 'CFBundleDisplayName': 'Celeste+',
    'CFBundlePackageType': 'APPL', 'CFBundleInfoDictionaryVersion': '6.0',
    'CFBundleShortVersionString': '0.1.0', 'CFBundleVersion': '1.0.3',
    'LSMinimumSystemVersion': '11.0', 'NSHighResolutionCapable': True,
    'CFBundleIconFile': 'Celeste.icns',
}
(app / 'Contents/Info.plist').write_bytes(plistlib.dumps(plist, sort_keys=True))
PY
iconset="$output/Celeste.iconset"
mkdir "$iconset"
for size in 16 32 128 256 512; do
  sips -s format png -z "$size" "$size" beta-lab/native-launcher/assets/celeste-logo.png --out "$iconset/icon_${size}x${size}.png" >/dev/null
  retina=$((size * 2))
  sips -s format png -z "$retina" "$retina" beta-lab/native-launcher/assets/celeste-logo.png --out "$iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil --convert icns --output "$app/Contents/Resources/Celeste.icns" "$iconset"
codesign --force --sign - --timestamp=none "$app"
codesign --verify --deep --strict --verbose=2 "$app"
codesign -dvv "$app" 2> "$output/codesign.txt"

# Signing changes executable bytes. Verify the final signed snapshot before
# executing it or producing a publication archive; CI is not protection proof.
python tools/packaging/native_protection_gate.py \
  --binary "$app/Contents/MacOS/CelestePlus" --receipt "$protection_receipt" \
  --release-id "$release_id" --product celeste_plus --platform "$platform" \
  > "$output/native-protection.json"

# Refuse to start if this disposable runner unexpectedly contains a saved
# activation. No license or live auth service is used by this GUI smoke test.
test ! -e "$HOME/Library/Application Support/Celeste/auth"
test ! -e "$HOME/Library/Application Support/Celeste+/auth"
gui_pid=''
cleanup() {
  if test -n "$gui_pid" && kill -0 "$gui_pid" 2>/dev/null; then
    kill "$gui_pid" || true
    for attempt in 1 2 3 4 5; do
      kill -0 "$gui_pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$gui_pid" 2>/dev/null; then
      kill -KILL "$gui_pid" || true
    fi
    wait "$gui_pid" || true
  fi
}
trap cleanup EXIT
"$app/Contents/MacOS/CelestePlus" > "$output/gui-startup.txt" 2>&1 &
gui_pid=$!
sleep 5
if ! kill -0 "$gui_pid" 2>/dev/null; then
  echo 'Celeste+ exited during the Mac GUI startup check' >&2
  tail -n 30 "$output/gui-startup.txt" >&2
  exit 1
fi
screencapture -x "$output/Celeste+-startup.png" 2> "$output/screenshot-status.txt" || true
cleanup
gui_pid=''
echo 'PASS: the native Mac setup window remained running for five seconds.'

python - "$app" "$platform" "$output" "$release_id" <<'PY'
import hashlib, json, os, sys
from pathlib import Path
app, platform, output = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
binary = app / 'Contents/MacOS/CelestePlus'
protection = json.loads((output / 'native-protection.json').read_text())
if hashlib.sha256(binary.read_bytes()).hexdigest() != protection['binary_sha256']:
    raise SystemExit('Native executable changed after protection verification')
manifest = {
    'schema': 'celeste-plus-macos-preview-1',
    'platform': platform, 'artifact': 'Celeste+.app',
    'native_source_commit': os.environ['NATIVE_SOURCE_COMMIT'],
    'packaging_commit': os.environ['GITHUB_SHA'],
    'release_id': sys.argv[4],
    'distribution_status': 'published-preview',
    'native_protection': protection,
    'executable_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
    'signature': 'ad-hoc', 'notarized': False,
    'gui_startup_smoke': 'passed',
    'keychain_interop': os.environ['CELESTE_KEYCHAIN_TEST_STATUS'],
    'first_use_keychain_consent_tested': False,
    'live_authentication_tested': False, 'minecraft_gameplay_tested': False,
}
(output / 'build-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
(output / 'START-HERE.txt').write_text(
    'Celeste+ macOS preview\n\n'
    'Open Celeste+.app using the same account as Minecraft. Use a full Java 21 JDK.\n'
    'Activate your Celeste+ or Both license, start Fabric 1.21.11, select its profile\n'
    'and Java process, then Load. Existing device limits still apply.\n\n'
    'This preview is ad-hoc signed, not Developer ID signed or Apple-notarized.\n'
    'macOS may require per-app approval in System Settings > Privacy & Security.\n'
    'Do not disable Gatekeeper globally. Review and approve Keychain prompts as needed.\n'
    'First setup checks Keychain access before using a license slot. When macOS asks\n'
    'whether security may read the Celeste credential item, choose Always Allow\n'
    'to permit subsequent reads by the runtime. Denying access cancels setup.\n'
    'The CI Keychain test explicitly authorizes synthetic readers; it does not\n'
    'automate or prove the first-use consent dialog on your Mac.\n'
    'GUI startup and offline fixtures are not live authentication/gameplay testing.\n'
    'Spotify/system media is Windows-only.\n'
)
PY
ditto -c -k --sequesterRsrc --keepParent "$app" "$output/Celeste+-$platform.zip"
python - "$output/Celeste+-$platform.zip" "$output/native-protection.json" <<'PY'
import hashlib, json, sys, zipfile
from pathlib import Path
expected = json.loads(Path(sys.argv[2]).read_text())['binary_sha256']
with zipfile.ZipFile(sys.argv[1]) as archive:
    actual = archive.read('Celeste+.app/Contents/MacOS/CelestePlus')
if hashlib.sha256(actual).hexdigest() != expected:
    Path(sys.argv[1]).unlink()
    raise SystemExit('Archived executable differs from verified native protection output')
PY
shasum -a 256 "$output/Celeste+-$platform.zip" | awk '{print $1 "  Celeste+-'"$platform"'.zip"}' > "$output/Celeste+-$platform.zip.sha256"
echo "Packaged $platform: ad-hoc signed, not notarized; auth and gameplay unverified."
