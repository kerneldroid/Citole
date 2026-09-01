#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Format {
    AacLc,
    HeAacV1,
    HeAacV2,
    XHeAac,
    Mp3,
    Flac,
    Vorbis,
    Opus,
    AmrNb,
    AmrWb,
    Pcm,
    Wav,
    Midi,
}

impl Format {
    #[inline]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::AacLc => "aac-lc",
            Self::HeAacV1 => "he-aac-v1",
            Self::HeAacV2 => "he-aac-v2",
            Self::XHeAac => "xhe-aac",
            Self::Mp3 => "mp3",
            Self::Flac => "flac",
            Self::Vorbis => "vorbis",
            Self::Opus => "opus",
            Self::AmrNb => "amr-nb",
            Self::AmrWb => "amr-wb",
            Self::Pcm => "pcm",
            Self::Wav => "wav",
            Self::Midi => "midi",
        }
    }

    #[inline]
    pub fn is_aac_family(self) -> bool {
        matches!(
            self,
            Self::AacLc | Self::HeAacV1 | Self::HeAacV2 | Self::XHeAac
        )
    }
}

#[inline]
pub fn probe_bytes(data: &[u8]) -> Option<Format> {
    if data.is_empty() {
        return None;
    }
    if data.len() >= 4 && data[0..4] == *b"fLaC" {
        return Some(Format::Flac);
    }
    if data.len() >= 3 && data[0..3] == *b"ID3" {
        return Some(Format::Mp3);
    }
    if data.len() >= 2 && data[0] == 0xFF && (data[1] & 0xE0) == 0xE0 {
        let layer = (data[1] >> 1) & 0x03;
        if layer == 0x01 {
            return Some(Format::Mp3);
        }
        if data.len() >= 4 {
            let profile = (data[2] >> 6) & 0x03;
            if profile == 0x01 {
                return Some(Format::AacLc);
            }
        }
        return Some(Format::AacLc);
    }
    if data.starts_with(b"OggS") {
        if data.len() >= 40 {
            let segment = &data[28..];
            if segment.windows(6).any(|w| w == b"vorbis") {
                return Some(Format::Vorbis);
            }
            if segment.windows(8).any(|w| w == b"OpusHead") {
                return Some(Format::Opus);
            }
            if segment.windows(4).any(|w| w == b"FLAC") {
                return Some(Format::Flac);
            }
        }
        if data.windows(6).any(|w| w == b"vorbis") {
            return Some(Format::Vorbis);
        }
        if data.windows(8).any(|w| w == b"OpusHead") {
            return Some(Format::Opus);
        }
        return Some(Format::Vorbis);
    }
    if data.starts_with(b"RIFF") && data.len() >= 12 && data[8..12] == *b"WAVE" {
        return Some(Format::Wav);
    }
    if data.starts_with(b"FORM") && data.len() >= 12 && data[8..12] == *b"AIFF" {
        return Some(Format::Pcm);
    }
    if data.starts_with(b"#!AMR\n") {
        return Some(Format::AmrNb);
    }
    if data.starts_with(b"#!AMR-WB\n") {
        return Some(Format::AmrWb);
    }
    if data.starts_with(b"MThd") {
        return Some(Format::Midi);
    }
    if data.len() >= 12 && data[4..8] == *b"ftyp" {
        let brand = &data[8..12];
        if brand == b"M4A "
            || brand == b"mp42"
            || brand == b"isom"
            || brand == b"iso5"
            || brand == b"dash"
        {
            if data.windows(4).any(|w| w == b"mp41") || data.windows(4).any(|w| w == b"mp42") {
                return Some(Format::AacLc);
            }
            return Some(Format::AacLc);
        }
        if data.windows(3).any(|w| w == b"MSN") {
            return Some(Format::Wav);
        }
        return Some(Format::AacLc);
    }
    None
}

#[inline]
pub fn probe_extension(ext: &str) -> Option<Format> {
    match ext.to_ascii_lowercase().as_str() {
        "aac" | "adts" => Some(Format::AacLc),
        "m4a" | "mp4" | "3gp" | "3gpp" | "m4b" => Some(Format::AacLc),
        "mp3" => Some(Format::Mp3),
        "flac" => Some(Format::Flac),
        "ogg" | "oga" => Some(Format::Vorbis),
        "opus" => Some(Format::Opus),
        "amr" => Some(Format::AmrNb),
        "awb" => Some(Format::AmrWb),
        "wav" => Some(Format::Wav),
        "pcm" | "raw" | "aiff" | "aif" => Some(Format::Pcm),
        "mid" | "midi" | "smf" => Some(Format::Midi),
        _ => None,
    }
}

#[inline]
pub fn refine_aac_variant(data: &[u8], base: Format) -> Format {
    if base != Format::AacLc {
        return base;
    }
    if data.is_empty() {
        return base;
    }
    if is_xhe_aac(data) {
        return Format::XHeAac;
    }
    if let Some(variant) = detect_he_via_adts(data) {
        return variant;
    }
    if has_aac_sbr_asc(data) {
        return Format::HeAacV1;
    }
    if data
        .windows(4)
        .any(|w| w == b"usac" || w == b"lhea" || w == b"lhvc")
    {
        return Format::XHeAac;
    }
    base
}

