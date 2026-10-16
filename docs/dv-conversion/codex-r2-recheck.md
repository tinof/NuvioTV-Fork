# Codex pass 2: re-check of the revision (2026-10-16)

Model gpt-6-astra, effort medium, read-only sandbox, 165 s. Codex checked only the changes made for its pass-1 points. The prompt is in `codex-r2-prompt.txt`. Line numbers refer to the files before the fixes below.

## Codex output (verbatim)

Most dispositions are implemented, but several still need enforceable wording or dependencies. This was a focused text re-check; no files were modified.

Here, **D** means `docs/dv-conversion/DESIGN.md`, **R** means `ROADMAP.md`, and **Q** means `docs/dv-conversion/QUALIFICATION.md`.

| Point | Verdict | Evidence and remaining issue |
|---|---|---|
| 1 | **resolved** | Nine-sample evidence is now explicitly a conservative policy, with fades/dissolves excluded. D:62–64. |
| 2 | **resolved** | Global suppression is distinguished from the separately versioned causal detector; the invented delay bound is gone. D:49–55, 218–220; R:604–606. |
| 3 | **resolved** | Complete serialization, actual framing/capacity, synchronous mutation, ownership and tests are specified. D:169–183. |
| 4 | **partly** | Commitment is frozen, but “capacity is checked before commitment” does not explain how **all subsequent AUs** are guaranteed to fit. D:144–152. Replacement below. |
| 5 | **resolved** | Identity, generations, completeness watermark and lifecycle tests are present. D:156–165. |
| 6 | **partly** | The default order still puts a `full` measured shot list ahead of HDR10+, contradicting the immediately following A/B requirement and R:499–501. D:137–140. |
| 7 | **resolved** | Manifest ownership, timeline mapping, incremental identity validation, provenance and adversarial fixtures are specified. D:224–237; R:636–641. |
| 8 | **partly** | The colour/composer contract is restored, but HLG sidecar playback can bypass DV2.4. D:195–208, 300–301 versus R:639–643, 662–665. |
| 9 | **resolved** | Track-init inputs, late-transfer policy, level selection, existing-DV exclusion, native fallback and Matroska scope are specified. D:185–201. Summary inconsistency noted below. |
| 10 | **resolved** | Static values are labelled heuristics; missing/bad metadata and dark HLG are included. R:519–522; D:290–292. |
| 11 | **resolved** | Rejection now rests on the input deadline; idle and native-first-play experiments remain available. D:241–249. |
| 12 | **resolved** | Separate PQ/HLG qualification, decoded-code comparison, chroma/crop, synchronization and lifetime tests are included. R:546–552. |
| 13 | **partly** | Probe/qualification distinction is explicit, but the matrix lacks G-C/G-D/G-E and configuration-specific thermal rows. Also, “starts only” can block implementation needed to perform qualification. R:542–544; Q:7–19. |
| 14 | **partly** | Coverage, baseline metrics, controlled L1 and thermal testing are added. Explicit network-stall testing and a requirement to freeze corpus/tolerances **before passing** remain missing. R:558–573, 689–690. |
| 15 | **resolved** | HLG scratch costs, qualified stride claim and complete memory categories are restored. D:260–279. |
| 16 | **resolved** | Shared budget, compressed-data example, reserve protection, catch-up and unvalidated dual-4K60 wording are present. D:275–282. |
| 17 | **partly** | General cache policy is fixed, but new DV5.6 again promises desktop tolerance and first-frame reuse without requiring complete coverage or full-quality analysis. R:662–665 versus D:296–297 and R:679–681. |
| 18 | **partly** | Most graph repairs are present. However, decoder feeding/skipping/stress remain bundled, as do import/synchronization and PQ statistics. R:656–672. Proposed split below. |
| 19 | **partly** | Warm-up, incomplete first shot and matched-context reference are added. DV4.3 still promises finished metadata within ten seconds without an eligibility condition, and omits DV4.5 from `Needs:`. R:644–651. |
| 20 | **resolved** | Builders no longer wait on analysis; idle analysis precedes concurrent analysis and does not require G-B. R:599–609, 627, 662–665. I accept the HDR10+ ordering deviation; explanation below. |
| 21 | **resolved** | Smaller PQ reduction, no global 64-bit atomics, adaptive sampling and explicitly hypothetical tiled HLG composition are present. D:251–256; R:656–661. |

**Exact replacement text for the partial points:**

- **4 — replace D:151–152:**

  > Before committing a late provider, establish a serialized-size/headroom guarantee for every AU in that shot, including scene-refresh variants and reconfiguration prefixes. Checking only the first AU is insufficient. If that guarantee cannot be established, retain the fallback for the whole shot. Test a later AU with less headroom than the first; no within-shot fallback is permitted.

- **6 — replace D:137–140:**

  > For HDR10+ content, the HDR10+ provider remains the default; replacing it with any measured shot list, including `full`, requires an explicitly enabled policy accepted by G-E. For other content, prefer an admissible complete `full` shot list, then a complete `approximate` measurement, then static. “Exact” means full-quality analysis of the same file, never merely matching identity.

