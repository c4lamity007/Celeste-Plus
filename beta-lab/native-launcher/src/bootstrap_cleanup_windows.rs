//! Hidden cleanup waiters survive the launcher and follow the exact target lifetime.
//! It accepts only an account-owned process root and an authenticated receipt.
use super::{Result, anyhow, ensure, read_owner};
use std::{
    collections::HashMap,
    ffi::OsString,
    io::{Read, Write},
    os::windows::{io::AsRawHandle, process::CommandExt},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};
use winapi::{
    shared::minwindef::{BOOL, DWORD, FILETIME, LPVOID},
    um::{
        handleapi::CloseHandle,
        processthreadsapi::{GetProcessTimes, OpenProcess},
        winnt::{HANDLE, PROCESS_QUERY_LIMITED_INFORMATION, SYNCHRONIZE},
    },
};

const MODE: &str = "--owned-bootstrap-cleanup";
const READY: u8 = b'R';
const WAIT_OBJECT_0: DWORD = 0;
const WAIT_TIMEOUT: DWORD = 258;

#[link(name = "kernel32")]
unsafe extern "system" {
    fn WaitForSingleObject(handle: HANDLE, milliseconds: DWORD) -> DWORD;
    fn PeekNamedPipe(
        handle: HANDLE,
        buffer: LPVOID,
        size: DWORD,
        read: *mut DWORD,
        available: *mut DWORD,
        remaining: *mut DWORD,
    ) -> BOOL;
}

struct Handle(HANDLE);
impl Drop for Handle {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.0);
        }
    }
}

fn process(pid: u32, started: u64) -> Result<Handle> {
    let raw = unsafe { OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
    ensure!(
        !raw.is_null(),
        "{}",
        obfstr::obfstr!("Cleanup could not open the selected process")
    );
    let handle = Handle(raw);
    let mut created: FILETIME = unsafe { std::mem::zeroed() };
    let mut exited = created;
    let mut kernel = created;
    let mut user = created;
    ensure!(
        unsafe { GetProcessTimes(raw, &mut created, &mut exited, &mut kernel, &mut user) } != 0,
        "{}",
        obfstr::obfstr!("Cleanup could not verify process creation")
    );
    let ticks = (u64::from(created.dwHighDateTime) << 32) | u64::from(created.dwLowDateTime);
    let actual = ticks
        .checked_sub(116_444_736_000_000_000)
        .map(|v| v / 10_000);
    ensure!(
        actual == Some(started) && unsafe { WaitForSingleObject(raw, 0) } == WAIT_TIMEOUT,
        "{}",
        obfstr::obfstr!("Selected process already exited or was replaced")
    );
    Ok(handle)
}

fn scope(arguments: &[OsString]) -> Result<std::path::PathBuf> {
    ensure!(
        arguments.len() == 4,
        "{}",
        obfstr::obfstr!("Invalid cleanup request")
    );
    let identity = arguments[2]
        .to_str()
        .ok_or_else(|| anyhow!(obfstr::obfstr!("Invalid process identity").to_owned()))?;
    let (pid, started) = identity
        .split_once('-')
        .ok_or_else(|| anyhow!(obfstr::obfstr!("Invalid process identity").to_owned()))?;
    let pid = pid.parse::<u32>()?;
    let started = started.parse::<u64>()?;
    ensure!(
        pid > 0 && started > 0 && identity == format!("{pid}-{started}"),
        obfstr::obfstr!("Invalid process identity").to_owned()
    );
    let name = arguments[3]
        .to_str()
        .ok_or_else(|| anyhow!(obfstr::obfstr!("Invalid receipt name").to_owned()))?;
    ensure!(
        name.starts_with(super::prefix())
            && name.len() == super::prefix().len() + 48
            && name[super::prefix().len()..]
                .bytes()
                .all(|b| b.is_ascii_hexdigit()),
        obfstr::obfstr!("Invalid receipt name").to_owned()
    );
    // No filesystem path is accepted from the command line.
    let base = crate::user_profile::current_local_app_data()?
        .join(obfstr::obfstr!("Celeste"))
        .join(obfstr::obfstr!("Plus"));
    super::owned_directory(&base)?;
    let root = base.join(identity);
    super::owned_directory(&root)?;
    Ok(root)
}

pub(super) fn dispatch() -> Result<bool> {
    let arguments: Vec<_> = std::env::args_os().collect();
    if arguments.get(1).is_none_or(|v| v != MODE) {
        return Ok(false);
    }
    let root = scope(&arguments)?;
    let directory = root.join(&arguments[3]);
    run(&root, &directory, || {
        std::io::stdout().write_all(&[READY])?;
        std::io::stdout().flush()?;
        Ok(())
    })?;
    Ok(true)
}

fn run(root: &Path, directory: &Path, ready: impl FnOnce() -> Result<()>) -> Result<()> {
    let owner = read_owner(root, directory)?;
    let target = process(owner.pid, owner.started)?;
    ready()?;
    // Holding the original process handle avoids PID-reuse races, and uses no polling CPU.
    ensure!(
        unsafe { WaitForSingleObject(target.0, u32::MAX) } == WAIT_OBJECT_0,
        obfstr::obfstr!("Cleanup process wait failed").to_owned()
    );
    // At this point the original process has exited. A replacement PID cannot own its files.
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if super::clean_root(root, |pid, started| {
            pid == owner.pid && started == owner.started
        })? {
            return Ok(());
        }
        if Instant::now() >= deadline {
            return Err(anyhow!(
                obfstr::obfstr!("Owned cleanup could not finish").to_owned()
            ));
        }
        std::thread::sleep(Duration::from_millis(100));
    }
}

