use anyhow::{Context, Result, bail};
use rusqlite::{Connection, OpenFlags, OptionalExtension};
use std::path::{Path, PathBuf};
use std::{env, fs};
#[cfg(windows)]
#[path = "process_windows.rs"]
mod process;
#[cfg(unix)]
#[path = "process_unix.rs"]
mod process;
pub use process::minecraft_processes;
use process::process_identity;

#[derive(Clone, Debug)]
pub struct MinecraftProcess {
    pub pid: u32,
    pub title: String,
    /// ProcessHandle-compatible Unix epoch milliseconds (not FILETIME or seconds).
    pub started: u64,
}

/// Convert Windows FILETIME once at discovery; all other code uses Unix milliseconds.
#[cfg(any(windows, test))]
fn windows_start_millis(value: u64) -> Result<u64> {
    value
        .checked_sub(116_444_736_000_000_000)
        .map(|ticks| ticks / 10_000)
        .filter(|millis| *millis > 0)
        .ok_or_else(|| anyhow::anyhow!("Invalid process creation time"))
}

pub fn recheck_process(target: &MinecraftProcess) -> Result<()> {
    let (name, started) = process_identity(target.pid)?;
    let is_java = if cfg!(windows) {
        name == "java.exe" || name == "javaw.exe"
    } else {
        name == "java"
    };
    if !is_java || started != target.started {
        bail!(
            "Selected Minecraft exited or its PID was reused. Refresh and explicitly select it again"
        );
    }
    Ok(())
}

pub(crate) fn process_is_definitely_gone(pid: u32, started: u64) -> bool {
    if pid == 0 || started == 0 {
        return false;
    }
    match process_identity(pid) {
        Ok((_, current)) => current != started,
        Err(error) => error.downcast_ref::<std::io::Error>().is_some_and(|error| {
            error.kind() == std::io::ErrorKind::NotFound
                || (cfg!(windows) && error.raw_os_error() == Some(87))
        }),
    }
}

#[derive(Clone, Debug)]
pub struct GameProfile {
    pub name: String,
    pub game: PathBuf,
}

/// Queries only nonsecret profile metadata. Opens Modrinth SQLite read-only.
pub fn modrinth_profiles() -> Result<Vec<GameProfile>> {
    #[cfg(windows)]
    let base = crate::windows_profile::current_roaming_app_data()?;
    #[cfg(unix)]
    let base = crate::user_profile::current_local_app_data()?;
    modrinth_profiles_under(&base)
}

