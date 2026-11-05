//! `libnuvio_rpu.so`: hdr-analyze's `dovi_rpu_builder` behind JNI, for the Dolby Vision
//! conversion of NuvioTV (ROADMAP DV2.2). Kotlin side: `com.nuvio.tv.core.player.DoviRpuBuilder`.
//!
//! - `api`: the logic with no JNI types (arguments, the handle table, the codes); host-tested.
//! - `manifest`: the playback manifest (DV4.6): parse, the source, track and composer checks,
//!   the manifest's builder and the AU matcher, on the same handle table.
//! - `ffi`: the exported `Java_*` functions, each inside `catch_unwind`.
//!
//! A separate `cdylib`, not part of `libdovi_bridge.so`: the vendored `libdovi.a` carries its own
//! copy of Rust's std, and a second Rust staticlib in the same link fails on
//! `rust_eh_personality` (`docs/dv-conversion/DESIGN.md` 2.7).

mod api;
mod ffi;
mod manifest;

#[cfg(test)]
mod fixture_tests;
#[cfg(all(test, feature = "jvm-tests"))]
mod jvm_tests;
