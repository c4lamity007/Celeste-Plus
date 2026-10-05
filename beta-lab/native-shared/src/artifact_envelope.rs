//! Per-request X25519/HKDF/AES-GCM artifact envelope. No key or plaintext persistence.
use aes_gcm::{Aes256Gcm, KeyInit, Nonce, Tag, aead::AeadInPlace};
use anyhow::{Result, ensure};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use hkdf::Hkdf;
use rand::{RngCore, rngs::OsRng};
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::{Zeroize, Zeroizing};

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
        envelope: Vec<u8>,
        path: &str,
        token: &str,
        limit: usize,
    ) -> Result<Zeroizing<Vec<u8>>> {
        let mut envelope = Zeroizing::new(envelope);
        self.decrypt_owned(&mut envelope, path, token, limit)?;
        Ok(envelope)
    }

    // On every failure the owned buffer is wiped before the error is returned.
    // In particular, no caller can observe plaintext until the full tag passes.
    // Preserve the existing VMP selection boundary: all acceptance decisions
    // belong to the separately emitted decrypt entry, not an unselected helper.
    #[inline(always)]
    fn decrypt_owned(
        &self,
        envelope: &mut Zeroizing<Vec<u8>>,
        path: &str,
        token: &str,
        limit: usize,
    ) -> Result<()> {
        let result = (|| {
            ensure!(
                limit > 0
                    && envelope.len() > OVERHEAD
                    && limit
                        .checked_add(OVERHEAD)
                        .is_some_and(|maximum| envelope.len() <= maximum)
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
            let cipher = Aes256Gcm::new_from_slice(&*key).map_err(|_| {
                anyhow::anyhow!("{}", obfstr::obfstr!("Artifact cipher unavailable"))
            })?;
            let nonce: [u8; 12] = envelope[38..50].try_into()?;
            let tag_at = envelope.len() - 16;
            let tag: [u8; 16] = envelope[tag_at..].try_into()?;
            cipher
                .decrypt_in_place_detached(
                    Nonce::from_slice(&nonce),
                    &info,
                    &mut envelope[50..tag_at],
                    Tag::from_slice(&tag),
                )
                .map_err(|_| {
                    anyhow::anyhow!(
                        "{}",
                        obfstr::obfstr!("Encrypted artifact authentication failed")
                    )
                })?;
            // Reuse the transport allocation. Wipe the old tail before truncating,
            // so the framing offset leaves no second plaintext suffix in capacity.
            let plain_length = tag_at - 50;
            envelope.copy_within(50..tag_at, 0);
            envelope[plain_length..].zeroize();
            envelope.truncate(plain_length);
            Ok(())
        })();
        if result.is_err() {
            envelope.zeroize();
        }
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aes_gcm::aead::{Aead, Payload};
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
            *request
                .decrypt(envelope.clone(), path, token, 1024)
                .unwrap(),
            plaintext
        );
        assert!(
            RequestKey::new()
                .unwrap()
                .decrypt(envelope, path, token, 1024)
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
            &**request.decrypt(envelope.clone(), path, token, 100).unwrap(),
            b"PK protected fixture jar"
        );
        assert!(
            request
                .decrypt(
                    envelope.clone(),
                    "/v1/beta/artifacts/test/payload-a",
                    token,
                    100
                )
                .is_err()
        );
        assert!(
            request
                .decrypt(envelope.clone(), path, "another.grant", 100)
                .is_err()
        );
        assert!(
            RequestKey::new()
                .unwrap()
                .decrypt(envelope.clone(), path, token, 100)
                .is_err()
        );
        for index in [0, 6, 38, envelope.len() - 1] {
            let mut bad = envelope.clone();
            bad[index] ^= 1;
            assert!(request.decrypt(bad, path, token, 100).is_err());
        }
        assert!(request.decrypt(envelope, path, token, 2).is_err());
        assert!(
            request
                .decrypt(
                    b"plaintext is not an encrypted artifact".to_vec(),
                    path,
                    token,
                    100
                )
                .is_err()
        );
    }

    #[test]
    fn authenticated_plaintext_reuses_transport_allocation_without_second_payload_copy() {
        let request = RequestKey::new().unwrap();
        for length in [1, 49, 50, 51, 8192, 1024 * 1024] {
            let plain = vec![0x67; length];
            let envelope = sealed(&request, "/fixture", "fixture.grant", &plain);
            let address = envelope.as_ptr();
            let capacity = envelope.capacity();
            let opened = request
                .decrypt(envelope, "/fixture", "fixture.grant", length)
                .unwrap();
            assert_eq!(&**opened, plain);
            assert_eq!(
                opened.as_ptr(),
                address,
                "allocated another artifact buffer"
            );
            assert_eq!(opened.capacity(), capacity);
            assert_eq!(opened.len(), length);
        }
    }

    #[test]
    fn rejected_owned_envelopes_are_cleared_for_framing_truncation_key_tag_and_bounds() {
        let request = RequestKey::new().unwrap();
        let envelope = sealed(
            &request,
            "/fixture",
            "fixture.grant",
            b"owned synthetic payload",
        );
        let mut mutations = Vec::new();
        for length in 0..envelope.len() {
            mutations.push(envelope[..length].to_vec());
        }
        for index in [0, 5, 6, 37, 38, 49, 50, envelope.len() - 1] {
            let mut changed = envelope.clone();
            changed[index] ^= 1;
            mutations.push(changed);
        }
        let mut low_order = envelope.clone();
        low_order[6..38].fill(0);
        mutations.push(low_order);
        let mut appended = envelope.clone();
        appended.push(0);
        mutations.push(appended);
        for mutation in mutations {
            let mut owned = Zeroizing::new(mutation);
            assert!(
                request
                    .decrypt_owned(&mut owned, "/fixture", "fixture.grant", 100)
                    .is_err()
            );
            assert!(
                owned.is_empty(),
                "failed envelope retained accessible bytes"
            );
        }
        for (path, token, limit) in [
            ("/wrong", "fixture.grant", 100),
            ("/fixture", "wrong.grant", 100),
            ("/fixture", "fixture.grant", 0),
            ("/fixture", "fixture.grant", 1),
            ("/fixture", "fixture.grant", usize::MAX),
        ] {
            let mut owned = Zeroizing::new(envelope.clone());
            assert!(
                request
                    .decrypt_owned(&mut owned, path, token, limit)
                    .is_err()
            );
            assert!(owned.is_empty());
        }
    }
}
