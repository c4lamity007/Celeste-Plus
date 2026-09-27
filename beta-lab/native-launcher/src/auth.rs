// Product-scoped protocol with account-protected credential storage. Portable
// hardware/key-store implementations are shared with the standard launcher so
// an existing Both activation preserves the exact same installation identity.
#![allow(dead_code)]
#[cfg(any(target_os = "macos", target_os = "linux", test))]
#[path = "../../native-shared/src/portable_hardware.rs"]
mod portable_hardware;
#[cfg(test)]
#[path = "../../../shared-auth/secret_buffer_tests.rs"]
mod secret_buffer_tests;
use crate::{atomic_store, release};
use aes_gcm::aead::AeadInPlace;
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use anyhow::{Context, Result, anyhow, bail};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use ed25519_dalek::pkcs8::DecodePrivateKey;
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use hkdf::Hkdf;
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
#[cfg(windows)]
use winreg::RegKey;
#[cfg(windows)]
use winreg::enums::{HKEY_LOCAL_MACHINE, KEY_READ, KEY_WOW64_64KEY};
#[cfg(windows)]
use wmi::{COMLibrary, WMIConnection};
use x25519_dalek::{PublicKey as X25519PublicKey, StaticSecret};
use zeroize::Zeroizing;

const STORE_MAGIC: &[u8; 4] = b"CST1";
const TICKET_MAGIC: &[u8; 4] = b"CLT1";
const INSTALLER_UUID: &str = "00000000-0000-0000-0000-000000000000";
const INSTALLER_NAME: &str = "CelesteInstaller";
const TOKEN_INFO: &[u8] = b"celeste-install-token-v1";

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstallCredential {
    device_id: String,
    uid: u64,
    signing_private: Zeroizing<String>,
    encryption_private: Zeroizing<String>,
    token_seed: Zeroizing<String>,
    certificate: String,
    game_uuid: String,
    game_name: String,
}

#[derive(Debug, Clone, Serialize)]
struct HardwareProfile {
    system: String,
    board: String,
    processor: String,
    monitors: Vec<String>,
}

#[cfg(windows)]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct ComputerSystem {
    manufacturer: Option<String>,
    model: Option<String>,
}

#[cfg(windows)]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct ComputerProduct {
    uuid: Option<String>,
}

#[cfg(windows)]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct Bios {
    serial_number: Option<String>,
}

#[cfg(windows)]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct BaseBoard {
    manufacturer: Option<String>,
    product: Option<String>,
    version: Option<String>,
    serial_number: Option<String>,
}

#[cfg(windows)]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct Processor {
    processor_id: Option<String>,
    manufacturer: Option<String>,
    name: Option<String>,
}

fn is_unauthorized(error: &anyhow::Error) -> bool {
    error
        .downcast_ref::<HttpFailure>()
        .is_some_and(|failure| matches!(failure.status, 401 | 403))
}

#[derive(Debug)]
struct HttpFailure {
    status: u16,
    path: String,
    detail: String,
}

impl std::fmt::Display for HttpFailure {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            formatter,
            "Authentication server rejected the request ({}) at {}: {}",
            self.status, self.path, self.detail
        )
    }
}

impl std::error::Error for HttpFailure {}

fn http_failure(status: u16, path: &str, detail: &str) -> anyhow::Error {
    HttpFailure {
        status,
        path: path.to_owned(),
        detail: detail.to_owned(),
    }
    .into()
}

#[cfg(windows)]
fn collect_hardware() -> Result<HardwareProfile> {
    crate::environment_guard::require_allowed()?;
    crate::native_manifest::require_current("celeste_plus")?;
    let com = COMLibrary::new().context("Windows hardware services are unavailable")?;
    let wmi = WMIConnection::new(com).context("Could not connect to Windows hardware services")?;
    let systems: Vec<ComputerSystem> =
        wmi.raw_query("SELECT Manufacturer,Model FROM Win32_ComputerSystem")?;
    let products: Vec<ComputerProduct> =
        wmi.raw_query("SELECT UUID FROM Win32_ComputerSystemProduct")?;
    let bios: Vec<Bios> = wmi.raw_query("SELECT SerialNumber FROM Win32_BIOS")?;
    let boards: Vec<BaseBoard> =
        wmi.raw_query("SELECT Manufacturer,Product,Version,SerialNumber FROM Win32_BaseBoard")?;
    let processors: Vec<Processor> =
        wmi.raw_query("SELECT ProcessorId,Manufacturer,Name FROM Win32_Processor")?;

    let system = systems.first();
    let product = products.first();
    let firmware = bios.first();
    let board = boards.first();
    let processor = processors.first();
    let system_value = canonical(&[
        system.and_then(|v| v.manufacturer.as_deref()),
        system.and_then(|v| v.model.as_deref()),
        firmware.and_then(|v| v.serial_number.as_deref()),
        product.and_then(|v| v.uuid.as_deref()),
    ]);
    let board_value = canonical(&[
        board.and_then(|v| v.manufacturer.as_deref()),
        board.and_then(|v| v.product.as_deref()),
        board.and_then(|v| v.version.as_deref()),
        board.and_then(|v| v.serial_number.as_deref()),
    ]);
    let processor_value = canonical(&[
        processor.and_then(|v| v.processor_id.as_deref()),
        processor.and_then(|v| v.manufacturer.as_deref()),
        processor.and_then(|v| v.name.as_deref()),
    ]);
    let mut monitors = monitor_edids()
        .into_iter()
        .map(|value| hex_sha256(&value))
        .collect::<Vec<_>>();
    monitors.sort();
    monitors.dedup();
    if monitors.is_empty() {
        monitors.push(hex_sha256(system_value.as_bytes()));
    }
    Ok(HardwareProfile {
        system: hex_sha256(system_value.as_bytes()),
        board: hex_sha256(board_value.as_bytes()),
        processor: hex_sha256(processor_value.as_bytes()),
        monitors,
    })
}

