use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint, jstring};
use jni::JNIEnv;

use crate::decoder::decode_auto;
use crate::format::{probe_bytes, probe_extension, Format};
use crate::midi::MidiFile;

fn format_ordinal(f: Format) -> jint {
    match f {
        Format::AacLc => 0,
        Format::HeAacV1 => 1,
        Format::HeAacV2 => 2,
        Format::XHeAac => 3,
        Format::Mp3 => 4,
        Format::Flac => 5,
        Format::Vorbis => 6,
        Format::Opus => 7,
        Format::AmrNb => 8,
        Format::AmrWb => 9,
        Format::Pcm => 10,
        Format::Wav => 11,
        Format::Midi => 12,
    }
}

fn jstring_to_rust(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|v| v.into())
}

fn probe_format_for_path(path: &str) -> Option<Format> {
    if let Ok(data) = read_head(path, 8192) {
        if let Some(f) = probe_bytes(&data) {
            return Some(f);
        }
    }
    let ext = path.rsplit('.').next().unwrap_or("");
    probe_extension(ext)
}

fn read_head(path: &str, n: usize) -> std::io::Result<Vec<u8>> {
    use std::io::Read;
    let mut f = std::fs::File::open(path)?;
    let mut buf = vec![0u8; n];
    let read = f.read(&mut buf)?;
    buf.truncate(read);
    Ok(buf)
}

fn read_all(path: &str) -> std::io::Result<Vec<u8>> {
    std::fs::read(path)
}

fn escape_json(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            _ => out.push(c),
        }
    }
    out
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: jni::JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    jni::sys::JNI_VERSION_1_6
}

#[no_mangle]
pub extern "system" fn Java_com_marotidev_citole_engine_CitoleEngine_probeFormat<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jint {
    let Some(p) = jstring_to_rust(&mut env, &path) else {
        return -1;
    };
    match probe_format_for_path(&p) {
        Some(f) => format_ordinal(f),
        None => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_marotidev_citole_engine_CitoleEngine_decodeToPcm<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jbyteArray {
    let Some(p) = jstring_to_rust(&mut env, &path) else {
        return std::ptr::null_mut();
    };
    let data = match read_all(&p) {
        Ok(d) => d,
        Err(_) => return std::ptr::null_mut(),
    };
    let pcm = match decode_auto(&data) {
        Ok(v) => v,
        Err(_) => return std::ptr::null_mut(),
    };
    let i16s = pcm.to_i16_interleaved();
    let mut bytes = Vec::with_capacity(i16s.len() * 2);
    for s in i16s {
        bytes.extend_from_slice(&s.to_le_bytes());
    }
    match env.byte_array_from_slice(&bytes) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_marotidev_citole_engine_CitoleEngine_getInfo<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    let Some(p) = jstring_to_rust(&mut env, &path) else {
        let s = r#"{"error":"invalid path","ordinal":-1}"#;
        return match env.new_string(s) {
            Ok(v) => v.into_raw(),
            Err(_) => std::ptr::null_mut(),
        };
    };
    let json = build_info_json(&p);
    match env.new_string(json) {
        Ok(v) => v.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn build_info_json(path: &str) -> String {
    let head = read_head(path, 8192).unwrap_or_default();
    let fmt_opt = probe_bytes(&head).or_else(|| {
        let ext = path.rsplit('.').next().unwrap_or("");
        probe_extension(ext)
    });
    let Some(fmt) = fmt_opt else {
        return format!(
            r#"{{"path":"{}","format":"unknown","ordinal":-1}}"#,
            escape_json(path)
        );
    };
    let ordinal = format_ordinal(fmt);
    let fmt_str = fmt.as_str();
    if fmt == Format::Midi {
        let data = read_all(path).unwrap_or_default();
        if let Ok(midi) = MidiFile::parse(&data) {
            return format!(
                r#"{{"path":"{}","format":"{}","ordinal":{},"ticksPerQuarter":{},"trackCount":{},"durationTicks":{},"eventCount":{}}}"#,
                escape_json(path),
                fmt_str,
                ordinal,
                midi.ticks_per_quarter,
                midi.track_count,
                midi.duration_ticks,
                midi.event_count()
            );
        }
        return format!(
            r#"{{"path":"{}","format":"{}","ordinal":{}}}"#,
            escape_json(path),
            fmt_str,
            ordinal
        );
    }
    let data = match read_all(path) {
        Ok(d) => d,
        Err(e) => {
            return format!(
                r#"{{"path":"{}","format":"{}","ordinal":{},"error":"{}"}}"#,
                escape_json(path),
                fmt_str,
                ordinal,
                escape_json(&e.to_string())
            )
        }
    };
    match decode_auto(&data) {
        Ok(pcm) => {
            let frames = pcm.frames();
            let duration = pcm.duration_secs();
            format!(
                r#"{{"path":"{}","format":"{}","ordinal":{},"sampleRate":{},"channels":{},"frames":{},"durationSecs":{:.6}}}"#,
                escape_json(path),
                fmt_str,
                ordinal,
                pcm.sample_rate,
                pcm.channels,
                frames,
                duration
            )
        }
        Err(e) => {
            let msg = escape_json(&e.to_string());
            format!(
                r#"{{"path":"{}","format":"{}","ordinal":{},"error":"{}"}}"#,
                escape_json(path),
                fmt_str,
                ordinal,
                msg
            )
        }
    }
}

#[allow(unused_imports)]
use jni::sys::jobject as _jobject_keep;
#[allow(unused_imports)]
use JByteArray as _JByteArray_keep;