pub(super) fn start(root: &Path, directory: &Path) -> Result<()> {
    static WATCHERS: OnceLock<Mutex<HashMap<PathBuf, Child>>> = OnceLock::new();
    let owner = read_owner(root, directory)?;
    let _target = process(owner.pid, owner.started)?;
    let mut watchers = WATCHERS
        .get_or_init(|| Mutex::new(HashMap::new()))
        .lock()
        .map_err(|_| anyhow!(obfstr::obfstr!("Cleanup waiter state is unavailable").to_owned()))?;
    watchers.retain(|_, child| matches!(child.try_wait(), Ok(None)));
    let identity = root.canonicalize()?;
    if watchers.contains_key(&identity) {
        return Ok(());
    }
    ensure!(
        watchers.len() < 64,
        obfstr::obfstr!("Too many active cleanup waiters").to_owned()
    );
    let mut command = Command::new(std::env::current_exe()?);
    command
        .arg(MODE)
        .arg(
            root.file_name()
                .ok_or_else(|| anyhow!(obfstr::obfstr!("Invalid process root").to_owned()))?,
        )
        .arg(
            directory
                .file_name()
                .ok_or_else(|| anyhow!(obfstr::obfstr!("Invalid owner directory").to_owned()))?,
        )
        .creation_flags(0x0800_0000) // CREATE_NO_WINDOW; never show a helper window.
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    let mut child = command.spawn()?;
    if let Err(error) = acknowledge(&mut child) {
        // This is only the helper created by this call, never the selected game.
        let _ = child.kill();
        let _ = child.wait();
        return Err(error);
    }
    drop(child.stdout.take());
    // Only our own live process handle permits reuse. A predictable named object
    // is never accepted as evidence that some other process is doing cleanup.
    // Dropping these handles on launcher exit does not terminate the waiter.
    watchers.insert(identity, child);
    Ok(())
}

fn acknowledge(child: &mut Child) -> Result<()> {
    let deadline = Instant::now() + Duration::from_secs(10);
    let stdout = child
        .stdout
        .as_mut()
        .ok_or_else(|| anyhow!(obfstr::obfstr!("Cleanup handshake unavailable").to_owned()))?;
    loop {
        let mut available = 0;
        let ok = unsafe {
            PeekNamedPipe(
                stdout.as_raw_handle().cast(),
                std::ptr::null_mut(),
                0,
                std::ptr::null_mut(),
                &mut available,
                std::ptr::null_mut(),
            )
        };
        ensure!(
            ok != 0,
            obfstr::obfstr!("Cleanup helper exited before acknowledgement").to_owned()
        );
        if available > 0 {
            let mut value = [0];
            stdout.read_exact(&mut value)?;
            ensure!(
                value[0] == READY,
                obfstr::obfstr!("Invalid cleanup acknowledgement").to_owned()
            );
            return Ok(());
        }
        ensure!(
            Instant::now() < deadline,
            obfstr::obfstr!("Cleanup helper did not acknowledge startup").to_owned()
        );
        std::thread::sleep(Duration::from_millis(20));
    }
}
