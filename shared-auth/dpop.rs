//! RFC 9449 proofs made with the existing installation Ed25519 key.
//! This module neither persists keys nor permits bearer-only resource requests.
use anyhow::{Context, Result, ensure};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use ed25519_dalek::{Signer, SigningKey};
use rand::{RngCore, rngs::OsRng};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::time::{SystemTime, UNIX_EPOCH};

pub(crate) fn thumbprint(key: &SigningKey) -> String {
    // RFC 7638: include only these JWK members, in lexicographic order.
    let public = URL_SAFE_NO_PAD.encode(key.verifying_key().as_bytes());
    let canonical = format!(r#"{{"crv":"Ed25519","kty":"OKP","x":"{public}"}}"#);
    URL_SAFE_NO_PAD.encode(Sha256::digest(canonical.as_bytes()))
}

pub(crate) fn confirmation(claims: &Value) -> Result<&str> {
    let confirmation = claims
        .get("cnf")
        .and_then(Value::as_object)
        .context("Server token omitted its DPoP key binding")?;
    ensure!(confirmation.len() == 1, "Invalid DPoP confirmation members");
    let jkt = confirmation
        .get("jkt")
        .and_then(Value::as_str)
        .context("Server token omitted its DPoP key thumbprint")?;
    let decoded = URL_SAFE_NO_PAD
        .decode(jkt)
        .context("Invalid DPoP key thumbprint")?;
    ensure!(
        decoded.len() == 32 && URL_SAFE_NO_PAD.encode(&decoded) == jkt,
        "Invalid DPoP key thumbprint"
    );
    Ok(jkt)
}

pub(crate) fn require_binding(claims: &Value, key: &SigningKey) -> Result<()> {
    ensure!(
        confirmation(claims)? == thumbprint(key),
        "Server token belongs to another installation signing key"
    );
    Ok(())
}

pub(crate) fn proof(
    key: &SigningKey,
    method: &str,
    url: &str,
    token: Option<&str>,
) -> Result<String> {
    // All Celeste API routes are canonical, query-free HTTPS URLs. Fail closed
    // rather than signing one URI while the HTTP client normalizes another.
    let parsed = ureq::get(url)
        .request_url()
        .context("Invalid DPoP request URL")?;
    let parsed = parsed.as_url();
    ensure!(
        parsed.scheme() == "https"
            && parsed.has_host()
            && parsed.username().is_empty()
            && parsed.password().is_none()
            && parsed.query().is_none()
            && parsed.fragment().is_none()
            && parsed.as_str() == url,
        "DPoP requires a canonical HTTPS request URL"
    );
    ensure!(
        !method.is_empty() && method.bytes().all(|byte| byte.is_ascii_uppercase()),
        "DPoP requires an uppercase HTTP method"
    );
    let mut random = [0_u8; 32];
    OsRng
        .try_fill_bytes(&mut random)
        .context("System randomness is unavailable for a fresh DPoP proof")?;
    let mut claims = json!({
        "jti": URL_SAFE_NO_PAD.encode(random),
        "htm": method,
        "htu": url,
        "iat": SystemTime::now().duration_since(UNIX_EPOCH)?.as_secs(),
    });
    if let Some(token) = token {
        ensure!(
            !token.is_empty() && token.bytes().all(|byte| (b'!'..=b'~').contains(&byte)),
            "DPoP requires an ASCII access token"
        );
        claims["ath"] = json!(URL_SAFE_NO_PAD.encode(Sha256::digest(token.as_bytes())));
    }
    let header = json!({"typ":"dpop+jwt", "alg":"EdDSA", "jwk": {
        "kty":"OKP", "crv":"Ed25519", "x":URL_SAFE_NO_PAD.encode(key.verifying_key().as_bytes())
    }});
    let unsigned = format!(
        "{}.{}",
        URL_SAFE_NO_PAD.encode(serde_json::to_vec(&header)?),
        URL_SAFE_NO_PAD.encode(serde_json::to_vec(&claims)?)
    );
    let signature = key.sign(unsigned.as_bytes());
    Ok(format!(
        "{unsigned}.{}",
        URL_SAFE_NO_PAD.encode(signature.to_bytes())
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signature, Verifier};

    fn parts(jwt: &str) -> (Value, Value) {
        let parts = jwt.split('.').collect::<Vec<_>>();
        assert_eq!(parts.len(), 3);
        (
            serde_json::from_slice(&URL_SAFE_NO_PAD.decode(parts[0]).unwrap()).unwrap(),
            serde_json::from_slice(&URL_SAFE_NO_PAD.decode(parts[1]).unwrap()).unwrap(),
        )
    }

    #[test]
    fn resource_proof_has_public_jwk_exact_target_and_token_hash() {
        let key = SigningKey::from_bytes(&[0x41; 32]);
        let url = "https://auth.example.test/v1/beta/manifest";
        let jwt = proof(&key, "GET", url, Some("test.access.token")).unwrap();
        let (header, claims) = parts(&jwt);
        assert_eq!(
            header,
            json!({"typ":"dpop+jwt", "alg":"EdDSA", "jwk":{
                "kty":"OKP", "crv":"Ed25519", "x":URL_SAFE_NO_PAD.encode(key.verifying_key().as_bytes())
            }})
        );
        assert_eq!(claims["htm"], "GET");
        assert_eq!(claims["htu"], url);
        assert_eq!(
            claims["ath"],
            URL_SAFE_NO_PAD.encode(Sha256::digest(b"test.access.token"))
        );
        assert_eq!(
            URL_SAFE_NO_PAD
                .decode(claims["jti"].as_str().unwrap())
                .unwrap()
                .len(),
            32
        );
        assert!(claims["iat"].as_u64().unwrap() > 0);
        let (unsigned, signature) = jwt.rsplit_once('.').unwrap();
        let signature = Signature::from_slice(&URL_SAFE_NO_PAD.decode(signature).unwrap()).unwrap();
        key.verifying_key()
            .verify(unsigned.as_bytes(), &signature)
            .unwrap();
        let mut changed_claims = claims;
        changed_claims["htm"] = json!("POST");
        let tampered = format!(
            "{}.{}",
            unsigned.split('.').next().unwrap(),
            URL_SAFE_NO_PAD.encode(serde_json::to_vec(&changed_claims).unwrap())
        );
        assert!(
            key.verifying_key()
                .verify(tampered.as_bytes(), &signature)
                .is_err()
        );
    }

    #[test]
    fn exchange_proof_omits_ath_and_every_request_gets_fresh_jti() {
        let key = SigningKey::from_bytes(&[0x41; 32]);
        let url = "https://auth.example.test/v1/installer/complete";
        let first = parts(&proof(&key, "POST", url, None).unwrap()).1;
        let second = parts(&proof(&key, "POST", url, None).unwrap()).1;
        assert!(first.get("ath").is_none());
        assert_ne!(first["jti"], second["jti"]);
    }

    #[test]
    fn resource_token_changes_the_ath() {
        let key = SigningKey::from_bytes(&[0x41; 32]);
        let url = "https://auth.example.test/v1/bootstrap/test";
        let first = parts(&proof(&key, "GET", url, Some("token-a")).unwrap()).1;
        let second = parts(&proof(&key, "GET", url, Some("token-b")).unwrap()).1;
        assert_ne!(first["ath"], second["ath"]);
    }

    #[test]
    fn confirmation_rejects_missing_malformed_and_foreign_keys() {
        let key = SigningKey::from_bytes(&[0x41; 32]);
        let other = SigningKey::from_bytes(&[0x42; 32]);
        let claims = json!({"cnf":{"jkt":thumbprint(&key)}});
        require_binding(&claims, &key).unwrap();
        assert!(require_binding(&claims, &other).is_err());
        for claims in [
            json!({}),
            json!({"cnf":{"jkt":"bad"}}),
            json!({"cnf":{"jkt":thumbprint(&key), "extra":true}}),
        ] {
            assert!(require_binding(&claims, &key).is_err());
        }
    }

    #[test]
    fn rejects_noncanonical_urls_methods_and_tokens() {
        let key = SigningKey::from_bytes(&[0x41; 32]);
        for url in [
            "http://auth.example.test/v1/test",
            "https://auth.example.test/v1/test?q=1",
            "https://auth.example.test/v1/test#fragment",
            "https://user@auth.example.test/v1/test",
            "https://auth.example.test/v1/../test",
            "https://auth.example.test",
        ] {
            assert!(proof(&key, "GET", url, None).is_err(), "accepted {url}");
        }
        let url = "https://auth.example.test/v1/test";
        assert!(proof(&key, "get", url, None).is_err());
        assert!(proof(&key, "GET", url, Some("bad\r\ntoken")).is_err());
        assert!(proof(&key, "GET", url, Some("nön-ascii")).is_err());
        assert!(proof(&key, "GET", url, Some("")).is_err());
    }
}
