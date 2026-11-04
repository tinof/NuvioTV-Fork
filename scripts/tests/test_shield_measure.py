import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import types
import unittest
from unittest import mock
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "shield_measure.py"
spec = importlib.util.spec_from_file_location("shield_measure", SCRIPT)
sm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sm)
FAKE_ADB = Path(__file__).with_name("fake_adb.py")
SERIAL = "10.99.88.77:5555"
SHIELD = "com.nuvio.tv.test.shield"
DAILY = "com.nuvio.tv.test"
NEVER = re.compile(r"\bdumpsys meminfo\b|\bpm clear\b|\bcompile\b|\binstall\b|\buninstall\b")

GFXINFO = """Applications Graphics Acceleration Info:
** Graphics info for pid 4242 [com.nuvio.tv.test.shield] **
Stats since: 1234ns
Total frames rendered: 200
Janky frames: 15 (7.50%)
50th percentile: 9ms
90th percentile: 17ms
95th percentile: 25ms
99th percentile: 61ms
Number Missed Vsync: 3
Number High input latency: 0
Number Slow UI thread: 8
Number Slow bitmap uploads: 0
Number Slow issue draw commands: 2
Number Frame deadline missed: 12
"""

FRAMESTATS = """---PROFILEDATA---
Flags,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,
0,1000000000,1000000000,0,0,0,0,0,0,0,0,0,0,1010000000,
0,1016000000,1016000000,0,0,0,0,0,0,0,0,0,0,1046000000,
1,1032000000,1032000000,0,0,0,0,0,0,0,0,0,0,1300000000,
0,1048000000,1048000000,0,0,0,0,0,0,0,0,0,0,1056000000,
---PROFILEDATA---
"""


def log(tag, message, level="I"):
    return f"11-03 12:00:01.000  4242  4242 {level} {tag}: {message}"


PLAYBACK_LINES = [
    log("TTFF_STAGE", "PLAYBACK_STARTED: clickToPlayingMs=2840 firstFrameToPlayingMs=610 "
        "afrTrackHeld=true pausedAtFirstFrame=false seq=1"),
    log("PlayerViewModel", "SEEK_READY: ms=88 targetMs=130000 via=ready"),
    log("NuvioExoEvent", "EXO_EVENT: name=dropped_video_frames elapsedMs=73180 host=streams.example.org "
        "engine=EXOPLAYER state=READY positionMs=1 bufferedMs=2 details=count=2,total=2,elapsedMs=10", "W"),
    log("NuvioExoEvent", "EXO_EVENT: name=dropped_video_frames elapsedMs=73190 host=streams.example.org "
        "engine=EXOPLAYER state=READY positionMs=1 bufferedMs=2 details=count=1,total=3,elapsedMs=10", "W"),
    log("IecPassthrough", "iec_health mode=TRUEHD payload=61440 underruns=0 head=1 written=2 pending=0"),
    log("IecPassthrough", "iec_health mode=TRUEHD payload=61440 underruns=2 head=1 written=2 pending=0"),
    log("AudioTrack", "restartIfDisabled(904): releaseBuffer() track 0x21 disabled due to previous underrun", "W"),
    log("NuvioPosFreeze", "FREEZE_BEGIN stallMs=900 positionMs=1 bufferedMs=2 state=READY"),
    log("NuvioPosFreeze", "FREEZE_END stallMs=1200 positionMs=1"),
]


