//! The wrapper against hdr-analyze's committed `dovi_tool` 2.3.4 output (`tests-fixtures/`, see
//! its README). Every case goes through the `api` functions the JNI layer calls, with the
//! arguments Kotlin passes, and must equal the fixture byte for byte.

use std::path::PathBuf;

use dovi_rpu_builder::{ExtraShot, ExtraShots, RpuParams, L1};
use serde_json::Value;

use crate::api::{self, Builders};

fn fixture(name: &str) -> Vec<u8> {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests-fixtures")
        .join(name);
    std::fs::read(&path).unwrap_or_else(|error| panic!("{}: {error}", path.display()))
}

fn json(name: &str) -> Value {
    serde_json::from_slice(&fixture(name)).unwrap()
}

/// One AU in the `RPU.bin` layout: a start code, then the NAL without its 2-byte header.
fn push_record(out: &mut Vec<u8>, nal: &[u8]) {
    assert_eq!(nal[..2], [0x7C, 0x01]);
    out.extend_from_slice(&[0, 0, 0, 1]);
    out.extend_from_slice(&nal[2..]);
}

/// `(duration, [min, avg, max])` per shot, from an `extra.json`.
fn shots(extra: &Value) -> Vec<(u64, [i32; 3])> {
    extra["shots"]
        .as_array()
        .unwrap()
        .iter()
        .map(|shot| {
            let l1 = &shot["metadata_blocks"][0]["Level1"];
            let pq = |key: &str| l1[key].as_i64().unwrap() as i32;
            (
                shot["duration"].as_u64().unwrap(),
                [pq("min_pq"), pq("avg_pq"), pq("max_pq")],
            )
        })
        .collect()
}

/// The `extra.json` these params and shots stand for, to compare with the committed one.
fn extra_json(params: &RpuParams, shots: &[(u64, [i32; 3])]) -> Value {
    let mut start = 0;
    let shots = shots
        .iter()
        .map(|&(duration, [min, avg, max])| {
            let shot = ExtraShot {
                start,
                duration,
                l1: L1::new(min as u16, avg as u16, max as u16),
            };
            start += duration;
            shot
        })
        .collect();
    params.extra_json(Some(&ExtraShots {
        length: start,
        shots,
    }))
}

/// Each shot rendered once through `render_shot`; its first AU takes the scene-refresh NAL.
fn render(builders: &Builders, handle: i64, shots: &[(u64, [i32; 3])]) -> Vec<u8> {
    let mut out = Vec::new();
    for &(duration, [min, avg, max]) in shots {
        let nals = api::render_shot(builders, handle, min, avg, max).unwrap();
        for frame in 0..duration {
            let nal = if frame == 0 {
                &nals.scene_refresh
            } else {
                &nals.no_refresh
            };
            push_record(&mut out, nal);
        }
    }
    out
}

#[test]
fn static_84_equals_dovi_tool_for_bt2100_and_canary() {
    let builders = Builders::new();
    for (composer, name) in [(1, "static_84_bt2100"), (2, "static_84_canary")] {
        let extra = json(&format!("{name}.extra.json"));
        let shots = shots(&extra);
        // The static L1 is the fixture's L1.
        assert_eq!(shots, [(4, api::static_84_l1())], "{name}");

        // nativeCreateStatic84.
        let handle = api::create_static_84(&builders, composer).unwrap();
        let expected = fixture(&format!("{name}.RPU.bin"));
        assert!(
            render(&builders, handle, &shots) == expected,
            "{name}: nativeCreateStatic84 differs from dovi_tool"
        );
        // nativeCreate with the same values.
        let params = api::params(84, composer, 40, 29, None, None, None).unwrap();
        assert_eq!(extra_json(&params, &shots), extra, "{name}");
        let same = api::create(&builders, &params).unwrap();
        assert!(
            render(&builders, same, &shots) == expected,
            "{name}: nativeCreate differs from dovi_tool"
        );
        api::release(&builders, handle).unwrap();
        api::release(&builders, same).unwrap();
    }
    assert_eq!(builders.len(), 0);
}

#[test]
fn static_84_records_0_and_1_are_the_two_variants() {
    // Record 0 (scene refresh) and record 1 (no refresh), as render_shot returns them.
    let builders = Builders::new();
    let handle = api::create_static_84(&builders, 1).unwrap();
    let [min, avg, max] = api::static_84_l1();
    let nals = api::render_shot(&builders, handle, min, avg, max).unwrap();
    let mut both = Vec::new();
    push_record(&mut both, &nals.scene_refresh);
    push_record(&mut both, &nals.no_refresh);
    assert_eq!(both, fixture("static_84_bt2100.RPU.bin")[..both.len()]);
    assert_ne!(nals.scene_refresh, nals.no_refresh);
}

