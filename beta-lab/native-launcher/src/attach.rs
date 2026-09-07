use crate::platform::{MinecraftProcess, recheck_process};
use aes_gcm::{
    Aes256Gcm, KeyInit, Nonce,
    aead::{Aead, Payload},
};
use anyhow::{Context, Result, anyhow, bail};
use rand::RngCore;
use serde::Deserialize;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::ffi::OsString;
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::os::windows::ffi::{OsStrExt, OsStringExt};
use std::os::windows::fs::OpenOptionsExt;
use std::os::windows::process::CommandExt;
use std::path::{Component, Path, PathBuf, Prefix};
use std::process::{Command, Stdio};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};
use winapi::um::winnt::FILE_SHARE_READ;

#[derive(Clone)]
pub struct AttachContext {
    pub target: MinecraftProcess,
    pub java: PathBuf,
    pub agent: PathBuf,
    pub support: PathBuf,
    pub game: PathBuf,
    pub release: String,
    pub catalog_sha256: String,
    pub sha256: Vec<u8>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Report {
    pub phase: String,
    #[serde(default)]
    pub release: String,
    #[serde(default)]
    pub error: String,
    #[serde(default)]
    pub game_directory: String,
    #[serde(default)]
    pub minecraft: String,
    #[serde(default)]
    pub celeste_loaded: String,
    #[serde(default)]
    pub pid: String,
    #[serde(default)]
    pub started_at: String,
}

// Capability never enters a file, command line, Debug report, or UI state.
#[derive(Clone, Deserialize)]
#[serde(deny_unknown_fields)]
struct ResidentEndpoint {
    schema: u32,
    port: u16,
    pid: u64,
    started: u64,
    digest: String,
    key: String,
}

static RESIDENT: OnceLock<Mutex<HashMap<String, ResidentEndpoint>>> = OnceLock::new();

fn unhex(value: &str) -> Result<Vec<u8>> {
    if value.len() != 64
        || !value
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
    {
        bail!("Invalid resident control identity");
    }
    (0..64)
        .step_by(2)
        .map(|i| u8::from_str_radix(&value[i..i + 2], 16).map_err(Into::into))
        .collect()
}

fn resident_key(context: &AttachContext) -> String {
    format!(
        "{}:{}:{:x}",
        context.target.pid,
        context.target.started,
        Sha256::digest(&context.sha256)
    )
}

fn validate_endpoint(context: &AttachContext, endpoint: &ResidentEndpoint) -> Result<Vec<u8>> {
    let started = context
        .target
        .started
        .checked_sub(116_444_736_000_000_000)
        .ok_or_else(|| anyhow!("Invalid selected process creation time"))?
        / 10_000;
    if endpoint.schema != 1
        || endpoint.port == 0
        || endpoint.pid != u64::from(context.target.pid)
        || endpoint.started != started
        || unhex(&endpoint.digest)? != context.sha256
    {
        bail!("Resident control does not match the selected process and signed bootstrap");
    }
    unhex(&endpoint.key)
}

fn control_aad(
    endpoint: &ResidentEndpoint,
    direction: u8,
    challenge: &[u8; 32],
) -> Result<Vec<u8>> {
    let mut aad = Vec::with_capacity(81);
    aad.extend(endpoint.pid.to_be_bytes());
    aad.extend(endpoint.started.to_be_bytes());
    aad.extend(unhex(&endpoint.digest)?);
    aad.push(direction);
    aad.extend(challenge);
    Ok(aad)
}

fn send_sealed(
    output: &mut impl Write,
    cipher: &Aes256Gcm,
    aad: &[u8],
    plain: &[u8],
) -> Result<()> {
    if plain.is_empty() || plain.len() > 65_536 {
        bail!("Resident request exceeds bounds");
    }
    let mut nonce = [0; 12];
    rand::thread_rng().fill_bytes(&mut nonce);
    let encrypted = cipher
        .encrypt(Nonce::from_slice(&nonce), Payload { msg: plain, aad })
        .map_err(|_| anyhow!("Could not protect resident request"))?;
    output.write_all(&((12 + encrypted.len()) as u32).to_be_bytes())?;
    output.write_all(&nonce)?;
    output.write_all(&encrypted)?;
    output.flush()?;
    Ok(())
}

fn read_sealed(input: &mut impl Read, cipher: &Aes256Gcm, aad: &[u8]) -> Result<Vec<u8>> {
    let mut length = [0; 4];
    input.read_exact(&mut length)?;
    let length = u32::from_be_bytes(length) as usize;
    if !(29..=65_564).contains(&length) {
        bail!("Resident response exceeds bounds");
    }
    let mut frame = vec![0; length];
    input.read_exact(&mut frame)?;
    cipher
        .decrypt(
            Nonce::from_slice(&frame[..12]),
            Payload {
                msg: &frame[12..],
                aad,
            },
        )
        .map_err(|_| anyhow!("Resident response authentication failed"))
}

struct ControlIo {
    socket: TcpStream,
    deadline: Instant,
}
impl ControlIo {
    fn remaining(&self) -> std::io::Result<Duration> {
        self.deadline
            .checked_duration_since(Instant::now())
            .filter(|d| !d.is_zero())
            .ok_or_else(|| {
                std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    "Resident control deadline elapsed",
                )
            })
    }
}
impl Read for ControlIo {
    fn read(&mut self, bytes: &mut [u8]) -> std::io::Result<usize> {
        self.socket.set_read_timeout(Some(self.remaining()?))?;
        self.socket.read(bytes)
    }
}
impl Write for ControlIo {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        self.socket.set_write_timeout(Some(self.remaining()?))?;
        self.socket.write(bytes)
    }
    fn flush(&mut self) -> std::io::Result<()> {
        self.socket.flush()
    }
}

