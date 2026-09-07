use anyhow::{Context, Result, anyhow, bail};
use rusqlite::{Connection, OpenFlags};
use std::path::{Path, PathBuf};
use std::{env, fs};
use winapi::shared::minwindef::{BOOL, FILETIME, LPARAM, TRUE};
use winapi::shared::windef::HWND;
use winapi::um::handleapi::CloseHandle;
use winapi::um::processthreadsapi::{GetProcessTimes, OpenProcess};
use winapi::um::winbase::QueryFullProcessImageNameW;
use winapi::um::winnt::PROCESS_QUERY_LIMITED_INFORMATION;
use winapi::um::winuser::{
    EnumWindows, GetWindowTextLengthW, GetWindowTextW, GetWindowThreadProcessId, IsWindowVisible,
};

#[derive(Clone, Debug)]
pub struct MinecraftProcess {
    pub pid: u32,
    pub title: String,
    pub started: u64,
}

fn process_identity(pid: u32) -> Result<(String, u64)> {
    unsafe {
        let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if handle.is_null() {
            return Err(std::io::Error::last_os_error()).context("Cannot inspect selected process");
        }
        let result = (|| {
            let mut path = vec![0u16; 32_768];
            let mut length = path.len() as u32;
            if QueryFullProcessImageNameW(handle, 0, path.as_mut_ptr(), &mut length) == 0 {
                return Err(std::io::Error::last_os_error())
                    .context("Cannot identify Java executable");
            }
            let mut created: FILETIME = std::mem::zeroed();
            let mut exited: FILETIME = std::mem::zeroed();
            let mut kernel: FILETIME = std::mem::zeroed();
            let mut user: FILETIME = std::mem::zeroed();
            if GetProcessTimes(handle, &mut created, &mut exited, &mut kernel, &mut user) == 0 {
                return Err(std::io::Error::last_os_error())
                    .context("Cannot verify process creation time");
            }
            let full = String::from_utf16_lossy(&path[..length as usize]);
            let name = Path::new(&full)
                .file_name()
                .and_then(|v| v.to_str())
                .unwrap_or("")
                .to_ascii_lowercase();
            Ok((
                name,
                (u64::from(created.dwHighDateTime) << 32) | u64::from(created.dwLowDateTime),
            ))
        })();
        CloseHandle(handle);
        result
    }
}

/// Reads only visible titles, PID, executable basename and process creation time.
/// It never reads Java command lines, environment, launcher accounts or tokens.
pub fn minecraft_processes() -> Vec<MinecraftProcess> {
    unsafe extern "system" fn collect(window: HWND, data: LPARAM) -> BOOL {
        unsafe {
            if IsWindowVisible(window) == 0 {
                return TRUE;
            }
            let length = GetWindowTextLengthW(window);
            if !(1..=4096).contains(&length) {
                return TRUE;
            }
            let mut text = vec![0u16; length as usize + 1];
            let actual = GetWindowTextW(window, text.as_mut_ptr(), text.len() as i32);
            if actual <= 0 {
                return TRUE;
            }
            let title = String::from_utf16_lossy(&text[..actual as usize]);
            if !title.to_ascii_lowercase().contains("minecraft") {
                return TRUE;
            }
            let mut pid = 0;
            GetWindowThreadProcessId(window, &mut pid);
            if let Ok((name, started)) = process_identity(pid) {
                if name == "java.exe" || name == "javaw.exe" {
                    let values = &mut *(data as *mut Vec<MinecraftProcess>);
                    if !values.iter().any(|value| value.pid == pid) {
                        values.push(MinecraftProcess {
                            pid,
                            title,
                            started,
                        });
                    }
                }
            }
            TRUE
        }
    }
    let mut values: Vec<MinecraftProcess> = Vec::new();
    unsafe {
        EnumWindows(Some(collect), &mut values as *mut _ as LPARAM);
    }
    values.sort_by_key(|value| value.pid);
    values
}

pub fn recheck_process(target: &MinecraftProcess) -> Result<()> {
    let (name, started) = process_identity(target.pid)?;
    if (name != "java.exe" && name != "javaw.exe") || started != target.started {
        bail!(
            "Selected Minecraft exited or its PID was reused. Refresh and explicitly select it again"
        );
    }
    Ok(())
}

#[derive(Clone, Debug)]
pub struct GameProfile {
    pub name: String,
    pub game: PathBuf,
}

