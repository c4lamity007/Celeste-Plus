//! Shared native launch policy. Facts contain platform names, never hardware IDs.
//! Host hypervisor support/presence alone is not evidence of a guest (Windows VBS).
//! This is an environment policy, not remote attestation or tamper-proof detection.

use std::fmt;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum Denial {
    VirtualMachine,
    Sandbox,
    Unavailable,
}

impl Denial {
    pub(crate) fn code(self) -> String {
        match self {
            Self::VirtualMachine => obfstr::obfstr!("environment_vm").to_owned(),
            Self::Sandbox => obfstr::obfstr!("environment_sandbox").to_owned(),
            Self::Unavailable => obfstr::obfstr!("environment_unavailable").to_owned(),
        }
    }
}

impl fmt::Display for Denial {
    fn fmt(&self, out: &mut fmt::Formatter<'_>) -> fmt::Result {
        let message = match self {
            Self::VirtualMachine => {
                obfstr::obfstr!("Celeste cannot run inside a virtual machine. Open it on your physical computer.").to_owned()
            }
            Self::Sandbox => {
                obfstr::obfstr!("Celeste cannot run inside a sandbox or container. Open it outside that environment.").to_owned()
            }
            Self::Unavailable => {
                obfstr::obfstr!("Celeste could not verify this environment. Restore access to the system information services and try again.").to_owned()
            }
        };
        write!(out, "{message} ({})", self.code())
    }
}
impl std::error::Error for Denial {}

#[derive(Default)]
struct Facts {
    platform: Vec<String>,
    guest: bool,
    sandbox: bool,
    complete: bool,
}

fn guest_platform(value: &str) -> bool {
    let value = value.trim().to_ascii_lowercase();
    [
        obfstr::obfstr!("virtual machine"),
        obfstr::obfstr!("vmware"),
        obfstr::obfstr!("virtualbox"),
        obfstr::obfstr!("vbox"),
        obfstr::obfstr!("innotek"),
        obfstr::obfstr!("hvm domu"),
        obfstr::obfstr!("parallels"),
        obfstr::obfstr!("openstack"),
        obfstr::obfstr!("virtualmac"),
        obfstr::obfstr!("virtual platform"),
        obfstr::obfstr!("google compute engine"),
    ]
    .iter()
    .any(|needle| value.contains(needle))
        || value
            .split(|c: char| !c.is_ascii_alphanumeric())
            .any(|word| {
                [
                    obfstr::obfstr!("qemu"),
                    obfstr::obfstr!("kvm"),
                    obfstr::obfstr!("xen"),
                    obfstr::obfstr!("bhyve"),
                    obfstr::obfstr!("bochs"),
                ]
                .contains(&word)
            })
        || (value.contains(obfstr::obfstr!("amazon ec2"))
            && !value.contains(obfstr::obfstr!(".metal")))
}

fn decide(facts: &Facts) -> Result<(), Denial> {
    if facts.sandbox {
        return Err(Denial::Sandbox);
    }
    if facts.guest || guest_platform(&facts.platform.join(" ")) {
        return Err(Denial::VirtualMachine);
    }
    if !facts.complete {
        return Err(Denial::Unavailable);
    }
    Ok(())
}

fn meaningful(value: &str) -> bool {
    let value = value.trim().to_ascii_lowercase();
    !value.is_empty()
        && !matches!(
            value.as_str(),
            "unknown" | "none" | "not specified" | "default string" | "to be filled by o.e.m."
        )
}

#[cfg(any(windows, test))]
fn guest_evidence(
    hypervisor: bool,
    vendor: &str,
    exclusive_guest_driver: bool,
    shared_guest_hint: bool,
) -> bool {
    let vendor = vendor.trim().to_ascii_lowercase();
    // Microsoft's hypervisor is also present on physical Windows VBS hosts.
    // Host VM network adapters and shared vmci drivers do not establish a guest.
    exclusive_guest_driver
        || (hypervisor
            && ([
                obfstr::obfstr!("vmwarevmware"),
                obfstr::obfstr!("vboxvboxvbox"),
                obfstr::obfstr!("kvmkvmkvm"),
                obfstr::obfstr!("xenvmmxenvmm"),
                obfstr::obfstr!("bhyve bhyve"),
                obfstr::obfstr!("prl hyperv"),
            ]
            .contains(&vendor.as_str())
                || (vendor != obfstr::obfstr!("microsoft hv") && shared_guest_hint)))
}

