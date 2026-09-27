//! The current account's application-data root for the standard Celeste launcher.
//! Java's bootstrap resolves the same paths when it reads the shared activation.

use anyhow::Result;
#[cfg(unix)]
use anyhow::bail;
#[cfg(unix)]
use std::path::Path;
use std::path::PathBuf;

#[cfg(unix)]
pub(crate) fn ensure_private_activation_dir(directory: &Path) -> Result<()> {
    use anyhow::Context;
    use std::fs;
    use std::os::unix::fs::{DirBuilderExt, PermissionsExt};

    if !directory.is_absolute() {
        bail!("Activation storage requires an absolute account directory");
    }
    let mut builder = fs::DirBuilder::new();
    builder.recursive(true).mode(0o700);
    builder
        .create(directory)
        .context("Could not create the private activation directory")?;
    let metadata = fs::symlink_metadata(directory)
        .context("Could not inspect the private activation directory")?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        bail!("The activation path must be a private directory, not a link or file");
    }
    fs::set_permissions(directory, fs::Permissions::from_mode(0o700))
        .context("Could not restrict access to the activation directory")
}

#[cfg(windows)]
pub(crate) fn current_local_app_data() -> Result<PathBuf> {
    crate::windows_profile::current_local_app_data()
}

#[cfg(any(target_os = "macos", target_os = "linux"))]
fn current_home() -> Result<PathBuf> {
    let home = std::env::var_os("HOME")
        .filter(|value| !value.is_empty())
        .ok_or_else(|| anyhow::anyhow!("This account's HOME is unavailable"))?;
    let path = PathBuf::from(home);
    if !path.is_absolute() {
        bail!("This account's HOME is not an absolute path");
    }
    Ok(path)
}

#[cfg(target_os = "macos")]
pub(crate) fn current_local_app_data() -> Result<PathBuf> {
    Ok(current_home()?.join("Library").join("Application Support"))
}

#[cfg(target_os = "linux")]
pub(crate) fn current_local_app_data() -> Result<PathBuf> {
    if let Some(xdg) = std::env::var_os("XDG_DATA_HOME").filter(|value| !value.is_empty()) {
        let path = PathBuf::from(xdg);
        if path.is_absolute() {
            return Ok(path);
        }
        // The XDG specification ignores relative values.
    }
    Ok(current_home()?.join(".local").join("share"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn application_data_path_is_absolute() {
        assert!(current_local_app_data().unwrap().is_absolute());
    }
}
