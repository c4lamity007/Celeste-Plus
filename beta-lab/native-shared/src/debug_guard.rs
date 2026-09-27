//! Local debugger policy. No process enumeration, network activity or persistence.
//! This raises the cost of casual inspection; it cannot attest an unmodified client.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum DebugError {
    Detected,
    Unavailable,
}

fn verdict(signals: &[bool]) -> Result<(), DebugError> {
    if signals.iter().any(|present| *present) {
        Err(DebugError::Detected)
    } else {
        Ok(())
    }
}

pub(crate) fn enforce() {
    if require_clear().is_err() {
        // Do not put a debugger signal, hardware fact or credential into a log/UI.
        std::process::exit(0);
    }
}

#[cfg(all(windows, target_arch = "x86_64"))]
#[inline(never)]
pub(crate) fn require_clear() -> Result<(), DebugError> {
    use winapi::ctypes::c_void;
    use winapi::um::{
        debugapi::{CheckRemoteDebuggerPresent, IsDebuggerPresent},
        processthreadsapi::GetCurrentProcess,
    };
    #[link(name = "ntdll")]
    unsafe extern "system" {
        fn NtQueryInformationProcess(
            process: *mut c_void,
            class: u32,
            output: *mut c_void,
            length: u32,
            returned: *mut u32,
        ) -> i32;
    }
    let mut remote = 0;
    let direct = unsafe { IsDebuggerPresent() != 0 };
    if unsafe { CheckRemoteDebuggerPresent(GetCurrentProcess(), &mut remote) } == 0 {
        return Err(DebugError::Unavailable);
    }
    let peb: usize;
    unsafe {
        std::arch::asm!("mov {}, gs:[0x60]", out(reg) peb, options(nostack, preserves_flags, readonly));
    }
    if peb == 0 {
        return Err(DebugError::Unavailable);
    }
    let peb_debug = unsafe { std::ptr::read_volatile((peb + 2) as *const u8) != 0 };
    let mut port = 0usize;
    let status = unsafe {
        NtQueryInformationProcess(
            GetCurrentProcess(),
            7,
            (&mut port as *mut usize).cast(),
            std::mem::size_of::<usize>() as u32,
            std::ptr::null_mut(),
        )
    };
    if status < 0 {
        return Err(DebugError::Unavailable);
    }
    verdict(&[
        direct,
        remote != 0,
        peb_debug,
        port != 0,
        hardware_breakpoints()?,
        timing_anomaly(),
    ])
}

#[cfg(all(windows, target_arch = "x86_64"))]
#[inline(never)]
fn hardware_breakpoints() -> Result<bool, DebugError> {
    use winapi::um::{
        handleapi::CloseHandle,
        processthreadsapi::{
            GetCurrentThreadId, GetThreadContext, OpenThread, ResumeThread, SuspendThread,
        },
        winnt::{CONTEXT, CONTEXT_DEBUG_REGISTERS, THREAD_GET_CONTEXT, THREAD_SUSPEND_RESUME},
    };
    // GetThreadContext on the current running thread is not valid. A helper
    // suspends only this caller, captures debug registers, and ALWAYS resumes it.
    // No allocation, lock, logging, or Rust unwinding occurs while it is suspended.
    let target = unsafe {
        OpenThread(
            THREAD_GET_CONTEXT | THREAD_SUSPEND_RESUME,
            0,
            GetCurrentThreadId(),
        )
    };
    if target.is_null() {
        return Err(DebugError::Unavailable);
    }
    let raw = target as usize;
    let helper = std::thread::Builder::new().spawn(move || {
        let target = raw as winapi::shared::ntdef::HANDLE;
        let mut context: CONTEXT = unsafe { std::mem::zeroed() };
        context.ContextFlags = CONTEXT_DEBUG_REGISTERS;
        let suspended = unsafe { SuspendThread(target) };
        if suspended == u32::MAX {
            return Err(DebugError::Unavailable);
        }
        let captured = unsafe { GetThreadContext(target, &mut context) };
        let resumed = unsafe { ResumeThread(target) };
        if resumed == u32::MAX {
            // The caller cannot safely continue if the only resume failed.
            unsafe {
                winapi::um::processthreadsapi::TerminateProcess(
                    winapi::um::processthreadsapi::GetCurrentProcess(),
                    0,
                );
            }
        }
        if captured == 0 {
            return Err(DebugError::Unavailable);
        }
        Ok(has_hardware_breakpoint(
            [context.Dr0, context.Dr1, context.Dr2, context.Dr3],
            context.Dr7,
        ))
    });
    let result = match helper {
        Ok(thread) => thread.join().unwrap_or(Err(DebugError::Unavailable)),
        Err(_) => Err(DebugError::Unavailable),
    };
    unsafe {
        CloseHandle(target);
    }
    result
}

