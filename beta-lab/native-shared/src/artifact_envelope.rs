//! Per-request X25519/HKDF/AES-GCM artifact envelope. No key or plaintext persistence.
use aes_gcm::{
    Aes256Gcm, KeyInit, Nonce,
    aead::{Aead, Payload},
};
use anyhow::{Result, ensure};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use hkdf::Hkdf;
use rand::{RngCore, rngs::OsRng};
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroizing;

pub(crate) const OVERHEAD: usize = 66;
pub(crate) struct RequestKey {
    private: StaticSecret,
    pub public: String,
    pub nonce: String,
}
impl RequestKey {
    pub(crate) fn new() -> Result<Self> {
        let private = StaticSecret::random_from_rng(OsRng);
        let public = URL_SAFE_NO_PAD.encode(PublicKey::from(&private).as_bytes());
        let mut random = [0u8; 24];
        OsRng.try_fill_bytes(&mut random)?;
        Ok(Self {
            private,
            public,
            nonce: URL_SAFE_NO_PAD.encode(random),
        })
    }
    fn info(&self, path: &str) -> Vec<u8> {
        format!(
            "{}\n{path}\n{}\n{}",
            obfstr::obfstr!("celeste-artifact-v1"),
            self.nonce,
            self.public
        )
        .into_bytes()
    }
    #[inline(never)]
    pub(crate) fn decrypt(
        &self,
        envelope: &[u8],
        path: &str,
        token: &str,
        limit: usize,
    ) -> Result<Zeroizing<Vec<u8>>> {
        ensure!(
            envelope.len() > OVERHEAD
                && envelope.len() <= limit + OVERHEAD
                && envelope.starts_with(b"CSTAE1"),
            "{}",
            obfstr::obfstr!("Encrypted artifact envelope is invalid")
        );
        let public: [u8; 32] = envelope[6..38].try_into()?;
        let shared = self.private.diffie_hellman(&PublicKey::from(public));
        ensure!(
            shared.was_contributory(),
            "{}",
            obfstr::obfstr!("Invalid artifact agreement key")
        );
        let info = self.info(path);
        let salt = Sha256::digest(token.as_bytes());
        let mut key = Zeroizing::new([0u8; 32]);
        Hkdf::<Sha256>::new(Some(&salt), shared.as_bytes())
            .expand(&info, &mut *key)
            .map_err(|_| {
                anyhow::anyhow!("{}", obfstr::obfstr!("Artifact key derivation failed"))
            })?;
        let cipher = Aes256Gcm::new_from_slice(&*key)
            .map_err(|_| anyhow::anyhow!("{}", obfstr::obfstr!("Artifact cipher unavailable")))?;
        let plain = cipher
            .decrypt(
                Nonce::from_slice(&envelope[38..50]),
                Payload {
                    msg: &envelope[50..],
                    aad: &info,
                },
            )
            .map_err(|_| {
                anyhow::anyhow!(
                    "{}",
                    obfstr::obfstr!("Encrypted artifact authentication failed")
                )
            })?;
        ensure!(
            !plain.is_empty() && plain.len() <= limit,
            "{}",
            obfstr::obfstr!("Artifact plaintext bound exceeded")
        );
        Ok(Zeroizing::new(plain))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn actual_python_server_vector_decrypts_and_cannot_be_replayed_for_new_request() {
        let fixture: serde_json::Value = serde_json::from_str(include_str!(
            "../../../auth-service/tests/fixtures/artifact-envelope-v1.json"
        ))
        .unwrap();
        let private: [u8; 32] = URL_SAFE_NO_PAD
            .decode(fixture["client_private_key"].as_str().unwrap())
            .unwrap()
            .try_into()
            .unwrap();
        let request = RequestKey {
            private: StaticSecret::from(private),
            public: fixture["client_public_key"].as_str().unwrap().into(),
            nonce: fixture["request_nonce"].as_str().unwrap().into(),
        };
        let envelope = URL_SAFE_NO_PAD
            .decode(fixture["envelope"].as_str().unwrap())
            .unwrap();
        let plaintext = URL_SAFE_NO_PAD
            .decode(fixture["plaintext"].as_str().unwrap())
            .unwrap();
        let path = fixture["request_path"].as_str().unwrap();
        let token = fixture["installer_token"].as_str().unwrap();
        assert_eq!(
            *request.decrypt(&envelope, path, token, 1024).unwrap(),
            plaintext
        );
        assert!(
            RequestKey::new()
                .unwrap()
                .decrypt(&envelope, path, token, 1024)
                .is_err()
        );
    }
    fn sealed(request: &RequestKey, path: &str, token: &str, plain: &[u8]) -> Vec<u8> {
        let server = StaticSecret::from([19; 32]);
        let public = PublicKey::from(&server);
        let client: [u8; 32] = URL_SAFE_NO_PAD
            .decode(&request.public)
            .unwrap()
            .try_into()
            .unwrap();
        let shared = server.diffie_hellman(&PublicKey::from(client));
        let info = request.info(path);
        let mut key = [0u8; 32];
        Hkdf::<Sha256>::new(Some(&Sha256::digest(token)), shared.as_bytes())
            .expand(&info, &mut key)
            .unwrap();
        let cipher = Aes256Gcm::new_from_slice(&key).unwrap();
        let nonce = [11; 12];
        let ciphertext = cipher
            .encrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: plain,
                    aad: &info,
                },
            )
            .unwrap();
        [b"CSTAE1".as_slice(), public.as_bytes(), &nonce, &ciphertext].concat()
    }
    #[test]
    fn envelope_is_bound_to_ephemeral_key_nonce_path_and_installer() {
        let request = RequestKey::new().unwrap();
        let path = "/v1/beta/artifacts/test/bootstrap-a";
        let token = "signed.fixture.grant";
        let envelope = sealed(&request, path, token, b"PK protected fixture jar");
        assert_eq!(
            &**request.decrypt(&envelope, path, token, 100).unwrap(),
            b"PK protected fixture jar"
        );
        assert!(
            request
                .decrypt(&envelope, "/v1/beta/artifacts/test/payload-a", token, 100)
                .is_err()
        );
        assert!(
            request
                .decrypt(&envelope, path, "another.grant", 100)
                .is_err()
        );
        assert!(
            RequestKey::new()
                .unwrap()
                .decrypt(&envelope, path, token, 100)
                .is_err()
        );
        for index in [0, 6, 38, envelope.len() - 1] {
            let mut bad = envelope.clone();
            bad[index] ^= 1;
            assert!(request.decrypt(&bad, path, token, 100).is_err());
        }
        assert!(request.decrypt(&envelope, path, token, 2).is_err());
        assert!(
            request
                .decrypt(b"plaintext is not an encrypted artifact", path, token, 100)
                .is_err()
        );
    }
}
