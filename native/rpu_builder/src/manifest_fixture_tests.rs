//! The HDR10 → 8.1 playback manifest fixture of ROADMAP DV4.10 (`app/src/test/resources/dv_manifest/
//! hdr10_81/`, see its README) through the real parser, matcher and builder at this crate's pin:
//! every AU of the clip, as Matroska stores it, gets an RPU equal to `mkvdovi`'s 8.1 output for the
//! same clip. The verdicts go to `hdr10_81.verdicts.txt`, which the JVM extractor test
//! (`MatroskaRpuInjectionTest`) replays without the library; `NUVIO_WRITE_FIXTURES=1` rewrites it.

use std::path::PathBuf;

use serde_json::Value;

use crate::api::{self, Handles};
use crate::manifest::{self, FALLBACK, INJECT, REJECTED, VERDICT_LEN};

const NAME: &str = "hdr10_81";
const MANIFEST: i32 = 8;
const VERDICTS_HEADER: &str = "# pts size kind min avg max refresh shot";

fn path(suffix: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../app/src/test/resources/dv_manifest")
        .join(NAME)
        .join(format!("{NAME}.{suffix}"))
}

fn read(suffix: &str) -> Vec<u8> {
    let path = path(suffix);
    std::fs::read(&path).unwrap_or_else(|error| panic!("{}: {error}", path.display()))
}

fn manifest_json() -> Value {
    serde_json::from_slice(&read("dvmanifest.json")).unwrap()
}

/// The records of an `RPU.bin`, in its order: each NAL after its start code, without the 2-byte
/// header. A NAL never holds `00 00 00 01` (emulation prevention), so the start codes split it.
fn records(bin: &[u8]) -> Vec<&[u8]> {
    const START: [u8; 4] = [0, 0, 0, 1];
    let mut starts = Vec::new();
    let mut i = 0;
    while i + START.len() <= bin.len() {
        if bin[i..i + START.len()] == START {
            starts.push(i + START.len());
            i += START.len();
        } else {
            i += 1;
        }
    }
    assert_eq!(
        starts.first(),
        Some(&START.len()),
        "RPU.bin starts with a start code"
    );
    starts
        .iter()
        .enumerate()
        .map(|(n, &start)| {
            let end = starts
                .get(n + 1)
                .map_or(bin.len(), |&next| next - START.len());
            &bin[start..end]
        })
        .collect()
}

/// `(pts, size)` of every AU in decode order, from the manifest's `aus`.
fn au_table(manifest: &Value) -> Vec<(i64, usize)> {
    let aus = &manifest["aus"];
    let mut pts = aus["pts_first"].as_i64().unwrap();
    let deltas = aus["pts_delta"].as_array().unwrap();
    let sizes = aus["size"].as_array().unwrap();
    assert_eq!(deltas.len() + 1, sizes.len());
    let mut table = Vec::with_capacity(sizes.len());
    for (index, size) in sizes.iter().enumerate() {
        if index > 0 {
            pts += deltas[index - 1].as_i64().unwrap();
        }
        table.push((pts, size.as_u64().unwrap() as usize));
    }
    table
}

/// The AUs of `aus.bin`, split by the table's sizes.
fn au_bytes<'a>(bin: &'a [u8], table: &[(i64, usize)]) -> Vec<&'a [u8]> {
    let mut offset = 0;
    let aus = table
        .iter()
        .map(|&(_, size)| {
            let au = &bin[offset..offset + size];
            offset += size;
            au
        })
        .collect();
    assert_eq!(offset, bin.len(), "aus.bin holds exactly the table's AUs");
    aus
}

/// One verdict as a `verdicts.txt` line.
fn line(pts: i64, size: usize, verdict: &[i64; VERDICT_LEN]) -> String {
    let kind = match verdict[0] {
        INJECT => "inject",
        FALLBACK => "fallback",
        REJECTED => "rejected",
        other => panic!("verdict kind {other}"),
    };
    let [_, min, avg, max, refresh, shot, ..] = *verdict;
    format!("{pts} {size} {kind} {min} {avg} {max} {refresh} {shot}")
}

