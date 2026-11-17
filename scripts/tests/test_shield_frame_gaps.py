import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "shield_frame_gaps.py"
spec = importlib.util.spec_from_file_location("shield_frame_gaps", SCRIPT)
fg = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fg)

PKG = "com.nuvio.tv.test.shield"
SERIAL = "10.99.88.77:5555"
LAYER = f"SurfaceView - {PKG}/com.nuvio.tv.MainActivity#0"
VSYNC = 16_666_667          # 60 Hz display
FRAME = 41_708_333          # 23.976 fps content
LATENCY = 2 * VSYNC         # usual desired -> actual


def frames(start_ns, count, step=FRAME, late=None, skip=()):
    """(desired, actual, ready) rows; late maps a frame index to extra vsyncs, skip drops indices."""
    rows = []
    for i in range(count):
        if i in skip:
            continue
        d = start_ns + i * step
        extra = (late or {}).get(i, 0) * VSYNC
        rows.append((d, d + LATENCY + extra, d - 5_000_000))
    return rows


def dump_text(rows, period=VSYNC):
    return "\n".join([str(period)] + [f"{d}\t{a}\t{r}" for d, a, r in rows] + ["0\t0\t0", ""])


def mem_text(uptime, avail_kb, refault, direct):
    return (f"{uptime:.2f} 1000.00\nMemTotal: 2900000 kB\nMemAvailable: {avail_kb} kB\nSwapFree: 200000 kB\n"
            f"allocstall_normal {direct}\nallocstall_movable 1\npgscan_direct {direct * 10}\n"
            f"workingset_refault {refault}\npgmajfault 3\n")


class ParseTest(unittest.TestCase):
    def test_latency_drops_empty_and_pending_rows_and_reads_the_refresh_period(self):
        text = f"{VSYNC}\n100\t200\t50\n0\t0\t0\n300\t{fg.INF}\t250\n400\t500\t350\n"
        period, rows = fg.parse_latency(text)
        self.assertEqual(VSYNC, period)
        self.assertEqual([(100, 200, 50), (400, 500, 350)], rows)

    def test_layer_match_is_anchored_on_the_package_and_the_slash(self):
        listing = "\n".join([
            "SurfaceView - com.nuvio.tv.test/com.nuvio.tv.MainActivity#0",
            f"Background for SurfaceView - {PKG}/com.nuvio.tv.MainActivity#0",
            f"SurfaceView - {PKG}/com.nuvio.tv.MainActivity#0",
            f"SurfaceView - {PKG}/com.nuvio.tv.MainActivity#1",
        ])
        self.assertEqual(f"SurfaceView - {PKG}/com.nuvio.tv.MainActivity#1", fg.pick_layer(listing, PKG))
        self.assertEqual("SurfaceView - com.nuvio.tv.test/com.nuvio.tv.MainActivity#0",
                         fg.pick_layer(listing, "com.nuvio.tv.test"))
        self.assertIsNone(fg.pick_layer(listing, "com.other"))

    def test_mem_sample_sums_allocstall_zones(self):
        sample = fg.parse_mem(mem_text(12.5, 400000, 7, 2))
        self.assertEqual(12.5, sample["uptime"])
        self.assertEqual(400000, sample["MemAvailable"])
        self.assertEqual(3, sample["allocstall"])
        self.assertEqual(20, sample["pgscan_direct"])


