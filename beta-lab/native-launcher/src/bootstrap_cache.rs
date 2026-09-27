//! Encrypted durable Bootstrap A cache and strictly owned plaintext materialization.
//! No recursive removal. Unknown files, links, ambiguous process state are preserved.
use aes_gcm::{
    Aes256Gcm, KeyInit, Nonce,
    aead::{Aead, Payload},
};
use anyhow::{Result, anyhow, ensure};
use rand::{RngCore, rngs::OsRng};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};
use zeroize::Zeroizing;

const MAX: usize = 64 * 1024 * 1024;
const MAGIC: &[u8] = b"CBC2";
const PREFIX: &str = ".owned-bootstrap-";

fn plain_file(path: &Path) -> Result<fs::Metadata> {
    let metadata = fs::symlink_metadata(path)?;
    ensure!(
        metadata.is_file() && !metadata.file_type().is_symlink(),
        "Cache entry must be a regular file"
    );
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        ensure!(
            metadata.file_attributes() & 0x400 == 0,
            "Cache entry must not be a reparse point"
        );
    }
    Ok(metadata)
}

fn owned_directory(path: &Path) -> Result<()> {
    ensure!(path.is_absolute(), "Cache directory must be absolute");
    let metadata = fs::symlink_metadata(path)?;
    ensure!(
        metadata.is_dir() && !metadata.file_type().is_symlink(),
        "Cache directory must not be a link"
    );
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        ensure!(
            metadata.file_attributes() & 0x400 == 0,
            "Cache directory must not be a reparse point"
        );
    }
    Ok(())
}

fn checked_read(path: &Path, limit: usize) -> Result<Vec<u8>> {
    ensure!(
        plain_file(path)?.len() <= limit as u64,
        "Cache entry exceeds size bound"
    );
    let mut bytes = Vec::new();
    fs::File::open(path)?
        .take((limit + 1) as u64)
        .read_to_end(&mut bytes)?;
    ensure!(bytes.len() <= limit, "Cache entry changed beyond bounds");
    Ok(bytes)
}

fn verify(bytes: &[u8], digest: &str, size: usize) -> Result<()> {
    ensure!(
        size > 0
            && size <= MAX
            && bytes.len() == size
            && format!("{:x}", Sha256::digest(bytes)) == digest,
        "Bootstrap cache does not match its signed artifact"
    );
    Ok(())
}

fn sealed(
    bytes: &[u8],
    digest: &str,
    key: &[u8; 32],
    nonce: &[u8; 12],
    protected_key: &[u8],
) -> Result<Vec<u8>> {
    ensure!(
        !protected_key.is_empty() && protected_key.len() <= 65536,
        "Invalid protected cache key"
    );
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| anyhow!("Invalid cache key"))?;
    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: bytes,
                aad: digest.as_bytes(),
            },
        )
        .map_err(|_| anyhow!("Cache encryption failed"))?;
    Ok([
        MAGIC,
        &(protected_key.len() as u32).to_le_bytes(),
        protected_key,
        nonce,
        &ciphertext,
    ]
    .concat())
}

fn opened(envelope: &[u8], digest: &str, key: &[u8; 32]) -> Result<Zeroizing<Vec<u8>>> {
    let (_, payload) = parts(envelope)?;
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| anyhow!("Invalid cache key"))?;
    let bytes = cipher
        .decrypt(
            Nonce::from_slice(&payload[..12]),
            Payload {
                msg: &payload[12..],
                aad: digest.as_bytes(),
            },
        )
        .map_err(|_| anyhow!("Cache authentication failed"))?;
    Ok(Zeroizing::new(bytes))
}

fn parts(bytes: &[u8]) -> Result<(&[u8], &[u8])> {
    ensure!(
        bytes.len() >= 8 + 1 + 12 + 16 && bytes.len() <= MAX + 65572 && bytes.starts_with(MAGIC),
        "Invalid encrypted cache"
    );
    let length = u32::from_le_bytes(bytes[4..8].try_into()?) as usize;
    ensure!(
        (1..=65536).contains(&length) && 8 + length + 28 < bytes.len(),
        "Invalid encrypted cache key length"
    );
    Ok((&bytes[8..8 + length], &bytes[8 + length..]))
}