#[inline]
fn is_xhe_aac(data: &[u8]) -> bool {
    if data.windows(4).any(|w| w == b"usac" || w == b"lhea") {
        return true;
    }
    has_loas_sync(data)
}

#[inline]
fn has_loas_sync(data: &[u8]) -> bool {
    if data.len() < 2 {
        return false;
    }
    if data
        .windows(2)
        .any(|w| w == [0x56, 0xE0] || w == [0x56, 0xE4] || w == [0x56, 0xE8])
    {
        return true;
    }
    for i in 0..data.len().saturating_sub(2) {
        let sync = ((data[i] as u16) << 3) | ((data[i + 1] as u16) >> 5);
        if (sync & 0x7FF) == 0x2B7 && data[i] == 0x56 {
            return true;
        }
    }
    false
}

fn detect_he_via_adts(data: &[u8]) -> Option<Format> {
    let mut i = 0usize;
    let mut found_sbr = false;
    let mut found_ps = false;
    let mut frames = 0usize;
    while i + 7 < data.len() && frames < 8 {
        if data[i] == 0xFF && (data[i + 1] & 0xF0) == 0xF0 {
            let protection_absent = (data[i + 1] & 0x01) != 0;
            let header_len = if protection_absent { 7 } else { 9 };
            if i + header_len > data.len() {
                break;
            }
            let frame_len = (((data[i + 3] & 0x03) as usize) << 11)
                | ((data[i + 4] as usize) << 3)
                | ((data[i + 5] >> 5) as usize);
            if frame_len < header_len || i + frame_len > data.len() {
                i += 1;
                continue;
            }
            let payload = &data[i + header_len..i + frame_len];
            if payload.len() >= 2 {
                if payload
                    .windows(2)
                    .any(|w| w[0] == 0x2B && (w[1] & 0xF8) == 0x00)
                {
                    found_sbr = true;
                }
                for w in payload.windows(3) {
                    if w[0] == 0xEB && w[1] == 0x80 {
                        found_sbr = true;
                        break;
                    }
                }
            }
            let channel_cfg = ((data[i + 2] & 0x01) << 2) | ((data[i + 3] >> 6) & 0x03);
            if found_sbr && channel_cfg == 1 {
                found_ps = true;
            }
            frames += 1;
            if frame_len == 0 {
                i += 1;
            } else {
                i += frame_len;
            }
        } else {
            i += 1;
        }
    }
    if found_sbr {
        if found_ps {
            return Some(Format::HeAacV2);
        }
        return Some(Format::HeAacV1);
    }
    if frames > 0 && has_sbr_in_raw_payload(data) {
        return Some(Format::HeAacV1);
    }
    if data
        .windows(2)
        .any(|w| w[0] == 0xFF && (w[1] & 0xF0) == 0xF0)
        && data
            .windows(3)
            .any(|w| w[0] == 0x2B && (w[1] & 0xF8) == 0x00)
    {
        return Some(Format::HeAacV1);
    }
    None
}

#[inline]
fn has_sbr_in_raw_payload(data: &[u8]) -> bool {
    for i in 0..data.len().saturating_sub(4) {
        if data[i] == 0xFF && (data[i + 1] & 0xF6) == 0xF0 {
            let ext_type = (data[i + 2] >> 3) & 0x1F;
            if ext_type == 0x05 || ext_type == 0x1D {
                return true;
            }
        }
    }
    false
}

fn has_aac_sbr_asc(data: &[u8]) -> bool {
    let mut pos = 0usize;
    while pos + 8 < data.len() {
        if pos + 4 > data.len() {
            break;
        }
        let size =
            u32::from_be_bytes([data[pos], data[pos + 1], data[pos + 2], data[pos + 3]]) as usize;
        if size < 8 || pos + size > data.len() {
            pos += 1;
            continue;
        }
        let tag = &data[pos + 4..pos + 8];
        if tag == b"esds" && size > 30 {
            let esds = &data[pos..pos + size];
            if esds
                .windows(2)
                .any(|w| w == [0x05, 0x80] || w == [0x05, 0x88])
            {
                return true;
            }
            for j in 0..esds.len().saturating_sub(2) {
                let aot = esds[j] >> 3;
                if aot == 5 || aot == 29 {
                    return true;
                }
                if esds[j] == 0x2B && (esds[j + 1] & 0xE0) == 0x00 {
                    return true;
                }
            }
        }
        if tag == b"asc " || tag == b"decC" {
            let payload = &data[pos + 8..pos + size];
            if payload
                .first()
                .is_some_and(|b| (*b >> 3) == 5 || (*b >> 3) == 29 || (*b >> 3) == 42)
            {
                return true;
            }
        }
        if size == 0 {
            break;
        }
        pos += size;
    }
    false
}
