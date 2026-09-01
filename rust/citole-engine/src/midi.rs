use midly::{Smf, TrackEventKind};
use thiserror::Error;

use crate::pcm::PcmOutput;

#[derive(Debug, Error)]
pub enum MidiError {
    #[error("midly parse: {0}")]
    Parse(String),
    #[error("empty midi")]
    Empty,
}

#[derive(Debug, Clone)]
pub struct MidiTrackInfo {
    pub index: usize,
    pub event_count: usize,
    pub ticks: u32,
}

#[derive(Debug, Clone)]
pub struct MidiFile {
    pub ticks_per_quarter: u16,
    pub track_count: usize,
    pub tracks: Vec<MidiTrackInfo>,
    pub duration_ticks: u32,
}

impl MidiFile {
    #[inline]
    pub fn parse(data: &[u8]) -> Result<Self, MidiError> {
        let smf = Smf::parse(data).map_err(|e| MidiError::Parse(e.to_string()))?;
        if smf.tracks.is_empty() {
            return Err(MidiError::Empty);
        }
        let ticks_per_quarter = match smf.header.timing {
            midly::Timing::Metrical(t) => t.as_int(),
            midly::Timing::Timecode(_, _) => 0,
        };
        let mut tracks = Vec::with_capacity(smf.tracks.len());
        let mut max_ticks: u32 = 0;
        for (idx, track) in smf.tracks.iter().enumerate() {
            let mut ticks: u32 = 0;
            for ev in track {
                ticks = ticks.saturating_add(ev.delta.as_int());
            }
            max_ticks = max_ticks.max(ticks);
            tracks.push(MidiTrackInfo {
                index: idx,
                event_count: track.len(),
                ticks,
            });
        }
        Ok(Self {
            ticks_per_quarter,
            track_count: smf.tracks.len(),
            tracks,
            duration_ticks: max_ticks,
        })
    }

    #[inline]
    pub fn event_count(&self) -> usize {
        self.tracks.iter().map(|t| t.event_count).sum()
    }

    #[inline]
    pub fn is_single_track(&self) -> bool {
        self.track_count == 1
    }
}

#[inline]
pub fn parse_midi(data: &[u8]) -> Result<MidiFile, MidiError> {
    MidiFile::parse(data)
}

#[inline]
pub fn midi_note_events(data: &[u8]) -> Result<Vec<(u8, u8, bool)>, MidiError> {
    let smf = Smf::parse(data).map_err(|e| MidiError::Parse(e.to_string()))?;
    let mut out = Vec::new();
    for track in &smf.tracks {
        for ev in track {
            if let TrackEventKind::Midi {
                channel: _,
                message,
            } = ev.kind
            {
                match message {
                    midly::MidiMessage::NoteOn { key, vel } => {
                        out.push((key.as_int(), vel.as_int(), true))
                    }
                    midly::MidiMessage::NoteOff { key, vel } => {
                        out.push((key.as_int(), vel.as_int(), false))
                    }
                    _ => {}
                }
            }
        }
    }
    Ok(out)
}

pub fn render_midi_to_pcm(data: &[u8], sample_rate: u32) -> Result<PcmOutput, MidiError> {
    render_midi_to_pcm_with_rate(data, sample_rate)
}

