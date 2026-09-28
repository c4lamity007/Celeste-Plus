use super::*;
use ed25519_dalek::{Signer, SigningKey};
use serde_json::{Value, json};
use std::io::{self, Cursor};

fn fixture() -> Value {
    serde_json::from_str(include_str!("fixtures/server-manifest.json")).unwrap()
}

fn key() -> SigningKey {
    let bytes: [u8; 32] = URL_SAFE_NO_PAD
        .decode(fixture()["public_test_seed"].as_str().unwrap())
        .unwrap()
        .try_into()
        .unwrap();
    SigningKey::from_bytes(&bytes)
}

fn header() -> Value {
    json!({"alg":"EdDSA","kid":"celeste-release-1","typ":"CST"})
}

fn signed_raw(metadata: &str, claims: &str) -> Vec<u8> {
    let message = format!(
        "{}.{}",
        URL_SAFE_NO_PAD.encode(metadata),
        URL_SAFE_NO_PAD.encode(claims)
    );
    let token = format!(
        "{message}.{}",
        URL_SAFE_NO_PAD.encode(key().sign(message.as_bytes()).to_bytes())
    );
    serde_json::to_vec(&json!({"manifest":token})).unwrap()
}

fn signed(metadata: &Value, claims: &Value) -> Vec<u8> {
    signed_raw(&metadata.to_string(), &claims.to_string())
}

fn checked(body: &[u8]) -> Result<Manifest> {
    let f = fixture();
    verify(
        body,
        f["nonce"].as_str().unwrap(),
        &key().verifying_key(),
        f["now"].as_u64().unwrap(),
    )
}

fn valid() -> Manifest {
    checked(&serde_json::to_vec(&fixture()["response"]).unwrap()).unwrap()
}

#[test]
fn actual_backend_router_manifest_and_payload_match() {
    let f = fixture();
    let manifest = valid();
    let public: [u8; 32] = URL_SAFE_NO_PAD
        .decode(f["public_key"].as_str().unwrap())
        .unwrap()
        .try_into()
        .unwrap();
    assert_eq!(key().verifying_key().to_bytes(), public);
    assert_eq!(manifest.url, f["claims"]["url"].as_str().unwrap());
    let payload = f["payload_utf8"].as_str().unwrap().as_bytes();
    let mut output = Vec::new();
    copy_verified(
        payload,
        &mut output,
        &manifest,
        &AtomicBool::new(false),
        |_, _| {},
    )
    .unwrap();
    assert_eq!(output, payload);
}

#[test]
fn wrong_signature_key_and_payload_tampering_are_rejected() {
    let f = fixture();
    let body = serde_json::to_vec(&f["response"]).unwrap();
    assert!(
        verify(
            &body,
            f["nonce"].as_str().unwrap(),
            &SigningKey::from_bytes(&[99; 32]).verifying_key(),
            2_000_000_000
        )
        .is_err()
    );
    let token = f["response"]["manifest"].as_str().unwrap();
    let mut parts: Vec<_> = token.split('.').map(str::to_owned).collect();
    let mut claims = f["claims"].clone();
    claims["size"] = json!(1);
    parts[1] = URL_SAFE_NO_PAD.encode(claims.to_string());
    assert!(checked(&serde_json::to_vec(&json!({"manifest":parts.join(".")})).unwrap()).is_err());
}

