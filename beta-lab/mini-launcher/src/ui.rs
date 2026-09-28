use anyhow::{Result, ensure};
use std::{
    ffi::OsStr,
    os::windows::ffi::OsStrExt,
    ptr,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU32, Ordering},
    },
};
use winapi::{
    shared::{
        minwindef::{HINSTANCE, LPARAM, LRESULT, UINT, WPARAM},
        windef::{HFONT, HWND},
    },
    um::{
        commctrl::{
            ICC_PROGRESS_CLASS, INITCOMMONCONTROLSEX, InitCommonControlsEx, PBM_SETPOS,
            PBM_SETRANGE32,
        },
        libloaderapi::GetModuleHandleW,
        wingdi::{
            CLIP_DEFAULT_PRECIS, CreateFontW, DEFAULT_CHARSET, DEFAULT_QUALITY, DeleteObject,
            FF_DONTCARE, FW_NORMAL, OUT_DEFAULT_PRECIS,
        },
        winuser::*,
    },
};

pub struct Progress {
    pub cancel: AtomicBool,
    pub phase: AtomicU32,
    pub percent: AtomicU32,
    pub launched: AtomicBool,
    error: Mutex<Option<String>>,
}
impl Progress {
    fn new() -> Self {
        Self {
            cancel: AtomicBool::new(false),
            phase: AtomicU32::new(0),
            percent: AtomicU32::new(0),
            launched: AtomicBool::new(false),
            error: Mutex::new(None),
        }
    }
}

struct Window {
    progress: Arc<Progress>,
    label: HWND,
    bar: HWND,
    cancel: HWND,
    font: HFONT,
    hidden: bool,
}

// Declared after the boxed state so every return retires the HWND first.
// Clearing its pointer also keeps cleanup messages from borrowing that state.
struct WindowLifetime {
    hwnd: HWND,
    font: HFONT,
}
impl Drop for WindowLifetime {
    fn drop(&mut self) {
        unsafe {
            if IsWindow(self.hwnd) != 0 {
                SetWindowLongPtrW(self.hwnd, GWLP_USERDATA, 0);
                KillTimer(self.hwnd, 1);
                DestroyWindow(self.hwnd);
            }
            if !self.font.is_null() {
                DeleteObject(self.font.cast());
            }
        }
    }
}
const FINISHED: UINT = WM_APP + 1;
fn wide(value: impl AsRef<OsStr>) -> Vec<u16> {
    value.as_ref().encode_wide().chain(Some(0)).collect()
}

pub fn error(message: &str) {
    unsafe {
        MessageBoxW(
            ptr::null_mut(),
            wide(message).as_ptr(),
            wide("Celeste+").as_ptr(),
            MB_OK | MB_ICONERROR,
        );
    }
}

unsafe extern "system" fn procedure(
    hwnd: HWND,
    message: UINT,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    unsafe {
        let state = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *mut Window;
        if message == WM_NCCREATE {
            let creation = &*(lparam as *const CREATESTRUCTW);
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, creation.lpCreateParams as isize);
        } else if message == WM_DESTROY {
            PostQuitMessage(0);
            return 0;
        } else if !state.is_null() {
            let state = &mut *state;
            if message == WM_CLOSE || (message == WM_COMMAND && matches!(wparam & 0xffff, 1 | 2)) {
                if !state.progress.launched.load(Ordering::Acquire) {
                    state.progress.cancel.store(true, Ordering::Release);
                    SetWindowTextW(state.label, wide("Canceling...").as_ptr());
                    EnableWindow(state.cancel, 0);
                }
                return 0;
            }
            if message == WM_TIMER {
                if state.progress.launched.load(Ordering::Acquire) {
                    state.hidden = true;
                    ShowWindow(hwnd, SW_HIDE);
                    KillTimer(hwnd, 1);
                } else if !state.progress.cancel.load(Ordering::Acquire) {
                    let percent = state.progress.percent.load(Ordering::Acquire);
                    let text = match state.progress.phase.load(Ordering::Acquire) {
                        0 => "Connecting...".to_owned(),
                        1 => format!("Downloading Celeste+... {percent}%"),
                        _ => "Opening Celeste+...".to_owned(),
                    };
                    SetWindowTextW(state.label, wide(text).as_ptr());
                    SendMessageW(state.bar, PBM_SETPOS, percent as WPARAM, 0);
                }
                return 0;
            }
            if message == FINISHED {
                if !state.progress.cancel.load(Ordering::Acquire) {
                    if let Ok(guard) = state.progress.error.lock() {
                        if let Some(message) = guard.as_ref() {
                            MessageBoxW(
                                hwnd,
                                wide(message).as_ptr(),
                                wide("Celeste+").as_ptr(),
                                MB_OK | MB_ICONERROR,
                            );
                        }
                    }
                }
                DestroyWindow(hwnd);
                return 0;
            }
        }
        DefWindowProcW(hwnd, message, wparam, lparam)
    }
}

