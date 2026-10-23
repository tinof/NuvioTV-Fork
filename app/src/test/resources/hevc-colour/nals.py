"""Prints the VPS, SPS, PPS and prefix SEI NALs of an Annex B HEVC stream as `name=hex` lines."""
import sys

NAMES = {32: "vps", 33: "sps", 34: "pps", 39: "sei"}


def split(data):
    i, starts = 0, []
    while i + 3 <= len(data):
        if data[i] == 0 and data[i + 1] == 0 and data[i + 2] == 1:
            starts.append(i + 3)
            i += 3
        else:
            i += 1
    for k, s in enumerate(starts):
        end = starts[k + 1] - 3 if k + 1 < len(starts) else len(data)
        nal = data[s:end]
        while nal and nal[-1] == 0:
            nal = nal[:-1]
        yield nal


def main(path):
    data = open(path, "rb").read()
    seen = set()
    for nal in split(data):
        t = (nal[0] >> 1) & 0x3F
        if t in NAMES:
            key = (t, nal.hex())
            if key in seen:
                continue
            seen.add(key)
            print(f"{NAMES[t]}={nal.hex()}")


if __name__ == "__main__":
    main(sys.argv[1])
