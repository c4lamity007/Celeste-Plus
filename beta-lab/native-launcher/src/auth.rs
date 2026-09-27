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
#[cfg(test)]
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
    code: Option<String>,
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

fn http_failure(status: u16, path: &str, detail: &str, code: Option<String>) -> anyhow::Error {
    HttpFailure {
        status,
        path: path.to_owned(),
        detail: detail.to_owned(),
        code,
    }
    .into()
}

#[cfg(windows)]
fn collect_hardware() -> Result<HardwareProfile> {
    crate::debug_guard::enforce();
    crate::environment_guard::require_allowed()?;
    crate::native_manifest::require_current("celeste_plus")?;
    let com = COMLibrary::new()
        .context(obfstr::obfstr!("Windows hardware services are unavailable").to_owned())?;
    let wmi = WMIConnection::new(com)
        .context(obfstr::obfstr!("Could not connect to Windows hardware services").to_owned())?;
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
    crate::debug_guard::enforce();
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
    preflight().context(
        obfstr::obfstr!("Activation storage is unavailable; no new device was enrolled").to_owned(),
    )?;
    enroll_remote()
}

fn enroll_remote(license: &str, hardware: &HardwareProfile) -> Result<InstallCredential> {
    let signing = SigningKey::generate(&mut OsRng);
    let encryption = StaticSecret::random_from_rng(OsRng);
    let encryption_public = X25519PublicKey::from(&encryption);
    let hwid = crate::hardware_v2::current()?;
    let start = post_json(
        obfstr::obfstr!("/v1/enroll/start"),
        &with_hardware_v2(json!({
            (obfstr::obfstr!("product")): "celeste_plus",
            (obfstr::obfstr!("release_id")): release::LAUNCHER_CHANNEL_ID,
            (obfstr::obfstr!("client_version")): release::LAUNCHER_PROTOCOL_VERSION,
            (obfstr::obfstr!("client_sha256")): release::LAUNCHER_CHANNEL_DIGEST,
            (obfstr::obfstr!("game_uuid")): INSTALLER_UUID,
            (obfstr::obfstr!("game_name")): INSTALLER_NAME,
            (obfstr::obfstr!("license_key")): license,
            (obfstr::obfstr!("install_signing_key")): encode(signing.verifying_key().as_bytes()),
            (obfstr::obfstr!("install_encryption_key")): encode(encryption_public.as_bytes()),
            (obfstr::obfstr!("hardware")): hardware,
        }), hwid.as_deref()),
    ).map_err(|error| {
        if error.downcast_ref::<HttpFailure>().is_some_and(|failure| failure.status == 409) {
            error.context(obfstr::obfstr!("This license is already activated. Select its original instance and account, or contact support if the saved activation was lost").to_owned())
        } else {
            error.context(obfstr::obfstr!("License activation failed").to_owned())
        }
    })?;
    let message = decode(required_text(&start, obfstr::obfstr!("/message"))?)?;
    let transcript: Value = serde_json::from_slice(&message)?;
    verify_hardware_v2(&transcript, hwid.as_deref())?;
    if required_text(&transcript, obfstr::obfstr!("/product"))? != "celeste_plus" {
        bail!(
            "{}",
            obfstr::obfstr!("Authentication challenge belongs to another product")
        );
    }
    let complete = post_json_with_dpop(
        obfstr::obfstr!("/v1/enroll/complete"),
        &json!({
            (obfstr::obfstr!("challenge_id")): required_text(&start, obfstr::obfstr!("/challenge_id"))?,
            (obfstr::obfstr!("install_signature")): encode(&signing.sign(&message).to_bytes()),
        }),
        Some(&signing),
    )?;
    let certificate = required_text(&complete, obfstr::obfstr!("/device_certificate"))?.to_owned();
    let certificate_claims = verify_token(&certificate, obfstr::obfstr!("celeste-installation"))?;
    crate::dpop::require_binding(&certificate_claims, &signing)?;
    let device_id = required_text(&complete, obfstr::obfstr!("/device_id"))?.to_owned();
    let uid = complete
        .pointer(obfstr::obfstr!("/uid"))
        .and_then(Value::as_u64)
        .ok_or_else(|| anyhow!("{}", obfstr::obfstr!("Enrollment response omitted uid")))?;
    if required_text(&certificate_claims, obfstr::obfstr!("/device"))? != device_id
        || required_text(&certificate_claims, obfstr::obfstr!("/sub"))? != uid.to_string()
        || required_text(&certificate_claims, obfstr::obfstr!("/install_key"))?
            != encode(signing.verifying_key().as_bytes())
    {
        bail!(
            "{}",
            obfstr::obfstr!("Installation certificate does not match this device")
        );
    }
    let seed = decrypt_token(
        complete
            .pointer(obfstr::obfstr!("/token_envelope"))
            .ok_or_else(|| {
                anyhow!(
                    "{}",
                    obfstr::obfstr!("Enrollment response omitted token envelope")
                )
            })?,
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
    let hwid = crate::hardware_v2::current()?;
    let start = post_json(
        obfstr::obfstr!("/v1/installer/start"),
        &with_hardware_v2(
            json!({
                (obfstr::obfstr!("product")): "celeste_plus",
                (obfstr::obfstr!("release_id")): release::LAUNCHER_CHANNEL_ID,
                (obfstr::obfstr!("client_version")): release::LAUNCHER_PROTOCOL_VERSION,
                (obfstr::obfstr!("client_sha256")): release::LAUNCHER_CHANNEL_DIGEST,
                (obfstr::obfstr!("game_uuid")): INSTALLER_UUID,
                (obfstr::obfstr!("game_name")): INSTALLER_NAME,
                (obfstr::obfstr!("device_id")): credential.device_id,
                (obfstr::obfstr!("license_key")): if license.is_empty() { None } else { Some(license) },
                (obfstr::obfstr!("hardware")): hardware,
            }),
            hwid.as_deref(),
        ),
    )?;
    let message = decode(required_text(&start, obfstr::obfstr!("/message"))?)?;
    let transcript: Value = serde_json::from_slice(&message)?;
    verify_hardware_v2(&transcript, hwid.as_deref())?;
    if required_text(&transcript, obfstr::obfstr!("/product"))? != "celeste_plus" {
        bail!(
            "{}",
            obfstr::obfstr!("Authentication challenge belongs to another product")
        );
    }
    let complete = post_json_with_dpop(
        obfstr::obfstr!("/v1/installer/complete"),
        &json!({
            (obfstr::obfstr!("challenge_id")): required_text(&start, obfstr::obfstr!("/challenge_id"))?,
            (obfstr::obfstr!("install_signature")): encode(&signing.sign(&message).to_bytes()),
            (obfstr::obfstr!("token_signature")): encode(&token.sign(&message).to_bytes()),
        }),
        Some(&signing),
    )?;
    let grant = required_text(&complete, obfstr::obfstr!("/grant"))?;
    let claims = verify_token(grant, obfstr::obfstr!("celeste-installer"))?;
    crate::dpop::require_binding(&claims, &signing)?;
    let bootstrap_url = required_text(&complete, obfstr::obfstr!("/bootstrap/url"))?;
    let bootstrap_id = bootstrap_url.rsplit('/').next().unwrap_or("");
    let bootstrap_sha = required_text(&complete, obfstr::obfstr!("/bootstrap/sha256"))?;
    if required_text(&claims, obfstr::obfstr!("/product"))? != "celeste_plus"
        || required_text(&claims, obfstr::obfstr!("/sub"))? != credential.uid.to_string()
        || required_text(&claims, obfstr::obfstr!("/device"))? != credential.device_id
        || claims
            .get(obfstr::obfstr!("auth_protocol"))
            .and_then(Value::as_u64)
            != Some(2)
        || required_text(&claims, obfstr::obfstr!("/release"))? != release::LAUNCHER_CHANNEL_ID
        || required_text(&claims, obfstr::obfstr!("/client_sha256"))?
            != release::LAUNCHER_CHANNEL_DIGEST
        || required_text(&claims, obfstr::obfstr!("/bootstrap"))? != bootstrap_id
        || required_text(&claims, obfstr::obfstr!("/bootstrap_sha256"))? != bootstrap_sha
    {
        bail!(
            "{}",
            obfstr::obfstr!("Installer grant does not match this device or release")
        );
    }
    Ok(complete)
}

fn with_hardware_v2(mut request: Value, hwid: Option<&str>) -> Value {
    if let Some(hwid) = hwid {
        request[obfstr::obfstr!("hwid_sha256")] = json!(hwid);
    }
    request
}

fn verify_hardware_v2(transcript: &Value, hwid: Option<&str>) -> Result<()> {
    if let Some(hwid) = hwid {
        if transcript
            .get(obfstr::obfstr!("protocol"))
            .and_then(Value::as_str)
            != Some(obfstr::obfstr!("cst-auth-2"))
            || transcript
                .get(obfstr::obfstr!("hwid_sha256"))
                .and_then(Value::as_str)
                != Some(hwid)
        {
            bail!(
                "{}",
                obfstr::obfstr!(
                    "Authentication challenge does not bind the current hardware identity"
                )
            );
        }
    } else if transcript
        .get(obfstr::obfstr!("protocol"))
        .and_then(Value::as_str)
        != Some(obfstr::obfstr!("cst-auth-1"))
        || transcript.get(obfstr::obfstr!("hwid_sha256")).is_some()
    {
        bail!(
            "{}",
            obfstr::obfstr!("Authentication challenge changed the hardware protocol")
        );
    }
    Ok(())
}

fn decrypt_token(
    envelope: &Value,
    private: &StaticSecret,
    message: &[u8],
) -> Result<Zeroizing<[u8; 32]>> {
    let ephemeral = fixed_32(&decode(required_text(
        envelope,
        obfstr::obfstr!("/ephemeral_key"),
    )?)?)?;
    let nonce = decode(required_text(envelope, obfstr::obfstr!("/nonce"))?)?;
    if nonce.len() != 12 {
        bail!("{}", obfstr::obfstr!("Token envelope nonce is invalid"));
    }
    let mut plain = Zeroizing::new(decode(required_text(
        envelope,
        obfstr::obfstr!("/ciphertext"),
    )?)?);
    let transcript: Value = serde_json::from_slice(message)?;
    let salt = decode(required_text(&transcript, obfstr::obfstr!("/nonce"))?)?;
    if salt.len() != 32 {
        bail!("{}", obfstr::obfstr!("Token envelope salt is invalid"));
    }
    let shared = private.diffie_hellman(&X25519PublicKey::from(ephemeral));
    let hkdf = Hkdf::<Sha256>::new(Some(&salt), shared.as_bytes());
    let mut wrapping = Zeroizing::new([0u8; 32]);
    hkdf.expand(
        obfstr::obfstr!("celeste-install-token-v1").as_bytes(),
        &mut wrapping[..],
    )
    .map_err(|_| anyhow!("{}", obfstr::obfstr!("Token key derivation failed")))?;
    let cipher = Aes256Gcm::new_from_slice(&wrapping[..])
        .map_err(|_| anyhow!("{}", obfstr::obfstr!("Token cipher could not start")))?;
    cipher
        .decrypt_in_place(Nonce::from_slice(&nonce), message, &mut *plain)
        .map_err(|_| {
            anyhow!(
                "{}",
                obfstr::obfstr!("Installation token envelope was rejected")
            )
        })?;
    if plain.len() != 32 {
        bail!("{}", obfstr::obfstr!("Expected a 32-byte key"));
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
    let agent = crate::transport_policy::agent(release::BASE_URL)?;
    let mut request = agent
        .post(&url)
        .set(
            obfstr::obfstr!("Content-Type"),
            obfstr::obfstr!("application/json"),
        )
        .set(
            obfstr::obfstr!("Accept"),
            obfstr::obfstr!("application/json"),
        );
    if let Some(signing) = signing {
        request = request.set(
            obfstr::obfstr!("DPoP"),
            &crate::dpop::proof(signing, "POST", &url, None)?,
        );
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
                bail!(
                    "{}",
                    obfstr::obfstr!("Authentication response exceeds size bounds")
                );
            }
            serde_json::from_slice(&bytes)
                .context(obfstr::obfstr!("Server returned invalid JSON").to_owned())
        }
        Err(ureq::Error::Status(code, response)) => {
            let mut detail = String::new();
            let _ = response
                .into_reader()
                .take(4_097)
                .read_to_string(&mut detail);
            Err(http_failure(
                code,
                path,
                &short_detail(&detail, body),
                response_code(&detail),
            ))
        }
        Err(error) => Err(anyhow!(error)
            .context(obfstr::obfstr!("Authentication server is unreachable").to_owned())),
    }
}