unsafe fn control(
    kind: &str,
    text: &str,
    style: u32,
    x: i32,
    y: i32,
    width: i32,
    height: i32,
    parent: HWND,
    id: usize,
    instance: HINSTANCE,
) -> HWND {
    unsafe {
        CreateWindowExW(
            0,
            wide(kind).as_ptr(),
            wide(text).as_ptr(),
            WS_CHILD | WS_VISIBLE | style,
            x,
            y,
            width,
            height,
            parent,
            id as _,
            instance,
            ptr::null_mut(),
        )
    }
}

pub fn run(worker: fn(Arc<Progress>) -> Result<()>) -> Result<()> {
    unsafe {
        let controls = INITCOMMONCONTROLSEX {
            dwSize: std::mem::size_of::<INITCOMMONCONTROLSEX>() as u32,
            dwICC: ICC_PROGRESS_CLASS,
        };
        ensure!(
            InitCommonControlsEx(&controls) != 0,
            "Windows controls are unavailable."
        );
        let instance = GetModuleHandleW(ptr::null());
        let class_name = wide("CelestePlusMiniWindow");
        let class = WNDCLASSW {
            style: 0,
            lpfnWndProc: Some(procedure),
            cbClsExtra: 0,
            cbWndExtra: 0,
            hInstance: instance,
            hIcon: LoadIconW(ptr::null_mut(), IDI_APPLICATION),
            hCursor: LoadCursorW(ptr::null_mut(), IDC_ARROW),
            hbrBackground: (COLOR_WINDOW + 1) as _,
            lpszMenuName: ptr::null(),
            lpszClassName: class_name.as_ptr(),
        };
        ensure!(
            RegisterClassW(&class) != 0,
            "Couldn't create the download window."
        );
        let progress = Arc::new(Progress::new());
        let mut state = Box::new(Window {
            progress: progress.clone(),
            label: ptr::null_mut(),
            bar: ptr::null_mut(),
            cancel: ptr::null_mut(),
            font: ptr::null_mut(),
            hidden: false,
        });
        let width = 408;
        let height = 176;
        let hwnd = CreateWindowExW(
            0,
            class_name.as_ptr(),
            wide("Celeste+").as_ptr(),
            WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU,
            (GetSystemMetrics(SM_CXSCREEN) - width) / 2,
            (GetSystemMetrics(SM_CYSCREEN) - height) / 2,
            width,
            height,
            ptr::null_mut(),
            ptr::null_mut(),
            instance,
            (&mut *state as *mut Window).cast(),
        );
        ensure!(!hwnd.is_null(), "Couldn't create the download window.");
        let mut window_lifetime = WindowLifetime {
            hwnd,
            font: ptr::null_mut(),
        };
        state.font = CreateFontW(
            -16,
            0,
            0,
            0,
            FW_NORMAL as i32,
            0,
            0,
            0,
            DEFAULT_CHARSET as u32,
            OUT_DEFAULT_PRECIS as u32,
            CLIP_DEFAULT_PRECIS as u32,
            DEFAULT_QUALITY as u32,
            FF_DONTCARE as u32,
            wide("Segoe UI").as_ptr(),
        );
        window_lifetime.font = state.font;
        state.label = control(
            "STATIC",
            "Connecting...",
            0,
            24,
            20,
            352,
            24,
            hwnd,
            2,
            instance,
        );
        state.bar = control(
            "msctls_progress32",
            "",
            0,
            24,
            55,
            344,
            12,
            hwnd,
            3,
            instance,
        );
        state.cancel = control(
            "BUTTON",
            "Cancel",
            BS_PUSHBUTTON | WS_TABSTOP,
            288,
            84,
            80,
            28,
            hwnd,
            1,
            instance,
        );
        ensure!(
            !state.label.is_null() && !state.bar.is_null() && !state.cancel.is_null(),
            "Couldn't create Windows controls."
        );
        for control in [state.label, state.cancel] {
            SendMessageW(control, WM_SETFONT, state.font as usize, 1);
        }
        SendMessageW(state.bar, PBM_SETRANGE32, 0, 100);
        SetTimer(hwnd, 1, 100, None);
        ShowWindow(hwnd, SW_SHOW);
        UpdateWindow(hwnd);
        let window_id = hwnd as usize;
        let thread = std::thread::Builder::new()
            .name("celeste-download".into())
            .spawn(move || {
                let result = worker(progress.clone());
                if let Err(error) = result {
                    // Once the full loader is running, only background temp retirement
                    // remains. A locked leftover must not report a failed launch later.
                    if !progress.launched.load(Ordering::Acquire) {
                        if let Ok(mut message) = progress.error.lock() {
                            *message = Some(format!("{error:#}"));
                        }
                    }
                }
                PostMessageW(window_id as HWND, FINISHED, 0, 0);
            })?;
        let mut message: MSG = std::mem::zeroed();
        loop {
            let result = GetMessageW(&mut message, ptr::null_mut(), 0, 0);
            if result <= 0 {
                break;
            }
            if IsDialogMessageW(hwnd, &mut message) == 0 {
                TranslateMessage(&message);
                DispatchMessageW(&message);
            }
        }
        let _ = thread.join();
        Ok(())
    }
}