fn resident_command(
    context: &AttachContext,
    endpoint: &ResidentEndpoint,
    request: &[u8],
) -> Result<Vec<u8>> {
    let mut key = validate_endpoint(context, endpoint)?;
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| anyhow!("Invalid resident key"))?;
    key.fill(0);
    let socket = TcpStream::connect_timeout(
        &SocketAddr::from((Ipv4Addr::LOCALHOST, endpoint.port)),
        Duration::from_secs(3),
    )
    .context("Resident control is unavailable; no replacement agent was attached")?;
    let mut socket = ControlIo {
        socket,
        deadline: Instant::now() + Duration::from_secs(5),
    };
    let mut challenge = [0; 32];
    rand::thread_rng().fill_bytes(&mut challenge);
    socket.write_all(&challenge)?;
    let proof = read_sealed(&mut socket, &cipher, &control_aad(endpoint, 1, &challenge)?)?;
    if proof.len() != 64 || proof[..32] != challenge {
        bail!("Resident endpoint challenge does not match");
    }
    let round: [u8; 32] = Sha256::digest(&proof).into();
    // Authenticate the resident process before sending an installer grant.
    socket.deadline = Instant::now() + Duration::from_secs(180);
    send_sealed(
        &mut socket,
        &cipher,
        &control_aad(endpoint, 2, &round)?,
        request,
    )?;
    read_sealed(&mut socket, &cipher, &control_aad(endpoint, 3, &round)?)
}

fn parse_report(context: &AttachContext, request: &Value, output: &[u8]) -> Result<Report> {
    let mut report: Report = serde_json::from_slice(output)
        .context("Resident control did not return a valid JSON status")?;
    if !report.release.is_empty() && report.release != context.release {
        bail!(
            "Runtime reported a different release than this process's pinned beta. Restart Minecraft before changing releases"
        );
    }
    if let Some(secret) = request.get("installer").and_then(Value::as_str) {
        report.error = report.error.replace(secret, "[redacted installer grant]");
    }
    if ![
        "inspected",
        "inactive",
        "starting",
        "active",
        "stopping",
        "stopped",
        "failed",
        "restart required",
    ]
    .contains(&report.phase.as_str())
    {
        bail!("Resident control returned an unsupported phase; runtime readiness is unknown");
    }
    Ok(report)
}

pub fn verify_retry_artifact(
    context: &AttachContext,
    signed_sha256: &[u8],
    signed_release: &str,
    signed_catalog_sha256: &str,
) -> Result<()> {
    if context.sha256 != signed_sha256
        || context.release != signed_release
        || context.catalog_sha256 != signed_catalog_sha256
    {
        bail!(
            "Signed beta agent or release changed. Restart Minecraft before attaching the new release; the resident agent was not replaced"
        );
    }
    Ok(())
}

