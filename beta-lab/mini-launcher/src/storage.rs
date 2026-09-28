//! Windows custody for a verified full launcher. No shell, process termination,
//! recursive deletion, or executable mutation. The full launcher keeps its ABI.
use anyhow::{Context, Result, anyhow, ensure};
use rand::{RngCore, rngs::OsRng};
use sha2::{Digest, Sha256};
use std::{
    ffi::OsStr,
    fs::{File, OpenOptions},
    io::{Read, Seek, SeekFrom},
    os::windows::{ffi::OsStrExt, fs::OpenOptionsExt, io::AsRawHandle, process::CommandExt},
    path::{Component, Path, PathBuf, Prefix},
    process::{Command, Stdio},
    ptr,
    sync::Mutex,
    thread,
    time::{Duration, Instant},
};
use winapi::{
    shared::{
        minwindef::{DWORD, FALSE},
        sddl::{
            ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
            SDDL_REVISION_1,
        },
    },
    um::{
        fileapi::{
            BY_HANDLE_FILE_INFORMATION, CreateDirectoryW, FILE_DISPOSITION_INFO,
            GetFileInformationByHandle, GetTempPathW, SetFileInformationByHandle,
        },
        handleapi::CloseHandle,
        minwinbase::{FileDispositionInfo, SECURITY_ATTRIBUTES},
        processthreadsapi::{GetCurrentProcess, OpenProcessToken},
        securitybaseapi::GetTokenInformation,
        winbase::{FILE_FLAG_BACKUP_SEMANTICS, FILE_FLAG_OPEN_REPARSE_POINT, LocalFree},
        winnt::{
            DELETE, FILE_ATTRIBUTE_DIRECTORY, FILE_ATTRIBUTE_REPARSE_POINT, FILE_SHARE_READ,
            FILE_SHARE_WRITE, GENERIC_READ, HANDLE, PSECURITY_DESCRIPTOR, TOKEN_QUERY, TOKEN_USER,
            TokenUser,
        },
    },
};

const EXE: &str = "Celeste+.exe";
const MAX_EXE: u64 = 256 * 1024 * 1024;
const CLEANUP_BOUND: Duration = Duration::from_secs(24 * 60 * 60);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct Identity {
    volume: u32,
    index: u64,
}

#[derive(Clone, Debug)]
struct Verified {
    identity: Identity,
    size: u64,
}

struct Token(HANDLE);
impl Drop for Token {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.0);
        }
    }
}

struct LocalAllocation(*mut std::ffi::c_void);
impl Drop for LocalAllocation {
    fn drop(&mut self) {
        unsafe {
            LocalFree(self.0.cast());
        }
    }
}

fn wide(value: &OsStr) -> Result<Vec<u16>> {
    let mut text: Vec<u16> = value.encode_wide().collect();
    ensure!(!text.contains(&0), "Invalid temporary path");
    text.push(0);
    Ok(text)
}

fn identity(file: &File, directory: bool) -> Result<Identity> {
    let mut info: BY_HANDLE_FILE_INFORMATION = unsafe { std::mem::zeroed() };
    if unsafe { GetFileInformationByHandle(file.as_raw_handle().cast(), &mut info) } == 0 {
        return Err(std::io::Error::last_os_error()).context("Cannot identify owned file");
    }
    ensure!(
        info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT == 0
            && (info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY != 0) == directory,
        "Owned temporary entry changed type"
    );
    ensure!(
        directory || info.nNumberOfLinks == 1,
        "Executable must not have another hard link"
    );
    Ok(Identity {
        volume: info.dwVolumeSerialNumber,
        index: (u64::from(info.nFileIndexHigh) << 32) | u64::from(info.nFileIndexLow),
    })
}

fn open_directory(path: &Path, deletion: bool) -> Result<File> {
    let file = OpenOptions::new()
        .access_mode(GENERIC_READ | if deletion { DELETE } else { 0 })
        .share_mode(FILE_SHARE_READ | FILE_SHARE_WRITE) // Deny rename/replacement.
        .custom_flags(FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT)
        .open(path)?;
    identity(&file, true)?;
    Ok(file)
}

