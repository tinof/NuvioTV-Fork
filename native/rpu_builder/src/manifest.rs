//! The playback manifest behind the handle table (ROADMAP DV4.6), with no JNI types: hdr-analyze's
//! `dovi_rpu_builder::manifest` (`docs/PLAYBACK_MANIFEST.md` there). `ffi.rs` moves the values.
//!
//! - [`parse`] keeps a parsed manifest under a handle. The crate's strict parser refuses anything it
//!   cannot use (code 8), so a manifest handle always describes a consistent manifest.
//! - [`check_source`], [`check_track`] and [`check_composer`] are the spec's candidate and track
//!   steps. Digests cross the boundary as 32 raw bytes and become the lowercase hex the crate
//!   compares, so the player never formats hex itself.
//! - [`create_builder`] makes the manifest's own RPU builder, whose RPUs equal mkvdovi's.
//! - [`create_matcher`], [`next`] and [`reset`] wrap `AuMatcher`. A matcher keeps its own copy of
//!   the AU table, so it outlives the release of its manifest.
//!
//! Every refusal of the crate is code 8; a mismatch inside the stream is not a code but a
//! [`next`] verdict with the crate's stable reason (1 unknown pts, 2 size, 3 CRC, 4 order,
//! 5 laced, 6 past the end).

use std::sync::{Arc, Mutex, PoisonError};

use dovi_rpu_builder::manifest::{AuMatcher, AuVerdict, PlaybackManifest, QualityClass};
use dovi_rpu_builder::{Error, RpuBuilder};

use crate::api::{bad, composer, u32_field, Entry, Handles, Result, WrapperError};

/// Longer than any version 1 manifest: three hours at 60 fps is about 650 000 AUs, under 20 bytes
/// of JSON each. A longer one is refused (code 20) without being read.
pub const MAX_MANIFEST_BYTES: usize = 64 << 20;

/// A SHA-256 digest.
pub const DIGEST_BYTES: usize = 32;

/// The length of a [`next`] verdict:
///
/// | Index | Value |
/// |---|---|
/// | 0 | [`INJECT`], [`FALLBACK`] or [`REJECTED`] |
/// | 1-3 | inject: L1 min, avg, max (12-bit PQ, unclamped, as the manifest has them) |
/// | 4 | inject: 1 when the AU starts its shot (`scene_refresh`) |
/// | 5 | inject: the shot's index in the manifest |
/// | 6 | inject: the coverage class, [`CLASS_FULL`] or [`CLASS_APPROXIMATE`] |
/// | 7 | rejected: the reason code, 1-6 |
/// | 8 | rejected: the table's decode index the AU was checked against, or -1 |
///
/// Unused fields are 0, apart from index 8, which is -1.
pub const VERDICT_LEN: usize = 9;
pub const INJECT: i64 = 0;
pub const FALLBACK: i64 = 1;
pub const REJECTED: i64 = 2;
pub const CLASS_FULL: i64 = 0;
pub const CLASS_APPROXIMATE: i64 = 1;

fn manifest_error(message: impl Into<String>) -> WrapperError {
    Error::Manifest(message.into()).into()
}

/// `nativeManifestParse`: the manifest's UTF-8 JSON under a new handle.
pub fn parse(handles: &Handles, json: &[u8]) -> Result<i64> {
    if json.len() > MAX_MANIFEST_BYTES {
        return Err(bad(format!("a manifest of {} bytes", json.len())));
    }
    let text =
        std::str::from_utf8(json).map_err(|error| manifest_error(format!("not UTF-8: {error}")))?;
    let manifest = PlaybackManifest::parse(text)?;
    Ok(handles.insert(Entry::Manifest(Arc::new(manifest))))
}

fn manifest(handles: &Handles, handle: i64) -> Result<Arc<PlaybackManifest>> {
    handles.get(handle, |entry| match entry {
        Entry::Manifest(manifest) => Some(Arc::clone(manifest)),
        _ => None,
    })
}

fn matcher(handles: &Handles, handle: i64) -> Result<Arc<Mutex<AuMatcher>>> {
    handles.get(handle, |entry| match entry {
        Entry::Matcher(matcher) => Some(Arc::clone(matcher)),
        _ => None,
    })
}

