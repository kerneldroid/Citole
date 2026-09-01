use midly::{Smf, TrackEventKind};
use thiserror::Error;

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

    pub fn event_count(&self) -> usize {
        self.tracks.iter().map(|t| t.event_count).sum()
    }

    pub fn is_single_track(&self) -> bool {
        self.track_count == 1
    }
}

pub fn parse_midi(data: &[u8]) -> Result<MidiFile, MidiError> {
    MidiFile::parse(data)
}

pub fn midi_note_events(data: &[u8]) -> Result<Vec<(u8, u8, bool)>, MidiError> {
    let smf = Smf::parse(data).map_err(|e| MidiError::Parse(e.to_string()))?;
    let mut out = Vec::new();
    for track in &smf.tracks {
        for ev in track {
            match ev.kind {
                TrackEventKind::Midi {
                    channel: _,
                    message,
                } => match message {
                    midly::MidiMessage::NoteOn { key, vel } => {
                        out.push((key.as_int(), vel.as_int(), true))
                    }
                    midly::MidiMessage::NoteOff { key, vel } => {
                        out.push((key.as_int(), vel.as_int(), false))
                    }
                    _ => {}
                },
                _ => {}
            }
        }
    }
    Ok(out)
}
