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

#[cfg(windows)]
#[path = "bootstrap_cleanup_windows.rs"]
mod watcher;

const MAX: usize = 64 * 1024 * 1024;
const MAGIC: &[u8] = b"CBC2";
fn prefix() -> &'static str {
    static VALUE: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    VALUE.get_or_init(|| obfstr::obfstr!(".owned-bootstrap-").to_owned())
}

fn plain_file(path: &Path) -> Result<fs::Metadata> {
    let metadata = fs::symlink_metadata(path)?;
    ensure!(
        metadata.is_file() && !metadata.file_type().is_symlink(),
        obfstr::obfstr!("Cache entry must be a regular file").to_owned()
    );
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        ensure!(
            metadata.file_attributes() & 0x400 == 0,
            obfstr::obfstr!("Cache entry must not be a reparse point").to_owned()
        );
    }
    Ok(metadata)
}

fn owned_directory(path: &Path) -> Result<()> {
    ensure!(
        path.is_absolute(),
        obfstr::obfstr!("Cache directory must be absolute").to_owned()
    );
    let metadata = fs::symlink_metadata(path)?;
    ensure!(
        metadata.is_dir() && !metadata.file_type().is_symlink(),
        obfstr::obfstr!("Cache directory must not be a link").to_owned()
    );
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        ensure!(
            metadata.file_attributes() & 0x400 == 0,
            obfstr::obfstr!("Cache directory must not be a reparse point").to_owned()
        );
    }
    Ok(())
}

fn checked_read(path: &Path, limit: usize) -> Result<Vec<u8>> {
    ensure!(
        plain_file(path)?.len() <= limit as u64,
        obfstr::obfstr!("Cache entry exceeds size bound").to_owned()
    );
    let mut bytes = Vec::new();
    fs::File::open(path)?
        .take((limit + 1) as u64)
        .read_to_end(&mut bytes)?;
    ensure!(
        bytes.len() <= limit,
        obfstr::obfstr!("Cache entry changed beyond bounds").to_owned()
    );
    Ok(bytes)
}

fn verify(bytes: &[u8], digest: &str, size: usize) -> Result<()> {
    ensure!(
        size > 0
            && size <= MAX
            && bytes.len() == size
            && format!("{:x}", Sha256::digest(bytes)) == digest,
        obfstr::obfstr!("Bootstrap cache does not match its signed artifact").to_owned()
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
        obfstr::obfstr!("Invalid protected cache key").to_owned()
    );
    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|_| anyhow!(obfstr::obfstr!("Invalid cache key").to_owned()))?;
    let ciphertext = cipher
        .encrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: bytes,
                aad: digest.as_bytes(),
            },
        )
        .map_err(|_| anyhow!(obfstr::obfstr!("Cache encryption failed").to_owned()))?;
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
    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|_| anyhow!(obfstr::obfstr!("Invalid cache key").to_owned()))?;
    let bytes = cipher
        .decrypt(
            Nonce::from_slice(&payload[..12]),
            Payload {
                msg: &payload[12..],
                aad: digest.as_bytes(),
            },
        )
        .map_err(|_| anyhow!(obfstr::obfstr!("Cache authentication failed").to_owned()))?;
    Ok(Zeroizing::new(bytes))
}

fn parts(bytes: &[u8]) -> Result<(&[u8], &[u8])> {
    ensure!(
        bytes.len() >= 8 + 1 + 12 + 16 && bytes.len() <= MAX + 65572 && bytes.starts_with(MAGIC),
        obfstr::obfstr!("Invalid encrypted cache").to_owned()
    );
    let length = u32::from_le_bytes(bytes[4..8].try_into()?) as usize;
    ensure!(
        (1..=65536).contains(&length) && 8 + length + 28 < bytes.len(),
        obfstr::obfstr!("Invalid encrypted cache key length").to_owned()
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
            .ok_or_else(|| anyhow!(obfstr::obfstr!("Missing cache parent").to_owned()))?,
    )?;
    let bytes = checked_read(path, MAX + 65572)?;
    let (protected, _) = parts(&bytes)?;
    let key = Zeroizing::new(crate::credential_protection::unprotect(protected)?);
    let key: &[u8; 32] = key
        .as_slice()
        .try_into()
        .map_err(|_| anyhow!(obfstr::obfstr!("Invalid account-protected cache key").to_owned()))?;
    let plain = opened(&bytes, digest, key)?;
    verify(&plain, digest, size)?;
    Ok(Some(plain))
}