/// No network, persistence, credential access, or revocation occurs here.
pub(crate) fn require_allowed() -> Result<(), Denial> {
    decide(&probe()?)
}

#[cfg(windows)]
fn probe() -> Result<Facts, Denial> {
    use serde::Deserialize;
    use winapi::um::libloaderapi::GetModuleHandleW;
    use winreg::{
        RegKey,
        enums::{HKEY_LOCAL_MACHINE, KEY_READ, KEY_WOW64_64KEY},
    };
    use wmi::{COMLibrary, WMIConnection};

    // Query only this process's token/module state. Installed host VM software
    // and HypervisorPresent are deliberately not guest/sandbox indicators.
    let sandboxie: Vec<u16> = obfstr::obfstr!("SbieDll.dll\0").encode_utf16().collect();
    if unsafe { !GetModuleHandleW(sandboxie.as_ptr()).is_null() } || windows_app_container()? {
        return Ok(Facts {
            sandbox: true,
            ..Facts::default()
        });
    }
    let control = RegKey::predef(HKEY_LOCAL_MACHINE)
        .open_subkey_with_flags(
            obfstr::obfstr!("SYSTEM\\CurrentControlSet\\Control"),
            KEY_READ | KEY_WOW64_64KEY,
        )
        .map_err(|_| Denial::Unavailable)?;
    // Microsoft PerfView uses this value's presence to identify Windows containers.
    match control.get_raw_value(obfstr::obfstr!("ContainerType")) {
        Ok(_) => {
            return Ok(Facts {
                sandbox: true,
                ..Facts::default()
            });
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(_) => return Err(Denial::Unavailable),
    }
    #[derive(Deserialize)]
    #[serde(rename_all = "PascalCase")]
    struct System {
        manufacturer: Option<String>,
        model: Option<String>,
    }
    let platform = (|| -> Result<Vec<String>, Denial> {
        let com = COMLibrary::new().map_err(|_| Denial::Unavailable)?;
        let wmi = WMIConnection::new(com).map_err(|_| Denial::Unavailable)?;
        let systems: Vec<System> = wmi
            .raw_query(obfstr::obfstr!(
                "SELECT Manufacturer,Model FROM Win32_ComputerSystem"
            ))
            .map_err(|_| Denial::Unavailable)?;
        let [system] = systems.as_slice() else {
            return Err(Denial::Unavailable);
        };
        let manufacturer = system.manufacturer.clone().unwrap_or_default();
        let model = system.model.clone().unwrap_or_default();
        if !meaningful(&manufacturer) || !meaningful(&model) {
            return Err(Denial::Unavailable);
        }
        Ok(vec![manufacturer, model])
    })()
    .or_else(|_| {
        let identity = crate::hardware_v2::firmware::read().map_err(|_| Denial::Unavailable)?;
        if !meaningful(&identity.system[0]) || !meaningful(&identity.system[1]) {
            return Err(Denial::Unavailable);
        }
        Ok(vec![identity.system[0].clone(), identity.system[1].clone()])
    })?;
    let (hypervisor, hypervisor_vendor) = windows_hypervisor();
    let services = RegKey::predef(HKEY_LOCAL_MACHINE)
        .open_subkey_with_flags(
            obfstr::obfstr!("SYSTEM\\CurrentControlSet\\Services"),
            KEY_READ | KEY_WOW64_64KEY,
        )
        .map_err(|_| Denial::Unavailable)?;
    let present = |name: &str| -> Result<bool, Denial> {
        match services.open_subkey_with_flags(name, KEY_READ | KEY_WOW64_64KEY) {
            Ok(_) => Ok(true),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(false),
            Err(_) => Err(Denial::Unavailable),
        }
    };
    let exclusive = present(obfstr::obfstr!("VBoxGuest"))?
        || present(obfstr::obfstr!("VBoxMouse"))?
        || present(obfstr::obfstr!("prl_tg"))?;
    let mut shared = present(obfstr::obfstr!("vmci"))? || present(obfstr::obfstr!("vmxnet"))?;
    let mut system_directory = [0u16; 32768];
    let system_length = unsafe {
        winapi::um::sysinfoapi::GetSystemDirectoryW(
            system_directory.as_mut_ptr(),
            system_directory.len() as u32,
        )
    } as usize;
    if system_length == 0 || system_length >= system_directory.len() {
        return Err(Denial::Unavailable);
    }
    let system_directory = std::path::PathBuf::from(
        String::from_utf16(&system_directory[..system_length]).map_err(|_| Denial::Unavailable)?,
    );
    let exclusive = exclusive
        || system_directory
            .join(obfstr::obfstr!("drivers\\VBoxMouse.sys"))
            .is_file();
    shared |= system_directory
        .join(obfstr::obfstr!("vmtray.dll"))
        .is_file();
    #[derive(Deserialize)]
    #[serde(rename_all = "PascalCase")]
    struct Adapter {
        #[serde(rename = "MACAddress")]
        mac_address: Option<String>,
    }
    let adapters = (|| -> Result<Vec<String>, Denial> {
        let com = COMLibrary::new().map_err(|_| Denial::Unavailable)?;
        let wmi = WMIConnection::new(com).map_err(|_| Denial::Unavailable)?;
        let adapters: Vec<Adapter> = wmi
            .raw_query(obfstr::obfstr!(
                "SELECT MACAddress FROM Win32_NetworkAdapter WHERE MACAddress IS NOT NULL"
            ))
            .map_err(|_| Denial::Unavailable)?;
        Ok(adapters
            .into_iter()
            .filter_map(|adapter| adapter.mac_address)
            .collect())
    })()
    .or_else(|_| windows_adapter_addresses())?;
    shared |= adapters.iter().any(|address| guest_mac(address));
    Ok(Facts {
        platform: platform.clone(),
        guest: guest_evidence(hypervisor, &hypervisor_vendor, exclusive, shared),
        complete: platform.iter().all(|value| meaningful(value)),
        ..Facts::default()
    })
}

#[cfg(any(windows, test))]
fn guest_mac(address: &str) -> bool {
    let prefix = address.to_ascii_lowercase().replace('-', ":");
    [
        obfstr::obfstr!("08:00:27:"),
        obfstr::obfstr!("00:05:69:"),
        obfstr::obfstr!("00:0c:29:"),
        obfstr::obfstr!("00:50:56:"),
        "00:1c:42:",
    ]
    .iter()
    .any(|value| prefix.starts_with(value))
}

#[cfg(windows)]
fn windows_adapter_addresses() -> Result<Vec<String>, Denial> {
    use winapi::um::{iphlpapi::GetAdaptersAddresses, iptypes::IP_ADAPTER_ADDRESSES};
    // AF_UNSPEC plus INCLUDE_ALL_INTERFACES covers IPv4, IPv6 and unbound adapters.
    // Skip address/DNS/name sublists: only each adapter MAC is used as a weak hint.
    let mut needed = 0;
    let status = unsafe {
        GetAdaptersAddresses(
            0,
            0x12f,
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            &mut needed,
        )
    };
    if status == 232 {
        return Ok(Vec::new());
    } // ERROR_NO_DATA: no adapters.
    if status != 111
        || needed as usize > 1024 * 1024
        || (needed as usize) < std::mem::size_of::<IP_ADAPTER_ADDRESSES>()
    {
        return Err(Denial::Unavailable);
    }
    // Allocate with the API structure's alignment; Windows fills this buffer.
    let count = (needed as usize).div_ceil(std::mem::size_of::<IP_ADAPTER_ADDRESSES>());
    let mut rows: Vec<IP_ADAPTER_ADDRESSES> =
        (0..count).map(|_| unsafe { std::mem::zeroed() }).collect();
    if unsafe {
        GetAdaptersAddresses(
            0,
            0x12f,
            std::ptr::null_mut(),
            rows.as_mut_ptr(),
            &mut needed,
        )
    } != 0
    {
        return Err(Denial::Unavailable);
    }
    let base = rows.as_ptr() as usize;
    let end = base + rows.len() * std::mem::size_of::<IP_ADAPTER_ADDRESSES>();
    let mut cursor = rows.as_ptr();
    let mut result = Vec::new();
    for _ in 0..4096 {
        if cursor.is_null() {
            return Ok(result);
        }
        let address = cursor as usize;
        if address < base
            || address
                .checked_add(std::mem::size_of::<IP_ADAPTER_ADDRESSES>())
                .is_none_or(|v| v > end)
            || address % std::mem::align_of::<IP_ADAPTER_ADDRESSES>() != 0
        {
            return Err(Denial::Unavailable);
        }
        let row = unsafe { &*cursor };
        if row.PhysicalAddressLength as usize > row.PhysicalAddress.len() {
            return Err(Denial::Unavailable);
        }
        if row.PhysicalAddressLength > 0 {
            result.push(
                row.PhysicalAddress[..row.PhysicalAddressLength as usize]
                    .iter()
                    .map(|b| format!("{b:02x}"))
                    .collect::<Vec<_>>()
                    .join(":"),
            );
        }
        cursor = row.Next;
    }
    Err(Denial::Unavailable)
}

#[cfg(all(windows, target_arch = "x86_64"))]
fn windows_hypervisor() -> (bool, String) {
    let flags = std::arch::x86_64::__cpuid(1);
    if flags.ecx & (1 << 31) == 0 {
        return (false, String::new());
    }
    let leaf = std::arch::x86_64::__cpuid(0x40000000);
    let mut bytes = Vec::new();
    bytes.extend_from_slice(&leaf.ebx.to_le_bytes());
    bytes.extend_from_slice(&leaf.ecx.to_le_bytes());
    bytes.extend_from_slice(&leaf.edx.to_le_bytes());
    (
        true,
        String::from_utf8_lossy(&bytes)
            .trim_end_matches('\0')
            .to_owned(),
    )
}

#[cfg(all(windows, not(target_arch = "x86_64")))]
fn windows_hypervisor() -> (bool, String) {
    (false, String::new())
}

#[cfg(windows)]
fn windows_app_container() -> Result<bool, Denial> {
    use winapi::um::{
        handleapi::CloseHandle,
        processthreadsapi::{GetCurrentProcess, OpenProcessToken},
        securitybaseapi::GetTokenInformation,
        winnt::{TOKEN_QUERY, TokenIsAppContainer},
    };
    let mut token = std::ptr::null_mut();
    let mut value = 0u32;
    let mut size = 0u32;
    unsafe {
        if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) == 0 {
            return Err(Denial::Unavailable);
        }
        let result = GetTokenInformation(
            token,
            TokenIsAppContainer,
            (&mut value as *mut u32).cast(),
            std::mem::size_of::<u32>() as u32,
            &mut size,
        );
        CloseHandle(token);
        if result == 0 || size != std::mem::size_of::<u32>() as u32 {
            return Err(Denial::Unavailable);
        }
    }
    Ok(value != 0)
}

