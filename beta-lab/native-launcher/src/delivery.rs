//! Owner-only beta delivery. This module verifies metadata; it never attaches or executes code.
//!
//! Production trust is compiled in from the reviewed release snapshot. Only Bootstrap A is
//! downloaded into an account-encrypted durable cache and owned temporary file here;
//! the other three signed links are validated, not fetched.
//! Keep ureq's `gzip` and `brotli` features DISABLED: automatic decompression hides the original
//! Content-Encoding/Content-Length headers and would defeat the transport checks below.

use anyhow::{Result, anyhow, bail, ensure};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use ed25519_dalek::{Signature, VerifyingKey};
use rand::{RngCore, rngs::OsRng};
use serde::de::{self, DeserializeSeed, MapAccess, SeqAccess, Visitor};
use serde_json::{Map, Number, Value};
use sha2::{Digest, Sha256};
use std::fmt;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const PRODUCT: &str = "celeste_plus";
const MAX_TOKEN_BYTES: usize = 16_384;
const MAX_MANIFEST_RESPONSE_BYTES: usize = 20_480;
const MAX_ARTIFACT_BYTES: usize = 64 * 1024 * 1024;
const MAX_RELEASE_BYTES: u64 = 128 * 1024 * 1024;
const IDS: [&str; 4] = ["bootstrap-a", "bootstrap-b", "payload-a", "payload-b"];
fn installer_keys() -> Vec<String> {
    vec![
        obfstr::obfstr!("auth_protocol").to_owned(),
        obfstr::obfstr!("cnf").to_owned(),
        obfstr::obfstr!("product").to_owned(),
        obfstr::obfstr!("aud").to_owned(),
        obfstr::obfstr!("sub").to_owned(),
        obfstr::obfstr!("device").to_owned(),
        obfstr::obfstr!("iat").to_owned(),
        obfstr::obfstr!("nbf").to_owned(),
        obfstr::obfstr!("exp").to_owned(),
        obfstr::obfstr!("jti").to_owned(),
        obfstr::obfstr!("release").to_owned(),
        obfstr::obfstr!("client_sha256").to_owned(),
        obfstr::obfstr!("payload_release").to_owned(),
        obfstr::obfstr!("payload_sha256").to_owned(),
        obfstr::obfstr!("bootstrap").to_owned(),
        obfstr::obfstr!("bootstrap_sha256").to_owned(),
    ]
}
fn manifest_keys() -> Vec<String> {
    vec![
        obfstr::obfstr!("cnf").to_owned(),
        obfstr::obfstr!("product").to_owned(),
        obfstr::obfstr!("aud").to_owned(),
        obfstr::obfstr!("sub").to_owned(),
        obfstr::obfstr!("device").to_owned(),
        obfstr::obfstr!("iat").to_owned(),
        obfstr::obfstr!("nbf").to_owned(),
        obfstr::obfstr!("exp").to_owned(),
        obfstr::obfstr!("installer_jti").to_owned(),
        obfstr::obfstr!("installer_sha256").to_owned(),
        obfstr::obfstr!("request_nonce").to_owned(),
        obfstr::obfstr!("catalog_sha256").to_owned(),
        obfstr::obfstr!("schema").to_owned(),
        obfstr::obfstr!("release_id").to_owned(),
        obfstr::obfstr!("client_version").to_owned(),
        obfstr::obfstr!("runtime_sha256").to_owned(),
        obfstr::obfstr!("artifacts").to_owned(),
    ]
}
fn catalog_keys() -> Vec<String> {
    vec![
        obfstr::obfstr!("schema").to_owned(),
        obfstr::obfstr!("release_id").to_owned(),
        obfstr::obfstr!("client_version").to_owned(),
        obfstr::obfstr!("runtime_sha256").to_owned(),
        obfstr::obfstr!("artifacts").to_owned(),
    ]
}

/// Printable metadata deliberately excludes installer and manifest grants.
pub struct VerifiedBootstrap {
    pub agent: PathBuf,
    pub release: String,
    /// Recomputed schema-2 public-catalog hash, bound by the verified signed manifest.
    pub catalog_sha256: String,
    /// Digest from signed catalog metadata, never a post-verification reread of the cache path.
    pub sha256: Vec<u8>,
}

struct Artifact {
    url: String,
    sha256: String,
    size: usize,
}

struct Catalog {
    release: String,
    catalog_sha256: String,
    expires: u64,
    artifacts: Vec<Artifact>,
}

/// Obtain a fresh Celeste+ manifest and atomically persist its verified Bootstrap A.
/// Neither the DPoP-bound grant, manifest, Bootstrap B, nor either payload is written to disk.
/// All network/parse failures are reduced to fixed messages so credentials cannot enter logs.
pub fn download_bootstrap(
    session: &crate::auth::Session,
    directory: &Path,
) -> Result<VerifiedBootstrap> {
    let installer = session.grant.as_str();
    let device = session.device.as_str();
    let uid = session.uid;
    let raw_key = decode(crate::release::SERVER_KEY)?;
    let raw_key: [u8; 32] = raw_key
        .try_into()
        .map_err(|_| anyhow!("{}", obfstr::obfstr!("Invalid compiled beta server key")))?;
    let key = VerifyingKey::from_bytes(&raw_key)
        .map_err(|_| anyhow!("{}", obfstr::obfstr!("Invalid compiled beta server key")))?;
    let claims = verify_installer(installer, device, &key, now()?)?;
    ensure!(
        uid > 0 && text(&claims, obfstr::obfstr!("sub"))? == uid.to_string(),
        "Installer grant belongs to another activation"
    );
    let transport = HttpsTransport::new(crate::release::BASE_URL)?;

    let request_nonce = new_request_nonce()?;
    let mut wrapper = transport.fetch(
        obfstr::obfstr!("/v1/beta/manifest"),
        session,
        None,
        Some(&request_nonce),
        MAX_MANIFEST_RESPONSE_BYTES,
        obfstr::obfstr!("application/json"),
    )?;
    let manifest_result = manifest_from_response(&wrapper);
    wrapper.fill(0);
    let manifest = manifest_result?;
    let catalog = verify_manifest(installer, &manifest, device, &request_nonce, &key, now()?)?;
    current(installer, device, &key, &catalog, now()?)?;
    let bootstrap = &catalog.artifacts[0];
    let expected_digest = digest_bytes(&bootstrap.sha256)?;
    let cache = directory.join(&bootstrap.sha256).join("bootstrap-a.cst");
    if let Some(bytes) = crate::bootstrap_cache::load(&cache, &bootstrap.sha256, bootstrap.size)? {
        current(installer, device, &key, &catalog, now()?)?;
        let agent = crate::bootstrap_cache::materialize(directory, &bootstrap.sha256, &bytes)?;
        return Ok(VerifiedBootstrap {
            agent,
            release: catalog.release,
            catalog_sha256: catalog.catalog_sha256,
            sha256: expected_digest,
        });
    }
    let mut bytes = transport.fetch(
        &bootstrap.url,
        session,
        Some(&manifest),
        None,
        bootstrap.size,
        obfstr::obfstr!("application/octet-stream"),
    )?;
    let result = (|| {
        verify_artifact(&bytes, bootstrap)?;
        current(installer, device, &key, &catalog, now()?)?;
        crate::bootstrap_cache::store(&cache, &bootstrap.sha256, &bytes)?;
        let agent = crate::bootstrap_cache::materialize(directory, &bootstrap.sha256, &bytes)?;
        Ok(VerifiedBootstrap {
            agent,
            release: catalog.release.clone(),
            catalog_sha256: catalog.catalog_sha256.clone(),
            sha256: expected_digest,
        })
    })();
    bytes.fill(0);
    result
}

