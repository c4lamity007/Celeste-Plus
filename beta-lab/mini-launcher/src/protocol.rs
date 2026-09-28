use anyhow::{Context, Result, bail, ensure};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use ed25519_dalek::{Signature, VerifyingKey};
use rand::{RngCore, rngs::OsRng};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    io::{Read, Write},
    sync::atomic::{AtomicBool, Ordering},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

pub const ORIGIN: &str = "https://qb0f90cf11f4d1d52.fly.dev";
const SERVER_KEY: &str = "rhLhkicLy3laX7_Q7rSv9OGVyrSHr1OyEZW8_hJfoKY";
const MAX_RESPONSE: usize = 16_384;
const MAX_DOWNLOAD: u64 = 256 * 1024 * 1024;

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

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    aud: String,
    iat: u64,
    nbf: u64,
    pub exp: u64,
    request_nonce: String,
    product: String,
    platform: String,
    sequence: u64,
    release_id: String,
    pub sha256: String,
    pub size: u64,
    pub url: String,
}

pub fn now() -> Result<u64> {
    Ok(SystemTime::now().duration_since(UNIX_EPOCH)?.as_secs())
}

pub fn cancelled(cancel: &AtomicBool) -> Result<()> {
    ensure!(!cancel.load(Ordering::Acquire), "Download canceled.");
    Ok(())
}

fn decode(text: &str) -> Result<Vec<u8>> {
    ensure!(!text.is_empty(), "Missing signed download information.");
    let bytes = URL_SAFE_NO_PAD.decode(text)?;
    ensure!(
        URL_SAFE_NO_PAD.encode(&bytes) == text,
        "Invalid download signature encoding."
    );
    Ok(bytes)
}

fn verify(body: &[u8], nonce: &str, key: &VerifyingKey, now: u64) -> Result<Manifest> {
    ensure!(
        body.len() <= MAX_RESPONSE && decode(nonce)?.len() == 24,
        "Invalid download response."
    );
    let envelope: Envelope = serde_json::from_slice(body)?;
    let parts: Vec<_> = envelope.manifest.split('.').collect();
    ensure!(
        parts.len() == 3 && parts[0].len() <= 512 && parts[1].len() <= 8192 && parts[2].len() == 86,
        "Invalid signed download information."
    );
    let signature = Signature::from_slice(&decode(parts[2])?)?;
    key.verify_strict(
        &envelope.manifest.as_bytes()[..parts[0].len() + 1 + parts[1].len()],
        &signature,
    )?;
    let header: Header = serde_json::from_slice(&decode(parts[0])?)?;
    ensure!(
        header.alg == "EdDSA" && header.kid == "celeste-release-1" && header.typ == "CST",
        "Unexpected download signature."
    );
    let claims: Manifest = serde_json::from_slice(&decode(parts[1])?)?;
    ensure!(
        claims.aud == "celeste-launcher-download"
            && claims.product == "celeste_plus"
            && claims.platform == "windows-x64"
            && claims.request_nonce == nonce,
        "Download approval belongs to a different request."
    );
    ensure!(
        claims.nbf == claims.iat
            && claims.iat <= now.saturating_add(5)
            && claims.exp > now
            && claims.exp > claims.iat
            && claims.exp - claims.iat <= 120,
        "Download approval expired. Please try again."
    );
    ensure!(
        (1..=i64::MAX as u64).contains(&claims.sequence)
            && (1..=MAX_DOWNLOAD).contains(&claims.size),
        "Unexpected download size or version."
    );
    ensure!(
        claims.sha256.len() == 64
            && claims
                .sha256
                .bytes()
                .all(|v| v.is_ascii_digit() || (b'a'..=b'f').contains(&v)),
        "Invalid download checksum."
    );
    ensure!(
        (1..=80).contains(&claims.release_id.len())
            && claims.release_id.as_bytes()[0].is_ascii_alphanumeric()
            && claims
                .release_id
                .bytes()
                .all(|v| v.is_ascii_alphanumeric() || v == b'-' || v == b'_'),
        "Invalid download version."
    );
    ensure!(
        claims.url
            == format!(
                "{ORIGIN}/v1/launcher/windows-x64/{}/Celeste+.exe",
                claims.sha256
            ),
        "Unexpected download address."
    );
    Ok(claims)
}

pub fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .https_only(true)
        .redirects(0)
        .max_idle_connections(0)
        .timeout_connect(Duration::from_secs(8))
        .timeout_read(Duration::from_secs(8))
        .timeout_write(Duration::from_secs(8))
        .timeout(Duration::from_secs(300))
        .user_agent("Celeste-Plus-Mini/1.0")
        .build()
}

