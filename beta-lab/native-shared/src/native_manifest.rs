//! Fresh server-signed executable allowlist. This is a local integrity gate;
//! server-side licensing still requires the independent installation proofs.
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use ed25519_dalek::{Signature, VerifyingKey};
use rand::{RngCore, rngs::OsRng};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    fmt,
    io::Read,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const MAX_RESPONSE: usize = 16_384;
const MAX_EXE: u64 = 256 * 1024 * 1024;

#[derive(Debug, Clone, Copy, Eq, PartialEq)]
pub(crate) enum IntegrityError {
    Unavailable,
    Rejected,
}
impl fmt::Display for IntegrityError {
    fn fmt(&self, out: &mut fmt::Formatter<'_>) -> fmt::Result {
        out.write_str(match self {
            Self::Unavailable => "Connection required to verify this client. Try again when the verification service is available.",
            Self::Rejected => "Client integrity check failed. Please download a fresh copy of Celeste.",
        })
    }
}
impl std::error::Error for IntegrityError {}
type Result<T> = std::result::Result<T, IntegrityError>;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Envelope {
    manifest: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Header {
    alg: String,
    kid: String,
    typ: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Claims {
    aud: String,
    product: String,
    platform: String,
    request_nonce: String,
    iat: u64,
    nbf: u64,
    exp: u64,
    sequence: u64,
    sha256: Vec<String>,
}

fn decode(value: &str) -> Result<Vec<u8>> {
    if value.is_empty()
        || !value
            .bytes()
            .all(|v| v.is_ascii_alphanumeric() || v == b'_' || v == b'-')
    {
        return Err(IntegrityError::Rejected);
    }
    let bytes = URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| IntegrityError::Rejected)?;
    if URL_SAFE_NO_PAD.encode(&bytes) != value {
        return Err(IntegrityError::Rejected);
    }
    Ok(bytes)
}

fn platform() -> &'static str {
    if cfg!(all(windows, target_arch = "x86_64")) {
        "windows-x64"
    } else if cfg!(all(target_os = "linux", target_arch = "x86_64")) {
        "linux-x64"
    } else if cfg!(all(target_os = "macos", target_arch = "x86_64")) {
        "macos-x64"
    } else if cfg!(all(target_os = "macos", target_arch = "aarch64")) {
        "macos-arm64"
    } else {
        "unsupported"
    }
}

fn hash_executable(mut input: impl Read) -> Result<String> {
    let mut hasher = Sha256::new();
    let mut buffer = [0u8; 32768];
    let mut total = 0u64;
    loop {
        let count = input
            .read(&mut buffer)
            .map_err(|_| IntegrityError::Unavailable)?;
        if count == 0 {
            break;
        }
        total += count as u64;
        if total > MAX_EXE {
            return Err(IntegrityError::Rejected);
        }
        hasher.update(&buffer[..count]);
    }
    if total == 0 {
        return Err(IntegrityError::Rejected);
    }
    Ok(format!("{:x}", hasher.finalize()))
}

fn hex_digest(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|v| v.is_ascii_digit() || (b'a'..=b'f').contains(&v))
}

fn verify(
    body: &[u8],
    key: &VerifyingKey,
    product: &str,
    target: &str,
    nonce: &str,
    digest: &str,
    now: u64,
) -> Result<()> {
    if body.len() > MAX_RESPONSE || decode(nonce)?.len() != 24 || !hex_digest(digest) {
        return Err(IntegrityError::Rejected);
    }
    let envelope: Envelope = serde_json::from_slice(body).map_err(|_| IntegrityError::Rejected)?;
    let parts: Vec<_> = envelope.manifest.split('.').collect();
    if parts.len() != 3 || parts[0].len() > 512 || parts[1].len() > 8192 {
        return Err(IntegrityError::Rejected);
    }
    let signature =
        Signature::from_slice(&decode(parts[2])?).map_err(|_| IntegrityError::Rejected)?;
    let signed_len = parts[0].len() + 1 + parts[1].len();
    key.verify_strict(&envelope.manifest.as_bytes()[..signed_len], &signature)
        .map_err(|_| IntegrityError::Rejected)?;
    let header: Header =
        serde_json::from_slice(&decode(parts[0])?).map_err(|_| IntegrityError::Rejected)?;
    if header.alg != "EdDSA" || header.typ != "CST" || header.kid != "celeste-release-1" {
        return Err(IntegrityError::Rejected);
    }
    let claims: Claims =
        serde_json::from_slice(&decode(parts[1])?).map_err(|_| IntegrityError::Rejected)?;
    if claims.aud != "celeste-native-manifest"
        || claims.product != product
        || claims.platform != target
        || claims.request_nonce != nonce
        || claims.iat > now.saturating_add(5)
        || claims.nbf != claims.iat
        || claims.exp <= now
        || claims.exp <= claims.iat
        || claims.exp - claims.iat > 120
        || claims.sequence == 0
        || claims.sequence > i64::MAX as u64
        || !(1..=4).contains(&claims.sha256.len())
    {
        return Err(IntegrityError::Rejected);
    }
    let mut seen = std::collections::HashSet::new();
    if claims
        .sha256
        .iter()
        .any(|value| !hex_digest(value) || !seen.insert(value))
        || !claims.sha256.iter().any(|value| value == digest)
    {
        return Err(IntegrityError::Rejected);
    }
    Ok(())
}