fn manifest_from_response(bytes: &[u8]) -> Result<String> {
    ensure!(
        bytes.len() <= MAX_MANIFEST_RESPONSE_BYTES,
        "Beta manifest response too large"
    );
    let wrapper = object(bytes)?;
    exact(&wrapper, &[obfstr::obfstr!("manifest")])?;
    Ok(text(&wrapper, obfstr::obfstr!("manifest"))?.to_owned())
}

fn now() -> Result<u64> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .map_err(|_| {
            anyhow!(
                "{}",
                obfstr::obfstr!("System clock cannot validate beta grants")
            )
        })
}

fn current(
    installer: &str,
    device: &str,
    key: &VerifyingKey,
    catalog: &Catalog,
    time: u64,
) -> Result<()> {
    verify_installer(installer, device, key, time)?;
    ensure!(
        catalog.expires > time,
        "Beta manifest expired; request a fresh manifest"
    );
    Ok(())
}

fn verify_installer(
    installer: &str,
    device: &str,
    key: &VerifyingKey,
    time: u64,
) -> Result<Map<String, Value>> {
    ensure!(identifier(device, 32, 48), "Invalid beta device binding");
    let claims = signed(installer, obfstr::obfstr!("celeste-installer"), key)?;
    exact(&claims, &installer_keys())?;
    ensure!(
        integer(&claims, obfstr::obfstr!("auth_protocol"))? == 2,
        "A current protected-delivery authorization is required"
    );
    crate::dpop::confirmation(&Value::Object(claims.clone()))?;
    identity(&claims, device)?;
    ensure!(
        integer(&claims, obfstr::obfstr!("iat"))? <= time.saturating_add(5)
            && integer(&claims, obfstr::obfstr!("nbf"))? <= time.saturating_add(5)
            && integer(&claims, obfstr::obfstr!("exp"))? > time
            && identifier(text(&claims, obfstr::obfstr!("jti"))?, 16, 128),
        "Installer grant is not current"
    );
    for field in [
        obfstr::obfstr!("release"),
        obfstr::obfstr!("payload_release"),
        obfstr::obfstr!("bootstrap"),
    ] {
        let value = text(&claims, field)?;
        ensure!(
            !value.is_empty() && value.encode_utf16().count() <= 80,
            "Invalid installer identity"
        );
    }
    for field in [
        obfstr::obfstr!("client_sha256"),
        obfstr::obfstr!("payload_sha256"),
        obfstr::obfstr!("bootstrap_sha256"),
    ] {
        hex(&claims, field)?;
    }
    Ok(claims)
}

fn verify_manifest(
    installer: &str,
    manifest: &str,
    device: &str,
    request_nonce: &str,
    key: &VerifyingKey,
    time: u64,
) -> Result<Catalog> {
    let installer_claims = verify_installer(installer, device, key, time)?;
    let claims = signed(manifest, obfstr::obfstr!("celeste-beta-manifest"), key)?;
    exact(&claims, &manifest_keys())?;
    ensure!(
        valid_request_nonce(request_nonce)
            && text(&claims, obfstr::obfstr!("request_nonce"))? == request_nonce,
        "Beta manifest does not match this fresh request"
    );
    let manifest_value = Value::Object(claims.clone());
    let installer_value = Value::Object(installer_claims.clone());
    ensure!(
        crate::dpop::confirmation(&manifest_value)? == crate::dpop::confirmation(&installer_value)?,
        "Manifest belongs to another installation signing key"
    );
    identity(&claims, device)?;
    ensure!(
        text(&claims, obfstr::obfstr!("sub"))? == text(&installer_claims, obfstr::obfstr!("sub"))?,
        "Manifest belongs to another license"
    );
    let issued = integer(&claims, obfstr::obfstr!("iat"))?;
    let expires = integer(&claims, obfstr::obfstr!("exp"))?;
    ensure!(
        issued <= time
            && integer(&claims, obfstr::obfstr!("nbf"))? == issued
            && expires > time
            && expires > issued
            && expires - issued <= 120
            && expires <= integer(&installer_claims, obfstr::obfstr!("exp"))?
            && text(&claims, obfstr::obfstr!("installer_jti"))?
                == text(&installer_claims, obfstr::obfstr!("jti"))?
            && hex(&claims, obfstr::obfstr!("installer_sha256"))? == sha256(installer.as_bytes()),
        "Beta manifest is not bound to the current installer grant"
    );
    let release = text(&claims, obfstr::obfstr!("release_id"))?;
    let version = text(&claims, obfstr::obfstr!("client_version"))?;
    let runtime_digest = hex(&claims, obfstr::obfstr!("runtime_sha256"))?;
    ensure!(
        text(&claims, obfstr::obfstr!("schema"))? == "celeste-beta-release-2"
            && identifier(release, 1, 80)
            && release.as_bytes()[0].is_ascii_alphanumeric()
            && !version.is_empty()
            && version.len() <= 40
            && version.bytes().all(|byte| (b'!'..=b'~').contains(&byte))
            && release != text(&installer_claims, obfstr::obfstr!("payload_release"))?
            && runtime_digest != hex(&installer_claims, obfstr::obfstr!("payload_sha256"))?,
        "Invalid beta release identity"
    );
    let raw_artifacts = claims
        .get(obfstr::obfstr!("artifacts"))
        .and_then(Value::as_array)
        .ok_or_else(|| anyhow!("{}", obfstr::obfstr!("Expected four beta artifacts")))?;
    ensure!(
        raw_artifacts.len() == IDS.len(),
        "Expected four beta artifacts"
    );
    let mut artifacts = Vec::with_capacity(IDS.len());
    let mut total = 0_u64;
    for (index, expected_id) in IDS.iter().enumerate() {
        let item = raw_artifacts[index]
            .as_object()
            .ok_or_else(|| anyhow!("{}", obfstr::obfstr!("Invalid beta artifact metadata")))?;
        exact(
            item,
            &[
                obfstr::obfstr!("id"),
                obfstr::obfstr!("role"),
                obfstr::obfstr!("url"),
                obfstr::obfstr!("sha256"),
                obfstr::obfstr!("size"),
            ],
        )?;
        let id = text(item, obfstr::obfstr!("id"))?;
        let role = text(item, obfstr::obfstr!("role"))?;
        let url = text(item, obfstr::obfstr!("url"))?;
        let size = integer(item, obfstr::obfstr!("size"))?;
        ensure!(
            id == *expected_id
                && (if index < 2 {
                    role == obfstr::obfstr!("bootstrap")
                } else {
                    role == "payload"
                })
                && url == format!("{}{release}/{id}", obfstr::obfstr!("/v1/beta/artifacts/"))
                && size > 0
                && size <= MAX_ARTIFACT_BYTES as u64,
            "Invalid beta artifact identity, path or size"
        );
        total += size;
        ensure!(
            total <= MAX_RELEASE_BYTES,
            "Beta release exceeds its memory limit"
        );
        artifacts.push(Artifact {
            url: url.to_owned(),
            sha256: hex(item, obfstr::obfstr!("sha256"))?.to_owned(),
            size: size as usize,
        });
    }
    let projected = catalog_projection(&claims);
    ensure!(
        hex(&claims, obfstr::obfstr!("catalog_sha256"))?
            == sha256(canonical(&projected)?.as_bytes()),
        "Beta catalog digest mismatch"
    );
    Ok(Catalog {
        release: release.to_owned(),
        catalog_sha256: hex(&claims, obfstr::obfstr!("catalog_sha256"))?.to_owned(),
        expires,
        artifacts,
    })
}

