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

    pub fn is_aac_family(self) -> bool {
        matches!(self, Self::AacLc | Self::HeAacV1 | Self::HeAacV2 | Self::XHeAac)
    }
}

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
        if brand == b"M4A " || brand == b"mp42" || brand == b"isom" || brand == b"iso5" || brand == b"dash" {
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

pub fn refine_aac_variant(data: &[u8], base: Format) -> Format {
    if base != Format::AacLc {
        return base;
    }
    if data.windows(2).any(|w| w == [0x2B, 0x20]) {
        return Format::XHeAac;
    }
    base
}