#[cfg(any(target_os = "macos", target_os = "linux"))]
fn collect_hardware() -> Result<HardwareProfile> {
    crate::environment_guard::require_allowed()?;
    crate::native_manifest::require_current("celeste_plus")?;
    portable_hardware::collect()
}

fn canonical(values: &[Option<&str>]) -> String {
    values
        .iter()
        .map(|value| {
            let normalized = value.unwrap_or("").trim().to_lowercase();
            if normalized.is_empty() {
                "unknown".to_owned()
            } else {
                normalized
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

#[cfg(windows)]
fn monitor_edids() -> Vec<Vec<u8>> {
    let mut values = Vec::new();
    let root = RegKey::predef(HKEY_LOCAL_MACHINE);
    let Ok(display) = root.open_subkey_with_flags(
        r"SYSTEM\CurrentControlSet\Enum\DISPLAY",
        KEY_READ | KEY_WOW64_64KEY,
    ) else {
        return values;
    };
    for vendor in display.enum_keys().flatten() {
        let Ok(vendor_key) = display.open_subkey_with_flags(&vendor, KEY_READ | KEY_WOW64_64KEY)
        else {
            continue;
        };
        for instance in vendor_key.enum_keys().flatten() {
            let Ok(parameters) = vendor_key.open_subkey_with_flags(
                format!(r"{}\Device Parameters", instance),
                KEY_READ | KEY_WOW64_64KEY,
            ) else {
                continue;
            };
            if let Ok(raw) = parameters.get_raw_value("EDID")
                && !raw.bytes.is_empty()
            {
                values.push(raw.bytes);
            }
        }
    }
    values
}

fn enroll(license: &str, hardware: &HardwareProfile) -> Result<InstallCredential> {
    // Check this account's OS key store and durable storage before consuming a device slot.
    enroll_after_preflight(
        || crate::credential_protection::preflight(&user_cache()?),
        || enroll_remote(license, hardware),
    )
}

fn enroll_after_preflight<T>(
    preflight: impl FnOnce() -> Result<()>,
    enroll_remote: impl FnOnce() -> Result<T>,
) -> Result<T> {
    preflight().context("Activation storage is unavailable; no new device was enrolled")?;
    enroll_remote()
}

fn enroll_remote(license: &str, hardware: &HardwareProfile) -> Result<InstallCredential> {
    let signing = SigningKey::generate(&mut OsRng);
    let encryption = StaticSecret::random_from_rng(OsRng);
    let encryption_public = X25519PublicKey::from(&encryption);
    let start = post_json(
        "/v1/enroll/start",
        &json!({
            "product": "celeste_plus",
            "release_id": release::LAUNCHER_CHANNEL_ID,
            "client_version": release::LAUNCHER_PROTOCOL_VERSION,
            "client_sha256": release::LAUNCHER_CHANNEL_DIGEST,
            "game_uuid": INSTALLER_UUID,
            "game_name": INSTALLER_NAME,
            "license_key": license,
            "install_signing_key": encode(signing.verifying_key().as_bytes()),
            "install_encryption_key": encode(encryption_public.as_bytes()),
            "hardware": hardware,
        }),
    ).map_err(|error| {
        if error.downcast_ref::<HttpFailure>().is_some_and(|failure| failure.status == 409) {
            error.context("This license is already activated. Select its original instance and account, or contact support if the saved activation was lost")
        } else {
            error.context("License activation failed")
        }
    })?;
    let message = decode(required_text(&start, "/message")?)?;
    let transcript: Value = serde_json::from_slice(&message)?;
    if required_text(&transcript, "/product")? != "celeste_plus" {
        bail!("Authentication challenge belongs to another product");
    }
    let complete = post_json_with_dpop(
        "/v1/enroll/complete",
        &json!({
            "challenge_id": required_text(&start, "/challenge_id")?,
            "install_signature": encode(&signing.sign(&message).to_bytes()),
        }),
        Some(&signing),
    )?;
    let certificate = required_text(&complete, "/device_certificate")?.to_owned();
    let certificate_claims = verify_token(&certificate, "celeste-installation")?;
    crate::dpop::require_binding(&certificate_claims, &signing)?;
    let device_id = required_text(&complete, "/device_id")?.to_owned();
    let uid = complete
        .pointer("/uid")
        .and_then(Value::as_u64)
        .ok_or_else(|| anyhow!("Enrollment response omitted uid"))?;
    if required_text(&certificate_claims, "/device")? != device_id
        || required_text(&certificate_claims, "/sub")? != uid.to_string()
        || required_text(&certificate_claims, "/install_key")?
            != encode(signing.verifying_key().as_bytes())
    {
        bail!("Installation certificate does not match this device");
    }
    let seed = decrypt_token(
        complete
            .pointer("/token_envelope")
            .ok_or_else(|| anyhow!("Enrollment response omitted token envelope"))?,
        &encryption,
        &message,
    )?;
    Ok(InstallCredential {
        device_id,
        uid,
        signing_private: encode(signing.as_bytes()).into(),
        encryption_private: encode(encryption.as_bytes()).into(),
        token_seed: encode(&seed[..]).into(),
        certificate,
        game_uuid: INSTALLER_UUID.to_owned(),
        game_name: INSTALLER_NAME.to_owned(),
    })
}

fn authenticate_installer(
    credential: &InstallCredential,
    hardware: &HardwareProfile,
    license: &str,
) -> Result<Value> {
    let signing = signing_key(&credential.signing_private)?;
    let token = signing_key(&credential.token_seed)?;
    let start = post_json(
        "/v1/installer/start",
        &json!({
            "product": "celeste_plus",
            "release_id": release::LAUNCHER_CHANNEL_ID,
            "client_version": release::LAUNCHER_PROTOCOL_VERSION,
            "client_sha256": release::LAUNCHER_CHANNEL_DIGEST,
            "game_uuid": INSTALLER_UUID,
            "game_name": INSTALLER_NAME,
            "device_id": credential.device_id,
            "license_key": if license.is_empty() { None } else { Some(license) },
            "hardware": hardware,
        }),
    )?;
    let message = decode(required_text(&start, "/message")?)?;
    let transcript: Value = serde_json::from_slice(&message)?;
    if required_text(&transcript, "/product")? != "celeste_plus" {
        bail!("Authentication challenge belongs to another product");
    }
    let complete = post_json_with_dpop(
        "/v1/installer/complete",
        &json!({
            "challenge_id": required_text(&start, "/challenge_id")?,
            "install_signature": encode(&signing.sign(&message).to_bytes()),
            "token_signature": encode(&token.sign(&message).to_bytes()),
        }),
        Some(&signing),
    )?;
    let grant = required_text(&complete, "/grant")?;
    let claims = verify_token(grant, "celeste-installer")?;
    crate::dpop::require_binding(&claims, &signing)?;
    let bootstrap_url = required_text(&complete, "/bootstrap/url")?;
    let bootstrap_id = bootstrap_url.rsplit('/').next().unwrap_or("");
    let bootstrap_sha = required_text(&complete, "/bootstrap/sha256")?;
    if required_text(&claims, "/product")? != "celeste_plus"
        || required_text(&claims, "/sub")? != credential.uid.to_string()
        || required_text(&claims, "/device")? != credential.device_id
        || required_text(&claims, "/release")? != release::LAUNCHER_CHANNEL_ID
        || required_text(&claims, "/client_sha256")? != release::LAUNCHER_CHANNEL_DIGEST
        || required_text(&claims, "/bootstrap")? != bootstrap_id
        || required_text(&claims, "/bootstrap_sha256")? != bootstrap_sha
    {
        bail!("Installer grant does not match this device or release");
    }
    Ok(complete)
}

fn decrypt_token(
    envelope: &Value,
    private: &StaticSecret,
    message: &[u8],
) -> Result<Zeroizing<[u8; 32]>> {
    let ephemeral = fixed_32(&decode(required_text(envelope, "/ephemeral_key")?)?)?;
    let nonce = decode(required_text(envelope, "/nonce")?)?;
    if nonce.len() != 12 {
        bail!("Token envelope nonce is invalid");
    }
    let mut plain = Zeroizing::new(decode(required_text(envelope, "/ciphertext")?)?);
    let transcript: Value = serde_json::from_slice(message)?;
    let salt = decode(required_text(&transcript, "/nonce")?)?;
    if salt.len() != 32 {
        bail!("Token envelope salt is invalid");
    }
    let shared = private.diffie_hellman(&X25519PublicKey::from(ephemeral));
    let hkdf = Hkdf::<Sha256>::new(Some(&salt), shared.as_bytes());
    let mut wrapping = Zeroizing::new([0u8; 32]);
    hkdf.expand(TOKEN_INFO, &mut wrapping[..])
        .map_err(|_| anyhow!("Token key derivation failed"))?;
    let cipher = Aes256Gcm::new_from_slice(&wrapping[..])
        .map_err(|_| anyhow!("Token cipher could not start"))?;
    cipher
        .decrypt_in_place(Nonce::from_slice(&nonce), message, &mut *plain)
        .map_err(|_| anyhow!("Installation token envelope was rejected"))?;
    if plain.len() != 32 {
        bail!("Expected a 32-byte key");
    }
    let mut seed = Zeroizing::new([0u8; 32]);
    seed.copy_from_slice(&plain);
    Ok(seed)
}

fn post_json(path: &str, body: &Value) -> Result<Value> {
    post_json_with_dpop(path, body, None)
}

fn post_json_with_dpop(path: &str, body: &Value, signing: Option<&SigningKey>) -> Result<Value> {
    let url = endpoint(path)?;
    let agent = ureq::AgentBuilder::new()
        .https_only(true)
        .redirects(0)
        .timeout(std::time::Duration::from_secs(25))
        .build();
    let mut request = agent
        .post(&url)
        .set("Content-Type", "application/json")
        .set("Accept", "application/json");
    if let Some(signing) = signing {
        request = request.set("DPoP", &crate::dpop::proof(signing, "POST", &url, None)?);
    }
    let result = request.send_json(body.clone());
    match result {
        Ok(response) => {
            let mut bytes = Vec::new();
            response
                .into_reader()
                .take(65_537)
                .read_to_end(&mut bytes)?;
            if bytes.len() > 65_536 {
                bail!("Authentication response exceeds size bounds");
            }
            serde_json::from_slice(&bytes).context("Server returned invalid JSON")
        }
        Err(ureq::Error::Status(code, response)) => {
            let mut detail = String::new();
            let _ = response
                .into_reader()
                .take(4_097)
                .read_to_string(&mut detail);
            Err(http_failure(code, path, &short_detail(&detail, body)))
        }
        Err(error) => Err(anyhow!(error).context("Authentication server is unreachable")),
    }
}

fn endpoint(path: &str) -> Result<String> {
    if !release::BASE_URL.starts_with("https://")
        || !path.starts_with("/v1/")
        || path.contains("://")
        || path.contains("..")
        || path.contains(['?', '#', '%', '\\'])
    {
        bail!("Server response referenced an untrusted endpoint");
    }
    Ok(format!(
        "{}{}",
        release::BASE_URL.trim_end_matches('/'),
        path
    ))
}

fn verify_token(token: &str, audience: &str) -> Result<Value> {
    let parts = token.split('.').collect::<Vec<_>>();
    if parts.len() != 3 {
        bail!("Server returned a malformed signed token");
    }
    let key = VerifyingKey::from_bytes(&fixed_32(&decode(release::SERVER_KEY)?)?)?;
    let signature = Signature::from_slice(&decode(parts[2])?)?;
    key.verify(format!("{}.{}", parts[0], parts[1]).as_bytes(), &signature)
        .context("Server token signature is invalid")?;
    let header: Value = serde_json::from_slice(&decode(parts[0])?)?;
    let claims: Value = serde_json::from_slice(&decode(parts[1])?)?;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)?
        .as_secs();
    if required_text(&header, "/alg")? != "EdDSA"
        || required_text(&header, "/typ")? != "CST"
        || required_text(&claims, "/aud")? != audience
        || claims.pointer("/nbf").and_then(Value::as_u64).unwrap_or(0) > now + 5
        || claims.pointer("/exp").and_then(Value::as_u64).unwrap_or(0) <= now
    {
        bail!("Server token is expired or has the wrong audience");
    }
    Ok(claims)
}

fn signing_key(encoded: &str) -> Result<SigningKey> {
    let mut value = Zeroizing::new(Vec::new());
    URL_SAFE_NO_PAD
        .decode_vec(encoded, &mut value)
        .context("Invalid base64url value")?;
    if value.len() == 32 {
        return Ok(SigningKey::from_bytes(value.as_slice().try_into()?));
    }
    SigningKey::from_pkcs8_der(&value).context("Saved signing key is invalid")
}

fn load_credential(path: &Path) -> Result<InstallCredential> {
    let stored = fs::read(path)?;
    if stored.len() <= STORE_MAGIC.len() || &stored[..STORE_MAGIC.len()] != STORE_MAGIC {
        bail!("Credential envelope is invalid");
    }
    let plain = Zeroizing::new(unprotect_credential(&stored[STORE_MAGIC.len()..])?);
    let credential: InstallCredential = serde_json::from_slice(&plain)?;
    if credential.device_id.len() < 32 || credential.uid == 0 || credential.certificate.is_empty() {
        bail!("Credential data is incomplete");
    }
    let signing = signing_key(&credential.signing_private)?;
    signing_key(&credential.token_seed)?;
    let claims = verify_token(&credential.certificate, "celeste-installation")?;
    // Existing signed install_key certificates remain reusable; fresh grants
    // always require cnf and cannot fall back to bearer authorization.
    if claims.get("cnf").is_some() {
        crate::dpop::require_binding(&claims, &signing)?;
    }
    let public_key = encode(signing.verifying_key().as_bytes());
    if required_text(&claims, "/sub")? != credential.uid.to_string()
        || required_text(&claims, "/device")? != credential.device_id
        || required_text(&claims, "/install_key")? != public_key
    {
        bail!("Saved installation credential does not match its signed certificate");
    }
    Ok(credential)
}

fn save_credential(path: &Path, credential: &InstallCredential) -> Result<()> {
    #[cfg(any(target_os = "macos", target_os = "linux"))]
    crate::user_profile::ensure_private_activation_dir(
        path.parent()
            .ok_or_else(|| anyhow!("Credential storage has no parent directory"))?,
    )?;
    let mut plain = Zeroizing::new(Vec::new());
    serde_json::to_writer(&mut *plain, credential)?;
    let protected = protect_credential(&plain)?;
    let mut stored = Vec::with_capacity(STORE_MAGIC.len() + protected.len());
    stored.extend_from_slice(STORE_MAGIC);
    stored.extend_from_slice(&protected);
    atomic_write(path, &stored)
}

fn save_launch_ticket(path: &Path, ticket: &str) -> Result<()> {
    let protected = protect_credential(ticket.as_bytes())?;
    let mut stored = Vec::with_capacity(TICKET_MAGIC.len() + protected.len());
    stored.extend_from_slice(TICKET_MAGIC);
    stored.extend_from_slice(&protected);
    atomic_write(path, &stored)
}

fn atomic_write(path: &Path, value: &[u8]) -> Result<()> {
    atomic_store::atomic_write(path, value)
}

fn protect_credential(value: &[u8]) -> Result<Vec<u8>> {
    crate::credential_protection::protect(value)
}

fn unprotect_credential(value: &[u8]) -> Result<Vec<u8>> {
    crate::credential_protection::unprotect(value)
}

fn required_text<'a>(value: &'a Value, pointer: &str) -> Result<&'a str> {
    value
        .pointer(pointer)
        .and_then(Value::as_str)
        .ok_or_else(|| anyhow!("Server response omitted {pointer}"))
}

fn require_digest(value: &[u8], expected: &str) -> Result<()> {
    let actual = hex_sha256(value);
    if actual.as_bytes() != expected.as_bytes() {
        bail!(
            "Signed loader digest does not match the downloaded file (expected {expected}, got {actual}, bytes={})",
            value.len()
        );
    }
    Ok(())
}

fn hex_sha256(value: &[u8]) -> String {
    let digest = Sha256::digest(value);
    digest.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn encode(value: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(value)
}

fn decode(value: &str) -> Result<Vec<u8>> {
    URL_SAFE_NO_PAD
        .decode(value)
        .context("Invalid base64url value")
}

fn fixed_32(value: &[u8]) -> Result<[u8; 32]> {
    value
        .try_into()
        .map_err(|_| anyhow!("Expected a 32-byte key"))
}

fn short_detail(value: &str, request: &Value) -> String {
    let parsed: Value = serde_json::from_str(value).unwrap_or(Value::Null);
    let mut detail = parsed
        .pointer("/detail")
        .and_then(Value::as_str)
        .unwrap_or("request denied")
        .to_owned();
    // Redact before truncation so a server echo cannot reveal a partial license
    // or signature. Keep the actual bounded server reason useful to the user.
    for key in [
        "license_key",
        "install_signature",
        "token_signature",
        "grant",
    ] {
        if let Some(secret) = request
            .get(key)
            .and_then(Value::as_str)
            .filter(|secret| !secret.is_empty())
        {
            detail = detail.replace(secret, "[redacted]");
        }
    }
    detail.chars().take(120).collect()
}

#[cfg(test)]
mod error_tests {
    use super::*;
    #[test]
    fn failed_storage_preflight_makes_zero_enrollment_requests() {
        let requests = std::cell::Cell::new(0);
        let failure = enroll_after_preflight(
            || Err(std::io::Error::from_raw_os_error(3).into()),
            || {
                requests.set(requests.get() + 1);
                Ok(())
            },
        )
        .unwrap_err();
        assert_eq!(requests.get(), 0);
        assert!(failure.to_string().contains("no new device was enrolled"));
        assert_eq!(
            failure
                .downcast_ref::<std::io::Error>()
                .unwrap()
                .raw_os_error(),
            Some(3)
        );
    }

    #[test]
    fn successful_storage_preflight_precedes_the_only_enrollment_request() {
        let calls = std::cell::RefCell::new(Vec::new());
        enroll_after_preflight(
            || {
                calls.borrow_mut().push("preflight");
                Ok(())
            },
            || {
                calls.borrow_mut().push("enroll");
                Ok(())
            },
        )
        .unwrap();
        assert_eq!(*calls.borrow(), ["preflight", "enroll"]);
    }

    #[test]
    fn failed_optional_profile_discovery_preserves_direct_recovery_candidates() {
        let original = PathBuf::from(r"D:\Redirected AppData\Celeste\auth\uid-7.bin");
        let mut paths = vec![original.clone()];
        let failure = append_recovery_bindings(
            &mut paths,
            Err(anyhow!("Modrinth database is temporarily unavailable")),
        );
        assert_eq!(paths, [original]);
        assert!(
            failure
                .unwrap()
                .to_string()
                .contains("temporarily unavailable")
        );
    }

    #[test]
    fn legacy_bindings_follow_explicit_profile_location_not_appdata_spelling() {
        let game = PathBuf::from(r"D:\Minecraft games\Casual ç”¨æˆ·\instance");
        let mut paths = Vec::new();
        assert!(
            append_recovery_bindings(
                &mut paths,
                Ok(vec![crate::platform::GameProfile {
                    name: "Fixture only".to_owned(),
                    game: game.clone(),
                }]),
            )
            .is_none()
        );
        assert_eq!(paths.len(), 3);
        assert!(paths.iter().all(|path| path.starts_with(&game)));
        assert_eq!(
            paths[0],
            game.join("config")
                .join("celeste")
                .join("auth")
                .join("celeste_plus")
                .join("device.bin")
        );
    }

    #[test]
    fn protection_failure_survives_mixed_corrupt_candidate_order() {
        for protection_first in [true, false] {
            let mut protection = None;
            let mut other = None;
            let protected_store_error =
                unprotect_credential(b"not a protected envelope").unwrap_err();
            let malformed = anyhow!("Credential envelope is invalid");
            let failures = if protection_first {
                [protected_store_error, malformed]
            } else {
                [malformed, protected_store_error]
            };
            for error in failures {
                remember_local_failure(error, &mut protection, &mut other);
            }
            assert!(
                protection
                    .unwrap()
                    .downcast_ref::<crate::credential_protection::ProtectionError>()
                    .is_some()
            );
            assert!(other.is_some());
        }
    }
    #[cfg(windows)]
    #[test]
    fn original_shared_owner_cache_path_is_reused_without_beta_suffix() {
        let cache = shared_cache_under(Path::new(r"C:\Users\fixture\AppData\Local")).unwrap();
        assert_eq!(
            cache.join("uid-7.bin"),
            PathBuf::from(format!(
                r"C:\Users\fixture\AppData\Local\Celeste\auth\{}\uid-7.bin",
                release::LAUNCHER_CHANNEL_ID
            ))
        );
        assert!(shared_cache_under(Path::new("relative-user-root")).is_err());
        for redirected in [
            r"D:\Roaming data\ãƒ¦ãƒ¼ã‚¶ãƒ¼\Local",
            r"C:\Users\DESKTOP-QJDBQ2D$\Casual\AppData\Local",
            r"\\profile-server\Current user\Local",
        ] {
            let cache = shared_cache_under(Path::new(redirected)).unwrap();
            assert_eq!(
                cache,
                Path::new(redirected)
                    .join("Celeste")
                    .join("auth")
                    .join(release::LAUNCHER_CHANNEL_ID)
            );
        }
    }

    #[cfg(any(target_os = "macos", target_os = "linux"))]
    #[test]
    fn portable_cache_reuses_standard_identity_but_keeps_plus_selection_distinct() {
        for root in [
            "/home/fixture/.local/share",
            "/Users/fixture/Library/Application Support",
        ] {
            let cache = shared_cache_under(Path::new(root)).unwrap();
            assert_eq!(
                cache,
                Path::new(root)
                    .join("Celeste")
                    .join("auth")
                    .join(release::LAUNCHER_CHANNEL_ID)
            );
            assert_ne!(
                cache.join("celeste-plus-active.bin"),
                cache.join("celeste-active.bin")
            );
        }
        assert!(shared_cache_under(Path::new("relative-user-root")).is_err());
    }

    #[cfg(any(target_os = "macos", target_os = "linux"))]
    #[test]
    fn relative_credential_destination_is_rejected_before_opening_key_store() {
        let credential = InstallCredential {
            device_id: "public-fixture-not-an-activation".into(),
            uid: 7,
            signing_private: Zeroizing::default(),
            encryption_private: Zeroizing::default(),
            token_seed: Zeroizing::default(),
            certificate: String::new(),
            game_uuid: INSTALLER_UUID.into(),
            game_name: INSTALLER_NAME.into(),
        };
        let error =
            save_credential(Path::new("relative-auth/device.bin"), &credential).unwrap_err();
        assert!(error.to_string().contains("absolute account directory"));
    }
    #[test]
    fn authentication_errors_redact_request_secrets_before_truncation() {
        let key = "fixture-only-license-key-not-a-live-key";
        let reason =
            json!({"detail": format!("Rejected key {key}: owner access required")}).to_string();
        let displayed = short_detail(&reason, &json!({"license_key":key}));
        assert!(!displayed.contains(key));
        assert!(displayed.contains("[redacted]"));
        assert!(displayed.contains("owner access required"));
        assert_eq!(short_detail("invalid JSON", &json!({})), "request denied");
    }
}

/// Secrets deliberately have no Debug/Serialize implementation or UI representation.
pub(crate) struct Session {
    pub grant: String,
    pub device: String,
    pub uid: u64,
    credential: InstallCredential,
}

fn user_cache() -> Result<PathBuf> {
    let root = crate::user_profile::current_local_app_data()?;
    shared_cache_under(&root)
}

// Exact existing native-verifier/main.rs user_binding_dir layout, not a beta cache.
fn shared_cache_under(root: &Path) -> Result<PathBuf> {
    if !root.is_absolute() {
        bail!("Shared activation cache requires an absolute account directory");
    }
    Ok(root
        .join("Celeste")
        .join("auth")
        .join(release::LAUNCHER_CHANNEL_ID))
}

/// Reuse only this product's selected binding and our account-protected identity cache.
/// Generic device certificates can serve a Both key; every grant is product scoped.
pub(crate) fn authenticate(game: &Path, license: &str) -> Result<Session> {
    authenticate_for_setup(Some(game), license)
}

/// Activation is independent of Minecraft being installed or running.
/// The UI gate only controls setup; every later Load still authenticates afresh.
pub(crate) fn activate(license: &str) -> Result<bool> {
    match authenticate_for_setup(None, license) {
        Ok(_) => Ok(true),
        Err(error) if error.downcast_ref::<NoSavedActivation>().is_some() => Ok(false),
        Err(error) => Err(error),
    }
}

#[derive(Debug)]
struct NoSavedActivation;
impl std::fmt::Display for NoSavedActivation {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Enter your Celeste+ or Both license to activate this computer")
    }
}
impl std::error::Error for NoSavedActivation {}

fn authenticate_for_setup(game: Option<&Path>, license: &str) -> Result<Session> {
    let license = license.trim();
    if !license.is_empty() && !(20..=96).contains(&license.len()) {
        bail!("Enter the complete license key");
    }
    let cache = user_cache()?;
    let active_path = cache.join("celeste-plus-active.bin");
    let pinned = license.is_empty() && active_path.is_file();
    let mut paths = vec![active_path.clone()];
    if let Some(game) = game {
        let auth_dir = game.join("config").join("celeste").join("auth");
        paths.extend([
            auth_dir.join("celeste_plus").join("device.bin"),
            auth_dir.join("installer.bin"),
            auth_dir.join("device.bin"),
        ]);
    }
    if cache.is_dir() {
        let mut saved = fs::read_dir(&cache)?
            .filter_map(Result::ok)
            .map(|entry| entry.path())
            .filter(|path| {
                path.file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| {
                        name.strip_prefix("uid-")
                            .and_then(|s| s.strip_suffix(".bin"))
                            .is_some_and(|s| s.parse::<u64>().is_ok_and(|uid| uid > 0))
                    })
                    && path.is_file()
            })
            .collect::<Vec<_>>();
        saved.sort();
        paths.extend(saved);
    }
    let discovery_failure = if pinned {
        // Reopening or changing Minecraft profiles must not silently choose a
        // different license. A replacement key is an explicit setup action.
        paths.truncate(1);
        None
    } else {
        // Earlier versions could save only inside a Minecraft profile. Migrate
        // those signed bindings before considering a new enrollment so an
        // existing customer is not sent back to an already-consumed key.
        append_recovery_bindings(&mut paths, crate::platform::modrinth_profiles())
    };
    // A first-time user can enter their key before hardware/profile/process setup.
    if license.is_empty() && !paths.iter().any(|path| path.is_file()) {
        return Err(NoSavedActivation.into());
    }
    let hardware = collect_hardware()?;
    let mut seen = std::collections::HashSet::new();
    let mut rejected = None;
    let mut local_failure = None;
    let mut protection_failure = None;
    #[cfg(target_os = "macos")]
    let mut runtime_reader_verified = false;
    for path in paths.into_iter().filter(|path| path.is_file()) {
        let credential = match load_credential(&path) {
            Ok(value) => value,
            Err(error) => {
                remember_local_failure(error, &mut protection_failure, &mut local_failure);
                continue;
            }
        };
        if !seen.insert(credential.device_id.clone()) {
            continue;
        }
        #[cfg(target_os = "macos")]
        if !runtime_reader_verified {
            // A native read alone does not prove Minecraft's fixed OS reader
            // has permission. Check before server auth or declaring setup ready.
            crate::credential_protection::validate_runtime_reader()?;
            runtime_reader_verified = true;
        }
        match authenticate_installer(&credential, &hardware, license) {
            Ok(value) => {
                let session = product_session(credential, value)?;
                save_setup_binding(&cache, &session.credential)?;
                return Ok(session);
            }
            Err(error) if is_unauthorized(&error) => rejected = Some(error),
            Err(error) => return Err(error),
        }
    }
    if let Some(error) = discovery_failure {
        // A redirected/unavailable Modrinth database must not prevent reuse of
        // an already valid account cache. If reuse failed, however, do not
        // consume another device slot with an incomplete recovery search.
        return Err(error.context(
            "Could not check existing profile activations. Restore access to this account's Modrinth data and retry; no new device was enrolled",
        ));
    }
    if license.is_empty() {
        if let Some(error) = rejected {
            return Err(error.context("Saved activation is unavailable for Celeste+. Enter a current Celeste+ or Both license"));
        }
        if let Some(error) = protection_failure.or(local_failure) {
            return Err(error.context("Saved activation could not be opened. Use the account that activated this computer, or enter a current license"));
        }
        return Err(NoSavedActivation.into());
    }
    if let Some(error) = protection_failure {
        return Err(error.context("The operating system could not open a saved activation. Sign in to the account that created it and unlock its password store before retrying; no new device was enrolled"));
    }
    let credential = enroll(license, &hardware)?;
    save_credential(
        &cache.join(format!("uid-{}.bin", credential.uid)),
        &credential,
    )?;
    let value = authenticate_installer(&credential, &hardware, license)?;
    let session = product_session(credential, value)?;
    save_setup_binding(&cache, &session.credential)?;
    Ok(session)
}

