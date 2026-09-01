use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jobject, jstring};

use crate::decoder::decode_auto;
use crate::format::{Format, probe_bytes, probe_extension};
use crate::midi::MidiFile;

#[inline]
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

#[inline]
fn jstring_to_rust(env: &JNIEnv, s: &JString) -> Option<String> {
    if env.exception_check().unwrap_or(false) {
        return None;
    }
    let js = unsafe { env.get_string_unchecked(s).ok()? };
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
        return None;
    }
    Some(js.into())
}

#[inline]
fn new_java_string(env: &mut JNIEnv, s: &str) -> jstring {
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
    match env.new_string(s) {
        Ok(v) => v.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[inline]
fn probe_format_for_path(path: &str) -> Option<Format> {
    if let Ok(data) = read_head(path, 8192) {
        if let Some(f) = probe_bytes(&data) {
            return Some(f);
        }
    }
    let ext = path.rsplit('.').next().unwrap_or("");
    probe_extension(ext)
}

#[inline]
fn read_head(path: &str, n: usize) -> std::io::Result<Vec<u8>> {
    use std::io::Read;
    let mut f = std::fs::File::open(path)?;
    let mut buf = vec![0u8; n];
    let read = f.read(&mut buf)?;
    buf.truncate(read);
    Ok(buf)
}

#[inline]
fn read_all(path: &str) -> std::io::Result<Vec<u8>> {
    std::fs::read(path)
}

#[inline]
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

#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(_vm: jni::JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    jni::sys::JNI_VERSION_1_6
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_marotidev_citole_engine_CitoleEngine_isAvailable<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
    1 as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_marotidev_citole_engine_CitoleEngine_probeFormat<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jint {
    let Some(p) = jstring_to_rust(&env, &path) else {
        return -1;
    };
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
        return -1;
    }
    match probe_format_for_path(&p) {
        Some(f) => format_ordinal(f),
        None => -1,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_marotidev_citole_engine_CitoleEngine_decodeToPcm<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jbyteArray {
    let Some(p) = jstring_to_rust(&env, &path) else {
        return std::ptr::null_mut();
    };
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
        return std::ptr::null_mut();
    }
    let data = match read_all(&p) {
        Ok(d) => d,
        Err(_) => return std::ptr::null_mut(),
    };
    let pcm = match decode_auto(&data) {
        Ok(v) => v,
        Err(_) => return std::ptr::null_mut(),
    };
    let bytes = pcm.to_le_bytes();
    match env.byte_array_from_slice(&bytes) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_marotidev_citole_engine_CitoleEngine_decodeToPcmDirect<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jobject {
    let Some(p) = jstring_to_rust(&env, &path) else {
        return std::ptr::null_mut();
    };
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
        return std::ptr::null_mut();
    }
    let data = match read_all(&p) {
        Ok(d) => d,
        Err(_) => return std::ptr::null_mut(),
    };
    let pcm = match decode_auto(&data) {
        Ok(v) => v,
        Err(_) => return std::ptr::null_mut(),
    };
    let bytes = pcm.to_le_bytes();
    if bytes.is_empty() {
        return std::ptr::null_mut();
    }
    let len = bytes.len();
    let leaked = Box::leak(bytes.into_boxed_slice());
    let ptr = leaked.as_mut_ptr();
    match unsafe { env.new_direct_byte_buffer(ptr, len) } {
        Ok(buf) => buf.into_raw(),
        Err(_) => {
            unsafe {
                let _ = Box::from_raw(leaked as *mut [u8]);
            }
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_marotidev_citole_engine_CitoleEngine_getInfo<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    let Some(p) = jstring_to_rust(&env, &path) else {
        return new_java_string(&mut env, r#"{"error":"invalid path","ordinal":-1}"#);
    };
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
        return new_java_string(&mut env, r#"{"error":"jni exception","ordinal":-1}"#);
    }
    let json = build_info_json(&p);
    new_java_string(&mut env, &json)
}

#[inline]
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
            );
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
