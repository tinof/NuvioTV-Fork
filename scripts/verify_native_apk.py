"""Validate the fork's required native playback components before distributing an APK."""
import argparse
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path

MACHINES = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40)}
REQUIRED = {"libdovi_bridge.so", "libffmpegJNI.so", "libass.so", "libasskt.so"}
# Dolby Vision conversion (ROADMAP DV2.2), built by scripts/build_rpu_builder.sh for arm64-v8a only.
RPU_BUILDER = "libnuvio_rpu.so"
RPU_BUILDER_MARKERS = (b"Java_com_nuvio_tv_core_player_DoviRpuBuilder_nativeRenderShot", b"nuvio_rpu ")
REQUIRED_BY_ABI = {"arm64-v8a": REQUIRED | {RPU_BUILDER}, "armeabi-v7a": REQUIRED}


def verify(apk: Path, abi: str, allow_missing_rpu_builder: bool = False) -> dict:
    """allow_missing_rpu_builder: accept an APK without libnuvio_rpu.so (a build with no Rust step).
    A present library is still checked."""
    elf_class, machine = MACHINES[abi]
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate ZIP entries")
        corrupt = archive.testzip()
        if corrupt:
            raise ValueError(f"Corrupt ZIP entry: {corrupt}")
        libraries = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
        present = {name.rsplit("/", 1)[-1] for name in libraries if name.startswith(f"lib/{abi}/")}
        missing = REQUIRED_BY_ABI[abi] - present
        if allow_missing_rpu_builder and RPU_BUILDER in missing:
            missing.discard(RPU_BUILDER)
            print(f"notice: no {RPU_BUILDER} for {abi}; Dolby Vision conversion is off in this APK", file=sys.stderr)
        if missing:
            raise ValueError(f"Missing native playback libraries for {abi}: {sorted(missing)}")
        hashes = {}
        for name in libraries:
            if not name.startswith(f"lib/{abi}/"):
                raise ValueError(f"Unexpected ABI in single-ABI APK: {name}")
            data = archive.read(name)
            if len(data) < 20 or data[:4] != b"\x7fELF" or data[4] != elf_class or data[5] != 1:
                raise ValueError(f"Invalid ELF class/encoding: {name}")
            if struct.unpack_from("<H", data, 18)[0] != machine:
                raise ValueError(f"Wrong native architecture: {name}")
            hashes[name] = hashlib.sha256(data).hexdigest()
        bridge = archive.read(f"lib/{abi}/libdovi_bridge.so")
        if b"dovi-bridge-libdovi-capi-0.2" not in bridge or b"dovi_convert_rpu_with_mode" not in bridge:
            raise ValueError("Dolby Vision bridge lacks real libdovi linkage")
        if RPU_BUILDER in REQUIRED_BY_ABI[abi] and RPU_BUILDER in present:
            rpu_builder = archive.read(f"lib/{abi}/{RPU_BUILDER}")
            if not all(marker in rpu_builder for marker in RPU_BUILDER_MARKERS):
                raise ValueError("Dolby Vision RPU builder lacks its JNI entry points or version")
    return {"apk": str(apk), "abi": abi, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "nativeLibraries": hashes}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--abi", required=True, choices=MACHINES)
    parser.add_argument("--allow-missing-rpu-builder", action="store_true",
                        help=f"accept an APK without {RPU_BUILDER} (built with no Rust step); a present one is still checked")
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.apk, args.abi, args.allow_missing_rpu_builder), indent=2))
    except (ValueError, zipfile.BadZipFile, OSError) as error:
        parser.exit(1, f"Native APK validation failed: {error}\n")
