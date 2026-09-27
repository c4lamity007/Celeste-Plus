//! Read-only SMBIOS fallback for the existing Windows hardware profile.
//! Never persists or logs identifiers. Malformed/incomplete firmware fails closed.
use anyhow::{Result, anyhow, ensure};

pub(crate) struct Identity {
    pub system: [String; 4],
    pub board: [String; 4],
    pub processor_id: String,
}

fn field(record: &[u8], strings: &[u8], offset: usize) -> Result<String> {
    let index = *record
        .get(offset)
        .ok_or_else(|| anyhow!("{}", obfstr::obfstr!("Incomplete firmware field")))?;
    if index == 0 {
        return Ok(String::new());
    }
    let value = strings
        .split(|byte| *byte == 0)
        .nth(index as usize - 1)
        .ok_or_else(|| anyhow!("{}", obfstr::obfstr!("Invalid firmware string index")))?;
    ensure!(
        !value.is_empty() && value.len() <= 1024 && value.is_ascii(),
        "{}",
        obfstr::obfstr!("Invalid firmware string")
    );
    Ok(std::str::from_utf8(value)?.to_owned())
}

pub(crate) fn parse(raw: &[u8]) -> Result<Identity> {
    ensure!(
        raw.len() >= 8,
        "{}",
        obfstr::obfstr!("Incomplete firmware table")
    );
    ensure!(
        u32::from_le_bytes(raw[4..8].try_into()?) as usize == raw.len() - 8,
        "{}",
        obfstr::obfstr!("Invalid firmware length")
    );
    let mut system = None;
    let mut board = None;
    let mut processor = None;
    let mut offset = 8;
    while offset + 4 <= raw.len() {
        let kind = raw[offset];
        let size = raw[offset + 1] as usize;
        ensure!(
            size >= 4 && offset + size <= raw.len(),
            "{}",
            obfstr::obfstr!("Invalid firmware structure")
        );
        let record = &raw[offset..offset + size];
        let remaining = &raw[offset + size..];
        let end = remaining
            .windows(2)
            .position(|pair| pair == [0, 0])
            .ok_or_else(|| anyhow!("{}", obfstr::obfstr!("Unterminated firmware strings")))?;
        let strings = &remaining[..end];
        match kind {
            1 if system.is_none() => {
                ensure!(
                    record.len() >= 24,
                    "{}",
                    obfstr::obfstr!("Incomplete system firmware")
                );
                let uuid = &record[8..24];
                ensure!(
                    uuid.iter().any(|b| *b != 0) && uuid.iter().any(|b| *b != 255),
                    "{}",
                    obfstr::obfstr!("System UUID unavailable")
                );
                // SMBIOS 2.6+ stores the first three UUID fields little-endian.
                let little = raw[1] > 2 || (raw[1] == 2 && raw[2] >= 6);
                let a = if little {
                    u32::from_le_bytes(uuid[..4].try_into()?)
                } else {
                    u32::from_be_bytes(uuid[..4].try_into()?)
                };
                let b = if little {
                    u16::from_le_bytes(uuid[4..6].try_into()?)
                } else {
                    u16::from_be_bytes(uuid[4..6].try_into()?)
                };
                let c = if little {
                    u16::from_le_bytes(uuid[6..8].try_into()?)
                } else {
                    u16::from_be_bytes(uuid[6..8].try_into()?)
                };
                let tail: String = uuid[10..].iter().map(|b| format!("{b:02x}")).collect();
                system = Some([
                    field(record, strings, 4)?,
                    field(record, strings, 5)?,
                    field(record, strings, 7)?,
                    format!(
                        "{a:08x}-{b:04x}-{c:04x}-{:02x}{:02x}-{tail}",
                        uuid[8], uuid[9]
                    ),
                ]);
            }
            2 if board.is_none() => {
                board = Some([
                    field(record, strings, 4)?,
                    field(record, strings, 5)?,
                    field(record, strings, 6)?,
                    field(record, strings, 7)?,
                ]);
            }
            4 if processor.is_none() => {
                ensure!(
                    record.len() >= 16,
                    "{}",
                    obfstr::obfstr!("Incomplete processor firmware")
                );
                let id = u64::from_le_bytes(record[8..16].try_into()?);
                ensure!(
                    id != 0 && id != u64::MAX,
                    "{}",
                    obfstr::obfstr!("Processor identity unavailable")
                );
                processor = Some(format!("{id:016x}"));
            }
            _ => {}
        }
        offset += size + end + 2;
        if kind == 127 {
            break;
        }
    }
    Ok(Identity {
        system: system
            .ok_or_else(|| anyhow!("{}", obfstr::obfstr!("System firmware unavailable")))?,
        board: board.ok_or_else(|| anyhow!("{}", obfstr::obfstr!("Board firmware unavailable")))?,
        processor_id: processor
            .ok_or_else(|| anyhow!("{}", obfstr::obfstr!("Processor firmware unavailable")))?,
    })
}

