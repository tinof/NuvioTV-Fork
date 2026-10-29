"""Rebuild an RPU.bin for `dovi_tool info` from the app's RPU debug dump in logcat.

Turn the dump on, play, and capture it (the switch is read when playback starts):

    adb shell setprop debug.nuvio.rpu_dump 24      # N: the first 24 AUs after a start or seek, plus every
                                                   # scene-refresh AU; 0 or unset turns it off
    adb logcat -c && adb logcat -P ''              # clear the log and logd's prune list
    adb logcat -v threadtime -s NuvioRpuDump > dump.logcat
    python3 scripts/rpu_dump_to_bin.py dump.logcat -o RPU.bin --strict
    dovi_tool info -i RPU.bin -f 0

Input is logcat text from a file or stdin, in `threadtime`/`brief` format (the text after `NuvioRpuDump:`) or
`-v raw` (lines that start with `v1 `), with or without `-v color`; other tags are ignored. The line format is `RpuDumpFormat` in
app/src/main/java/com/nuvio/tv/core/player/RpuDump.kt:

    v1 seq=<n> trk=<id> gen=<g> au=<i> pts=<us> sr=<0|1|?> why=<start|refresh> len=<bytes> crc=<crc32>
       part=<i>/<n> <hex>
    v1 gen=<g> trk=<id> event=<seek|format> seekUs=<us|-1> n=<N> codecs=<codecs|->

Each record's parts are joined and checked against len, crc (CRC32 of the whole NAL) and the `7C 01` NAL
header. A playback numbers its records from seq=0, so a missing seq is a lost record; a new seq=0 starts the
next playback (a session). Records are written in seq order, which is the order the renderer queued them (decode
order), each as `00 00 00 01` followed by the NAL without its 2-byte header: the layout dovi_tool's
parse_rpu_file splits on. Emulation prevention bytes stay in, as in the stream.

`--order pts` writes the selected records by presentation time instead (ROADMAP DV3.1), up to the first gap
in presentation time (the B-frames of the last anchor a stopped playback queued): the order of
`dovi_tool generate --hdr10plus-json` output, which `scripts/rpu_l1_compare.py` compares frame by frame. Select one
generation of a playback from the start (`--session 1 --gen 0`) with N above the frames played, so every AU is
dumped once:

    adb shell setprop debug.nuvio.rpu_dump 100000
    python3 scripts/rpu_dump_to_bin.py dump.logcat -o ours.RPU.bin --session 1 --gen 0 --order pts --strict
    python3 scripts/rpu_l1_compare.py ours.RPU.bin reference.RPU.bin

logcat loses lines when logd rate-limits a chatty app or its buffer wraps: keep N small, clear the prune list
as above, and use --strict to fail on a gap. Gaps are found only up to the last record received: records lost
after it leave no trace, so the summary prints each session's last seq to compare with what the playback should
have dumped (the first N AUs of each generation plus its scene refreshes).

The video renderer records what it queues to the decoder, from any source (Matroska, MP4, TS, HLS, DASH). A
generation starts at each position reset of the renderer, the start included, which shows as
`event=seek seekUs=<position>`, also for a seek Media3 serves from samples it already extracted; or at a change
of the video Format (`event=format seekUs=-1`).
"""
import argparse
import re
import sys
import zlib
from pathlib import Path

TAG = "NuvioRpuDump"
START_CODE = b"\x00\x00\x00\x01"
NAL_HEADER = b"\x7c\x01"
FIELD = re.compile(r"(\w+)=(\S+)")
ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
RECORD_KEYS = ("seq", "trk", "gen", "au", "pts", "sr", "why", "len", "crc")


def messages(text):
    """The dump's own lines, without the logcat prefix."""
    for line in text.splitlines():
        line = ANSI.sub("", line).rstrip("\r")       # `adb logcat -v color`
        at = line.find(TAG)
        if at >= 0:
            message = line[at + len(TAG):].lstrip()
            # brief: `I/NuvioRpuDump( 1234): msg`; threadtime: `... I NuvioRpuDump: msg`
            message = re.sub(r"^\(\s*\d+\)", "", message).lstrip()
            if message.startswith(":"):
                message = message[1:].lstrip()
        else:
            message = line
        if message.startswith("v1 "):
            yield message


def parse(text):
    """Returns (sessions, problems): each session a list of records in seq order, each record a dict with the
    header fields and `nal` (bytes, with its 7C 01 header); problems lists what is wrong or missing."""
    sessions, problems = [], []
    current, pending, bad = None, {}, set()

    def close():
        if current is None:
            return
        index = len(sessions) + 1
        for seq, (fields, chunks) in sorted(pending.items()):
            problems.append(f"session {index}: seq {seq} incomplete, parts {sorted(chunks)} of {fields['parts']}")
        records = sorted(current.values(), key=lambda r: r["seq"])
        expected = set(range(records[-1]["seq"] + 1)) if records else set()
        missing = sorted(expected - set(current) - set(pending) - bad)
        if missing:
            problems.append(f"session {index}: seq gap, missing {format_ranges(missing)}")
        sessions.append(records)

    for message in messages(text):
        fields = dict(FIELD.findall(message))
        if "seq" not in fields:
            continue                                  # a generation line
        try:
            seq = int(fields["seq"])
            part, parts = (int(n) for n in fields["part"].split("/"))
            header = {key: fields[key] for key in RECORD_KEYS}
            chunk = bytes.fromhex(message.rsplit(" ", 1)[1])
        except (KeyError, ValueError) as error:
            problems.append(f"unreadable line ({error}): {message[:120]}")
            continue
        if seq == 0 and part == 1 and current:
            close()
            current, pending, bad = None, {}, set()
        if current is None:
            current = {}
        if seq in current:
            problems.append(f"session {len(sessions) + 1}: seq {seq} repeated")
            continue
        entry = pending.setdefault(seq, ({**header, "parts": parts}, {}))
        if {**header, "parts": parts} != entry[0]:
            problems.append(f"session {len(sessions) + 1}: seq {seq} parts disagree")
            continue
        entry[1][part] = chunk
        if len(entry[1]) == parts and set(entry[1]) == set(range(1, parts + 1)):
            del pending[seq]
            nal = b"".join(entry[1][i] for i in range(1, parts + 1))
            problem = check(header, nal)
            if problem:
                problems.append(f"session {len(sessions) + 1}: seq {seq} {problem}")
                bad.add(seq)
                continue
            current[seq] = {**{k: header[k] for k in RECORD_KEYS}, "seq": seq, "nal": nal}
    close()
    return sessions, problems