fn temp_root() -> Result<PathBuf> {
    let mut buffer = vec![0u16; 32768];
    let count = unsafe { GetTempPathW(buffer.len() as DWORD, buffer.as_mut_ptr()) } as usize;
    ensure!(
        count > 0 && count < buffer.len(),
        "Windows temporary directory is unavailable"
    );
    use std::os::windows::ffi::OsStringExt;
    let root = PathBuf::from(std::ffi::OsString::from_wide(&buffer[..count])).canonicalize()?;
    ensure!(
        root.is_absolute()
            && matches!(root.components().next(),
        Some(Component::Prefix(prefix)) if matches!(prefix.kind(), Prefix::Disk(_) | Prefix::VerbatimDisk(_))),
        "Temporary storage must be on a local Windows drive"
    );
    Ok(root)
}

fn launch_spelling(path: &Path) -> Result<PathBuf> {
    use std::os::windows::ffi::OsStringExt;
    ensure!(path.is_absolute(), "Launch path must be absolute");
    let prefix = match path.components().next() {
        Some(Component::Prefix(prefix)) => prefix.kind(),
        _ => return Err(anyhow!("Launch path requires a local drive")),
    };
    let wide: Vec<u16> = path.as_os_str().encode_wide().collect();
    let ordinary = match prefix {
        Prefix::VerbatimDisk(_) => &wide[4..],
        Prefix::Disk(_) => &wide[..],
        _ => return Err(anyhow!("Launch path must not be a UNC or device namespace")),
    };
    // Some native GUI/driver code expects conventional DOS spelling. Custody
    // continues to use canonical paths and locked handles. Retain the verbatim
    // spelling for genuine long paths rather than breaking those downloads.
    if ordinary.len() >= 260 {
        return Ok(path.to_owned());
    }
    let candidate = PathBuf::from(std::ffi::OsString::from_wide(ordinary));
    ensure!(
        candidate.canonicalize()? == path.canonicalize()?,
        "Ordinary launch path resolves to another file"
    );
    Ok(candidate)
}

fn account_sid() -> Result<String> {
    let mut raw = ptr::null_mut();
    if unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut raw) } == 0 {
        return Err(std::io::Error::last_os_error()).context("Cannot identify current account");
    }
    let token = Token(raw);
    let mut needed = 0;
    unsafe {
        GetTokenInformation(token.0, TokenUser, ptr::null_mut(), 0, &mut needed);
    }
    ensure!(
        (std::mem::size_of::<TOKEN_USER>() as u32..=65536).contains(&needed),
        "Invalid account identity"
    );
    // Pointer-sized allocation provides TOKEN_USER's alignment.
    let mut storage = vec![0usize; (needed as usize).div_ceil(std::mem::size_of::<usize>())];
    if unsafe {
        GetTokenInformation(
            token.0,
            TokenUser,
            storage.as_mut_ptr().cast(),
            needed,
            &mut needed,
        )
    } == 0
    {
        return Err(std::io::Error::last_os_error()).context("Cannot read current account");
    }
    let user = unsafe { &*storage.as_ptr().cast::<TOKEN_USER>() };
    let mut text = ptr::null_mut();
    if unsafe { ConvertSidToStringSidW(user.User.Sid, &mut text) } == 0 {
        return Err(std::io::Error::last_os_error()).context("Cannot represent current account");
    }
    let _allocation = LocalAllocation(text.cast());
    let length = (0..256)
        .find(|i| unsafe { *text.add(*i) == 0 })
        .ok_or_else(|| anyhow!("Invalid account SID length"))?;
    let sid = String::from_utf16(unsafe { std::slice::from_raw_parts(text, length) })?;
    ensure!(
        sid.starts_with("S-1-")
            && sid
                .bytes()
                .all(|c| c.is_ascii_digit() || c == b'S' || c == b'-'),
        "Invalid account SID"
    );
    Ok(sid)
}

