//! Current-user credential protection for the standard macOS/Linux launcher.
//! A random AES key lives in Keychain or Secret Service, never beside the JAR.

use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use anyhow::{Context, Result, bail};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use rand::{RngCore, rngs::OsRng};
use std::fs::{self, OpenOptions};
use std::io;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};

const ENVELOPE: &[u8; 4] = b"CSP2";
const AAD: &[u8] = b"Celeste normal credential v2";
fn service() -> &'static str {
    "dev.c4lam.celeste.normal"
}

fn account() -> &'static str {
    "a39fbbf8e5c344edb39ee82d"
}
const MAX_PLAINTEXT_BYTES: usize = 1024 * 1024;
const NONCE_BYTES: usize = 12;
const TAG_BYTES: usize = 16;

/// A protected-store error has no key, token, or credential in its message.
#[derive(Debug)]
pub(crate) struct ProtectionError {
    operation: &'static str,
    detail: &'static str,
}

impl std::fmt::Display for ProtectionError {
    fn fmt(&self, output: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            output,
            "Could not {} this account's Celeste credential: {}",
            self.operation, self.detail
        )
    }
}

impl std::error::Error for ProtectionError {}

fn protection_error(operation: &'static str, detail: &'static str) -> anyhow::Error {
    ProtectionError { operation, detail }.into()
}

pub(crate) fn protect(value: &[u8]) -> Result<Vec<u8>> {
    if value.is_empty() || value.len() > MAX_PLAINTEXT_BYTES {
        bail!("Credential data is empty or exceeds the supported size bounds");
    }
    let key = current_key(true)?;
    let mut nonce = [0u8; NONCE_BYTES];
    OsRng
        .try_fill_bytes(&mut nonce)
        .context("System randomness is unavailable for credential protection")?;
    seal_with_key(value, &key, &nonce)
}

fn seal_with_key(value: &[u8], key: &[u8; 32], nonce: &[u8; NONCE_BYTES]) -> Result<Vec<u8>> {
    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|_| protection_error("protect", "invalid account key"))?;
    let sealed = cipher
        .encrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: value,
                aad: AAD,
            },
        )
        .map_err(|_| protection_error("protect", "encryption failed"))?;
    let mut result = Vec::with_capacity(ENVELOPE.len() + NONCE_BYTES + sealed.len());
    result.extend_from_slice(ENVELOPE);
    result.extend_from_slice(nonce);
    result.extend_from_slice(&sealed);
    Ok(result)
}

pub(crate) fn unprotect(value: &[u8]) -> Result<Vec<u8>> {
    if value.len() < ENVELOPE.len() + NONCE_BYTES + TAG_BYTES + 1
        || value.len() > ENVELOPE.len() + NONCE_BYTES + TAG_BYTES + MAX_PLAINTEXT_BYTES
        || !value.starts_with(ENVELOPE)
    {
        return Err(protection_error("open", "invalid protected envelope"));
    }
    // A missing OS key must never be silently replaced while opening existing data.
    let key = current_key(false)?;
    open_with_key(value, &key)
}

fn open_with_key(value: &[u8], key: &[u8; 32]) -> Result<Vec<u8>> {
    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|_| protection_error("open", "invalid account key"))?;
    let offset = ENVELOPE.len() + NONCE_BYTES;
    cipher
        .decrypt(
            Nonce::from_slice(&value[ENVELOPE.len()..offset]),
            Payload {
                msg: &value[offset..],
                aad: AAD,
            },
        )
        .map_err(|_| {
            protection_error(
                "open",
                "the activation belongs to another account or was damaged",
            )
        })
}

fn current_key(create: bool) -> Result<[u8; 32]> {
    if let Some(key) = keyring::load()? {
        return Ok(key);
    }
    if !create {
        return Err(protection_error(
            "open",
            "the account key is missing from the system password store",
        ));
    }
    let mut new_key = [0u8; 32];
    OsRng
        .try_fill_bytes(&mut new_key)
        .context("System randomness is unavailable for the account key")?;
    keyring::store(&new_key)?;
    // Use the value the OS retained, including if another launcher created it.
    keyring::load()?.ok_or_else(|| {
        protection_error(
            "protect",
            "the system password store did not retain the account key",
        )
    })
}

