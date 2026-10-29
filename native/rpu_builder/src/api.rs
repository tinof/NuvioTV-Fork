//! The wrapper's logic, with no JNI types: argument decoding, the builder handle table and the
//! result codes. `ffi.rs` only moves values between Java and these functions.
//!
//! Codes (stable; `DoviRpuBuilder.Code` in Kotlin lists the same numbers):
//!
//! | Code | Meaning |
//! |---|---|
//! | 0 | ok |
//! | 1-7 | `dovi_rpu_builder::Error::code`: 1 composer, 2 block level, 3 value out of range, 4 not HDR10+ (skip the SEI), 5 HDR10+ parse, 6 HDR10+ without the selected peak, 7 internal RPU failure |
//! | 20 | bad argument: null or wrong-length array, unknown enum value, value out of range for the field |
//! | 21 | unknown handle: never created, or already released |
//! | 22 | JNI failure: a Java array could not be allocated or accessed |
//! | 99 | panic, caught at the JNI boundary |

use std::collections::HashMap;
use std::hash::{DefaultHasher, Hash, Hasher};
use std::sync::{Arc, LazyLock, Mutex, MutexGuard, PoisonError};

use dovi_rpu_builder::{
    static_84_params, CmMetadata, CmV40Config, CmVersion, Hdr10PlusFrame, HlgComposer, Level5,
    Level6, PeakSource, Profile, RpuBuilder, RpuParams, SceneKey, DEFAULT_TRIM_TARGETS_NITS, L1,
    STATIC_84_L1,
};

/// `nativeVersion`: this crate's version and the pinned hdr-analyze commit. One literal, so the
/// whole string is in the library's read-only data, where `scripts/verify_native_apk.py` looks
/// for its `nuvio_rpu ` prefix. A test checks the commit against `Cargo.toml`.
pub const VERSION: &str = concat!(
    "nuvio_rpu ",
    env!("CARGO_PKG_VERSION"),
    " dovi_rpu_builder f55c779"
);

pub const OK: i32 = 0;
pub const BAD_ARGUMENT: i32 = 20;
pub const UNKNOWN_HANDLE: i32 = 21;
pub const JNI_FAILURE: i32 = 22;
pub const PANIC: i32 = 99;

/// Why a call failed. [`WrapperError::code`] is the number Kotlin sees; the details only show in
/// `Debug` output (test failures), since the library does not log.
#[derive(Debug)]
#[allow(dead_code)]
pub enum WrapperError {
    /// `dovi_rpu_builder` refused the input (codes 1-7).
    Builder(dovi_rpu_builder::Error),
    BadArgument(String),
    UnknownHandle(i64),
    Jni(String),
}

impl WrapperError {
    pub fn code(&self) -> i32 {
        match self {
            WrapperError::Builder(error) => error.code(),
            WrapperError::BadArgument(_) => BAD_ARGUMENT,
            WrapperError::UnknownHandle(_) => UNKNOWN_HANDLE,
            WrapperError::Jni(_) => JNI_FAILURE,
        }
    }
}

impl From<dovi_rpu_builder::Error> for WrapperError {
    fn from(error: dovi_rpu_builder::Error) -> Self {
        WrapperError::Builder(error)
    }
}

pub type Result<T, E = WrapperError> = std::result::Result<T, E>;

fn bad(message: String) -> WrapperError {
    WrapperError::BadArgument(message)
}

/// 0 preset, 1 bt2100, 2 the canary at its default offset (`HlgComposer::canary`).
fn composer(value: i32) -> Result<HlgComposer> {
    match value {
        0 => Ok(HlgComposer::Preset),
        1 => Ok(HlgComposer::Bt2100),
        2 => Ok(HlgComposer::canary()),
        _ => Err(bad(format!("composer {value}"))),
    }
}

/// 29 or 40.
fn cm_version(value: i32) -> Result<CmVersion> {
    match value {
        29 => Ok(CmVersion::V29),
        40 => Ok(CmVersion::V40),
        _ => Err(bad(format!("CM version {value}"))),
    }
}