pub(crate) fn store(path: &Path, digest: &str, bytes: &[u8]) -> Result<()> {
    verify(bytes, digest, bytes.len())?;
    let parent = path
        .parent()
        .ok_or_else(|| anyhow!(obfstr::obfstr!("Missing cache parent").to_owned()))?;
    let grandparent = parent
        .parent()
        .ok_or_else(|| anyhow!(obfstr::obfstr!("Missing cache root").to_owned()))?;
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
        "{}{}",
        obfstr::obfstr!(".encrypted-stage-"),
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
    #[serde(default)]
    file_identity: Option<FileIdentity>,
    #[serde(default = "completed_receipt")]
    write_complete: bool,
}

fn completed_receipt() -> bool {
    true
}

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug)]
#[serde(deny_unknown_fields)]
struct FileIdentity {
    volume: u32,
    index_high: u32,
    index_low: u32,
    created_high: u32,
    created_low: u32,
}

#[cfg(windows)]
fn file_identity(file: &fs::File) -> Result<FileIdentity> {
    use std::os::windows::io::AsRawHandle;
    use winapi::um::fileapi::{BY_HANDLE_FILE_INFORMATION, GetFileInformationByHandle};
    let mut info: BY_HANDLE_FILE_INFORMATION = unsafe { std::mem::zeroed() };
    ensure!(
        unsafe { GetFileInformationByHandle(file.as_raw_handle().cast(), &mut info) } != 0,
        obfstr::obfstr!("Cannot identify owned cache file").to_owned()
    );
    ensure!(
        info.dwFileAttributes & (0x400 | 0x10) == 0,
        obfstr::obfstr!("Owned cache file is a link or directory").to_owned()
    );
    Ok(FileIdentity {
        volume: info.dwVolumeSerialNumber,
        index_high: info.nFileIndexHigh,
        index_low: info.nFileIndexLow,
        created_high: info.ftCreationTime.dwHighDateTime,
        created_low: info.ftCreationTime.dwLowDateTime,
    })
}

fn save_owner(directory: &Path, owner: &Owner) -> Result<()> {
    let receipt = crate::credential_protection::protect(&serde_json::to_vec(owner)?)?;
    crate::atomic_store::atomic_write(&directory.join(obfstr::obfstr!("owner.cst")), &receipt)
}

pub(crate) fn materialize(root: &Path, digest: &str, bytes: &[u8]) -> Result<PathBuf> {
    materialize_with(root, digest, bytes, |root, directory| {
        #[cfg(windows)]
        watcher::start(root, directory)?;
        #[cfg(not(windows))]
        let _ = (root, directory);
        Ok(())
    })
}