fn response_code(body: &str) -> Option<String> {
    let value: Value = serde_json::from_str(body).ok()?;
    value
        .get(obfstr::obfstr!("error_code"))
        .and_then(Value::as_str)
        .filter(|value| {
            value.len() <= 80
                && value
                    .bytes()
                    .all(|byte| byte.is_ascii_lowercase() || byte == b'_')
        })
        .map(str::to_owned)
}

pub(crate) fn resource_failure(path: &str, status: u16, response: ureq::Response) -> anyhow::Error {
    let mut body = String::new();
    let _ = response.into_reader().take(4097).read_to_string(&mut body);
    http_failure(
        status,
        path,
        obfstr::obfstr!("Resource request denied"),
        response_code(&body),
    )
}

fn authoritative_rejection(error: &anyhow::Error) -> bool {
    error.downcast_ref::<HttpFailure>().is_some_and(|failure| {
        failure.status == 401
            && matches!(
                failure.code.as_deref(),
                Some(
                    "device_mismatch"
                        | "subscription_inactive"
                        | "token_revoked"
                        | "token_rotated"
                        | "reactivation_required"
                )
            )
    })
}

fn endpoint(path: &str) -> Result<String> {
    if !release::BASE_URL.starts_with("https://")
        || !path.starts_with(obfstr::obfstr!("/v1/"))
        || path.contains("://")
        || path.contains("..")
        || path.contains(['?', '#', '%', '\\'])
    {
        bail!(
            "{}",
            obfstr::obfstr!("Server response referenced an untrusted endpoint")
        );
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
        bail!(
            "{}",
            obfstr::obfstr!("Server returned a malformed signed token")
        );
    }
    let key = VerifyingKey::from_bytes(&fixed_32(&decode(release::SERVER_KEY)?)?)?;
    let signature = Signature::from_slice(&decode(parts[2])?)?;
    key.verify(format!("{}.{}", parts[0], parts[1]).as_bytes(), &signature)
        .context(obfstr::obfstr!("Server token signature is invalid").to_owned())?;
    let header: Value = serde_json::from_slice(&decode(parts[0])?)?;
    let claims: Value = serde_json::from_slice(&decode(parts[1])?)?;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)?
        .as_secs();
    if required_text(&header, obfstr::obfstr!("/alg"))? != obfstr::obfstr!("EdDSA")
        || required_text(&header, obfstr::obfstr!("/typ"))? != obfstr::obfstr!("CST")
        || required_text(&claims, obfstr::obfstr!("/aud"))? != audience
        || claims
            .pointer(obfstr::obfstr!("/nbf"))
            .and_then(Value::as_u64)
            .unwrap_or(0)
            > now + 5
        || claims
            .pointer(obfstr::obfstr!("/exp"))
            .and_then(Value::as_u64)
            .unwrap_or(0)
            <= now
    {
        bail!(
            "{}",
            obfstr::obfstr!("Server token is expired or has the wrong audience")
        );
    }
    Ok(claims)
}