/// Hash the running executable's final signed bytes and require a newly signed
/// allowlist response. Never cache approval or accept a local expected hash.
pub(crate) fn require_current(product: &str) -> Result<()> {
    let target = platform();
    if !matches!(product, "celeste" | "celeste_plus") || target == "unsupported" {
        return Err(IntegrityError::Rejected);
    }
    let executable = std::env::current_exe().map_err(|_| IntegrityError::Unavailable)?;
    let file = std::fs::File::open(executable).map_err(|_| IntegrityError::Unavailable)?;
    let digest = hash_executable(file)?;
    let mut random = [0u8; 24];
    OsRng
        .try_fill_bytes(&mut random)
        .map_err(|_| IntegrityError::Unavailable)?;
    let nonce = URL_SAFE_NO_PAD.encode(random);
    let key: [u8; 32] = decode(crate::release::SERVER_KEY)?
        .try_into()
        .map_err(|_| IntegrityError::Rejected)?;
    let key = VerifyingKey::from_bytes(&key).map_err(|_| IntegrityError::Rejected)?;
    let url = format!(
        "{}/v1/client-manifest?product={product}&platform={target}",
        crate::release::BASE_URL
    );
    let agent = ureq::AgentBuilder::new()
        .https_only(true)
        .redirects(0)
        .timeout_connect(Duration::from_secs(8))
        .timeout(Duration::from_secs(25))
        .max_idle_connections(0)
        .build();
    let response = agent
        .get(&url)
        .set("X-Celeste-Request-Nonce", &nonce)
        .set("Accept", "application/json")
        .set("Accept-Encoding", "identity")
        .set("Cache-Control", "no-store")
        .call()
        .map_err(|_| IntegrityError::Unavailable)?;
    if response.status() != 200
        || response.get_url() != url
        || !matches!(
            response
                .header("Content-Type")
                .and_then(|v| v.split(';').next()),
            Some("application/json")
        )
        || response
            .header("Content-Encoding")
            .is_some_and(|v| v != "identity")
    {
        return Err(IntegrityError::Rejected);
    }
    let mut body = Vec::new();
    response
        .into_reader()
        .take(MAX_RESPONSE as u64 + 1)
        .read_to_end(&mut body)
        .map_err(|_| IntegrityError::Unavailable)?;
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| IntegrityError::Unavailable)?
        .as_secs();
    verify(&body, &key, product, target, &nonce, &digest, now)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};
    use serde_json::{Value, json};
    const NONCE: &str = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    fn claims() -> Value {
        json!({"aud":"celeste-native-manifest","product":"celeste_plus","platform":"windows-x64","request_nonce":NONCE,"iat":1000,"nbf":1000,"exp":1120,"sequence":1,"sha256":["a".repeat(64)]})
    }
    fn envelope_raw(header: &str, claims: &str) -> Vec<u8> {
        let key = SigningKey::from_bytes(&[0x55; 32]);
        let input = format!(
            "{}.{}",
            URL_SAFE_NO_PAD.encode(header),
            URL_SAFE_NO_PAD.encode(claims)
        );
        let token = format!(
            "{input}.{}",
            URL_SAFE_NO_PAD.encode(key.sign(input.as_bytes()).to_bytes())
        );
        serde_json::to_vec(&json!({"manifest":token})).unwrap()
    }
    fn envelope(claims: &Value) -> Vec<u8> {
        envelope_raw(
            r#"{"alg":"EdDSA","kid":"celeste-release-1","typ":"CST"}"#,
            &claims.to_string(),
        )
    }
    fn check(claims: &Value) -> Result<()> {
        verify(
            &envelope(claims),
            &SigningKey::from_bytes(&[0x55; 32]).verifying_key(),
            "celeste_plus",
            "windows-x64",
            NONCE,
            &"a".repeat(64),
            1000,
        )
    }
    #[test]
    fn signed_matching_final_executable_is_allowed() {
        assert_eq!(check(&claims()), Ok(()));
    }
    #[test]
    fn signed_stale_or_other_context_is_denied() {
        for (field, value) in [
            ("aud", json!("celeste-installer")),
            ("product", json!("celeste")),
            ("platform", json!("linux-x64")),
            ("request_nonce", json!("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")),
            ("sequence", json!(0)),
            ("exp", json!(1000)),
            ("exp", json!(1121)),
            ("iat", json!(1006)),
            ("nbf", json!(999)),
            ("sha256", json!(["b".repeat(64)])),
        ] {
            let mut value_claims = claims();
            value_claims[field] = value;
            assert_eq!(
                check(&value_claims),
                Err(IntegrityError::Rejected),
                "{field}"
            );
        }
    }
    #[test]
    fn malformed_or_missing_nonce_and_duplicate_fields_are_denied() {
        let mut value = claims();
        value.as_object_mut().unwrap().remove("request_nonce");
        assert!(check(&value).is_err());
        for hashes in [
            json!([]),
            json!(["a".repeat(64), "a".repeat(64)]),
            json!(["A".repeat(64)]),
            json!([
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64),
                "e".repeat(64)
            ]),
        ] {
            let mut value = claims();
            value["sha256"] = hashes;
            assert!(check(&value).is_err());
        }
        let raw = claims().to_string().replacen('{', "{\"sequence\":1,", 1);
        let body = envelope_raw(
            r#"{"alg":"EdDSA","kid":"celeste-release-1","typ":"CST"}"#,
            &raw,
        );
        assert!(
            verify(
                &body,
                &SigningKey::from_bytes(&[0x55; 32]).verifying_key(),
                "celeste_plus",
                "windows-x64",
                NONCE,
                &"a".repeat(64),
                1000
            )
            .is_err()
        );
    }
    #[test]
    fn signature_cannot_select_a_different_key_or_algorithm() {
        let body = envelope(&claims());
        assert!(
            verify(
                &body,
                &SigningKey::from_bytes(&[0x56; 32]).verifying_key(),
                "celeste_plus",
                "windows-x64",
                NONCE,
                &"a".repeat(64),
                1000
            )
            .is_err()
        );
        let body = envelope_raw(
            r#"{"alg":"none","kid":"celeste-release-1","typ":"CST"}"#,
            &claims().to_string(),
        );
        assert!(
            verify(
                &body,
                &SigningKey::from_bytes(&[0x55; 32]).verifying_key(),
                "celeste_plus",
                "windows-x64",
                NONCE,
                &"a".repeat(64),
                1000
            )
            .is_err()
        );
    }
    #[test]
    fn changed_executable_bytes_and_empty_files_are_rejected() {
        let original = hash_executable(&b"final signed executable fixture"[..]).unwrap();
        let modified = hash_executable(&b"final signed executable fixturE"[..]).unwrap();
        assert_ne!(original, modified);
        assert!(hash_executable(&b""[..]).is_err());
        let mut value = claims();
        value["sha256"] = json!([original]);
        assert!(
            verify(
                &envelope(&value),
                &SigningKey::from_bytes(&[0x55; 32]).verifying_key(),
                "celeste_plus",
                "windows-x64",
                NONCE,
                &modified,
                1000
            )
            .is_err()
        );
    }
    #[test]
    fn fixture_key_is_not_production_trust_and_errors_are_redacted() {
        let raw: [u8; 32] = decode(crate::release::SERVER_KEY)
            .unwrap()
            .try_into()
            .unwrap();
        assert!(
            verify(
                &envelope(&claims()),
                &VerifyingKey::from_bytes(&raw).unwrap(),
                "celeste_plus",
                "windows-x64",
                NONCE,
                &"a".repeat(64),
                1000
            )
            .is_err()
        );
        assert!(!IntegrityError::Rejected.to_string().contains(NONCE));
    }

    #[test]
    fn real_server_endpoint_fixture_and_captured_replay() {
        let fixture: Value =
            serde_json::from_str(include_str!("../tests/native-manifest-golden.json")).unwrap();
        let public: [u8; 32] = decode(fixture["public_key"].as_str().unwrap())
            .unwrap()
            .try_into()
            .unwrap();
        let key = VerifyingKey::from_bytes(&public).unwrap();
        let body = serde_json::to_vec(&fixture["response"]).unwrap();
        let digest =
            hash_executable(fixture["executable_utf8"].as_str().unwrap().as_bytes()).unwrap();
        assert_eq!(digest, fixture["sha256"].as_str().unwrap());
        let check = |nonce| {
            verify(
                &body,
                &key,
                fixture["product"].as_str().unwrap(),
                fixture["platform"].as_str().unwrap(),
                nonce,
                &digest,
                fixture["now"].as_u64().unwrap(),
            )
        };
        assert_eq!(
            check(fixture["expected_request_nonce"].as_str().unwrap()),
            Ok(())
        );
        assert_eq!(check(NONCE), Err(IntegrityError::Rejected));
    }
}