#[cfg(target_os = "macos")]
mod keyring {
    use super::*;
    use std::ffi::c_void;
    use std::ptr;

    const ITEM_NOT_FOUND: i32 = -25300;
    const DUPLICATE_ITEM: i32 = -25299;

    #[link(name = "Security", kind = "framework")]
    unsafe extern "C" {
        fn SecKeychainFindGenericPassword(
            keychain: *mut c_void,
            service_len: u32,
            service: *const u8,
            account_len: u32,
            account: *const u8,
            password_len: *mut u32,
            password: *mut *mut c_void,
            item: *mut *mut c_void,
        ) -> i32;
        fn SecKeychainAddGenericPassword(
            keychain: *mut c_void,
            service_len: u32,
            service: *const u8,
            account_len: u32,
            account: *const u8,
            password_len: u32,
            password: *const u8,
            item: *mut *mut c_void,
        ) -> i32;
        fn SecKeychainItemFreeContent(attributes: *mut c_void, data: *mut c_void) -> i32;
    }

    pub(super) fn load() -> Result<Option<[u8; 32]>> {
        let mut length = 0u32;
        let mut data = ptr::null_mut();
        let status = unsafe {
            SecKeychainFindGenericPassword(
                ptr::null_mut(),
                service().len() as u32,
                service().as_ptr(),
                account().len() as u32,
                account().as_ptr(),
                &mut length,
                &mut data,
                ptr::null_mut(),
            )
        };
        if status == ITEM_NOT_FOUND {
            return Ok(None);
        }
        if status != 0 {
            return Err(protection_error(
                "read",
                "macOS Keychain is unavailable or locked",
            ));
        }
        if data.is_null() || length != 43 {
            if !data.is_null() {
                unsafe {
                    SecKeychainItemFreeContent(ptr::null_mut(), data);
                }
            }
            return Err(protection_error(
                "read",
                "macOS Keychain returned an invalid account key",
            ));
        }
        let encoded = unsafe {
            let copy = std::slice::from_raw_parts(data.cast::<u8>(), length as usize).to_vec();
            SecKeychainItemFreeContent(ptr::null_mut(), data);
            copy
        };
        let bytes = URL_SAFE_NO_PAD.decode(encoded).map_err(|_| {
            protection_error("read", "macOS Keychain returned an invalid account key")
        })?;
        let key: [u8; 32] = bytes.try_into().map_err(|_| {
            protection_error("read", "macOS Keychain returned an invalid account key")
        })?;
        Ok(Some(key))
    }

    pub(super) fn store(key: &[u8; 32]) -> Result<()> {
        let encoded = URL_SAFE_NO_PAD.encode(key);
        let status = unsafe {
            SecKeychainAddGenericPassword(
                ptr::null_mut(),
                service().len() as u32,
                service().as_ptr(),
                account().len() as u32,
                account().as_ptr(),
                encoded.len() as u32,
                encoded.as_ptr(),
                ptr::null_mut(),
            )
        };
        if status == 0 || status == DUPLICATE_ITEM {
            Ok(())
        } else {
            Err(protection_error(
                "save",
                "macOS Keychain is unavailable or locked",
            ))
        }
    }
}

/// Test the same fixed, OS-owned reader used by Java while enrollment is still
/// reversible. Native Keychain access alone does not grant that reader access.
#[cfg(target_os = "macos")]
pub(crate) fn validate_runtime_reader() -> Result<()> {
    let expected = macos_reader::Secret(current_key(false)?);
    for timeout in [
        std::time::Duration::from_secs(120),
        std::time::Duration::from_secs(5),
    ] {
        let output = macos_reader::read(&mut macos_reader::command(), timeout).map_err(|_| {
            protection_error(
                "verify runtime access to",
                "macOS Keychain access was denied or timed out. Unlock your login keychain and approve access to the Celeste item. Choose Always Allow if you want Minecraft to read it without asking again, then retry. No new device was enrolled",
            )
        })?;
        macos_reader::matches_key(&output.0, &expected.0)?;
    }
    Ok(())
}