fn signing_key(encoded: &str) -> Result<SigningKey> {
    let mut value = Zeroizing::new(Vec::new());
    URL_SAFE_NO_PAD
        .decode_vec(encoded, &mut value)
        .context(obfstr::obfstr!("Invalid base64url value").to_owned())?;
    if value.len() == 32 {
        return Ok(SigningKey::from_bytes(value.as_slice().try_into()?));
    }
    SigningKey::from_pkcs8_der(&value)
        .context(obfstr::obfstr!("Saved signing key is invalid").to_owned())
}

fn load_credential(path: &Path) -> Result<InstallCredential> {
    let stored = fs::read(path)?;
    if stored.len() <= STORE_MAGIC.len() || &stored[..STORE_MAGIC.len()] != STORE_MAGIC {
        bail!("{}", obfstr::obfstr!("Credential envelope is invalid"));
    }
    let plain = Zeroizing::new(unprotect_credential(&stored[STORE_MAGIC.len()..])?);
    let credential: InstallCredential = serde_json::from_slice(&plain)?;
    if credential.device_id.len() < 32 || credential.uid == 0 || credential.certificate.is_empty() {
        bail!("{}", obfstr::obfstr!("Credential data is incomplete"));
    }
    let signing = signing_key(&credential.signing_private)?;
    signing_key(&credential.token_seed)?;
    let claims = verify_token(
        &credential.certificate,
        obfstr::obfstr!("celeste-installation"),
    )?;
    // Existing signed install_key certificates remain reusable; fresh grants
    // always require cnf and cannot fall back to bearer authorization.
    if claims.get(obfstr::obfstr!("cnf")).is_some() {
        crate::dpop::require_binding(&claims, &signing)?;
    }
    let public_key = encode(signing.verifying_key().as_bytes());
    if required_text(&claims, obfstr::obfstr!("/sub"))? != credential.uid.to_string()
        || required_text(&claims, obfstr::obfstr!("/device"))? != credential.device_id
        || required_text(&claims, obfstr::obfstr!("/install_key"))? != public_key
    {
        bail!(
            "{}",
            obfstr::obfstr!("Saved installation credential does not match its signed certificate")
        );
    }
    Ok(credential)
}

