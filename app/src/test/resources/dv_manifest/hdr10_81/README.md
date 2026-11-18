# HDR10 → 8.1 playback manifest fixture (ROADMAP DV4.10)

A 48-frame PQ clip and what `mkvdovi` makes of it: its playback manifest and its own Profile 8.1 RPUs.
`gen.sh` makes the first four files in one run; `NUVIO_WRITE_FIXTURES=1 cargo test manifest_fixture`
in `native/rpu_builder` then writes the fifth.

| File | What it is |
|---|---|
| `hdr10_81.mkv` | 256x144 at 24 fps, x265 `ultrafast`, muxed by `mkvmerge --deterministic 1` (15 078 bytes) |
| `hdr10_81.dvmanifest.json` | `mkvdovi manifest hdr10_81.mkv`: version 1, Profile 8.1, CM v4.0, L6 1000/1/1000/400, source range 7..3079 |
| `hdr10_81.mkvdovi.RPU.bin` | `dovi_tool extract-rpu` of `mkvdovi hdr10_81.mkv --keep-source`'s output: one record per picture, in presentation order |
| `hdr10_81.aus.bin` | the 48 AUs as the Matroska blocks store them (4-byte NAL lengths), in decode order; the manifest's `aus.size` splits them |
| `hdr10_81.verdicts.txt` | the real matcher's verdict for each AU in decode order: `pts size kind min avg max refresh shot` |

The clip, from hdr-analyze `mkvdovi/tests/support/playback.rs`:

- Two shots: dark before picture 20, bright from it. The manifest's shots start at pictures 0 and 20.
- Three B-frames per GOP (decode order I P B B), so decode and presentation order differ. IRAPs at AU 0
  (IDR) and AU 24 (a CRA with no leading pictures), both with VPS, SPS and PPS.
- The hvcC holds the VPS, SPS, PPS and x265's user-data SEI only. The mastering display (P3 D65,
  0.0001 to 1000 nits) and light level (MaxCLL 1000, MaxFALL 400) SEI ride in AUs 0 and 24, and the file
  has no Colour element, so PQ comes from the SPS VUI. A converted track is therefore prepared twice:
  at track init, with no static values, and again when the first AU's static SEI is read (DV3.4).
- Cues and Tags follow the Clusters, so a reader that starts at 0 seeks to the Cues and back.

Tools, 2026-11-18 (macOS arm64): ffmpeg 9.0.2 with libx265 4.3+1-e9b8812, mkvmerge v103.0, mkvdovi 0.8.0
(the hdr-analyze checkout at `00ebf6c`, with its `hdr_analyzer_mvp`), dovi_tool 2.3.4. The RPU library's
pin (hdr-analyze `eb727a0`) reads the manifest. Two runs of `gen.sh` gave the same bytes.

Who reads them:

- `native/rpu_builder/src/manifest_fixture_tests.rs` (`cargo test`): the real parser, matcher and builder
  at the pin. Every AU is injected, and its RPU is `7C 01` plus the `RPU.bin` record at the AU's
  presentation rank, so it equals `mkvdovi`'s. CI does not run it.
- `MatroskaRpuInjectionTest` (Gradle): the real Matroska extractor reads `hdr10_81.mkv`, and a stand-in for
  the library (`TraceManifestNative`) replays `verdicts.txt`, checks each AU's bytes against `aus.bin` and
  renders each shot from the `RPU.bin` records.