fn private_directory(path: &Path) -> Result<()> {
    let sid = account_sid()?;
    // Protected, inheritable DACL: only this account and SYSTEM, including files
    // subsequently created by the downloader. There is no inherited broad ACE.
    let sddl = format!("D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;{sid})");
    let sddl = wide(OsStr::new(&sddl))?;
    let mut descriptor: PSECURITY_DESCRIPTOR = ptr::null_mut();
    if unsafe {
        ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl.as_ptr(),
            DWORD::from(SDDL_REVISION_1),
            &mut descriptor,
            ptr::null_mut(),
        )
    } == 0
    {
        return Err(std::io::Error::last_os_error())
            .context("Cannot create private security descriptor");
    }
    let _allocation = LocalAllocation(descriptor.cast());
    let mut attributes = SECURITY_ATTRIBUTES {
        nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as DWORD,
        lpSecurityDescriptor: descriptor,
        bInheritHandle: FALSE,
    };
    if unsafe { CreateDirectoryW(wide(path.as_os_str())?.as_ptr(), &mut attributes) } == 0 {
        return Err(std::io::Error::last_os_error())
            .context("Cannot create fresh private temporary directory");
    }
    Ok(())
}

fn ordinary_delete(file: &File) -> Result<()> {
    let mut value = FILE_DISPOSITION_INFO { DeleteFile: 1 };
    if unsafe {
        SetFileInformationByHandle(
            file.as_raw_handle().cast(),
            FileDispositionInfo,
            (&mut value as *mut FILE_DISPOSITION_INFO).cast(),
            std::mem::size_of_val(&value) as DWORD,
        )
    } == 0
    {
        return Err(std::io::Error::last_os_error())
            .context("Owned temporary file is still in use");
    }
    Ok(())
}

fn transient_deletion(error: &anyhow::Error) -> bool {
    error
        .downcast_ref::<std::io::Error>()
        .and_then(std::io::Error::raw_os_error)
        .is_some_and(|code| matches!(code, 5 | 32 | 33 | 1224))
}

/// `path()` is the private directory. The downloader creates only Celeste+.exe
/// via create_download(), closes the writer, then obtains the verified read guard.
pub struct PrivateTemp {
    path: PathBuf,
    root_guard: File,
    directory_guard: Option<File>,
    directory_identity: Identity,
    download_identity: Mutex<Option<Identity>>,
    verified: Mutex<Option<Verified>>,
    removed: bool,
}