fn save_credential(path: &Path, credential: &InstallCredential) -> Result<()> {
    #[cfg(any(target_os = "macos", target_os = "linux"))]
    crate::user_profile::ensure_private_activation_dir(path.parent().ok_or_else(|| {
        anyhow!(
            "{}",
            obfstr::obfstr!("Credential storage has no parent directory")
        )
    })?)?;
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
        .context(obfstr::obfstr!("Invalid base64url value").to_owned())
}

fn fixed_32(value: &[u8]) -> Result<[u8; 32]> {
    value
        .try_into()
        .map_err(|_| anyhow!("{}", obfstr::obfstr!("Expected a 32-byte key")))
}

fn short_detail(value: &str, request: &Value) -> String {
    let parsed: Value = serde_json::from_str(value).unwrap_or(Value::Null);
    let mut detail = parsed
        .pointer(obfstr::obfstr!("/detail"))
        .and_then(Value::as_str)
        .unwrap_or("request denied")
        .to_owned();
    // Redact before truncation so a server echo cannot reveal a partial license
    // or signature. Keep the actual bounded server reason useful to the user.
    for key in [
        obfstr::obfstr!("license_key"),
        "install_signature",
        obfstr::obfstr!("token_signature"),
        obfstr::obfstr!("grant"),
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
    fn typed_remote_denials_clear_only_transient_grants_and_preserve_installation() {
        let credential = InstallCredential {
            device_id: "fixture-device".into(),
            uid: 7,
            signing_private: "private-signing-fixture".to_owned().into(),
            encryption_private: "private-encryption-fixture".to_owned().into(),
            token_seed: "private-token-fixture".to_owned().into(),
            certificate: "signed-installation-fixture".into(),
            game_uuid: INSTALLER_UUID.into(),
            game_name: INSTALLER_NAME.into(),
        };
        let before = serde_json::to_vec(&credential).unwrap();
        for code in [
            "device_mismatch",
            "subscription_inactive",
            "token_revoked",
            "token_rotated",
            "reactivation_required",
        ] {
            let error = http_failure(401, "/v1/validate", "denied", Some(code.into()));
            assert!(authoritative_rejection(&error));
            let mut session = Session {
                grant: "transient-token".into(),
                device: credential.device_id.clone(),
                uid: 7,
                credential: credential.clone(),
            };
            session
                .reject_if_authoritative(Path::new("unused-fixture-path"), &error)
                .unwrap();
            assert!(session.grant.is_empty());
            assert_eq!(serde_json::to_vec(&session.credential).unwrap(), before);
            assert!(!authoritative_rejection(&http_failure(
                403,
                "/v1/validate",
                "denied",
                Some(code.into())
            )));
        }
        for code in [
            None,
            Some("unauthorized"),
            Some("concurrent_session_rejected"),
        ] {
            assert!(!authoritative_rejection(&http_failure(
                401,
                "/v1/validate",
                "denied",
                code.map(str::to_owned)
            )));
        }
    }
    #[test]
    fn rotation_retries_are_bounded_and_denial_uses_fresh_dual_auth_once() {
        let attempts = std::cell::Cell::new(0);
        let reauth = std::cell::Cell::new(0);
        let value = rotate_or_reauthenticate(
            || {
                attempts.set(attempts.get() + 1);
                Err(anyhow!("{}", obfstr::obfstr!("offline")))
            },
            || {
                reauth.set(reauth.get() + 1);
                Ok(42)
            },
        )
        .unwrap();
        assert_eq!((value, attempts.get(), reauth.get()), (42, 3, 1));
        attempts.set(0);
        reauth.set(0);
        assert_eq!(
            rotate_or_reauthenticate(
                || {
                    attempts.set(attempts.get() + 1);
                    Ok(7)
                },
                || {
                    reauth.set(1);
                    Ok(42)
                }
            )
            .unwrap(),
            7
        );
        assert_eq!((attempts.get(), reauth.get()), (1, 0));
        attempts.set(0);
        let result: Result<i32> = rotate_or_reauthenticate(
            || {
                attempts.set(attempts.get() + 1);
                Err(http_failure(
                    401,
                    "/v1/rotate",
                    "denied",
                    Some("device_mismatch".into()),
                ))
            },
            || {
                reauth.set(1);
                Ok(42)
            },
        );
        assert_eq!(result.unwrap(), 42);
        assert_eq!((attempts.get(), reauth.get()), (1, 1));
    }
    #[test]
    fn optional_hardware_digest_is_signed_without_changing_legacy_profile() {
        let legacy =
            json!({"hardware":{"system":"s","board":"b","processor":"p","monitors":["m"]}});
        assert_eq!(with_hardware_v2(legacy.clone(), None), legacy);
        let digest = "a".repeat(64);
        let request = with_hardware_v2(legacy.clone(), Some(&digest));
        assert_eq!(request["hardware"], legacy["hardware"]);
        assert_eq!(request["hwid_sha256"], digest);
        let correct = json!({"protocol":"cst-auth-2","hwid_sha256":digest});
        verify_hardware_v2(&correct, Some(&digest)).unwrap();
        verify_hardware_v2(&json!({"protocol":"cst-auth-1"}), None).unwrap();
        assert!(verify_hardware_v2(&correct, None).is_err());
        assert!(
            verify_hardware_v2(&json!({"protocol":"cst-auth-1","hwid_sha256":digest}), None)
                .is_err()
        );
        for wrong in [
            json!({"protocol":"cst-auth-1","hwid_sha256":digest}),
            json!({"protocol":"cst-auth-2","hwid_sha256":"b".repeat(64)}),
            json!({"protocol":"cst-auth-2"}),
        ] {
            assert!(verify_hardware_v2(&wrong, Some(&digest)).is_err());
        }
    }
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
            Err(anyhow!(
                "{}",
                obfstr::obfstr!("Modrinth database is temporarily unavailable")
            )),
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
            let malformed = anyhow!("{}", obfstr::obfstr!("Credential envelope is invalid"));
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

impl Drop for Session {
    fn drop(&mut self) {
        use zeroize::Zeroize;
        self.grant.zeroize();
    }
}

fn user_cache() -> Result<PathBuf> {
    let root = crate::user_profile::current_local_app_data()?;
    shared_cache_under(&root)
}

// Exact existing native-verifier/main.rs user_binding_dir layout, not a beta cache.
fn shared_cache_under(root: &Path) -> Result<PathBuf> {
    if !root.is_absolute() {
        bail!(
            "{}",
            obfstr::obfstr!("Shared activation cache requires an absolute account directory")
        );
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
        bail!("{}", obfstr::obfstr!("Enter the complete license key"));
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
            return Err(error.context(obfstr::obfstr!("Saved activation is unavailable for Celeste+. Enter a current Celeste+ or Both license").to_owned()));
        }
        if let Some(error) = protection_failure.or(local_failure) {
            return Err(error.context(obfstr::obfstr!("Saved activation could not be opened. Use the account that activated this computer, or enter a current license").to_owned()));
        }
        return Err(NoSavedActivation.into());
    }
    if let Some(error) = protection_failure {
        return Err(error.context(obfstr::obfstr!("The operating system could not open a saved activation. Sign in to the account that created it and unlock its password store before retrying; no new device was enrolled").to_owned()));
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
    .context(obfstr::obfstr!("Could not save this account's activation").to_owned())?;
    save_credential(&cache.join("celeste-plus-active.bin"), credential)
        .context(obfstr::obfstr!("Could not remember this Celeste+ activation").to_owned())
}

pub(crate) fn needs_activation(error: &anyhow::Error) -> bool {
    is_unauthorized(error) || error.downcast_ref::<NoSavedActivation>().is_some()
}

fn product_session(credential: InstallCredential, value: Value) -> Result<Session> {
    let grant = required_text(&value, obfstr::obfstr!("/grant"))?.to_owned();
    let claims = verify_token(&grant, obfstr::obfstr!("celeste-installer"))?;
    crate::dpop::require_binding(&claims, &signing_key(&credential.signing_private)?)?;
    if credential.uid == 0
        || required_text(&claims, obfstr::obfstr!("/sub"))? != credential.uid.to_string()
        || required_text(&claims, obfstr::obfstr!("/product"))? != "celeste_plus"
        || required_text(&claims, obfstr::obfstr!("/device"))? != credential.device_id
        || claims
            .get(obfstr::obfstr!("auth_protocol"))
            .and_then(Value::as_u64)
            != Some(2)
    {
        bail!(
            "{}",
            obfstr::obfstr!("Installer authorization does not match this Celeste+ activation")
        );
    }
    Ok(Session {
        grant,
        uid: credential.uid,
        device: credential.device_id.clone(),
        credential,
    })
}

fn rotate_or_reauthenticate<T>(
    mut rotate: impl FnMut() -> Result<T>,
    reauthenticate: impl FnOnce() -> Result<T>,
) -> Result<T> {
    for _ in 0..3 {
        match rotate() {
            Ok(value) => return Ok(value),
            Err(error) if authoritative_rejection(&error) => break,
            Err(_) => {}
        }
    }
    reauthenticate()
}

impl Session {
    pub(crate) fn reject_if_authoritative(
        &mut self,
        _game: &Path,
        error: &anyhow::Error,
    ) -> Result<()> {
        if authoritative_rejection(error) {
            self.invalidate_grant();
        }
        Ok(())
    }
    /// Short-lived installer grants are checked online immediately before use.
    /// Rotation happens before their one-use handoff; no grant is persisted.
    #[inline(never)]
    pub(crate) fn validate_for_launch(&mut self, _game: &Path) -> Result<()> {
        let claims = verify_token(&self.grant, obfstr::obfstr!("celeste-installer"))?;
        let expiration = claims
            .get(obfstr::obfstr!("exp"))
            .and_then(Value::as_u64)
            .ok_or_else(|| anyhow!("{}", obfstr::obfstr!("Installer grant has no expiration")))?;
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)?
            .as_secs();
        if expiration.saturating_sub(now) < 120 {
            let response = match rotate_or_reauthenticate(
                || self.grant_request(obfstr::obfstr!("/v1/rotate")),
                || authenticate_installer(&self.credential, &collect_hardware()?, ""),
            ) {
                Ok(value) => value,
                Err(error) => {
                    if authoritative_rejection(&error) {
                        self.invalidate_grant();
                    }
                    return Err(error);
                }
            };
            self.adopt_authorization(response, &claims)?;
        }
        let validation = self.grant_request(obfstr::obfstr!("/v1/validate"));
        let validation = match validation {
            Err(error) if self.recover_authorization(&error)? => {
                self.grant_request(obfstr::obfstr!("/v1/validate"))
            }
            other => other,
        };
        match validation {
            Ok(response) => {
                let claims = verify_token(&self.grant, obfstr::obfstr!("celeste-installer"))?;
                if response
                    .get(obfstr::obfstr!("status"))
                    .and_then(Value::as_str)
                    != Some("valid")
                    || response.get(obfstr::obfstr!("expires_epoch"))
                        != claims.get(obfstr::obfstr!("exp"))
                {
                    bail!(
                        "{}",
                        obfstr::obfstr!(
                            "Server validation did not match the current installer grant"
                        )
                    );
                }
                Ok(())
            }
            Err(error) => {
                if authoritative_rejection(&error) {
                    self.invalidate_grant();
                }
                Err(error)
            }
        }
    }

    fn invalidate_grant(&mut self) {
        // Remote denial can concern one grant or a protocol migration. The same
        // durable installation keys/certificate are required for fresh dual proof.
        use zeroize::Zeroize;
        self.grant.zeroize();
    }

    pub(crate) fn recover_authorization(&mut self, error: &anyhow::Error) -> Result<bool> {
        if !authoritative_rejection(error) {
            return Ok(false);
        }
        let previous = verify_token(&self.grant, obfstr::obfstr!("celeste-installer"))?;
        self.invalidate_grant();
        let response = authenticate_installer(&self.credential, &collect_hardware()?, "")?;
        self.adopt_authorization(response, &previous)?;
        Ok(true)
    }

    fn adopt_authorization(&mut self, response: Value, previous: &Value) -> Result<()> {
        let fresh = product_session(self.credential.clone(), response)?;
        let current = verify_token(&fresh.grant, obfstr::obfstr!("celeste-installer"))?;
        for field in [
            obfstr::obfstr!("sub"),
            obfstr::obfstr!("device"),
            obfstr::obfstr!("product"),
            obfstr::obfstr!("release"),
            obfstr::obfstr!("client_sha256"),
            obfstr::obfstr!("payload_release"),
            obfstr::obfstr!("payload_sha256"),
            obfstr::obfstr!("bootstrap"),
            obfstr::obfstr!("bootstrap_sha256"),
        ] {
            if previous.get(field) != current.get(field) {
                bail!(
                    "{}",
                    obfstr::obfstr!("Reauthorization changed the selected release; retry Load")
                );
            }
        }
        *self = fresh;
        Ok(())
    }

    #[inline(never)]
    fn grant_request(&self, path: &str) -> Result<Value> {
        let hwid = if let Some(value) = crate::hardware_v2::current()? {
            value
        } else {
            let hardware = collect_hardware()?;
            // Exact existing backend canonical JSON, sorted keys and no whitespace.
            hex_sha256(serde_json::to_string(&json!({"board":hardware.board,"processor":hardware.processor,"system":hardware.system}))?.as_bytes())
        };
        let url = endpoint(path)?;
        let key = signing_key(&self.credential.signing_private)?;
        let proof =
            crate::dpop::proof_bound(&key, "POST", &url, Some(&self.grant), None, Some(&hwid))?;
        let agent = crate::transport_policy::agent(release::BASE_URL)?;
        match agent
            .post(&url)
            .set(
                obfstr::obfstr!("Authorization"),
                &format!("DPoP {}", self.grant),
            )
            .set(obfstr::obfstr!("DPoP"), &proof)
            .set(obfstr::obfstr!("X-Device-Id"), &hwid)
            .set(
                obfstr::obfstr!("Content-Type"),
                obfstr::obfstr!("application/json"),
            )
            .send_json(json!({}))
        {
            Ok(response) => {
                if response.status() != 200
                    || response.get_url() != url
                    || response
                        .header(obfstr::obfstr!("Content-Type"))
                        .and_then(|value| value.split(';').next())
                        != Some(obfstr::obfstr!("application/json"))
                    || response
                        .header(obfstr::obfstr!("Content-Encoding"))
                        .is_some_and(|value| value != obfstr::obfstr!("identity"))
                {
                    bail!("{}", obfstr::obfstr!("Invalid validation response"));
                }
                let mut bytes = Vec::new();
                response.into_reader().take(65537).read_to_end(&mut bytes)?;
                if bytes.len() > 65536 {
                    bail!("{}", obfstr::obfstr!("Oversized validation response"));
                }
                Ok(serde_json::from_slice(&bytes)?)
            }
            Err(ureq::Error::Status(status, response)) => {
                let mut body = String::new();
                let _ = response.into_reader().take(4097).read_to_string(&mut body);
                Err(http_failure(
                    status,
                    path,
                    &short_detail(&body, &json!({})),
                    response_code(&body),
                ))
            }
            Err(_) => bail!(
                "{}",
                obfstr::obfstr!("Connection required to verify license")
            ),
        }
    }

    /// Only the proof leaves this capability; no installation key is handed to
    /// delivery code or added to attach IPC, logs, or a new credential file.
    pub(crate) fn delivery_proof(&self, method: &str, url: &str) -> Result<String> {
        let key = signing_key(&self.credential.signing_private)?;
        let claims = verify_token(&self.grant, obfstr::obfstr!("celeste-installer"))?;
        crate::dpop::require_binding(&claims, &key)?;
        crate::dpop::proof(&key, method, url, Some(&self.grant))
    }

    pub(crate) fn artifact_proof(&self, url: &str, public: &str, nonce: &str) -> Result<String> {
        let key = signing_key(&self.credential.signing_private)?;
        let claims = verify_token(&self.grant, obfstr::obfstr!("celeste-installer"))?;
        crate::dpop::require_binding(&claims, &key)?;
        crate::dpop::proof_bound(
            &key,
            "GET",
            url,
            Some(&self.grant),
            Some((public, nonce)),
            None,
        )
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