/// 0 histogram, 1 histogram99, 2 max-scl, 3 max-scl-luminance.
fn peak_source(value: i32) -> Result<PeakSource> {
    match value {
        0 => Ok(PeakSource::Histogram),
        1 => Ok(PeakSource::Histogram99),
        2 => Ok(PeakSource::MaxScl),
        3 => Ok(PeakSource::MaxSclLuminance),
        _ => Err(bad(format!("peak source {value}"))),
    }
}

fn fields<const N: usize>(name: &str, values: &[i32]) -> Result<[i32; N]> {
    values
        .try_into()
        .map_err(|_| bad(format!("{name}: {} values, expected {N}", values.len())))
}

fn u16_field(name: &str, value: i32) -> Result<u16> {
    u16::try_from(value).map_err(|_| bad(format!("{name} {value} is not 0..=65535")))
}

fn u32_field(name: &str, value: i32) -> Result<u32> {
    u32::try_from(value).map_err(|_| bad(format!("{name} {value} is negative")))
}

/// The `RpuParams` of `nativeCreate`.
///
/// - `profile` 81 or 84; `composer` as [`composer`], read for 8.4 and checked but unused for 8.1.
/// - `cm` 29 or 40; 40 is the crate's default CM v4.0 config and trim targets
///   (`CmMetadata::mkvdovi_default`). `l1_avg_cm` 29 or 40: the L1 average floor.
/// - `level6`: `[max_dml, min_dml, max_cll, max_fall]` as `Level6` holds them (nits; the
///   mastering minimum in 0.0001 nits); `None` is `Level6::mkvdovi_default`.
/// - `source_range`: `[min, max]` PQ codes, 8.1 only; `None` leaves it to the generator.
/// - `level5`: `[left, right, top, bottom]` offsets in pixels; `None` writes no L5.
///
/// Wrong lengths, unknown values and values that do not fit the field (negative, or above
/// 65535 for a 16-bit field) are code 20. What fits the field but not the RPU (an L6 above
/// 10 000 nits, a source range above 4095) is the crate's code 3, from [`create`].
///
/// L9 is `CmV40Config::default()`'s BT.2020; `nativeCreate` calls [`params_with_primaries`].
#[cfg(test)]
pub fn params(
    profile: i32,
    composer_value: i32,
    cm: i32,
    l1_avg_cm: i32,
    level6: Option<&[i32]>,
    source_range: Option<&[i32]>,
    level5: Option<&[i32]>,
) -> Result<RpuParams> {
    params_with_primaries(
        profile,
        composer_value,
        cm,
        l1_avg_cm,
        level6,
        source_range,
        level5,
        BT2020_PRIMARIES,
    )
}

/// L9's `source_primary_index` of `CmV40Config::default()`: BT.2020.
#[cfg(test)]
pub const BT2020_PRIMARIES: i32 = 2;

/// [`params`] with L9's `source_primary_index` (0 P3-D65, 1 BT.709, 2 BT.2020), as `mkvdovi` detects
/// it for the title (DV3.1). Read under CM v4.0 only; any other value is code 20.
#[allow(clippy::too_many_arguments)]
pub fn params_with_primaries(
    profile: i32,
    composer_value: i32,
    cm: i32,
    l1_avg_cm: i32,
    level6: Option<&[i32]>,
    source_range: Option<&[i32]>,
    level5: Option<&[i32]>,
    source_primaries: i32,
) -> Result<RpuParams> {
    let source_primary_index = match source_primaries {
        0..=2 => source_primaries as u8,
        _ => return Err(bad(format!("source primaries {source_primaries}"))),
    };
    let composer = composer(composer_value)?;
    let source_range = source_range.map(source_range_arg).transpose()?;
    let profile = match profile {
        81 => Profile::P81 { source_range },
        84 if source_range.is_some() => {
            return Err(bad("8.4 keeps the preset's source range".into()))
        }
        84 => Profile::P84 { composer },
        _ => return Err(bad(format!("profile {profile}"))),
    };
    let cm = match cm_version(cm)? {
        CmVersion::V29 => CmMetadata::V29,
        CmVersion::V40 => CmMetadata::V40 {
            config: CmV40Config {
                source_primary_index,
                ..CmV40Config::default()
            },
            trim_targets_nits: DEFAULT_TRIM_TARGETS_NITS.to_vec(),
        },
    };
    let level6 = match level6 {
        Some(values) => level6_arg(values)?,
        None => Level6::mkvdovi_default(),
    };
    Ok(RpuParams {
        profile,
        cm,
        level6,
        level5: level5.map(level5_arg).transpose()?,
        l1_avg_pq_cm_version: cm_version(l1_avg_cm)?,
    })
}