// Linux test builds exercise only synthetic subprocesses; production Linux
// continues using the unchanged Secret Service implementation below.
#[cfg(any(target_os = "macos", all(test, target_os = "linux")))]
mod macos_reader {
    use super::*;
    use std::io::Read;
    use std::os::fd::AsRawFd;
    use std::process::{Child, Command, Stdio};
    use std::sync::atomic::{Ordering, compiler_fence};
    use std::time::{Duration, Instant};

    const OUTPUT_LIMIT: usize = 64;
    const F_GETFL: i32 = 3;
    const F_SETFL: i32 = 4;
    #[cfg(target_os = "macos")]
    const O_NONBLOCK: i32 = 0x4;
    #[cfg(target_os = "linux")]
    const O_NONBLOCK: i32 = 0x800;
    unsafe extern "C" {
        fn fcntl(fd: i32, command: i32, ...) -> i32;
    }

    pub(super) struct Secret<T: AsMut<[u8]>>(pub(super) T);
    impl<T: AsMut<[u8]>> Drop for Secret<T> {
        fn drop(&mut self) {
            for byte in self.0.as_mut() {
                // Do not optimize away clearing command output or key buffers.
                unsafe { std::ptr::write_volatile(byte, 0) };
            }
            compiler_fence(Ordering::SeqCst);
        }
    }

    struct PendingProcess(Option<Child>);
    impl Drop for PendingProcess {
        fn drop(&mut self) {
            if let Some(mut child) = self.0.take() {
                let _ = child.kill();
                let _ = std::thread::Builder::new()
                    .name("Celeste Keychain reader cleanup".into())
                    .spawn(move || {
                        let _ = child.wait();
                    });
            }
        }
    }

    pub(super) fn command() -> Command {
        let mut command = Command::new("/usr/bin/security");
        command.args([
            "find-generic-password",
            "-s",
            service(),
            "-a",
            account(),
            "-w",
        ]);
        command
    }