pub fn start_request(context: &AttachContext, installer: &str, device: &str, uid: u64) -> Value {
    serde_json::json!({
        "action":"start", "installer":installer, "device":device, "uid":uid,
        "support_directory":context.support, "expected_game_directory":context.game,
        "expected_release":context.release, "expected_catalog_sha256":context.catalog_sha256,
    })
}

pub fn verify_inspection(context: &AttachContext, report: &Report) -> Result<()> {
    if report.phase != "inspected" {
        bail!(
            "Minecraft inspection failed: {} {}",
            report.phase,
            report.error
        );
    }
    let observed = fs::canonicalize(&report.game_directory)
        .context("Agent did not report an accessible canonical Minecraft directory")?;
    if observed != context.game {
        bail!(
            "Selected profile and running Minecraft directory differ.\nSelected: {}\nRunning Minecraft: {}\nOpen a fresh launcher and choose the profile for the running folder. No runtime credential was copied and no feature hooks were installed",
            context.game.display(),
            observed.display()
        );
    }
    if report.minecraft != "1.21.11" || report.celeste_loaded != "false" {
        bail!(
            "Celeste+ requires Minecraft 1.21.11 without an already loaded Celeste client; the selected instance was not changed"
        );
    }
    let started_ms = context
        .target
        .started
        .checked_sub(116_444_736_000_000_000)
        .ok_or_else(|| anyhow!("Invalid Windows process creation time"))?
        / 10_000;
    if report.pid != context.target.pid.to_string() || report.started_at != started_ms.to_string() {
        bail!("Minecraft inspection identity differs from the explicitly selected process");
    }
    Ok(())
}

fn bounded_read(input: &mut impl Read) -> Result<Vec<u8>> {
    let mut bytes = Vec::new();
    input.take(64 * 1024 * 1024 + 1).read_to_end(&mut bytes)?;
    if bytes.is_empty() || bytes.len() > 64 * 1024 * 1024 {
        bail!("Bootstrap agent exceeds size bounds");
    }
    Ok(bytes)
}

fn read_output(mut input: impl Read) -> std::io::Result<Vec<u8>> {
    let mut bytes = Vec::new();
    input.by_ref().take(65_537).read_to_end(&mut bytes)?;
    Ok(bytes)
}

// Rust canonical Windows paths use the verbatim namespace. Java 21's -jar
// loader cannot resolve its Main-Class through that spelling, although NIO can.
// Keep canonical paths for trust checks; convert only at the helper boundary.
fn java_path_spelling(path: &Path) -> Result<PathBuf> {
    if !path.is_absolute() {
        bail!("Java helper path must be absolute");
    }
    let wide: Vec<u16> = path.as_os_str().encode_wide().collect();
    let prefix = match path.components().next() {
        Some(Component::Prefix(prefix)) => prefix.kind(),
        _ => bail!("Java helper path needs a Windows disk or UNC root"),
    };
    for component in path.components() {
        if let Component::Normal(name) = component {
            let units: Vec<u16> = name.encode_wide().collect();
            if units.iter().any(|c| *c == 0 || *c == b':' as u16)
                || matches!(units.last(), Some(32 | 46))
            {
                bail!("Java helper path contains an ambiguous Windows name");
            }
            let base: Vec<u16> = units
                .iter()
                .copied()
                .take_while(|c| *c != b'.' as u16)
                .map(|c| if (97..=122).contains(&c) { c - 32 } else { c })
                .collect();
            let reserved = ["CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"]
                .iter()
                .any(|name| base.iter().copied().eq(name.encode_utf16()));
            let port = base.len() == 4
                && (&base[..3] == [67, 79, 77] || &base[..3] == [76, 80, 84])
                && matches!(base[3], 49..=57 | 0xb9 | 0xb2 | 0xb3);
            if reserved || port {
                bail!("Java helper path contains a reserved Windows name");
            }
        }
    }
    let ordinary = match prefix {
        Prefix::VerbatimDisk(_) => wide[4..].to_vec(),
        Prefix::VerbatimUNC(_, _) => [vec![92, 92], wide[8..].to_vec()].concat(),
        Prefix::Disk(_) | Prefix::UNC(_, _) => wide,
        _ => bail!("Java helper does not support Windows device namespace paths"),
    };
    Ok(PathBuf::from(OsString::from_wide(&ordinary)))
}