#[cfg(any(target_os = "linux", target_os = "macos", test))]
fn detector_result(exit: Option<i32>, stdout: &str, stderr: &str) -> Result<bool, Denial> {
    let value = stdout.trim();
    match (exit, value, stderr.trim().is_empty()) {
        (Some(1), "none", true) => Ok(false),
        (Some(0), value, true)
            if !value.is_empty()
                && value != "none"
                && value.len() <= 80
                && value
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-') =>
        {
            Ok(true)
        }
        _ => Err(Denial::Unavailable),
    }
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn command(program: &str, args: &[&str]) -> Result<(Option<i32>, String, String), Denial> {
    use std::process::{Command, Stdio};
    use std::time::{Duration, Instant};
    // Fixed absolute executables and arguments; no shell or PATH lookup.
    let mut child = Command::new(program)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|_| Denial::Unavailable)?;
    let deadline = Instant::now() + Duration::from_secs(3);
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(10)),
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(Denial::Unavailable);
            }
        }
    }
    let output = child.wait_with_output().map_err(|_| Denial::Unavailable)?;
    if output.stdout.len() > 8192 || output.stderr.len() > 8192 {
        return Err(Denial::Unavailable);
    }
    Ok((
        output.status.code(),
        String::from_utf8(output.stdout).map_err(|_| Denial::Unavailable)?,
        String::from_utf8(output.stderr).map_err(|_| Denial::Unavailable)?,
    ))
}