    pub(super) fn read(command: &mut Command, timeout: Duration) -> io::Result<Secret<Vec<u8>>> {
        let deadline = Instant::now()
            .checked_add(timeout)
            .ok_or_else(|| io::Error::other("Invalid Keychain reader deadline"))?;
        let mut process = PendingProcess(Some(
            command
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::null())
                .spawn()?,
        ));
        let child = process.0.as_mut().expect("owned reader");
        let mut stdout = child
            .stdout
            .take()
            .ok_or_else(|| io::Error::other("Missing Keychain reader output"))?;
        let flags = unsafe { fcntl(stdout.as_raw_fd(), F_GETFL) };
        if flags < 0 || unsafe { fcntl(stdout.as_raw_fd(), F_SETFL, flags | O_NONBLOCK) } < 0 {
            return Err(io::Error::last_os_error());
        }
        let mut output = Secret(Vec::with_capacity(OUTPUT_LIMIT));
        let mut buffer = Secret([0u8; OUTPUT_LIMIT]);
        let mut eof = false;
        let mut status = None;
        loop {
            if Instant::now() >= deadline {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "Keychain reader timed out",
                ));
            }
            // One bounded read per turn: even continuous output cannot postpone
            // the deadline check. No shell, stdin secret, or diagnostic capture.
            if !eof {
                match stdout.read(&mut buffer.0) {
                    Ok(0) => eof = true,
                    Ok(length) => {
                        if output.0.len() + length > OUTPUT_LIMIT {
                            return Err(io::Error::other(
                                "Keychain reader output exceeds its limit",
                            ));
                        }
                        output.0.extend_from_slice(&buffer.0[..length]);
                        buffer.0.fill(0);
                    }
                    Err(error)
                        if matches!(
                            error.kind(),
                            io::ErrorKind::WouldBlock | io::ErrorKind::Interrupted
                        ) => {}
                    Err(error) => return Err(error),
                }
            }
            if status.is_none() {
                status = child.try_wait()?;
            }
            if let Some(status) = status {
                if eof {
                    process.0 = None; // try_wait already reaped the child.
                    if !status.success() {
                        return Err(io::Error::other("Keychain reader denied access"));
                    }
                    return Ok(output);
                }
            }
            std::thread::sleep(
                Duration::from_millis(5).min(deadline.saturating_duration_since(Instant::now())),
            );
        }
    }

    pub(super) fn matches_key(output: &[u8], expected: &[u8; 32]) -> Result<()> {
        // security -w appends a newline. Accept only that exact terminator (or
        // none), not arbitrary whitespace, metadata, or a different item.
        let encoded = match output.strip_suffix(b"\n") {
            Some(line) => line.strip_suffix(b"\r").unwrap_or(line),
            None => output,
        };
        let invalid = || {
            protection_error(
                "verify runtime access to",
                "macOS Keychain reader returned a different or invalid account key; unlock the original login keychain and retry",
            )
        };
        if encoded.len() != 43 {
            return Err(invalid());
        }
        let mut decoded = Secret([0u8; 32]);
        let length = URL_SAFE_NO_PAD
            .decode_slice(encoded, &mut decoded.0)
            .map_err(|_| invalid())?;
        if length != 32
            || decoded
                .0
                .iter()
                .zip(expected)
                .fold(0u8, |difference, (left, right)| difference | (left ^ right))
                != 0
        {
            return Err(invalid());
        }
        Ok(())
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn reader_command_is_fixed_and_contains_no_key() {
            let command = command();
            assert_eq!(command.get_program(), "/usr/bin/security");
            let args: Vec<_> = command.get_args().collect();
            assert_eq!(
                args,
                [
                    "find-generic-password",
                    "-s",
                    service(),
                    "-a",
                    account(),
                    "-w"
                ]
            );
        }

        #[test]
        fn only_canonical_matching_key_output_is_accepted() {
            let key = [0x41; 32];
            let encoded = URL_SAFE_NO_PAD.encode(key);
            for suffix in ["", "\n", "\r\n"] {
                assert!(matches_key(format!("{encoded}{suffix}").as_bytes(), &key).is_ok());
            }
            for invalid in [
                "".to_string(),
                format!(" {encoded}"),
                format!("{encoded}\n\n"),
                format!("{encoded}\r"),
                format!("{encoded}="),
                "!".repeat(43),
            ] {
                assert!(matches_key(invalid.as_bytes(), &key).is_err());
            }
            assert!(matches_key(encoded.as_bytes(), &[0x42; 32]).is_err());
        }

        #[test]
        fn reader_timeout_and_output_limit_do_not_touch_keychain() {
            let started = Instant::now();
            let error = read(
                Command::new("/bin/sleep").arg("60"),
                Duration::from_millis(30),
            )
            .err()
            .unwrap();
            assert_eq!(error.kind(), io::ErrorKind::TimedOut);
            assert!(started.elapsed() < Duration::from_secs(2));
            assert!(
                read(
                    Command::new("/usr/bin/yes").arg("synthetic fixture"),
                    Duration::from_secs(2)
                )
                .is_err()
            );
            assert!(read(&mut Command::new("/usr/bin/false"), Duration::from_secs(2)).is_err());
        }

        #[test]
        fn bounded_output_is_exact_and_standard_input_is_closed() {
            let output = read(
                Command::new("/usr/bin/printf").arg("public fixture\n"),
                Duration::from_secs(2),
            )
            .unwrap();
            assert_eq!(output.0, b"public fixture\n");
            assert!(
                read(&mut Command::new("/bin/cat"), Duration::from_secs(2))
                    .unwrap()
                    .0
                    .is_empty()
            );
        }
    }
}

#[cfg(target_os = "linux")]
mod keyring {
    use super::*;
    use std::io::{Read, Write};
    use std::os::fd::AsRawFd;
    use std::process::{Child, Command, ExitStatus, Stdio};
    use std::time::{Duration, Instant};

    const PROCESS_TIMEOUT: Duration = Duration::from_secs(15);
    const OUTPUT_LIMIT: usize = 256;

    // Linux fcntl values; this module is never compiled for another OS.
    const F_GETFL: i32 = 3;
    const F_SETFL: i32 = 4;
    const O_NONBLOCK: i32 = 0x800;
    unsafe extern "C" {
        fn fcntl(fd: i32, command: i32, ...) -> i32;
    }