/// A raw SHA-256 digest as the 64 lowercase hex digits the manifest stores.
fn hex(name: &str, digest: &[u8]) -> Result<String> {
    if digest.len() != DIGEST_BYTES {
        return Err(bad(format!(
            "{name}: {} bytes, expected {DIGEST_BYTES}",
            digest.len()
        )));
    }
    Ok(digest.iter().map(|byte| format!("{byte:02x}")).collect())
}

/// `nativeManifestCheckSource`: the candidate step. The file's size, the SHA-256 of its first and
/// last MiB, and the `lookup_key` the player computed from them and fetched the manifest by.
pub fn check_source(
    handles: &Handles,
    handle: i64,
    size_bytes: i64,
    head_sha256: &[u8],
    tail_sha256: &[u8],
    lookup_key: &[u8],
) -> Result<()> {
    let size = u64::try_from(size_bytes)
        .map_err(|_| bad(format!("source size {size_bytes} is negative")))?;
    let head = hex("head digest", head_sha256)?;
    let tail = hex("tail digest", tail_sha256)?;
    let key = hex("lookup key", lookup_key)?;
    let manifest = manifest(handles, handle)?;
    // The crate does not compute SHA-256, so it cannot tie the key to the digests; the player
    // computed the key from the file, and a manifest filed under another key is not this file's.
    if key != manifest.source.lookup_key {
        return Err(manifest_error("the lookup_key differs"));
    }
    Ok(manifest.check_source(size, &head, &tail)?)
}

/// `nativeManifestCheckTrack`: the track step. The SHA-256 of the track's CodecPrivate, its
/// dimensions and its time base in seconds per tick. For Matroska that is `(TimestampScale,
/// 1_000_000_000)`. The manifest stores FFmpeg's stream time base, the same fraction in lowest terms
/// (1 000 000 → 1/1000, 3 000 000 → 3/1000), so a positive fraction is reduced here before the
/// crate compares it exactly.
pub fn check_track(
    handles: &Handles,
    handle: i64,
    codec_private_sha256: &[u8],
    width: i32,
    height: i32,
    time_base: (i32, i32),
) -> Result<()> {
    let digest = hex("CodecPrivate digest", codec_private_sha256)?;
    let width = u32_field("width", width)?;
    let height = u32_field("height", height)?;
    let manifest = manifest(handles, handle)?;
    Ok(manifest.check_track(&digest, width, height, reduced(time_base))?)
}

/// `(num, den)` in lowest terms when both are positive, as FFmpeg's `av_reduce` leaves an exact
/// fraction; anything else unchanged, so it fails the crate's comparison.
fn reduced((num, den): (i32, i32)) -> (i32, i32) {
    if num <= 0 || den <= 0 {
        return (num, den);
    }
    let (mut a, mut b) = (num, den);
    while b != 0 {
        (a, b) = (b, a % b);
    }
    (num / a, den / a)
}

/// `nativeManifestCheckComposer`: refuses a manifest whose 8.4 composer is not the one the player
/// writes (`api::composer` values), and every 8.1 manifest.
pub fn check_composer(handles: &Handles, handle: i64, composer_value: i32) -> Result<()> {
    let expected = composer(composer_value)?;
    Ok(manifest(handles, handle)?.check_composer(expected)?)
}

/// `nativeManifestCreateBuilder`: a builder (an `api` builder handle) for the manifest's `rpu`.
/// The parser already refused an `rpu` the builder would refuse, so this fails only on a handle.
pub fn create_builder(handles: &Handles, handle: i64) -> Result<i64> {
    let builder = RpuBuilder::new(&manifest(handles, handle)?.rpu)?;
    Ok(handles.insert(Entry::Builder(Arc::new(builder))))
}

/// `nativeMatcherCreate`: a matcher for the manifest, waiting for its first IRAP.
pub fn create_matcher(handles: &Handles, manifest_handle: i64) -> Result<i64> {
    let manifest = manifest(handles, manifest_handle)?;
    let matcher = AuMatcher::new(&manifest)?;
    Ok(handles.insert(Entry::Matcher(Arc::new(Mutex::new(matcher)))))
}

/// A matcher even after a panic inside it: `AuMatcher` changes its state in one assignment, so a
/// poisoned one is still consistent. The lock is held only for one call, never across JNI calls.
fn with_matcher<T>(
    handles: &Handles,
    handle: i64,
    body: impl FnOnce(&mut AuMatcher) -> T,
) -> Result<T> {
    let matcher = matcher(handles, handle)?;
    let mut matcher = matcher.lock().unwrap_or_else(PoisonError::into_inner);
    Ok(body(&mut matcher))
}

