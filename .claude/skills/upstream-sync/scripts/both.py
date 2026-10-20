"""Resolve git conflict blocks in a file, during a rebase or a merge.

usage: both.py [--list] <file> [ours|theirs|ours-theirs|theirs-ours] [block ...]

ours-theirs keeps both sides, ours first; SEP=1 puts a blank line between them. Without block
indexes (0-based, as --list prints them) every block is resolved. During a rebase "ours" is the
new base (upstream) and "theirs" is the fork commit being replayed. A diff3 base section is
dropped. Exit 0: resolved; 1: no blocks or a bad index; 2: a malformed block.
"""
import os
import re
import sys
from pathlib import Path

START = re.compile(r"^<{7} ")
BASE = re.compile(r"^\|{7}( |$)")
MID = re.compile(r"^={7}$")
END = re.compile(r"^>{7}( |$)")
ORDERS = ("ours", "theirs", "ours-theirs", "theirs-ours")


class Malformed(ValueError):
    pass


def blocks(lines):
    """Yields (start, end, ours, theirs) for each block; end is the index of the >>>>>>> line."""
    i = 0
    while i < len(lines):
        if not START.match(lines[i]):
            i += 1
            continue
        start, ours, theirs, j, part = i, [], [], i + 1, "ours"
        while True:
            if j >= len(lines):
                raise Malformed(f"block at line {start + 1} has no end marker")
            line = lines[j]
            if START.match(line):
                raise Malformed(f"block at line {start + 1} contains another start marker")
            if part == "ours" and BASE.match(line):
                part = "base"
            elif part in ("ours", "base") and MID.match(line):
                part = "theirs"
            elif part == "theirs" and END.match(line):
                break
            elif part == "ours":
                ours.append(line)
            elif part == "theirs":
                theirs.append(line)
            j += 1
        if part != "theirs":
            raise Malformed(f"block at line {start + 1} has no ======= separator")
        yield start, j, ours, theirs
        i = j + 1


def resolve(text, order, only=(), sep=False):
    """Returns (new text, number of blocks resolved)."""
    if order not in ORDERS:
        raise ValueError(f"order must be one of {', '.join(ORDERS)}")
    lines = text.split("\n")
    found = list(blocks(lines))
    bad = [n for n in only if n < 0 or n >= len(found)]
    if bad:
        raise IndexError(f"no block {bad[0]}; the file has {len(found)}")
    out, last, done = [], 0, 0
    for n, (start, end, ours, theirs) in enumerate(found):
        out.extend(lines[last:start])
        if only and n not in only:
            out.extend(lines[start:end + 1])
        else:
            gap = [""] if sep else []
            out.extend({"ours": ours, "theirs": theirs, "ours-theirs": ours + gap + theirs,
                        "theirs-ours": theirs + gap + ours}[order])
            done += 1
        last = end + 1
    out.extend(lines[last:])
    return "\n".join(out), done


def describe(text):
    lines = text.split("\n")
    for n, (start, end, ours, theirs) in enumerate(blocks(lines)):
        head = lambda side: (side[0].strip()[:70] if side else "(empty)")
        print(f"{n}: line {start + 1}-{end + 1}  ours {len(ours)} lines: {head(ours)}")
        print(f"   {' ' * len(str(start + 1))}        theirs {len(theirs)} lines: {head(theirs)}")


def main(argv):
    if argv[:1] == ["--list"]:
        if len(argv) != 2:
            print(__doc__.strip(), file=sys.stderr)
            return 2
        try:
            describe(Path(argv[1]).read_text())
        except Malformed as e:
            print(f"{argv[1]}: {e}", file=sys.stderr)
            return 2
        return 0
    if len(argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    path, order = argv[0], argv[1]
    try:
        only = {int(x) for x in argv[2:]}
        text, done = resolve(Path(path).read_text(), order, only, bool(os.environ.get("SEP")))
    except (Malformed,) as e:
        print(f"{path}: {e}", file=sys.stderr)
        return 2
    except (ValueError, IndexError) as e:
        print(f"{path}: {e}", file=sys.stderr)
        return 1
    if not done:
        print(f"{path}: no conflict blocks", file=sys.stderr)
        return 1
    Path(path).write_text(text)
    print(f"{done} block(s) resolved in {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