#[test]
fn manifest_fixture_is_an_8_1_manifest_for_a_pq_track_only() {
    let handles = Handles::new();
    let handle = manifest::parse(&handles, &read("dvmanifest.json")).unwrap();
    manifest::check_profile_81(&handles, handle).unwrap();
    for composer in 0..=2 {
        let refused = manifest::check_composer(&handles, handle, composer).unwrap_err();
        assert_eq!(refused.code(), MANIFEST, "composer {composer}");
    }
    let json = manifest_json();
    assert_eq!(json["rpu"]["profile"], "8.1");
    assert_eq!(json["analysis"]["luminance_mapping"], "pq");
    api::release(&handles, handle).unwrap();
}

#[test]
fn manifest_fixture_every_au_gets_mkvdovis_rpu() {
    let json = manifest_json();
    let table = au_table(&json);
    let bin = read("aus.bin");
    let aus = au_bytes(&bin, &table);
    let rpu_bin = read("mkvdovi.RPU.bin");
    let records = records(&rpu_bin);
    assert_eq!(records.len(), table.len(), "one mkvdovi RPU per picture");
    let mut by_pts: Vec<i64> = table.iter().map(|&(pts, _)| pts).collect();
    by_pts.sort_unstable();

    let handles = Handles::new();
    let handle = manifest::parse(&handles, &read("dvmanifest.json")).unwrap();
    let matcher = manifest::create_matcher(&handles, handle).unwrap();
    let builder = manifest::create_builder(&handles, handle).unwrap();
    let mut lines = vec![VERDICTS_HEADER.to_string()];
    for (index, (&(pts, size), au)) in table.iter().zip(&aus).enumerate() {
        let verdict = manifest::next(&handles, matcher, pts, au, false).unwrap();
        lines.push(line(pts, size, &verdict));
        assert_eq!(verdict[0], INJECT, "AU {index}: {verdict:?}");
        let [_, min, avg, max, refresh, ..] = verdict.map(|value| value as i32);
        let nals = api::render_shot(&handles, builder, min, avg, max).unwrap();
        let written = if refresh == 1 {
            &nals.scene_refresh
        } else {
            &nals.no_refresh
        };
        let rank = by_pts.binary_search(&pts).unwrap();
        let mut expected = vec![0x7C, 0x01];
        expected.extend_from_slice(records[rank]);
        assert!(
            *written == expected,
            "AU {index} (pts {pts}, picture {rank}) differs from mkvdovi's RPU"
        );
    }
    let text = lines.join("\n") + "\n";
    if std::env::var("NUVIO_WRITE_FIXTURES").as_deref() == Ok("1") {
        std::fs::write(path("verdicts.txt"), &text).unwrap();
    }
    let committed = String::from_utf8(read("verdicts.txt")).unwrap();
    assert_eq!(
        committed, text,
        "verdicts.txt is not the matcher's; NUVIO_WRITE_FIXTURES=1 rewrites it"
    );
    for handle in [matcher, builder, handle] {
        api::release(&handles, handle).unwrap();
    }
    assert_eq!(handles.len(), 0);
}

#[test]
fn manifest_fixture_a_changed_irap_is_rejected() {
    // The JVM test's stand-in rejects an AU whose bytes differ from aus.bin; the library does too.
    let json = manifest_json();
    let table = au_table(&json);
    let bin = read("aus.bin");
    let mut first = au_bytes(&bin, &table)[0].to_vec();
    let last = first.len() - 1;
    first[last] ^= 0x01;
    let handles = Handles::new();
    let handle = manifest::parse(&handles, &read("dvmanifest.json")).unwrap();
    let matcher = manifest::create_matcher(&handles, handle).unwrap();
    let verdict = manifest::next(&handles, matcher, table[0].0, &first, false).unwrap();
    assert_eq!(
        verdict,
        [REJECTED, 0, 0, 0, 0, 0, 0, 3, 0],
        "a CRC mismatch on AU 0"
    );
}