#[cfg(any(target_os = "linux", test))]
fn cgroup_sandbox(value: &str) -> bool {
    value.lines().any(|line| {
        let path = line
            .splitn(3, ':')
            .nth(2)
            .unwrap_or("")
            .to_ascii_lowercase();
        [
            "/docker/",
            "/docker-",
            "/kubepods",
            "/lxc/",
            "/lxc.payload.",
            "/libpod-",
            "/machine.slice/machine-lxc",
            "/garden/",
        ]
        .iter()
        .any(|s| path.contains(s))
    })
}

#[cfg(target_os = "linux")]
fn probe() -> Result<Facts, Denial> {
    use std::path::Path;
    let mut facts = Facts::default();
    for marker in [
        "/.dockerenv",
        "/run/.containerenv",
        "/.flatpak-info",
        "/run/systemd/container",
    ] {
        match Path::new(marker).try_exists() {
            Ok(true) => {
                return Ok(Facts {
                    sandbox: true,
                    ..facts
                });
            }
            Ok(false) => {}
            Err(_) => return Err(Denial::Unavailable),
        }
    }
    if ["container", "FLATPAK_ID", "SNAP"]
        .iter()
        .any(|k| std::env::var_os(k).is_some_and(|v| !v.is_empty()))
    {
        return Ok(Facts {
            sandbox: true,
            ..facts
        });
    }
    let osrelease =
        std::fs::read_to_string("/proc/sys/kernel/osrelease").map_err(|_| Denial::Unavailable)?;
    if osrelease.to_ascii_lowercase().contains("microsoft") {
        return Ok(Facts {
            sandbox: true,
            ..facts
        }); // WSL1 is a subsystem; WSL2 is a VM.
    }
    for path in ["/proc/self/cgroup", "/proc/1/cgroup"] {
        let value = std::fs::read_to_string(path).map_err(|_| Denial::Unavailable)?;
        if cgroup_sandbox(&value) {
            return Ok(Facts {
                sandbox: true,
                ..facts
            });
        }
    }
    let detector = if Path::new("/usr/bin/systemd-detect-virt")
        .try_exists()
        .map_err(|_| Denial::Unavailable)?
    {
        "/usr/bin/systemd-detect-virt"
    } else {
        "/bin/systemd-detect-virt"
    };
    let (status, out, err) = command(detector, &["--container"])?;
    facts.sandbox = detector_result(status, &out, &err)?;
    if facts.sandbox {
        return Ok(facts);
    }
    let (status, out, err) = command(detector, &["--vm"])?;
    facts.guest = detector_result(status, &out, &err)?;
    for flag in ["--chroot", "--private-users"] {
        let (status, out, err) = command(detector, &[flag])?;
        match (status, out.trim().is_empty(), err.trim().is_empty()) {
            (Some(0), true, true) => facts.sandbox = true,
            (Some(1), true, true) => {}
            _ => return Err(Denial::Unavailable),
        }
    }
    facts.complete = meaningful(&osrelease);
    Ok(facts)
}

