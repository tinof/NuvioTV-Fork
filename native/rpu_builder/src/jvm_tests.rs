//! The `Java_*` exports of `ffi.rs` called the way the JVM calls them: a real `JNIEnv` from a JVM
//! that jni's invocation API starts (feature `jvm-tests`, `JAVA_HOME` a JDK 17; README.md), and
//! real Java arrays. `-Xcheck:jni` makes the JVM itself check every JNI call the exports make, and
//! each test checks that no call leaves a Java exception pending.

use std::sync::OnceLock;

use jni::objects::{JByteArray, JClass, JIntArray, JLongArray, JObject, JObjectArray, JString};
use jni::sys::{jint, jintArray, jlong, JNI_FALSE, JNI_TRUE};
use jni::{InitArgsBuilder, JNIEnv, JNIVersion, JavaVM};

use crate::api::{self, BAD_ARGUMENT, OK, UNKNOWN_HANDLE};
use crate::ffi::{
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeCreate as native_create,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeCreateStatic84 as native_create_static_84,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeHdr10PlusFrame as native_hdr10plus_frame,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeHdr10PlusL1 as native_hdr10plus_l1,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeManifestCheckComposer as native_manifest_check_composer,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeManifestCheckSource as native_manifest_check_source,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeManifestCheckTrack as native_manifest_check_track,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeManifestCreateBuilder as native_manifest_create_builder,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeManifestParse as native_manifest_parse,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeMatcherCreate as native_matcher_create,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeMatcherNext as native_matcher_next,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeMatcherReset as native_matcher_reset,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeRelease as native_release,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeRenderShot as native_render_shot,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeSourceRange81 as native_source_range_81,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeStatic84L1 as native_static_84_l1,
    Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeVersion as native_version,
};
use crate::fixture_tests::{fixture, json, payloads, push_record, shots};
use crate::manifest::fixture::{
    self as manifest_fixture, data, expected_verdicts, CODEC_PRIVATE, HEAD, HEIGHT, KEY, PTS,
    SHOT_L1, SIZE, TAIL, TIME_BASE, WIDTH,
};
use crate::manifest::VERDICT_LEN;

/// One JVM per process (JNI_CreateJavaVM works once); every test thread attaches to it.
fn vm() -> &'static JavaVM {
    static VM: OnceLock<JavaVM> = OnceLock::new();
    VM.get_or_init(|| {
        let args = InitArgsBuilder::new()
            .version(JNIVersion::V8)
            .option("-Xcheck:jni")
            .build()
            .unwrap();
        JavaVM::new(args)
            .unwrap_or_else(|error| panic!("no JVM; set JAVA_HOME to a JDK 17: {error}"))
    })
}

/// `body` on this thread attached to the JVM.
fn with_env(body: impl FnOnce(&mut JNIEnv<'_>)) {
    let mut env = vm().attach_current_thread().unwrap();
    body(&mut env);
    assert!(
        !env.exception_check().unwrap(),
        "a Java exception is pending"
    );
}

/// The `JNIEnv` and `jclass` arguments of an export. The class is unused by every export (static
/// methods of an object), so null stands in for `DoviRpuBuilder`.
fn args<'local>(env: &JNIEnv<'local>) -> (JNIEnv<'local>, JClass<'local>) {
    // SAFETY: the copy is used only for one call on this thread, while `env` is alive.
    (unsafe { env.unsafe_clone() }, JClass::from(JObject::null()))
}

fn assert_no_exception(env: &mut JNIEnv<'_>, call: &str) {
    assert!(!env.exception_check().unwrap(), "{call} left an exception");
}

fn ints<'local>(env: &mut JNIEnv<'local>, values: &[i32]) -> JIntArray<'local> {
    let array = env.new_int_array(values.len() as i32).unwrap();
    env.set_int_array_region(&array, 0, values).unwrap();
    array
}

fn read_ints(env: &mut JNIEnv<'_>, array: &JIntArray<'_>) -> Vec<i32> {
    let mut values = vec![0; env.get_array_length(array).unwrap() as usize];
    env.get_int_array_region(array, 0, &mut values).unwrap();
    values
}

