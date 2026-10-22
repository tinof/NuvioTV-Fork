# dtshdma: provenance

`dtshdma_enc.c` and `dtshdma_enc.h` are verbatim copies of the DTS-HD Master Audio access-unit
writer from the owner's own repository `dts-hdma` (`~/Documents/GitHub/dts-hdma`, `src/`).

| | |
|---|---|
| Author | tinof (the NuvioTV fork owner), written with Claude |
| Commit | `991777a4574b74348d913551bd6fa236f312d54f` ("Tight unit size bound from the 24-bit residual cap") |
| `dtshdma_enc.c` SHA-256 | `6d224c54f481d503b2806aa0cd8136081282a6103e7ede5f9d1bdbd345c191af` |
| `dtshdma_enc.h` SHA-256 | `a4737396a63a478bd88a16e623b8167a0ff79bf3305ec24e06314e123947ac21` |
| Copied | 2026-10-22 |

The library is portable C17, uses only libc, allocates once at create time and contains no code
derived from Swift_DTSHDMAX or any other encoder (see its README, "Known limits"). The field-level
contract is `docs/WRITER_SPEC.md` in that repository.

**Rule:** never edit the copies here. Fix the library in `dts-hdma` first (with its unit tests and
`tests/run.sh`), commit there, copy both files again, and update the commit and the hashes above.

**Licence:** the dts-hdma repository has no licence file yet. The owner wrote it for this module,
which is distributed under GPL-3.0-only (`../../../../NOTICE.md`); a written grant in dts-hdma is an
open owner decision (DTSHD_PORT.md, F10).
