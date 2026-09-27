//! Same-account Java discovery without inspecting arguments, environment or game tokens.
//! The signed Java helper subsequently verifies the exact selected Minecraft folder/version.
use super::MinecraftProcess;
use anyhow::{Context, Result, bail};
use std::path::Path;

pub fn minecraft_processes() -> Vec<MinecraftProcess> {
    let mut processes = native::pids()
        .into_iter()
        .filter_map(|pid| {
            let (name, started) = process_identity(pid).ok()?;
            (name == "java").then(|| MinecraftProcess {
                pid,
                started,
                title: "Java (select your Minecraft process)".into(),
            })
        })
        .collect::<Vec<_>>();
    processes.sort_by_key(|value| value.pid);
    processes
}

pub(super) fn process_identity(pid: u32) -> Result<(String, u64)> {
    if pid == 0 || pid > i32::MAX as u32 {
        bail!("Invalid selected process");
    }
    native::identity(pid)
}

#[cfg(target_os = "linux")]
mod native {
    use super::*;
    use std::fs;
    use std::os::unix::fs::MetadataExt;

    pub fn pids() -> Vec<u32> {
        fs::read_dir("/proc")
            .ok()
            .into_iter()
            .flatten()
            .filter_map(|entry| entry.ok()?.file_name().to_str()?.parse().ok())
            .collect()
    }

    pub fn identity(pid: u32) -> Result<(String, u64)> {
        let directory = Path::new("/proc").join(pid.to_string());
        // /proc metadata supplies owner without reading cmdline or environ.
        if fs::metadata(&directory)?.uid() != unsafe { libc::geteuid() } {
            bail!("Choose Minecraft running under the same signed-in account");
        }
        let executable = fs::read_link(directory.join("exe"))?;
        let name = executable
            .file_name()
            .and_then(|value| value.to_str())
            .unwrap_or("")
            .to_owned();
        let stat = fs::read_to_string(directory.join("stat"))?;
        let boot = fs::read_to_string("/proc/stat")?;
        let ticks = unsafe { libc::sysconf(libc::_SC_CLK_TCK) };
        let started = start_millis(&stat, &boot, ticks)?;
        Ok((name, started))
    }

    // Matches Java 21 ProcessHandle's documented epoch-millisecond start instant.
    // /proc stat field 22 is relative clock ticks; parentheses in comm are legal.
    pub(super) fn start_millis(stat: &str, boot: &str, ticks_per_second: i64) -> Result<u64> {
        if ticks_per_second <= 0 {
            bail!("Process clock frequency is unavailable");
        }
        let (_, fields) = stat
            .rsplit_once(')')
            .context("Invalid process timing data")?;
        let ticks = fields
            .split_whitespace()
            .nth(19)
            .context("Missing process start time")?
            .parse::<u64>()?;
        let boot_seconds = boot
            .lines()
            .find_map(|line| line.strip_prefix("btime "))
            .context("System boot time is unavailable")?
            .trim()
            .parse::<u64>()?;
        let started = boot_seconds
            .checked_mul(1000)
            .and_then(|base| {
                ticks
                    .checked_mul(1000)
                    .and_then(|value| base.checked_add(value / ticks_per_second as u64))
            })
            .filter(|value| *value > 0)
            .context("Invalid process creation time")?;
        Ok(started)
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        #[test]
        fn proc_time_handles_comm_parentheses_and_millisecond_precision() {
            let tail = std::iter::repeat_n("0", 18).collect::<Vec<_>>().join(" ");
            let stat = format!("123 (java (test)) S {tail} 12345 0 0");
            assert_eq!(
                start_millis(&stat, "cpu 1\nbtime 1800000000\n", 100).unwrap(),
                1800000123450
            );
            assert!(start_millis(&stat, "btime 1800000000", 0).is_err());
            assert!(start_millis("123 (java) S", "btime 1", 100).is_err());
            assert!(start_millis(&stat, "cpu 1", 100).is_err());
        }
    }
}

#[cfg(target_os = "macos")]
mod native {
    use super::*;
    use std::ffi::CStr;
    use std::mem::{MaybeUninit, size_of};
    use std::os::unix::ffi::OsStrExt;

    pub fn pids() -> Vec<u32> {
        // Documented libproc PROC_UID_ONLY = 4. Grow once to account for births.
        let uid = unsafe { libc::geteuid() };
        let needed = unsafe { libc::proc_listpids(4, uid, std::ptr::null_mut(), 0) };
        if needed <= 0 || needed > 1024 * 1024 {
            return Vec::new();
        }
        let mut pids = vec![0i32; needed as usize / size_of::<i32>() + 128];
        let bytes = unsafe {
            libc::proc_listpids(
                4,
                uid,
                pids.as_mut_ptr().cast(),
                (pids.len() * size_of::<i32>()) as i32,
            )
        };
        if bytes <= 0 {
            return Vec::new();
        }
        pids.truncate((bytes as usize / size_of::<i32>()).min(pids.len()));
        pids.into_iter()
            .filter(|pid| *pid > 0)
            .map(|pid| pid as u32)
            .collect()
    }

    pub fn identity(pid: u32) -> Result<(String, u64)> {
        let mut info = MaybeUninit::<libc::proc_bsdinfo>::zeroed();
        let size = size_of::<libc::proc_bsdinfo>() as i32;
        let actual = unsafe {
            libc::proc_pidinfo(
                pid as i32,
                libc::PROC_PIDTBSDINFO,
                0,
                info.as_mut_ptr().cast(),
                size,
            )
        };
        if actual != size {
            bail!("Cannot inspect selected process");
        }
        let info = unsafe { info.assume_init() };
        if info.pbi_pid != pid || info.pbi_uid != unsafe { libc::geteuid() } {
            bail!("Choose Minecraft running under the same signed-in account");
        }
        let mut path = vec![0u8; libc::PROC_PIDPATHINFO_MAXSIZE as usize];
        let actual =
            unsafe { libc::proc_pidpath(pid as i32, path.as_mut_ptr().cast(), path.len() as u32) };
        if actual <= 0 {
            bail!("Cannot identify selected Java executable");
        }
        let bytes = CStr::from_bytes_until_nul(&path)
            .context("Invalid executable path")?
            .to_bytes();
        let executable = Path::new(std::ffi::OsStr::from_bytes(bytes));
        let name = executable
            .file_name()
            .and_then(|value| value.to_str())
            .unwrap_or("")
            .to_owned();
        let started = info
            .pbi_start_tvsec
            .checked_mul(1000)
            .and_then(|value| value.checked_add(info.pbi_start_tvusec / 1000))
            .filter(|value| *value > 0)
            .context("Invalid process creation time")?;
        Ok((name, started))
    }
}