/// A second local reference to `array`, to pass to an export that takes it by value.
fn same<'local>(env: &mut JNIEnv<'local>, array: &JIntArray<'local>) -> JIntArray<'local> {
    JIntArray::from(env.new_local_ref(array).unwrap())
}

fn null_ints<'local>() -> JIntArray<'local> {
    JIntArray::from(JObject::null())
}

/// nativeRenderShot: `Ok([sceneRefresh, noRefresh])`, or `Err(codeOut[0])` when it returns null.
fn render_shot(env: &mut JNIEnv<'_>, handle: jlong, l1: [i32; 3]) -> Result<[Vec<u8>; 2], jint> {
    let code_out = ints(env, &[-1]);
    let (call_env, class) = args(env);
    let code_arg = same(env, &code_out);
    let raw = native_render_shot(call_env, class, handle, l1[0], l1[1], l1[2], code_arg);
    assert_no_exception(env, "nativeRenderShot");
    if raw.is_null() {
        return Err(read_ints(env, &code_out)[0]);
    }
    assert_eq!(
        read_ints(env, &code_out),
        [-1],
        "codeOut written on success"
    );
    // SAFETY: a local reference to the byte[][] the export just returned.
    let array = unsafe { JObjectArray::from_raw(raw) };
    assert_eq!(env.get_array_length(&array).unwrap(), 2);
    let mut nal = |index| {
        let bytes = JByteArray::from(env.get_object_array_element(&array, index).unwrap());
        env.convert_byte_array(bytes).unwrap()
    };
    Ok([nal(0), nal(1)])
}

/// Each shot rendered once through nativeRenderShot, in the `RPU.bin` layout.
fn render(env: &mut JNIEnv<'_>, handle: jlong, shots: &[(u64, [i32; 3])]) -> Vec<u8> {
    let mut out = Vec::new();
    for &(duration, l1) in shots {
        let [scene_refresh, no_refresh] = render_shot(env, handle, l1).unwrap();
        push_record(&mut out, &scene_refresh);
        for _ in 1..duration {
            push_record(&mut out, &no_refresh);
        }
    }
    out
}

fn release(env: &mut JNIEnv<'_>, handle: jlong) -> jint {
    let (call_env, class) = args(env);
    let code = native_release(call_env, class, handle);
    assert_no_exception(env, "nativeRelease");
    code
}

/// nativeCreate with optional `int[]` arguments.
fn create(
    env: &mut JNIEnv<'_>,
    [profile, composer, cm, avg_cm]: [jint; 4],
    level6: Option<&[i32]>,
    source_range: Option<&[i32]>,
    level5: Option<&[i32]>,
) -> jlong {
    let mut array = |values: Option<&[i32]>| values.map_or_else(null_ints, |v| ints(env, v));
    let (level6, source_range, level5) = (array(level6), array(source_range), array(level5));
    let (call_env, class) = args(env);
    let handle = native_create(
        call_env,
        class,
        profile,
        composer,
        cm,
        avg_cm,
        level6,
        source_range,
        level5,
        api::BT2020_PRIMARIES,
    );
    assert_no_exception(env, "nativeCreate");
    handle
}

#[test]
fn version_and_static_l1_come_back_as_java_values() {
    with_env(|env| {
        let (call_env, class) = args(env);
        let raw = native_version(call_env, class);
        assert_no_exception(env, "nativeVersion");
        assert!(!raw.is_null());
        // SAFETY: a local reference to the String the export just returned.
        let version = unsafe { JString::from_raw(raw) };
        let version: String = env.get_string(&version).unwrap().into();
        assert_eq!(version, api::VERSION);

        let (call_env, class) = args(env);
        let raw: jintArray = native_static_84_l1(call_env, class);
        assert_no_exception(env, "nativeStatic84L1");
        assert!(!raw.is_null());
        // SAFETY: a local reference to the int[] the export just returned.
        let l1 = unsafe { JIntArray::from_raw(raw) };
        assert_eq!(read_ints(env, &l1), api::static_84_l1());
    });
}