fn source_range_arg(values: &[i32]) -> Result<(u16, u16)> {
    let [min, max] = fields::<2>("source range", values)?;
    Ok((u16_field("source min", min)?, u16_field("source max", max)?))
}

fn level6_arg(values: &[i32]) -> Result<Level6> {
    let [max_dml, min_dml, max_cll, max_fall] = fields::<4>("level6", values)?;
    Ok(Level6 {
        max_display_mastering_luminance: u32_field("L6 max_dml", max_dml)?,
        min_display_mastering_luminance: u32_field("L6 min_dml", min_dml)?,
        max_content_light_level: u32_field("L6 max_cll", max_cll)?,
        max_frame_average_light_level: u32_field("L6 max_fall", max_fall)?,
    })
}

fn level5_arg(values: &[i32]) -> Result<Level5> {
    let [left, right, top, bottom] = fields::<4>("level5", values)?;
    Ok(Level5 {
        left: u16_field("L5 left", left)?,
        right: u16_field("L5 right", right)?,
        top: u16_field("L5 top", top)?,
        bottom: u16_field("L5 bottom", bottom)?,
    })
}

/// A builder, shared between threads: `RpuBuilder::shot` takes `&self`.
const _: fn() = || {
    fn send_sync<T: Send + Sync>() {}
    send_sync::<RpuBuilder>();
};

/// Builders by handle. A handle is a number from a counter that starts at 1 and is never reused,
/// so a stale or invented handle finds nothing (code 21) instead of freed memory.
pub struct Builders {
    table: Mutex<Table>,
}

struct Table {
    next: i64,
    builders: HashMap<i64, Arc<RpuBuilder>>,
}

/// Panics inside the lock in test builds, to test that a panic poisons nothing.
#[cfg(test)]
pub(crate) const PANIC_HANDLE: i64 = i64::MIN;

impl Builders {
    pub fn new() -> Self {
        Self {
            table: Mutex::new(Table {
                next: 1,
                builders: HashMap::new(),
            }),
        }
    }

    /// The table even after a panic elsewhere: every change to it is a single insert or remove,
    /// so a poisoned table is still consistent.
    fn lock(&self) -> MutexGuard<'_, Table> {
        self.table.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn insert(&self, builder: RpuBuilder) -> i64 {
        let mut table = self.lock();
        let handle = table.next;
        table.next += 1;
        table.builders.insert(handle, Arc::new(builder));
        handle
    }

    /// The table for a call on `handle`.
    fn lock_for(&self, handle: i64) -> MutexGuard<'_, Table> {
        let table = self.lock();
        #[cfg(test)]
        if handle == PANIC_HANDLE {
            panic!("test panic inside the handle table");
        }
        #[cfg(not(test))]
        let _ = handle;
        table
    }

    fn get(&self, handle: i64) -> Result<Arc<RpuBuilder>> {
        self.lock_for(handle)
            .builders
            .get(&handle)
            .cloned()
            .ok_or(WrapperError::UnknownHandle(handle))
    }

    fn remove(&self, handle: i64) -> Result<()> {
        self.lock_for(handle)
            .builders
            .remove(&handle)
            .map(drop)
            .ok_or(WrapperError::UnknownHandle(handle))
    }

    #[cfg(test)]
    pub(crate) fn len(&self) -> usize {
        self.lock().builders.len()
    }
}

/// The table the JNI functions use.
pub static BUILDERS: LazyLock<Builders> = LazyLock::new(Builders::new);

/// `nativeCreate`: a builder for [`params`], refused by the crate (codes 1-7) when a write would
/// fail.
pub fn create(builders: &Builders, params: &RpuParams) -> Result<i64> {
    Ok(builders.insert(RpuBuilder::new(params)?))
}

