# Fixtures

Copied unchanged from hdr-analyze `dovi_rpu_builder/tests/fixtures` at commit
`f55c779f10034f19b4e4e26e3fcd09adcbe73233`, the commit `Cargo.toml` pins. Every `RPU.bin` was written by
`dovi_tool generate` 2.3.4 (and, for bt2100 and the canary, rewritten by hdr-analyze's
`fit_hlg_composer`), never by the library under test; their hashes equal hdr-analyze's own
`SHA256SUMS` at that commit. Only the files `src/fixture_tests.rs` reads are here.

| Files | Case |
|---|---|
| `static_84_bt2100.*`, `static_84_canary.*` | `static_84_params` with `STATIC_84_L1`, 4 AUs |
| `static_81.*` | 8.1, CM v4.0, source range 62..3079, L1 0 / 1500 / 3200, 4 AUs |
| `p81_v29_shots.*` | 8.1, CM v2.9, L5, three shots that test the L1 clamp |
| `p81_v40_shots.*` | 8.1, CM v4.0, average floor V29, L6 from a mastering SEI, L5, the same three shots |
| `profile_{a,b}.payloads` | HDR10+ T.35 payloads, each behind a 2-byte big-endian length |
| `profile_{a,b}.json` | `hdr10plus_tool`'s JSON of those payloads; its `SceneFirstFrameIndex` |
| `profile_{a,b}.<peak>.<cm>.RPU.bin`, `hdr10plus.<cm>.extra.json` | `dovi_tool generate --hdr10plus-json` per peak source and CM version |

An `RPU.bin` holds one record per AU: `00 00 00 01`, then the RPU NAL without its 2-byte header.

Check: `shasum -a 256 -c SHA256SUMS`. When the pin moves, copy the same files from the new commit,
rewrite `SHA256SUMS` and this README's commit, and run `cargo test`.