fn modrinth_profiles_under(roaming: &Path) -> Result<Vec<GameProfile>> {
    if !roaming.is_absolute() {
        bail!("Modrinth discovery requires this account's absolute application-data path");
    }
    let data = roaming.join("ModrinthApp");
    let database = data.join("app.db");
    match fs::metadata(&database) {
        Ok(metadata) if metadata.is_file() => {}
        Ok(_) => bail!("Modrinth's profile database path is not a file"),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => {
            return Err(error).context("Cannot access this account's Modrinth profile database");
        }
    }
    let db = Connection::open_with_flags(database, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    db.busy_timeout(std::time::Duration::from_secs(2))?;
    let custom: Option<String> = db
        .query_row("SELECT custom_dir FROM settings", [], |row| row.get(0))
        .optional()
        .context("Cannot read Modrinth's configured profile location")?
        .flatten();
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
    let root = jdk_home(root);
    if !root.is_absolute() {
        bail!("Select an absolute Java 21 JDK location");
    }
    let java = root
        .join("bin")
        .join(if cfg!(windows) { "java.exe" } else { "java" });
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

fn jdk_home(root: &Path) -> PathBuf {
    if root.join("Contents/Home/release").is_file() {
        root.join("Contents/Home")
    } else {
        root.to_path_buf()
    }
}

pub fn find_jdk() -> Option<PathBuf> {
    let mut candidates = Vec::new();
    if let Some(path) = env::var_os("JAVA_HOME") {
        candidates.push(PathBuf::from(path));
    }
    #[cfg(windows)]
    for program in [
        env::var_os("ProgramFiles").map(PathBuf::from),
        crate::windows_profile::current_local_app_data().ok(),
    ] {
        let Some(program) = program.filter(|path| path.is_absolute()) else {
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
    #[cfg(unix)]
    {
        let mut roots = vec![
            PathBuf::from("/usr/lib/jvm"),
            PathBuf::from("/Library/Java/JavaVirtualMachines"),
        ];
        if let Some(home) = env::var_os("HOME")
            .map(PathBuf::from)
            .filter(|path| path.is_absolute())
        {
            roots.push(home.join("Library/Java/JavaVirtualMachines"));
            roots.push(home.join(".jdks"));
            roots.push(home.join(".sdkman/candidates/java"));
        }
        for root in roots {
            if let Ok(entries) = fs::read_dir(root) {
                candidates.extend(
                    entries
                        .flatten()
                        .map(|entry| entry.path())
                        .filter(|path| path.is_dir()),
                );
            }
        }
        // Resolve symlinks only; never execute an arbitrary PATH candidate during discovery.
        if let Some(path) = env::var_os("PATH") {
            for directory in env::split_paths(&path).filter(|path| path.is_absolute()) {
                if let Ok(java) = fs::canonicalize(directory.join("java")) {
                    if let Some(root) = java.parent().and_then(Path::parent) {
                        candidates.push(root.to_path_buf());
                    }
                }
            }
        }
    }
    candidates
        .into_iter()
        .map(|path| jdk_home(&path))
        .find(|path| validate_jdk(path).is_ok())
}

pub fn support_directory(pid: u32, started: u64) -> Result<PathBuf> {
    let root = crate::user_profile::current_local_app_data()?
        .join("Celeste")
        .join("Plus")
        .join(format!("{pid}-{started}"));
    #[cfg(windows)]
    fs::create_dir_all(&root).context("Cannot create the beta agent/support directory")?;
    #[cfg(unix)]
    crate::user_profile::ensure_private_activation_dir(&root)?;
    fs::canonicalize(root).context("Cannot resolve the beta agent/support directory")
}

#[cfg(test)]
mod tests {
    use super::*;
    use rand::{RngCore, rngs::OsRng};

    #[test]
    fn windows_filetime_is_normalized_to_java_epoch_milliseconds() {
        assert_eq!(
            windows_start_millis(116_444_736_000_000_000 + 1800000000123 * 10000 + 9999).unwrap(),
            1800000000123
        );
        assert!(windows_start_millis(0).is_err());
        assert!(windows_start_millis(116_444_736_000_000_000).is_err());
    }

    #[test]
    fn native_process_identity_matches_java_21_without_attaching() {
        use std::io::{BufRead, Write};
        use std::process::{Command, Stdio};
        use std::sync::mpsc;
        use std::time::Duration;
        struct OwnedChild(std::process::Child);
        impl Drop for OwnedChild {
            fn drop(&mut self) {
                let _ = self.0.kill();
                let _ = self.0.wait();
            }
        }
        // Java 21 is a build/test prerequisite, not a downloaded or arbitrary executable.
        let jdk = find_jdk().expect("Java 21 JDK is required for the process identity regression");
        let mut command = Command::new(validate_jdk(&jdk).unwrap());
        command.arg(Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/ProcessIdentityProbe.java"));
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x08000000);
        }
        let mut child = OwnedChild(
            command
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::null())
                .spawn()
                .unwrap(),
        );
        let output = child.0.stdout.take().unwrap();
        let (tx, rx) = mpsc::channel();
        let reader = std::thread::spawn(move || {
            let mut line = String::new();
            let result = std::io::BufReader::new(output)
                .read_line(&mut line)
                .map(|_| line);
            let _ = tx.send(result);
        });
        let line = rx
            .recv_timeout(Duration::from_secs(30))
            .expect("Java identity fixture timed out")
            .unwrap();
        reader.join().unwrap();
        let reported = line.trim().parse::<u64>().unwrap();
        let (_, native_started) = process_identity(child.0.id()).unwrap();
        assert_eq!(
            native_started, reported,
            "native identity must exactly match ProcessHandle"
        );
        let mut selected = MinecraftProcess {
            pid: child.0.id(),
            title: "owned fixture".into(),
            started: reported,
        };
        assert!(recheck_process(&selected).is_ok());
        selected.started += 1;
        assert!(recheck_process(&selected).is_err());
        child.0.stdin.take().unwrap().write_all(b"\n").unwrap();
        assert!(child.0.wait().unwrap().success());
    }

    struct ProfileFixture {
        root: PathBuf,
        data: PathBuf,
        game: PathBuf,
    }

    impl ProfileFixture {
        fn new() -> Self {
            let mut random = [0u8; 16];
            OsRng.fill_bytes(&mut random);
            let suffix = random
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<String>();
            let root = std::env::temp_dir().join(format!("celeste-profile-paths-{suffix}"));
            fs::create_dir(&root).unwrap();
            let data = root.join("ModrinthApp");
            let game = root.join("Minecraft games 用户");
            fs::create_dir(&data).unwrap();
            fs::create_dir(&game).unwrap();
            Self { root, data, game }
        }
    }

    impl Drop for ProfileFixture {
        fn drop(&mut self) {
            // Only explicit files/directories created by this fixture. No profile
            // enumeration, credential reads, recursive deletion, or live database.
            let _ = fs::remove_file(self.data.join("app.db"));
            let _ = fs::remove_dir(&self.data);
            let _ = fs::remove_dir(&self.game);
            let _ = fs::remove_dir(&self.root);
        }
    }

    #[test]
    fn modrinth_discovery_uses_supplied_known_folder_and_separate_custom_game_root() {
        let fixture = ProfileFixture::new();
        let db = Connection::open(fixture.data.join("app.db")).unwrap();
        db.execute_batch(
            "CREATE TABLE settings (custom_dir TEXT);
             CREATE TABLE instances (id TEXT, path TEXT, name TEXT, applied_content_set_id TEXT);
             CREATE TABLE instance_content_sets (id TEXT, instance_id TEXT, game_version TEXT, loader TEXT);",
        ).unwrap();
        db.execute(
            "INSERT INTO settings VALUES (?1)",
            [fixture.root.to_str().unwrap()],
        )
        .unwrap();
        db.execute(
            "INSERT INTO instances VALUES ('one', ?1, 'Fixture profile', 'content')",
            [fixture.game.to_str().unwrap()],
        )
        .unwrap();
        db.execute_batch(
            "INSERT INTO instance_content_sets VALUES ('content', 'one', '1.21.11', 'fabric');",
        )
        .unwrap();
        drop(db);
        let before = fs::read(fixture.data.join("app.db")).unwrap();
        let profiles = modrinth_profiles_under(&fixture.root).unwrap();
        assert_eq!(profiles.len(), 1);
        assert_eq!(profiles[0].name, "Fixture profile");
        assert_eq!(profiles[0].game, fixture.game.canonicalize().unwrap());
        assert_eq!(fs::read(fixture.data.join("app.db")).unwrap(), before);
    }

    #[test]
    fn missing_modrinth_database_is_optional_and_never_created() {
        let fixture = ProfileFixture::new();
        assert!(modrinth_profiles_under(&fixture.root).unwrap().is_empty());
        assert!(!fixture.data.join("app.db").exists());
        assert!(modrinth_profiles_under(Path::new("relative-profile")).is_err());
    }

    #[test]
    fn corrupt_modrinth_settings_do_not_silently_select_the_default_location() {
        let fixture = ProfileFixture::new();
        let db = Connection::open(fixture.data.join("app.db")).unwrap();
        db.execute_batch("CREATE TABLE settings (unexpected_column TEXT);")
            .unwrap();
        drop(db);
        let error = modrinth_profiles_under(&fixture.root).unwrap_err();
        assert!(error.to_string().contains("configured profile location"));
    }

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