#[test]
fn static_84_bt2100_and_canary_equal_dovi_tool_through_jni() {
    with_env(|env| {
        for (composer, name) in [(1, "static_84_bt2100"), (2, "static_84_canary")] {
            let shots = shots(&json(&format!("{name}.extra.json")));
            let expected = fixture(&format!("{name}.RPU.bin"));

            let (call_env, class) = args(env);
            let handle = native_create_static_84(call_env, class, composer);
            assert_no_exception(env, "nativeCreateStatic84");
            assert!(handle > 0, "{name}: {handle}");
            assert!(
                render(env, handle, &shots) == expected,
                "{name}: nativeCreateStatic84 differs from dovi_tool through JNI"
            );

            // nativeCreate with the same values and null arrays.
            let same = create(env, [84, composer, 40, 29], None, None, None);
            assert!(same > 0, "{name}: {same}");
            assert!(
                render(env, same, &shots) == expected,
                "{name}: nativeCreate differs from dovi_tool through JNI"
            );
            assert_eq!(release(env, handle), OK);
            assert_eq!(release(env, same), OK);
        }
    });
}

#[test]
fn profile_81_with_int_arrays_equals_dovi_tool_through_jni() {
    with_env(|env| {
        let shots = shots(&json("p81_v40_shots.extra.json"));
        let handle = create(
            env,
            [81, 0, 40, 29],
            Some(&[1000, 1, 997, 91]),
            Some(&[7, 3079]),
            Some(&[0, 4, 140, 136]),
        );
        assert!(handle > 0, "{handle}");
        assert!(
            render(env, handle, &shots) == fixture("p81_v40_shots.RPU.bin"),
            "p81_v40_shots differs from dovi_tool through JNI"
        );
        assert_eq!(release(env, handle), OK);
    });
}

#[test]
fn wrong_length_int_arrays_are_code_20() {
    with_env(|env| {
        let bad = -jlong::from(BAD_ARGUMENT);
        // level6 takes 4 values, source range 2, level5 4.
        assert_eq!(
            create(env, [81, 0, 40, 29], Some(&[1000, 1, 1000]), None, None),
            bad
        );
        assert_eq!(create(env, [81, 0, 40, 29], None, Some(&[7]), None), bad);
        assert_eq!(create(env, [81, 0, 40, 29], None, None, Some(&[0; 5])), bad);
        // Longer than any argument: refused before it is read.
        assert_eq!(
            create(env, [81, 0, 40, 29], Some(&[0; 17]), None, None),
            bad
        );
    });
}

#[test]
fn hdr10plus_l1_fills_the_out_array() {
    with_env(|env| {
        let payload = &payloads("profile_a")[0];
        let call = |env: &mut JNIEnv<'_>, payload: Option<&[u8]>, out: JIntArray<'_>| {
            let payload = payload.map_or_else(
                || JByteArray::from(JObject::null()),
                |bytes| env.byte_array_from_slice(bytes).unwrap(),
            );
            let (call_env, class) = args(env);
            let out = JIntArray::from(env.new_local_ref(out).unwrap());
            let code = native_hdr10plus_l1(call_env, class, payload, 0, 40, out);
            assert_no_exception(env, "nativeHdr10PlusL1");
            code
        };

        let out = ints(env, &[-1, -1, -1]);
        let reference = env.new_local_ref(&out).unwrap();
        assert_eq!(call(env, Some(payload), JIntArray::from(reference)), OK);
        let l1 = read_ints(env, &out);
        assert_eq!(l1, api::hdr10plus_l1(payload, 0, 40).unwrap());
        assert!(l1[2] > l1[1] && l1[1] > l1[0], "{l1:?}");

        let short = ints(env, &[-1, -1]);
        let reference = env.new_local_ref(&short).unwrap();
        assert_eq!(
            call(env, Some(payload), JIntArray::from(reference)),
            BAD_ARGUMENT
        );
        assert_eq!(
            read_ints(env, &short),
            [-1, -1],
            "a short out array is not written"
        );
        assert_eq!(call(env, Some(payload), null_ints()), BAD_ARGUMENT);
        let out = ints(env, &[-1, -1, -1]);
        assert_eq!(call(env, None, out), BAD_ARGUMENT);
    });
}