#[inline]
pub fn render_midi_to_pcm_with_rate(data: &[u8], sample_rate: u32) -> Result<PcmOutput, MidiError> {
    let sr = if sample_rate == 0 { 44100 } else { sample_rate };
    let smf = Smf::parse(data).map_err(|e| MidiError::Parse(e.to_string()))?;
    if smf.tracks.is_empty() {
        return Err(MidiError::Empty);
    }
    let ticks_per_quarter = match smf.header.timing {
        midly::Timing::Metrical(t) => t.as_int() as f64,
        midly::Timing::Timecode(_, _) => 480.0,
    };
    if ticks_per_quarter == 0.0 {
        return Err(MidiError::Empty);
    }
    let mut tempo_us_per_quarter: f64 = 500_000.0;
    for track in &smf.tracks {
        for ev in track {
            if let TrackEventKind::Meta(midly::MetaMessage::Tempo(t)) = ev.kind {
                tempo_us_per_quarter = t.as_int() as f64;
                break;
            }
        }
    }
    let secs_per_tick = tempo_us_per_quarter / 1_000_000.0 / ticks_per_quarter;

    #[derive(Clone, Copy)]
    struct NoteSpan {
        key: u8,
        vel: u8,
        start_tick: u32,
        end_tick: u32,
    }

    let mut spans: Vec<NoteSpan> = Vec::new();
    let mut active: Vec<(u8, u32, u8)> = Vec::new();

    for track in &smf.tracks {
        let mut tick: u32 = 0;
        for ev in track {
            tick = tick.saturating_add(ev.delta.as_int());
            match ev.kind {
                TrackEventKind::Midi { message, .. } => match message {
                    midly::MidiMessage::NoteOn { key, vel } => {
                        let k = key.as_int();
                        let v = vel.as_int();
                        if v == 0 {
                            if let Some(pos) = active.iter().position(|(ak, _, _)| *ak == k) {
                                let (_, st, sv) = active.remove(pos);
                                spans.push(NoteSpan {
                                    key: k,
                                    vel: sv,
                                    start_tick: st,
                                    end_tick: tick,
                                });
                            }
                        } else {
                            active.push((k, tick, v));
                        }
                    }
                    midly::MidiMessage::NoteOff { key, .. } => {
                        let k = key.as_int();
                        if let Some(pos) = active.iter().position(|(ak, _, _)| *ak == k) {
                            let (_, st, sv) = active.remove(pos);
                            spans.push(NoteSpan {
                                key: k,
                                vel: sv,
                                start_tick: st,
                                end_tick: tick,
                            });
                        }
                    }
                    _ => {}
                },
                TrackEventKind::Meta(midly::MetaMessage::Tempo(_)) => {}
                _ => {}
            }
        }
    }
    for (k, st, sv) in active.drain(..) {
        let end = st.saturating_add((ticks_per_quarter * 2.0) as u32);
        spans.push(NoteSpan {
            key: k,
            vel: sv,
            start_tick: st,
            end_tick: end,
        });
    }

    let max_tick = spans.iter().map(|s| s.end_tick).max().unwrap_or(0);
    if max_tick == 0 {
        let secs = secs_per_tick * ticks_per_quarter * 4.0;
        let frames = (secs * sr as f64) as usize;
        let frames = frames.max(sr as usize);
        return Ok(PcmOutput::new(sr, 1, vec![0.0; frames]));
    }
    let total_secs = max_tick as f64 * secs_per_tick + 0.2;
    let total_frames = (total_secs * sr as f64).ceil() as usize;
    if total_frames == 0 {
        return Err(MidiError::Empty);
    }
    let mut out = vec![0.0f32; total_frames];

    for span in spans {
        if span.end_tick <= span.start_tick {
            continue;
        }
        let start_s = span.start_tick as f64 * secs_per_tick;
        let end_s = span.end_tick as f64 * secs_per_tick;
        let start_f = (start_s * sr as f64) as usize;
        let end_f = ((end_s * sr as f64) as usize).min(total_frames);
        if start_f >= end_f {
            continue;
        }
        let freq = 440.0 * (2.0f64).powf((span.key as f64 - 69.0) / 12.0);
        let amp = (span.vel as f64 / 127.0 * 0.22) as f32;
        let len = end_f - start_f;
        let attack = (0.008 * sr as f64) as usize;
        let release = (0.08 * sr as f64) as usize;
        for (idx, sample) in out[start_f..end_f].iter_mut().enumerate() {
            let env = if idx < attack {
                idx as f32 / attack as f32
            } else if idx >= len.saturating_sub(release) {
                let r = len - idx;
                r as f32 / release as f32
            } else {
                1.0
            };
            let t = (start_f + idx) as f64 / sr as f64;
            let phase = 2.0 * std::f64::consts::PI * freq * t;
            *sample += (phase.sin() as f32) * amp * env * 0.9;
        }
    }

    let mut peak: f32 = 0.0;
    for v in &out {
        let a = v.abs();
        if a > peak {
            peak = a;
        }
    }
    if peak > 1.0 {
        let g = 0.89 / peak;
        for v in &mut out {
            *v *= g;
        }
    }

    Ok(PcmOutput::new(sr, 1, out))
}