pub(crate) fn load(path: &Path, digest: &str, size: usize) -> Result<Option<Zeroizing<Vec<u8>>>> {
    match fs::symlink_metadata(path) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        result => {
            result?;
        }
    }
    owned_directory(
        path.parent()
            .ok_or_else(|| anyhow!("Missing cache parent"))?,
    )?;
    let bytes = checked_read(path, MAX + 65572)?;
    let (protected, _) = parts(&bytes)?;
    let key = Zeroizing::new(crate::credential_protection::unprotect(protected)?);
    let key: &[u8; 32] = key
        .as_slice()
        .try_into()
        .map_err(|_| anyhow!("Invalid account-protected cache key"))?;
    let plain = opened(&bytes, digest, key)?;
    verify(&plain, digest, size)?;
    Ok(Some(plain))
}

pub(crate) fn store(path: &Path, digest: &str, bytes: &[u8]) -> Result<()> {
    verify(bytes, digest, bytes.len())?;
    let parent = path
        .parent()
        .ok_or_else(|| anyhow!("Missing cache parent"))?;
    let grandparent = parent
        .parent()
        .ok_or_else(|| anyhow!("Missing cache root"))?;
    owned_directory(grandparent)?;
    match fs::create_dir(parent) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
        Err(error) => return Err(error.into()),
    }
    owned_directory(parent)?;
    if load(path, digest, bytes.len())?.is_some() {
        return Ok(());
    }
    let mut key = Zeroizing::new([0u8; 32]);
    let mut nonce = [0u8; 12];
    OsRng.try_fill_bytes(&mut *key)?;
    OsRng.try_fill_bytes(&mut nonce)?;
    let protected = crate::credential_protection::protect(&*key)?;
    let envelope = sealed(bytes, digest, &key, &nonce, &protected)?;
    let mut random = [0u8; 24];
    OsRng.try_fill_bytes(&mut random)?;
    let stage = parent.join(format!(
        ".encrypted-stage-{}",
        random
            .iter()
            .map(|v| format!("{v:02x}"))
            .collect::<String>()
    ));
    let mut output = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&stage)?;
    let result = (|| -> Result<()> {
        output.write_all(&envelope)?;
        output.sync_all()?;
        drop(output);
        // Hard-link publication never replaces another writer's or user's file.
        match fs::hard_link(&stage, path) {
            Ok(()) => Ok(()),
            Err(_) if load(path, digest, bytes.len())?.is_some() => Ok(()),
            Err(error) => Err(error.into()),
        }
    })();
    // Only the create_new-reserved encrypted staging file belongs to this call.
    let _ = fs::remove_file(stage);
    result
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Owner {
    schema: u32,
    product: String,
    root: String,
    directory: String,
    sha256: String,
    size: usize,
    pid: u32,
    started: u64,
}

pub(crate) fn materialize(root: &Path, digest: &str, bytes: &[u8]) -> Result<PathBuf> {
    owned_directory(root)?;
    verify(bytes, digest, bytes.len())?;
    let root = root.canonicalize()?;
    let identity = root
        .file_name()
        .and_then(|value| value.to_str())
        .and_then(|value| value.split_once('-'))
        .ok_or_else(|| anyhow!("Invalid owned process cache"))?;
    let pid = identity.0.parse::<u32>()?;
    let started = identity.1.parse::<u64>()?;
    ensure!(pid > 0 && started > 0, "Invalid process cache identity");
    let mut random = [0u8; 24];
    OsRng.try_fill_bytes(&mut random)?;
    let name = format!(
        "{PREFIX}{}",
        random
            .iter()
            .map(|v| format!("{v:02x}"))
            .collect::<String>()
    );
    let directory = root.join(&name);
    fs::create_dir(&directory)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&directory, fs::Permissions::from_mode(0o700))?;
    }
    let owner = Owner {
        schema: 2,
        product: "celeste_plus".into(),
        root: root.to_string_lossy().into_owned(),
        directory: name,
        sha256: digest.into(),
        size: bytes.len(),
        pid,
        started,
    };
    let receipt = crate::credential_protection::protect(&serde_json::to_vec(&owner)?)?;
    // The authenticated ownership record exists before any plaintext materialization.
    crate::atomic_store::atomic_write(&directory.join("owner.cst"), &receipt)?;
    let path = directory.join("bootstrap-a.jar");
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut output = options.open(&path)?;
    output.write_all(bytes)?;
    output.sync_all()?;
    drop(output);
    verify(&checked_read(&path, MAX)?, digest, bytes.len())?;
    Ok(path)
}

