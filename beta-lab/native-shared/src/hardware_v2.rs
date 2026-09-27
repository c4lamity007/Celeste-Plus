//! Additive Windows fingerprint. Legacy component hashes remain unchanged.
//! Only the final digest is cached; raw hardware values are never persisted/logged.
#[cfg(any(windows, test))]
use sha2::{Digest, Sha256};
#[cfg(any(windows, test))]
#[path = "windows_firmware.rs"]
pub(crate) mod firmware;

#[cfg(any(windows, test))]
fn digest(cpu: &str, disk: &str, guid: &str) -> Result<String, String> {
    fn normalized(value: &str) -> Result<String, String> {
        let value = value.trim().to_ascii_lowercase();
        if value.is_empty()
            || value.len() > 1024
            || value.contains([':', '\0', '\n', '\r'])
            || matches!(
                value.as_str(),
                "unknown" | "none" | "default string" | "to be filled by o.e.m."
            )
        {
            return Err(obfstr::obfstr!("Hardware identity is unavailable").to_owned());
        }
        Ok(value)
    }
    let value = format!(
        "{}:{}:{}",
        normalized(cpu)?,
        normalized(disk)?,
        normalized(guid)?
    );
    Ok(format!("{:x}", Sha256::digest(value.as_bytes())))
}

#[cfg(windows)]
#[inline(never)]
pub(crate) fn current() -> anyhow::Result<Option<String>> {
    static VALUE: std::sync::OnceLock<Result<String, String>> = std::sync::OnceLock::new();
    VALUE
        .get_or_init(|| {
            windows::collect().map_err(|_| {
                obfstr::obfstr!("Windows hardware identity could not be read").to_owned()
            })
        })
        .clone()
        .map(Some)
        .map_err(anyhow::Error::msg)
}

#[cfg(not(windows))]
pub(crate) fn current() -> anyhow::Result<Option<String>> {
    Ok(None)
}

#[cfg(any(windows, test))]
fn processor_ids_from_smbios(raw: &[u8]) -> Result<Vec<String>, String> {
    if raw.len() < 8 {
        return Err(obfstr::obfstr!("Invalid firmware table").to_owned());
    }
    let length = u32::from_le_bytes(raw[4..8].try_into().unwrap()) as usize;
    if length != raw.len() - 8 {
        return Err(obfstr::obfstr!("Invalid firmware length").to_owned());
    }
    let mut result = Vec::new();
    let mut offset = 8;
    while offset + 4 <= raw.len() {
        let kind = raw[offset];
        let size = raw[offset + 1] as usize;
        if size < 4 || offset + size > raw.len() {
            return Err(obfstr::obfstr!("Invalid firmware structure").to_owned());
        }
        if kind == 4 && size >= 16 {
            let id = u64::from_le_bytes(raw[offset + 8..offset + 16].try_into().unwrap());
            if id != 0 && id != u64::MAX {
                result.push(format!("{id:016x}"));
            }
        }
        let strings = &raw[offset + size..];
        let end = strings
            .windows(2)
            .position(|pair| pair == [0, 0])
            .ok_or_else(|| obfstr::obfstr!("Invalid firmware strings").to_owned())?;
        offset += size + end + 2;
        if kind == 127 {
            break;
        }
    }
    result.sort();
    result.dedup();
    if result.is_empty() {
        return Err(obfstr::obfstr!("Processor identity unavailable").to_owned());
    }
    Ok(result)
}

#[cfg(any(windows, test))]
fn serial_from_descriptor(data: &[u8]) -> Result<String, String> {
    // STORAGE_DEVICE_DESCRIPTOR fixed header: SerialNumberOffset at byte 24.
    if data.len() < 36 {
        return Err(obfstr::obfstr!("Invalid storage descriptor").to_owned());
    }
    let declared = u32::from_le_bytes(data[4..8].try_into().unwrap()) as usize;
    let offset = u32::from_le_bytes(data[24..28].try_into().unwrap()) as usize;
    if declared > data.len() || declared < 36 || offset < 36 || offset >= declared {
        return Err(obfstr::obfstr!("Invalid storage serial offset").to_owned());
    }
    let tail = &data[offset..declared];
    let length = tail
        .iter()
        .position(|byte| *byte == 0)
        .ok_or_else(|| obfstr::obfstr!("Unterminated storage serial").to_owned())?;
    if length == 0 || length > 256 {
        return Err(obfstr::obfstr!("Invalid storage serial").to_owned());
    }
    let serial = std::str::from_utf8(&tail[..length])
        .map_err(|_| obfstr::obfstr!("Invalid storage serial").to_owned())?
        .trim();
    if serial.is_empty() || !serial.is_ascii() {
        return Err(obfstr::obfstr!("Empty storage serial").to_owned());
    }
    Ok(serial.to_owned())
}