fn append_profile_bindings(paths: &mut Vec<PathBuf>, game: &Path) {
    let root = game.join("config").join("celeste").join("auth");
    paths.extend([
        root.join("celeste_plus").join("device.bin"),
        root.join("installer.bin"),
        root.join("device.bin"),
    ]);
}

fn append_recovery_bindings(
    paths: &mut Vec<PathBuf>,
    profiles: Result<Vec<crate::platform::GameProfile>>,
) -> Option<anyhow::Error> {
    match profiles {
        Ok(profiles) => {
            for profile in profiles {
                append_profile_bindings(paths, &profile.game);
            }
            None
        }
        Err(error) => Some(error),
    }
}

fn remember_local_failure(
    error: anyhow::Error,
    protection: &mut Option<anyhow::Error>,
    other: &mut Option<anyhow::Error>,
) {
    if error
        .downcast_ref::<crate::credential_protection::ProtectionError>()
        .is_some()
    {
        if protection.is_none() {
            *protection = Some(error);
        }
    } else if other.is_none() {
        *other = Some(error);
    }
}

fn save_setup_binding(cache: &Path, credential: &InstallCredential) -> Result<()> {
    save_credential(
        &cache.join(format!("uid-{}.bin", credential.uid)),
        credential,
    )
    .context("Could not save this account's activation")?;
    save_credential(&cache.join("celeste-plus-active.bin"), credential)
        .context("Could not remember this Celeste+ activation")
}