    fn nonblocking(pipe: &impl AsRawFd) -> io::Result<()> {
        // These are our owned child pipes. Preserve every flag except adding
        // nonblocking I/O, so an inherited descendant cannot keep us waiting.
        let flags = unsafe { fcntl(pipe.as_raw_fd(), F_GETFL) };
        if flags < 0 || unsafe { fcntl(pipe.as_raw_fd(), F_SETFL, flags | O_NONBLOCK) } < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }

    struct PendingProcess(Option<Child>);

    impl Drop for PendingProcess {
        fn drop(&mut self) {
            if let Some(mut child) = self.0.take() {
                let _ = child.kill();
                // Reap off the request thread. Even process cleanup must not
                // turn a timed-out password-store operation into an infinite wait.
                let _ = std::thread::Builder::new()
                    .name("Celeste password-store cleanup".into())
                    .spawn(move || {
                        let _ = child.wait();
                    });
            }
        }
    }

    struct CommandOutput {
        status: ExitStatus,
        stdout: Vec<u8>,
        stderr_nonempty: bool,
    }

    impl Drop for CommandOutput {
        fn drop(&mut self) {
            self.stdout.fill(0);
        }
    }

    // The stdout capture can contain a key. Wipe it on every early error as
    // well as after a successful decode. Diagnostics are counted, never retained.
    struct KeyOutput(Vec<u8>);
    impl Drop for KeyOutput {
        fn drop(&mut self) {
            self.0.fill(0);
        }
    }

    fn read_pipe(
        pipe: &mut impl Read,
        output: Option<&mut Vec<u8>>,
        count: &mut usize,
    ) -> io::Result<bool> {
        let mut output = output;
        let mut buffer = [0_u8; 128];
        loop {
            match pipe.read(&mut buffer) {
                Ok(0) => return Ok(true),
                Ok(length) => {
                    *count += length;
                    if *count > OUTPUT_LIMIT {
                        buffer.fill(0);
                        return Err(io::Error::other("Password-store output exceeds its limit"));
                    }
                    if let Some(output) = &mut output {
                        output.extend_from_slice(&buffer[..length]);
                    }
                    buffer.fill(0);
                }
                // Return to the outer deadline check even under repeated signals.
                Err(error) if error.kind() == io::ErrorKind::Interrupted => return Ok(false),
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => return Ok(false),
                Err(error) => return Err(error),
            }
        }
    }