#[test]
fn hdr10plus_frame_fills_l1_and_the_key() {
    with_env(|env| {
        let call = |env: &mut JNIEnv<'_>, payload: Option<&[u8]>, out: JIntArray<'_>| {
            let payload = payload.map_or_else(
                || JByteArray::from(JObject::null()),
                |bytes| env.byte_array_from_slice(bytes).unwrap(),
            );
            let (call_env, class) = args(env);
            let code = native_hdr10plus_frame(call_env, class, payload, 0, 40, out);
            assert_no_exception(env, "nativeHdr10PlusFrame");
            code
        };
        // profile_a: frames 0 to 2 are one scene, frame 3 starts the next.
        let payloads = payloads("profile_a");
        let mut frames = Vec::new();
        for payload in &payloads[..4] {
            let out = ints(env, &[-1; 7]);
            let reference = same(env, &out);
            assert_eq!(call(env, Some(payload), reference), OK);
            let frame = read_ints(env, &out);
            assert_eq!(frame, api::hdr10plus_frame(payload, 0, 40).unwrap());
            assert_eq!(frame[..3], api::hdr10plus_l1(payload, 0, 40).unwrap());
            frames.push(frame);
        }
        assert_eq!(frames[0][3..], frames[2][3..], "same scene, same key");
        assert_ne!(frames[2][3..], frames[3][3..], "a new scene, a new key");

        let short = ints(env, &[-1; 3]);
        let reference = same(env, &short);
        assert_eq!(call(env, Some(&payloads[0]), reference), BAD_ARGUMENT);
        assert_eq!(
            read_ints(env, &short),
            [-1; 3],
            "a short out array is not written"
        );
        assert_eq!(call(env, Some(&payloads[0]), null_ints()), BAD_ARGUMENT);
        let out = ints(env, &[-1; 7]);
        let reference = same(env, &out);
        assert_eq!(call(env, None, reference), BAD_ARGUMENT);
        // Not HDR10+: code 4, the out array untouched.
        let reference = same(env, &out);
        assert_eq!(call(env, Some(&[0xB5]), reference), 4);
        assert_eq!(read_ints(env, &out), [-1; 7]);
    });
}

#[test]
fn source_range_81_fills_the_out_array() {
    with_env(|env| {
        let call = |env: &mut JNIEnv<'_>, min: f64, max: f64, out: JIntArray<'_>| {
            let (call_env, class) = args(env);
            let code = native_source_range_81(call_env, class, min, max, out);
            assert_no_exception(env, "nativeSourceRange81");
            code
        };
        let out = ints(env, &[0, 0]);
        let reference = same(env, &out);
        assert_eq!(call(env, 0.0001, 1000.0, reference), OK);
        assert_eq!(read_ints(env, &out), [7, 3079]);
        let reference = same(env, &out);
        assert_eq!(call(env, 50.0, 1000.0, reference), OK);
        assert_eq!(read_ints(env, &out), [-1, -1]);
        let long = ints(env, &[0; 3]);
        assert_eq!(call(env, 0.0001, 1000.0, long), BAD_ARGUMENT);
        assert_eq!(call(env, 0.0001, 1000.0, null_ints()), BAD_ARGUMENT);
    });
}

#[test]
fn a_stale_handle_is_code_21() {
    with_env(|env| {
        let (call_env, class) = args(env);
        let handle = native_create_static_84(call_env, class, 1);
        assert!(handle > 0, "{handle}");
        let l1 = api::static_84_l1();
        assert!(render_shot(env, handle, l1).is_ok());
        assert_eq!(release(env, handle), OK);

        assert_eq!(render_shot(env, handle, l1), Err(UNKNOWN_HANDLE));
        assert_eq!(release(env, handle), UNKNOWN_HANDLE);
        assert_eq!(render_shot(env, 0, l1), Err(UNKNOWN_HANDLE));
        // Without a codeOut the failure is still null, and nothing is thrown.
        let (call_env, class) = args(env);
        let raw = native_render_shot(call_env, class, handle, l1[0], l1[1], l1[2], null_ints());
        assert_no_exception(env, "nativeRenderShot");
        assert!(raw.is_null());
    });
}