fn materialize_with(
    root: &Path,
    digest: &str,
    bytes: &[u8],
    watch: impl FnOnce(&Path, &Path) -> Result<()>,
) -> Result<PathBuf> {
    owned_directory(root)?;
    verify(bytes, digest, bytes.len())?;
    let root = root.canonicalize()?;
    let identity = root
        .file_name()
        .and_then(|value| value.to_str())
        .and_then(|value| value.split_once('-'))
        .ok_or_else(|| anyhow!(obfstr::obfstr!("Invalid owned process cache").to_owned()))?;
    let pid = identity.0.parse::<u32>()?;
    let started = identity.1.parse::<u64>()?;
    ensure!(
        pid > 0 && started > 0,
        obfstr::obfstr!("Invalid process cache identity").to_owned()
    );
    let mut random = [0u8; 24];
    OsRng.try_fill_bytes(&mut random)?;
    let name = format!(
        "{}{}",
        prefix(),
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
    let path = directory.join(obfstr::obfstr!("bootstrap-a.jar"));
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    // Reserve the empty file first; its identity, not merely its path, authorizes
    // cleanup if the launcher dies while writing the plaintext.
    let mut output = options.open(&path)?;
    let mut owner = Owner {
        schema: if cfg!(windows) { 3 } else { 2 },
        product: obfstr::obfstr!("celeste_plus").into(),
        root: root.to_string_lossy().into_owned(),
        directory: name,
        sha256: digest.into(),
        size: bytes.len(),
        pid,
        started,
        #[cfg(windows)]
        file_identity: Some(file_identity(&output)?),
        #[cfg(not(windows))]
        file_identity: None,
        write_complete: false,
    };
    // The authenticated ownership record exists before any plaintext materialization.
    save_owner(&directory, &owner)?;
    // A separate, acknowledged process owns the exit wait before plaintext exists.
    // It remains alive if the launcher closes first. Failed startup leaves no JAR.
    if let Err(error) = watch(&root, &directory) {
        drop(output);
        let _ = clean_directory(&root, &directory, |_, _| true);
        return Err(error);
    }
    let result = (|| -> Result<()> {
        output.write_all(bytes)?;
        output.sync_all()?;
        drop(output);
        verify(&checked_read(&path, MAX)?, digest, bytes.len())?;
        owner.write_complete = true;
        save_owner(&directory, &owner)
    })();
    if let Err(error) = result {
        // This unpublished file has never been handed to a target process.
        // The incomplete receipt also lets the independent waiter remove it.
        let _ = clean_directory(&root, &directory, |_, _| true);
        return Err(error);
    }
    Ok(path)
}

/// Cleanup mode has no authentication, delivery, attach, or arbitrary-path entry point.
pub(crate) fn dispatch_cleanup() -> Result<bool> {
    #[cfg(windows)]
    return watcher::dispatch();
    #[cfg(not(windows))]
    Ok(false)
}

#[cfg(windows)]
fn clean_root(root: &Path, gone: impl Fn(u32, u64) -> bool) -> Result<bool> {
    owned_directory(root)?;
    let mut complete = true;
    for entry in fs::read_dir(root)?.take(4096) {
        let directory = entry?.path();
        if directory
            .file_name()
            .and_then(|v| v.to_str())
            .is_some_and(|name| name.starts_with(prefix()))
        {
            complete &= clean_directory(root, &directory, &gone).is_ok();
        }
    }
    Ok(complete)
}

fn read_owner(root: &Path, directory: &Path) -> Result<Owner> {
    owned_directory(root)?;
    owned_directory(directory)?;
    let encrypted = checked_read(&directory.join(obfstr::obfstr!("owner.cst")), 65536)?;
    let plain = Zeroizing::new(crate::credential_protection::unprotect(&encrypted)?);
    let owner: Owner = serde_json::from_slice(&plain)?;
    ensure!(
        ((owner.schema == 2 && owner.file_identity.is_none())
            || (owner.schema == 3 && owner.file_identity.is_some()))
            && owner.size > 0
            && owner.size <= MAX
            && owner.sha256.len() == 64
            && owner.sha256.bytes().all(|v| v.is_ascii_hexdigit())
            && owner.product == obfstr::obfstr!("celeste_plus")
            && owner.root == root.canonicalize()?.to_string_lossy()
            && directory.file_name().and_then(|v| v.to_str()) == Some(&owner.directory)
            && owner.directory.starts_with(prefix())
            && owner.directory.len() == prefix().len() + 48
            && owner.directory[prefix().len()..]
                .bytes()
                .all(|v| v.is_ascii_hexdigit())
            && root.file_name().and_then(|v| v.to_str())
                == Some(&format!("{}-{}", owner.pid, owner.started)),
        obfstr::obfstr!("Invalid cache ownership record").to_owned()
    );
    Ok(owner)
}

fn clean_directory(root: &Path, directory: &Path, gone: impl Fn(u32, u64) -> bool) -> Result<()> {
    // Keep the receipt itself pinned while validating it and deleting the JAR.
    // Its name cannot be replaced by a concurrent writer before deletion.
    #[cfg(windows)]
    let receipt = open_for_removal(&directory.join(obfstr::obfstr!("owner.cst")))?;
    let owner = read_owner(root, directory)?;
    ensure!(
        gone(owner.pid, owner.started),
        obfstr::obfstr!("Owned process is active or its state is uncertain").to_owned()
    );
    let jar = directory.join(obfstr::obfstr!("bootstrap-a.jar"));
    match fs::symlink_metadata(&jar) {
        Ok(_) => {
            remove_owned_jar(&jar, &owner)?;
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    #[cfg(windows)]
    {
        mark_delete(&receipt)?;
        drop(receipt);
    }
    #[cfg(not(windows))]
    fs::remove_file(directory.join(obfstr::obfstr!("owner.cst")))?;
    // An unexpected user file keeps the directory alive, untouched.
    let _ = fs::remove_dir(directory);
    Ok(())
}

#[cfg(windows)]
fn open_for_removal(path: &Path) -> Result<fs::File> {
    use std::os::windows::fs::OpenOptionsExt;
    use winapi::um::winnt::{DELETE, FILE_SHARE_READ, GENERIC_READ};
    plain_file(path)?;
    // Deny writes/renames until the verified handle itself is marked for deletion.
    // A replacement at the same path cannot be deleted through this handle.
    let file = OpenOptions::new()
        .access_mode(GENERIC_READ | DELETE)
        .share_mode(FILE_SHARE_READ)
        .custom_flags(0x0020_0000)
        .open(path)?;
    file_identity(&file)?;
    Ok(file)
}

#[cfg(windows)]
fn remove_owned_jar(path: &Path, owner: &Owner) -> Result<()> {
    let mut file = open_for_removal(path)?;
    let actual = file_identity(&file)?;
    if let Some(expected) = &owner.file_identity {
        ensure!(
            expected == &actual,
            obfstr::obfstr!("Owned cache file was replaced").to_owned()
        );
    }
    let mut bytes = Vec::new();
    (&mut file).take((MAX + 1) as u64).read_to_end(&mut bytes)?;
    if owner.schema == 3 && !owner.write_complete {
        ensure!(
            bytes.len() <= owner.size,
            obfstr::obfstr!("Incomplete cache file exceeds ownership bound").to_owned()
        );
    } else {
        verify(&bytes, &owner.sha256, owner.size)?;
    }
    mark_delete(&file)
}

#[cfg(windows)]
fn mark_delete(file: &fs::File) -> Result<()> {
    use std::os::windows::io::AsRawHandle;
    use winapi::um::{
        fileapi::{FILE_DISPOSITION_INFO, SetFileInformationByHandle},
        minwinbase::FileDispositionInfo,
    };
    let mut disposition = FILE_DISPOSITION_INFO { DeleteFile: 1 };
    ensure!(
        unsafe {
            SetFileInformationByHandle(
                file.as_raw_handle().cast(),
                FileDispositionInfo,
                (&mut disposition as *mut FILE_DISPOSITION_INFO).cast(),
                std::mem::size_of_val(&disposition) as u32,
            )
        } != 0,
        obfstr::obfstr!("Cannot remove owned cache file").to_owned()
    );
    Ok(())
}

#[cfg(not(windows))]
fn remove_owned_jar(path: &Path, owner: &Owner) -> Result<()> {
    verify(&checked_read(path, MAX)?, &owner.sha256, owner.size)?;
    fs::remove_file(path)?;
    Ok(())
}

pub(crate) fn cleanup_stale() -> Result<()> {
    let base = crate::user_profile::current_local_app_data()?
        .join(obfstr::obfstr!("Celeste"))
        .join(obfstr::obfstr!("Plus"));
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
                .is_some_and(|name| name.starts_with(prefix()))
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
    // These fixtures use synthetic, non-running process identities. Watcher tests
    // below exercise real owned child processes separately.
    #[cfg(windows)]
    fn materialize(root: &Path, digest: &str, bytes: &[u8]) -> Result<PathBuf> {
        super::materialize_with(root, digest, bytes, |_, _| Ok(()))
    }
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
    #[cfg(windows)]
    #[test]
    fn incomplete_materialization_is_removed_but_replaced_files_are_preserved() {
        let fixture = Fixture::new();
        let root = fixture.root();
        let plain = b"PK partial plaintext fixture";
        let digest = format!("{:x}", Sha256::digest(plain));
        let jar = materialize(&root, &digest, plain).unwrap();
        let directory = jar.parent().unwrap();
        let mut owner = read_owner(&root, directory).unwrap();
        owner.write_complete = false;
        save_owner(directory, &owner).unwrap();
        fs::write(&jar, &plain[..7]).unwrap();
        clean_directory(&root, directory, |_, _| true).unwrap();
        assert!(!directory.exists());

        let jar = materialize(&root, &digest, plain).unwrap();
        let directory = jar.parent().unwrap();
        let mut owner = read_owner(&root, directory).unwrap();
        owner.write_complete = false;
        save_owner(directory, &owner).unwrap();
        // Keep the old file alive to prevent filesystem ID reuse in this test.
        fs::rename(&jar, directory.join("replaced-original.jar")).unwrap();
        fs::write(&jar, b"keep").unwrap();
        assert!(clean_directory(&root, directory, |_, _| true).is_err());
        assert_eq!(fs::read(&jar).unwrap(), b"keep");
    }
    #[cfg(windows)]
    #[test]
    fn failed_watcher_never_leaves_plaintext_or_an_owned_receipt() {
        let fixture = Fixture::new();
        let root = fixture.root();
        let plain = b"PK private fixture";
        let digest = format!("{:x}", Sha256::digest(plain));
        let result = materialize_with(&root, &digest, plain, |_, directory| {
            assert!(
                fs::read(directory.join("bootstrap-a.jar"))
                    .unwrap()
                    .is_empty()
            );
            let owner = read_owner(&root, directory).unwrap();
            assert!(!owner.write_complete);
            Err(anyhow!("fixture handshake failure"))
        });
        assert!(result.is_err());
        assert_eq!(fs::read_dir(&root).unwrap().count(), 0);
    }
    #[cfg(windows)]
    #[test]
    fn cleanup_receipt_handle_prevents_replacement_and_releases_after_use() {
        let fixture = Fixture::new();
        let root = fixture.root();
        let plain = b"PK receipt locking fixture";
        let digest = format!("{:x}", Sha256::digest(plain));
        let jar = materialize(&root, &digest, plain).unwrap();
        let directory = jar.parent().unwrap();
        let receipt = directory.join("owner.cst");
        let moved = directory.join("unrelated-receipt.cst");
        let handle = open_for_removal(&receipt).unwrap();
        assert!(fs::rename(&receipt, &moved).is_err());
        assert!(fs::write(&receipt, b"replacement").is_err());
        assert!(read_owner(&root, directory).is_ok());
        drop(handle);
        fs::rename(&receipt, &moved).unwrap();
        fs::write(&receipt, b"replacement").unwrap();
        assert!(clean_directory(&root, directory, |_, _| true).is_err());
        assert_eq!(fs::read(&receipt).unwrap(), b"replacement");
        assert!(jar.exists());
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
