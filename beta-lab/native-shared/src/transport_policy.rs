//! Signed leaf certificate rotation policy. Its discovery request has NO credentials.
//! The resulting TLS verifier checks normal PKIX AND exact leaf DER before HTTP data.
use crate::delivery::{exact, integer, object, signed, text};
use anyhow::{Result, anyhow, ensure};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use ed25519_dalek::VerifyingKey;
use rand::{RngCore, rngs::OsRng};
use rustls::{
    DigitallySignedStruct, Error, SignatureScheme,
    client::{
        WebPkiServerVerifier,
        danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    },
    pki_types::{CertificateDer, ServerName, UnixTime},
};
use sha2::{Digest, Sha256};
use std::{
    io::Read,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

#[derive(Debug, Clone)]
struct Policy {
    leaves: Vec<String>,
    expires: u64,
}

#[inline(never)]
fn verify(body: &[u8], nonce: &str, origin: &str, key: &VerifyingKey, now: u64) -> Result<Policy> {
    ensure!(
        body.len() <= 16384 && nonce.len() == 32 && URL_SAFE_NO_PAD.decode(nonce)?.len() == 24,
        "{}",
        obfstr::obfstr!("Invalid public transport response")
    );
    let envelope = object(body)?;
    exact(&envelope, &[obfstr::obfstr!("policy")])?;
    let claims = signed(
        text(&envelope, obfstr::obfstr!("policy"))?,
        obfstr::obfstr!("celeste-transport-policy"),
        key,
    )?;
    exact(
        &claims,
        &[
            obfstr::obfstr!("aud"),
            obfstr::obfstr!("product"),
            obfstr::obfstr!("origin"),
            obfstr::obfstr!("request_nonce"),
            obfstr::obfstr!("iat"),
            obfstr::obfstr!("nbf"),
            obfstr::obfstr!("exp"),
            obfstr::obfstr!("leaf_sha256"),
        ],
    )?;
    let issued = integer(&claims, obfstr::obfstr!("iat"))?;
    let expires = integer(&claims, obfstr::obfstr!("exp"))?;
    ensure!(
        text(&claims, obfstr::obfstr!("product"))? == "celeste_plus"
            && text(&claims, obfstr::obfstr!("origin"))? == origin
            && text(&claims, obfstr::obfstr!("request_nonce"))? == nonce
            && integer(&claims, obfstr::obfstr!("nbf"))? == issued
            && issued <= now.saturating_add(5)
            && expires > now
            && expires > issued
            && expires - issued <= 120,
        "{}",
        obfstr::obfstr!("Public transport policy is stale or belongs to another request")
    );
    let values = claims
        .get(obfstr::obfstr!("leaf_sha256"))
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| anyhow!("{}", obfstr::obfstr!("Missing leaf certificate policy")))?;
    ensure!(
        (1..=4).contains(&values.len()),
        "{}",
        obfstr::obfstr!("Invalid leaf certificate count")
    );
    let mut leaves = Vec::new();
    for value in values {
        let value = value
            .as_str()
            .ok_or_else(|| anyhow!("{}", obfstr::obfstr!("Invalid leaf certificate digest")))?;
        ensure!(
            value.len() == 64
                && value
                    .bytes()
                    .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
                && !leaves.iter().any(|old| old == value),
            "{}",
            obfstr::obfstr!("Invalid or duplicate leaf certificate digest")
        );
        leaves.push(value.to_owned());
    }
    Ok(Policy { leaves, expires })
}

#[derive(Debug)]
struct PinnedVerifier {
    webpki: Arc<WebPkiServerVerifier>,
    policy: Policy,
}

impl ServerCertVerifier for PinnedVerifier {
    #[inline(never)]
    fn verify_server_cert(
        &self,
        leaf: &CertificateDer<'_>,
        chain: &[CertificateDer<'_>],
        name: &ServerName<'_>,
        ocsp: &[u8],
        now: UnixTime,
    ) -> Result<ServerCertVerified, Error> {
        let verified = self
            .webpki
            .verify_server_cert(leaf, chain, name, ocsp, now)?;
        let hash = format!("{:x}", Sha256::digest(leaf.as_ref()));
        if now.as_secs() >= self.policy.expires || !self.policy.leaves.contains(&hash) {
            return Err(Error::General(
                obfstr::obfstr!(
                    "Server certificate is not approved by the current transport policy"
                )
                .to_owned(),
            ));
        }
        Ok(verified)
    }
    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        self.webpki.verify_tls12_signature(message, cert, signature)
    }
    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        self.webpki.verify_tls13_signature(message, cert, signature)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.webpki.supported_verify_schemes()
    }
}