class ClassifyTest(unittest.TestCase):
    def test_regular_content_below_the_refresh_rate_is_not_a_skip(self):
        rows, gaps = fg.merge_dumps([{"rows": frames(10**12, 200)}], 0)
        events, period, _ = fg.classify(rows, gaps, VSYNC)
        self.assertEqual([], events)
        self.assertAlmostEqual(FRAME, period, delta=1)

    def test_skip_late_present_and_stall_are_told_apart(self):
        start = 10**12
        rows = frames(start, 100, skip={20, 21}, late={50: 1, 51: 1, 80: 2})
        rows += frames(start + 99 * FRAME + 2 * 10**9, 50)
        merged, gaps = fg.merge_dumps([{"rows": rows}], 0)
        events, _, _ = fg.classify(merged, gaps, VSYNC)
        kinds = [(e["kind"], e.get("frames")) for e in events]
        self.assertEqual([("app_skip", 2), ("late_present", 1), ("late_present", 2), ("stall", None)], kinds)

    def test_a_late_frame_that_ends_a_skip_belongs_to_the_skip(self):
        rows = frames(10**12, 60, skip={20}, late={21: 2, 40: 1})
        merged, gaps = fg.merge_dumps([{"rows": rows}], 0)
        events, _, _ = fg.classify(merged, gaps, VSYNC)
        self.assertEqual([("app_skip", 1), ("late_present", 1)], [(e["kind"], e["frames"]) for e in events])

    def test_a_dump_with_no_frame_after_the_start_is_not_a_gap(self):
        rows = frames(10**12, 100)
        start = rows[59][0]
        first = [r for r in rows if r[0] <= start]
        later = [r for r in rows if r[0] > rows[20][0]]
        _, gaps = fg.merge_dumps([{"rows": first}, {"rows": later}], start)
        self.assertEqual([], gaps)

    def test_frames_from_before_the_start_are_dropped(self):
        rows = frames(10**12, 100)
        start = rows[59][0]
        merged, _ = fg.merge_dumps([{"rows": rows}], start)
        self.assertEqual(40, len(merged))
        self.assertTrue(min(merged) > start)

    def test_dumps_that_do_not_overlap_are_a_coverage_gap_and_hide_events(self):
        first = frames(10**12, 50)
        second = frames(10**12 + 80 * FRAME, 50)
        merged, gaps = fg.merge_dumps([{"rows": first}, {"rows": second}], 0)
        self.assertEqual(1, len(gaps))
        self.assertEqual("dumps do not overlap", gaps[0][2])
        events, _, _ = fg.classify(merged, gaps, VSYNC)
        self.assertEqual([], events)

    def test_overlapping_dumps_have_no_gap_and_an_empty_dump_is_one(self):
        a = frames(10**12, 50)
        b = frames(10**12 + 30 * FRAME, 50)
        c = frames(10**12 + 70 * FRAME, 50)
        _, gaps = fg.merge_dumps([{"rows": a}, {"rows": b}], 0)
        self.assertEqual([], gaps)
        _, gaps = fg.merge_dumps([{"rows": a}, {"rows": [], "why": "empty dump"}, {"rows": c}], 0)
        self.assertEqual(["empty dump"], [g[2] for g in gaps])


class DumpIntervalTest(unittest.TestCase):
    def test_a_stall_in_the_history_does_not_stretch_the_interval(self):
        step = VSYNC  # 60 fps content: 511 frames hold 8.5 s
        before = frames(10**12, 255, step=step)
        after = frames(10**12 + 255 * step + 30 * 10**9, 256, step=step)
        device = FakeDevice([dump_text(before + after)])
        meta = {"dumps": [], "layers": [LAYER]}
        _, every = fg.dump(device, Path(tempfile.mkdtemp()), meta, LAYER, PKG, 0, 2.0, 8.0)
        self.assertLessEqual(every, 0.4 * 510 * step / 1e9 + 0.01)


class KillTest(unittest.TestCase):
    def test_lmkd_only_when_an_lmkd_line_names_the_pid(self):
        lines = [
            "  100.000   700   700 I lowmemorykiller: Kill 'com.a' (1111), uid 10050, oom_adj 900 to free 50000kB",
            "  100.100   600   600 I Zygote  : Process 1111 exited due to signal 9 (Killed)",
            "  101.000  1500  1600 I ActivityManager: Killing 2222:com.b/u0a51 (adj 985): empty #17",
            "  101.050   600   600 I Zygote  : Process 2222 exited due to signal 9 (Killed)",
            "  102.000   600   600 I Zygote  : Process 3333 exited due to signal 9 (Killed)",
            "  102.100   700   700 I lowmemorykiller: Kill 'com.c' (4444), uid 10052, oom_adj 900 to free 1kB",
        ]
        out = fg.kills(fg.parse_logcat(lines))
        self.assertEqual([(1111, "lmkd"), (2222, "activity-manager"), (3333, "unknown")],
                         [(k["pid"], k["killer"]) for k in out])