fn catalog_projection(claims: &Map<String, Value>) -> Value {
    Value::Object(
        catalog_keys()
            .iter()
            .filter_map(|key| claims.get(key).map(|value| (key.to_owned(), value.clone())))
            .collect(),
    )
}

fn verify_artifact(bytes: &[u8], artifact: &Artifact) -> Result<()> {
    ensure!(
        bytes.len() == artifact.size && sha256(bytes) == artifact.sha256,
        "Beta Bootstrap A integrity mismatch"
    );
    Ok(())
}

fn identity(claims: &Map<String, Value>, device: &str) -> Result<()> {
    let subject = text(claims, obfstr::obfstr!("sub"))?;
    let uid = subject
        .parse::<u64>()
        .map_err(|_| anyhow!("{}", obfstr::obfstr!("Invalid license identity")))?;
    ensure!(
        uid > 0
            && uid <= i64::MAX as u64
            && subject == uid.to_string()
            && text(claims, obfstr::obfstr!("product"))? == PRODUCT
            && text(claims, obfstr::obfstr!("device"))? == device,
        "Grant does not match Celeste+ and this device"
    );
    Ok(())
}

pub(crate) fn signed(
    token: &str,
    audience: &str,
    key: &VerifyingKey,
) -> Result<Map<String, Value>> {
    ensure!(
        !token.is_empty() && token.len() <= MAX_TOKEN_BYTES,
        "Invalid signed beta token"
    );
    let parts: Vec<_> = token.split('.').collect();
    ensure!(parts.len() == 3, "Invalid signed beta token");
    let header = decode(parts[0])?;
    let payload = decode(parts[1])?;
    let signature = decode(parts[2])?;
    let signature = Signature::from_slice(&signature)
        .map_err(|_| anyhow!("{}", obfstr::obfstr!("Invalid beta signature length")))?;
    // Verification precedes JSON parsing, and no algorithm or key is selected from untrusted claims.
    let signed_length = parts[0].len() + 1 + parts[1].len();
    key.verify_strict(&token.as_bytes()[..signed_length], &signature)
        .map_err(|_| {
            anyhow!(
                "{}",
                obfstr::obfstr!("Beta signature did not match the pinned server")
            )
        })?;
    let metadata = object(&header)?;
    let claims = object(&payload)?;
    exact(
        &metadata,
        &[
            obfstr::obfstr!("alg"),
            obfstr::obfstr!("kid"),
            obfstr::obfstr!("typ"),
        ],
    )?;
    ensure!(
        text(&metadata, obfstr::obfstr!("alg"))? == obfstr::obfstr!("EdDSA")
            && text(&metadata, obfstr::obfstr!("typ"))? == obfstr::obfstr!("CST")
            && text(&metadata, obfstr::obfstr!("kid"))? == obfstr::obfstr!("celeste-release-1")
            && text(&claims, obfstr::obfstr!("aud"))? == audience,
        "Unexpected beta token type"
    );
    Ok(claims)
}

fn decode(encoded: &str) -> Result<Vec<u8>> {
    ensure!(
        !encoded.is_empty()
            && encoded
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-'),
        "Invalid canonical beta base64url"
    );
    let bytes = URL_SAFE_NO_PAD
        .decode(encoded)
        .map_err(|_| anyhow!("{}", obfstr::obfstr!("Invalid canonical beta base64url")))?;
    ensure!(
        URL_SAFE_NO_PAD.encode(&bytes) == encoded,
        "Invalid canonical beta base64url"
    );
    Ok(bytes)
}

fn identifier(value: &str, minimum: usize, maximum: usize) -> bool {
    (minimum..=maximum).contains(&value.len())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
}

pub(crate) fn exact<K: AsRef<str>>(object: &Map<String, Value>, keys: &[K]) -> Result<()> {
    ensure!(
        object.len() == keys.len() && keys.iter().all(|key| object.contains_key(key.as_ref())),
        "Unexpected beta metadata fields"
    );
    Ok(())
}

pub(crate) fn text<'a>(object: &'a Map<String, Value>, key: &str) -> Result<&'a str> {
    object
        .get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| anyhow!("{}", obfstr::obfstr!("Expected text beta metadata")))
}

fn hex<'a>(object: &'a Map<String, Value>, key: &str) -> Result<&'a str> {
    let value = text(object, key)?;
    ensure!(
        value.len() == 64
            && value
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)),
        "Invalid SHA-256 beta metadata"
    );
    Ok(value)
}

pub(crate) fn integer(object: &Map<String, Value>, key: &str) -> Result<u64> {
    object
        .get(key)
        .and_then(Value::as_u64)
        .filter(|value| *value <= i64::MAX as u64)
        .ok_or_else(|| {
            anyhow!(
                "{}",
                obfstr::obfstr!("Expected exact nonnegative integer beta metadata")
            )
        })
}

fn sha256(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

fn digest_bytes(digest: &str) -> Result<Vec<u8>> {
    ensure!(
        digest.len() == 64
            && digest
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)),
        "Invalid signed beta artifact digest"
    );
    (0..32)
        .map(|index| {
            u8::from_str_radix(&digest[index * 2..index * 2 + 2], 16)
                .map_err(|_| anyhow!("{}", obfstr::obfstr!("Invalid signed beta artifact digest")))
        })
        .collect()
}