#[cfg(any(target_os = "macos", test))]
fn mac_guest_result(exit: Option<i32>, value: &str, stderr: &str) -> Result<bool, Denial> {
    match (exit, value.trim(), stderr.trim().is_empty()) {
        (Some(0), "0", true) => Ok(false),
        (Some(0), "1", true) => Ok(true),
        _ => Err(Denial::Unavailable),
    }
}

#[cfg(target_os = "macos")]
fn probe() -> Result<Facts, Denial> {
    #[link(name = "sandbox")]
    unsafe extern "C" {
        fn sandbox_check(pid: i32, operation: *const std::ffi::c_char, kind: i32, ...) -> i32;
    }
    // Read-only query of our own process; this does not apply/change a sandbox.
    // Chromium's Seatbelt::IsSandboxed uses this same null-operation API.
    let sandbox =
        sandbox_status(unsafe { sandbox_check(std::process::id() as i32, std::ptr::null(), 0) })?;
    if sandbox || std::env::var_os("APP_SANDBOX_CONTAINER_ID").is_some_and(|v| !v.is_empty()) {
        return Ok(Facts {
            sandbox: true,
            ..Facts::default()
        });
    }
    let (status, model, err) = command("/usr/sbin/sysctl", &["-n", "hw.model"])?;
    if status != Some(0) || !err.trim().is_empty() || !meaningful(&model) {
        return Err(Denial::Unavailable);
    }
    // kern.hv_support reports host capabilities, not whether this OS is a guest.
    let (status, value, err) = command("/usr/sbin/sysctl", &["-n", "kern.hv_vmm_present"])?;
    Ok(Facts {
        platform: vec![model],
        guest: mac_guest_result(status, &value, &err)?,
        complete: true,
        ..Facts::default()
    })
}