class ParserTest(unittest.TestCase):
    def test_gfxinfo_summary(self):
        g = sm.parse_gfxinfo(GFXINFO)
        self.assertEqual(g["frames"], 200)
        self.assertEqual(g["janky"], 15)
        self.assertEqual(g["janky_pct"], 7.5)
        self.assertEqual((g["p50_ms"], g["p90_ms"], g["p99_ms"]), (9, 17, 61))
        self.assertEqual((g["missed_vsync"], g["slow_ui"], g["deadline_missed"]), (3, 8, 12))

    def test_gfxinfo_without_frames_is_none(self):
        self.assertIsNone(sm.parse_gfxinfo("No process found for: com.nuvio.tv.test.shield\n"))

    def test_framestats_skips_flagged_frames(self):
        f = sm.parse_framestats(FRAMESTATS)
        self.assertEqual(f["frames"], 3)
        self.assertEqual(f["p50_ms"], 10.0)
        self.assertEqual(f["p99_ms"], 30.0)
        self.assertEqual(f["over_16ms"], 1)

    def test_am_start(self):
        a = sm.parse_am_start("Status: ok\nLaunchState: COLD\nTotalTime: 1834\nWaitTime: 1840\nComplete\n")
        self.assertEqual(a, {"status": "ok", "launch_state": "COLD", "total_ms": 1834, "wait_ms": 1840})

    def test_playback(self):
        p = sm.parse_playback(PLAYBACK_LINES)
        self.assertEqual(p["playback_started"], [{"click_to_playing_ms": 2840, "first_frame_to_playing_ms": 610,
                                                  "afr_track_held": True, "paused_at_first_frame": False, "seq": 1}])
        self.assertEqual(p["seek_ready"], [{"ms": 88, "via": "ready"}])
        self.assertEqual(p["dropped_frames"], 3)
        self.assertEqual(p["iec_underruns_max"], 2)
        self.assertEqual(p["restart_if_disabled"], 1)
        self.assertEqual(p["freezes"], 1)

    def test_playback_keeps_the_app_key_names(self):
        # PlaybackStartedStage.line() names these keys for this parser; a rename breaks M1.4.
        kotlin = (Path(__file__).parents[2] / "app/src/main/java/com/nuvio/tv/ui/screens/player/"
                  "PlaybackStartedStage.kt").read_text()
        for key in ("PLAYBACK_STARTED: clickToPlayingMs=", "firstFrameToPlayingMs=", "afrTrackHeld=",
                    "pausedAtFirstFrame=", "seq="):
            self.assertIn(key, kotlin)

    def test_details_stages_first_time_wins(self):
        lines = [log("NuvioDetails", "journey=1 stage=home_selection elapsedMs=0.0 thread=main"),
                 log("NuvioDetails", "journey=1 stage=controls_focus elapsedMs=812.5 thread=main"),
                 log("NuvioDetails", "journey=1 stage=controls_focus elapsedMs=900.0 thread=main"),
                 log("NuvioDetails", "journey=1 frame=3 totalNs=1 layoutNs=1 drawNs=1 syncNs=1 dropped=0")]
        self.assertEqual(sm.parse_details(lines), {"1": {"home_selection": 0.0, "controls_focus": 812.5}})

    def test_jank_lines(self):
        j = sm.parse_jank([log("JankStats", "JANK: 1030ms | states: []", "W"),
                           log("JankStats", "JANK: 40ms | states: [Screen: home]", "W")])
        self.assertEqual(j, {"lines": 2, "max_ms": 1030, "median_ms": 535.0})

    def test_health_and_scrub(self):
        self.assertTrue(sm.unhealthy([log("Watchdog", "*** WATCHDOG KILLING SYSTEM PROCESS", "W")]))
        self.assertFalse(sm.unhealthy([log("Looper", "com.android.server.Watchdog$HandlerChecker slow")]))
        line = sm.scrub("host=streams.example.org ip 10.99.88.77/24", SERIAL)
        self.assertNotIn("example.org", line)
        self.assertNotIn("10.99.88.77", line)


LIVE_WRITER = """import os, sys, time
sys.stdout.write("11-03 12:00:01.000  4242  4242 I JankStats: one\\n"); sys.stdout.flush()
open(os.environ["LS_READY"], "w").close()
open(os.environ["LS_GO"]).read()
sys.stdout.write("11-03 12:00:01.000  4242  4242 I JankStats: two\\n"); sys.stdout.flush()
open(os.environ["LS_DONE"], "w").close()
time.sleep(60)
"""

FAILING_LOGCAT = """import sys
sys.stderr.write("logcat: Unable to open log device: logd is not running\\n")
sys.exit(1)
"""


def wait_for(path):
    deadline = time.monotonic() + 10
    while not path.exists():
        if time.monotonic() > deadline:
            raise AssertionError(f"{path.name} never appeared")
        time.sleep(0.01)


