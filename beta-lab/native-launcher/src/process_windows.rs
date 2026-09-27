//! Windows visible-window discovery; no process command lines or account tokens.
use super::MinecraftProcess;
use anyhow::{Context, Result};
use std::path::Path;
use winapi::shared::minwindef::{BOOL, FILETIME, LPARAM, TRUE};
use winapi::shared::windef::HWND;
use winapi::um::handleapi::CloseHandle;
use winapi::um::processthreadsapi::{GetProcessTimes, OpenProcess};
use winapi::um::winbase::QueryFullProcessImageNameW;
use winapi::um::winnt::PROCESS_QUERY_LIMITED_INFORMATION;
use winapi::um::winuser::{
    EnumWindows, GetWindowTextLengthW, GetWindowTextW, GetWindowThreadProcessId, IsWindowVisible,
};

pub(super) fn process_identity(pid: u32) -> Result<(String, u64)> {
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
                super::windows_start_millis(
                    (u64::from(created.dwHighDateTime) << 32) | u64::from(created.dwLowDateTime),
                )?,
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