/// `nativeMatcherNext`: the verdict for the next AU in decode order ([`VERDICT_LEN`] fields). `pts`
/// is in the track's ticks (cluster plus block timecode), `data` the AU's bytes as the container
/// stores them, `laced` whether its block was laced.
pub fn next(
    handles: &Handles,
    handle: i64,
    pts: i64,
    data: &[u8],
    laced: bool,
) -> Result<[i64; VERDICT_LEN]> {
    with_matcher(handles, handle, |matcher| {
        verdict_fields(&matcher.next(pts, data, laced))
    })
}

/// `nativeMatcherReset`: after a seek or a flush, wait for an IRAP again. A rejection stays.
pub fn reset(handles: &Handles, handle: i64) -> Result<()> {
    with_matcher(handles, handle, AuMatcher::reset)
}

pub fn verdict_fields(verdict: &AuVerdict) -> [i64; VERDICT_LEN] {
    let mut fields = [0; VERDICT_LEN];
    fields[8] = -1;
    match verdict {
        AuVerdict::Inject {
            l1,
            scene_refresh,
            shot,
            class,
        } => {
            fields[0] = INJECT;
            fields[1] = i64::from(l1.min_pq);
            fields[2] = i64::from(l1.avg_pq);
            fields[3] = i64::from(l1.max_pq);
            fields[4] = i64::from(*scene_refresh);
            // A shot index fits: the parser holds every shot in memory.
            fields[5] = i64::try_from(*shot).unwrap_or(i64::MAX);
            fields[6] = match class {
                QualityClass::Full => CLASS_FULL,
                QualityClass::Approximate => CLASS_APPROXIMATE,
            };
        }
        AuVerdict::Fallback => fields[0] = FALLBACK,
        AuVerdict::Rejected(rejection) => {
            fields[0] = REJECTED;
            fields[7] = i64::from(rejection.reason.code());
            fields[8] = rejection
                .decode_index
                .map_or(-1, |index| i64::try_from(index).unwrap_or(i64::MAX));
        }
    }
    fields
}

/// A manifest built in code for the tests here and in `jvm_tests.rs`, after hdr-analyze's own
/// `manifest/tests.rs`: 8.4 bt2100, two shots, one coverage range.
#[cfg(test)]
pub(crate) mod fixture {
    use dovi84_composer::Composer;
    use dovi_rpu_builder::manifest::{
        crc32, Aus, Coverage, L1Policy, ManifestShot, PlaybackManifest, Producer, QualityClass,
        Source, Track,
    };
    use dovi_rpu_builder::{static_84_params, HlgComposer, L1};
    use serde_json::json;

    pub const HEAD: [u8; 32] = [0x11; 32];
    pub const TAIL: [u8; 32] = [0xA0; 32];
    pub const KEY: [u8; 32] = [0x5C; 32];
    pub const CODEC_PRIVATE: [u8; 32] = [0x0F; 32];
    pub const SIZE: i64 = 1000;
    pub const WIDTH: i32 = 3840;
    pub const HEIGHT: i32 = 2160;
    pub const TIME_BASE: (i32, i32) = (1, 1000);

    /// Decode order: IDR(pts 0) P(3) B(1) B(2) EOS(4, no picture) CRA(5) P(7) B(6). Pictures by
    /// pts: 0 1 2 3 5 6 7, so presentation ranks are 0 3 1 2 - 4 6 5.
    pub const PTS: [i64; 8] = [0, 3, 1, 2, 4, 5, 7, 6];
    pub const SIZES: [usize; 8] = [10, 6, 4, 4, 2, 8, 5, 4];
    pub const SHOT_L1: [[u16; 3]; 2] = [[0, 1000, 3000], [10, 1500, 3500]];

