# nuvio_rpu

`libnuvio_rpu.so`: hdr-analyze's `dovi_rpu_builder` behind JNI, for the on-device Dolby Vision
conversion (ROADMAP Appendix A, step DV2.2; design in `docs/dv-conversion/DESIGN.md`). It writes
complete Profile 8.1 and 8.4 RPU NALs, byte-identical to `dovi_tool generate` 2.3.4: static 8.4
(composer preset, bt2100 or the canary), 8.1 from given params, shot L1 → both scene-refresh
variants of an RPU, and HDR10+ payload → L1. The Kotlin side is
`app/src/main/java/com/nuvio/tv/core/player/DoviRpuBuilder.kt`.

- `src/api.rs`: the logic with no JNI types: argument decoding, the builder handle table, the codes.
- `src/ffi.rs`: the `Java_com_nuvio_tv_core_player_DoviRpuBuilder_native*` exports, each inside
  `catch_unwind`.
- `src/fixture_tests.rs`, `tests-fixtures/`: the wrapper against hdr-analyze's committed
  `dovi_tool` output.
- `src/jvm_tests.rs` (feature `jvm-tests`): the `Java_*` exports called through a real JVM.

## Why a separate library

The vendored `DV7/libdovi/*/lib/libdovi.a` is a Rust staticlib with its own copy of std. A second Rust
staticlib in `libdovi_bridge.so` fails to link under fat LTO on the duplicate unmangled
`rust_eh_personality`, and links without LTO only while both archives come from the same rustc
(`DESIGN.md` 2.7). As its own `cdylib` the builder needs only `libc`, `libm` and `libdl`, exports only
the seven `Java_*` functions, and is loaded by its own `System.loadLibrary("nuvio_rpu")`. Without it
(armeabi-v7a, a build without cargo-ndk) `DoviRpuBuilder.isAvailable()` is false and playback stays
native.

## The pin

`Cargo.toml` pins `dovi_rpu_builder` to hdr-analyze commit
`f55c779f10034f19b4e4e26e3fcd09adcbe73233` (main), and `Cargo.lock` records it. To move it:

1. Change `rev` in `Cargo.toml` and the seven-character commit in `VERSION` (`src/api.rs`); the test
   `the_version_names_the_pinned_commit` fails while they differ.
2. `cargo update -p dovi_rpu_builder` and commit the new `Cargo.lock`.
3. Copy the same fixture files from the new commit's `dovi_rpu_builder/tests/fixtures`, rewrite
   `tests-fixtures/SHA256SUMS` and the commit in `tests-fixtures/README.md`.
4. `cargo test`, then `scripts/build_rpu_builder.sh`.

`rust-toolchain.toml` pins rustc 1.93.0 (the compiler of the vendored `libdovi.a`) with the
`aarch64-linux-android` target; rustup installs both on the first `cargo` call here.

## Build and test

```sh
cd native/rpu_builder && cargo test        # host tests, fixtures included
scripts/build_rpu_builder.sh               # arm64-v8a .so into app/src/main/jniLibs (gitignored)
scripts/shield_build_install.sh            # runs build_rpu_builder.sh first when cargo-ndk is installed
python3 scripts/verify_native_apk.py app/build/outputs/apk/full/debug/app-full-arm64-v8a-debug.apk --abi arm64-v8a
```

Plain `cargo test` needs no JDK and does not touch `src/ffi.rs`'s JNI code. The JNI layer has its
own host tests, behind the test-only feature `jvm-tests` (it turns on jni's `invocation`; never use
it for the Android build):

```sh
cd native/rpu_builder
JAVA_HOME="$(/usr/libexec/java_home -v 17)" cargo test --features jvm-tests
```

They start one JVM with `-Xcheck:jni` in the test process and call every `Java_*` export with a real
`JNIEnv` and Java arrays: static 8.4 (bt2100, canary) and an 8.1 case equal the fixtures through
JNI, `nativeHdr10PlusL1` fills its out array, wrong-length and null arrays are code 20, a released
handle is code 21, and no call leaves a Java exception pending. jni finds `libjvm` through
`JAVA_HOME` (without it, macOS's default `/usr/libexec/java_home`, or the `java` on `PATH` on Linux)
and loads `$JAVA_HOME/lib/server/libjvm.dylib` by its full path, so macOS needs no
`DYLD_LIBRARY_PATH`. `cargo clippy --all-targets --features jvm-tests` checks the test code.

The build needs cargo-ndk (`cargo install cargo-ndk --locked`) and the NDK that `app/build.gradle.kts`
names (`ndkVersion`). It links with `-z max-page-size=16384` (`.cargo/config.toml`), and the script
refuses a result whose LOAD segments are not 16 KB aligned. The release profile keeps
`panic = "unwind"` (needed by `catch_unwind`), fat LTO, one codegen unit and strips symbols. CI builds
no Rust; the library is built locally only, like the other native binaries since 2026-10-22.

## Codes

Every call returns one of these (Kotlin: `DoviRpuBuilder.Code`). A code is never renumbered.

| Code | Kotlin | Meaning |
|---|---|---|
| 0 | `OK` | ok |
| 1 | `INVALID_COMPOSER` | the composer cannot be written |
| 2 | `BLOCK_LEVEL` | a metadata block does not belong to the RPU's CM version |
| 3 | `OUT_OF_RANGE` | a value the RPU cannot carry (L6 above 10 000 nits, L5 above 8191, a source range above 4095 or inverted) |
| 4 | `NOT_HDR10_PLUS` | the T.35 payload is not HDR10+; skip the SEI |
| 5 | `HDR10_PLUS_PARSE` | a broken or multi-window HDR10+ payload |
| 6 | `HDR10_PLUS_NO_PEAK` | the payload has no value for the selected peak source |
| 7 | `RPU` | the `dolby_vision` crate failed after the checks passed (internal) |
| 20 | `BAD_ARGUMENT` | a null or wrong-length array, an unknown enum value, a value that does not fit its field (negative, or above 65535 for a 16-bit field) |
| 21 | `UNKNOWN_HANDLE` | a handle never created or already released |
| 22 | `JNI_FAILURE` | a Java array could not be allocated or accessed (the pending exception is cleared) |
| 99 | `PANIC` | a panic, caught at the JNI boundary |
| - | `UNAVAILABLE` | Kotlin only: the library is not loaded, or the call threw |
| - | `UNKNOWN` | Kotlin only: a number the library should not return |

1 to 7 are `dovi_rpu_builder::Error::code`. `nativeCreate*` return a handle > 0 or the negated
code; `nativeRenderShot` returns null and sets `codeOut[0]`.

## Handles

Builders live in a table keyed by a counter that starts at 1 and never reuses a number, so a stale
or invented handle is code 21, never freed memory. `nativeRenderShot` clones the builder out of the
table and writes outside the lock; a builder is `Send + Sync`, so threads may share a handle. A
panic never leaves the table unusable (a poisoned lock is taken over). Every handle must be released
with `nativeRelease`.