#[cfg(windows)]
pub(crate) fn read() -> Result<Identity> {
    use winapi::um::sysinfoapi::GetSystemFirmwareTable;
    let provider = u32::from_be_bytes(*b"RSMB");
    let size = unsafe { GetSystemFirmwareTable(provider, 0, std::ptr::null_mut(), 0) };
    ensure!(
        (8..=4 * 1024 * 1024).contains(&size),
        "{}",
        obfstr::obfstr!("Firmware identity unavailable")
    );
    let mut raw = vec![0u8; size as usize];
    ensure!(
        unsafe { GetSystemFirmwareTable(provider, 0, raw.as_mut_ptr().cast(), size) } == size,
        "{}",
        obfstr::obfstr!("Firmware identity changed during query")
    );
    parse(&raw)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> Vec<u8> {
        let mut body = Vec::new();
        let mut system = vec![1, 24, 0, 0, 1, 2, 0, 3];
        system.extend_from_slice(&[
            0x33, 0x22, 0x11, 0x00, 0x55, 0x44, 0x77, 0x66, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd,
            0xee, 0xff,
        ]);
        body.extend(system);
        body.extend_from_slice(b"Maker\0Model\0System Serial\0\0");
        body.extend_from_slice(&[2, 8, 0, 0, 1, 2, 3, 4]);
        body.extend_from_slice(b"Board Maker\0Board\0Rev\0Board Serial\0\0");
        body.extend_from_slice(&[
            4, 16, 0, 0, 0, 0, 0, 0, 0x12, 0x34, 0x56, 0x78, 0x90, 0xab, 0xcd, 0xef, 0, 0,
        ]);
        let mut raw = vec![0, 3, 0, 0];
        raw.extend_from_slice(&(body.len() as u32).to_le_bytes());
        raw.extend(body);
        raw
    }
    #[test]
    fn reproduces_wmi_system_board_and_processor_components() {
        let raw = fixture();
        let identity = parse(&raw).unwrap();
        assert_eq!(
            identity.system,
            [
                "Maker",
                "Model",
                "System Serial",
                "00112233-4455-6677-8899-aabbccddeeff"
            ]
        );
        assert_eq!(
            identity.board,
            ["Board Maker", "Board", "Rev", "Board Serial"]
        );
        assert_eq!(identity.processor_id, "efcdab9078563412");
        let mut old = raw;
        old[1] = 2;
        old[2] = 5;
        assert_eq!(
            parse(&old).unwrap().system[3],
            "33221100-5544-7766-8899-aabbccddeeff"
        );
    }
    #[test]
    fn malformed_or_missing_identity_never_becomes_unknown_fingerprint() {
        let original = fixture();
        for length in 0..original.len() {
            assert!(parse(&original[..length]).is_err());
        }
        let mut bad = original.clone();
        bad[8 + 4] = 40;
        assert!(parse(&bad).is_err());
        let mut bad = original.clone();
        bad[8 + 8..8 + 24].fill(0);
        assert!(parse(&bad).is_err());
        let mut bad = original;
        bad[8] = 3;
        assert!(parse(&bad).is_err());
    }
}
