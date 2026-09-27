use super::*;
use aes_gcm::aead::{Aead, Payload};

#[test]
fn credential_secret_owners_keep_the_existing_serialized_format() {
    let fixture = br#"{"deviceId":"public-fixture-device","uid":7,"signingPrivate":"fixture-signing","encryptionPrivate":"fixture-encryption","tokenSeed":"fixture-token","certificate":"fixture-certificate","gameUuid":"fixture-uuid","gameName":"fixture-name"}"#;
    let credential: InstallCredential = serde_json::from_slice(fixture).unwrap();
    fn guarded(_: &Zeroizing<String>) {}
    guarded(&credential.signing_private);
    guarded(&credential.encryption_private);
    guarded(&credential.token_seed);
    let cloned = credential.clone();
    assert_eq!(serde_json::to_vec(&credential).unwrap(), fixture);
    drop(credential);
    assert_eq!(&*cloned.signing_private, "fixture-signing");
    assert_eq!(&*cloned.encryption_private, "fixture-encryption");
    assert_eq!(&*cloned.token_seed, "fixture-token");
    let malformed = br#"{"signingPrivate":"fixture-signing","encryptionPrivate":"fixture-encryption","tokenSeed":"fixture-token","uid":"not-a-number"}"#;
    assert!(serde_json::from_slice::<InstallCredential>(malformed).is_err());
}

fn token_fixture(plain: &[u8]) -> (Value, StaticSecret, Vec<u8>) {
    let recipient = StaticSecret::from([0x31; 32]);
    let ephemeral = StaticSecret::from([0x73; 32]);
    let recipient_public = X25519PublicKey::from(&recipient);
    let salt = [0x52; 32];
    let nonce = [0x16; 12];
    let message = serde_json::to_vec(&json!({"nonce": encode(&salt), "fixture": true})).unwrap();
    let shared = ephemeral.diffie_hellman(&recipient_public);
    let hkdf = Hkdf::<Sha256>::new(Some(&salt), shared.as_bytes());
    let mut wrapping = Zeroizing::new([0u8; 32]);
    hkdf.expand(TOKEN_INFO, &mut wrapping[..]).unwrap();
    let cipher = Aes256Gcm::new_from_slice(&wrapping[..]).unwrap();
    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: plain,
                aad: &message,
            },
        )
        .unwrap();
    (
        json!({
            "ephemeral_key": encode(X25519PublicKey::from(&ephemeral).as_bytes()),
            "nonce": encode(&nonce), "ciphertext": encode(&ciphertext),
        }),
        recipient,
        message,
    )
}

#[test]
fn token_envelope_returns_a_guarded_seed_and_rejects_tampering() {
    let (envelope, private, message) = token_fixture(&[0x29; 32]);
    let seed: Zeroizing<[u8; 32]> = decrypt_token(&envelope, &private, &message).unwrap();
    assert_eq!(&seed[..], &[0x29; 32]);
    let mut tampered = envelope.clone();
    let mut ciphertext = decode(tampered["ciphertext"].as_str().unwrap()).unwrap();
    ciphertext[0] ^= 1;
    tampered["ciphertext"] = json!(encode(&ciphertext));
    assert!(decrypt_token(&tampered, &private, &message).is_err());
    let (wrong_length, private, message) = token_fixture(&[0x29; 31]);
    assert!(decrypt_token(&wrong_length, &private, &message).is_err());
}

#[test]
fn private_key_decoding_preserves_raw_and_pkcs8_forms_and_rejects_invalid_input() {
    use ed25519_dalek::pkcs8::EncodePrivateKey;
    let fixture = SigningKey::from_bytes(&[0x37; 32]);
    let raw = Zeroizing::new(encode(fixture.as_bytes()));
    let der = fixture.to_pkcs8_der().unwrap();
    let encoded_der = Zeroizing::new(encode(der.as_bytes()));
    assert_eq!(
        signing_key(&raw).unwrap().verifying_key(),
        fixture.verifying_key()
    );
    assert_eq!(
        signing_key(&encoded_der).unwrap().verifying_key(),
        fixture.verifying_key()
    );
    assert!(signing_key("not base64url!").is_err());
    assert!(signing_key(&encode(&[0x37; 31])).is_err());
}
