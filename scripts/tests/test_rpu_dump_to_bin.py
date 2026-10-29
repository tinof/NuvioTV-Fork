import contextlib
import importlib.util
import io
import shutil
import subprocess
import tempfile
import unittest
import zlib
from pathlib import Path

spec = importlib.util.spec_from_file_location("rpu_dump_to_bin", Path(__file__).parents[1] / "rpu_dump_to_bin.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

ROOT = Path(__file__).parents[2]
# Written by RpuDumpFixtureTest's tap from this RPU.bin (see app/src/test/resources/rpu_dump/README.md).
LOGCAT = Path(__file__).parent / "fixtures" / "rpu_dump_p81_v29.logcat"
RPU_BIN = ROOT / "app" / "src" / "test" / "resources" / "rpu_dump" / "p81_v29_shots.RPU.bin"


def records_of(rpu_bin):
    return [b"\x00\x00\x00\x01" + record for record in rpu_bin.split(b"\x00\x00\x00\x01")[1:]]


def record_lines(seq, nal, chunk, gen=0, au=None, sr="1"):
    """Dump lines for one NAL, split into parts of `chunk` bytes, as RpuDumpFormat writes them."""
    parts = [nal[i:i + chunk] for i in range(0, len(nal), chunk)]
    head = (f"v1 seq={seq} trk=1 gen={gen} au={seq if au is None else au} pts={seq * 41708} sr={sr} why=start "
            f"len={len(nal)} crc={zlib.crc32(nal):08x}")
    return [f"{head} part={i}/{len(parts)} {part.hex()}" for i, part in enumerate(parts, 1)]


class RpuDumpToBinTest(unittest.TestCase):
    def run_main(self, text, *args):
        with tempfile.TemporaryDirectory() as root:
            source = Path(root) / "dump.logcat"
            source.write_text(text)
            out = Path(root) / "RPU.bin"
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr), contextlib.redirect_stdout(io.StringIO()):
                code = module.main([str(source), "-o", str(out), *args])
            return code, (out.read_bytes() if out.exists() else None), stderr.getvalue()

    def test_fixture_round_trip_is_byte_identical(self):
        code, data, _ = self.run_main(LOGCAT.read_text(), "--strict")
        self.assertEqual(0, code)
        self.assertEqual(RPU_BIN.read_bytes(), data)

    def test_fixture_matches_the_rpu_builder_copy(self):
        self.assertEqual((ROOT / "native" / "rpu_builder" / "tests-fixtures" / "p81_v29_shots.RPU.bin").read_bytes(),
                         RPU_BIN.read_bytes())

    def test_refresh_only_keeps_the_shot_starts(self):
        code, data, _ = self.run_main(LOGCAT.read_text(), "--refresh-only", "--strict")
        self.assertEqual(0, code)
        records = records_of(RPU_BIN.read_bytes())
        self.assertEqual(b"".join(records[i] for i in (0, 3, 5)), data)

    def test_pts_order_sorts_by_presentation_time(self):
        # Three records queued in decode order 0 2 1 by presentation time: --order pts writes 0 1 2.
        records = records_of(RPU_BIN.read_bytes())[:3]
        lines = []
        for seq, (record, pts) in enumerate(zip(records, (0, 2, 1))):
            nal = b"\x7c\x01" + record[4:]
            lines += [line.replace(f"pts={seq * 41708}", f"pts={pts * 41708}") for line in record_lines(seq, nal, 64)]
        code, data, _ = self.run_main("\n".join(lines), "--order", "pts", "--strict")
        self.assertEqual(0, code)
        self.assertEqual(records[0] + records[2] + records[1], data)
        code, data, _ = self.run_main("\n".join(lines), "--strict")
        self.assertEqual(b"".join(records), data)

    def test_pts_order_stops_at_the_first_gap(self):
        # Queued 0 1 2 then the anchor 5, whose B-frames 3 and 4 were never queued: 5 is left out.
        records = records_of(RPU_BIN.read_bytes())[:4]
        lines = []
        for seq, (record, pts) in enumerate(zip(records, (0, 1, 2, 5))):
            nal = b"\x7c\x01" + record[4:]
            lines += [line.replace(f"pts={seq * 41708}", f"pts={pts * 41708}") for line in record_lines(seq, nal, 64)]
        code, data, stderr = self.run_main("\n".join(lines), "--order", "pts", "--strict")
        self.assertEqual(0, code)
        self.assertEqual(b"".join(records[:3]), data)
        self.assertIn("1 record(s) after it are left out", stderr)

    def test_dropped_line_is_a_gap(self):
        lines = LOGCAT.read_text().splitlines()
        dropped = [line for line in lines if "seq=2 " not in line]
        self.assertEqual(len(lines) - 1, len(dropped))
        code, data, stderr = self.run_main("\n".join(dropped), "--strict")
        self.assertEqual(1, code)
        self.assertIn("missing 2", stderr)
        records = records_of(RPU_BIN.read_bytes())
        self.assertEqual(b"".join(r for i, r in enumerate(records) if i != 2), data)
        code, _, _ = self.run_main("\n".join(dropped))
        self.assertEqual(0, code)

    def test_dropped_final_record_is_reported_by_its_last_seq(self):
        # Tail loss leaves no gap: --strict passes, and the summary names the last seq received.
        lines = LOGCAT.read_text().splitlines()
        dropped = [line for line in lines if "seq=6 " not in line]
        code, data, stderr = self.run_main("\n".join(dropped), "--strict")
        self.assertEqual(0, code)
        self.assertIn("session 1: last seq 5;", stderr)
        self.assertEqual(b"".join(records_of(RPU_BIN.read_bytes())[:6]), data)
        _, _, stderr = self.run_main(LOGCAT.read_text(), "--strict")
        self.assertIn("session 1: last seq 6;", stderr)

    def test_parts_are_joined_and_a_missing_part_fails(self):
        nal = b"\x7c\x01\x19\x08\x09" + bytes(range(256)) * 12 + b"\x80"
        lines = record_lines(0, nal, 1500) + record_lines(1, nal[:40], 1500)
        self.assertEqual(4, len(lines))
        code, data, _ = self.run_main("\n".join(lines), "--strict")
        self.assertEqual(0, code)
        self.assertEqual(b"\x00\x00\x00\x01" + nal[2:] + b"\x00\x00\x00\x01" + nal[2:40], data)
        code, data, stderr = self.run_main("\n".join(lines[:1] + lines[2:]), "--strict")
        self.assertEqual(1, code)
        self.assertIn("seq 0 incomplete, parts [1, 3] of 3", stderr)
        self.assertEqual(b"\x00\x00\x00\x01" + nal[2:40], data)

    def test_bad_crc_length_and_header_are_rejected(self):
        nal = b"\x7c\x01\x19\x08\x09" + bytes(40) + b"\x80"
        good = record_lines(0, nal, 1500)[0]
        crc = good.replace(f"crc={zlib.crc32(nal):08x}", "crc=00000000")
        code, _, stderr = self.run_main(crc + "\n" + record_lines(1, nal, 1500)[0], "--strict")
        self.assertEqual(1, code)
        self.assertIn("seq 0 CRC mismatch", stderr)
        length = good.replace(f"len={len(nal)} ", f"len={len(nal) + 1} ")
        self.assertNotEqual(good, length)
        code, _, stderr = self.run_main(length + "\n" + record_lines(1, nal, 1500)[0], "--strict")
        self.assertEqual(1, code)
        self.assertIn(f"seq 0 length {len(nal)} != len={len(nal) + 1}", stderr)
        self.assertNotIn("missing", stderr)
        self.assertNotIn("missing", stderr)
        no_header = b"\x00\x01" + nal[2:]
        code, data, stderr = self.run_main(record_lines(0, no_header, 1500)[0], "--strict")
        self.assertEqual(2, code)                     # nothing left to write
        self.assertIsNone(data)
        self.assertIn("is not 7c01", stderr)

    def test_raw_and_brief_formats_and_sessions(self):
        nal = b"\x7c\x01\x19\x08\x09" + bytes(30) + b"\x80"
        first = record_lines(0, nal, 1500, gen=0) + record_lines(1, nal, 1500, gen=1, sr="0")
        second = record_lines(0, nal[:30], 1500)
        text = "\n".join(
            ["v1 gen=0 trk=1 event=seek seekUs=0 n=2 codecs=dvhe.08.06", first[0]]
            + [f"I/NuvioRpuDump( 4321): {first[1]}", f"D/Other( 1): {first[0]}"]
            + [f"10-23 21:14:02.101  4321  4412 I NuvioRpuDump: {second[0]}"]
        )
        sessions, problems = module.parse(text)
        self.assertEqual([], problems)
        self.assertEqual([2, 1], [len(s) for s in sessions])
        _, data, _ = self.run_main(text, "--session", "1", "--gen", "1")
        self.assertEqual(b"\x00\x00\x00\x01" + nal[2:], data)
        _, data, _ = self.run_main(text, "--session", "2")
        self.assertEqual(b"\x00\x00\x00\x01" + nal[2:30], data)

    def test_color_capture_parses(self):
        colored = "\n".join(f"\x1b[38;5;40m{line}\x1b[0m" for line in LOGCAT.read_text().splitlines())
        code, data, _ = self.run_main(colored, "--strict")
        self.assertEqual(0, code)
        self.assertEqual(RPU_BIN.read_bytes(), data)

    def test_dovi_tool_reads_the_rebuilt_file(self):
        dovi_tool = shutil.which("dovi_tool")
        if dovi_tool is None:
            self.skipTest("dovi_tool is not on PATH")
        with tempfile.TemporaryDirectory() as root:
            out = Path(root) / "RPU.bin"
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(0, module.main([str(LOGCAT), "-o", str(out), "--strict"]))
            for frame in range(7):
                result = subprocess.run([dovi_tool, "info", "-i", str(out), "-f", str(frame)],
                                        capture_output=True, text=True, check=False)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("dovi_profile", result.stdout)


if __name__ == "__main__":
    unittest.main()