fn read_owner(root: &Path, directory: &Path) -> Result<Owner> {
    owned_directory(root)?;
    owned_directory(directory)?;
    let encrypted = checked_read(&directory.join("owner.cst"), 65536)?;
    let plain = Zeroizing::new(crate::credential_protection::unprotect(&encrypted)?);
    let owner: Owner = serde_json::from_slice(&plain)?;
    ensure!(
        owner.schema == 2
            && owner.product == "celeste_plus"
            && owner.root == root.canonicalize()?.to_string_lossy()
            && directory.file_name().and_then(|v| v.to_str()) == Some(&owner.directory)
            && owner.directory.starts_with(PREFIX)
            && owner.directory.len() == PREFIX.len() + 48
            && owner.directory[PREFIX.len()..]
                .bytes()
                .all(|v| v.is_ascii_hexdigit())
            && root.file_name().and_then(|v| v.to_str())
                == Some(&format!("{}-{}", owner.pid, owner.started)),
        "Invalid cache ownership record"
    );
    Ok(owner)
}

fn clean_directory(root: &Path, directory: &Path, gone: impl Fn(u32, u64) -> bool) -> Result<()> {
    let owner = read_owner(root, directory)?;
    ensure!(
        gone(owner.pid, owner.started),
        "Owned process is active or its state is uncertain"
    );
    let jar = directory.join("bootstrap-a.jar");
    match fs::symlink_metadata(&jar) {
        Ok(_) => {
            verify(&checked_read(&jar, MAX)?, &owner.sha256, owner.size)?;
            fs::remove_file(&jar)?;
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    fs::remove_file(directory.join("owner.cst"))?;
    // An unexpected user file keeps the directory alive, untouched.
    let _ = fs::remove_dir(directory);
    Ok(())
}

pub(crate) fn cleanup_stale() -> Result<()> {
    let base = crate::user_profile::current_local_app_data()?
        .join("Celeste")
        .join("Plus");
    if !base.exists() {
        return Ok(());
    }
    owned_directory(&base)?;
    for root in fs::read_dir(&base)?
        .take(4096)
        .flatten()
        .map(|entry| entry.path())
    {
        if owned_directory(&root).is_err() {
            continue;
        }
        let Ok(entries) = fs::read_dir(&root) else {
            continue;
        };
        for directory in entries.take(4096).flatten().map(|entry| entry.path()) {
            if directory
                .file_name()
                .and_then(|v| v.to_str())
                .is_some_and(|name| name.starts_with(PREFIX))
            {
                let _ = clean_directory(
                    &root,
                    &directory,
                    crate::platform::process_is_definitely_gone,
                );
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(windows)]
    struct Fixture(PathBuf);
    #[cfg(windows)]
    impl Fixture {
        fn new() -> Self {
            let mut random = [0u8; 24];
            OsRng.fill_bytes(&mut random);
            let path = std::env::temp_dir().join(format!(
                "celeste-owned-cache-test-{}",
                random
                    .iter()
                    .map(|b| format!("{b:02x}"))
                    .collect::<String>()
            ));
            fs::create_dir(&path).unwrap();
            Self(path.canonicalize().unwrap())
        }
        fn root(&self) -> PathBuf {
            let root = self.0.join("123-456");
            fs::create_dir(&root).unwrap();
            root
        }
    }
    #[cfg(windows)]
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    #[cfg(windows)]
    #[test]
    fn encrypted_publication_is_concurrent_safe_and_never_clobbers_unknown_files() {
        let fixture = Fixture::new();
        let root = fixture.root();
        let plain = b"PK concurrent encrypted fixture";
        let digest = format!("{:x}", Sha256::digest(plain));
        let path = root.join(&digest).join("bootstrap-a.cst");
        std::thread::scope(|scope| {
            let first = scope.spawn(|| store(&path, &digest, plain));
            let second = scope.spawn(|| store(&path, &digest, plain));
            first.join().unwrap().unwrap();
            second.join().unwrap().unwrap();
        });
        assert_eq!(
            &**load(&path, &digest, plain.len()).unwrap().unwrap(),
            plain
        );
        assert_eq!(fs::read_dir(path.parent().unwrap()).unwrap().count(), 1);
        let original = fs::read(&path).unwrap();
        store(&path, &digest, plain).unwrap();
        assert_eq!(fs::read(&path).unwrap(), original);
        fs::write(&path, b"unrelated existing file").unwrap();
        assert!(store(&path, &digest, plain).is_err());
        assert_eq!(fs::read(&path).unwrap(), b"unrelated existing file");
    }
    #[cfg(windows)]
    #[test]
    fn account_encrypted_cache_round_trip_and_corruption_are_checked() {
        let fixture = Fixture::new();
        let root = fixture.root();
        let plain = b"PK genuine fixture";
        let digest = format!("{:x}", Sha256::digest(plain));
        let path = root.join(&digest).join("bootstrap-a.cst");
        store(&path, &digest, plain).unwrap();
        let envelope = fs::read(&path).unwrap();
        assert!(!envelope.windows(plain.len()).any(|part| part == plain));
        assert_eq!(
            &**load(&path, &digest, plain.len()).unwrap().unwrap(),
            plain
        );
        assert!(load(&path, &"f".repeat(64), plain.len()).is_err());
        assert!(load(&path, &digest, plain.len() + 1).is_err());
        let mut corrupted = envelope;
        *corrupted.last_mut().unwrap() ^= 1;
        fs::write(&path, corrupted).unwrap();
        assert!(load(&path, &digest, plain.len()).is_err());
    }
    #[cfg(windows)]
    #[test]
    fn authenticated_cleanup_keeps_live_foreign_modified_and_unknown_files() {
        let fixture = Fixture::new();
        let root = fixture.root();
        let plain = b"PK owned fixture";
        let digest = format!("{:x}", Sha256::digest(plain));
        let jar = materialize(&root, &digest, plain).unwrap();
        let directory = jar.parent().unwrap();
        assert!(clean_directory(&root, directory, |_, _| false).is_err());
        assert!(jar.exists());
        fs::write(directory.join("user-notes.txt"), b"keep").unwrap();
        fs::write(&jar, b"user changed this file").unwrap();
        assert!(clean_directory(&root, directory, |_, _| true).is_err());
        assert!(jar.exists());
        fs::write(&jar, plain).unwrap();
        clean_directory(&root, directory, |pid, start| pid == 123 && start == 456).unwrap();
        assert!(!jar.exists());
        assert!(!directory.join("owner.cst").exists());
        assert_eq!(fs::read(directory.join("user-notes.txt")).unwrap(), b"keep");
        let jar = materialize(&root, &digest, plain).unwrap();
        let directory = jar.parent().unwrap();
        fs::write(directory.join("owner.cst"), b"forged ownership").unwrap();
        assert!(clean_directory(&root, directory, |_, _| true).is_err());
        assert!(jar.exists());
        let jar = materialize(&root, &digest, plain).unwrap();
        let directory = jar.parent().unwrap();
        fs::remove_file(&jar).unwrap();
        clean_directory(&root, directory, |_, _| true).unwrap();
        assert!(!directory.exists());
    }
    #[cfg(windows)]
    #[test]
    fn copied_receipt_cannot_authorize_another_directory() {
        let fixture = Fixture::new();
        let root = fixture.root();
        let plain = b"PK owned fixture";
        let digest = format!("{:x}", Sha256::digest(plain));
        let first = materialize(&root, &digest, plain).unwrap();
        let second = materialize(&root, &digest, plain).unwrap();
        fs::copy(
            first.parent().unwrap().join("owner.cst"),
            second.parent().unwrap().join("owner.cst"),
        )
        .unwrap();
        assert!(clean_directory(&root, second.parent().unwrap(), |_, _| true).is_err());
        assert!(second.exists());
        clean_directory(&root, first.parent().unwrap(), |_, _| true).unwrap();
        assert!(!first.parent().unwrap().exists());
    }
    #[test]
    fn durable_ciphertext_requires_original_key_digest_and_tag() {
        let plain = b"PK private bootstrap content";
        let digest = format!("{:x}", Sha256::digest(plain));
        let envelope =
            sealed(plain, &digest, &[7; 32], &[9; 12], b"protected-key-fixture").unwrap();
        assert!(!envelope.windows(plain.len()).any(|v| v == plain));
        assert_eq!(&**opened(&envelope, &digest, &[7; 32]).unwrap(), plain);
        assert!(opened(&envelope, &digest, &[8; 32]).is_err());
        assert!(opened(&envelope, &"f".repeat(64), &[7; 32]).is_err());
        let mut changed = envelope.clone();
        *changed.last_mut().unwrap() ^= 1;
        assert!(opened(&changed, &digest, &[7; 32]).is_err());
        assert!(opened(plain, &digest, &[7; 32]).is_err());
    }
}