/// `nativeCreateStatic84`: `static_84_params(composer)`, for [`STATIC_84_L1`].
pub fn create_static_84(builders: &Builders, composer_value: i32) -> Result<i64> {
    create(builders, &static_84_params(composer(composer_value)?))
}

/// `nativeStatic84L1`: `[min, avg, max]`.
pub fn static_84_l1() -> [i32; 3] {
    l1_fields(STATIC_84_L1)
}

fn l1_fields(l1: L1) -> [i32; 3] {
    [l1.min_pq, l1.avg_pq, l1.max_pq].map(i32::from)
}

/// Both RPU NALs of one shot (the `0x7C01` header and the escaped payload, no start code).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ShotNals {
    /// `scene_refresh_flag` 1: the shot's first AU.
    pub scene_refresh: Vec<u8>,
    /// `scene_refresh_flag` 0: every other AU of the shot.
    pub no_refresh: Vec<u8>,
}

/// `nativeRenderShot`: the shot's L1 (16-bit codes, clamped by the crate as `dovi_tool` clamps
/// them) written once with each scene-refresh value.
pub fn render_shot(
    builders: &Builders,
    handle: i64,
    min_pq: i32,
    avg_pq: i32,
    max_pq: i32,
) -> Result<ShotNals> {
    let l1 = L1::new(
        u16_field("L1 min", min_pq)?,
        u16_field("L1 avg", avg_pq)?,
        u16_field("L1 max", max_pq)?,
    );
    // The builder is cloned out of the table, so the lock is not held while writing.
    let builder = builders.get(handle)?;
    let mut shot = builder.shot(l1)?;
    Ok(ShotNals {
        scene_refresh: shot.write(true)?,
        no_refresh: shot.write(false)?,
    })
}

/// `nativeHdr10PlusL1`: `[min, avg, max]` of one HDR10+ payload (the T.35 RBSP from the country
/// code `0xB5`, as hdr-analyze `docs/RPU_BUILDER.md` states), with `cm`'s average floor. Code 4
/// means the SEI is not HDR10+ and is skipped.
pub fn hdr10plus_l1(payload: &[u8], peak: i32, cm: i32) -> Result<[i32; 3]> {
    let l1 = dovi_rpu_builder::hdr10plus_l1(payload, peak_source(peak)?, cm_version(cm)?)?;
    Ok(l1_fields(l1))
}

/// `nativeHdr10PlusFrame`: what `nativeHdr10PlusL1` gives, `[min, avg, max]`, followed by four ints
/// that identify the payload's `SceneKey` (two 64-bit hashes, high half first). `dovi_tool`'s scene
/// rule compares keys (hdr-analyze `docs/RPU_BUILDER.md`, "Scenes"); the fork compares them between
/// AUs in presentation order (DV3.1, `docs/dv-conversion/DESIGN.md` 2.4), which `Hdr10PlusHold`,
/// fed in one order, cannot do. Equal keys give equal digests; a collision of both hashes is the
/// only way unequal keys compare equal, and it would cost one `scene_refresh_flag`, never an L1.
pub fn hdr10plus_frame(payload: &[u8], peak: i32, cm: i32) -> Result<[i32; 7]> {
    let (peak, cm) = (peak_source(peak)?, cm_version(cm)?);
    let frame = Hdr10PlusFrame::parse(payload)?;
    let [min, avg, max] = l1_fields(frame.l1(peak, cm)?);
    let [k0, k1, k2, k3] = key_digest(&frame.scene_key());
    Ok([min, avg, max, k0, k1, k2, k3])
}

fn key_digest(key: &SceneKey) -> [i32; 4] {
    // `DefaultHasher::new` has fixed keys, so a digest is stable within one library build, which
    // is all a playback compares.
    let half = |seed: u8| {
        let mut hasher = DefaultHasher::new();
        seed.hash(&mut hasher);
        key.hash(&mut hasher);
        hasher.finish()
    };
    let (a, b) = (half(0), half(1));
    [(a >> 32) as i32, a as i32, (b >> 32) as i32, b as i32]
}