    fn run_command(
        command: &mut Command,
        input: Option<&[u8]>,
        timeout: Duration,
    ) -> io::Result<CommandOutput> {
        let deadline = Instant::now()
            .checked_add(timeout)
            .ok_or_else(|| io::Error::other("Invalid password-store deadline"))?;
        if input.is_some_and(|value| value.len() > OUTPUT_LIMIT) {
            return Err(io::Error::other("Password-store input exceeds its limit"));
        }
        let mut pending = PendingProcess(Some(
            command
                .stdin(if input.is_some() {
                    Stdio::piped()
                } else {
                    Stdio::null()
                })
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()?,
        ));
        let child = pending.0.as_mut().expect("owned process");
        let mut stdout = child
            .stdout
            .take()
            .ok_or_else(|| io::Error::other("Missing password-store output"))?;
        let mut stderr = child
            .stderr
            .take()
            .ok_or_else(|| io::Error::other("Missing password-store diagnostic"))?;
        let mut stdin = child.stdin.take();
        nonblocking(&stdout)?;
        nonblocking(&stderr)?;
        if let Some(stdin) = &stdin {
            nonblocking(stdin)?;
        }
        let input = input.unwrap_or_default();
        let mut written = 0;
        let mut captured = KeyOutput(Vec::with_capacity(OUTPUT_LIMIT));
        let (mut stdout_count, mut stderr_count) = (0, 0);
        let (mut stdout_eof, mut stderr_eof) = (false, false);
        let mut status = None;
        loop {
            if Instant::now() >= deadline {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "Password-store operation timed out",
                ));
            }
            if let Some(pipe) = stdin.as_mut() {
                if written < input.len() {
                    match pipe.write(&input[written..]) {
                        Ok(0) => {
                            return Err(io::Error::new(
                                io::ErrorKind::WriteZero,
                                "Password-store input closed",
                            ));
                        }
                        Ok(length) => written += length,
                        Err(error)
                            if matches!(
                                error.kind(),
                                io::ErrorKind::WouldBlock | io::ErrorKind::Interrupted
                            ) => {}
                        Err(error) => return Err(error),
                    }
                }
                // secret-tool stores exact stdin bytes until EOF, with no newline.
                if written == input.len() {
                    stdin = None;
                }
            }
            if !stdout_eof {
                stdout_eof = read_pipe(&mut stdout, Some(&mut captured.0), &mut stdout_count)?;
            }
            if !stderr_eof {
                stderr_eof = read_pipe(&mut stderr, None, &mut stderr_count)?;
            }
            if status.is_none() {
                status = child.try_wait()?;
            }
            if let Some(status) = status {
                if stdout_eof && stderr_eof && stdin.is_none() {
                    // try_wait already reaped this child. No cleanup thread is needed.
                    pending.0 = None;
                    return Ok(CommandOutput {
                        status,
                        stdout: std::mem::take(&mut captured.0),
                        stderr_nonempty: stderr_count > 0,
                    });
                }
            }
            std::thread::sleep(
                Duration::from_millis(5).min(deadline.saturating_duration_since(Instant::now())),
            );
        }
    }

    fn command() -> Command {
        Command::new("secret-tool")
    }

    pub(super) fn load() -> Result<Option<[u8; 32]>> {
        let output = run_command(
            command().args(["lookup", "service", service(), "account", account()]),
            None, PROCESS_TIMEOUT,
        )
            .map_err(|_| {
                protection_error(
                    "read",
                    "Secret Service lookup failed or timed out; install secret-tool and unlock the account's password store",
                )
            })?;
        decode_lookup(output.status.code(), &output.stdout, output.stderr_nonempty)
    }

    fn decode_lookup(
        code: Option<i32>,
        stdout: &[u8],
        stderr_nonempty: bool,
    ) -> Result<Option<[u8; 32]>> {
        // ONLY this exact documented miss can allow an explicit save to create
        // a new key. Empty successful output and backend errors must fail closed.
        if code == Some(1) && stdout.is_empty() && !stderr_nonempty {
            return Ok(None);
        }
        if code != Some(0) {
            return Err(protection_error(
                "read",
                "the desktop Secret Service is unavailable or locked",
            ));
        }
        // With piped stdout secret-tool does not append a terminal newline.
        let bytes = URL_SAFE_NO_PAD.decode(stdout).map_err(|_| {
            protection_error(
                "read",
                "the desktop Secret Service returned an invalid account key",
            )
        })?;
        let key: [u8; 32] = bytes.try_into().map_err(|_| {
            protection_error(
                "read",
                "the desktop Secret Service returned an invalid account key",
            )
        })?;
        Ok(Some(key))
    }

    pub(super) fn store(key: &[u8; 32]) -> Result<()> {
        let mut encoded = URL_SAFE_NO_PAD.encode(key).into_bytes();
        let result = run_command(
            command().args([
                "store",
                "--label=Celeste credential key",
                "service",
                service(),
                "account",
                account(),
            ]),
            Some(&encoded),
            PROCESS_TIMEOUT,
        );
        encoded.fill(0);
        let output = result
            .map_err(|_| {
                protection_error(
                    "save",
                    "Secret Service save failed or timed out; install secret-tool and unlock the account's password store",
                )
            })?;
        if output.status.success() {
            Ok(())
        } else {
            Err(protection_error(
                "save",
                "the desktop Secret Service is unavailable or locked",
            ))
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn only_an_empty_diagnostic_free_exit_one_means_missing() {
            assert!(decode_lookup(Some(1), b"", false).unwrap().is_none());
            for (code, output, diagnostic) in [
                (Some(0), b"".as_slice(), false),
                (Some(1), b"", true),
                (Some(1), b"unexpected", false),
                (Some(2), b"", false),
                (None, b"", false),
            ] {
                assert!(decode_lookup(code, output, diagnostic).is_err());
            }
            let encoded = URL_SAFE_NO_PAD.encode([0x41; 32]);
            assert_eq!(
                decode_lookup(Some(0), encoded.as_bytes(), false).unwrap(),
                Some([0x41; 32])
            );
        }

        #[test]
        fn subprocess_deadline_and_output_limits_are_enforced_without_key_store() {
            let started = Instant::now();
            let error = run_command(
                Command::new("sleep").arg("60"),
                None,
                Duration::from_millis(30),
            )
            .err()
            .unwrap();
            assert_eq!(error.kind(), io::ErrorKind::TimedOut);
            assert!(started.elapsed() < Duration::from_secs(2));
            assert!(
                run_command(
                    Command::new("yes").arg("public-fixture-output"),
                    None,
                    Duration::from_secs(2)
                )
                .is_err()
            );
        }

        #[test]
        fn subprocess_stdout_and_input_remain_bounded_and_exact() {
            let output =
                run_command(&mut Command::new("false"), None, Duration::from_secs(2)).unwrap();
            assert!(
                decode_lookup(output.status.code(), &output.stdout, output.stderr_nonempty)
                    .unwrap()
                    .is_none()
            );
            let plain = b"public synthetic input with no newline";
            let output = run_command(
                &mut Command::new("cat"),
                Some(plain),
                Duration::from_secs(2),
            )
            .unwrap();
            assert!(output.status.success());
            assert_eq!(output.stdout, plain);
            assert!(!output.stderr_nonempty);
        }
    }
}