#[test]
fn token_kind_context_and_request_cannot_be_substituted() {
    let f = fixture();
    let claims = f["claims"].clone();
    for (name, value) in [("alg", "none"), ("kid", "attacker-key"), ("typ", "JWT")] {
        let mut h = header();
        h[name] = json!(value);
        assert!(checked(&signed(&h, &claims)).is_err(), "{name}");
    }
    for (name, value) in [
        ("aud", "celeste-native-manifest"),
        ("product", "celeste"),
        ("platform", "linux-x64"),
        ("request_nonce", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
    ] {
        let mut c = claims.clone();
        c[name] = json!(value);
        assert!(checked(&signed(&header(), &c)).is_err(), "{name}");
    }
}

#[test]
fn exact_schema_rejects_duplicate_unknown_and_missing_fields() {
    let f = fixture();
    let claims = f["claims"].clone();
    let mut extra = claims.clone();
    extra["offline"] = json!("allowed");
    assert!(checked(&signed(&header(), &extra)).is_err());
    let mut missing = claims.clone();
    missing.as_object_mut().unwrap().remove("sha256");
    assert!(checked(&signed(&header(), &missing)).is_err());
    let duplicate = format!("{{\"size\":1,{}", &claims.to_string()[1..]);
    assert!(checked(&signed_raw(&header().to_string(), &duplicate)).is_err());
    assert!(
        checked(&signed_raw(
            r#"{"alg":"EdDSA","alg":"EdDSA","kid":"celeste-release-1","typ":"CST"}"#,
            &claims.to_string()
        ))
        .is_err()
    );
    let token = serde_json::to_string(&f["response"]["manifest"]).unwrap();
    assert!(checked(format!("{{\"manifest\":{token},\"manifest\":{token}}}").as_bytes()).is_err());
    assert!(checked(format!("{{\"manifest\":{token},\"extra\":1}}").as_bytes()).is_err());
}

#[test]
fn malformed_encoding_and_response_bounds_are_rejected() {
    let f = fixture();
    let body = serde_json::to_vec(&f["response"]).unwrap();
    for nonce in [
        "",
        "A",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "+AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    ] {
        assert!(verify(&body, nonce, &key().verifying_key(), 2_000_000_000).is_err());
    }
    let token = f["response"]["manifest"].as_str().unwrap();
    for invalid in [
        format!("{token}="),
        format!("{token}.extra"),
        "a.b.c".to_owned(),
        String::new(),
    ] {
        assert!(checked(&serde_json::to_vec(&json!({"manifest":invalid})).unwrap()).is_err());
    }
    let mut oversized = body.clone();
    oversized.resize(MAX_RESPONSE + 1, b' ');
    assert!(checked(&oversized).is_err());
    assert!(checked(&[body, b" {}".to_vec()].concat()).is_err());
}

#[test]
fn expired_future_and_excessive_lifetime_approval_are_rejected() {
    let c = fixture()["claims"].clone();
    for (iat, nbf, exp) in [
        (1_999_999_880, 1_999_999_880, 2_000_000_000),
        (2_000_000_006, 2_000_000_006, 2_000_000_126),
        (2_000_000_000, 2_000_000_001, 2_000_000_120),
        (2_000_000_000, 2_000_000_000, 2_000_000_121),
        (2_000_000_000, 2_000_000_000, 2_000_000_000),
    ] {
        let mut v = c.clone();
        v["iat"] = json!(iat);
        v["nbf"] = json!(nbf);
        v["exp"] = json!(exp);
        assert!(checked(&signed(&header(), &v)).is_err());
    }
    let mut future = c;
    future["iat"] = json!(2_000_000_005u64);
    future["nbf"] = future["iat"].clone();
    future["exp"] = json!(2_000_000_125u64);
    assert!(checked(&signed(&header(), &future)).is_ok());
}

#[test]
fn numeric_types_and_signed_download_bounds_are_enforced() {
    let c = fixture()["claims"].clone();
    for field in ["size", "sequence", "iat", "nbf", "exp"] {
        for value in [json!(-1), json!(1.5), json!(true), Value::Null, json!("1")] {
            let mut v = c.clone();
            v[field] = value;
            assert!(checked(&signed(&header(), &v)).is_err(), "{field}");
        }
    }
    for (field, value) in [
        ("size", 0),
        ("size", MAX_DOWNLOAD + 1),
        ("sequence", 0),
        ("sequence", i64::MAX as u64 + 1),
    ] {
        let mut v = c.clone();
        v[field] = json!(value);
        assert!(checked(&signed(&header(), &v)).is_err());
    }
    let mut edge = c;
    edge["size"] = json!(MAX_DOWNLOAD);
    edge["sequence"] = json!(i64::MAX);
    assert!(checked(&signed(&header(), &edge)).is_ok());
}

#[test]
fn checksum_release_name_and_every_url_component_are_fixed() {
    let c = fixture()["claims"].clone();
    let url = c["url"].as_str().unwrap();
    for bad in [
        url.replace("https:", "http:"),
        url.replace(".fly.dev", ".fly.dev.attacker.invalid"),
        url.replace("https://", "https://user@"),
        url.replace(".fly.dev/", ".fly.dev:443/"),
        format!("{url}?redirect=x"),
        format!("{url}#fragment"),
        url.replace("Celeste+.exe", "Celeste%2B.exe"),
        url.replace("windows-x64", "windows-arm64"),
        url.replace(c["sha256"].as_str().unwrap(), &"0".repeat(64)),
    ] {
        let mut v = c.clone();
        v["url"] = json!(bad);
        assert!(checked(&signed(&header(), &v)).is_err());
    }
    for bad in [
        "",
        "_hidden",
        "../x",
        "invalid version",
        "é",
        &"a".repeat(81),
    ] {
        let mut v = c.clone();
        v["release_id"] = json!(bad);
        assert!(checked(&signed(&header(), &v)).is_err());
    }
    for bad in ["a".repeat(63), "A".repeat(64), "g".repeat(64)] {
        let mut v = c.clone();
        v["sha256"] = json!(bad);
        assert!(checked(&signed(&header(), &v)).is_err());
    }
}

fn payload_manifest(data: &[u8]) -> Manifest {
    let mut m = valid();
    m.size = data.len() as u64;
    m.sha256 = format!("{:x}", Sha256::digest(data));
    m
}

#[test]
fn streamed_binary_hash_size_and_progress_are_exact() {
    let data = vec![0x5a; 140_003];
    let m = payload_manifest(&data);
    let mut output = Vec::new();
    let mut updates = Vec::new();
    copy_verified(
        Cursor::new(&data),
        &mut output,
        &m,
        &AtomicBool::new(false),
        |n, total| updates.push((n, total)),
    )
    .unwrap();
    assert_eq!(output, data);
    assert_eq!(updates.last(), Some(&(140_003, 140_003)));
    assert!(updates.len() >= 3 && updates.windows(2).all(|p| p[0].0 < p[1].0));
}

#[test]
fn truncated_overlong_and_same_size_corruption_never_succeed() {
    let original = b"verified download bytes";
    let m = payload_manifest(original);
    let mut altered = original.to_vec();
    altered[0] ^= 1;
    for data in [
        original[..original.len() - 1].to_vec(),
        [original.as_slice(), b"extra"].concat(),
        altered,
    ] {
        assert!(
            copy_verified(
                data.as_slice(),
                Vec::new(),
                &m,
                &AtomicBool::new(false),
                |_, _| {}
            )
            .is_err()
        );
    }
}

#[test]
fn cancellation_before_or_during_copy_prevents_success() {
    let data = vec![7; 140_000];
    let m = payload_manifest(&data);
    let cancel = AtomicBool::new(true);
    let mut output = Vec::new();
    assert!(
        copy_verified(data.as_slice(), &mut output, &m, &cancel, |_, _| panic!(
            "must not progress"
        ))
        .is_err()
    );
    assert!(output.is_empty());
    cancel.store(false, Ordering::Release);
    assert!(
        copy_verified(data.as_slice(), &mut output, &m, &cancel, |_, _| cancel
            .store(true, Ordering::Release))
        .is_err()
    );
    assert!(!output.is_empty() && output.len() < data.len());
}

#[test]
fn read_write_and_flush_errors_prevent_success() {
    struct BrokenRead;
    impl Read for BrokenRead {
        fn read(&mut self, _: &mut [u8]) -> io::Result<usize> {
            Err(io::Error::other("fixture read failure"))
        }
    }
    struct BrokenWrite {
        flush_only: bool,
    }
    impl Write for BrokenWrite {
        fn write(&mut self, b: &[u8]) -> io::Result<usize> {
            if self.flush_only {
                Ok(b.len())
            } else {
                Err(io::Error::other("fixture disk full"))
            }
        }
        fn flush(&mut self) -> io::Result<()> {
            Err(io::Error::other("fixture flush failure"))
        }
    }
    let bytes = b"fixture";
    let m = payload_manifest(bytes);
    let cancel = AtomicBool::new(false);
    assert!(copy_verified(BrokenRead, Vec::new(), &m, &cancel, |_, _| {}).is_err());
    assert!(
        copy_verified(
            bytes.as_slice(),
            BrokenWrite { flush_only: false },
            &m,
            &cancel,
            |_, _| {}
        )
        .is_err()
    );
    assert!(
        copy_verified(
            bytes.as_slice(),
            BrokenWrite { flush_only: true },
            &m,
            &cancel,
            |_, _| {}
        )
        .is_err()
    );
}