    /// AU `index`'s bytes.
    pub fn data(index: usize) -> Vec<u8> {
        vec![index as u8 + 1; SIZES[index]]
    }

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|byte| format!("{byte:02x}")).collect()
    }

    pub fn manifest() -> PlaybackManifest {
        let l1 = |[min, avg, max]: [u16; 3]| L1::new(min, avg, max);
        PlaybackManifest {
            producer: Producer {
                mkvdovi_version: "0.8.0".into(),
                analyzer_version: "0.8.0".into(),
                sidecar_version: 6,
                au_table_version: 1,
                rpu_builder_version: "0.8.0".into(),
                sidecar_sha256: hex(&[0x22; 32]),
            },
            source: Source {
                file_name: "title.mkv".into(),
                size_bytes: SIZE as u64,
                head_sha256: hex(&HEAD),
                tail_sha256: hex(&TAIL),
                lookup_key: hex(&KEY),
            },
            track: Track {
                container: "matroska".into(),
                codec: "hevc".into(),
                width: WIDTH as u32,
                height: HEIGHT as u32,
                codec_private_sha256: hex(&CODEC_PRIVATE),
                time_base: TIME_BASE,
            },
            aus: Aus {
                pts: PTS.to_vec(),
                size: SIZES.iter().map(|&size| size as u32).collect(),
                non_picture: vec![4],
                irap_crc32: vec![(0, crc32(&data(0))), (5, crc32(&data(5)))],
            },
            leading_skipped: 0,
            analysis: json!({
                "luminance_mapping": Composer::Bt2100V1.luminance_mapping(),
                "downscale": 1
            })
            .as_object()
            .unwrap()
            .clone(),
            rpu: static_84_params(HlgComposer::Bt2100),
            l1_policy: L1Policy::Measured,
            shots: vec![
                ManifestShot {
                    start: 0,
                    l1: l1(SHOT_L1[0]),
                },
                ManifestShot {
                    start: 4,
                    l1: l1(SHOT_L1[1]),
                },
            ],
            coverage: vec![Coverage {
                start: 0,
                end: 7,
                class: QualityClass::Full,
            }],
        }
    }

    pub fn json() -> Vec<u8> {
        serde_json::to_vec(&manifest().to_json().unwrap()).unwrap()
    }

    /// The verdict of every AU of a read from the start, in decode order.
    pub fn expected_verdicts() -> [[i64; super::VERDICT_LEN]; 8] {
        let inject = |shot: usize, refresh: bool| {
            let [min, avg, max] = SHOT_L1[shot].map(i64::from);
            [0, min, avg, max, i64::from(refresh), shot as i64, 0, 0, -1]
        };
        [
            inject(0, true),
            inject(0, false),
            inject(0, false),
            inject(0, false),
            [1, 0, 0, 0, 0, 0, 0, 0, -1],
            inject(1, true),
            inject(1, false),
            inject(1, false),
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::fixture::{
        self, data, expected_verdicts, CODEC_PRIVATE, HEAD, HEIGHT, KEY, PTS, SHOT_L1, SIZE, TAIL,
        TIME_BASE, WIDTH,
    };
    use super::*;
    use crate::api::{self, BAD_ARGUMENT, PANIC_HANDLE, UNKNOWN_HANDLE};
    use dovi_rpu_builder::{static_84_params, HlgComposer, L1};
    use serde_json::{json, Value};

    const MANIFEST: i32 = 8;

    fn code<T: std::fmt::Debug>(result: Result<T>) -> i32 {
        result.expect_err("expected an error").code()
    }

    fn parsed(handles: &Handles) -> i64 {
        parse(handles, &fixture::json()).unwrap()
    }

    fn json_with(edit: impl FnOnce(&mut Value)) -> Vec<u8> {
        let mut value = fixture::manifest().to_json().unwrap();
        edit(&mut value);
        serde_json::to_vec(&value).unwrap()
    }

    #[test]
    fn a_parsed_manifest_passes_the_checks_of_its_own_file() {
        let handles = Handles::new();
        let handle = parsed(&handles);
        check_source(&handles, handle, SIZE, &HEAD, &TAIL, &KEY).unwrap();
        check_track(&handles, handle, &CODEC_PRIVATE, WIDTH, HEIGHT, TIME_BASE).unwrap();
        check_composer(&handles, handle, 1).unwrap();
        api::release(&handles, handle).unwrap();
        assert_eq!(handles.len(), 0);
    }

    #[test]
    fn another_file_or_track_is_code_8() {
        let handles = Handles::new();
        let h = parsed(&handles);
        let other = [0x12; 32];
        for (case, result) in [
            (
                "size",
                check_source(&handles, h, SIZE + 1, &HEAD, &TAIL, &KEY),
            ),
            ("head", check_source(&handles, h, SIZE, &other, &TAIL, &KEY)),
            ("tail", check_source(&handles, h, SIZE, &HEAD, &other, &KEY)),
            ("key", check_source(&handles, h, SIZE, &HEAD, &TAIL, &other)),
            (
                "codec private",
                check_track(&handles, h, &other, WIDTH, HEIGHT, TIME_BASE),
            ),
            (
                "width",
                check_track(&handles, h, &CODEC_PRIVATE, 1920, HEIGHT, TIME_BASE),
            ),
            (
                "height",
                check_track(&handles, h, &CODEC_PRIVATE, WIDTH, 1080, TIME_BASE),
            ),
            (
                "time base",
                check_track(&handles, h, &CODEC_PRIVATE, WIDTH, HEIGHT, (1, 1_000_000)),
            ),
            ("preset composer", check_composer(&handles, h, 0)),
            ("canary composer", check_composer(&handles, h, 2)),
        ] {
            assert_eq!(code(result), MANIFEST, "{case}");
        }
        // Lowercase hex: a digest with letters matches only as the crate stores it.
        assert!(fixture::manifest().source.tail_sha256.starts_with("a0a0"));
    }

    #[test]
    fn bad_arguments_are_code_20_before_the_handle_is_looked_up() {
        let handles = Handles::new();
        let unknown = 12_345;
        for (case, result) in [
            (
                "negative size",
                check_source(&handles, unknown, -1, &HEAD, &TAIL, &KEY),
            ),
            (
                "short head",
                check_source(&handles, unknown, SIZE, &HEAD[..31], &TAIL, &KEY),
            ),
            (
                "long tail",
                check_source(&handles, unknown, SIZE, &HEAD, &[0; 33], &KEY),
            ),
            (
                "empty key",
                check_source(&handles, unknown, SIZE, &HEAD, &TAIL, &[]),
            ),
            (
                "short digest",
                check_track(&handles, unknown, &[0; 20], WIDTH, HEIGHT, TIME_BASE),
            ),
            (
                "negative width",
                check_track(&handles, unknown, &CODEC_PRIVATE, -1, HEIGHT, TIME_BASE),
            ),
            (
                "negative height",
                check_track(&handles, unknown, &CODEC_PRIVATE, WIDTH, -1, TIME_BASE),
            ),
            ("composer 3", check_composer(&handles, unknown, 3)),
        ] {
            assert_eq!(code(result), BAD_ARGUMENT, "{case}");
        }
        assert_eq!(
            code(parse(&handles, &vec![b' '; MAX_MANIFEST_BYTES + 1])),
            BAD_ARGUMENT
        );
        assert_eq!(handles.len(), 0);
    }

    #[test]
    fn parse_refusals_are_code_8_and_take_no_handle() {
        let handles = Handles::new();
        let mut not_utf8 = fixture::json();
        not_utf8.insert(1, 0xFF);
        let refused = [
            ("empty", Vec::new()),
            ("not JSON", b"{".to_vec()),
            ("not UTF-8", not_utf8),
            ("version 2", json_with(|v| v["version"] = json!(2))),
            ("format", json_with(|v| v["format"] = json!("other"))),
            ("no AUs", json_with(|v| v["aus"] = json!({}))),
            (
                "composer differs from the analysis",
                json_with(|v| v["analysis"]["luminance_mapping"] = json!("pq")),
            ),
        ];
        for (case, json) in refused {
            assert_eq!(code(parse(&handles, &json)), MANIFEST, "{case}");
        }
        assert_eq!(handles.len(), 0);
        // Unknown keys are ignored within version 1.
        let extra = json_with(|v| v["future"] = json!({"x": 1}));
        assert!(parse(&handles, &extra).is_ok());
    }

    #[test]
    fn a_read_from_the_start_gets_each_shots_l1() {
        let handles = Handles::new();
        let matcher = create_matcher(&handles, parsed(&handles)).unwrap();
        let verdicts: Vec<_> = (0..8)
            .map(|i| next(&handles, matcher, PTS[i], &data(i), false).unwrap())
            .collect();
        assert_eq!(verdicts, expected_verdicts());
        // Past the table's last AU: rejected, reason 6.
        let past = next(&handles, matcher, 8, &[0; 3], false).unwrap();
        assert_eq!(past, [REJECTED, 0, 0, 0, 0, 0, 0, 6, -1]);
    }

    #[test]
    fn after_a_reset_the_matcher_waits_for_an_irap() {
        let handles = Handles::new();
        let matcher = create_matcher(&handles, parsed(&handles)).unwrap();
        for (i, &pts) in PTS.iter().enumerate().take(3) {
            next(&handles, matcher, pts, &data(i), false).unwrap();
        }
        reset(&handles, matcher).unwrap();
        // A seek lands on AU 6: no IRAP yet, so AUs 6 and 7 get the fallback (their pts and size
        // still checked). A second seek lands on the CRA, AU 5, and the shots resume there.
        assert_eq!(
            next(&handles, matcher, PTS[6], &data(6), false).unwrap()[0],
            FALLBACK
        );
        assert_eq!(
            next(&handles, matcher, PTS[7], &data(7), false).unwrap()[0],
            FALLBACK
        );
        reset(&handles, matcher).unwrap();
        for (i, &pts) in PTS.iter().enumerate().skip(5) {
            let verdict = next(&handles, matcher, pts, &data(i), false).unwrap();
            assert_eq!(verdict, expected_verdicts()[i], "AU {i}");
        }
    }

    #[test]
    fn a_mismatch_rejects_for_the_rest_of_the_playback() {
        let handles = Handles::new();
        let manifest = parsed(&handles);
        // Each reason with its code; the decode index where the table has one.
        // (pts, bytes, laced) per AU.
        type Aus<'a> = &'a [(i64, Vec<u8>, bool)];
        let cases: [(&str, Aus, [i64; 2]); 5] = [
            ("unknown pts", &[(99, vec![0; 4], false)], [1, -1]),
            ("size", &[(0, vec![1; 9], false)], [2, 0]),
            ("CRC", &[(0, vec![2; 10], false)], [3, 0]),
            ("order", &[(0, data(0), false), (1, data(2), false)], [4, 1]),
            ("laced", &[(0, data(0), true)], [5, -1]),
        ];
        for (case, aus, [reason, index]) in cases {
            let matcher = create_matcher(&handles, manifest).unwrap();
            let mut last = [0; VERDICT_LEN];
            for (pts, bytes, laced) in aus {
                last = next(&handles, matcher, *pts, bytes, *laced).unwrap();
            }
            assert_eq!(last, [REJECTED, 0, 0, 0, 0, 0, 0, reason, index], "{case}");
            // Sticky across a reset and for an AU that would match.
            reset(&handles, matcher).unwrap();
            assert_eq!(
                next(&handles, matcher, 0, &data(0), false).unwrap(),
                last,
                "{case}"
            );
        }
    }

    #[test]
    fn the_manifests_builder_writes_the_params_rpus() {
        let handles = Handles::new();
        let manifest = parsed(&handles);
        let builder = create_builder(&handles, manifest).unwrap();
        let reference =
            dovi_rpu_builder::RpuBuilder::new(&static_84_params(HlgComposer::Bt2100)).unwrap();
        for [min, avg, max] in SHOT_L1 {
            let nals =
                api::render_shot(&handles, builder, min.into(), avg.into(), max.into()).unwrap();
            let mut shot = reference.shot(L1::new(min, avg, max)).unwrap();
            assert_eq!(nals.scene_refresh, shot.write(true).unwrap());
            assert_eq!(nals.no_refresh, shot.write(false).unwrap());
        }
    }

    #[test]
    fn handles_of_another_kind_are_code_21() {
        let handles = Handles::new();
        let manifest = parsed(&handles);
        let matcher = create_matcher(&handles, manifest).unwrap();
        let builder = create_builder(&handles, manifest).unwrap();
        assert_eq!(
            (manifest, matcher, builder),
            (1, 2, 3),
            "one counter for every kind"
        );
        let [min, avg, max] = SHOT_L1[0].map(i32::from);
        assert_eq!(
            code(api::render_shot(&handles, manifest, min, avg, max)),
            UNKNOWN_HANDLE
        );
        assert_eq!(
            code(api::render_shot(&handles, matcher, min, avg, max)),
            UNKNOWN_HANDLE
        );
        for wrong in [matcher, builder] {
            assert_eq!(
                code(check_source(&handles, wrong, SIZE, &HEAD, &TAIL, &KEY)),
                UNKNOWN_HANDLE
            );
            assert_eq!(
                code(check_track(
                    &handles,
                    wrong,
                    &CODEC_PRIVATE,
                    WIDTH,
                    HEIGHT,
                    TIME_BASE
                )),
                UNKNOWN_HANDLE
            );
            assert_eq!(code(check_composer(&handles, wrong, 1)), UNKNOWN_HANDLE);
            assert_eq!(code(create_matcher(&handles, wrong)), UNKNOWN_HANDLE);
            assert_eq!(code(create_builder(&handles, wrong)), UNKNOWN_HANDLE);
        }
        for wrong in [manifest, builder] {
            assert_eq!(
                code(next(&handles, wrong, 0, &data(0), false)),
                UNKNOWN_HANDLE
            );
            assert_eq!(code(reset(&handles, wrong)), UNKNOWN_HANDLE);
        }
        assert_eq!(handles.len(), 3, "no failed call took a handle");
    }

    #[test]
    fn a_matcher_outlives_its_manifest_and_every_kind_is_released() {
        let handles = Handles::new();
        let manifest = parsed(&handles);
        let matcher = create_matcher(&handles, manifest).unwrap();
        api::release(&handles, manifest).unwrap();
        assert_eq!(code(check_composer(&handles, manifest, 1)), UNKNOWN_HANDLE);
        assert_eq!(
            next(&handles, matcher, 0, &data(0), false).unwrap(),
            expected_verdicts()[0]
        );
        api::release(&handles, matcher).unwrap();
        assert_eq!(
            code(next(&handles, matcher, PTS[1], &data(1), false)),
            UNKNOWN_HANDLE
        );
        assert_eq!(code(reset(&handles, matcher)), UNKNOWN_HANDLE);
        assert_eq!(code(api::release(&handles, matcher)), UNKNOWN_HANDLE);
        assert_eq!(handles.len(), 0);
    }

    #[test]
    fn a_panic_in_the_table_leaves_matchers_usable() {
        let handles = Handles::new();
        let matcher = create_matcher(&handles, parsed(&handles)).unwrap();
        let panicked = crate::ffi::guard(Err(api::PANIC), || {
            next(&handles, PANIC_HANDLE, 0, &[], false).map_err(|e| e.code())
        });
        assert_eq!(panicked, Err(99));
        assert_eq!(
            next(&handles, matcher, 0, &data(0), false).unwrap(),
            expected_verdicts()[0]
        );
    }

    #[test]
    fn the_time_base_is_compared_in_lowest_terms() {
        assert_eq!(reduced((1_000_000, 1_000_000_000)), (1, 1000));
        assert_eq!(reduced((3_000_000, 1_000_000_000)), (3, 1000));
        assert_eq!(reduced((1, 1000)), (1, 1000));
        assert_eq!(reduced((0, 1000)), (0, 1000));
        assert_eq!(reduced((1, -1000)), (1, -1000));
        let handles = Handles::new();
        let h = parsed(&handles);
        // Matroska's TimestampScale over 1e9, as the player reads it.
        check_track(
            &handles,
            h,
            &CODEC_PRIVATE,
            WIDTH,
            HEIGHT,
            (1_000_000, 1_000_000_000),
        )
        .unwrap();
        assert_eq!(
            code(check_track(
                &handles,
                h,
                &CODEC_PRIVATE,
                WIDTH,
                HEIGHT,
                (3_000_000, 1_000_000_000)
            )),
            MANIFEST
        );
    }

    #[test]
    fn verdict_fields_are_fixed() {
        assert_eq!((INJECT, FALLBACK, REJECTED), (0, 1, 2));
        assert_eq!((CLASS_FULL, CLASS_APPROXIMATE), (0, 1));
        assert_eq!(VERDICT_LEN, 9);
        assert_eq!(
            verdict_fields(&AuVerdict::Fallback),
            [1, 0, 0, 0, 0, 0, 0, 0, -1]
        );
        let approximate = AuVerdict::Inject {
            l1: L1::new(7, 8, 9),
            scene_refresh: false,
            shot: 41,
            class: QualityClass::Approximate,
        };
        assert_eq!(verdict_fields(&approximate), [0, 7, 8, 9, 0, 41, 1, 0, -1]);
    }
}