impl PrivateTemp {
    pub fn create() -> Result<Self> {
        let root = temp_root()?;
        let root_guard = open_directory(&root, false)?;
        let mut random = [0u8; 24];
        OsRng.try_fill_bytes(&mut random)?;
        let name = random
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        let path = root.join(format!("CelestePlus-{name}"));
        private_directory(&path)?;
        let directory_guard = open_directory(&path, false)?;
        let directory_identity = identity(&directory_guard, true)?;
        ensure!(
            path.canonicalize()?.parent() == Some(root.as_path()),
            "Private directory escaped temporary root"
        );
        Ok(Self {
            path,
            root_guard,
            directory_guard: Some(directory_guard),
            directory_identity,
            download_identity: Mutex::new(None),
            verified: Mutex::new(None),
            removed: false,
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Reserve the one allowed download name and record its identity before
    /// any bytes are written. Failed/cancelled partial downloads can then be
    /// removed without adopting an unrelated file at that path.
    pub fn create_download(&self) -> Result<File> {
        let file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .share_mode(0)
            .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT)
            .open(self.path.join(EXE))?;
        let created = identity(&file, false)?;
        *self
            .download_identity
            .lock()
            .map_err(|_| anyhow!("Download custody unavailable"))? = Some(created);
        Ok(file)
    }

    pub fn locked_verified_executable(
        &self,
        expected_sha: &str,
        expected_size: u64,
    ) -> Result<File> {
        ensure!(
            (1..=MAX_EXE).contains(&expected_size)
                && expected_sha.len() == 64
                && expected_sha
                    .bytes()
                    .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c)),
            "Invalid signed executable identity"
        );
        let mut file = OpenOptions::new()
            .read(true)
            .share_mode(FILE_SHARE_READ)
            .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT)
            .open(self.path.join(EXE))?;
        let file_identity = identity(&file, false)?;
        ensure!(
            self.download_identity
                .lock()
                .map_err(|_| anyhow!("Download custody unavailable"))?
                .as_ref()
                == Some(&file_identity),
            "Download file was not created by this launcher"
        );
        ensure!(
            file.metadata()?.len() == expected_size,
            "Downloaded executable size differs"
        );
        let mut digest = Sha256::new();
        let mut buffer = [0u8; 65536];
        let mut size = 0u64;
        loop {
            let count = file.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            size += count as u64;
            ensure!(size <= expected_size, "Downloaded executable grew");
            digest.update(&buffer[..count]);
        }
        ensure!(
            size == expected_size && format!("{:x}", digest.finalize()) == expected_sha,
            "Downloaded executable hash differs"
        );
        file.seek(SeekFrom::Start(0))?;
        *self
            .verified
            .lock()
            .map_err(|_| anyhow!("Executable custody unavailable"))? = Some(Verified {
            identity: file_identity,
            size,
        });
        Ok(file)
    }

    pub fn execute_and_cleanup(mut self, guard: File, on_started: impl FnOnce()) -> Result<()> {
        let verified = self
            .verified
            .lock()
            .map_err(|_| anyhow!("Executable custody unavailable"))?
            .clone()
            .ok_or_else(|| anyhow!("Executable has not been verified"))?;
        ensure!(
            identity(&guard, false)? == verified.identity
                && guard.metadata()?.len() == verified.size,
            "Executable custody guard differs"
        );
        let executable = launch_spelling(&self.path.join(EXE))?;
        let working_directory = launch_spelling(&self.path)?;
        let launched = Command::new(executable)
            .current_dir(working_directory)
            .creation_flags(0x0800_0000)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn();
        let mut child = match launched {
            Ok(child) => child,
            Err(error) => {
                drop(guard);
                let _ = self.cleanup(&verified, Duration::ZERO);
                return Err(error).context("Cannot open the verified full launcher");
            }
        };
        on_started();
        // No timeout/kill for the user's full launcher. Its cleanup descendants
        // can legitimately remain alive until Minecraft itself exits.
        child.wait().context("Cannot wait for the full launcher")?;
        drop(guard);
        self.cleanup(&verified, CLEANUP_BOUND)
    }

    fn remove_executable(&self, owned: Identity, expected_size: Option<u64>) -> Result<()> {
        let file = match OpenOptions::new()
            .access_mode(GENERIC_READ | DELETE)
            .share_mode(FILE_SHARE_READ)
            .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT)
            .open(self.path.join(EXE))
        {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
            Err(error) => return Err(error.into()),
        };
        ensure!(
            identity(&file, false)? == owned
                && expected_size.is_none_or(|size| file.metadata().is_ok_and(|m| m.len() == size)),
            "Temporary executable was replaced; leaving it untouched"
        );
        // Exact owned file handle; no path-only deletion race, no POSIX image
        // unlink, and no early delete-on-close setting. Mapped helpers block it.
        ordinary_delete(&file)
    }

    fn remove_directory(&mut self) -> Result<()> {
        // The root guard remains held. Reopen and compare after releasing our
        // deny-delete directory handle, then delete that exact empty directory.
        drop(self.directory_guard.take());
        let directory = open_directory(&self.path, true)?;
        ensure!(
            identity(&directory, true)? == self.directory_identity,
            "Temporary directory was replaced; leaving it untouched"
        );
        ordinary_delete(&directory)?; // Nonempty directories are preserved.
        drop(directory);
        self.removed = true;
        Ok(())
    }

    fn cleanup(&mut self, verified: &Verified, bound: Duration) -> Result<()> {
        let deadline = Instant::now() + bound;
        loop {
            match self.remove_executable(verified.identity, Some(verified.size)) {
                Ok(()) => return self.remove_directory(),
                Err(error) if transient_deletion(&error) && Instant::now() < deadline => {
                    thread::sleep(Duration::from_secs(1))
                }
                Err(error) => {
                    return Err(error)
                        .context("Private temporary copy remains for later Windows temp cleanup");
                }
            }
        }
    }
}

