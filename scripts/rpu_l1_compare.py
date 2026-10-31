"""Compare two RPU.bin files frame by frame: L1, the rest of each RPU, and scene_refresh_flag (ROADMAP DV3.1).

The gate of DV3.1 is "L1 equal to dovi_tool for the same frames". `ours` is the app's RPU debug dump in
presentation order (`scripts/rpu_dump_to_bin.py --order pts`), `reference` what `dovi_tool generate
--hdr10plus-json` writes for the same title, as `mkvdovi` runs it (peak source histogram, CM v4.0):

    hdr10plus_tool extract title.mkv -o title.hdr10plus.json
    mkvdovi ... (or dovi_tool generate -j extra.json --hdr10plus-json title.hdr10plus.json -o reference.RPU.bin)
    python3 scripts/rpu_l1_compare.py ours.RPU.bin reference.RPU.bin [--offset N]

Frame i of `ours` is compared with frame N + i of `reference` (N: the first frame the dump holds, 0 for a
playback from the start). Both files are read through `dovi_tool export`, so dovi_tool must be on PATH.

Three results per frame:
  - L1 (min, avg, max): must be equal; a difference fails.
  - The rest of the RPU (L2, L6, L9, L11, L254, the source range, the mapping): must be equal; a difference
    fails. The CRC is left out, as it follows from the rest.
  - scene_refresh_flag: reported, never a failure. The app's flag differs from dovi_tool's where
    `docs/dv-conversion/DESIGN.md` 2.4 says it can (extra flags up to an anchor's display lead after a
    scene start, starts and seeks).

Exit 0 when every compared frame has equal L1 and an equal rest, 1 otherwise, 2 on a usage error.
"""
import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def export(rpu_bin, workdir, name):
    out = Path(workdir) / f"{name}.json"
    subprocess.run(["dovi_tool", "export", "-i", str(rpu_bin), "-d", f"all={out}"],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    return json.loads(out.read_text())


def level1(rpu):
    """(min, avg, max) of the first L1 block, or None."""
    blocks = (rpu.get("vdr_dm_data") or {}).get("cmv29_metadata", {}).get("ext_metadata_blocks", [])
    for block in blocks:
        if "Level1" in block:
            l1 = block["Level1"]
            return l1["min_pq"], l1["avg_pq"], l1["max_pq"]
    return None


def scene_refresh(rpu):
    return (rpu.get("vdr_dm_data") or {}).get("scene_refresh_flag")


def rest(rpu):
    """The RPU without L1, scene_refresh_flag and the CRC."""
    copy = json.loads(json.dumps(rpu))
    copy.pop("rpu_data_crc32", None)
    dm = copy.get("vdr_dm_data") or {}
    dm.pop("scene_refresh_flag", None)
    blocks = dm.get("cmv29_metadata", {}).get("ext_metadata_blocks")
    if blocks is not None:
        dm["cmv29_metadata"]["ext_metadata_blocks"] = [b for b in blocks if "Level1" not in b]
    return copy


def compare(ours, reference, offset=0):
    """Returns (compared, l1_diffs, rest_diffs, refresh_diffs): each diff list holds (frame, ours, reference)."""
    l1_diffs, rest_diffs, refresh_diffs = [], [], []
    compared = 0
    for i, rpu in enumerate(ours):
        frame = offset + i
        if frame >= len(reference):
            break
        compared += 1
        ref = reference[frame]
        if level1(rpu) != level1(ref):
            l1_diffs.append((frame, level1(rpu), level1(ref)))
        if rest(rpu) != rest(ref):
            rest_diffs.append((frame, None, None))
        if scene_refresh(rpu) != scene_refresh(ref):
            refresh_diffs.append((frame, scene_refresh(rpu), scene_refresh(ref)))
    return compared, l1_diffs, rest_diffs, refresh_diffs


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("ours", type=Path, help="the app's RPUs in presentation order")
    parser.add_argument("reference", type=Path, help="dovi_tool's RPUs for the whole title")
    parser.add_argument("--offset", type=int, default=0, help="the reference frame of ours' first RPU")
    parser.add_argument("--show", type=int, default=20, help="differences to print per kind")
    args = parser.parse_args(argv)
    if shutil.which("dovi_tool") is None:
        parser.exit(2, "dovi_tool is not on PATH\n")
    if args.offset < 0:
        parser.error("--offset must be 0 or more")

    with tempfile.TemporaryDirectory() as workdir:
        ours = export(args.ours, workdir, "ours")
        reference = export(args.reference, workdir, "reference")
    compared, l1_diffs, rest_diffs, refresh_diffs = compare(ours, reference, args.offset)
    if compared < len(ours):
        print(f"warning: {len(ours) - compared} of ours' RPUs lie past the reference's {len(reference)} frames",
              file=sys.stderr)
    for name, diffs in (("L1", l1_diffs), ("rest of the RPU", rest_diffs), ("scene_refresh_flag", refresh_diffs)):
        for frame, mine, theirs in diffs[:args.show]:
            detail = "" if mine is None and theirs is None else f": ours {mine}, reference {theirs}"
            print(f"frame {frame}: {name} differs{detail}")
        if len(diffs) > args.show:
            print(f"... {len(diffs) - args.show} more {name} differences")
    print(f"{compared} frames compared from frame {args.offset}: {len(l1_diffs)} L1 differences, "
          f"{len(rest_diffs)} other differences, {len(refresh_diffs)} scene_refresh_flag differences (not a failure)")
    return 1 if l1_diffs or rest_diffs or compared == 0 else 0


if __name__ == "__main__":
    sys.exit(main())