// serde_json::Value alone silently accepts duplicate keys. This bounded visitor rejects them,
// negative/floating/exponent numbers, booleans, null, excessive nesting, and large containers.
struct StrictValue {
    depth: usize,
}

impl<'de> DeserializeSeed<'de> for StrictValue {
    type Value = Value;
    fn deserialize<D>(self, deserializer: D) -> std::result::Result<Value, D::Error>
    where
        D: de::Deserializer<'de>,
    {
        if self.depth > 12 {
            return Err(de::Error::custom("Metadata nesting exceeds limit"));
        }
        deserializer.deserialize_any(self)
    }
}

impl<'de> Visitor<'de> for StrictValue {
    type Value = Value;
    fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("bounded string, nonnegative integer, array or object metadata")
    }
    fn visit_u64<E>(self, value: u64) -> std::result::Result<Value, E>
    where
        E: de::Error,
    {
        if value > i64::MAX as u64 {
            return Err(E::custom("Metadata integer exceeds limit"));
        }
        Ok(Value::Number(Number::from(value)))
    }
    fn visit_str<E>(self, value: &str) -> std::result::Result<Value, E>
    where
        E: de::Error,
    {
        Ok(Value::String(value.to_owned()))
    }
    fn visit_string<E>(self, value: String) -> std::result::Result<Value, E>
    where
        E: de::Error,
    {
        Ok(Value::String(value))
    }
    fn visit_seq<A>(self, mut sequence: A) -> std::result::Result<Value, A::Error>
    where
        A: SeqAccess<'de>,
    {
        let mut values = Vec::new();
        while let Some(value) = sequence.next_element_seed(StrictValue {
            depth: self.depth + 1,
        })? {
            if values.len() >= 64 {
                return Err(de::Error::custom("Metadata array exceeds limit"));
            }
            values.push(value);
        }
        Ok(Value::Array(values))
    }
    fn visit_map<A>(self, mut map: A) -> std::result::Result<Value, A::Error>
    where
        A: MapAccess<'de>,
    {
        let mut values = Map::new();
        while let Some(key) = map.next_key::<String>()? {
            if values.len() >= 64 || values.contains_key(&key) {
                return Err(de::Error::custom("Duplicate or excessive metadata keys"));
            }
            let value = map.next_value_seed(StrictValue {
                depth: self.depth + 1,
            })?;
            values.insert(key, value);
        }
        Ok(Value::Object(values))
    }
}

pub(crate) fn object(bytes: &[u8]) -> Result<Map<String, Value>> {
    let mut deserializer = serde_json::Deserializer::from_slice(bytes);
    let value = StrictValue { depth: 0 }
        .deserialize(&mut deserializer)
        .map_err(|_| anyhow!("{}", obfstr::obfstr!("Malformed signed beta metadata")))?;
    deserializer
        .end()
        .map_err(|_| anyhow!("{}", obfstr::obfstr!("Malformed signed beta metadata")))?;
    match value {
        Value::Object(object) => Ok(object),
        _ => bail!("{}", obfstr::obfstr!("Expected one beta metadata object")),
    }
}

/// Matches the server's ASCII-escaped, sorted-key, compact canonical JSON, including UTF-16
/// surrogate escaping. Default serde_json serialization is deliberately not used for this hash.
fn canonical(value: &Value) -> Result<String> {
    match value {
        Value::Object(object) => {
            let mut keys: Vec<_> = object.keys().collect();
            keys.sort();
            let mut fields = Vec::with_capacity(keys.len());
            for key in keys {
                fields.push(format!("{}:{}", quote(key), canonical(&object[key])?));
            }
            Ok(format!("{{{}}}", fields.join(",")))
        }
        Value::Array(array) => {
            let entries: Result<Vec<_>> = array.iter().map(canonical).collect();
            Ok(format!("[{}]", entries?.join(",")))
        }
        Value::String(value) => Ok(quote(value)),
        Value::Number(value) => value
            .as_u64()
            .filter(|value| *value <= i64::MAX as u64)
            .map(|value| value.to_string())
            .ok_or_else(|| anyhow!("{}", obfstr::obfstr!("Unsupported canonical beta number"))),
        _ => bail!(
            "{}",
            obfstr::obfstr!("Unsupported canonical beta metadata type")
        ),
    }
}

fn quote(value: &str) -> String {
    use std::fmt::Write as _;
    let mut output = String::from("\"");
    for character in value.encode_utf16() {
        match character {
            0x22 => output.push_str("\\\""),
            0x5c => output.push_str("\\\\"),
            0x08 => output.push_str("\\b"),
            0x0c => output.push_str("\\f"),
            0x0a => output.push_str("\\n"),
            0x0d => output.push_str("\\r"),
            0x09 => output.push_str("\\t"),
            32..=126 => output.push(character as u8 as char),
            _ => {
                let _ = write!(output, "\\u{character:04x}");
            }
        }
    }
    output.push('"');
    output
}

struct HttpsTransport {
    origin: String,
}

impl HttpsTransport {
    fn new(origin: &str) -> Result<Self> {
        let agent = ureq::AgentBuilder::new()
            .https_only(true)
            .redirects(0)
            .timeout_connect(Duration::from_secs(8))
            .timeout(Duration::from_secs(25))
            .max_idle_connections(0)
            .build();
        let parsed = agent
            .get(origin)
            .request_url()
            .map_err(|_| anyhow!("{}", obfstr::obfstr!("Invalid compiled beta HTTPS origin")))?;
        let url = parsed.as_url();
        ensure!(
            origin.starts_with("https://")
                && url.scheme() == "https"
                && url.has_host()
                && url.username().is_empty()
                && url.password().is_none()
                && url.query().is_none()
                && url.fragment().is_none()
                && url.path() == "/"
                && url.port() != Some(0),
            "Invalid compiled beta HTTPS origin"
        );
        Ok(Self {
            origin: url.origin().ascii_serialization(),
        })
    }

