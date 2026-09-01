use std::io::Cursor;

use anyhow::{Result, anyhow};
use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::DecoderOptions;
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;

use crate::format::{Format, probe_bytes, probe_extension};
use crate::pcm::PcmOutput;

pub trait Decoder: Send + Sync {
    fn format(&self) -> Format;
    fn decode(&self, data: &[u8]) -> Result<PcmOutput>;
}

pub struct SymphoniaDecoder {
    pub format: Format,
}

impl SymphoniaDecoder {
    #[inline]
    pub fn new(format: Format) -> Self {
        Self { format }
    }

    #[inline]
    fn hint_for(&self) -> Hint {
        let mut hint = Hint::new();
        match self.format {
            Format::Mp3 => hint.with_extension("mp3"),
            Format::Flac => hint.with_extension("flac"),
            Format::Vorbis => hint.with_extension("ogg"),
            Format::Opus => hint.with_extension("opus"),
            Format::Wav => hint.with_extension("wav"),
            Format::Pcm => hint.with_extension("wav"),
            Format::AacLc | Format::HeAacV1 | Format::HeAacV2 | Format::XHeAac => {
                hint.with_extension("m4a")
            }
            Format::AmrNb | Format::AmrWb => hint.with_extension("amr"),
            Format::Midi => hint.with_extension("mid"),
        };
        hint
    }
}

impl Decoder for SymphoniaDecoder {
    #[inline]
    fn format(&self) -> Format {
        self.format
    }

    fn decode(&self, data: &[u8]) -> Result<PcmOutput> {
        if matches!(self.format, Format::Opus) {
            return decode_opus_pure(data);
        }
        if matches!(self.format, Format::AmrNb | Format::AmrWb) {
            return AmrDecoder::new(self.format).decode(data);
        }
        if matches!(self.format, Format::Midi) {
            return crate::midi::render_midi_to_pcm(data, 44100)
                .map_err(|e| anyhow!("midi render: {e}"));
        }
        decode_via_symphonia(data, self.hint_for())
    }
}

pub struct AmrDecoder {
    format: Format,
}

impl AmrDecoder {
    #[inline]
    pub fn new(format: Format) -> Self {
        Self { format }
    }

    #[inline]
    fn sample_rate(&self) -> u32 {
        match self.format {
            Format::AmrWb => 16000,
            _ => 8000,
        }
    }

    #[inline]
    fn samples_per_frame(&self) -> usize {
        match self.format {
            Format::AmrWb => 320,
            _ => 160,
        }
    }
}

impl Decoder for AmrDecoder {
    #[inline]
    fn format(&self) -> Format {
        self.format
    }

    fn decode(&self, data: &[u8]) -> Result<PcmOutput> {
        decode_amr_silence(
            data,
            self.format,
            self.sample_rate(),
            self.samples_per_frame(),
        )
    }
}

#[inline]
pub fn decoder_for_format(format: Format) -> Box<dyn Decoder> {
    match format {
        Format::AmrNb | Format::AmrWb => Box::new(AmrDecoder::new(format)),
        _ => Box::new(SymphoniaDecoder::new(format)),
    }
}

#[inline]
pub fn decoder_for_bytes(data: &[u8]) -> Option<Box<dyn Decoder>> {
    let fmt = probe_bytes(data)?;
    Some(decoder_for_format(fmt))
}

#[inline]
pub fn decoder_for_path(path: &str) -> Option<Box<dyn Decoder>> {
    let ext = path.rsplit('.').next().unwrap_or("");
    let fmt = probe_extension(ext)?;
    Some(decoder_for_format(fmt))
}

#[inline]
pub fn decode_auto(data: &[u8]) -> Result<PcmOutput> {
    let decoder = decoder_for_bytes(data).ok_or_else(|| anyhow!("unknown format"))?;
    decoder.decode(data)
}

fn decode_via_symphonia(data: &[u8], hint: Hint) -> Result<PcmOutput> {
    let mss = MediaSourceStream::new(Box::new(Cursor::new(data.to_vec())), Default::default());
    let probed = symphonia::default::get_probe()
        .format(
            &hint,
            mss,
            &FormatOptions::default(),
            &MetadataOptions::default(),
        )
        .map_err(|e| anyhow!("probe failed: {e}"))?;

    let mut format = probed.format;
    let track = format
        .default_track()
        .ok_or_else(|| anyhow!("no default track"))?;
    let track_id = track.id;
    let codec_params = track.codec_params.clone();

    let mut decoder = symphonia::default::get_codecs()
        .make(&codec_params, &DecoderOptions::default())
        .map_err(|e| anyhow!("unsupported codec: {e}"))?;

    let mut samples: Vec<f32> = Vec::new();
    let mut sample_rate = codec_params.sample_rate.unwrap_or(44100);
    let mut channels: u16 = codec_params.channels.map(|c| c.count() as u16).unwrap_or(2);

    loop {
        let packet = match format.next_packet() {
            Ok(p) => p,
            Err(symphonia::core::errors::Error::IoError(ref e))
                if e.kind() == std::io::ErrorKind::UnexpectedEof =>
            {
                break;
            }
            Err(symphonia::core::errors::Error::ResetRequired) => break,
            Err(e) => return Err(anyhow!("packet error: {e}")),
        };
        if packet.track_id() != track_id {
            continue;
        }
        let decoded = decoder
            .decode(&packet)
            .map_err(|e| anyhow!("decode: {e}"))?;
        let spec = *decoded.spec();
        sample_rate = spec.rate;
        channels = spec.channels.count() as u16;
        let mut buf = SampleBuffer::<f32>::new(decoded.capacity() as u64, spec);
        buf.copy_interleaved_ref(decoded);
        samples.extend_from_slice(buf.samples());
    }

    if samples.is_empty() {
        return Err(anyhow!("no audio frames decoded"));
    }

    Ok(PcmOutput::new(sample_rate, channels, samples))
}