struct ProbeFile(Option<PathBuf>);

impl ProbeFile {
    fn reserve(directory: &Path) -> Result<Self> {
        for _ in 0..32 {
            let mut random = [0u8; 16];
            OsRng
                .try_fill_bytes(&mut random)
                .context("System randomness is unavailable for the storage check")?;
            let suffix: String = random.iter().map(|byte| format!("{byte:02x}")).collect();
            let path = directory.join(format!(
                ".celeste-storage-check-{}-{suffix}.probe",
                std::process::id()
            ));
            match OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(&path)
            {
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
        let _ = self.remove();
    }
}

pub(crate) fn preflight(directory: &Path) -> Result<()> {
    if !directory.is_absolute() {
        bail!("Activation storage check requires an absolute account directory");
    }
    let mut sample = [0u8; 64];
    OsRng
        .try_fill_bytes(&mut sample)
        .context("System randomness is unavailable for the protection check")?;
    let protected = protect(&sample)?;
    if unprotect(&protected)? != sample {
        bail!("Credential protection did not pass its round-trip check");
    }
    #[cfg(target_os = "macos")]
    validate_runtime_reader()?;
    crate::user_profile::ensure_private_activation_dir(directory)
        .context("Could not prepare private activation storage; enrollment was not started")?;
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
    fn java_compatible_envelope_fixture_and_tampering() {
        let mut key = [0u8; 32];
        let mut nonce = [0u8; NONCE_BYTES];
        for (index, byte) in key.iter_mut().enumerate() {
            *byte = index as u8;
        }
        for (index, byte) in nonce.iter_mut().enumerate() {
            *byte = (index + 32) as u8;
        }
        let plain = b"nonsecret portable credential fixture";
        let sealed = seal_with_key(plain, &key, &nonce).unwrap();
        let expected = "43535032202122232425262728292a2bbc55c80309fb686b6e5c32a1b36c959bbc2cccfff5e5078b0282057325aa38600dfa9076eb9ec02829d82e3ff1fd07a85741d550c2";
        let actual: String = sealed.iter().map(|byte| format!("{byte:02x}")).collect();
        assert_eq!(actual, expected);
        assert_eq!(open_with_key(&sealed, &key).unwrap(), plain);
        let mut damaged = sealed;
        *damaged.last_mut().unwrap() ^= 1;
        assert!(open_with_key(&damaged, &key).is_err());
        assert!(unprotect(b"invalid envelope").is_err());
    }

    #[test]
    fn relative_preflight_path_is_rejected_without_creating_a_directory() {
        assert!(preflight(Path::new("relative-storage-probe")).is_err());
    }
}