    fn fetch(
        &self,
        path: &str,
        session: &crate::auth::Session,
        manifest: Option<&str>,
        request_nonce: Option<&str>,
        limit: usize,
        media_type: &str,
    ) -> Result<Vec<u8>> {
        ensure!(
            limit > 0 && limit <= MAX_ARTIFACT_BYTES,
            "Invalid beta response bound"
        );
        // Paths are generated by the schema verifier, not resolved from arbitrary server URLs.
        ensure!(
            path.starts_with(obfstr::obfstr!("/v1/beta/"))
                && !path.contains(['?', '#', '%', '\\'])
                && !path.contains(".."),
            "Invalid beta route"
        );
        let url = format!("{}{path}", self.origin);
        let encrypted = if manifest.is_some() {
            Some(crate::artifact_envelope::RequestKey::new()?)
        } else {
            None
        };
        let proof = if let Some(key) = &encrypted {
            session.artifact_proof(&url, &key.public, &key.nonce)?
        } else {
            session.delivery_proof("GET", &url)?
        };
        let agent = crate::transport_policy::agent(&self.origin)?;
        let mut request = agent
            .get(&url)
            .set(
                obfstr::obfstr!("Authorization"),
                &format!("DPoP {}", session.grant),
            )
            .set(obfstr::obfstr!("DPoP"), &proof)
            .set(obfstr::obfstr!("Accept"), media_type)
            .set(obfstr::obfstr!("Accept-Encoding"), "identity")
            .set(obfstr::obfstr!("Cache-Control"), "no-store");
        if let Some(manifest) = manifest {
            request = request.set(obfstr::obfstr!("X-Celeste-Beta-Manifest"), manifest);
        }
        if let Some(key) = &encrypted {
            ensure!(
                request_nonce.is_none(),
                "Conflicting artifact request nonce"
            );
            request = request
                .set(obfstr::obfstr!("X-Celeste-Delivery-Key"), &key.public)
                .set(obfstr::obfstr!("X-Celeste-Request-Nonce"), &key.nonce);
        } else {
            request = bind_request_nonce(request, path, request_nonce)?;
        }
        // Never retain a ureq error as a source: it can carry response/URL context.
        let response = match request.call() {
            Ok(response) => response,
            Err(ureq::Error::Status(status, response)) => {
                return Err(crate::auth::resource_failure(path, status, response));
            }
            Err(_) => return Err(anyhow!("{}", obfstr::obfstr!("Beta HTTPS request failed"))),
        };
        ensure!(
            response.get_url() == url && response.status() == 200,
            "Beta response origin or status rejected"
        );
        let transport_limit = limit
            + if encrypted.is_some() {
                crate::artifact_envelope::OVERHEAD
            } else {
                0
            };
        let advertised = response_metadata(
            &response.all(obfstr::obfstr!("Content-Type")),
            &response.all(obfstr::obfstr!("Content-Length")),
            &response.all(obfstr::obfstr!("Content-Encoding")),
            media_type,
            transport_limit,
        )?;
        let bytes = read_bounded(response.into_reader(), advertised, transport_limit)?;
        if let Some(key) = encrypted {
            Ok(key.decrypt(&bytes, path, &session.grant, limit)?.to_vec())
        } else {
            Ok(bytes)
        }
    }
}

fn new_request_nonce() -> Result<String> {
    let mut bytes = [0u8; 24];
    OsRng.try_fill_bytes(&mut bytes).map_err(|_| {
        anyhow!(
            "{}",
            obfstr::obfstr!("Fresh beta request randomness is unavailable")
        )
    })?;
    Ok(URL_SAFE_NO_PAD.encode(bytes))
}

fn valid_request_nonce(value: &str) -> bool {
    identifier(value, 32, 32) && decode(value).is_ok_and(|bytes| bytes.len() == 24)
}

fn bind_request_nonce(
    request: ureq::Request,
    path: &str,
    nonce: Option<&str>,
) -> Result<ureq::Request> {
    if path == obfstr::obfstr!("/v1/beta/manifest") {
        let value = nonce
            .filter(|value| valid_request_nonce(value))
            .ok_or_else(|| {
                anyhow!(
                    "{}",
                    obfstr::obfstr!("A fresh beta manifest request nonce is required")
                )
            })?;
        Ok(request.set(obfstr::obfstr!("X-Celeste-Request-Nonce"), value))
    } else {
        ensure!(nonce.is_none(), "Unexpected beta request nonce route");
        Ok(request)
    }
}

fn read_bounded(reader: impl Read, advertised: Option<usize>, limit: usize) -> Result<Vec<u8>> {
    ensure!(
        limit > 0 && limit <= MAX_ARTIFACT_BYTES + crate::artifact_envelope::OVERHEAD,
        "Invalid beta response bound"
    );
    // Explicitly cap both bytes retained and Vec capacity. Reading at most one byte beyond
    // the signed size detects overflow without a read_to_end growth past that allocation.
    let mut body = Vec::with_capacity(limit);
    let read_result = (|| -> std::io::Result<()> {
        let mut reader = reader.take(limit as u64 + 1);
        let mut chunk = [0_u8; 8192];
        loop {
            let length = match reader.read(&mut chunk) {
                Ok(length) => length,
                Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(error) => return Err(error),
            };
            if length == 0 {
                return Ok(());
            }
            if length > limit - body.len() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    "Beta body exceeds bound",
                ));
            }
            body.extend_from_slice(&chunk[..length]);
        }
    })();
    let accepted = read_result.is_ok()
        && body.len() <= limit
        && advertised.is_none_or(|expected| expected == body.len());
    if !accepted {
        body.fill(0);
        bail!(
            "{}",
            obfstr::obfstr!("Beta response size or transfer deadline rejected")
        );
    }
    Ok(body)
}