impl Drop for PrivateTemp {
    fn drop(&mut self) {
        if !self.removed {
            // Cleanup authority is the identity reserved before downloading,
            // even for a cancelled partial file. Replacements/links are refused.
            if let Some(owned) = self
                .download_identity
                .get_mut()
                .ok()
                .and_then(|value| *value)
            {
                let _ = self.remove_executable(owned, None);
            }
            let _ = self.remove_directory();
        }
        let _ = &self.root_guard; // Keep the temp root pinned through retirement.
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn fixture() -> (PrivateTemp, File, Verified) {
        let temp = PrivateTemp::create().unwrap();
        let bytes = b"owned nonexecutable fixture";
        let mut out = temp.create_download().unwrap();
        out.write_all(bytes).unwrap();
        drop(out);
        let guard = temp
            .locked_verified_executable(&format!("{:x}", Sha256::digest(bytes)), bytes.len() as u64)
            .unwrap();
        let verified = temp.verified.lock().unwrap().clone().unwrap();
        (temp, guard, verified)
    }

    #[test]
    fn guard_blocks_writes_rename_and_delete_until_release() {
        let (mut temp, guard, verified) = fixture();
        assert!(
            OpenOptions::new()
                .write(true)
                .open(temp.path().join(EXE))
                .is_err()
        );
        assert!(std::fs::remove_file(temp.path().join(EXE)).is_err());
        assert!(std::fs::rename(temp.path(), temp.path().with_extension("moved")).is_err());
        let root = temp.path().to_owned();
        drop(guard);
        temp.cleanup(&verified, Duration::ZERO).unwrap();
        assert!(!root.exists());
    }

    #[test]
    fn wrong_hash_never_grants_execution_and_unowned_contents_survive() {
        let (mut temp, guard, verified) = fixture();
        assert!(
            temp.locked_verified_executable(&"0".repeat(64), verified.size)
                .is_err()
        );
        let other = temp.path().join("unowned.txt");
        std::fs::write(&other, b"preserve").unwrap();
        drop(guard);
        assert!(temp.cleanup(&verified, Duration::ZERO).is_err());
        assert_eq!(std::fs::read(&other).unwrap(), b"preserve");
        std::fs::remove_file(&other).unwrap(); // Test-created fixture only.
        temp.remove_directory().unwrap();
    }

    #[test]
    fn replaced_executable_is_never_removed() {
        let (mut temp, guard, verified) = fixture();
        drop(guard);
        let path = temp.path().join(EXE);
        let original = temp.path().join("test-original.bin");
        std::fs::rename(&path, &original).unwrap();
        std::fs::write(&path, b"replacement").unwrap();
        assert!(temp.cleanup(&verified, Duration::ZERO).is_err());
        assert_eq!(std::fs::read(&path).unwrap(), b"replacement");
        std::fs::remove_file(&path).unwrap();
        std::fs::remove_file(&original).unwrap();
        temp.remove_directory().unwrap();
    }

    #[test]
    fn private_directory_has_only_account_and_system_inheritable_access() {
        use winapi::shared::sddl::ConvertSecurityDescriptorToStringSecurityDescriptorW;
        use winapi::um::{
            accctrl::SE_FILE_OBJECT, aclapi::GetSecurityInfo, winnt::DACL_SECURITY_INFORMATION,
        };
        let mut temp = PrivateTemp::create().unwrap();
        let handle = temp.directory_guard.as_ref().unwrap();
        let mut descriptor: PSECURITY_DESCRIPTOR = ptr::null_mut();
        assert_eq!(
            unsafe {
                GetSecurityInfo(
                    handle.as_raw_handle().cast(),
                    SE_FILE_OBJECT,
                    DACL_SECURITY_INFORMATION,
                    ptr::null_mut(),
                    ptr::null_mut(),
                    ptr::null_mut(),
                    ptr::null_mut(),
                    &mut descriptor,
                )
            },
            0
        );
        let _descriptor = LocalAllocation(descriptor.cast());
        let mut text = ptr::null_mut();
        let mut length = 0;
        assert_ne!(
            unsafe {
                ConvertSecurityDescriptorToStringSecurityDescriptorW(
                    descriptor,
                    DWORD::from(SDDL_REVISION_1),
                    DACL_SECURITY_INFORMATION,
                    &mut text,
                    &mut length,
                )
            },
            0
        );
        let _text = LocalAllocation(text.cast());
        let units = unsafe { std::slice::from_raw_parts(text, length as usize) };
        let end = units.iter().position(|unit| *unit == 0).unwrap();
        let actual = String::from_utf16(&units[..end]).unwrap();
        assert!(
            actual
                == format!(
                    "D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;{})",
                    account_sid().unwrap()
                ),
            "Private DACL does not match the two authorized principals"
        );
        temp.remove_directory().unwrap();
    }

    #[test]
    fn hardlinked_download_cannot_be_verified_or_launched() {
        let temp = PrivateTemp::create().unwrap();
        let path = temp.path().join(EXE);
        let other = temp.path().join("owned-link-fixture");
        let mut out = temp.create_download().unwrap();
        out.write_all(b"fixture").unwrap();
        drop(out);
        std::fs::hard_link(&path, &other).unwrap();
        assert!(
            temp.locked_verified_executable(&format!("{:x}", Sha256::digest(b"fixture")), 7)
                .is_err()
        );
        assert!(temp.verified.lock().unwrap().is_none());
        std::fs::remove_file(&other).unwrap();
        std::fs::remove_file(&path).unwrap();
    }

    #[test]
    fn cancelled_partial_download_is_retired_by_owned_identity() {
        let temp = PrivateTemp::create().unwrap();
        let path = temp.path().to_owned();
        let mut writer = temp.create_download().unwrap();
        writer.write_all(b"cancelled partial download").unwrap();
        assert!(temp.create_download().is_err());
        drop(writer);
        drop(temp);
        assert!(!path.exists());
    }

    #[test]
    fn failed_hash_verification_still_retires_only_its_own_download() {
        let temp = PrivateTemp::create().unwrap();
        let path = temp.path().to_owned();
        let mut writer = temp.create_download().unwrap();
        writer.write_all(b"wrong content").unwrap();
        drop(writer);
        assert!(
            temp.locked_verified_executable(&"0".repeat(64), 13)
                .is_err()
        );
        drop(temp);
        assert!(!path.exists());
    }

    #[test]
    fn replaced_partial_download_survives_drop() {
        let temp = PrivateTemp::create().unwrap();
        let path = temp.path().to_owned();
        let mut writer = temp.create_download().unwrap();
        writer.write_all(b"partial").unwrap();
        drop(writer);
        let original = path.join("owned-original-test-fixture");
        std::fs::rename(path.join(EXE), &original).unwrap();
        std::fs::write(path.join(EXE), b"preserve replacement").unwrap();
        drop(temp);
        assert_eq!(
            std::fs::read(path.join(EXE)).unwrap(),
            b"preserve replacement"
        );
        std::fs::remove_file(path.join(EXE)).unwrap();
        std::fs::remove_file(original).unwrap();
        std::fs::remove_dir(path).unwrap();
    }

    #[test]
    fn ordinary_launch_spelling_keeps_exact_short_file_and_directory() {
        let (temp, guard, _) = fixture();
        for path in [temp.path().to_owned(), temp.path().join(EXE)] {
            let canonical = path.canonicalize().unwrap();
            let ordinary = launch_spelling(&canonical).unwrap();
            assert!(matches!(ordinary.components().next(),
                Some(Component::Prefix(prefix)) if matches!(prefix.kind(), Prefix::Disk(_))));
            assert_eq!(ordinary.canonicalize().unwrap(), canonical);
            assert_eq!(launch_spelling(&ordinary).unwrap(), ordinary);
        }
        drop(guard);
    }

    #[test]
    fn launch_spelling_refuses_unc_before_any_path_resolution() {
        for name in [
            r"\\server\share\file.exe",
            r"\\?\UNC\server\share\file.exe",
            r"\\.\pipe\name",
        ] {
            assert!(launch_spelling(Path::new(name)).is_err());
        }
        let long = PathBuf::from(format!(r"\\?\C:\{}\Celeste+.exe", "x".repeat(260)));
        assert_eq!(launch_spelling(&long).unwrap(), long);
    }
}