fn helper_command(context: &AttachContext) -> Result<Command> {
    let agent = java_path_spelling(&context.agent)?;
    let original = fs::canonicalize(&context.agent).context("Cannot resolve the verified agent")?;
    if fs::canonicalize(&agent).context("Java cannot resolve the verified agent path")? != original
    {
        bail!("Java helper path resolves to a different agent");
    }
    let started_ms = context
        .target
        .started
        .checked_sub(116_444_736_000_000_000)
        .ok_or_else(|| anyhow!("Invalid selected process creation time"))?
        / 10_000;
    let mut command = Command::new(&context.java);
    command
        .args(["--add-modules", "jdk.attach", "-jar"])
        .arg(&agent)
        .arg("--pid")
        .arg(context.target.pid.to_string())
        .arg("--agent")
        .arg(&agent)
        .arg("--started-at")
        .arg(started_ms.to_string())
        .creation_flags(0x08000000); // CREATE_NO_WINDOW
    Ok(command)
}

pub fn command(context: &AttachContext, request: Value) -> Result<Report> {
    recheck_process(&context.target)?;
    let mut resident = RESIDENT
        .get_or_init(|| Mutex::new(HashMap::new()))
        .lock()
        .map_err(|_| anyhow!("Resident control state is unavailable"))?;
    let cache_key = resident_key(context);
    let mut request_bytes = serde_json::to_vec(&request)?;
    if request_bytes.is_empty() || request_bytes.len() > 65_536 {
        bail!("Beta request exceeds protocol bounds");
    }
    if let Some(endpoint) = resident.get(&cache_key) {
        let response = resident_command(context, endpoint, &request_bytes);
        request_bytes.fill(0);
        return parse_report(context, &request, &response?);
    }
    // Prevent concurrent replacement while the helper JVM reads this verified A.
    let mut lock = OpenOptions::new()
        .read(true)
        .share_mode(FILE_SHARE_READ)
        .open(&context.agent)?;
    if Sha256::digest(bounded_read(&mut lock)?).as_slice() != context.sha256 {
        bail!(
            "Local bootstrap agent changed after signature verification; it will not be executed"
        );
    }
    let deadline = Instant::now() + Duration::from_secs(200);
    let mut child = helper_command(context)?
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .context("Could not start the Java 21 Attach API helper")?;
    let mut stdin = child
        .stdin
        .take()
        .ok_or_else(|| anyhow!("Attach helper stdin unavailable"))?;
    // Concurrent stdin keeps the overall deadline effective even when Java never
    // reads a request larger than the pipe buffer. EOF completes AttachCli's read.
    let input_thread = std::thread::spawn(move || {
        let result = stdin.write_all(&request_bytes);
        request_bytes.fill(0);
        drop(stdin);
        result
    });
    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| anyhow!("Attach helper stdout unavailable"))?;
    let stderr = child
        .stderr
        .take()
        .ok_or_else(|| anyhow!("Attach helper stderr unavailable"))?;
    let output_thread = std::thread::spawn(move || read_output(stdout));
    let error_thread = std::thread::spawn(move || read_output(stderr));
    let status = loop {
        let polled = match child.try_wait() {
            Ok(polled) => polled,
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                let _ = input_thread.join();
                let _ = output_thread.join();
                let _ = error_thread.join();
                return Err(error).context("Could not observe the owned Java attach helper");
            }
        };
        match polled {
            Some(status) => break status,
            None if Instant::now() >= deadline => {
                // Kill only our helper, never Minecraft. Feature state is unknown.
                let _ = child.kill();
                let _ = child.wait();
                let _ = input_thread.join();
                let _ = output_thread.join();
                let _ = error_thread.join();
                bail!(
                    "Java attach helper timed out. Minecraft was not terminated; use Status or restart the game before another attempt"
                );
            }
            None => std::thread::sleep(Duration::from_millis(100)),
        }
    };
    let write_result = input_thread.join();
    let output = output_thread
        .join()
        .map_err(|_| anyhow!("Attach output reader failed"))??;
    let errors = error_thread
        .join()
        .map_err(|_| anyhow!("Attach error reader failed"))??;
    if output.len() > 65_536 || errors.len() > 65_536 {
        bail!("Attach helper output exceeds bounds");
    }
    if !status.success() {
        let mut detail = String::from_utf8_lossy(&errors).trim().to_owned();
        if let Some(secret) = request.get("installer").and_then(Value::as_str) {
            detail = detail.replace(secret, "[redacted installer grant]");
        }
        if detail.is_empty() {
            detail = format!("helper exit {status}");
        }
        bail!(
            "Attach API failed: {}",
            detail.chars().take(2200).collect::<String>()
        );
    }
    write_result
        .map_err(|_| anyhow!("Attach input writer failed"))?
        .context("Attach helper rejected the private stdin request")?;
    let mut envelope: Value = serde_json::from_slice(&output)
        .context("Attach helper did not return a valid JSON status")?;
    if let Some(value) = envelope
        .as_object_mut()
        .and_then(|value| value.remove("_control"))
    {
        let endpoint: ResidentEndpoint = serde_json::from_value(value)
            .context("Attach helper returned invalid resident control metadata")?;
        validate_endpoint(context, &endpoint)?;
        resident.insert(cache_key, endpoint);
    } else {
        bail!(
            "Verified bootstrap did not provide resident control; restart Minecraft with the current beta launcher"
        );
    }
    parse_report(context, &request, &serde_json::to_vec(&envelope)?)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn resident_frames_authenticate_key_identity_direction_and_bounds() {
        let cipher = Aes256Gcm::new_from_slice(&[7; 32]).unwrap();
        let mut frame = Vec::new();
        let aad = [4; 81];
        send_sealed(&mut frame, &cipher, &aad, b"fixture request").unwrap();
        assert_eq!(
            read_sealed(&mut std::io::Cursor::new(&frame), &cipher, &aad).unwrap(),
            b"fixture request"
        );
        let mut changed = aad;
        changed[48] ^= 1;
        assert!(read_sealed(&mut std::io::Cursor::new(&frame), &cipher, &changed).is_err());
        let wrong = Aes256Gcm::new_from_slice(&[8; 32]).unwrap();
        assert!(read_sealed(&mut std::io::Cursor::new(&frame), &wrong, &aad).is_err());
        assert!(
            read_sealed(
                &mut std::io::Cursor::new(&frame[..frame.len() - 1]),
                &cipher,
                &aad
            )
            .is_err()
        );
        assert!(
            read_sealed(
                &mut std::io::Cursor::new(65_565u32.to_be_bytes()),
                &cipher,
                &aad
            )
            .is_err()
        );
        assert!(send_sealed(&mut Vec::new(), &cipher, &aad, b"").is_err());
        assert!(send_sealed(&mut Vec::new(), &cipher, &aad, &vec![0; 65_537]).is_err());
    }

    #[test]
    #[ignore = "requires explicit Java resident-control fixture classpath; no game or Attach API"]
    fn actual_java_resident_control_interoperates_without_attaching() {
        use std::io::BufRead;
        struct Fixture(std::process::Child);
        impl Drop for Fixture {
            fn drop(&mut self) {
                let _ = self.0.kill();
                let _ = self.0.wait();
            }
        }
        let java = std::env::var_os("CELESTE_CONTROL_TEST_JAVA").expect("fixture Java required");
        let cp =
            std::env::var_os("CELESTE_CONTROL_TEST_CLASSPATH").expect("fixture classpath required");
        let mut child = Fixture(
            Command::new(java)
                .args(["-Xverify:all", "-cp"])
                .arg(cp)
                .args(["dev.celestebeta.attach.ResidentControlCheck", "bridge"])
                .creation_flags(0x08000000)
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()
                .unwrap(),
        );
        let mut line = String::new();
        std::io::BufReader::new(child.0.stdout.take().unwrap())
            .read_line(&mut line)
            .unwrap();
        let endpoint: ResidentEndpoint =
            serde_json::from_str(&line).expect("valid private fixture descriptor");
        line.clear();
        let context = AttachContext {
            target: MinecraftProcess {
                pid: child.0.id(),
                title: "owned non-Minecraft fixture".into(),
                started: 116_444_736_000_000_000 + endpoint.started * 10_000,
            },
            java: PathBuf::new(),
            agent: PathBuf::new(),
            support: PathBuf::new(),
            game: PathBuf::new(),
            release: "fixture".into(),
            catalog_sha256: String::new(),
            sha256: vec![0xbb; 32],
        };
        for action in ["inspect", "start", "status", "stop"] {
            let request = serde_json::json!({"action":action});
            let response =
                resident_command(&context, &endpoint, &serde_json::to_vec(&request).unwrap())
                    .unwrap();
            assert_eq!(
                parse_report(&context, &request, &response).unwrap().phase,
                "inactive"
            );
        }
        let mut wrong = endpoint.clone();
        wrong.pid += 1;
        assert!(validate_endpoint(&context, &wrong).is_err());
        let mut wrong = endpoint.clone();
        wrong.started += 1;
        assert!(validate_endpoint(&context, &wrong).is_err());
        let mut wrong = endpoint.clone();
        wrong.digest = "a".repeat(64);
        assert!(validate_endpoint(&context, &wrong).is_err());
        let mut wrong = endpoint.clone();
        wrong.key = "a".repeat(64);
        assert!(resident_command(&context, &wrong, b"{\"action\":\"status\"}").is_err());
        child.0.stdin.take().unwrap().write_all(b"\n").unwrap();
        assert!(child.0.wait().unwrap().success());
    }
    #[test]
    fn java_paths_preserve_disk_unc_spaces_and_unicode_without_loss() {
        for (input, expected) in [
            (
                r"\\?\C:\Users\space name\bootstrap-a.jar",
                r"C:\Users\space name\bootstrap-a.jar",
            ),
            (
                r"\\?\UNC\server\share\folder name\bootstrap-a.jar",
                r"\\server\share\folder name\bootstrap-a.jar",
            ),
            (
                r"C:\already ordinary\bootstrap-a.jar",
                r"C:\already ordinary\bootstrap-a.jar",
            ),
            (
                r"\\server\share\bootstrap-a.jar",
                r"\\server\share\bootstrap-a.jar",
            ),
            (
                concat!(
                    r"\\?\C:\",
                    "\u{65e5}\u{672c}\u{8a9e} \u{3a9} caf\u{e9}",
                    r"\bootstrap-a.jar"
                ),
                concat!(
                    r"C:\",
                    "\u{65e5}\u{672c}\u{8a9e} \u{3a9} caf\u{e9}",
                    r"\bootstrap-a.jar"
                ),
            ),
        ] {
            assert_eq!(
                java_path_spelling(Path::new(input)).unwrap(),
                PathBuf::from(expected)
            );
        }
        let long = format!(r"C:\{}\bootstrap-a.jar", "long path segment\\".repeat(30));
        assert_eq!(
            java_path_spelling(Path::new(&format!(r"\\?\{long}"))).unwrap(),
            PathBuf::from(long)
        );
    }

    #[test]
    fn java_paths_reject_relative_device_and_ambiguous_names() {
        for path in [
            r"relative.jar",
            r"C:relative.jar",
            r"\\.\C:\agent.jar",
            r"\\?\Volume{fixture}\agent.jar",
            r"\\?\C:\name.\agent.jar",
            r"\\?\C:\name \agent.jar",
            r"\\?\C:\agent.jar:stream",
            r"\\?\C:\NUL\agent.jar",
            r"\\?\C:\com1.txt\agent.jar",
            concat!(r"\\?\C:\LPT", "\u{b2}", r"\agent.jar"),
        ] {
            assert!(
                java_path_spelling(Path::new(path)).is_err(),
                "accepted {path}"
            );
        }
    }

    #[test]
    #[ignore = "requires explicit Java and verified bootstrap-A fixture paths; no process is attached"]
    fn actual_helper_command_loads_main_before_rejecting_zero_pid() {
        let java = PathBuf::from(
            std::env::var_os("CELESTE_ATTACH_TEST_JAVA").expect("test Java required"),
        );
        let agent = fs::canonicalize(
            std::env::var_os("CELESTE_ATTACH_TEST_JAR").expect("test JAR required"),
        )
        .unwrap();
        let context = AttachContext {
            target: MinecraftProcess {
                pid: 0,
                title: "no target".into(),
                started: 116_444_736_000_010_000,
            },
            java,
            agent,
            support: PathBuf::new(),
            game: PathBuf::new(),
            release: "no release".into(),
            catalog_sha256: String::new(),
            sha256: vec![],
        };
        let mut command = helper_command(&context).unwrap();
        let args: Vec<_> = command.get_args().map(|s| s.to_os_string()).collect();
        assert_eq!(args[3], args[7]);
        assert!(!args[3].to_string_lossy().starts_with(r"\\?\"));
        let output = command.stdin(Stdio::null()).output().unwrap();
        let error = String::from_utf8_lossy(&output.stderr);
        assert!(!output.status.success());
        assert!(
            error.contains("Select an external Minecraft process"),
            "{error}"
        );
        assert!(!error.contains("ClassNotFoundException"), "{error}");
    }

    #[test]
    fn starting_is_not_active_and_failed_retains_reason() {
        let starting: Report =
            serde_json::from_str(r#"{"phase":"starting","release":"test"}"#).unwrap();
        assert_ne!(starting.phase, "active");
        let failed: Report =
            serde_json::from_str(r#"{"phase":"failed","error":"authorization expired"}"#).unwrap();
        assert_eq!(failed.error, "authorization expired");
    }
    #[test]
    fn empty_agent_is_rejected() {
        assert!(bounded_read(&mut std::io::Cursor::new(Vec::<u8>::new())).is_err());
    }

    #[test]
    fn inspection_requires_exact_directory_process_version_and_unmodified_client() {
        let directory = std::env::temp_dir().join(format!(
            "celeste-beta-target-fixture-{}-{}",
            std::process::id(),
            rand::random::<u64>()
        ));
        fs::create_dir(&directory).unwrap();
        let other = directory.join("other");
        fs::create_dir(&other).unwrap();
        let game = fs::canonicalize(&directory).unwrap();
        let context = AttachContext {
            target: MinecraftProcess {
                pid: 7654,
                title: "Minecraft fixture".into(),
                started: 116_444_736_000_000_000 + 1_800_000_000_123 * 10_000,
            },
            java: PathBuf::new(),
            agent: PathBuf::new(),
            support: PathBuf::new(),
            game: game.clone(),
            release: "fixture".into(),
            catalog_sha256: "a".repeat(64),
            sha256: vec![0; 32],
        };
        let matching = Report {
            phase: "inspected".into(),
            release: String::new(),
            error: String::new(),
            game_directory: game.display().to_string(),
            minecraft: "1.21.11".into(),
            celeste_loaded: "false".into(),
            pid: "7654".into(),
            started_at: "1800000000123".into(),
        };
        assert!(verify_inspection(&context, &matching).is_ok());
        assert!(verify_retry_artifact(&context, &[0; 32], "fixture", &"a".repeat(64)).is_ok());
        assert!(verify_retry_artifact(&context, &[1; 32], "fixture", &"a".repeat(64)).is_err());
        assert!(
            verify_retry_artifact(&context, &[0; 32], "another-release", &"a".repeat(64)).is_err()
        );
        assert!(verify_retry_artifact(&context, &[0; 32], "fixture", &"b".repeat(64)).is_err());
        let request = start_request(&context, "fixture-installer-not-live", "fixture-device", 42);
        let fields: std::collections::BTreeSet<_> = request
            .as_object()
            .unwrap()
            .keys()
            .map(String::as_str)
            .collect();
        assert_eq!(
            fields,
            [
                "action",
                "installer",
                "device",
                "uid",
                "support_directory",
                "expected_game_directory",
                "expected_release",
                "expected_catalog_sha256"
            ]
            .into_iter()
            .collect()
        );
        assert_eq!(request["expected_release"], context.release);
        assert_eq!(request["expected_catalog_sha256"], context.catalog_sha256);
        let mut wrong = matching.clone();
        wrong.pid = "7655".into();
        assert!(verify_inspection(&context, &wrong).is_err());
        let mut wrong = matching.clone();
        wrong.started_at = "1800000000124".into();
        assert!(verify_inspection(&context, &wrong).is_err());
        let mut wrong = matching.clone();
        wrong.game_directory = other.display().to_string();
        let mismatch = verify_inspection(&context, &wrong).unwrap_err().to_string();
        assert!(mismatch.contains(&game.display().to_string()));
        assert!(mismatch.contains(&fs::canonicalize(&other).unwrap().display().to_string()));
        assert!(mismatch.contains("choose the profile"));
        let mut wrong = matching.clone();
        wrong.minecraft = "1.21.10".into();
        assert!(verify_inspection(&context, &wrong).is_err());
        let mut wrong = matching.clone();
        wrong.celeste_loaded = "true".into();
        assert!(verify_inspection(&context, &wrong).is_err());
        let mut wrong = matching;
        wrong.phase = "active".into();
        assert!(verify_inspection(&context, &wrong).is_err());
        fs::remove_dir(&other).unwrap();
        fs::remove_dir(&directory).unwrap();
    }
}