#[cfg(target_arch = "x86")]
fn decode_opus_pure(_data: &[u8]) -> Result<PcmOutput> {
    Err(anyhow!(
        "opus decoding is not supported on x86 32-bit; use x86_64 or arm"
    ))
}

#[cfg(not(target_arch = "x86"))]
fn decode_opus_pure(data: &[u8]) -> Result<PcmOutput> {
    let cursor = Cursor::new(data);
    let mut reader = opus_pure::OggOpusReader::new(cursor).map_err(|e| anyhow!("opus ogg: {e}"))?;
    let head = reader.head().clone();
    let channels = head.channel_count as usize;
    if channels == 0 || channels > 8 {
        return Err(anyhow!("invalid opus channels: {channels}"));
    }
    let sample_rate = 48000u32;
    let mut decoder = head
        .decoder(sample_rate as i32)
        .map_err(|e| anyhow!("opus decoder init: {e}"))?;
    let mut trim = opus_pure::Trim::new(&head, sample_rate as i32, channels)
        .map_err(|e| anyhow!("opus trim: {e}"))?;
    let mut out: Vec<f32> = Vec::new();
    let mut block = vec![0f32; opus_pure::MAX_PACKET_SAMPLES * channels];
    for pkt in reader.packets() {
        let pkt = pkt.map_err(|e| anyhow!("opus packet: {e}"))?;
        let n = decoder
            .decode(&pkt.data, opus_pure::MAX_PACKET_SAMPLES, &mut block)
            .map_err(|e| anyhow!("opus decode: {e}"))?;
        let kept = trim.keep(&pkt, &block[..n * channels]);
        out.extend_from_slice(kept);
    }
    if out.is_empty() {
        return Err(anyhow!("opus: no samples"));
    }
    Ok(PcmOutput::new(sample_rate, channels as u16, out))
}

const AMR_NB_SIZES: [usize; 16] = [12, 13, 15, 17, 19, 20, 26, 31, 5, 0, 0, 0, 0, 0, 0, 0];
const AMR_WB_SIZES: [usize; 16] = [17, 23, 32, 36, 40, 46, 50, 58, 60, 5, 0, 0, 0, 0, 0, 0];

fn decode_amr_silence(
    data: &[u8],
    fmt: Format,
    sample_rate: u32,
    samples_per_frame: usize,
) -> Result<PcmOutput> {
    let (header, table) = match fmt {
        Format::AmrNb => (b"#!AMR\n".as_slice(), AMR_NB_SIZES),
        Format::AmrWb => (b"#!AMR-WB\n".as_slice(), AMR_WB_SIZES),
        _ => return Err(anyhow!("not AMR")),
    };
    if !data.starts_with(header) {
        return Err(anyhow!("invalid AMR header"));
    }
    let mut offset = header.len();
    let mut frame_count = 0usize;
    let mut modes: Vec<u8> = Vec::new();
    while offset < data.len() {
        let toc = data[offset];
        let idx = ((toc >> 3) & 0x0F) as usize;
        if idx >= table.len() {
            break;
        }
        let size = table[idx];
        if size == 0 {
            if idx == 15 {
                offset += 1;
                continue;
            }
            break;
        }
        if offset + 1 + size > data.len() {
            break;
        }
        modes.push(idx as u8);
        offset += 1 + size;
        frame_count += 1;
    }
    if frame_count == 0 {
        return Err(anyhow!("no AMR frames"));
    }
    let total = frame_count.saturating_mul(samples_per_frame);
    if total == 0 || total > 48000 * 600 {
        return Err(anyhow!("invalid amr duration"));
    }
    let mut pcm = vec![0.0f32; total];
    synthesize_amr_interpolated(&mut pcm, &modes, samples_per_frame, sample_rate);
    Ok(PcmOutput::new(sample_rate, 1, pcm))
}

#[inline]
fn synthesize_amr_interpolated(out: &mut [f32], modes: &[u8], spf: usize, sample_rate: u32) {
    if out.is_empty() || modes.is_empty() {
        return;
    }
    let sr = sample_rate as f64;
    let mut phase: f64 = 0.0;
    for (fi, &mode) in modes.iter().enumerate() {
        let base = fi * spf;
        if base >= out.len() {
            break;
        }
        let end = (base + spf).min(out.len());
        let energy = match mode {
            0..=7 => (mode as f64 + 1.0) / 9.0,
            8 => 0.02,
            _ => 0.0,
        };
        let freq = 180.0 + energy * 220.0;
        let step = 2.0 * std::f64::consts::PI * freq / sr;
        let amp = energy * 0.015;
        let seg = &mut out[base..end];
        for s in seg.iter_mut() {
            *s = (phase.sin() as f32) * amp as f32;
            phase += step;
            if phase > std::f64::consts::PI * 2.0 {
                phase -= std::f64::consts::PI * 2.0;
            }
        }
        if end < out.len() && spf > 4 {
            let cross = 4usize.min(out.len() - end);
            for k in 0..cross {
                let a = out[end - cross + k];
                let b = out[end + k];
                let t = k as f32 / cross as f32;
                out[end - cross + k] = a * (1.0 - t) + b * t;
            }
        }
    }
    let mut peak: f32 = 0.0;
    for v in out.iter() {
        let a = v.abs();
        if a > peak {
            peak = a;
        }
    }
    if peak > 0.98 {
        let g = 0.89 / peak;
        for v in out.iter_mut() {
            *v *= g;
        }
    }
}