#[cfg(any(target_os = "macos", test))]
fn sandbox_status(status: i32) -> Result<bool, Denial> {
    match status {
        0 => Ok(false),
        1 => Ok(true),
        _ => Err(Denial::Unavailable),
    }
}

#[cfg(not(any(windows, target_os = "linux", target_os = "macos")))]
fn probe() -> Result<Facts, Denial> {
    Err(Denial::Unavailable)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(windows)]
    #[test]
    #[ignore = "Read-only native adapter and firmware fallback query; no identifiers emitted"]
    fn local_windows_fallback_queries() {
        assert!(
            windows_adapter_addresses().is_ok(),
            "native adapters unavailable"
        );
        let identity = crate::hardware_v2::firmware::read().expect("firmware unavailable");
        assert!(meaningful(&identity.system[0]) && meaningful(&identity.system[1]));
    }

    #[test]
    fn fallback_mac_hints_keep_the_same_vm_rules() {
        assert!(guest_mac("08-00-27-12-34-56"));
        assert!(guest_mac("00:0C:29:12:34:56"));
        assert!(!guest_mac("10:20:30:40:50:60"));
        assert!(!guest_evidence(
            true,
            "Microsoft Hv",
            false,
            guest_mac("08:00:27:12:34:56")
        ));
        assert!(guest_evidence(
            true,
            "KVMKVMKVM",
            false,
            guest_mac("08:00:27:12:34:56")
        ));
    }

    #[test]
    fn guest_signals_preserve_physical_vbs_and_host_vm_software() {
        assert!(!guest_evidence(true, "Microsoft Hv", false, true));
        assert!(!guest_evidence(false, "", false, true));
        assert!(!guest_evidence(true, "Microsoft Hv", false, false));
        assert!(guest_evidence(false, "", true, false));
        assert!(guest_evidence(true, "VMwareVMware", false, false));
        assert!(guest_evidence(true, "unknown", false, true));
    }

    #[test]
    fn recognized_guest_platforms_are_denied() {
        for value in [
            "VMware, Inc. VMware Virtual Platform",
            "innotek GmbH VirtualBox",
            "Microsoft Corporation Virtual Machine",
            "QEMU Standard PC (Q35 + ICH9, 2009)",
            "KVM",
            "Xen HVM domU",
            "Parallels Software International Inc.",
            "bhyve",
            "Bochs",
            "OpenStack Nova",
            "VirtualMac2,1",
            "Amazon EC2",
            "Google Compute Engine",
        ] {
            let facts = Facts {
                platform: vec![value.into()],
                complete: true,
                ..Facts::default()
            };
            assert_eq!(decide(&facts), Err(Denial::VirtualMachine), "{value}");
        }
    }

    #[test]
    fn physical_hyperv_vbs_hosts_and_mac_models_are_allowed() {
        // No HypervisorPresent bit is consumed: VBS can set it on physical PCs.
        for value in [
            "Microsoft Corporation Surface Laptop 6",
            "ASUSTeK COMPUTER INC. ROG STRIX",
            "Dell Inc. XPS 15",
            "LENOVO ThinkPad",
            "MacBookPro18,3",
            "Mac14,2",
            "XPG Xenia 14",
            "Amazon EC2 c5.metal",
        ] {
            assert_eq!(
                decide(&Facts {
                    platform: vec![value.into()],
                    complete: true,
                    ..Facts::default()
                }),
                Ok(())
            );
        }
    }

    #[test]
    fn sandbox_wins_without_reading_hardware_and_missing_facts_never_allow() {
        assert_eq!(
            decide(&Facts {
                sandbox: true,
                ..Facts::default()
            }),
            Err(Denial::Sandbox)
        );
        assert_eq!(decide(&Facts::default()), Err(Denial::Unavailable));
        assert_eq!(
            decide(&Facts {
                guest: true,
                ..Facts::default()
            }),
            Err(Denial::VirtualMachine)
        );
        for value in ["", "unknown", " Default String ", "To be filled by O.E.M."] {
            assert!(!meaningful(value));
        }
    }

    #[test]
    fn detector_errors_and_malformed_output_do_not_mean_physical() {
        assert_eq!(detector_result(Some(1), "none\n", ""), Ok(false));
        for id in [
            "kvm",
            obfstr::obfstr!("vmware"),
            "microsoft",
            "docker",
            "podman",
            "lxc",
            "wsl",
            "bwrap",
        ] {
            assert_eq!(detector_result(Some(0), id, ""), Ok(true));
        }
        for (status, out, err) in [
            (None, "", ""),
            (Some(127), "", ""),
            (Some(1), "", ""),
            (Some(1), "none", "Permission denied"),
            (Some(0), "none", ""),
            (Some(0), "unexpected output", ""),
        ] {
            assert_eq!(detector_result(status, out, err), Err(Denial::Unavailable));
        }
    }

    #[test]
    fn container_cgroups_do_not_confuse_regular_desktop_services() {
        for value in [
            "0::/docker/abc",
            "0::/system.slice/docker-abc.scope",
            "0::/kubepods.slice/pod1",
            "2:memory:/lxc/game",
            "0::/machine.slice/libpod-abc.scope",
        ] {
            assert!(cgroup_sandbox(value));
        }
        for value in [
            "0::/",
            "0::/user.slice/user-1000.slice/session-2.scope",
            "0::/system.slice/docker.service",
        ] {
            assert!(!cgroup_sandbox(value));
        }
    }

    #[test]
    fn mac_guest_status_is_not_host_hypervisor_support() {
        assert_eq!(mac_guest_result(Some(0), "0\n", ""), Ok(false));
        assert_eq!(mac_guest_result(Some(0), "1\n", ""), Ok(true));
        for (exit, value, err) in [
            (Some(1), "", "unknown oid"),
            (Some(0), "true", ""),
            (Some(0), "", ""),
            (None, "0", ""),
        ] {
            assert_eq!(mac_guest_result(exit, value, err), Err(Denial::Unavailable));
        }
    }

    #[test]
    fn seatbelt_status_is_required_even_without_environment_marker() {
        assert_eq!(sandbox_status(0), Ok(false));
        assert_eq!(sandbox_status(1), Ok(true));
        assert_eq!(sandbox_status(-1), Err(Denial::Unavailable));
        assert_eq!(sandbox_status(2), Err(Denial::Unavailable));
    }

    #[test]
    fn errors_never_include_collected_values() {
        let secret_fixture = "not-a-real-serial-user-supplied-fixture";
        let facts = Facts {
            platform: vec![format!("VMware {secret_fixture}")],
            complete: true,
            ..Facts::default()
        };
        let error = decide(&facts).unwrap_err();
        assert_eq!(error.code(), "environment_vm");
        assert!(!format!("{error:?}: {error}").contains(secret_fixture));
    }

    #[test]
    #[ignore = "Opt-in read-only host probe; prints only the policy outcome, no platform identifiers"]
    fn local_environment_probe() {
        match require_allowed() {
            Ok(()) => println!("environment_allowed"),
            Err(error) => println!("{}", error.code()),
        }
    }
}
