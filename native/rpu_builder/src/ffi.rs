//! The JNI entry points of `com.nuvio.tv.core.player.DoviRpuBuilder` (static methods: Kotlin
//! `@JvmStatic` on an object). Each one moves values between Java and `api.rs` inside
//! [`guard`], so a panic returns code 99 instead of unwinding into the JVM.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;

use jni::objects::{JByteArray, JClass, JIntArray, JObject};
use jni::sys::{jdouble, jint, jintArray, jlong, jobjectArray, jstring};
use jni::JNIEnv;

use crate::api::{self, WrapperError, BUILDERS, OK, PANIC};

/// `body`'s value, or `on_panic` when it panics. The panic hook still runs (its message goes to
/// stderr, which Android discards).
pub(crate) fn guard<T>(on_panic: T, body: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(body)).unwrap_or(on_panic)
}

/// A handle, or the negated code (`nativeCreate*`).
pub(crate) fn handle_or_code(result: api::Result<i64>) -> jlong {
    result.unwrap_or_else(|error| -jlong::from(error.code()))
}

/// 0, or the code.
pub(crate) fn code(result: api::Result<()>) -> jint {
    result.map_or_else(|error| error.code(), |()| OK)
}

/// A failed JNI call as code 22. A failed array allocation or access leaves a Java exception
/// pending; it is cleared so that the code, not an exception, reaches Kotlin.
fn checked<T>(env: &mut JNIEnv<'_>, result: jni::errors::Result<T>) -> api::Result<T> {
    result.map_err(|error| {
        if env.exception_check().unwrap_or(true) {
            let _ = env.exception_clear();
        }
        WrapperError::Jni(error.to_string())
    })
}

/// Longer than any array argument; a longer one is refused (code 20) without being read.
const MAX_INT_ARGS: i32 = 16;

/// An optional `int[]` argument: `None` for null.
fn read_ints(env: &mut JNIEnv<'_>, array: &JIntArray<'_>) -> api::Result<Option<Vec<i32>>> {
    if array.is_null() {
        return Ok(None);
    }
    let length = env.get_array_length(array);
    let length = checked(env, length)?;
    if length > MAX_INT_ARGS {
        return Err(WrapperError::BadArgument(format!(
            "{length} values in an int[] argument"
        )));
    }
    let mut values = vec![0; length.max(0) as usize];
    let read = env.get_int_array_region(array, 0, &mut values);
    checked(env, read)?;
    Ok(Some(values))
}

/// Writes `values` to `out`, which must hold exactly `values.len()` ints.
fn write_ints(env: &mut JNIEnv<'_>, out: &JIntArray<'_>, values: &[i32]) -> api::Result<()> {
    if out.is_null() {
        return Err(WrapperError::BadArgument("null out array".into()));
    }
    let length = env.get_array_length(out);
    if checked(env, length)? as usize != values.len() {
        return Err(WrapperError::BadArgument(format!(
            "out array needs {} values",
            values.len()
        )));
    }
    let written = env.set_int_array_region(out, 0, values);
    checked(env, written)
}