/// A `byte[]` of `bytes`, or null.
fn bytes<'local>(env: &mut JNIEnv<'local>, bytes: Option<&[u8]>) -> JByteArray<'local> {
    bytes.map_or_else(
        || JByteArray::from(JObject::null()),
        |bytes| env.byte_array_from_slice(bytes).unwrap(),
    )
}

fn manifest_parse(env: &mut JNIEnv<'_>, json: Option<&[u8]>) -> jlong {
    let json = bytes(env, json);
    let (call_env, class) = args(env);
    let handle = native_manifest_parse(call_env, class, json);
    assert_no_exception(env, "nativeManifestParse");
    handle
}

fn check_source(
    env: &mut JNIEnv<'_>,
    handle: jlong,
    size: jlong,
    digests: [Option<&[u8]>; 3],
) -> jint {
    let [head, tail, key] = digests.map(|digest| bytes(env, digest));
    let (call_env, class) = args(env);
    let code = native_manifest_check_source(call_env, class, handle, size, head, tail, key);
    assert_no_exception(env, "nativeManifestCheckSource");
    code
}

fn check_track(
    env: &mut JNIEnv<'_>,
    handle: jlong,
    digest: Option<&[u8]>,
    size: [jint; 2],
    time_base: (jint, jint),
) -> jint {
    let digest = bytes(env, digest);
    let (call_env, class) = args(env);
    let code = native_manifest_check_track(
        call_env,
        class,
        handle,
        digest,
        size[0],
        size[1],
        time_base.0,
        time_base.1,
    );
    assert_no_exception(env, "nativeManifestCheckTrack");
    code
}

/// nativeMatcherNext on `length` bytes from `offset` of `array`: the code and the out array (its
/// values stay -7 where the export did not write).
fn matcher_next(
    env: &mut JNIEnv<'_>,
    handle: jlong,
    pts: jlong,
    array: Option<&[u8]>,
    range: [jint; 2],
    laced: bool,
    out_length: Option<usize>,
) -> (jint, Vec<i64>) {
    let data = bytes(env, array);
    let out = out_length.map(|length| {
        let out = env.new_long_array(length as i32).unwrap();
        env.set_long_array_region(&out, 0, &vec![-7; length])
            .unwrap();
        out
    });
    let out_arg = match &out {
        Some(out) => JLongArray::from(env.new_local_ref(out).unwrap()),
        None => JLongArray::from(JObject::null()),
    };
    let (call_env, class) = args(env);
    let laced = if laced { JNI_TRUE } else { JNI_FALSE };
    let code = native_matcher_next(
        call_env, class, handle, pts, data, range[0], range[1], laced, out_arg,
    );
    assert_no_exception(env, "nativeMatcherNext");
    let values = out.map_or_else(Vec::new, |out| {
        let mut values = vec![0; env.get_array_length(&out).unwrap() as usize];
        env.get_long_array_region(&out, 0, &mut values).unwrap();
        values
    });
    (code, values)
}

/// AU `index` at offset 3 of a larger array, as an extractor's buffer holds it.
fn au_in_buffer(index: usize) -> (Vec<u8>, [jint; 2]) {
    let au = data(index);
    let mut buffer = vec![0xEE; 3];
    buffer.extend_from_slice(&au);
    buffer.extend_from_slice(&[0xEE; 5]);
    (buffer, [3, au.len() as jint])
}

