#[derive(Debug, Clone)]
pub struct PcmOutput {
    pub sample_rate: u32,
    pub channels: u16,
    pub samples: Vec<f32>,
}

impl PcmOutput {
    #[inline]
    pub fn new(sample_rate: u32, channels: u16, samples: Vec<f32>) -> Self {
        Self {
            sample_rate,
            channels,
            samples,
        }
    }

    #[inline]
    pub fn frames(&self) -> usize {
        if self.channels == 0 {
            0
        } else {
            self.samples.len() / self.channels as usize
        }
    }

    #[inline]
    pub fn duration_secs(&self) -> f64 {
        if self.sample_rate == 0 {
            0.0
        } else {
            self.frames() as f64 / self.sample_rate as f64
        }
    }

    #[inline]
    pub fn to_i16_interleaved(&self) -> Vec<i16> {
        self.samples
            .iter()
            .map(|s| (s.clamp(-1.0, 1.0) * 32767.0) as i16)
            .collect()
    }

    #[inline]
    pub fn to_le_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(self.samples.len() * 2);
        for s in &self.samples {
            out.extend_from_slice(&((s.clamp(-1.0, 1.0) * 32767.0) as i16).to_le_bytes());
        }
        out
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.samples.is_empty()
    }

    #[inline]
    pub fn as_f32_slice(&self) -> &[f32] {
        &self.samples
    }

    pub fn resample(&self, new_rate: u32) -> Self {
        if new_rate == 0
            || new_rate == self.sample_rate
            || self.samples.is_empty()
            || self.channels == 0
        {
            return self.clone();
        }
        let ch = self.channels as usize;
        let in_frames = self.frames();
        if in_frames == 0 {
            return Self::new(new_rate, self.channels, Vec::new());
        }
        let out_frames = ((in_frames as u64 * new_rate as u64) / self.sample_rate as u64) as usize;
        if out_frames == 0 {
            return Self::new(new_rate, self.channels, Vec::new());
        }
        let mut out = Vec::with_capacity(out_frames * ch);
        let ratio = self.sample_rate as f64 / new_rate as f64;
        for i in 0..out_frames {
            let src_pos = i as f64 * ratio;
            let idx = src_pos as usize;
            let frac = (src_pos - idx as f64) as f32;
            let idx_next = (idx + 1).min(in_frames - 1);
            let idx = idx.min(in_frames - 1);
            for c in 0..ch {
                let a = self.samples[idx * ch + c];
                let b = self.samples[idx_next * ch + c];
                out.push(a + (b - a) * frac);
            }
        }
        Self::new(new_rate, self.channels, out)
    }

    pub fn to_stereo(&self) -> Self {
        match self.channels {
            2 => self.clone(),
            1 => {
                let frames = self.frames();
                let mut out = Vec::with_capacity(frames * 2);
                for &s in &self.samples {
                    out.push(s);
                    out.push(s);
                }
                Self::new(self.sample_rate, 2, out)
            }
            0 => self.clone(),
            _ => {
                let ch = self.channels as usize;
                let frames = self.frames();
                let mut out = Vec::with_capacity(frames * 2);
                for f in 0..frames {
                    let base = f * ch;
                    let l = self.samples[base];
                    let r = if ch > 1 { self.samples[base + 1] } else { l };
                    out.push(l);
                    out.push(r);
                }
                Self::new(self.sample_rate, 2, out)
            }
        }
    }

    pub fn normalize(&mut self) {
        let peak = self.samples.iter().fold(0.0f32, |m, &v| m.max(v.abs()));
        if peak > 1e-6 {
            let g = 0.89 / peak;
            if (g - 1.0).abs() > f32::EPSILON {
                for v in &mut self.samples {
                    *v *= g;
                }
            }
        }
    }

    pub fn normalized(mut self) -> Self {
        self.normalize();
        self
    }

    #[inline]
    pub fn peak(&self) -> f32 {
        self.samples.iter().fold(0.0f32, |m, &v| m.max(v.abs()))
    }
}
