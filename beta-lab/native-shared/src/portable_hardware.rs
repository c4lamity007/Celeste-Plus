//! Hardware facts shared with the normal mod's HardwareFingerprint.java.
//! Keep the source order and fallbacks in this file aligned with that class.

// The same collector is nested in Celeste+'s auth module and at the standard
// launcher's crate root. Keep one implementation of device identity on each OS.
use super::{HardwareProfile, canonical, hex_sha256};
use anyhow::Result;
#[cfg(any(target_os = "macos", target_os = "linux"))]
use anyhow::bail;
use std::fs;
use std::path::Path;
use std::process::Command;

pub(super) fn collect() -> Result<HardwareProfile> {
    #[cfg(target_os = "linux")]
    {
        collect_linux(Path::new("/"))
    }
    #[cfg(target_os = "macos")]
    {
        collect_macos()
    }
    #[cfg(windows)]
    {
        unreachable!("Portable hardware is never collected on Windows")
    }
}

fn profile(
    system: &[Option<&str>],
    board: &[Option<&str>],
    processor: &[Option<&str>],
    edids: impl IntoIterator<Item = Vec<u8>>,
) -> HardwareProfile {
    let system_value = canonical(system);
    let mut monitors = edids
        .into_iter()
        .filter(|edid| edid.len() >= 128)
        .map(|edid| hex_sha256(&edid))
        .collect::<Vec<_>>();
    monitors.sort();
    monitors.dedup();
    monitors.truncate(8);
    if monitors.is_empty() {
        monitors.push(hex_sha256(system_value.as_bytes()));
    }
    HardwareProfile {
        system: hex_sha256(system_value.as_bytes()),
        board: hex_sha256(canonical(board).as_bytes()),
        processor: hex_sha256(canonical(processor).as_bytes()),
        monitors,
    }
}

fn read_text(path: &Path) -> Option<String> {
    let value = fs::read_to_string(path).ok()?;
    let value = value.trim();
    (!value.is_empty()).then(|| value.to_owned())
}

fn command_text(program: &str, arguments: &[&str]) -> Option<String> {
    let output = Command::new(program).args(arguments).output().ok()?;
    if !output.status.success() {
        return None;
    }
    let value = String::from_utf8(output.stdout).ok()?;
    let value = value.trim();
    (!value.is_empty()).then(|| value.to_owned())
}

fn meaningful(value: Option<&str>) -> bool {
    let Some(value) = value else { return false };
    let value = value.trim().to_ascii_lowercase();
    !value.is_empty()
        && !matches!(
            value.as_str(),
            "unknown"
                | "none"
                | "not specified"
                | "default string"
                | "to be filled by o.e.m."
                | "uninitialized"
        )
        && value.chars().any(|c| c.is_ascii_alphanumeric() && c != '0')
}

#[cfg(target_os = "linux")]
fn collect_linux(root: &Path) -> Result<HardwareProfile> {
    let read = |relative: &str| read_text(&root.join(relative));
    let machine_id = read("etc/machine-id").or_else(|| read("var/lib/dbus/machine-id"));
    let uuid = read("sys/class/dmi/id/product_uuid");
    let serial = read("sys/class/dmi/id/product_serial");
    let board_serial = read("sys/class/dmi/id/board_serial");
    if ![
        machine_id.as_deref(),
        uuid.as_deref(),
        serial.as_deref(),
        board_serial.as_deref(),
    ]
    .into_iter()
    .any(meaningful)
    {
        bail!("No stable Linux machine identity is available for Celeste activation");
    }

    let board_vendor = read("sys/class/dmi/id/board_vendor");
    let board_name = read("sys/class/dmi/id/board_name");
    let board_version = read("sys/class/dmi/id/board_version");
    let cpuinfo = read("proc/cpuinfo").unwrap_or_default();
    let vendor = cpuinfo_value(&cpuinfo, &["vendor_id", "CPU implementer"]);
    let model = cpuinfo_value(&cpuinfo, &["model name", "Processor", "Hardware"]);
    let arch = command_text("uname", &["-m"]);
    let edids = fs::read_dir(root.join("sys/class/drm"))
        .ok()
        .into_iter()
        .flatten()
        .filter_map(|entry| fs::read(entry.ok()?.path().join("edid")).ok())
        .collect::<Vec<_>>();
    Ok(profile(
        &[
            Some("linux"),
            machine_id.as_deref(),
            uuid.as_deref(),
            serial.as_deref(),
        ],
        &[
            Some("linux"),
            board_vendor.as_deref(),
            board_name.as_deref(),
            board_version.as_deref(),
            board_serial.as_deref(),
        ],
        &[
            Some("linux"),
            vendor.as_deref(),
            model.as_deref(),
            arch.as_deref(),
        ],
        edids,
    ))
}

#[cfg(target_os = "linux")]
fn cpuinfo_value(text: &str, names: &[&str]) -> Option<String> {
    for name in names {
        for line in text.lines() {
            let Some((key, value)) = line.split_once(':') else {
                continue;
            };
            if key.trim() == *name && !value.trim().is_empty() {
                return Some(value.trim().to_owned());
            }
        }
    }
    None
}

