use anyhow::{Context, Result, anyhow};
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

static NEXT_TEMP: AtomicU64 = AtomicU64::new(0);

struct PendingFile(PathBuf);

impl Drop for PendingFile {
    fn drop(&mut self) {
        // Only our create_new temporary file may be removed. Never unlink the
        // destination: an interrupted or rejected replacement must preserve it.
        let _ = fs::remove_file(&self.0);
    }
}

pub(crate) fn atomic_write(path: &Path, value: &[u8]) -> Result<()> {
    let name = path
        .file_name()
        .ok_or_else(|| anyhow!("Output path has no file name"))?;
    let parent = path
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    fs::create_dir_all(parent).context("Could not create output directory")?;
    let (pending, mut file) = create_pending(parent, name)?;
    let result = (|| -> io::Result<()> {
        file.write_all(value)?;
        file.flush()?;
        file.sync_all()
    })();
    // Windows replacement requires closing our own temporary-file handle.
    drop(file);
    result.context("Could not persist temporary output")?;
    replace(&pending.0, path).context("Could not replace output; previous file was retained")?;
    Ok(())
}

fn create_pending(parent: &Path, name: &std::ffi::OsStr) -> Result<(PendingFile, File)> {
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    for _ in 0..128 {
        let sequence = NEXT_TEMP.fetch_add(1, Ordering::Relaxed);
        let mut pending_name = std::ffi::OsString::from(".");
        pending_name.push(name);
        pending_name.push(format!(
            ".{}-{stamp}-{sequence}.pending",
            std::process::id()
        ));
        let pending = parent.join(pending_name);
        match OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&pending)
        {
            Ok(file) => return Ok((PendingFile(pending), file)),
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(error).context("Could not create temporary output"),
        }
    }
    Err(anyhow!("Could not reserve a unique temporary output"))
}

#[cfg(windows)]
fn replace(source: &Path, destination: &Path) -> io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use winapi::um::winbase::{MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH, MoveFileExW};

    fn wide(path: &Path) -> io::Result<Vec<u16>> {
        let mut value: Vec<u16> = path.as_os_str().encode_wide().collect();
        if value.contains(&0) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Path contains a null character",
            ));
        }
        value.push(0);
        Ok(value)
    }

    // std::fs accepts long Windows paths; the raw Win32 rename needs their
    // canonical verbatim spelling too. Preserve the exact destination name.
    let source = wide(&source.canonicalize()?)?;
    let parent = destination
        .parent()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "Destination has no parent"))?
        .canonicalize()?;
    let destination = wide(&parent.join(destination.file_name().ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, "Destination has no filename")
    })?))?;
    // Same-directory rename; no copy/delete fallback or prior target removal.
    let result = unsafe {
        MoveFileExW(
            source.as_ptr(),
            destination.as_ptr(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    };
    if result == 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(())
    }
}

#[cfg(not(windows))]
fn replace(source: &Path, destination: &Path) -> io::Result<()> {
    fs::rename(source, destination)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct TestDirectory(PathBuf);

    impl TestDirectory {
        fn new() -> Self {
            let stamp = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos();
            for _ in 0..128 {
                let sequence = NEXT_TEMP.fetch_add(1, Ordering::Relaxed);
                let path = std::env::temp_dir().join(format!(
                    "celeste-atomic-store-test-{}-{stamp}-{sequence}",
                    std::process::id()
                ));
                match fs::create_dir(&path) {
                    Ok(()) => return Self(path),
                    Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                    Err(error) => panic!("Could not create test directory: {error}"),
                }
            }
            panic!("Could not reserve a test directory");
        }

        fn assert_only_target(&self, target: &Path) {
            let entries: Vec<_> = fs::read_dir(&self.0)
                .unwrap()
                .map(|entry| entry.unwrap().path())
                .collect();
            assert_eq!(entries, vec![target.to_path_buf()]);
        }
    }

    impl Drop for TestDirectory {
        fn drop(&mut self) {
            // Tests create only direct files in their uniquely owned directory;
            // cleanup neither recurses nor follows any directory links.
            if let Ok(entries) = fs::read_dir(&self.0) {
                for entry in entries.flatten() {
                    let _ = fs::remove_file(entry.path());
                }
            }
            let _ = fs::remove_dir(&self.0);
        }
    }

    #[test]
    fn initial_write_and_replacement_leave_complete_file() {
        let directory = TestDirectory::new();
        let target = directory.0.join("device.bin");
        atomic_write(&target, b"original protected credential").unwrap();
        assert_eq!(fs::read(&target).unwrap(), b"original protected credential");
        atomic_write(&target, b"replacement protected credential").unwrap();
        assert_eq!(
            fs::read(&target).unwrap(),
            b"replacement protected credential"
        );
        directory.assert_only_target(&target);
    }

    #[cfg(windows)]
    #[test]
    fn denied_replacement_preserves_existing_file_and_cleans_temporary() {
        use std::os::windows::fs::OpenOptionsExt;
        use winapi::um::winnt::FILE_SHARE_READ;

        let directory = TestDirectory::new();
        let target = directory.0.join("device.bin");
        fs::write(&target, b"keep this protected credential").unwrap();
        // Deny delete sharing, so MoveFileExW cannot replace this file.
        let locked = OpenOptions::new()
            .read(true)
            .share_mode(FILE_SHARE_READ)
            .open(&target)
            .unwrap();
        assert!(atomic_write(&target, b"must not replace").is_err());
        assert_eq!(
            fs::read(&target).unwrap(),
            b"keep this protected credential"
        );
        directory.assert_only_target(&target);
        drop(locked);
        atomic_write(&target, b"replacement after unlock").unwrap();
        assert_eq!(fs::read(&target).unwrap(), b"replacement after unlock");
        directory.assert_only_target(&target);
    }
    #[cfg(windows)]
    #[test]
    fn long_temporary_name_keeps_atomic_replacement_working() {
        let directory = TestDirectory::new();
        let target = directory.0.join(format!("{}.backup", "x".repeat(170)));
        atomic_write(&target, b"old").unwrap();
        atomic_write(&target, b"new").unwrap();
        assert_eq!(fs::read(&target).unwrap(), b"new");
        directory.assert_only_target(&target);
    }
}