fn configured_agent(policy: Policy, roots: rustls::RootCertStore) -> Result<ureq::Agent> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let webpki =
        WebPkiServerVerifier::builder_with_provider(Arc::new(roots), provider.clone()).build()?;
    let mut config = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(PinnedVerifier { webpki, policy }))
        .with_no_client_auth();
    // Do not resume a handshake that might skip certificate policy revalidation.
    config.resumption = rustls::client::Resumption::disabled();
    config.enable_early_data = false;
    Ok(ureq::AgentBuilder::new()
        .https_only(true)
        .redirects(0)
        .max_idle_connections(0)
        .timeout_connect(Duration::from_secs(8))
        .timeout(Duration::from_secs(25))
        .tls_config(Arc::new(config))
        .build())
}

pub(crate) fn agent(origin: &str) -> Result<ureq::Agent> {
    ensure!(
        origin == obfstr::obfstr!(crate::release::BASE_URL) && origin.starts_with("https://"),
        "{}",
        obfstr::obfstr!("Unexpected transport policy origin")
    );
    let mut random = [0u8; 24];
    OsRng.try_fill_bytes(&mut random)?;
    let nonce = URL_SAFE_NO_PAD.encode(random);
    let url = format!(
        "{}{}",
        origin,
        obfstr::obfstr!("/v1/transport-policy?product=celeste_plus")
    );
    // Only nonce, product and public negotiation metadata are sent unpinned.
    let discovery = ureq::AgentBuilder::new()
        .https_only(true)
        .redirects(0)
        .max_idle_connections(0)
        .timeout_connect(Duration::from_secs(8))
        .timeout(Duration::from_secs(25))
        .build();
    let response = discovery
        .get(&url)
        .set(obfstr::obfstr!("X-Celeste-Request-Nonce"), &nonce)
        .set(
            obfstr::obfstr!("Accept"),
            obfstr::obfstr!("application/json"),
        )
        .set(
            obfstr::obfstr!("Accept-Encoding"),
            obfstr::obfstr!("identity"),
        )
        .set(
            obfstr::obfstr!("Cache-Control"),
            obfstr::obfstr!("no-store"),
        )
        .call()
        .map_err(|_| {
            anyhow!(
                "{}",
                obfstr::obfstr!("Connection required to verify the server certificate")
            )
        })?;
    ensure!(
        response.status() == 200
            && response.get_url() == url
            && response
                .header(obfstr::obfstr!("Content-Type"))
                .and_then(|v| v.split(';').next())
                == Some(obfstr::obfstr!("application/json"))
            && response
                .header(obfstr::obfstr!("Content-Encoding"))
                .is_none_or(|v| v == obfstr::obfstr!("identity")),
        "{}",
        obfstr::obfstr!("Invalid public transport response")
    );
    let mut body = Vec::new();
    response.into_reader().take(16385).read_to_end(&mut body)?;
    let key: [u8; 32] = URL_SAFE_NO_PAD
        .decode(obfstr::obfstr!(crate::release::SERVER_KEY))?
        .try_into()
        .map_err(|_| anyhow!("{}", obfstr::obfstr!("Invalid transport trust key")))?;
    let policy = verify(
        &body,
        &nonce,
        origin,
        &VerifyingKey::from_bytes(&key)?,
        SystemTime::now().duration_since(UNIX_EPOCH)?.as_secs(),
    )?;
    let roots = rustls::RootCertStore::from_iter(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    configured_agent(policy, roots)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};
    use serde_json::json;
    fn response(key: &SigningKey, claims: &serde_json::Value) -> Vec<u8> {
        let header =
            URL_SAFE_NO_PAD.encode(br#"{"alg":"EdDSA","kid":"celeste-release-1","typ":"CST"}"#);
        let body = URL_SAFE_NO_PAD.encode(serde_json::to_vec(claims).unwrap());
        let message = format!("{header}.{body}");
        let signature = URL_SAFE_NO_PAD.encode(key.sign(message.as_bytes()).to_bytes());
        serde_json::to_vec(&json!({"policy":format!("{message}.{signature}")})).unwrap()
    }
    #[test]
    fn actual_server_signed_policy_fixture_matches_native_verifier() {
        let fixture: serde_json::Value = serde_json::from_str(include_str!(
            "../../../auth-service/tests/fixtures/transport-policy-v1.json"
        ))
        .unwrap();
        let public: [u8; 32] = URL_SAFE_NO_PAD
            .decode(fixture["public_key"].as_str().unwrap())
            .unwrap()
            .try_into()
            .unwrap();
        let body = serde_json::to_vec(&fixture["response"]).unwrap();
        let policy = verify(
            &body,
            fixture["request_nonce"].as_str().unwrap(),
            fixture["origin"].as_str().unwrap(),
            &VerifyingKey::from_bytes(&public).unwrap(),
            fixture["now"].as_u64().unwrap(),
        )
        .unwrap();
        assert_eq!(
            serde_json::to_value(policy.leaves).unwrap(),
            fixture["leaf_sha256"]
        );
    }
    #[test]
    fn tls_rejects_wrong_leaf_before_any_http_credentials_and_keeps_pkix_hostname_check() {
        use rustls::pki_types::PrivatePkcs8KeyDer;
        use std::{
            io::{Read, Write},
            net::TcpListener,
        };
        for case in 0..3 {
            let certificate = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
            let leaf = certificate.cert.der().clone();
            let mut roots = rustls::RootCertStore::empty();
            roots.add(leaf.clone()).unwrap();
            let hash = format!("{:x}", Sha256::digest(leaf.as_ref()));
            let provider = Arc::new(rustls::crypto::ring::default_provider());
            let config = rustls::ServerConfig::builder_with_provider(provider)
                .with_safe_default_protocol_versions()
                .unwrap()
                .with_no_client_auth()
                .with_single_cert(
                    vec![leaf],
                    PrivatePkcs8KeyDer::from(certificate.key_pair.serialize_der()).into(),
                )
                .unwrap();
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let port = listener.local_addr().unwrap().port();
            let server = std::thread::spawn(move || {
                let (socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(Duration::from_secs(5)))
                    .unwrap();
                let mut tls = rustls::StreamOwned::new(
                    rustls::ServerConnection::new(Arc::new(config)).unwrap(),
                    socket,
                );
                let mut request = [0u8; 4096];
                match tls.read(&mut request) {
                    Ok(length) if length > 0 => {
                        let _ = tls.write_all(
                            b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}",
                        );
                        true
                    }
                    _ => false,
                }
            });
            let policy = Policy {
                leaves: vec![if case == 1 { "0".repeat(64) } else { hash }],
                expires: SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap()
                    .as_secs()
                    + 120,
            };
            let agent = configured_agent(policy, roots).unwrap();
            let host = if case == 2 { "127.0.0.1" } else { "localhost" };
            let result = agent
                .post(&format!("https://{host}:{port}/"))
                .set(
                    obfstr::obfstr!("Authorization"),
                    "DPoP public-test-placeholder",
                )
                .send_string("test-only");
            assert_eq!(result.is_ok(), case == 0);
            assert_eq!(
                server.join().unwrap(),
                case == 0,
                "Untrusted certificate received HTTP data"
            );
        }
    }
    #[test]
    fn signed_policy_requires_fresh_nonce_origin_lifetime_and_canonical_leaf_set() {
        let key = SigningKey::from_bytes(&[49; 32]);
        let nonce = URL_SAFE_NO_PAD.encode([7; 24]);
        let claims = json!({"aud":"celeste-transport-policy","product":"celeste_plus","origin":"https://fixture.invalid",
            "request_nonce":nonce,"iat":1000,"nbf":1000,"exp":1120,"leaf_sha256":["a".repeat(64),"b".repeat(64)]});
        let check = |value: &serde_json::Value| {
            verify(
                &response(&key, value),
                &nonce,
                "https://fixture.invalid",
                &key.verifying_key(),
                1001,
            )
        };
        assert_eq!(check(&claims).unwrap().leaves.len(), 2);
        for (field, value) in [
            ("request_nonce", json!(URL_SAFE_NO_PAD.encode([8; 24]))),
            ("origin", json!("https://foreign.invalid")),
            ("product", json!("celeste")),
            ("exp", json!(1001)),
            ("exp", json!(1121)),
            ("iat", json!(1100)),
            ("leaf_sha256", json!(["A".repeat(64)])),
            ("leaf_sha256", json!(["a".repeat(64), "a".repeat(64)])),
        ] {
            let mut bad = claims.clone();
            bad[field] = value;
            assert!(check(&bad).is_err(), "{field}");
        }
        assert!(
            verify(
                &response(&SigningKey::from_bytes(&[50; 32]), &claims),
                &nonce,
                "https://fixture.invalid",
                &key.verifying_key(),
                1001
            )
            .is_err()
        );
    }
}
