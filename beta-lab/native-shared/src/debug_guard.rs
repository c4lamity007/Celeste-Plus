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

// This callback runs under the Windows loader lock. It must stay allocation-free:
// no Rust runtime initialization, COM, thread creation, waits, logs or network.
// The full checks (including thread context and timing) remain at normal startup.
#[cfg(all(windows, target_arch = "x86_64", not(test)))]
static EARLY_CHECK_RAN: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

#[cfg(all(windows, target_arch = "x86_64", not(test)))]
#[used]
#[unsafe(link_section = ".CRT$XLB")]
static EARLY_DEBUG_CALLBACK: unsafe extern "system" fn(
    *mut std::ffi::c_void,
    u32,
    *mut std::ffi::c_void,
) = early_debug_callback;

#[cfg(all(windows, target_arch = "x86_64", not(test)))]
unsafe extern "system" fn early_debug_callback(
    _module: *mut std::ffi::c_void,
    reason: u32,
    _reserved: *mut std::ffi::c_void,
) {
    if reason != 1 {
        // DLL_PROCESS_ATTACH; never repeat on thread attach/detach.
        return;
    }
    let peb: usize;
    unsafe {
        std::arch::asm!("mov {}, gs:[0x60]", out(reg) peb, options(nostack, preserves_flags, readonly));
    }
    let detected = unsafe {
        winapi::um::debugapi::IsDebuggerPresent() != 0
            || (peb != 0 && std::ptr::read_volatile((peb + 2) as *const u8) != 0)
    };
    if detected || peb == 0 {
        // TerminateProcess does not run Rust destructors or loader callbacks.
        unsafe {
            winapi::um::processthreadsapi::TerminateProcess(
                winapi::um::processthreadsapi::GetCurrentProcess(),
                0,
            );
        }
        // If termination unexpectedly fails, normal startup still fails closed.
        return;
    }
    EARLY_CHECK_RAN.store(true, std::sync::atomic::Ordering::Release);
}

#[cfg(all(windows, target_arch = "x86_64"))]
#[inline(never)]
pub(crate) fn require_clear() -> Result<(), DebugError> {
    #[cfg(not(test))]
    if !EARLY_CHECK_RAN.load(std::sync::atomic::Ordering::Acquire) {
        return Err(DebugError::Unavailable);
    }
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
// winapi's CONTEXT has only 8-byte Rust alignment; Windows x64 needs 16.
#[repr(C, align(16))]
struct AlignedThreadContext(winapi::um::winnt::CONTEXT);

#[cfg(all(windows, target_arch = "x86_64"))]
#[inline(never)]
fn hardware_breakpoints() -> Result<bool, DebugError> {
    use winapi::um::{
        handleapi::CloseHandle,
        processthreadsapi::{
            GetCurrentThreadId, GetThreadContext, OpenThread, ResumeThread, SuspendThread,
        },
        winnt::{CONTEXT_DEBUG_REGISTERS, THREAD_GET_CONTEXT, THREAD_SUSPEND_RESUME},
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
        let mut context: AlignedThreadContext = unsafe { std::mem::zeroed() };
        context.0.ContextFlags = CONTEXT_DEBUG_REGISTERS;
        let suspended = unsafe { SuspendThread(target) };
        if suspended == u32::MAX {
            return Err(DebugError::Unavailable);
        }
        let captured = unsafe { GetThreadContext(target, &mut context.0) };
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
            [context.0.Dr0, context.0.Dr1, context.0.Dr2, context.0.Dr3],
            context.0.Dr7,
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
#[inline(never)]
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
    #[cfg(all(windows, target_arch = "x86_64"))]
    #[test]
    fn context_buffer_guarantees_windows_x64_abi_alignment() {
        assert_eq!(std::mem::align_of::<AlignedThreadContext>(), 16);
        let contexts: [AlignedThreadContext; 2] = unsafe { std::mem::zeroed() };
        for context in &contexts {
            assert_eq!(std::ptr::addr_of!(context.0) as usize % 16, 0);
        }
    }
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
