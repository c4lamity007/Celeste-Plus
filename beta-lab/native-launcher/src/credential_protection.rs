//! Current-user DPAPI protection and a credential-free persistence preflight.
//! Keep this implementation identical in the standard and Celeste+ launchers.

use anyhow::{Context, Result, anyhow, bail};
use rand::{RngCore, rngs::OsRng};
use std::fs::{self, OpenOptions};
use std::io;
use std::path::{Path, PathBuf};
use std::ptr;
use winapi::shared::minwindef::DWORD;
use winapi::um::dpapi::{CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData};
use winapi::um::winbase::LocalFree;
use winapi::um::wincrypt::DATA_BLOB;
use zeroize::Zeroize;

// Credentials and launch tickets are small. Bound both allocations and Win32's
// DWORD conversion, allowing ample room for DPAPI's protected-envelope overhead.
const MAX_PLAINTEXT_BYTES: usize = 1024 * 1024;
const MAX_PROTECTED_BYTES: usize = MAX_PLAINTEXT_BYTES + 64 * 1024;

/// Kept typed so callers can distinguish a local Windows protection failure
/// from a missing binding or a server rejection. Never contains credential data.
#[derive(Debug)]
pub(crate) struct ProtectionError {
    operation: &'static str,
    action: &'static str,
    os_error: io::Error,
    profile_diagnostics: Option<String>,
}

impl std::fmt::Display for ProtectionError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            formatter,
            "Windows could not {} the device credential ({}",
            self.action, self.operation
        )?;
        if let Some(code) = self.os_error.raw_os_error() {
            let code = code as u32;
            write!(formatter, ", Windows error {code} / 0x{code:08X}")?;
        }
        write!(
            formatter,
            ": {}). Use your normal signed-in Windows account with its user profile loaded; saved activations require the Windows account that created them. If the problem persists, sign out of Windows, sign back in, and retry. Do not delete saved activation files.",
            self.os_error
        )?;
        if let Some(diagnostics) = &self.profile_diagnostics {
            write!(
                formatter,
                " Windows error 3 means a required path was not found. If AppData is redirected to another drive or a network location, make that location available, then sign out of Windows and back in. Keep using the same Windows account. {diagnostics}"
            )?;
        }
        Ok(())
    }
}

impl std::error::Error for ProtectionError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        Some(&self.os_error)
    }
}

#[derive(Clone, Copy)]
enum Operation {
    Protect,
    Unprotect,
}

impl Operation {
    fn description(self) -> (&'static str, &'static str) {
        match self {
            Self::Protect => ("CryptProtectData", "protect"),
            Self::Unprotect => ("CryptUnprotectData", "open"),
        }
    }
}

fn checked_length(length: usize, limit: usize) -> Result<DWORD> {
    let win32_length = DWORD::try_from(length)
        .map_err(|_| anyhow!("Credential data exceeds the Windows DPAPI length limit"))?;
    if length == 0 || length > limit {
        bail!("Credential data is empty or exceeds the supported size bounds");
    }
    Ok(win32_length)
}

/// Owns only a buffer returned by a successful DPAPI operation. The OS owns the
/// allocation mechanism, so neither Vec::from_raw_parts nor Rust deallocation
/// may be used for this pointer.
struct DpapiBuffer(DATA_BLOB);

impl DpapiBuffer {
    fn clear_and_release(&mut self, release: impl FnOnce(*mut u8)) {
        let allocation = std::mem::replace(
            &mut self.0,
            DATA_BLOB {
                cbData: 0,
                pbData: ptr::null_mut(),
            },
        );
        if !allocation.pbData.is_null() {
            unsafe {
                std::slice::from_raw_parts_mut(allocation.pbData, allocation.cbData as usize)
                    .zeroize();
            }
            release(allocation.pbData);
        }
    }

    fn copy(&self, limit: usize) -> Result<Vec<u8>> {
        let length = self.0.cbData as usize;
        checked_length(length, limit)?;
        if self.0.pbData.is_null() {
            bail!("Windows DPAPI returned an invalid output buffer");
        }
        // A successful DPAPI call owns `cbData` initialized bytes at `pbData`.
        // Validate non-null/nonempty/bounded output before constructing a slice.
        Ok(unsafe { std::slice::from_raw_parts(self.0.pbData, length) }.to_vec())
    }
}