/// Queries only nonsecret profile metadata. Opens Modrinth SQLite read-only.
pub fn modrinth_profiles() -> Result<Vec<GameProfile>> {
    let data = env::var_os("APPDATA")
        .map(PathBuf::from)
        .filter(|value| value.is_absolute())
        .ok_or_else(|| anyhow!("Windows APPDATA is unavailable"))?
        .join("ModrinthApp");
    let database = data.join("app.db");
    if !database.is_file() {
        return Ok(Vec::new());
    }
    let db = Connection::open_with_flags(database, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    db.busy_timeout(std::time::Duration::from_secs(2))?;
    let custom: Option<String> = db
        .query_row("SELECT custom_dir FROM settings", [], |row| row.get(0))
        .unwrap_or(None);
    let directory = custom
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .unwrap_or(data);
    if !directory.is_absolute() {
        bail!("Modrinth profile directory is not absolute");
    }
    let mut query = db.prepare(
        "SELECT i.path, i.name, COALESCE(cs.game_version, ''), COALESCE(cs.loader, '')
         FROM instances i LEFT JOIN instance_content_sets cs
         ON cs.instance_id = i.id AND cs.id = i.applied_content_set_id",
    )?;
    let rows = query.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, String>(3)?,
        ))
    })?;
    let mut result = Vec::new();
    for row in rows {
        let (path, name, version, loader) = row?;
        if version != "1.21.11" || !loader.eq_ignore_ascii_case("fabric") {
            continue;
        }
        let path = PathBuf::from(path);
        let path = if path.is_absolute() {
            path
        } else {
            directory.join("profiles").join(path)
        };
        if path.is_dir() {
            result.push(GameProfile {
                name,
                game: fs::canonicalize(path)?,
            });
        }
    }
    Ok(result)
}

pub fn canonical_directory(value: &str) -> Result<PathBuf> {
    let path = Path::new(value.trim());
    if !path.is_absolute() || !path.is_dir() {
        bail!("Select an existing absolute game directory");
    }
    fs::canonicalize(path).context("Cannot resolve the selected game directory")
}

pub fn validate_jdk(root: &Path) -> Result<PathBuf> {
    let java = root.join("bin").join("java.exe");
    if !java.is_file() || !root.join("jmods").join("jdk.attach.jmod").is_file() {
        bail!("Select a full Java 21 JDK with jdk.attach; a Java runtime alone is insufficient");
    }
    let metadata = fs::read_to_string(root.join("release"))?;
    let version = metadata
        .lines()
        .find_map(|line| line.strip_prefix("JAVA_VERSION="))
        .unwrap_or("")
        .trim_matches('"');
    if version != "21" && !version.starts_with("21.") && !version.starts_with("21+") {
        bail!("Selected attach JDK is not Java 21");
    }
    fs::canonicalize(java).context("Cannot resolve Java 21 attach executable")
}

pub fn find_jdk() -> Option<PathBuf> {
    let mut candidates = Vec::new();
    if let Some(path) = env::var_os("JAVA_HOME") {
        candidates.push(PathBuf::from(path));
    }
    for program in [env::var_os("ProgramFiles"), env::var_os("LOCALAPPDATA")] {
        let Some(program) = program.map(PathBuf::from) else {
            continue;
        };
        for vendor in [
            "Eclipse Adoptium",
            "Java",
            "Microsoft",
            "Programs\\Eclipse Adoptium",
        ] {
            if let Ok(entries) = fs::read_dir(program.join(vendor)) {
                candidates.extend(
                    entries
                        .flatten()
                        .map(|entry| entry.path())
                        .filter(|path| path.is_dir()),
                );
            }
        }
    }
    candidates
        .into_iter()
        .find(|path| validate_jdk(path).is_ok())
}

pub fn support_directory(pid: u32, started: u64) -> Result<PathBuf> {
    let root = env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .filter(|path| path.is_absolute())
        .ok_or_else(|| anyhow!("Windows LOCALAPPDATA is unavailable"))?
        .join("Celeste")
        .join("Plus")
        .join(format!("{pid}-{started}"));
    fs::create_dir_all(&root).context("Cannot create the beta agent/support directory")?;
    fs::canonicalize(root).context("Cannot resolve the beta agent/support directory")
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn relative_game_directories_are_rejected() {
        assert!(canonical_directory(".").is_err());
        assert!(canonical_directory("config").is_err());
    }
    #[test]
    fn missing_jdk_is_rejected_without_launching_any_process() {
        assert!(validate_jdk(Path::new("nonexistent-jdk-fixture")).is_err());
    }
}
