//! Resolve this signed-in Windows user's AppData through the shell API.
//! Environment variables and other C:\Users profiles are never fallback roots.

use anyhow::{Context, Result, bail};
use std::ffi::OsString;
use std::fmt;
use std::fs;
use std::io;
use std::os::windows::ffi::OsStringExt;
use std::path::{Path, PathBuf};
use std::ptr;
use winapi::shared::guiddef::GUID;
use winapi::um::combaseapi::CoTaskMemFree;
use winapi::um::knownfolders::{FOLDERID_LocalAppData, FOLDERID_RoamingAppData};
use winapi::um::shlobj::{KF_FLAG_CREATE, KF_FLAG_DONT_VERIFY, SHGetKnownFolderPath};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Folder {
    Local,
    Roaming,
}

impl Folder {
    fn id(self) -> &'static GUID {
        match self {
            Self::Local => &FOLDERID_LocalAppData,
            Self::Roaming => &FOLDERID_RoamingAppData,
        }
    }

    fn name(self) -> &'static str {
        match self {
            Self::Local => "Local AppData",
            Self::Roaming => "Roaming AppData",
        }
    }
}

#[derive(Debug)]
struct KnownFolderError {
    folder: Folder,
    create: bool,
    hresult: u32,
}

impl fmt::Display for KnownFolderError {
    fn fmt(&self, output: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            output,
            "Windows could not {} this account's {} folder (SHGetKnownFolderPath, HRESULT 0x{:08X}",
            if self.create { "prepare" } else { "locate" },
            self.folder.name(),
            self.hresult
        )?;
        if self.hresult & 0xFFFF_0000 == 0x8007_0000 {
            let code = (self.hresult & 0xFFFF) as i32;
            write!(
                output,
                ", Windows error {code}: {}",
                io::Error::from_raw_os_error(code)
            )?;
        }
        write!(output, ")")
    }
}

impl std::error::Error for KnownFolderError {}

/// SHGetKnownFolderPath requires CoTaskMemFree even for a failed HRESULT.
struct ShellPath(*mut u16);

impl Drop for ShellPath {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe { CoTaskMemFree(self.0.cast()) };
        }
    }
}

fn path_from_wide(value: &[u16]) -> Result<PathBuf> {
    if value.is_empty() || value.contains(&0) {
        bail!("Windows returned an empty or invalid AppData path");
    }
    let path = PathBuf::from(OsString::from_wide(value));
    if !path.is_absolute() {
        bail!("Windows returned an AppData path that is not absolute");
    }
    Ok(path)
}

fn native_folder(folder: Folder, create: bool) -> Result<PathBuf> {
    let mut output = ShellPath(ptr::null_mut());
    // NULL token means the current user. Do not request default paths: the
    // configured redirected AppData location must remain authoritative.
    let result = unsafe {
        SHGetKnownFolderPath(
            folder.id(),
            if create {
                KF_FLAG_CREATE
            } else {
                KF_FLAG_DONT_VERIFY
            },
            ptr::null_mut(),
            &mut output.0,
        )
    };
    if result < 0 {
        return Err(KnownFolderError {
            folder,
            create,
            hresult: result as u32,
        }
        .into());
    }
    if output.0.is_null() {
        bail!(
            "Windows returned no path for this account's {} folder",
            folder.name()
        );
    }
    // The API guarantees a NUL-terminated UTF-16 allocation. Limit scanning to
    // the Win32 extended-path bound; retain ownership until the path is copied.
    let mut length = 0;
    while length < 32_768 && unsafe { *output.0.add(length) } != 0 {
        length += 1;
    }
    if length == 32_768 {
        bail!("Windows returned an oversized AppData path");
    }
    path_from_wide(unsafe { std::slice::from_raw_parts(output.0, length) })
}

/// Resolve without creating anything. DONT_VERIFY also returns a configured
/// missing folder so first activation can prepare it before using DPAPI.
pub(crate) fn current_local_app_data() -> Result<PathBuf> {
    native_folder(Folder::Local, false)
}

/// Use the same current-user authority for launcher discovery and activation
/// recovery. Inherited APPDATA may refer to a different or obsolete profile.
pub(crate) fn current_roaming_app_data() -> Result<PathBuf> {
    native_folder(Folder::Roaming, false)
}

#[derive(Debug)]
pub(crate) struct CurrentUserFolders {
    pub(crate) local_app_data: PathBuf,
    pub(crate) roaming_app_data: PathBuf,
}

fn require_directory(path: &Path, folder: Folder) -> Result<()> {
    if !path.is_absolute() {
        bail!("Windows returned a relative {} folder", folder.name());
    }
    let metadata = fs::metadata(path).with_context(|| {
        format!(
            "This account's {} folder is unavailable: {}",
            folder.name(),
            path.display()
        )
    })?;
    if !metadata.is_dir() {
        bail!(
            "This account's {} path is not a directory: {}",
            folder.name(),
            path.display()
        );
    }
    Ok(())
}

fn prepare_with(
    mut resolve: impl FnMut(Folder, bool) -> Result<PathBuf>,
) -> Result<CurrentUserFolders> {
    let local_app_data = resolve(Folder::Local, true)?;
    require_directory(&local_app_data, Folder::Local)?;
    let roaming_app_data = resolve(Folder::Roaming, true)?;
    require_directory(&roaming_app_data, Folder::Roaming)?;
    Ok(CurrentUserFolders {
        local_app_data,
        roaming_app_data,
    })
}