class FakeStream:
    def __init__(self, path, lines, alive):
        Path(path).write_text("".join(line + "\n" for line in lines))
        self.still = alive

    def stop(self):
        return self.still


class FakeDevice:
    """Answers the commands capture sends; records them; plays one dump per --latency call."""

    def __init__(self, dumps, product="mdarcy", listing=LAYER, logs=(), log_alive=True, mono_offset=5.0):
        self.dumps = list(dumps)
        self.product = product
        self.listing = listing
        self.logs = list(logs)
        self.log_alive = log_alive
        self.mono_offset = mono_offset
        self.sent = []
        self.uptime = 1000.0
        self.serial = SERIAL

    def out(self, command, ok_codes=(0,)):
        if fg.NEVER_RE.search(command):
            raise fg.Refused(f"refused to send: {command}")
        self.sent.append(command)
        self.uptime += 0.01
        if command == "getprop ro.product.device":
            return self.product + "\n"
        if command.startswith("logcat -d -v threadtime"):
            return ""
        if command.startswith("logcat -d -v monotonic"):
            return f"  {self.uptime + self.mono_offset - 1:.3f}   10   10 I adbd    : hello\n"
        if command == "dumpsys SurfaceFlinger --list":
            return self.listing + "\n"
        if command.startswith("pidof"):
            return "4242\n"
        if command == "cat /proc/uptime":
            return f"{self.uptime:.2f} 9.00\n"
        if command == "cat /proc/timer_list":
            return f"Timer List Version: v0.8\nnow at {int((self.uptime + self.mono_offset) * 1e9)} nsecs\n"
        if command.startswith("cat /proc/uptime /proc/meminfo"):
            return mem_text(self.uptime, 400000, int(self.uptime * 10), 0)
        if command.startswith("dumpsys SurfaceFlinger --latency"):
            return self.dumps.pop(0) if len(self.dumps) > 1 else self.dumps[0]
        raise AssertionError(f"unexpected command {command}")

    def log_stream(self, path):
        return FakeStream(path, self.logs, self.log_alive)


class FakeClock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t

    def sleep(self, s):
        self.t += s


def run_capture(device, seconds=10.0):
    tmp = Path(tempfile.mkdtemp())
    clock = FakeClock()
    fg.capture(device, tmp, PKG, seconds, mem_every=2.5, clock=clock, sleep=clock.sleep)
    return tmp