impl Drop for DpapiBuffer {
    fn drop(&mut self) {
        self.clear_and_release(|allocation| unsafe {
            LocalFree(allocation as *mut _);
        });
    }
}

fn transform(value: &[u8], operation: Operation) -> Result<Vec<u8>> {
    let (input_limit, output_limit) = match operation {
        Operation::Protect => (MAX_PLAINTEXT_BYTES, MAX_PROTECTED_BYTES),
        Operation::Unprotect => (MAX_PROTECTED_BYTES, MAX_PLAINTEXT_BYTES),
    };
    let mut input = DATA_BLOB {
        cbData: checked_length(value.len(), input_limit)?,
        // The API declares BYTE*, but documents pDataIn as input-only. The
        // borrowed slice remains alive and unchanged for the entire call.
        pbData: value.as_ptr() as *mut u8,
    };
    let mut output = DATA_BLOB {
        cbData: 0,
        pbData: ptr::null_mut(),
    };
    let result = unsafe {
        match operation {
            Operation::Protect => CryptProtectData(
                &mut input,
                ptr::null(),
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null_mut(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            ),
            Operation::Unprotect => CryptUnprotectData(
                &mut input,
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null_mut(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            ),
        }
    };
    if result == 0 {
        // Capture before formatting, allocation, cleanup, or any other Win32
        // operation can replace the calling thread's last-error value.
        let os_error = io::Error::last_os_error();
        let profile_diagnostics = (os_error.raw_os_error() == Some(3))
            .then(crate::windows_profile::current_profile_diagnostics);
        let (operation, action) = operation.description();
        return Err(ProtectionError {
            operation,
            action,
            os_error,
            profile_diagnostics,
        }
        .into());
    }
    // On failure the output is unspecified. Only take ownership on success.
    DpapiBuffer(output).copy(output_limit)
}

pub(crate) fn protect(value: &[u8]) -> Result<Vec<u8>> {
    transform(value, Operation::Protect)
}

pub(crate) fn unprotect(value: &[u8]) -> Result<Vec<u8>> {
    transform(value, Operation::Unprotect)
}

/// The path is owned only after a successful create_new reservation. Cleanup
/// never enumerates files, touches an activation filename, or removes a folder.
struct ProbeFile(Option<PathBuf>);

impl ProbeFile {
    fn reserve(directory: &Path) -> Result<Self> {
        for _ in 0..32 {
            let mut random = [0u8; 16];
            OsRng
                .try_fill_bytes(&mut random)
                .context("Windows randomness is unavailable for the storage check")?;
            let suffix: String = random.iter().map(|byte| format!("{byte:02x}")).collect();
            let path = directory.join(format!(
                ".celeste-storage-check-{}-{suffix}.probe",
                std::process::id()
            ));
            match OpenOptions::new().write(true).create_new(true).open(&path) {
                Ok(file) => {
                    drop(file);
                    return Ok(Self(Some(path)));
                }
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                Err(error) => {
                    return Err(error).context("Could not reserve an activation storage probe");
                }
            }
        }
        bail!("Could not reserve a unique activation storage probe")
    }

    fn path(&self) -> &Path {
        self.0.as_deref().expect("owned probe before cleanup")
    }

    fn remove(&mut self) -> io::Result<()> {
        if let Some(path) = &self.0 {
            match fs::remove_file(path) {
                Ok(()) => {}
                Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                Err(error) => return Err(error),
            }
            self.0 = None;
        }
        Ok(())
    }
}

impl Drop for ProbeFile {
    fn drop(&mut self) {
        // Also runs on read, validation, and write errors. Explicit successful-
        // path cleanup reports errors; Drop retries only this exact owned path.
        let _ = self.remove();
    }
}

/// Call before a new remote enrollment. Checks current-user DPAPI and the same
/// atomic persistence/readback path used for credentials, using random nonsecret
/// bytes only. This never reads, replaces, or removes an existing activation.
pub(crate) fn preflight(directory: &Path) -> Result<()> {
    if !directory.is_absolute() {
        bail!("Activation storage check requires an absolute Windows directory");
    }
    // Resolve redirected current-account AppData and let Windows prepare its
    // known folders before DPAPI needs the profile's Roaming AppData path.
    let _folders = crate::windows_profile::prepare_current_user_folders()?;
    let mut sample = [0u8; 64];
    OsRng
        .try_fill_bytes(&mut sample)
        .context("Windows randomness is unavailable for the protection check")?;
    let protected = protect(&sample)?;
    if unprotect(&protected)? != sample {
        bail!("Windows credential protection did not pass its round-trip check");
    }
    fs::create_dir_all(directory)
        .context("Could not create the activation storage directory; enrollment was not started")?;
    let mut probe = ProbeFile::reserve(directory)?;
    let result = (|| -> Result<()> {
        crate::atomic_store::atomic_write(probe.path(), &protected).context(
            "Could not persist the activation storage check; enrollment was not started",
        )?;
        let stored =
            fs::read(probe.path()).context("Could not read the activation storage check")?;
        if stored != protected || unprotect(&stored)? != sample {
            bail!("The activation storage check did not preserve its protected data");
        }
        Ok(())
    })();
    let cleanup = probe.remove();
    match (result, cleanup) {
        (Ok(()), Ok(())) => Ok(()),
        (Err(error), Ok(())) => Err(error),
        (Ok(()), Err(error)) => {
            Err(error).context("Could not remove the temporary activation storage check")
        }
        (Err(error), Err(cleanup_error)) => Err(error.context(format!(
            "The temporary activation storage check also could not be removed: {cleanup_error}"
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn os_buffer_is_wiped_before_release_after_copy_success_or_failure() {
        for limit in [8, 7] {
            let allocation = unsafe { winapi::um::winbase::LocalAlloc(0, 8) } as *mut u8;
            assert!(!allocation.is_null());
            unsafe {
                std::slice::from_raw_parts_mut(allocation, 8).fill(0x5a);
            }
            let mut owned = DpapiBuffer(DATA_BLOB {
                cbData: 8,
                pbData: allocation,
            });
            let copied = owned.copy(limit);
            if limit == 8 {
                assert_eq!(copied.unwrap(), [0x5a; 8]);
            } else {
                assert!(copied.is_err());
            }
            let released = std::cell::Cell::new(false);
            owned.clear_and_release(|pointer| {
                assert_eq!(unsafe { std::slice::from_raw_parts(pointer, 8) }, [0; 8]);
                unsafe {
                    LocalFree(pointer as *mut _);
                }
                released.set(true);
            });
            assert!(released.get());
            owned.clear_and_release(|_| panic!("allocation released twice"));
        }
    }

    struct TestDirectory(PathBuf);

    impl TestDirectory {
        fn new() -> Self {
            for _ in 0..32 {
                let mut random = [0u8; 16];
                OsRng.try_fill_bytes(&mut random).unwrap();
                let suffix: String = random.iter().map(|byte| format!("{byte:02x}")).collect();
                let path = std::env::temp_dir().join(format!(
                    "celeste-protection-test-{}-{suffix}",
                    std::process::id()
                ));
                match fs::create_dir(&path) {
                    Ok(()) => return Self(path),
                    Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                    Err(error) => panic!("Could not create fixture directory: {error}"),
                }
            }
            panic!("Could not reserve a fixture directory");
        }
    }

    impl Drop for TestDirectory {
        fn drop(&mut self) {
            // The tests create direct fixture files only. No recursive cleanup.
            if let Ok(entries) = fs::read_dir(&self.0) {
                for entry in entries.flatten() {
                    let _ = fs::remove_file(entry.path());
                }
            }
            let _ = fs::remove_dir(&self.0);
        }
    }

    #[test]
    fn nonsecret_fixture_round_trips_for_the_current_windows_user() {
        let fixture = b"Celeste DPAPI fixture: not a license, key, token, or credential";
        let protected = protect(fixture).unwrap();
        assert_ne!(protected, fixture);
        assert_eq!(unprotect(&protected).unwrap(), fixture);
    }

    #[test]
    fn corrupted_and_invalid_protected_envelopes_are_rejected() {
        let mut protected = protect(b"nonsecret corruption fixture").unwrap();
        let last = protected.len() - 1;
        protected[last] ^= 0x80;
        let error = unprotect(&protected).unwrap_err();
        assert!(error.downcast_ref::<ProtectionError>().is_some());
        let error = unprotect(b"not a Windows DPAPI envelope").unwrap_err();
        assert!(error.downcast_ref::<ProtectionError>().is_some());
    }

    #[test]
    fn empty_and_oversize_input_is_rejected_before_calling_windows() {
        assert!(protect(&[]).is_err());
        assert!(unprotect(&[]).is_err());
        assert!(checked_length(MAX_PLAINTEXT_BYTES + 1, MAX_PLAINTEXT_BYTES).is_err());
        assert!(checked_length(MAX_PROTECTED_BYTES + 1, MAX_PROTECTED_BYTES).is_err());
        if let Some(too_large) = (DWORD::MAX as usize).checked_add(1) {
            assert!(checked_length(too_large, usize::MAX).is_err());
        }
    }

    #[test]
    fn null_and_empty_output_is_rejected_without_forming_a_slice() {
        let empty = DpapiBuffer(DATA_BLOB {
            cbData: 0,
            pbData: ptr::null_mut(),
        });
        assert!(empty.copy(MAX_PLAINTEXT_BYTES).is_err());
        let invalid = DpapiBuffer(DATA_BLOB {
            cbData: 1,
            pbData: ptr::null_mut(),
        });
        assert!(invalid.copy(MAX_PLAINTEXT_BYTES).is_err());
    }

    #[test]
    fn windows_error_keeps_numeric_code_and_safe_recovery_advice() {
        let error = ProtectionError {
            operation: "CryptProtectData",
            action: "protect",
            os_error: io::Error::from_raw_os_error(5),
            profile_diagnostics: None,
        };
        let detail = error.to_string();
        assert!(detail.contains("CryptProtectData"));
        assert!(detail.contains("Windows error 5 / 0x00000005"));
        assert!(detail.contains("normal signed-in Windows account"));
        assert!(detail.contains("Do not delete saved activation files"));
    }

    #[test]
    fn missing_path_error_explains_profile_recovery_without_exposing_credentials() {
        let error = ProtectionError {
            operation: "CryptProtectData",
            action: "protect",
            os_error: io::Error::from_raw_os_error(3),
            profile_diagnostics: Some("Current Windows profile: fixture path (missing).".into()),
        };
        let text = error.to_string();
        assert!(text.contains("Windows error 3 / 0x00000003"));
        assert!(text.contains("AppData is redirected"));
        assert!(text.contains("same Windows account"));
        assert!(text.contains("fixture path (missing)"));
    }

    #[test]
    fn writable_preflight_leaves_existing_files_unchanged_and_removes_probe() {
        let directory = TestDirectory::new();
        let existing = directory.0.join("existing-fixture.bin");
        fs::write(&existing, b"existing nonsecret fixture").unwrap();
        preflight(&directory.0).unwrap();
        assert_eq!(fs::read(&existing).unwrap(), b"existing nonsecret fixture");
        assert_eq!(fs::read_dir(&directory.0).unwrap().count(), 1);
    }

    #[test]
    fn blocked_directory_preflight_preserves_the_blocking_file() {
        let directory = TestDirectory::new();
        let blocked = directory.0.join("blocked-by-fixture.bin");
        fs::write(&blocked, b"nonsecret blocking fixture").unwrap();
        assert!(preflight(&blocked).is_err());
        assert_eq!(fs::read(&blocked).unwrap(), b"nonsecret blocking fixture");
        assert_eq!(fs::read_dir(&directory.0).unwrap().count(), 1);
    }

    #[test]
    fn probe_guard_cleans_up_after_an_early_failure() {
        let directory = TestDirectory::new();
        let probe = ProbeFile::reserve(&directory.0).unwrap();
        let path = probe.path().to_path_buf();
        let result: Result<()> = (move || {
            let _owned_probe = probe;
            bail!("simulated readback failure")
        })();
        assert!(result.is_err());
        assert!(!path.exists());
        assert_eq!(fs::read_dir(&directory.0).unwrap().count(), 0);
    }

    #[test]
    fn relative_preflight_path_is_rejected_without_creating_a_directory() {
        assert!(preflight(Path::new("relative-storage-probe")).is_err());
    }
}