/// `nativeSourceRange81`: `[min, max]` PQ codes of `source_range_81` for the mastering display
/// (nits), as `mkvdovi` derives the 8.1 source range; `[-1, -1]` when the values are not a
/// plausible mastering display and the generator's L6 lookup stays in charge (`P81(null)`).
pub fn source_range_81(min_nits: f64, max_nits: f64) -> [i32; 2] {
    dovi_rpu_builder::source_range_81(min_nits, max_nits)
        .map_or([-1, -1], |(min, max)| [i32::from(min), i32::from(max)])
}

/// `nativeRelease`.
pub fn release(builders: &Builders, handle: i64) -> Result<()> {
    builders.remove(handle)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn code<T: std::fmt::Debug>(result: Result<T>) -> i32 {
        result.expect_err("expected an error").code()
    }

    fn static_84(composer: i32) -> RpuParams {
        params(84, composer, 40, 29, None, None, None).unwrap()
    }

    #[test]
    fn the_version_names_the_pinned_commit() {
        let manifest = include_str!("../Cargo.toml");
        let rev = VERSION.rsplit(' ').next().unwrap();
        assert_eq!(rev.len(), 7);
        assert!(
            manifest.contains(&format!("rev = \"{rev}")),
            "VERSION names {rev}, Cargo.toml pins another commit"
        );
        assert!(VERSION.starts_with(concat!("nuvio_rpu ", env!("CARGO_PKG_VERSION"), " ")));
    }

    #[test]
    fn create_equals_static_84_params_for_every_composer() {
        for composer in 0..=2 {
            assert_eq!(
                static_84(composer),
                static_84_params(super::composer(composer).unwrap())
            );
        }
        assert_eq!(static_84_l1(), [0, 1283, 3079]);
    }

    #[test]
    fn create_arguments_map_to_the_params() {
        let p = params(
            81,
            0,
            29,
            40,
            Some(&[1000, 1, 997, 91]),
            Some(&[7, 3079]),
            Some(&[0, 4, 140, 136]),
        )
        .unwrap();
        assert_eq!(
            p,
            RpuParams {
                profile: Profile::P81 {
                    source_range: Some((7, 3079)),
                },
                cm: CmMetadata::V29,
                level6: Level6 {
                    max_display_mastering_luminance: 1000,
                    min_display_mastering_luminance: 1,
                    max_content_light_level: 997,
                    max_frame_average_light_level: 91,
                },
                level5: Some(Level5 {
                    left: 0,
                    right: 4,
                    top: 140,
                    bottom: 136,
                }),
                l1_avg_pq_cm_version: CmVersion::V40,
            }
        );
        let defaults = params(81, 0, 40, 40, None, None, None).unwrap();
        assert_eq!(defaults.level6, Level6::mkvdovi_default());
        assert_eq!(defaults.cm, CmMetadata::mkvdovi_default());
        assert_eq!(defaults.level5, None);
        assert_eq!(defaults.profile, Profile::P81 { source_range: None });
    }

    #[test]
    fn bad_arguments_are_code_20() {
        let refused: [(&str, Result<RpuParams>); 14] = [
            ("profile 82", params(82, 1, 40, 29, None, None, None)),
            ("composer 3", params(84, 3, 40, 29, None, None, None)),
            (
                "composer -1 on 8.1",
                params(81, -1, 40, 29, None, None, None),
            ),
            ("CM 30", params(84, 1, 30, 29, None, None, None)),
            ("avg CM 41", params(84, 1, 40, 41, None, None, None)),
            (
                "L6 of 3",
                params(81, 0, 40, 40, Some(&[1, 2, 3]), None, None),
            ),
            (
                "L6 negative",
                params(81, 0, 40, 40, Some(&[1000, -1, 1000, 400]), None, None),
            ),
            ("range of 1", params(81, 0, 40, 40, None, Some(&[7]), None)),
            (
                "range negative",
                params(81, 0, 40, 40, None, Some(&[-1, 3079]), None),
            ),
            (
                "range 65536",
                params(81, 0, 40, 40, None, Some(&[0, 65536]), None),
            ),
            (
                "range on 8.4",
                params(84, 1, 40, 29, None, Some(&[62, 3079]), None),
            ),
            (
                "L5 of 5",
                params(81, 0, 40, 40, None, None, Some(&[0, 0, 0, 0, 0])),
            ),
            (
                "L5 negative",
                params(81, 0, 40, 40, None, None, Some(&[0, 0, -2, 0])),
            ),
            (
                "L5 65536",
                params(81, 0, 40, 40, None, None, Some(&[0, 0, 65536, 0])),
            ),
        ];
        for (case, result) in refused {
            assert_eq!(code(result), BAD_ARGUMENT, "{case}");
        }
        assert_eq!(code(create_static_84(&Builders::new(), 3)), BAD_ARGUMENT);
        assert_eq!(code(hdr10plus_l1(&[0xB5], 4, 40)), BAD_ARGUMENT);
        assert_eq!(code(hdr10plus_l1(&[0xB5], 0, 30)), BAD_ARGUMENT);
        let builders = Builders::new();
        let handle = create_static_84(&builders, 1).unwrap();
        for (min, avg, max) in [(-1, 1283, 3079), (0, 65536, 3079), (0, 1283, i32::MAX)] {
            assert_eq!(
                code(render_shot(&builders, handle, min, avg, max)),
                BAD_ARGUMENT
            );
        }
    }

    #[test]
    fn builder_refusals_keep_the_crates_codes() {
        let builders = Builders::new();
        // Fits u32 and u16, not the RPU: the crate's OutOfRange.
        let l6 = params(81, 0, 40, 40, Some(&[10_001, 50, 1000, 400]), None, None).unwrap();
        assert_eq!(code(create(&builders, &l6)), 3);
        let range = params(81, 0, 40, 40, None, Some(&[7, 4096]), None).unwrap();
        assert_eq!(code(create(&builders, &range)), 3);
        let inverted = params(81, 0, 40, 40, None, Some(&[3079, 62]), None).unwrap();
        assert_eq!(code(create(&builders, &inverted)), 3);
        let l5 = params(81, 0, 40, 40, None, None, Some(&[8192, 0, 0, 0])).unwrap();
        assert_eq!(code(create(&builders, &l5)), 3);
        assert_eq!(builders.len(), 0, "a refused builder takes no handle");
        // Not HDR10+ (wrong country code, or too short) is 4; a broken HDR10+ payload is 5.
        assert_eq!(
            code(hdr10plus_l1(&[0x26, 0, 0x3C, 0, 1, 4, 1, 0], 0, 40)),
            4
        );
        assert_eq!(code(hdr10plus_l1(&[0xB5], 0, 40)), 4);
        assert_eq!(code(hdr10plus_l1(&[0xB5, 0, 0x3C, 0, 1, 4, 1], 0, 40)), 5);
        // nativeHdr10PlusFrame refuses the same way, arguments first.
        assert_eq!(code(hdr10plus_frame(&[0xB5], 4, 40)), BAD_ARGUMENT);
        assert_eq!(code(hdr10plus_frame(&[0xB5], 0, 30)), BAD_ARGUMENT);
        assert_eq!(code(hdr10plus_frame(&[0xB5], 0, 40)), 4);
        assert_eq!(
            code(hdr10plus_frame(&[0xB5, 0, 0x3C, 0, 1, 4, 1], 0, 40)),
            5
        );
    }

    #[test]
    fn l9_primaries_reach_the_cm_v40_config() {
        let default = params(81, 0, 40, 40, None, None, None).unwrap();
        assert_eq!(default.cm, CmMetadata::mkvdovi_default());
        let p3 = params_with_primaries(81, 0, 40, 40, None, None, None, 0).unwrap();
        match &p3.cm {
            CmMetadata::V40 {
                config,
                trim_targets_nits,
            } => {
                assert_eq!(config.source_primary_index, 0);
                assert_eq!((config.content_type, config.reference_mode), (1, false));
                assert_eq!(*trim_targets_nits, DEFAULT_TRIM_TARGETS_NITS.to_vec());
            }
            other => panic!("{other:?}"),
        }
        // A P3 RPU differs from the BT.2020 one only in L9.
        let builders = Builders::new();
        let [a, b] = [default, p3].map(|p| {
            let handle = create(&builders, &p).unwrap();
            render_shot(&builders, handle, 0, 1229, 3079).unwrap()
        });
        assert_ne!(a.no_refresh, b.no_refresh);
        for value in [-1, 3] {
            assert_eq!(
                code(params_with_primaries(
                    81, 0, 40, 40, None, None, None, value
                )),
                BAD_ARGUMENT
            );
        }
    }

    #[test]
    fn the_81_source_range_is_mkvdovis() {
        // hdr10plus.*.extra.json: 0.0001..1000 nits.
        assert_eq!(source_range_81(0.0001, 1000.0), [7, 3079]);
        assert_eq!(
            source_range_81(0.005, 4000.0),
            dovi_rpu_builder::source_range_81(0.005, 4000.0)
                .map(|(a, b)| [i32::from(a), i32::from(b)])
                .unwrap()
        );
        // Not a plausible mastering display: the generator's lookup decides.
        for (min, max) in [
            (0.0, 50.0),
            (50.0, 1000.0),
            (0.0, f64::NAN),
            (0.0, 20_000.0),
        ] {
            assert_eq!(source_range_81(min, max), [-1, -1], "{min} {max}");
        }
    }

    #[test]
    fn error_codes_are_fixed() {
        let errors = [
            WrapperError::Builder(dovi_rpu_builder::Error::InvalidComposer(String::new())),
            WrapperError::Builder(dovi_rpu_builder::Error::Rpu(String::new())),
            WrapperError::BadArgument(String::new()),
            WrapperError::UnknownHandle(7),
            WrapperError::Jni(String::new()),
        ];
        let codes: Vec<i32> = errors.iter().map(WrapperError::code).collect();
        assert_eq!(codes, [1, 7, 20, 21, 22]);
        assert_eq!((OK, PANIC), (0, 99));
    }

    #[test]
    fn handles_are_created_rendered_and_released() {
        let builders = Builders::new();
        let first = create_static_84(&builders, 1).unwrap();
        let second = create_static_84(&builders, 2).unwrap();
        assert_eq!((first, second), (1, 2));
        assert_eq!(builders.len(), 2);

        let [min, avg, max] = static_84_l1();
        let shot = render_shot(&builders, first, min, avg, max).unwrap();
        assert_ne!(shot.scene_refresh, shot.no_refresh);
        assert_eq!(shot.scene_refresh[..2], [0x7C, 0x01]);
        assert_eq!(shot.no_refresh[..2], [0x7C, 0x01]);
        // The canary differs from bt2100 only in its composer.
        let canary = render_shot(&builders, second, min, avg, max).unwrap();
        assert_ne!(canary.scene_refresh, shot.scene_refresh);

        release(&builders, first).unwrap();
        assert_eq!(builders.len(), 1);
        // Stale, never issued, zero and negative handles.
        for handle in [first, 3, 0, -1] {
            assert_eq!(code(render_shot(&builders, handle, min, avg, max)), 21);
            assert_eq!(code(release(&builders, handle)), 21);
        }
        // A new builder never reuses a released handle.
        assert_eq!(create_static_84(&builders, 1).unwrap(), 3);
        assert_eq!(
            render_shot(&builders, 3, min, avg, max).unwrap(),
            shot,
            "same params, same bytes"
        );
    }

    #[test]
    fn the_global_table_works_across_threads() {
        let handle = create_static_84(&BUILDERS, 1).unwrap();
        let [min, avg, max] = static_84_l1();
        let expected = render_shot(&BUILDERS, handle, min, avg, max).unwrap();
        let threads: Vec<_> = (0..4)
            .map(|_| std::thread::spawn(move || render_shot(&BUILDERS, handle, min, avg, max)))
            .collect();
        for thread in threads {
            assert_eq!(thread.join().unwrap().unwrap(), expected);
        }
        release(&BUILDERS, handle).unwrap();
        assert_eq!(code(release(&BUILDERS, handle)), 21);
    }
}