#[test]
fn a_manifest_parses_and_checks_through_jni() {
    with_env(|env| {
        let handle = manifest_parse(env, Some(&manifest_fixture::json()));
        assert!(handle > 0, "{handle}");
        assert_eq!(
            check_source(env, handle, SIZE, [Some(&HEAD), Some(&TAIL), Some(&KEY)]),
            OK
        );
        assert_eq!(
            check_source(
                env,
                handle,
                SIZE + 1,
                [Some(&HEAD), Some(&TAIL), Some(&KEY)]
            ),
            8
        );
        assert_eq!(
            check_source(env, handle, SIZE, [Some(&TAIL), Some(&TAIL), Some(&KEY)]),
            8
        );
        assert_eq!(
            check_track(
                env,
                handle,
                Some(&CODEC_PRIVATE),
                [WIDTH, HEIGHT],
                TIME_BASE
            ),
            OK
        );
        assert_eq!(
            check_track(
                env,
                handle,
                Some(&CODEC_PRIVATE),
                [WIDTH, HEIGHT],
                (1, 1_000_000)
            ),
            8
        );
        for (composer, expected) in [(1, OK), (0, 8), (2, 8), (3, BAD_ARGUMENT)] {
            let (call_env, class) = args(env);
            let code = native_manifest_check_composer(call_env, class, handle, composer);
            assert_no_exception(env, "nativeManifestCheckComposer");
            assert_eq!(code, expected, "composer {composer}");
        }

        // Null, short and long digests are code 20; a long one is not read.
        for digests in [
            [None, Some(&TAIL[..]), Some(&KEY[..])],
            [Some(&HEAD[..]), Some(&TAIL[..31]), Some(&KEY[..])],
            [Some(&HEAD[..]), Some(&TAIL[..]), Some(&[0; 33][..])],
        ] {
            assert_eq!(check_source(env, handle, SIZE, digests), BAD_ARGUMENT);
        }
        assert_eq!(
            check_source(env, handle, -1, [Some(&HEAD), Some(&TAIL), Some(&KEY)]),
            BAD_ARGUMENT
        );
        assert_eq!(
            check_track(env, handle, None, [WIDTH, HEIGHT], TIME_BASE),
            BAD_ARGUMENT
        );
        assert_eq!(
            check_track(env, handle, Some(&CODEC_PRIVATE), [-1, HEIGHT], TIME_BASE),
            BAD_ARGUMENT
        );
        assert_eq!(release(env, handle), OK);
        assert_eq!(
            check_source(env, handle, SIZE, [Some(&HEAD), Some(&TAIL), Some(&KEY)]),
            UNKNOWN_HANDLE
        );

        // Refusals are negated codes and take no handle.
        assert_eq!(manifest_parse(env, Some(b"{")), -8);
        assert_eq!(manifest_parse(env, Some(&[0xFF, 0xFE])), -8);
        assert_eq!(manifest_parse(env, None), -jlong::from(BAD_ARGUMENT));
    });
}