pub(crate) fn needs_activation(error: &anyhow::Error) -> bool {
    is_unauthorized(error) || error.downcast_ref::<NoSavedActivation>().is_some()
}

fn product_session(credential: InstallCredential, value: Value) -> Result<Session> {
    let grant = required_text(&value, "/grant")?.to_owned();
    let claims = verify_token(&grant, "celeste-installer")?;
    crate::dpop::require_binding(&claims, &signing_key(&credential.signing_private)?)?;
    if credential.uid == 0
        || required_text(&claims, "/sub")? != credential.uid.to_string()
        || required_text(&claims, "/product")? != "celeste_plus"
        || required_text(&claims, "/device")? != credential.device_id
    {
        bail!("Installer authorization does not match this Celeste+ activation");
    }
    Ok(Session {
        grant,
        uid: credential.uid,
        device: credential.device_id.clone(),
        credential,
    })
}

impl Session {
    /// Only the proof leaves this capability; no installation key is handed to
    /// delivery code or added to attach IPC, logs, or a new credential file.
    pub(crate) fn delivery_proof(&self, method: &str, url: &str) -> Result<String> {
        let key = signing_key(&self.credential.signing_private)?;
        let claims = verify_token(&self.grant, "celeste-installer")?;
        crate::dpop::require_binding(&claims, &key)?;
        crate::dpop::proof(&key, method, url, Some(&self.grant))
    }

    /// Caller must first verify the selected PID's canonical game directory.
    pub fn prepare_runtime_credential(&self, game: &Path) -> Result<()> {
        let target = game
            .join("config")
            .join("celeste")
            .join("auth")
            .join("celeste_plus")
            .join("device.bin");
        // Only a freshly server-authorized identity may update this product's binding.
        // The standard Celeste binding and generic cached identities remain independent.
        save_credential(&target, &self.credential).context(
            "Could not save the account-protected Celeste+ activation in the selected instance",
        )
    }
}