/// The only call path using KF_FLAG_CREATE. Windows applies each known
/// folder's predefined security. We do not create or inspect DPAPI key files.
pub(crate) fn prepare_current_user_folders() -> Result<CurrentUserFolders> {
    prepare_with(native_folder).with_context(|| {
        format!(
            "Windows could not prepare this account's AppData folders before activation. {}",
            current_profile_diagnostics()
        )
    })
}

fn path_status(path: &Path) -> String {
    let status = match fs::metadata(path) {
        Ok(metadata) if metadata.is_dir() => "directory exists".to_owned(),
        Ok(_) => "exists but is not a directory".to_owned(),
        Err(error) if error.kind() == io::ErrorKind::NotFound => "missing".to_owned(),
        Err(error) => format!("unavailable: {error}"),
    };
    format!("{} ({status})", path.display())
}

/// Diagnostic text contains only configured folder paths and metadata status.
/// The expected Protect root is checked as a directory; nothing is enumerated,
/// read, created, copied, or removed below it.
pub(crate) fn current_profile_diagnostics() -> String {
    let local = match native_folder(Folder::Local, false) {
        Ok(path) => path_status(&path),
        Err(error) => format!("unavailable: {error}"),
    };
    let (roaming, protect) = match native_folder(Folder::Roaming, false) {
        Ok(path) => (
            path_status(&path),
            path_status(&path.join("Microsoft").join("Protect")),
        ),
        Err(error) => (format!("unavailable: {error}"), "unresolved".to_owned()),
    };
    format!(
        "Current Windows profile: Local AppData: {local}; Roaming AppData: {roaming}; expected Protect root: {protect}."
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use rand::{RngCore, rngs::OsRng};

    struct FixtureDirectory(PathBuf);
    impl FixtureDirectory {
        fn new() -> Self {
            let mut random = [0u8; 16];
            OsRng.fill_bytes(&mut random);
            let suffix: String = random.iter().map(|byte| format!("{byte:02x}")).collect();
            let path = std::env::temp_dir().join(format!("celeste-profile-fixture-{suffix}"));
            fs::create_dir(&path).unwrap();
            Self(path)
        }
    }
    impl Drop for FixtureDirectory {
        fn drop(&mut self) {
            // Fixtures create only these explicit direct children.
            let _ = fs::remove_file(self.0.join("blocked"));
            let _ = fs::remove_dir(&self.0);
        }
    }

    #[test]
    fn current_user_lookup_returns_an_absolute_configured_path_without_creation() {
        let path = current_local_app_data().unwrap();
        assert!(path.is_absolute());
        assert!(current_roaming_app_data().unwrap().is_absolute());
    }

    #[test]
    fn redirected_unicode_and_nested_profile_paths_are_preserved_exactly() {
        for raw in [
            r"D:\Profile data\Róaming 用户",
            r"C:\Users\DESKTOP-QJDBQ2D$\Casual\AppData\Local",
            r"\\profile-server\User shares\Casual\AppData\Roaming",
        ] {
            let wide = raw.encode_utf16().collect::<Vec<_>>();
            assert_eq!(path_from_wide(&wide).unwrap(), PathBuf::from(raw));
        }
    }

    #[test]
    fn invalid_native_path_values_are_rejected() {
        for value in [Vec::new(), "C:relative".encode_utf16().collect(), vec![0]] {
            assert!(path_from_wide(&value).is_err());
        }
        let unicode: Vec<_> = "C:\\Users\\Example \\AppData\\Local"
            .encode_utf16()
            .collect();
        assert!(path_from_wide(&unicode).unwrap().is_absolute());
    }

    #[test]
    fn preparation_requests_only_current_known_folders_and_creation() {
        let fixture = FixtureDirectory::new();
        let mut calls = Vec::new();
        let result = prepare_with(|folder, create| {
            calls.push((folder, create));
            Ok(fixture.0.clone())
        })
        .unwrap();
        assert_eq!(calls, vec![(Folder::Local, true), (Folder::Roaming, true)]);
        assert_eq!(result.local_app_data, fixture.0);
        assert_eq!(result.roaming_app_data, fixture.0);
    }

    #[test]
    fn missing_folder_fails_without_manually_creating_it() {
        let fixture = FixtureDirectory::new();
        let missing = fixture.0.join("missing");
        assert!(prepare_with(|_, _| Ok(missing.clone())).is_err());
        assert!(!missing.exists());
        assert!(path_status(&missing).contains("missing"));
    }

    #[test]
    fn a_file_in_place_of_a_known_folder_fails_and_is_preserved() {
        let fixture = FixtureDirectory::new();
        let blocked = fixture.0.join("blocked");
        fs::write(&blocked, b"nonsecret profile fixture").unwrap();
        assert!(prepare_with(|_, _| Ok(blocked.clone())).is_err());
        assert_eq!(fs::read(&blocked).unwrap(), b"nonsecret profile fixture");
        assert!(path_status(&blocked).contains("not a directory"));
    }

    #[test]
    fn native_failure_preserves_hresult_and_stops_preparation() {
        let mut calls = 0;
        let error = prepare_with(|folder, create| {
            calls += 1;
            Err(KnownFolderError {
                folder,
                create,
                hresult: 0x8007_0003,
            }
            .into())
        })
        .unwrap_err();
        assert_eq!(calls, 1);
        assert!(error.to_string().contains("HRESULT 0x80070003"));
        assert!(error.to_string().contains("Windows error 3"));
    }
}