#[cfg(windows)]
mod windows {
    use super::*;
    use anyhow::{Result, anyhow, ensure};
    use serde::Deserialize;
    use winapi::um::{
        fileapi::{CreateFileW, OPEN_EXISTING},
        handleapi::{CloseHandle, INVALID_HANDLE_VALUE},
        ioapiset::DeviceIoControl,
        sysinfoapi::{GetSystemFirmwareTable, GetWindowsDirectoryW},
        winioctl::{IOCTL_STORAGE_QUERY_PROPERTY, IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS},
        winnt::{FILE_SHARE_READ, FILE_SHARE_WRITE},
    };

    struct Handle(winapi::shared::ntdef::HANDLE);
    impl Drop for Handle {
        fn drop(&mut self) {
            unsafe {
                CloseHandle(self.0);
            }
        }
    }
    fn open(path: &str) -> Result<Handle> {
        let path: Vec<u16> = path.encode_utf16().chain(Some(0)).collect();
        let handle = unsafe {
            CreateFileW(
                path.as_ptr(),
                0,
                FILE_SHARE_READ | FILE_SHARE_WRITE,
                std::ptr::null_mut(),
                OPEN_EXISTING,
                0,
                std::ptr::null_mut(),
            )
        };
        ensure!(
            handle != INVALID_HANDLE_VALUE,
            "{}",
            obfstr::obfstr!("System storage device is unavailable")
        );
        Ok(Handle(handle))
    }
    fn ioctl(handle: &Handle, code: u32, query: &mut [u8]) -> Result<Vec<u8>> {
        let mut output = vec![0u8; 65536];
        let mut count = 0;
        let ok = unsafe {
            DeviceIoControl(
                handle.0,
                code,
                if query.is_empty() {
                    std::ptr::null_mut()
                } else {
                    query.as_mut_ptr().cast()
                },
                query.len() as u32,
                output.as_mut_ptr().cast(),
                output.len() as u32,
                &mut count,
                std::ptr::null_mut(),
            )
        };
        ensure!(
            ok != 0 && count as usize <= output.len(),
            "{}",
            obfstr::obfstr!("System storage query failed")
        );
        output.truncate(count as usize);
        Ok(output)
    }
    fn disk() -> Result<String> {
        let mut path = [0u16; 32768];
        let length = unsafe { GetWindowsDirectoryW(path.as_mut_ptr(), path.len() as u32) } as usize;
        ensure!(
            length >= 3 && length < path.len() && path[1] == b':' as u16,
            "{}",
            obfstr::obfstr!("Windows system drive is unavailable")
        );
        let drive = char::from_u32(path[0] as u32)
            .filter(char::is_ascii_alphabetic)
            .ok_or_else(|| anyhow!("{}", obfstr::obfstr!("Invalid system drive")))?;
        let volume = open(&format!("\\\\.\\{drive}:"))?;
        let extents = ioctl(&volume, IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS, &mut [])?;
        ensure!(
            extents.len() >= 32,
            "{}",
            obfstr::obfstr!("Invalid system disk extents")
        );
        let count = u32::from_le_bytes(extents[..4].try_into().unwrap()) as usize;
        ensure!(
            (1..=32).contains(&count) && 8 + count * 24 <= extents.len(),
            "{}",
            obfstr::obfstr!("Invalid system disk count")
        );
        let mut serials = Vec::new();
        for index in 0..count {
            let start = 8 + index * 24;
            let number = u32::from_le_bytes(extents[start..start + 4].try_into().unwrap());
            let device = open(&format!("\\\\.\\PhysicalDrive{number}"))?;
            // StorageDeviceProperty=0, PropertyStandardQuery=0, additional params zero.
            let descriptor = ioctl(&device, IOCTL_STORAGE_QUERY_PROPERTY, &mut [0u8; 12])?;
            serials.push(serial_from_descriptor(&descriptor).map_err(anyhow::Error::msg)?);
        }
        serials.sort();
        serials.dedup();
        Ok(serials.join("+"))
    }
    fn firmware_cpu() -> Result<String> {
        let provider = u32::from_be_bytes(*b"RSMB");
        let length = unsafe { GetSystemFirmwareTable(provider, 0, std::ptr::null_mut(), 0) };
        ensure!(
            (8..=4 * 1024 * 1024).contains(&length),
            "{}",
            obfstr::obfstr!("Firmware identity is unavailable")
        );
        let mut data = vec![0u8; length as usize];
        ensure!(
            unsafe { GetSystemFirmwareTable(provider, 0, data.as_mut_ptr().cast(), length) }
                == length,
            "{}",
            obfstr::obfstr!("Firmware table changed")
        );
        Ok(processor_ids_from_smbios(&data)
            .map_err(anyhow::Error::msg)?
            .join("+"))
    }
    fn cpu() -> Result<String> {
        #[derive(Deserialize)]
        #[serde(rename_all = "PascalCase")]
        struct Processor {
            processor_id: Option<String>,
        }
        let queried = (|| -> Result<String> {
            let com = wmi::COMLibrary::new()?;
            let connection = wmi::WMIConnection::new(com)?;
            let values: Vec<Processor> =
                connection.raw_query(obfstr::obfstr!("SELECT ProcessorId FROM Win32_Processor"))?;
            let mut ids: Vec<String> = values
                .into_iter()
                .filter_map(|value| value.processor_id)
                .map(|value| value.trim().to_ascii_lowercase())
                .collect();
            ensure!(
                !ids.is_empty()
                    && ids.iter().all(|id| id.len() == 16
                        && id.bytes().all(|b| b.is_ascii_hexdigit())
                        && id != "0000000000000000"
                        && id != "ffffffffffffffff"),
                "{}",
                obfstr::obfstr!("Processor identity unavailable")
            );
            ids.sort();
            ids.dedup();
            Ok(ids.join("+"))
        })();
        queried.or_else(|_| firmware_cpu())
    }
    pub(super) fn collect() -> Result<String> {
        use winreg::{
            RegKey,
            enums::{HKEY_LOCAL_MACHINE, KEY_READ, KEY_WOW64_64KEY},
        };
        let key = RegKey::predef(HKEY_LOCAL_MACHINE).open_subkey_with_flags(
            obfstr::obfstr!("SOFTWARE\\Microsoft\\Cryptography"),
            KEY_READ | KEY_WOW64_64KEY,
        )?;
        let guid: String = key.get_value(obfstr::obfstr!("MachineGuid"))?;
        digest(&cpu()?, &disk()?, &guid).map_err(anyhow::Error::msg)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(windows)]
    #[test]
    #[ignore = "Reads this computer's fingerprint; writes only the final digest to ignored local QA evidence"]
    fn actual_windows_fingerprint_parity_probe() {
        let digest = current().unwrap().unwrap();
        assert_eq!(Some(digest.clone()), current().unwrap());
        let directory = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../build/native-hardening-qa");
        std::fs::create_dir_all(&directory).unwrap();
        std::fs::write(directory.join("hwid-v2.txt"), digest).unwrap();
    }
    #[test]
    fn identity_is_stable_normalized_and_component_bound() {
        let original = digest(" CPU ", "Disk", "Guid").unwrap();
        assert_eq!(original, digest("cpu", "disk", "guid").unwrap());
        for (a, b, c) in [
            ("new", "disk", "guid"),
            ("cpu", "new", "guid"),
            ("cpu", "disk", "new"),
        ] {
            assert_ne!(original, digest(a, b, c).unwrap());
        }
        assert!(digest("", "disk", "guid").is_err());
        assert!(digest("cpu:disk", "disk", "guid").is_err());
    }
    #[test]
    fn storage_offsets_and_terminators_are_bounded() {
        let mut data = vec![0u8; 44];
        data[4..8].copy_from_slice(&44u32.to_le_bytes());
        data[24..28].copy_from_slice(&36u32.to_le_bytes());
        data[36..43].copy_from_slice(b" SERIAL");
        assert_eq!(serial_from_descriptor(&data).unwrap(), "SERIAL");
        data[24..28].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(serial_from_descriptor(&data).is_err());
        data[24..28].copy_from_slice(&36u32.to_le_bytes());
        data[43] = 1;
        assert!(serial_from_descriptor(&data).is_err());
    }
    #[test]
    fn firmware_processor_id_matches_wmi_hex_byte_order() {
        let mut raw = vec![0u8; 26];
        raw[4..8].copy_from_slice(&18u32.to_le_bytes());
        raw[8] = 4;
        raw[9] = 16;
        raw[16..24].copy_from_slice(&0xBFEBFBFF000906EAu64.to_le_bytes());
        assert_eq!(
            processor_ids_from_smbios(&raw).unwrap(),
            ["bfebfbff000906ea"]
        );
        raw.pop();
        assert!(processor_ids_from_smbios(&raw).is_err());
    }
}