class LogStreamTest(unittest.TestCase):
    """LogStream with a scripted `adb logcat` child, synchronised by files and a FIFO, never by timing."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def device(self, body):
        child = self.root / "child.py"
        child.write_text(body)
        shim = self.root / "adb"
        shim.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{child}" "$@"\n')
        shim.chmod(0o755)
        return types.SimpleNamespace(adb=str(shim), serial=SERIAL)

    def test_a_line_written_while_lines_reads_keeps_the_earlier_ones(self):
        ready, go, done = self.root / "ready", self.root / "go", self.root / "done"
        os.mkfifo(go)
        env = {"LS_READY": str(ready), "LS_GO": str(go), "LS_DONE": str(done)}
        with mock.patch.dict(os.environ, env):
            stream = sm.LogStream(self.device(LIVE_WRITER), "11-03 12:00:00.000", ("JankStats",))
        wait_for(ready)

        class WriteDuringRead:
            """The child writes its second line after lines() has positioned the file and before it reads."""

            def __init__(self, inner):
                self.inner, self.fired = inner, False

            def read(self, *a):
                if not self.fired:
                    self.fired = True
                    with open(go, "w") as fifo:
                        fifo.write("x")
                    wait_for(done)
                return self.inner.read(*a)

            def __getattr__(self, name):
                return getattr(self.inner, name)

        stream.file = WriteDuringRead(stream.file)
        try:
            got = [sm.logcat_message(line)[1] for line in stream.lines()]
        finally:
            stream.stop()
        self.assertEqual(got, ["one", "two"])

    def test_a_live_logcat_that_failed_fails_the_measurement(self):
        stream = sm.LogStream(self.device(FAILING_LOGCAT), "11-03 12:00:00.000", ("JankStats",))
        stream.proc.wait(timeout=10)
        with self.assertRaises(sm.Refused) as caught:
            stream.stop()
        self.assertIn("logd is not running", str(caught.exception))


def state(**extra):
    s = {
        "serial": SERIAL,
        "props": {"ro.product.device": "mdarcy"},
        "settings": {"global": {}, "secure": {}, "system": {}},
        "logcat": {"main": 256},
        "packages": {DAILY: 1533, SHIELD: 1533, "com.stremio.one": 100},
        "paths": {},
        "files": {},
        "foreground": SHIELD,
        "running": [SHIELD],
        "gfxinfo": GFXINFO,
        "framestats": FRAMESTATS,
        "logcat_lines": [],
    }
    s.update(extra)
    return s


class CommandTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.shim = self.root / "adb"
        self.shim.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{FAKE_ADB}" "$@"\n')
        self.shim.chmod(0o755)
        self.state_file = self.root / "state.json"
        self.log_file = self.root / "adb.log"
        self.session = self.root / "session"
        (self.session / "before").mkdir(parents=True)
        (self.session / "before" / "complete").write_text("")

    def tearDown(self):
        self.tmp.cleanup()

    def run_measure(self, *args, initial=None, timeout="5"):
        self.state_file.write_text(json.dumps(initial or state()))
        env = {"FAKE_ADB_STATE": str(self.state_file), "FAKE_ADB_LOG": str(self.log_file), "PATH": "/usr/bin:/bin"}
        proc = subprocess.run([sys.executable, str(SCRIPT), "--serial", SERIAL, "--adb", str(self.shim),
                               "--adb-timeout", timeout, "--pause-scale", "0", *args],
                              capture_output=True, text=True, env=env, timeout=60)
        self.calls = [json.loads(line) for line in self.log_file.read_text().splitlines()] if self.log_file.exists() else []
        self.final = json.loads(self.state_file.read_text())
        return proc

    def shell_calls(self):
        return [" ".join(c[3:]) for c in self.calls if c[2:3] == ["shell"]]

    def assert_clean(self, proc):
        out = proc.stdout + proc.stderr
        self.assertNotIn("10.99.88.77", out)
        for path in (self.session / "measure").glob("*") if (self.session / "measure").exists() else []:
            self.assertNotIn("10.99.88.77", path.read_text())
            self.assertNotIn("example.org", path.read_text())
        for call in self.shell_calls():
            self.assertIsNone(NEVER.search(call), call)
            if DAILY in call.split() or "com.stremio.one" in call:
                self.assertNotRegex(call, r"force-stop|setprop")

    def results(self, name):
        files = sorted((self.session / "measure").glob(f"{name}-*[0-9].json"))
        self.assertEqual(len(files), 1)
        return json.loads(files[0].read_text())

    def test_check_reads_only(self):
        proc = self.run_measure("check")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        report = json.loads(proc.stdout)
        self.assertEqual(report["foreground"], SHIELD)
        self.assertTrue(report["installed"][DAILY])
        self.assertTrue(all(c.split()[0] in ("getprop", "logcat", "dumpsys", "pm", "pidof") for c in self.shell_calls()))
        self.assert_clean(proc)

    def test_refuses_a_box_that_is_not_the_shield(self):
        proc = self.run_measure("check", initial=state(props={"ro.product.device": "sabrina"}))
        self.assertEqual(proc.returncode, 1)

    def test_stops_on_watchdog(self):
        lines = [log("Watchdog", "*** WATCHDOG KILLING SYSTEM PROCESS: Blocked in handler", "W")]
        proc = self.run_measure("check", initial=state(logcat_lines=lines))
        self.assertEqual(proc.returncode, 5)

    def test_stops_when_adb_hangs(self):
        proc = self.run_measure("check", initial=state(hang_on="getprop"), timeout="1")
        self.assertEqual(proc.returncode, 5)
        self.assertIn("did not answer", proc.stderr)
        self.assert_clean(proc)

    def test_needs_a_completed_snapshot(self):
        (self.session / "before" / "complete").unlink()
        proc = self.run_measure("ui", "--session", str(self.session), "--package", SHIELD)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("shield_snapshot.sh", proc.stderr)
        self.assertNotIn("input keyevent", " ".join(self.shell_calls()))

    def test_ui_all_journeys(self):
        proc = self.run_measure("ui", "--session", str(self.session), "--package", SHIELD)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        result = self.results("ui")
        self.assertEqual([r["journey"] for r in result["runs"]], list(sm.JOURNEYS))
        self.assertEqual(result["runs"][0]["gfxinfo"]["janky_pct"], 7.5)
        details = next(r for r in result["runs"] if r["journey"] == "details")
        self.assertIsNone(details["details_stages"])
        self.assertIn("details_stages", details["missing"])
        self.assertEqual(len(self.final["keys"]), sum(len(k) for k in sm.JOURNEYS.values()))
        self.assertIn(f"dumpsys gfxinfo {SHIELD} reset", self.shell_calls())
        self.assert_clean(proc)

    def test_ui_shots(self):
        proc = self.run_measure("ui", "--session", str(self.session), "--package", SHIELD, "--journey", "sidebar",
                                "--shots")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        shot = self.results("ui")["runs"][0]["shot"]
        self.assertEqual((self.session / "measure" / shot).read_text(), "png")
        self.assertEqual(self.final["files"], {})

    def test_ui_wakes_the_screensaver_once(self):
        proc = self.run_measure("ui", "--session", str(self.session), "--package", SHIELD, "--journey", "sidebar",
                                initial=state(dream=True))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(self.final["keys"][0], "KEYCODE_WAKEUP")
        self.assertEqual(self.final["keys"].count("KEYCODE_WAKEUP"), 1)

    def test_ui_refuses_when_the_app_is_not_in_front(self):
        proc = self.run_measure("ui", "--session", str(self.session), "--package", SHIELD,
                                initial=state(foreground="com.google.android.tvlauncher"))
        self.assertEqual(proc.returncode, 1)
        self.assertNotIn("keys", self.final)

    def test_details_trace_restores_the_property(self):
        lines = {"KEYCODE_DPAD_CENTER": [log("NuvioDetails", "journey=1 stage=controls_focus elapsedMs=812.5 thread=main")]}
        proc = self.run_measure("ui", "--session", str(self.session), "--package", SHIELD, "--journey", "details",
                                "--details-trace", initial=state(lines_on_key=lines))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(self.results("ui")["runs"][0]["details_stages"], {"1": {"controls_focus": 812.5}})
        self.assertEqual(self.final["props"].get(sm.DETAILS_PROP), "")
        self.assertIn(f"setprop {sm.DETAILS_PROP} VERBOSE", self.shell_calls())

    def test_details_trace_restores_the_value_before_a_killed_run(self):
        # A killed earlier run left VERBOSE; the session file keeps the value the first run found.
        (self.session / "measure").mkdir()
        (self.session / "measure" / "details-prop-before.txt").write_text("\n")
        initial = state()
        initial["props"][sm.DETAILS_PROP] = "VERBOSE"
        proc = self.run_measure("ui", "--session", str(self.session), "--package", SHIELD, "--journey", "sidebar",
                                "--details-trace", initial=initial)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(self.final["props"][sm.DETAILS_PROP], "")

    def test_details_trace_sends_nothing_after_a_device_stop(self):
        watchdog = [log("Watchdog", "*** WATCHDOG KILLING SYSTEM PROCESS: Blocked in handler", "W")]
        proc = self.run_measure("ui", "--session", str(self.session), "--package", SHIELD, "--journey", "sidebar",
                                "--details-trace", initial=state(lines_on_key={"KEYCODE_DPAD_LEFT": watchdog}))
        self.assertEqual(proc.returncode, 5, proc.stderr)
        props = [c for c in self.shell_calls() if c.startswith(f"setprop {sm.DETAILS_PROP}")]
        self.assertEqual(props, [f"setprop {sm.DETAILS_PROP} VERBOSE"])
        self.assertIn("details-prop-before.txt", proc.stderr)

    def test_details_trace_is_refused_on_a_daily_app(self):
        proc = self.run_measure("ui", "--session", str(self.session), "--package", DAILY, "--details-trace",
                                initial=state(foreground=DAILY))
        self.assertEqual(proc.returncode, 1)
        self.assert_clean(proc)

    def test_cold_start_skips_a_running_daily_app(self):
        proc = self.run_measure("start", "--session", str(self.session), "--package", DAILY, "--force-cold",
                                initial=state(running=[DAILY]))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        result = self.results("start")
        self.assertIsNone(result["cold"])
        self.assertIn("never force-stopped", result["missing"]["cold"])
        self.assertFalse(any(c.startswith("am ") for c in self.shell_calls()))
        self.assert_clean(proc)

    def test_cold_start_of_a_stopped_daily_app(self):
        proc = self.run_measure("start", "--session", str(self.session), "--package", DAILY,
                                initial=state(running=[]))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(self.results("start")["cold"]["total_ms"], 1834)
        self.assert_clean(proc)

    def test_force_cold_stops_a_test_build(self):
        proc = self.run_measure("start", "--session", str(self.session), "--package", SHIELD, "--force-cold")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn(f"am force-stop {SHIELD}", self.shell_calls())
        self.assertEqual(self.results("start")["cold"]["launch_state"], "COLD")

    def test_playback(self):
        proc = self.run_measure("playback", "--session", str(self.session), "--package", SHIELD, "--seeks", "2",
                                "--play", "0", initial=state(logcat_lines=PLAYBACK_LINES))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        result = self.results("playback")
        self.assertEqual(result["playback_started"][0]["click_to_playing_ms"], 2840)
        self.assertEqual(result["dropped_frames"], 3)
        self.assertEqual(result["missing"], {"seek_ready": "1 SEEK_READY lines for 2 seeks"})
        self.assertEqual(self.final["keys"], ["KEYCODE_DPAD_RIGHT"] * 2)
        self.assert_clean(proc)

    def test_playback_without_the_stage_says_why(self):
        proc = self.run_measure("playback", "--session", str(self.session), "--package", DAILY, "--wait-start", "0",
                                "--play", "0", initial=state(foreground=DAILY))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        missing = self.results("playback")["missing"]
        self.assertIn("upstream app does not log it", missing["playback_started"])
        self.assertIn("iec_underruns_max", missing)


if __name__ == "__main__":
    unittest.main()