#[cfg(any(all(windows, target_arch = "x86_64"), test))]
fn has_hardware_breakpoint(addresses: [u64; 4], control: u64) -> bool {
    addresses
        .iter()
        .enumerate()
        .any(|(index, address)| *address != 0 && control & (3 << (index * 2)) != 0)
}

#[cfg(all(windows, target_arch = "x86_64"))]
fn timing_anomaly() -> bool {
    use std::arch::x86_64::{_mm_lfence, _rdtsc};
    let mut minimum = u64::MAX;
    for _ in 0..32 {
        let elapsed = unsafe {
            _mm_lfence();
            let before = _rdtsc();
            std::arch::asm!("nop", options(nomem, nostack, preserves_flags));
            _mm_lfence();
            _rdtsc().wrapping_sub(before)
        };
        minimum = minimum.min(elapsed);
    }
    // A single interrupted sample is not a detection. Requiring every sample
    // to exceed millions of cycles avoids treating routine scheduling as a trap.
    minimum > 10_000_000
}

#[cfg(target_os = "linux")]
pub(crate) fn require_clear() -> Result<(), DebugError> {
    let status =
        std::fs::read_to_string("/proc/self/status").map_err(|_| DebugError::Unavailable)?;
    let tracer = status
        .lines()
        .find_map(|line| line.strip_prefix("TracerPid:"))
        .ok_or(DebugError::Unavailable)?
        .trim()
        .parse::<u32>()
        .map_err(|_| DebugError::Unavailable)?;
    verdict(&[tracer != 0])
}

#[cfg(target_os = "macos")]
pub(crate) fn require_clear() -> Result<(), DebugError> {
    let mut info: libc::proc_bsdinfo = unsafe { std::mem::zeroed() };
    let size = std::mem::size_of_val(&info);
    let result = unsafe {
        libc::proc_pidinfo(
            libc::getpid(),
            libc::PROC_PIDTBSDINFO,
            0,
            (&mut info as *mut libc::proc_bsdinfo).cast(),
            size as i32,
        )
    };
    if result != size as i32 || info.pbi_pid != unsafe { libc::getpid() } as u32 {
        return Err(DebugError::Unavailable);
    }
    // Apple xnu bsd/sys/proc_info.h: PROC_FLAG_TRACED is 2 in pbi_flags.
    verdict(&[info.pbi_flags & 2 != 0])
}

#[cfg(not(any(
    all(windows, target_arch = "x86_64"),
    target_os = "linux",
    target_os = "macos"
)))]
pub(crate) fn require_clear() -> Result<(), DebugError> {
    Err(DebugError::Unavailable)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "Opt-in local self-debugger probe; no authentication, remote requests, or process enumeration"]
    fn actual_self_probe_without_debugger_is_clear() {
        assert_eq!(require_clear(), Ok(()));
    }
    #[test]
    fn each_reliable_signal_independently_denies() {
        assert_eq!(verdict(&[false; 6]), Ok(()));
        for index in 0..6 {
            let mut flags = [false; 6];
            flags[index] = true;
            assert_eq!(verdict(&flags), Err(DebugError::Detected));
        }
    }
    #[test]
    fn only_enabled_nonzero_hardware_slots_deny() {
        assert!(!has_hardware_breakpoint([0; 4], 0xff));
        assert!(!has_hardware_breakpoint([1, 2, 3, 4], 0));
        for slot in 0..4 {
            let mut addresses = [0; 4];
            addresses[slot] = 0x1000;
            assert!(has_hardware_breakpoint(addresses, 1 << (slot * 2)));
        }
        assert!(!has_hardware_breakpoint([1, 0, 0, 0], 1 << 2));
    }
}