/// The 8.1 shot cases `nativeCreate` can express: the CM v4.0 config and trim targets are the
/// crate's defaults in each.
#[test]
fn profile_81_cases_equal_dovi_tool() {
    type Args = (
        i32,
        i32,
        Option<[i32; 4]>,
        Option<[i32; 2]>,
        Option<[i32; 4]>,
    );
    let cases: [(&str, Args); 3] = [
        // cm, l1 avg cm, level6, source range, level5
        (
            "static_81",
            (40, 40, Some([1000, 50, 1000, 400]), Some([62, 3079]), None),
        ),
        (
            "p81_v29_shots",
            (29, 29, None, None, Some([0, 0, 276, 276])),
        ),
        (
            "p81_v40_shots",
            (
                40,
                29,
                Some([1000, 1, 997, 91]),
                Some([7, 3079]),
                Some([0, 4, 140, 136]),
            ),
        ),
    ];
    let builders = Builders::new();
    for (name, (cm, avg_cm, level6, range, level5)) in cases {
        let extra = json(&format!("{name}.extra.json"));
        let shots = shots(&extra);
        let params = api::params(
            81,
            0,
            cm,
            avg_cm,
            level6.as_ref().map(|v| &v[..]),
            range.as_ref().map(|v| &v[..]),
            level5.as_ref().map(|v| &v[..]),
        )
        .unwrap();
        assert_eq!(extra_json(&params, &shots), extra, "{name}");
        let handle = api::create(&builders, &params).unwrap();
        assert!(
            render(&builders, handle, &shots) == fixture(&format!("{name}.RPU.bin")),
            "{name} differs from dovi_tool"
        );
    }
}

fn payloads(sequence: &str) -> Vec<Vec<u8>> {
    let data = fixture(&format!("{sequence}.payloads"));
    let mut rest = data.as_slice();
    let mut payloads = Vec::new();
    while !rest.is_empty() {
        let length = usize::from(u16::from_be_bytes([rest[0], rest[1]]));
        payloads.push(rest[2..2 + length].to_vec());
        rest = &rest[2 + length..];
    }
    payloads
}

const PEAK_SOURCES: [(i32, &str); 4] = [
    (0, "histogram"),
    (1, "histogram99"),
    (2, "max-scl"),
    (3, "max-scl-luminance"),
];

/// HDR10+ → 8.1 through nativeHdr10PlusL1 and nativeRenderShot: L1 from each scene's first
/// payload (scene starts from `hdr10plus_tool`'s JSON), held for the scene, rendered with the
/// params mkvdovi writes for HDR10+ (L6 1000 / 0.0001 nits, MaxCLL 1000, MaxFALL 400, source
/// range 7..3079, the average floor of the CM version).
#[test]
fn hdr10plus_sequences_equal_dovi_tool_for_every_peak_source() {
    let builders = Builders::new();
    let mut compared = 0;
    for sequence in ["profile_a", "profile_b"] {
        let scene_starts: Vec<usize> = json(&format!("{sequence}.json"))["SceneInfoSummary"]
            ["SceneFirstFrameIndex"]
            .as_array()
            .unwrap()
            .iter()
            .map(|index| index.as_u64().unwrap() as usize)
            .collect();
        let payloads = payloads(sequence);
        for (cm, cm_name) in [(29, "v29"), (40, "v40")] {
            let params = api::params(
                81,
                0,
                cm,
                cm,
                Some(&[1000, 1, 1000, 400]),
                Some(&[7, 3079]),
                None,
            )
            .unwrap();
            assert_eq!(
                params.extra_json(None),
                json(&format!("hdr10plus.{cm_name}.extra.json")),
                "{cm_name}"
            );
            let handle = api::create(&builders, &params).unwrap();
            for (peak, peak_name) in PEAK_SOURCES {
                let mut ours = Vec::new();
                let mut held = None;
                for (index, payload) in payloads.iter().enumerate() {
                    if scene_starts.contains(&index) {
                        let [min, avg, max] = api::hdr10plus_l1(payload, peak, cm).unwrap();
                        let nals = api::render_shot(&builders, handle, min, avg, max).unwrap();
                        push_record(&mut ours, &nals.scene_refresh);
                        held = Some(nals);
                    } else {
                        push_record(&mut ours, &held.as_ref().unwrap().no_refresh);
                    }
                }
                let name = format!("{sequence}.{peak_name}.{cm_name}.RPU.bin");
                assert!(ours == fixture(&name), "{name} differs from dovi_tool");
                compared += 1;
            }
            api::release(&builders, handle).unwrap();
        }
    }
    assert_eq!(compared, 16);
}

#[test]
fn hdr10plus_l1_follows_the_peak_source() {
    // profile_a's first payload: histogram and histogram99 differ (its 98% percentile is the
    // highest); for profile_b they agree, and so do dovi_tool's files.
    let a = &payloads("profile_a")[0];
    assert_ne!(
        api::hdr10plus_l1(a, 0, 40).unwrap(),
        api::hdr10plus_l1(a, 1, 40).unwrap()
    );
    let b = &payloads("profile_b")[0];
    assert_eq!(
        api::hdr10plus_l1(b, 0, 40).unwrap(),
        api::hdr10plus_l1(b, 1, 40).unwrap()
    );
    assert_eq!(
        fixture("profile_b.histogram.v40.RPU.bin"),
        fixture("profile_b.histogram99.v40.RPU.bin")
    );
    // Min is always 0; the average floor is the CM version's (819 or 1229).
    for payload in payloads("profile_a").iter().chain(&payloads("profile_b")) {
        for (cm, floor) in [(29, 819), (40, 1229)] {
            let [min, avg, max] = api::hdr10plus_l1(payload, 0, cm).unwrap();
            assert_eq!(min, 0);
            assert!(avg >= floor && avg < max && (2081..=4095).contains(&max));
        }
    }
}