def check(header, nal):
    if len(nal) != int(header["len"]):
        return f"length {len(nal)} != len={header['len']}"
    if f"{zlib.crc32(nal):08x}" != header["crc"]:
        return "CRC mismatch"
    if nal[:2] != NAL_HEADER:
        return f"NAL header {nal[:2].hex()} is not 7c01"
    return None


def format_ranges(numbers):
    ranges, start = [], None
    for n, following in zip(numbers, numbers[1:] + [None]):
        start = n if start is None else start
        if following != n + 1:
            ranges.append(str(start) if start == n else f"{start}-{n}")
            start = None
    return ", ".join(ranges)


def select(sessions, session=None, gen=None, trk=None, refresh_only=False):
    chosen = sessions if session is None else sessions[session - 1:session]
    return [
        record for records in chosen for record in records
        if (gen is None or int(record["gen"]) in gen)
        and (trk is None or int(record["trk"]) == trk)
        and (not refresh_only or record["sr"] == "1")
    ]


def by_presentation_time(records):
    """The records sorted by pts, cut before the first gap of more than one frame: a playback stopped
    after an anchor picture whose B-frames were never queued, so the frame index of everything past the
    gap is unknown. The step is the most common difference between neighbours."""
    records = sorted(records, key=lambda record: int(record["pts"]))
    pts = [int(record["pts"]) for record in records]
    if len(set(pts)) != len(pts):
        print("warning: repeated pts in the selection; select one generation (--session, --gen)", file=sys.stderr)
    steps = [b - a for a, b in zip(pts, pts[1:]) if b > a]
    if not steps:
        return records
    step = max(set(steps), key=steps.count)
    for index, (a, b) in enumerate(zip(pts, pts[1:])):
        if b - a > step * 3 // 2:
            dropped = len(records) - index - 1
            print(f"warning: a gap after pts {a} (step {step} us); the {dropped} record(s) after it are left out",
                  file=sys.stderr)
            return records[:index + 1]
    return records


def rpu_bin(records):
    return b"".join(START_CODE + record["nal"][2:] for record in records)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("logcat", nargs="?", type=Path, help="logcat text; stdin when omitted")
    parser.add_argument("-o", "--output", type=Path, help="RPU.bin to write")
    parser.add_argument("--list", action="store_true", help="print one tab-separated row per record")
    parser.add_argument("--session", type=int, help="only this playback (1 = the first in the log)")
    parser.add_argument("--gen", type=int, action="append", help="only this generation (repeatable)")
    parser.add_argument("--trk", type=int, help="only this track id")
    parser.add_argument("--refresh-only", action="store_true", help="only AUs with scene_refresh_flag 1 (sr=1)")
    parser.add_argument("--order", choices=("seq", "pts"), default="seq",
                        help="write the records in queue (decode) order, or by presentation time (DV3.1)")
    parser.add_argument("--strict", action="store_true", help="exit 1 on a seq gap before the last record received, or an incomplete or bad record; "
                             "records lost after the last one cannot be seen")
    args = parser.parse_args(argv)
    if not args.output and not args.list:
        parser.error("give -o RPU.bin and/or --list")

    text = args.logcat.read_text(errors="replace") if args.logcat else sys.stdin.read()
    sessions, problems = parse(text)
    for problem in problems:
        print(f"warning: {problem}", file=sys.stderr)
    if args.session is not None and not 1 <= args.session <= len(sessions):
        parser.exit(2, f"no session {args.session}; the log has {len(sessions)}\n")
    records = select(sessions, args.session, args.gen, args.trk, args.refresh_only)
    if not records:
        print("no RPU dump records selected", file=sys.stderr)
        return 2
    if args.list:
        print("session\tseq\ttrk\tgen\tau\tpts\tsr\twhy\tlen\tcrc")
        selected = {id(record) for record in records}
        for index, session_records in enumerate(sessions, 1):
            for record in session_records:
                if id(record) in selected:
                    print("\t".join([str(index)] + [str(record[k]) for k in ("seq",) + RECORD_KEYS[1:]]))
    if args.output:
        if args.order == "pts":
            records = by_presentation_time(records)
        args.output.write_bytes(rpu_bin(records))
    for index, session_records in enumerate(sessions, 1):
        last = session_records[-1]["seq"] if session_records else "none"
        print(f"session {index}: last seq {last}; a record lost after it cannot be detected", file=sys.stderr)
    print(f"{len(records)} records from {len(sessions)} session(s), {len(problems)} problem(s)"
          + (f", wrote {args.output}" if args.output else ""), file=sys.stderr)
    return 1 if args.strict and problems else 0


if __name__ == "__main__":
    sys.exit(main())