fn response_metadata(
    types: &[&str],
    lengths: &[&str],
    encodings: &[&str],
    media_type: &str,
    limit: usize,
) -> Result<Option<usize>> {
    ensure!(
        types.len() == 1
            && types[0]
                .split(';')
                .next()
                .unwrap_or("")
                .trim()
                .eq_ignore_ascii_case(media_type),
        "Beta response content type rejected"
    );
    ensure!(
        encodings.len() <= 1
            && encodings
                .first()
                .is_none_or(|value| value.eq_ignore_ascii_case("identity")),
        "Encoded beta responses are not accepted"
    );
    ensure!(lengths.len() <= 1, "Beta response length rejected");
    if let Some(value) = lengths.first() {
        ensure!(
            *value == "0"
                || (!value.is_empty()
                    && value.as_bytes()[0] != b'0'
                    && value.bytes().all(|byte| byte.is_ascii_digit())),
            "Beta response length rejected"
        );
        let length = value
            .parse::<usize>()
            .map_err(|_| anyhow!("{}", obfstr::obfstr!("Beta response length rejected")))?;
        ensure!(length <= limit, "Beta response length exceeds signed bound");
        return Ok(Some(length));
    }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};
    use serde_json::json;

    fn golden() -> Value {
        serde_json::from_str(include_str!(
            "../../delivery-client/fixtures/server-schema2-v2-golden.json"
        ))
        .unwrap()
    }

    fn fixture_key(fixture: &Value) -> VerifyingKey {
        let key: [u8; 32] = decode(fixture["public_key"].as_str().unwrap())
            .unwrap()
            .try_into()
            .unwrap();
        VerifyingKey::from_bytes(&key).unwrap()
    }

    #[test]
    fn old_signed_installer_cannot_enable_plaintext_delivery() {
        let old: Value = serde_json::from_str(include_str!(
            "../../delivery-client/fixtures/server-schema2-golden.json"
        ))
        .unwrap();
        assert!(verify_fixture(&old).is_err());
        assert!(verify_fixture(&golden()).is_ok());
    }

    fn verify_fixture(fixture: &Value) -> Result<Catalog> {
        verify_manifest(
            fixture["installer_token"].as_str().unwrap(),
            fixture["manifest_token"].as_str().unwrap(),
            fixture["expected_device"].as_str().unwrap(),
            fixture["expected_request_nonce"].as_str().unwrap(),
            &fixture_key(fixture),
            fixture["now"].as_u64().unwrap(),
        )
    }

    fn sign_raw(payload: &str, key: &SigningKey) -> String {
        let header =
            URL_SAFE_NO_PAD.encode(br#"{"alg":"EdDSA","kid":"celeste-release-1","typ":"CST"}"#);
        let unsigned = format!("{header}.{}", URL_SAFE_NO_PAD.encode(payload.as_bytes()));
        format!(
            "{unsigned}.{}",
            URL_SAFE_NO_PAD.encode(key.sign(unsigned.as_bytes()).to_bytes())
        )
    }

    fn sign(claims: &Value, key: &SigningKey) -> String {
        sign_raw(&serde_json::to_string(claims).unwrap(), key)
    }

    fn synthetic() -> (Value, SigningKey) {
        let mut fixture = golden();
        let key = SigningKey::from_bytes(&[0x37; 32]);
        fixture["public_key"] = json!(URL_SAFE_NO_PAD.encode(key.verifying_key().to_bytes()));
        let installer = sign(&fixture["installer_claims"], &key);
        fixture["manifest_claims"]["installer_sha256"] = json!(sha256(installer.as_bytes()));
        fixture["installer_token"] = json!(installer);
        fixture["manifest_token"] = json!(sign(&fixture["manifest_claims"], &key));
        (fixture, key)
    }

    fn resign_manifest(fixture: &mut Value, key: &SigningKey) {
        let projected = catalog_projection(fixture["manifest_claims"].as_object().unwrap());
        fixture["manifest_claims"]["catalog_sha256"] =
            json!(sha256(canonical(&projected).unwrap().as_bytes()));
        fixture["manifest_token"] = json!(sign(&fixture["manifest_claims"], key));
    }

    #[test]
    fn fresh_nonce_is_random_canonical_and_sent_on_manifest_only() {
        let first = new_request_nonce().unwrap();
        let second = new_request_nonce().unwrap();
        assert!(valid_request_nonce(&first));
        assert!(valid_request_nonce(&second));
        assert_ne!(first, second);
        let request = bind_request_nonce(
            ureq::get("https://fixture.invalid/v1/beta/manifest"),
            "/v1/beta/manifest",
            Some(&first),
        )
        .unwrap();
        assert_eq!(
            request.header(obfstr::obfstr!("X-Celeste-Request-Nonce")),
            Some(first.as_str())
        );
        assert!(
            bind_request_nonce(
                ureq::get("https://fixture.invalid/v1/beta/manifest"),
                "/v1/beta/manifest",
                None
            )
            .is_err()
        );
        assert!(
            bind_request_nonce(
                ureq::get("https://fixture.invalid/v1/beta/artifacts/test/bootstrap-a"),
                "/v1/beta/artifacts/test/bootstrap-a",
                Some(&first)
            )
            .is_err()
        );
    }

    #[test]
    fn captured_valid_manifest_cannot_satisfy_a_new_request() {
        let fixture = golden();
        assert!(verify_fixture(&fixture).is_ok());
        let next = new_request_nonce().unwrap();
        let error = verify_manifest(
            fixture["installer_token"].as_str().unwrap(),
            fixture["manifest_token"].as_str().unwrap(),
            fixture["expected_device"].as_str().unwrap(),
            &next,
            &fixture_key(&fixture),
            fixture["now"].as_u64().unwrap(),
        )
        .err()
        .unwrap();
        assert_eq!(
            error.to_string(),
            "Beta manifest does not match this fresh request"
        );
    }

    #[test]
    fn missing_or_changed_signed_nonce_has_no_compatibility_fallback() {
        let (mut fixture, key) = synthetic();
        fixture["manifest_claims"]
            .as_object_mut()
            .unwrap()
            .remove("request_nonce");
        resign_manifest(&mut fixture, &key);
        assert!(verify_fixture(&fixture).is_err());
        fixture["manifest_claims"]["request_nonce"] = json!(new_request_nonce().unwrap());
        resign_manifest(&mut fixture, &key);
        assert!(verify_fixture(&fixture).is_err());
        for invalid in [
            "",
            "short",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+",
        ] {
            assert!(!valid_request_nonce(invalid));
        }
    }

    #[test]
    fn actual_server_schema2_golden_verifies_offline() {
        let fixture = golden();
        // The fixture's explicitly PUBLIC TEST key is also consumed by the
        // Python server and Java client: catch cross-language RFC 7638 drift.
        let installation_seed: [u8; 32] =
            decode(fixture["installation_test_seed"].as_str().unwrap())
                .unwrap()
                .try_into()
                .unwrap();
        let installation = SigningKey::from_bytes(&installation_seed);
        assert_eq!(
            URL_SAFE_NO_PAD.encode(installation.verifying_key().as_bytes()),
            fixture["installation_public_key"].as_str().unwrap()
        );
        crate::dpop::require_binding(&fixture["installer_claims"], &installation).unwrap();
        crate::dpop::require_binding(&fixture["manifest_claims"], &installation).unwrap();
        let catalog = verify_fixture(&fixture).unwrap();
        assert_eq!(catalog.release, "owner-beta-golden-1");
        assert_eq!(
            catalog.catalog_sha256,
            fixture["catalog_sha256"].as_str().unwrap()
        );
        assert_eq!(catalog.artifacts.len(), 4);
        let projected = catalog_projection(fixture["manifest_claims"].as_object().unwrap());
        assert_eq!(
            canonical(&projected).unwrap(),
            fixture["canonical_catalog"].as_str().unwrap()
        );
        for (index, id) in IDS.iter().enumerate() {
            let bytes = decode(fixture["artifact_bytes"][*id].as_str().unwrap()).unwrap();
            verify_artifact(&bytes, &catalog.artifacts[index]).unwrap();
        }
        let compiled_key: [u8; 32] = decode(crate::release::SERVER_KEY)
            .unwrap()
            .try_into()
            .unwrap();
        assert!(
            verify_manifest(
                fixture["installer_token"].as_str().unwrap(),
                fixture["manifest_token"].as_str().unwrap(),
                fixture["expected_device"].as_str().unwrap(),
                fixture["expected_request_nonce"].as_str().unwrap(),
                &VerifyingKey::from_bytes(&compiled_key).unwrap(),
                fixture["now"].as_u64().unwrap(),
            )
            .is_err(),
            "offline fixture key must never be trusted by the production entry point"
        );
    }

    #[test]
    fn installer_and_manifest_cannot_omit_dpop_confirmation() {
        let (base, key) = synthetic();
        for target in ["installer", "manifest"] {
            let mut fixture = base.clone();
            let claims = format!("{target}_claims");
            let token = format!("{target}_token");
            fixture[&claims].as_object_mut().unwrap().remove("cnf");
            fixture[&token] = json!(sign(&fixture[&claims], &key));
            if target == "installer" {
                fixture["manifest_claims"]["installer_sha256"] =
                    json!(sha256(fixture[&token].as_str().unwrap().as_bytes()));
                resign_manifest(&mut fixture, &key);
            }
            assert!(
                verify_fixture(&fixture).is_err(),
                "accepted unbound {target}"
            );
        }
    }

    #[test]
    fn catalog_identity_changes_when_only_internal_bootstrap_changes() {
        let (mut fixture, key) = synthetic();
        let initial = verify_fixture(&fixture).unwrap();
        fixture["manifest_claims"]["artifacts"][1]["sha256"] = json!("f".repeat(64));
        resign_manifest(&mut fixture, &key);
        let changed = verify_fixture(&fixture).unwrap();
        assert_eq!(initial.release, changed.release);
        assert_eq!(initial.artifacts[0].sha256, changed.artifacts[0].sha256);
        assert_ne!(initial.catalog_sha256, changed.catalog_sha256);
    }

    #[test]
    fn signed_manifest_mutations_fail_closed() {
        let (base, key) = synthetic();
        verify_fixture(&base).unwrap();
        let mutations = [
            ("cnf", json!({"jkt":"A".repeat(43)})),
            (
                "cnf",
                json!({"jkt":base["manifest_claims"]["cnf"]["jkt"], "extra":true}),
            ),
            ("schema", json!("celeste-beta-release-1")),
            ("aud", json!("celeste-client")),
            ("sub", json!("8")),
            ("sub", json!(7)),
            ("device", json!("wrong-device")),
            ("installer_jti", json!("wrong-installer-ticket")),
            ("installer_sha256", json!("0".repeat(64))),
            ("iat", json!(1_800_000_001_u64)),
            ("nbf", json!(1_799_999_999_u64)),
            ("exp", json!(1_800_000_000_u64)),
            ("exp", json!(1_800_000_121_u64)),
            ("exp", json!("1800000100")),
            ("release_id", json!("../escape")),
            ("release_id", json!("production-release")),
            ("runtime_sha256", json!("b".repeat(64))),
            ("client_version", json!("spaces invalid")),
            ("unexpected", json!("field")),
        ];
        for (field, value) in mutations {
            let mut fixture = base.clone();
            fixture["manifest_claims"][field] = value;
            resign_manifest(&mut fixture, &key);
            assert!(
                verify_fixture(&fixture).is_err(),
                "accepted manifest mutation: {field}"
            );
        }
        for index in 0..4 {
            for (field, value) in [
                ("id", json!("unknown")),
                ("role", json!("unknown")),
                ("url", json!("//foreign.invalid/leak")),
                ("url", json!("/v1/bootstrap/production")),
                ("url", json!("/v1/beta/artifacts/../production/bootstrap-a")),
                (
                    "url",
                    json!("https://auth.invalid/v1/beta/artifacts/owner-beta-golden-1/bootstrap-a"),
                ),
                ("filename", json!("payload-a.bin")),
                ("sha256", json!("Z".repeat(64))),
                ("size", json!(0)),
                ("size", json!(67_108_865)),
                ("size", json!("42")),
            ] {
                let mut fixture = base.clone();
                fixture["manifest_claims"]["artifacts"][index][field] = value;
                resign_manifest(&mut fixture, &key);
                assert!(
                    verify_fixture(&fixture).is_err(),
                    "accepted artifact {index} mutation: {field}"
                );
            }
        }
        for count in [3, 5] {
            let mut fixture = base.clone();
            let artifacts = fixture["manifest_claims"]["artifacts"]
                .as_array_mut()
                .unwrap();
            if count == 3 {
                artifacts.pop();
            } else {
                artifacts.push(artifacts[0].clone());
            }
            resign_manifest(&mut fixture, &key);
            assert!(verify_fixture(&fixture).is_err());
        }
        let mut fixture = base.clone();
        for index in 0..4 {
            fixture["manifest_claims"]["artifacts"][index]["size"] = json!(MAX_ARTIFACT_BYTES);
        }
        resign_manifest(&mut fixture, &key);
        assert!(verify_fixture(&fixture).is_err());
        let mut fixture = base.clone();
        fixture["manifest_claims"]["catalog_sha256"] = json!("0".repeat(64));
        fixture["manifest_token"] = json!(sign(&fixture["manifest_claims"], &key));
        assert!(verify_fixture(&fixture).is_err());
    }

    #[test]
    fn installer_current_time_and_exact_bytes_binding() {
        let (base, key) = synthetic();
        for (field, value) in [
            ("cnf", json!({"jkt":"bad"})),
            (
                "cnf",
                json!({"jkt":base["installer_claims"]["cnf"]["jkt"], "extra":true}),
            ),
            ("sub", json!("08")),
            ("sub", json!("0")),
            ("sub", json!("9223372036854775808")),
            ("product", json!("celeste")),
            ("aud", json!("celeste-client")),
            ("exp", json!(1)),
            ("nbf", json!(1_800_000_006_u64)),
            ("iat", json!(1_800_000_006_u64)),
            ("jti", json!("short")),
            ("bootstrap_sha256", json!("bad")),
            ("sub", json!(7)),
            ("unexpected", json!("field")),
        ] {
            let mut fixture = base.clone();
            fixture["installer_claims"][field] = value;
            let installer = sign(&fixture["installer_claims"], &key);
            assert!(
                verify_installer(
                    &installer,
                    fixture["expected_device"].as_str().unwrap(),
                    &key.verifying_key(),
                    fixture["now"].as_u64().unwrap()
                )
                .is_err(),
                "accepted installer mutation: {field}"
            );
        }
        let mut fixture = base.clone();
        // Same claims/jti but a differently encoded and valid signed grant is not interchangeable.
        let alternate = serde_json::to_string_pretty(&fixture["installer_claims"]).unwrap();
        fixture["installer_token"] = json!(sign_raw(&alternate, &key));
        assert!(verify_fixture(&fixture).is_err());
        let mut fixture = base.clone();
        fixture["now"] = json!(1_800_000_120_u64);
        assert!(verify_fixture(&fixture).is_err());
        let mut fixture = base.clone();
        fixture["expected_device"] = json!("00000000-0000-0000-0000-000000000008");
        assert!(verify_fixture(&fixture).is_err());
        let mut fixture = base.clone();
        fixture["installer_claims"]["exp"] = json!(1_800_000_060_u64);
        let installer = sign(&fixture["installer_claims"], &key);
        fixture["installer_token"] = json!(installer);
        fixture["manifest_claims"]["installer_sha256"] = json!(sha256(
            fixture["installer_token"].as_str().unwrap().as_bytes()
        ));
        resign_manifest(&mut fixture, &key);
        assert!(
            verify_fixture(&fixture).is_err(),
            "manifest cannot outlive its installer"
        );
    }

    #[test]
    fn strict_metadata_base64_signatures_and_canonical_escaping() {
        for body in [
            r#"{"a":1,"a":2}"#,
            r#"{"a":null}"#,
            r#"{"a":true}"#,
            r#"{"a":false}"#,
            r#"{"a":-1}"#,
            r#"{"a":-0}"#,
            r#"{"a":1.0}"#,
            r#"{"a":1e0}"#,
            r#"{"a":01}"#,
            r#"{"a":9223372036854775808}"#,
            r#"{"a":1} {}"#,
            r#"{"a":1,}"#,
            r#"[]"#,
            r#"{"a":"\ud800"}"#,
        ] {
            assert!(
                object(body.as_bytes()).is_err(),
                "accepted malformed metadata"
            );
        }
        assert!(object(b"{\"a\":\"\xff\"}").is_err());
        assert!(
            object(format!("{{\"a\":{}0{}}}", "[".repeat(13), "]".repeat(13)).as_bytes()).is_err()
        );
        assert!(object(format!("{{\"a\":[{}]}}", vec!["0"; 65].join(",")).as_bytes()).is_err());
        let keys: Vec<_> = (0..65).map(|index| format!("\"k{index}\":0")).collect();
        assert!(object(format!("{{{}}}", keys.join(",")).as_bytes()).is_err());
        for encoded in ["", "AA=", "AA==", "AB", "A+", "A/", "A A", "\r\n"] {
            assert!(decode(encoded).is_err());
        }
        assert_eq!(
            quote("<>&\"\\\n\u{00e9}\u{1f680}"),
            r#""<>&\"\\\n\u00e9\ud83d\ude80""#
        );
        let (mut fixture, key) = synthetic();
        let payload = serde_json::to_string(&fixture["manifest_claims"]).unwrap();
        fixture["manifest_token"] = json!(sign_raw(
            &format!("{{\"schema\":\"duplicate\",{}", &payload[1..]),
            &key
        ));
        assert!(verify_fixture(&fixture).is_err());
        fixture["manifest_token"] =
            json!(format!("{}=", fixture["manifest_token"].as_str().unwrap()));
        assert!(verify_fixture(&fixture).is_err());
        let foreign_key = SigningKey::from_bytes(&[0x48; 32]);
        fixture["manifest_token"] = json!(sign(&fixture["manifest_claims"], &foreign_key));
        assert!(verify_fixture(&fixture).is_err());
    }

    #[test]
    fn transport_metadata_origin_and_artifact_integrity_are_bounded() {
        assert!(
            response_metadata(
                &[obfstr::obfstr!("application/octet-stream")],
                &["42"],
                &[obfstr::obfstr!("identity")],
                obfstr::obfstr!("application/octet-stream"),
                42
            )
            .is_ok()
        );
        for (types, lengths, encodings) in [
            (vec![], vec![], vec![]),
            (vec!["text/html"], vec![], vec![]),
            (
                vec![
                    obfstr::obfstr!("application/octet-stream"),
                    obfstr::obfstr!("application/octet-stream"),
                ],
                vec![],
                vec![],
            ),
            (
                vec![obfstr::obfstr!("application/octet-stream")],
                vec!["42", "42"],
                vec![],
            ),
            (
                vec![obfstr::obfstr!("application/octet-stream")],
                vec!["43"],
                vec![],
            ),
            (
                vec![obfstr::obfstr!("application/octet-stream")],
                vec!["042"],
                vec![],
            ),
            (
                vec![obfstr::obfstr!("application/octet-stream")],
                vec!["+42"],
                vec![],
            ),
            (
                vec![obfstr::obfstr!("application/octet-stream")],
                vec!["18446744073709551616"],
                vec![],
            ),
            (
                vec![obfstr::obfstr!("application/octet-stream")],
                vec![],
                vec!["gzip"],
            ),
            (
                vec![obfstr::obfstr!("application/octet-stream")],
                vec![],
                vec![obfstr::obfstr!("identity"), obfstr::obfstr!("identity")],
            ),
        ] {
            assert!(
                response_metadata(
                    &types,
                    &lengths,
                    &encodings,
                    obfstr::obfstr!("application/octet-stream"),
                    42
                )
                .is_err()
            );
        }
        for origin in [
            "http://auth.invalid/",
            "https://other@auth.invalid/",
            "https://auth.invalid/path",
            "https://auth.invalid/?q=1",
            "https://auth.invalid/#x",
            "https://auth.invalid:0/",
        ] {
            assert!(
                HttpsTransport::new(origin).is_err(),
                "accepted unsafe origin"
            );
        }
        assert!(HttpsTransport::new("https://auth.invalid/").is_ok()); // Parsing only; no requests.
        let fixture = golden();
        let catalog = verify_fixture(&fixture).unwrap();
        let mut bytes = decode(fixture["artifact_bytes"]["bootstrap-a"].as_str().unwrap()).unwrap();
        bytes[0] ^= 1;
        assert!(verify_artifact(&bytes, &catalog.artifacts[0]).is_err());
        bytes.push(0);
        assert!(verify_artifact(&bytes, &catalog.artifacts[0]).is_err());
        assert!(manifest_from_response(br#"{"manifest":"token","extra":"value"}"#).is_err());
    }

    #[test]
    fn streaming_bounds_short_reads_and_io_errors_fail_closed() {
        use std::io::{self, Cursor};
        assert_eq!(
            read_bounded(Cursor::new(b"1234"), Some(4), 4).unwrap(),
            b"1234"
        );
        assert!(read_bounded(Cursor::new(b"12345"), None, 4).is_err());
        assert!(read_bounded(Cursor::new(b"123"), Some(4), 4).is_err());
        assert!(read_bounded(Cursor::new(b"1234"), Some(3), 4).is_err());
        assert!(read_bounded(Cursor::new(b""), None, 0).is_err());
        assert!(
            read_bounded(
                Cursor::new(b""),
                None,
                MAX_ARTIFACT_BYTES + crate::artifact_envelope::OVERHEAD + 1
            )
            .is_err()
        );
        struct InterruptedBody;
        impl Read for InterruptedBody {
            fn read(&mut self, _: &mut [u8]) -> io::Result<usize> {
                Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "sensitive transport context",
                ))
            }
        }
        let error = read_bounded(InterruptedBody, None, 4).unwrap_err();
        assert!(!format!("{error:#}").contains("sensitive"));
        assert!(error.source().is_none());
    }
}