class CaptureTest(unittest.TestCase):
    def test_capture_is_read_only_names_no_serial_and_analyzes(self):
        start = 10**12
        timeline = frames(start, 400, skip={90}, late={160: 1})
        # Each dump is SurfaceFlinger's last 60 frames, 20 frames after the one before.
        dumps = [dump_text([r for r in timeline if start + i * 20 * FRAME <= r[0] < start + (i * 20 + 60) * FRAME])
                 for i in range(12)]
        device = FakeDevice(dumps)
        cap = run_capture(device)
        for command in device.sent:
            self.assertRegex(command, r"^(getprop ro\.product\.device|logcat -d |dumpsys SurfaceFlinger --(list|latency) |"
                                      r"dumpsys SurfaceFlinger --list$|pidof |cat /proc/)")
        for path in cap.iterdir():
            self.assertNotIn(SERIAL.split(":")[0], path.read_text(errors="replace"))
        result = fg.analyze(cap)
        self.assertTrue(result["gate"]["app_skip"])
        self.assertTrue(result["gate"]["late_present"])
        self.assertEqual(0, result["gate"]["coverage_gaps"])
        self.assertTrue(json.loads((cap / "meta.json").read_text())["log_clock_ok"])

    def test_refuses_a_box_that_is_not_the_shield_and_a_missing_layer(self):
        with self.assertRaises(fg.Refused):
            run_capture(FakeDevice([dump_text(frames(10**12, 10))], product="foster"))
        with self.assertRaises(fg.Refused):
            run_capture(FakeDevice([dump_text(frames(10**12, 10))], listing="SurfaceView - com.other/x#0"))

    def test_a_logcat_that_ended_early_is_a_coverage_gap(self):
        cap = run_capture(FakeDevice([dump_text(frames(10**12, 400))], log_alive=False))
        result = fg.analyze(cap)
        self.assertTrue(any("logcat ended early" in g["why"] for g in result["coverage_gaps"]))

    def test_a_capture_cut_short_still_has_a_meta_file_to_analyze(self):
        start = 10**12
        timeline = frames(start, 400, skip={150})
        dumps = [dump_text([r for r in timeline if start + i * 20 * FRAME <= r[0] < start + (i * 20 + 60) * FRAME])
                 for i in range(12)]
        device = FakeDevice(dumps)
        calls = {"n": 0}
        real_out = device.out

        def dies(command, ok_codes=(0,)):
            if command.startswith("dumpsys SurfaceFlinger --latency"):
                calls["n"] += 1
                if calls["n"] > 8:
                    raise fg.DeviceStop("adb did not answer")
            return real_out(command, ok_codes)

        device.out = dies
        tmp = Path(tempfile.mkdtemp())
        clock = FakeClock()
        with self.assertRaises(fg.DeviceStop):
            fg.capture(device, tmp, PKG, 60.0, clock=clock, sleep=clock.sleep)
        meta = json.loads((tmp / "meta.json").read_text())
        self.assertFalse(meta["complete"])
        result = fg.analyze(tmp)
        self.assertEqual(1, result["counts"]["app_skip"])
        self.assertTrue(any("cut short" in w for w in result["warnings"]))

    def test_watchdog_in_the_capture_log_stops(self):
        device = FakeDevice([dump_text(frames(10**12, 400))],
                            logs=["  1005.000   900   900 E Watchdog: Blocked in handler"])
        with self.assertRaises(fg.DeviceStop):
            run_capture(device)


class AnalyzeClockTest(unittest.TestCase):
    def test_memory_samples_are_shifted_onto_the_monotonic_clock(self):
        cap = Path(tempfile.mkdtemp())
        offset = 3600.0  # the box slept an hour since boot: uptime runs ahead of CLOCK_MONOTONIC
        start = 500 * 10**9
        rows = frames(start, 200, skip={100})
        (cap / "lat_0000.txt").write_text(dump_text(rows))
        skip_t = (start + 101 * FRAME) / 1e9
        samples = []
        for k in range(6):
            mono = skip_t - 5 + 2.5 * k
            refault = 1000 * k + (50_000 if mono > skip_t else 0)
            samples.append(fg.parse_mem(mem_text(mono + offset, 400000, refault, 0)))
        (cap / "mem.jsonl").write_text("".join(json.dumps(s) + "\n" for s in samples))
        (cap / "meta.json").write_text(json.dumps({
            "package": PKG, "layer": LAYER, "pid": 4242, "start_ns": start - 1, "mono_minus_uptime_s": -offset,
            "dumps": [{"file": "lat_0000.txt"}], "end_ns": rows[-1][0]}))
        result = fg.analyze(cap)
        skips = [e for e in result["events"] if e["kind"] == "app_skip"]
        self.assertEqual(1, len(skips))
        self.assertGreaterEqual(skips[0]["vm"]["workingset_refault"], 50_000)
        self.assertEqual(199, result["frames"])


if __name__ == "__main__":
    unittest.main()