fn get(
    agent: &ureq::Agent,
    url: &str,
    nonce: Option<&str>,
    cancel: &AtomicBool,
) -> Result<ureq::Response> {
    for attempt in 0..4 {
        cancelled(cancel)?;
        let mut request = agent.get(url).set("Accept-Encoding", "identity");
        if let Some(nonce) = nonce {
            request = request
                .set("X-Celeste-Request-Nonce", nonce)
                .set("Accept", "application/json")
                .timeout(Duration::from_secs(20));
        }
        match request.call() {
            Ok(response) => {
                ensure!(
                    response.status() == 200
                        && response.get_url() == url
                        && response.header("Content-Encoding").unwrap_or("identity") == "identity",
                    "Unexpected response from the download server."
                );
                return Ok(response);
            }
            Err(ureq::Error::Status(429 | 503, response)) if attempt < 3 => {
                let delay = response
                    .header("Retry-After")
                    .and_then(|v| v.parse::<u64>().ok())
                    .unwrap_or(1)
                    .clamp(1, 3);
                for _ in 0..delay * 10 {
                    cancelled(cancel)?;
                    std::thread::sleep(Duration::from_millis(100));
                }
            }
            Err(error) => return Err(error.into()),
        }
    }
    bail!("The download server is busy. Please try again shortly.")
}

pub fn manifest(agent: &ureq::Agent, cancel: &AtomicBool) -> Result<Manifest> {
    let mut random = [0; 24];
    OsRng.try_fill_bytes(&mut random)?;
    let nonce = URL_SAFE_NO_PAD.encode(random);
    let response = get(
        agent,
        &format!("{ORIGIN}/v1/launcher/manifest?platform=windows-x64"),
        Some(&nonce),
        cancel,
    )?;
    ensure!(
        response
            .header("Content-Type")
            .is_some_and(|v| v.split(';').next() == Some("application/json")),
        "Unexpected download information."
    );
    let mut body = Vec::new();
    response
        .into_reader()
        .take((MAX_RESPONSE + 1) as u64)
        .read_to_end(&mut body)?;
    cancelled(cancel)?;
    let raw: [u8; 32] = decode(SERVER_KEY)?
        .try_into()
        .map_err(|_| anyhow::anyhow!("Invalid release key."))?;
    verify(&body, &nonce, &VerifyingKey::from_bytes(&raw)?, now()?)
        .context("Couldn't verify the Celeste+ download.")
}

pub fn copy_verified(
    mut input: impl Read,
    mut output: impl Write,
    manifest: &Manifest,
    cancel: &AtomicBool,
    mut progress: impl FnMut(u64, u64),
) -> Result<()> {
    let started = Instant::now();
    let mut digest = Sha256::new();
    let mut count = 0u64;
    let mut buffer = [0; 64 * 1024];
    loop {
        cancelled(cancel)?;
        ensure!(
            started.elapsed() <= Duration::from_secs(300),
            "The download timed out. Please try again."
        );
        let bytes = input.read(&mut buffer)?;
        if bytes == 0 {
            break;
        }
        count += bytes as u64;
        ensure!(
            count <= manifest.size,
            "The download was larger than expected."
        );
        output.write_all(&buffer[..bytes])?;
        digest.update(&buffer[..bytes]);
        progress(count, manifest.size);
    }
    ensure!(
        count == manifest.size && format!("{:x}", digest.finalize()) == manifest.sha256,
        "The download was incomplete or changed. Please try again."
    );
    output.flush()?;
    cancelled(cancel)
}

pub fn download(
    agent: &ureq::Agent,
    manifest: &Manifest,
    output: impl Write,
    cancel: &AtomicBool,
    progress: impl FnMut(u64, u64),
) -> Result<()> {
    let response = get(agent, &manifest.url, None, cancel)?;
    ensure!(
        response.header("Content-Type") == Some("application/octet-stream")
            && response
                .header("Content-Length")
                .and_then(|v| v.parse::<u64>().ok())
                == Some(manifest.size),
        "The download doesn't match its signed information."
    );
    copy_verified(response.into_reader(), output, manifest, cancel, progress)
}

pub fn refresh_if_needed(agent: &ureq::Agent, old: &Manifest, cancel: &AtomicBool) -> Result<()> {
    if old.exp <= now()?.saturating_add(5) {
        let fresh = manifest(agent, cancel)?;
        ensure!(
            fresh.sha256 == old.sha256 && fresh.size == old.size,
            "Celeste+ was updated during the download. Please open this launcher again."
        );
    }
    cancelled(cancel)
}

#[cfg(test)]
mod tests;
