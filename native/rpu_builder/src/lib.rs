//! `libnuvio_rpu.so`: hdr-analyze's `dovi_rpu_builder` behind JNI, for the Dolby Vision
//! conversion of NuvioTV (ROADMAP DV2.2). Kotlin side: `com.nuvio.tv.core.player.DoviRpuBuilder`.
//!
//! - `api`: the logic with no JNI types (arguments, the handle table, the codes); host-tested.
//! - `ffi`: the exported `Java_*` functions, each inside `catch_unwind`.
//!
//! A separate `cdylib`, not part of `libdovi_bridge.so`: the vendored `libdovi.a` carries its own
//! copy of Rust's std, and a second Rust staticlib in the same link fails on
//! `rust_eh_personality` (`docs/dv-conversion/DESIGN.md` 2.7).

mod api;
mod ffi;