#[cfg(target_os = "macos")]
fn collect_macos() -> Result<HardwareProfile> {
    let platform =
        command_text("ioreg", &["-rd1", "-c", "IOPlatformExpertDevice"]).unwrap_or_default();
    let serial = ioreg_value(&platform, "IOPlatformSerialNumber");
    let uuid = ioreg_value(&platform, "IOPlatformUUID");
    if ![serial.as_deref(), uuid.as_deref()]
        .into_iter()
        .any(meaningful)
    {
        bail!("No stable macOS machine identity is available for Celeste activation");
    }
    let model =
        command_text("sysctl", &["-n", "hw.model"]).or_else(|| ioreg_value(&platform, "model"));
    let board_id = ioreg_value(&platform, "board-id");
    let arch = command_text("sysctl", &["-n", "hw.machine"]);
    let brand = command_text("sysctl", &["-n", "machdep.cpu.brand_string"]);
    let display =
        command_text("ioreg", &["-l", "-w0", "-c", "IODisplayConnect"]).unwrap_or_default();
    let edids = display
        .lines()
        .filter_map(|line| ioreg_hex_value(line, "IODisplayEDID"))
        .collect::<Vec<_>>();
    Ok(profile(
        &[
            Some("macos"),
            serial.as_deref(),
            uuid.as_deref(),
            model.as_deref(),
        ],
        &[
            Some("macos"),
            board_id.as_deref(),
            model.as_deref(),
            serial.as_deref(),
        ],
        &[
            Some("macos"),
            arch.as_deref(),
            brand.as_deref(),
            model.as_deref(),
        ],
        edids,
    ))
}

fn ioreg_value(text: &str, name: &str) -> Option<String> {
    let prefix = format!("\"{name}\"");
    for line in text.lines() {
        let line = line.trim();
        let Some(start) = line.find('"') else {
            continue;
        };
        let line = &line[start..];
        let Some(rest) = line.strip_prefix(&prefix) else {
            continue;
        };
        let Some(value) = rest.trim().strip_prefix('=') else {
            continue;
        };
        let value = value.trim().trim_matches('"').to_owned();
        if !value.is_empty() {
            return Some(value);
        }
    }
    None
}

fn ioreg_hex_value(line: &str, name: &str) -> Option<Vec<u8>> {
    let value = ioreg_value(line, name)?;
    let value = value.strip_prefix('<')?.strip_suffix('>')?;
    let hex = value
        .chars()
        .filter(|c| !c.is_ascii_whitespace())
        .collect::<String>();
    if hex.len() < 256 || hex.len() % 2 != 0 {
        return None;
    }
    (0..hex.len())
        .step_by(2)
        .map(|index| u8::from_str_radix(&hex[index..index + 2], 16).ok())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placeholder_identifiers_cannot_create_a_portable_device_binding() {
        for value in [
            None,
            Some(""),
            Some("  "),
            Some("0000-0000"),
            Some("unknown"),
            Some("Default String"),
            Some("To Be Filled By O.E.M."),
            Some("uninitialized"),
        ] {
            assert!(!meaningful(value), "accepted placeholder identity");
        }
        assert!(meaningful(Some("fixture-machine-012345")));
    }

    #[test]
    fn profile_has_server_shape_and_stable_monitor_fallback() {
        let profile = profile(
            &[Some("linux"), Some("example-machine")],
            &[Some("linux"), Some("example-board")],
            &[Some("linux"), Some("example-cpu")],
            Vec::<Vec<u8>>::new(),
        );
        assert_eq!(profile.monitors, vec![profile.system.clone()]);
        assert_eq!(profile.board.len(), 64);
        assert_eq!(profile.processor.len(), 64);
    }

    #[test]
    fn rust_and_java_fixture_hashes_match() {
        let edid = (0..128).map(|byte| byte as u8).collect::<Vec<_>>();
        let profile = profile(
            &[
                Some("linux"),
                Some("machine-id-123"),
                Some("uuid-456"),
                Some("serial-789"),
            ],
            &[
                Some("linux"),
                Some("vendor"),
                Some("board"),
                Some("v1"),
                Some("bserial"),
            ],
            &[
                Some("linux"),
                Some("intel"),
                Some("example cpu"),
                Some("x86_64"),
            ],
            vec![edid],
        );
        assert_eq!(
            profile.system,
            "4ff84390d36c2a1e99bcb4002129900596cce9059cd8e5b1c81009e83c2ae3cc"
        );
        assert_eq!(
            profile.board,
            "9b272921005d8908265afde91a3db25797befc4435269c11752c4173da9cc9f6"
        );
        assert_eq!(
            profile.processor,
            "a432b08eded76bbd3567419360acde30d6708476a21c2ac41f278a28ba5f96b9"
        );
        assert_eq!(
            profile.monitors,
            vec!["471fb943aa23c511f6f72f8d1652d9c880cfa392ad80503120547703e56a2be5"]
        );
    }

    #[test]
    fn parses_ioreg_quoted_and_binary_values() {
        let input = "| | \"IOPlatformUUID\" = \"abc-def\"\n| | \"board-id\" = <414243>";
        assert_eq!(
            ioreg_value(input, "IOPlatformUUID").as_deref(),
            Some("abc-def")
        );
        assert_eq!(ioreg_value(input, "board-id").as_deref(), Some("<414243>"));
        let edid = "aa".repeat(128);
        assert_eq!(
            ioreg_hex_value(&format!("\"IODisplayEDID\" = <{edid}>"), "IODisplayEDID")
                .unwrap()
                .len(),
            128
        );
    }
}
