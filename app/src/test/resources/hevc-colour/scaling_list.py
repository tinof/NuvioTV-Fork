"""Prints an HM-style scaling list file for x265 --scaling-list: every matrix differs from the
defaults and from the others, except INTER*_CHROMAV, a copy of INTER*_CHROMAU (predicted, not coded)."""

SIZES = (("4X4", 16), ("8X8", 64), ("16X16", 64), ("32X32", 64))
for size, count in SIZES:
    for kind in ("INTRA", "INTER"):
        for comp in ("LUMA", "CHROMAU", "CHROMAV"):
            seed = 1 if (kind, comp) == ("INTER", "CHROMAV") else ("LUMA", "CHROMAU", "CHROMAV").index(comp)
            seed += 3 * ("INTRA", "INTER").index(kind) + 6 * [s for s, _ in SIZES].index(size)
            name = f"{kind}{size}_{comp}"
            values = [16 + (i * 5 + seed * 3) % 48 for i in range(count)]
            print(f"{name} =")
            for row in range(0, count, 8):
                print(",".join(str(v) for v in values[row:row + 8]) + ",")
            if size in ("16X16", "32X32"):
                print(f"{name}_DC =")
                print(f"{16 + seed},")