#[test]
fn the_matcher_gives_each_au_its_verdict_through_jni() {
    with_env(|env| {
        let manifest = manifest_parse(env, Some(&manifest_fixture::json()));
        let (call_env, class) = args(env);
        let matcher = native_matcher_create(call_env, class, manifest);
        assert_no_exception(env, "nativeMatcherCreate");
        assert!(matcher > manifest, "{matcher}");
        // The matcher keeps its own table: releasing the manifest first is fine.
        assert_eq!(release(env, manifest), OK);

        let feed = |env: &mut JNIEnv<'_>, i: usize| {
            let (buffer, range) = au_in_buffer(i);
            let (code, verdict) = matcher_next(
                env,
                matcher,
                PTS[i],
                Some(&buffer),
                range,
                false,
                Some(VERDICT_LEN),
            );
            assert_eq!(code, OK, "AU {i}");
            assert_eq!(verdict, expected_verdicts()[i], "AU {i}");
        };
        for i in 0..4 {
            feed(env, i);
        }

        // In the middle of the stream, a refused call for AU 4 leaves the matcher where it was and
        // the out array unwritten: had it reached the matcher, AU 4 below would be out of order.
        let (buffer, au_range) = au_in_buffer(4);
        for (case, array, range, out) in [
            ("null data", None, [0, 0], Some(VERDICT_LEN)),
            (
                "offset past the end",
                Some(&buffer[..]),
                [buffer.len() as jint, 1],
                Some(VERDICT_LEN),
            ),
            (
                "negative offset",
                Some(&buffer[..]),
                [-1, 2],
                Some(VERDICT_LEN),
            ),
            (
                "negative length",
                Some(&buffer[..]),
                [0, -1],
                Some(VERDICT_LEN),
            ),
            (
                "int overflow",
                Some(&buffer[..]),
                [jint::MAX, jint::MAX],
                Some(VERDICT_LEN),
            ),
            (
                "short out",
                Some(&buffer[..]),
                au_range,
                Some(VERDICT_LEN - 1),
            ),
            ("null out", Some(&buffer[..]), au_range, None),
        ] {
            let (code, values) = matcher_next(env, matcher, PTS[4], array, range, false, out);
            assert_eq!(code, BAD_ARGUMENT, "{case}");
            assert!(values.iter().all(|&v| v == -7), "{case}");
        }
        for i in 4..PTS.len() {
            feed(env, i);
        }

        // Still at the end of the table: the next AU is past it (reason 6), sticky after a reset.
        let (code, verdict) = matcher_next(
            env,
            matcher,
            8,
            Some(&buffer),
            [0, 2],
            false,
            Some(VERDICT_LEN),
        );
        assert_eq!((code, &verdict), (OK, &vec![2, 0, 0, 0, 0, 0, 0, 6, -1]));
        let (call_env, class) = args(env);
        assert_eq!(native_matcher_reset(call_env, class, matcher), OK);
        assert_no_exception(env, "nativeMatcherReset");
        let (buffer, range) = au_in_buffer(0);
        let (_, again) = matcher_next(
            env,
            matcher,
            0,
            Some(&buffer),
            range,
            true,
            Some(VERDICT_LEN),
        );
        assert_eq!(again, verdict);

        assert_eq!(release(env, matcher), OK);
        let (code, _) = matcher_next(
            env,
            matcher,
            0,
            Some(&buffer),
            range,
            false,
            Some(VERDICT_LEN),
        );
        assert_eq!(code, UNKNOWN_HANDLE);
        let (call_env, class) = args(env);
        assert_eq!(
            native_matcher_reset(call_env, class, matcher),
            UNKNOWN_HANDLE
        );
    });
}

#[test]
fn a_laced_au_is_rejected_through_jni() {
    with_env(|env| {
        let manifest = manifest_parse(env, Some(&manifest_fixture::json()));
        let (call_env, class) = args(env);
        let matcher = native_matcher_create(call_env, class, manifest);
        let (buffer, range) = au_in_buffer(0);
        let (code, verdict) = matcher_next(
            env,
            matcher,
            0,
            Some(&buffer),
            range,
            true,
            Some(VERDICT_LEN),
        );
        assert_eq!((code, verdict), (OK, vec![2, 0, 0, 0, 0, 0, 0, 5, -1]));
        assert_eq!(release(env, matcher), OK);
        assert_eq!(release(env, manifest), OK);
    });
}

#[test]
fn the_manifests_builder_renders_through_jni() {
    with_env(|env| {
        let manifest = manifest_parse(env, Some(&manifest_fixture::json()));
        let (call_env, class) = args(env);
        let builder = native_manifest_create_builder(call_env, class, manifest);
        assert_no_exception(env, "nativeManifestCreateBuilder");
        assert!(builder > manifest, "{builder}");
        let static_84 = create(env, [84, 1, 40, 29], None, None, None);
        for l1 in SHOT_L1 {
            let l1 = l1.map(i32::from);
            assert_eq!(
                render_shot(env, builder, l1),
                render_shot(env, static_84, l1)
            );
        }
        // A builder handle is not a manifest, and a manifest handle is not a builder.
        let (call_env, class) = args(env);
        assert_eq!(
            native_manifest_create_builder(call_env, class, builder),
            -jlong::from(UNKNOWN_HANDLE)
        );
        assert_eq!(
            render_shot(env, manifest, api::static_84_l1()),
            Err(UNKNOWN_HANDLE)
        );
        for handle in [builder, static_84, manifest] {
            assert_eq!(release(env, handle), OK);
        }
    });
}
