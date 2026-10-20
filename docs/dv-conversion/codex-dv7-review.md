# Codex review of the DV7 draft (2026-10-19)

Codex (gpt-6.1-sol, high, read-only sandbox) reviewed the first draft of DV7 (`ROADMAP.md` Appendix A,
`DESIGN.md` section 5 and its additions to 2.3, 2.6, 2.9 and 3, the G-C rows) against this fork's code
and hdr-analyze `docs/FEL_PLAN.md`. Verdict: revise before accepting. Every code claim below was
re-checked by hand before it was applied. All eleven points were accepted.

| # | Severity | Finding | Disposition |
|---|---|---|---|
| 1 | major | DV7.3 reuses an RPU "without signalling a new scene", but the bundled C API has no scene-refresh setter (`rpu_parser.h`: besides conversion, only `dovi_rpu_remove_mapping` and `dovi_rpu_set_active_area_offsets` change an RPU) | DV7.3 now needs a writer that clears `scene_refresh_flag` (DV2.2's staticlib or a rebuilt libdovi), with tests for the flag and the CRC; Needs DV2.2 |
| 2 | major | Mode 1 (`ToMel`) keeps Profile 7 syntax, yet the fork signals Profile 8 (`DolbyVisionExtractorsFactory.kt:515-516`); it is not FEL_PLAN candidate b | `DESIGN.md` 5.1 and 5.4 say so; DV7.7 calls the mode 1 comparison a first look only |
| 3 | major | The 60-failure abandon is Matroska-only; MP4 and TS only count drops (`dovi_bridge.cpp:102-113`) | `DESIGN.md` 5.1 corrected |
| 4 | major | DV7.6 made RPUs for a real title, so the pulse test had no constant pixels | DV7.6 now makes an offline fixed-pixel clip in three variants that differ only in their RPUs |
| 5 | major | The 8.1 canary is evidence for FEL_PLAN gate 3, not the whole gate (profile validity, the mapping reaching the decoder, the chain) | DV7.6 and DV7.7 add an identity control, Profile 8 checks, RPUs from the debug log compared with the files, the expected difference written down first and the chain recorded |
| 6 | major | DV7.8 accepted FEL_PLAN gate "4 or 5", weaker than FEL_PLAN | (b) needs gates 1 to 4, (c) gates 1 to 5, a default change gate 6; also in `DESIGN.md` 5.4 and the owner decision |
| 7 | major | Budgeting an unknown frame rate as 24 fps would admit a 60 fps stream | `DESIGN.md` section 3: an unknown frame rate or size refuses the second decode until measured |
| 8 | minor | Auto also uses mode 2 on capability-selected non-Nvidia devices (`DolbyVisionBaseLayerPolicy.kt:56-66`, `:150-157`); TS is a separate path (`dovi_bridge.cpp:862-900`) | `DESIGN.md` 5.1 and DV7.4 corrected |
| 9 | minor | DV7.4 allowed a doc-only branch with a code gate; DV7.8 was `[desk]` with a playback-log gate | DV7.4 implements the fallback on every path; DV7.8's gate is extractor unit tests with manifest fixtures |
| 10 | minor | The canary row was scoped to all tiers | The canary row applies to DV7 candidates b and c; the pulse row stays for all tiers |
| 11 | minor | The same curve structure does not guarantee the same RPU size (variable-length coefficients, emulation prevention) | `DESIGN.md` 5.4 and DV7.8: injection accepts a different RPU length per AU |

Codex also confirmed that a panic in libdovi aborts the app (the cargo-c `-Cpanic=abort` setting and no
`catch_unwind` at the C boundary) and noted that a mode-2 decoder failure already retries the whole
playback once in mode 1 (`PlayerRuntimeControllerInitialization.kt:2295-2300`), now in `DESIGN.md` 5.1.