#[no_mangle]
pub extern "system" fn Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeVersion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    guard(ptr::null_mut(), || {
        let version = env.new_string(api::VERSION);
        match checked(&mut env, version) {
            Ok(version) => version.into_raw(),
            Err(_) => ptr::null_mut(),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeCreateStatic84<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    composer: jint,
) -> jlong {
    guard(-jlong::from(PANIC), || {
        handle_or_code(api::create_static_84(&BUILDERS, composer))
    })
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeCreate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    profile: jint,
    composer: jint,
    cm_version: jint,
    l1_avg_cm_version: jint,
    level6: JIntArray<'local>,
    source_range: JIntArray<'local>,
    level5: JIntArray<'local>,
    source_primaries: jint,
) -> jlong {
    guard(-jlong::from(PANIC), || {
        handle_or_code((|| {
            let level6 = read_ints(&mut env, &level6)?;
            let source_range = read_ints(&mut env, &source_range)?;
            let level5 = read_ints(&mut env, &level5)?;
            let params = api::params_with_primaries(
                profile,
                composer,
                cm_version,
                l1_avg_cm_version,
                level6.as_deref(),
                source_range.as_deref(),
                level5.as_deref(),
                source_primaries,
            )?;
            api::create(&BUILDERS, &params)
        })())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeStatic84L1<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jintArray {
    guard(ptr::null_mut(), || {
        let result = (|| {
            let array = env.new_int_array(3);
            let array = checked(&mut env, array)?;
            write_ints(&mut env, &array, &api::static_84_l1())?;
            Ok::<_, WrapperError>(array)
        })();
        result.map_or(ptr::null_mut(), JIntArray::into_raw)
    })
}

/// `[sceneRefresh, noRefresh]` as a Java `byte[][]`.
fn shot_arrays(env: &mut JNIEnv<'_>, nals: &api::ShotNals) -> api::Result<jobjectArray> {
    let class = env.find_class("[B");
    let class = checked(env, class)?;
    let array = env.new_object_array(2, &class, JObject::null());
    let array = checked(env, array)?;
    for (index, nal) in [&nals.scene_refresh, &nals.no_refresh]
        .into_iter()
        .enumerate()
    {
        let bytes = env.byte_array_from_slice(nal);
        let bytes = checked(env, bytes)?;
        let set = env.set_object_array_element(&array, index as i32, &bytes);
        checked(env, set)?;
        let _ = env.delete_local_ref(bytes);
    }
    Ok(array.into_raw())
}

#[no_mangle]
pub extern "system" fn Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeRenderShot<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    min_pq: jint,
    avg_pq: jint,
    max_pq: jint,
    code_out: JIntArray<'local>,
) -> jobjectArray {
    let result = guard(Err(PANIC), || {
        let nals = api::render_shot(&BUILDERS, handle, min_pq, avg_pq, max_pq);
        nals.and_then(|nals| shot_arrays(&mut env, &nals))
            .map_err(|error| error.code())
    });
    match result {
        Ok(array) => array,
        Err(code) => {
            // Without a usable codeOut, Kotlin still sees null.
            guard((), || {
                let _ = write_ints(&mut env, &code_out, &[code]);
            });
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeHdr10PlusL1<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JByteArray<'local>,
    peak_source: jint,
    cm_version: jint,
    out: JIntArray<'local>,
) -> jint {
    guard(PANIC, || {
        code((|| {
            if payload.is_null() {
                return Err(WrapperError::BadArgument("null payload".into()));
            }
            let bytes = env.convert_byte_array(&payload);
            let bytes = checked(&mut env, bytes)?;
            let l1 = api::hdr10plus_l1(&bytes, peak_source, cm_version)?;
            write_ints(&mut env, &out, &l1)
        })())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeHdr10PlusFrame<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    payload: JByteArray<'local>,
    peak_source: jint,
    cm_version: jint,
    out: JIntArray<'local>,
) -> jint {
    guard(PANIC, || {
        code((|| {
            if payload.is_null() {
                return Err(WrapperError::BadArgument("null payload".into()));
            }
            let bytes = env.convert_byte_array(&payload);
            let bytes = checked(&mut env, bytes)?;
            let frame = api::hdr10plus_frame(&bytes, peak_source, cm_version)?;
            write_ints(&mut env, &out, &frame)
        })())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeSourceRange81<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    min_nits: jdouble,
    max_nits: jdouble,
    out: JIntArray<'local>,
) -> jint {
    guard(PANIC, || {
        code(write_ints(
            &mut env,
            &out,
            &api::source_range_81(min_nits, max_nits),
        ))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeRelease<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    guard(PANIC, || code(api::release(&BUILDERS, handle)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::{Builders, PANIC_HANDLE};

    #[test]
    fn a_panic_is_code_99_at_every_kind_of_entry() {
        let builders = Builders::new();
        let handle = api::create_static_84(&builders, 1).unwrap();
        let [min, avg, max] = api::static_84_l1();
        // The bodies of nativeRelease, nativeCreate* and nativeRenderShot, with a panic in the
        // handle table (cfg(test) only).
        let release = guard(PANIC, || code(api::release(&builders, PANIC_HANDLE)));
        assert_eq!(release, 99);
        let create = guard(-jlong::from(PANIC), || -> jlong { panic!("test panic") });
        assert_eq!(create, -99);
        let render = guard(Err(PANIC), || {
            api::render_shot(&builders, PANIC_HANDLE, min, avg, max).map_err(|e| e.code())
        });
        assert_eq!(render, Err(99));
        // The table survives the panics: the lock is not poisoned for later calls.
        assert!(api::render_shot(&builders, handle, min, avg, max).is_ok());
        assert_eq!(code(api::release(&builders, handle)), OK);
    }

    #[test]
    fn results_become_handles_and_codes() {
        assert_eq!(handle_or_code(Ok(5)), 5);
        assert_eq!(handle_or_code(Err(WrapperError::UnknownHandle(5))), -21);
        assert_eq!(
            handle_or_code(Err(
                dovi_rpu_builder::Error::OutOfRange(String::new()).into()
            )),
            -3
        );
        assert_eq!(code(Ok(())), 0);
        assert_eq!(code(Err(WrapperError::Jni(String::new()))), 22);
    }
}