- **8 — replace DV4.1’s dependency sentence at R:641:**

  > Needs: DV4.4, DV2.2, DV2.3; for HLG, also DV2.4.

  This also covers the dependent local-sidecar and idle-cache playback paths.

- **13 — replace R:542–544 and Q:3–5 with:**

  > Probes and implementation may run before qualification; enabling a configuration requires passing results for every gate applicable to its tier. Record each result against device/firmware, transfer/composer, resolution, frame rate, decode mode and pixel path, plus the applicable playback/display chain. Add G-C, G-D and G-E rows and separate configuration-specific idle and concurrent G-F rows. A missing, failed or not-run applicable result keeps that configuration disabled; idle analysis does not require G-B or concurrent G-F.

- **14 — append to the common device-run requirements at R:571–573:**

  > Before declaring a gate passed, freeze the corpus, expected outcomes and numerical acceptance tolerances. Include long uncut shots, repeated seeks, network stalls and resource reclaim; report recovery and fallback coverage as well as steady-state results.

- **17 — replace DV5.6’s gate at R:664–665:**

  > Gate: identical-frame implementation parity and declared sampling/scene-policy error are evaluated separately. The next play uses validated covered ranges at their recorded quality; uncovered ranges retain fallback. First-frame reuse is required only when coverage includes that frame, and offline parity is claimed only for complete validated analysis.

- **18 — use these scope/dependency replacements, preserving existing IDs and allocating new IDs after DV5.9:**

  > DV5.2: Analysis decoder feeding and best-effort priority. Gate: feeding and lifecycle unit tests. Needs: DV1.3.
  > DV5.10: Dependency-safe skipping modes. Gate: reference-dependency and output-coverage tests for each mode. Needs: DV5.2.
  > DV5.11: Vulkan frame import and synchronization. Gate: G-A image-lifetime, fence and reuse tests on the target device. Needs: DV1.2.
  > DV5.3: PQ statistics and reductions. Gate: identical-frame parity against the desktop CPU path. Needs: DV5.11, DV2.5.
  > DV5.12: Device stress validation of decoder feeding, skipping and memory governance. Gate: playback remains within G-B criteria under pressure and reclaim. Needs: DV5.10, DV5.7.
  > Add DV5.12 to DV5.4’s Needs list.

- **19 — replace DV4.3’s gate and dependencies at R:648–651:**

  > Gate: on fixtures whose declared analysis context permits a complete subsequent shot to be finalized and delivered within 10 s, its metadata reaches the injector before that shot’s commitment deadline. Other fixtures, including seeking inside a long uncut shot, retain fallback until an eligible shot is ready; record time to first usable finalized shot. No added dropped frames. Needs: DV4.1, DV4.2, DV4.5, DV5.1.

**Point 20:** Claude’s reason is sound. The HDR10+ provider consumes metadata already attached to the AU and uses the shared builders/extractor path; it does not need the manifest or late-replacement infrastructure. With DV2.2 now depending only on builders, keeping HDR10+ in T0 no longer creates the original delay. I no longer disagree with that ordering. The provider-precedence correction in point 6 remains necessary independently.

**Additional revision inconsistencies:**

- **Unknown-colour policy:** R:508–509 says unknown colour information means native playback, whereas D:195 accepts untagged HLG range/matrix/primaries. Replace the summary with:

  > Dolby Vision is signalled at track init from container metadata and the SPS; unknown transfer or contradicting colour tags mean native playback. Untagged HLG range, matrix and primaries follow the logged defaults in DESIGN §2.5. Matroska first.

- **Wrong measurement-step reference:** D:274 assigns the PQ kernel runtime measurement to DV1.2, which is a pixel-path probe, while kernel implementation is DV5.3. Replace “Measured in DV1.2” with:

  > Measure kernel runtime on the target device after DV5.3; DV1.2 qualifies pixel access.

- **IDs and references otherwise check out:** No step ID was reused or renumbered against HEAD. New IDs extend the previous maxima; moved DV5.1 retains its identity. Referenced DESIGN sections exist, and Q’s Appendix A.4 reference is correct. No additional contradiction found in the revised log entry at `docs/ROADMAP_LOG.md:19–39`.
## Disposition

Every replacement Codex proposed was applied:

- 4: headroom guaranteed for every AU of a shot before a late provider is committed (DESIGN 2.2).
- 6: HDR10+ stays the default for HDR10+ content; a measured shot list replaces it only under a G-E policy (DESIGN 2.1).
- 8: DV4.1 needs DV2.4 for HLG.
- 13: qualification paragraph in A.4 and `QUALIFICATION.md` rewritten; G-C, G-D, G-E rows and separate idle and playback G-F rows added.
- 14: frozen corpus, outcomes and tolerances before a pass; long shots, repeated seeks, network stalls and reclaim in the corpus.
- 17: DV5.6 gate rewritten.
- 18: DV5.2 and DV5.3 split into DV5.2, DV5.10, DV5.11, DV5.3 and DV5.12; DV5.4 needs DV5.12.
- 19: DV4.3 gate rewritten; DV4.3 needs DV4.5.
- The summary line on unknown colour and the GPU runtime reference in DESIGN 3 fixed as proposed.

Also clarified: DV1.6 names the load of each G-F run. Point 20 is closed: Codex accepted keeping HDR10+ in T0.
