# dcaxll: provenance

`dcaxll_enc.c` and `dcaxll_enc.h` are verbatim copies of the dcaxll library, a DCA core + ExSS + XLL
access-unit writer (ETSI TS 102 114) whose output is compatible with DTS-HD Master Audio decoders.
They come from the owner's own repository `dts-hdma` (`~/Documents/GitHub/dts-hdma`, `src/`).

| | |
|---|---|
| Author | tinof (the NuvioTV fork owner), written with Claude |
| Licence | LGPL-2.1-or-later (SPDX header in both files; full text in `COPYING` of that repository) |
| Commit | `f8df9b346961353181446fa5c5170e46b0f0f886` ("README: dcaxll name, provenance, notice and licence") |
| `dcaxll_enc.c` SHA-256 | `c9f3f93a641c66781481309b09757603d5f6b773bfc08696e0294474d7417448` |
| `dcaxll_enc.h` SHA-256 | `40196acc73e81bf1fde2396503533f08e3580ac289448e0896e3c8054ed895e7` |
| Copied | 2026-10-22 |

**Rename:** until 2026-10-22 the library was called dtshdma (`dtshdma_enc.{c,h}`, prefix `dtshdma_`,
macros `DTSHDMA_`, folder `jni/dtshdma/`, copied from commit `991777a`). dts-hdma commit `c4866a4`
renamed it to dcaxll, so the library's name is not a trademark. The code did not change: the only
differences from the `991777a` copy are the names and the licence header (`537a0ed`).

The library is portable C17, uses only libc, allocates once at create time and contains no code
derived from Swift_DTSHDMAX or any other encoder (see its README, "Provenance"). The field-level
contract is `docs/WRITER_SPEC.md` in that repository.

**Rule:** never edit the copies here. Fix the library in `dts-hdma` first (with its unit tests and
`tests/run.sh`), commit there, copy both files again, and update the commit and the hashes above.

**Licence:** the library is LGPL-2.1-or-later. This module is distributed under GPL-3.0-only
(`../../../../NOTICE.md`); LGPL-2.1-or-later code may be combined with it and distributed under
GPL-3.0 (LGPL-2.1 section 3). The copies keep their own LGPL headers.

`dtshd_stage.c` and `dtshd_stage.h` in this folder are not copies: they are this module's glue
between the JNI and the library (quantizing, lookahead and pairing), GPL-3.0-only like the rest of
the module.
