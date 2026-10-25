import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).parents[1]
FAKE_ADB = Path(__file__).with_name("fake_adb.py")
SERIAL = "10.99.88.77:5555"
SHIELD = "com.nuvio.tv.test.shield"
SHIELD_PATH = "/data/app/~~a1==/com.nuvio.tv.test.shield-b2==/base.apk"
LAUNCHER = "com.google.android.tvlauncher"
DAILY = re.compile(r"(?<![\w.])com\.nuvio\.tv\.test(?![\w.])|com\.stremio\.one")
FORBIDDEN = re.compile(r"\bpm clear\b|force-stop|\bcompile\b|\binstall\b|\buninstall\b|^am\b")


def default_state():
    return {
        "serial": SERIAL,
        "props": {
            "ro.product.device": "mdarcy",
            "ro.serialno": "1323219072345",
            "ro.build.display.id": "RQ1A.210105.003.7825230_4387.0822",
            "debug.nuvio.rpu_dump": "",
            "debug.nuvio.owner_fix": "1",
            "persist.vendor.sys.display.resolution": "1920x1080",
            "vendor.sys.tegra.refresh": "24.000",
        },
        # A multi-line value, as getprop prints three on the Shield; not a debug.nuvio one.
        "getprop_extra": ["[persist.sys.multiline]: [first", "second]"],
        "settings": {
            "global": {
                "battery_stats_constants": "track_cpu_times_by_proc_state=false",
                "device_name": "SHIELD Android TV",
                "hdmi_control_enabled": "1",
                "network_watchlist_last_report_time": "1792789200000",
                "zen_mode_config_etag": "46339828",
            },
            "secure": {"default_input_method": "com.google.android.leanback.ime/.LeanbackImeService"},
            "system": {"audio_output_mode": "0", "screen_off_timeout": "1800000"},
        },
        "logcat": {"main": 256, "system": 256, "crash": 256, "stats": 64},
        "display": {
            "active": 18,
            "default": 21,
            "modes": [[18, 3840, 2160, 24.000002], [19, 3840, 2160, 50.0], [21, 3840, 2160, 23.976025]],
        },
        "home_mode": 18,
        "foreground": LAUNCHER,
        "packages": {
            "com.google.android.gms": 264230117,
            "com.nuvio.tv.test": 1533,
            SHIELD: 1533,
            "com.stremio.one": 100,
        },
        "paths": {SHIELD: SHIELD_PATH},
        "files": {SHIELD_PATH: "apk built at HEAD"},
        "uptime": 226724.74,
        # The device logs its own address; the snapshot must not keep it.
        "logcat_lines": ["10-25 19:00:00.000 I ActivityManager: Start proc", "I wpa_supplicant: ip 10.99.88.77/24"],
    }


@unittest.skipUnless(shutil.which("gtimeout") or shutil.which("timeout"), "needs gtimeout or timeout")
class ShieldSnapshotRestoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.home = self.root / "home"
        self.home.mkdir()
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        shim = bin_dir / "adb"
        shim.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{FAKE_ADB}" "$@"\n')
        shim.chmod(0o755)
        self.state_file = self.root / "state.json"
        self.log_file = self.root / "adb.log"
        self.log_file.touch()
        self.write_state(default_state())
        self.env = {k: v for k, v in os.environ.items() if k != "ANDROID_SERIAL"}
        self.env.update(
            HOME=str(self.home),
            PATH=f"{bin_dir}:{os.environ['PATH']}",
            FAKE_ADB_STATE=str(self.state_file),
            FAKE_ADB_LOG=str(self.log_file),
            SHIELD_ADB_TIMEOUT="2",
            SHIELD_POLL_INTERVAL="0.1",
            SHIELD_POLL_TRIES="3",
        )
        self.outputs = []
        self.allow_input = False

    def tearDown(self):
        try:
            for argv in self.commands():
                if argv[:1] == ["connect"] or argv[2:3] == ["pull"]:
                    continue
                shell = " ".join(argv[3:])
                self.assertNotRegex(shell, FORBIDDEN)
                self.assertIsNone(DAILY.search(shell), shell)
                if shell.startswith("setprop"):
                    self.assertTrue(shell.startswith("setprop debug.nuvio."), shell)
                if not self.allow_input:
                    self.assertFalse(shell.startswith("input"), shell)
            host = SERIAL.split(":")[0]
            for text in self.outputs:
                self.assertNotIn(host, text)
            # Neither the address nor the raw hardware serial is ever kept, not even after a stop.
            for path in self.home.rglob("*"):
                if path.is_file():
                    text = path.read_text(errors="replace")
                    self.assertNotIn(host, text, str(path))
                    self.assertNotIn("1323219072345", text, str(path))
        finally:
            self.tmp.cleanup()

    def state(self):
        return json.loads(self.state_file.read_text())

    def write_state(self, state):
        self.state_file.write_text(json.dumps(state))

    def change(self, fn):
        state = self.state()
        fn(state)
        self.write_state(state)

    def commands(self):
        return [json.loads(line) for line in self.log_file.read_text().splitlines()]

    def shell_commands(self, since=0):
        return [" ".join(a[3:]) for a in self.commands()[since:] if a[2:3] == ["shell"]]

    def run_script(self, name, *args, serial=SERIAL):
        argv = ["bash", str(SCRIPTS / name)] + (["--serial", serial] if serial else []) + list(args)
        result = subprocess.run(argv, env=self.env, capture_output=True, text=True, timeout=120)
        self.outputs += [result.stdout, result.stderr]
        return result

    def snapshot(self, label="t", *args):
        result = self.run_script("shield_snapshot.sh", "--label", label, *args)
        self.assertEqual(0, result.returncode, result.stderr)
        return Path(re.search(r"^snapshot: (.+)$", result.stdout, re.M).group(1))

    def restore(self, *args, expect=0):
        result = self.run_script("shield_restore.sh", *args)
        self.assertEqual(expect, result.returncode, result.stdout + result.stderr)
        return result

    def test_round_trip_without_changes_is_clean(self):
        session = self.snapshot()
        self.assertTrue((session / "before" / "complete").is_file())
        self.assertEqual("debug.nuvio.owner_fix=1\n", (session / "before" / "debug-nuvio.props").read_text())
        self.assertIn("active_desc=3840x2160@24.000", (session / "before" / "display-mode.txt").read_text())
        self.restore()
        self.assertEqual("clean\n", (session / "restore" / "verdict").read_text())
        self.assertTrue((session / "after" / "meta").is_file())

    def test_changed_owner_prop_is_set_back(self):
        session = self.snapshot()
        self.change(lambda s: s["props"].update({"debug.nuvio.owner_fix": "0"}))
        self.restore("--session", str(session))
        self.assertEqual("1", self.state()["props"]["debug.nuvio.owner_fix"])
        self.assertIn("setprop debug.nuvio.owner_fix '1'", self.shell_commands())

    def test_props_unset_or_empty_before_are_cleared_with_empty_quotes(self):
        self.snapshot()
        self.change(lambda s: s["props"].update({"debug.nuvio.m21_probe": "1", "debug.nuvio.rpu_dump": "8"}))
        self.restore()
        commands = self.shell_commands()
        self.assertIn("setprop debug.nuvio.m21_probe ''", commands)
        self.assertIn("setprop debug.nuvio.rpu_dump ''", commands)
        self.assertEqual("", self.state()["props"]["debug.nuvio.m21_probe"])

    def test_unchanged_owner_prop_is_not_touched(self):
        self.snapshot()
        self.change(lambda s: s["props"].update({"debug.nuvio.m21_probe": "1"}))
        self.restore()
        self.assertFalse([c for c in self.shell_commands() if "owner_fix" in c])

    def test_unnamed_setting_is_reported_not_written(self):
        session = self.snapshot()
        self.change(lambda s: s["settings"]["system"].update({"screen_off_timeout": "600000"}))
        result = self.restore(expect=3)
        self.assertIn("DRIFT setting system/screen_off_timeout (changed)", result.stdout)
        self.assertFalse([c for c in self.shell_commands() if c.startswith("settings put")])
        self.assertEqual("drift\n", (session / "restore" / "verdict").read_text())

    def test_named_settings_are_written_back_exactly(self):
        session = self.snapshot()
        with open(session / "session-changes.txt", "a") as handle:
            handle.write("setting global/device_name\nraised logcat to 4M\n")

        def edit(state):
            state["settings"]["global"]["battery_stats_constants"] = "other"
            state["settings"]["global"]["device_name"] = "renamed"

        self.change(edit)
        result = self.restore("--setting", "global/battery_stats_constants")
        settings = self.state()["settings"]["global"]
        self.assertEqual("track_cpu_times_by_proc_state=false", settings["battery_stats_constants"])
        self.assertEqual("SHIELD Android TV", settings["device_name"])
        self.assertIn("settings put global device_name 'SHIELD Android TV'", self.shell_commands())
        self.assertIn("session note: raised logcat to 4M", result.stdout)

    def test_named_setting_absent_before_is_deleted(self):
        self.snapshot()
        self.change(lambda s: s["settings"]["secure"].update({"nuvio_fault_reject_mime": "audio/vnd.dts.hd"}))
        self.restore("--setting", "secure/nuvio_fault_reject_mime")
        self.assertIn("settings delete secure nuvio_fault_reject_mime", self.shell_commands())
        self.assertNotIn("nuvio_fault_reject_mime", self.state()["settings"]["secure"])

    def test_volatile_keys_are_ignored_and_listed(self):
        self.snapshot()
        self.change(lambda s: s.update({"tick_volatile": True}))
        result = self.restore()
        self.assertIn("IGNORED (volatile) global/zen_mode_config_etag", result.stdout)
        self.assertFalse([c for c in self.shell_commands() if c.startswith("settings put")])

    def test_local_volatile_patterns_are_used_and_announced(self):
        self.snapshot()
        (self.home / "shield-snapshots" / "volatile-keys.txt").write_text("system/screen_off_* # seen today\n")
        self.change(lambda s: s["settings"]["system"].update({"screen_off_timeout": "600000"}))
        result = self.restore()
        self.assertIn("using 1 local volatile patterns", result.stdout)
        self.assertIn("IGNORED (volatile) system/screen_off_timeout", result.stdout)

    def test_logcat_buffers_are_restored_one_by_one(self):
        self.snapshot()
        self.change(lambda s: s["logcat"].update({"main": 1024, "system": 1024, "crash": 1024}))
        self.restore()
        commands = self.shell_commands()
        for buffer in ("main", "system", "crash"):
            self.assertIn(f"logcat -b {buffer} -G 256K", commands)
        self.assertEqual(256, self.state()["logcat"]["main"])

    def test_display_drift_with_a_test_build_in_front_sends_home_once(self):
        self.allow_input = True
        session = self.snapshot()
        self.change(lambda s: s.update({"foreground": SHIELD, "home_restores_mode": True}) or s["display"].update({"active": 19}))
        self.restore()
        self.assertEqual(1, self.shell_commands().count("input keyevent KEYCODE_HOME"))
        self.assertEqual("clean\n", (session / "restore" / "verdict").read_text())

    def test_display_that_stays_wrong_after_home_is_an_owner_action(self):
        self.allow_input = True
        self.snapshot()
        self.change(lambda s: s.update({"foreground": SHIELD}) or s["display"].update({"active": 19}))
        result = self.restore(expect=4)
        self.assertEqual(1, self.shell_commands().count("input keyevent KEYCODE_HOME"))
        self.assertIn("OWNER display mode is 19 (3840x2160@50.000)", result.stdout)

    def test_display_drift_with_the_daily_app_in_front_sends_nothing(self):
        self.snapshot()
        self.change(lambda s: s.update({"foreground": "com.nuvio.tv.test"}) or s["display"].update({"active": 19}))
        result = self.restore(expect=4)
        self.assertIn("Settings > Device Preferences > Display & Sound > Resolution", result.stdout)

    def test_foreground_is_read_again_before_home(self):
        self.snapshot()
        # restore/pre sees the test build; by the time the display is handled the owner opened the daily app.
        self.change(lambda s: s.update({"foreground": SHIELD, "foreground_after": "com.nuvio.tv.test"}) or s["display"].update({"active": 19}))
        self.restore(expect=4)

    def test_watchdog_after_home_stops_before_the_after_capture(self):
        self.allow_input = True
        session = self.snapshot()
        self.change(lambda s: s.update({"foreground": SHIELD, "home_restores_mode": True, "watchdog_after_home": True}) or s["display"].update({"active": 19}))
        result = self.restore(expect=5)
        self.assertIn("STOP:", result.stderr)
        self.assertEqual("device\n", (session / "restore" / "verdict").read_text())
        self.assertFalse((session / "after").exists())

    def test_snapshot_hang_stops_without_a_baseline(self):
        result = self.run_script("shield_snapshot.sh", "--label", "h", "--out", str(self.home / "s"))
        self.assertEqual(0, result.returncode)
        self.change(lambda s: s.update({"hang_on": "dumpsys display"}))
        before = len(self.commands())
        result = self.run_script("shield_snapshot.sh", "--out", str(self.home / "hung"))
        self.assertEqual(5, result.returncode, result.stderr)
        self.assertIn("No more adb calls, no retry", result.stderr)
        self.assertTrue((self.home / "hung" / "before" / "hang.txt").is_file())
        self.assertFalse((self.home / "hung" / "before" / "complete").exists())
        self.assertIn("dumpsys display", " ".join(self.commands()[-1]))
        self.assertGreater(len(self.commands()), before)

    def test_restore_hang_stops_with_no_further_adb_call(self):
        session = self.snapshot()
        self.change(lambda s: s.update({"hang_on": "dumpsys display"}))
        result = self.restore(expect=5)
        self.assertIn("did not answer", result.stderr)
        self.assertTrue((session / "restore" / "hang.txt").is_file())
        self.assertFalse((session / "after").exists())
        self.assertIn("dumpsys display", " ".join(self.commands()[-1]))

    def test_wrong_device_is_refused_without_writes(self):
        self.change(lambda s: s["props"].update({"ro.product.device": "foster"}))
        result = self.run_script("shield_snapshot.sh", "--out", str(self.home / "x"))
        self.assertEqual(1, result.returncode)
        self.assertIn("not mdarcy", result.stderr)
        self.assertFalse((self.home / "x" / "before" / "complete").exists())

    def test_other_device_than_the_snapshot_is_refused(self):
        self.snapshot()
        self.change(lambda s: s["props"].update({"ro.serialno": "999", "debug.nuvio.m21_probe": "1"}))
        result = self.restore(expect=1)
        self.assertIn("not the Shield the snapshot was taken on", result.stderr)
        self.assertFalse([c for c in self.shell_commands() if c.startswith("setprop")])

    def test_serial_comes_from_flag_or_environment(self):
        result = self.run_script("shield_snapshot.sh", "--out", str(self.home / "n"), serial=None)
        self.assertEqual(1, result.returncode)
        self.assertIn("no serial", result.stderr)
        self.env["ANDROID_SERIAL"] = "10.1.1.1:5555"
        result = self.run_script("shield_snapshot.sh", "--out", str(self.home / "f"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(all(a[1] == SERIAL for a in self.commands() if a[0] == "-s"))

    def test_dry_run_sends_no_writes(self):
        session = self.snapshot()
        self.change(lambda s: s["props"].update({"debug.nuvio.owner_fix": "0"}) or s["logcat"].update({"main": 1024}))
        since = len(self.commands())
        result = self.restore("--dry-run", expect=3)
        commands = self.shell_commands(since)
        self.assertFalse([c for c in commands if c.startswith(("setprop", "settings put", "settings delete", "input")) or "-G" in c])
        dry = next(session.glob("restore.dry-*"))
        plan = (dry / "plan.txt").read_text()
        self.assertIn("write: setprop debug.nuvio.owner_fix '1'", plan)
        self.assertIn("write: logcat -b main -G 256K", plan)
        self.assertIn("dry run: nothing sent", result.stdout)
        self.assertFalse((session / "restore").exists())
        self.assertFalse((dry / "verdict").exists())

    def test_snapshot_without_any_debug_nuvio_prop(self):
        self.change(lambda s: s.update({"props": {k: v for k, v in s["props"].items() if not k.startswith("debug.nuvio.")}}))
        session = self.snapshot()
        self.assertEqual("", (session / "before" / "debug-nuvio.props").read_text())
        self.restore()

    def test_missing_test_build_and_a_new_install(self):
        self.change(lambda s: s["packages"].pop(SHIELD))
        session = self.snapshot("none")
        self.assertEqual("", (session / "before" / "apks.tsv").read_text())
        self.write_state(default_state())
        result = self.restore(expect=0)
        self.assertIn(f"INFO test APK {SHIELD}: not installed ->", result.stdout)

    def test_changed_apk_hash_names_the_package_specific_rebuild(self):
        self.snapshot()
        self.change(lambda s: s["files"].update({SHIELD_PATH: "a newer build"}))
        result = self.restore()
        self.assertIn(f"INFO test APK {SHIELD}:", result.stdout)
        self.assertIn("--suffix .shield --install", result.stdout)

    def test_pulled_apk_gives_the_reinstall_command(self):
        session = self.snapshot("pull", "--pull-apk")
        self.assertEqual("apk built at HEAD", (session / "before" / "apk" / f"{SHIELD}.apk").read_text())
        self.change(lambda s: s["files"].update({SHIELD_PATH: "a newer build"}))
        result = self.restore()
        self.assertIn(f"install -r {session}/before/apk/{SHIELD}.apk", result.stdout)

    def test_package_version_change_is_info(self):
        self.snapshot()
        self.change(lambda s: s["packages"].update({"com.google.android.gms": 264231117}))
        result = self.restore()
        self.assertIn("INFO package com.google.android.gms: 264230117 -> 264231117", result.stdout)

    def test_snapshot_refuses_a_folder_in_use(self):
        out = self.home / "used"
        (out / "before").mkdir(parents=True)
        (out / "before" / "meta").write_text("x")
        result = self.run_script("shield_snapshot.sh", "--out", str(out))
        self.assertEqual(1, result.returncode)
        self.assertEqual([], self.commands())

    def test_watchdog_in_logcat_stops_snapshot_and_restore(self):
        session = self.snapshot()
        self.change(lambda s: s["logcat_lines"].append("W Watchdog: Blocked in handler on main thread"))
        result = self.run_script("shield_snapshot.sh", "--out", str(self.home / "sick"))
        self.assertEqual(5, result.returncode)
        self.assertTrue((self.home / "sick" / "before" / "unhealthy.txt").is_file())
        self.assertFalse((self.home / "sick" / "before" / "complete").exists())
        since = len(self.commands())
        self.change(lambda s: s["props"].update({"debug.nuvio.m21_probe": "1"}))
        self.restore("--session", str(session), expect=5)
        self.assertFalse([c for c in self.shell_commands(since) if c.startswith("setprop")])
        result = self.restore("--session", str(self.home / "sick"), expect=1)
        self.assertIn("no complete marker", result.stderr)

    def test_interrupted_snapshot_is_never_a_baseline(self):
        complete = self.snapshot("a")
        self.change(lambda s: s.update({"hang_on": "pm path"}))
        result = self.run_script("shield_snapshot.sh", "--label", "b")
        self.assertEqual(5, result.returncode)
        self.change(lambda s: s.pop("hang_on"))
        result = self.restore()
        self.assertIn(f"using {complete}", result.stdout)
        interrupted = next(p for p in (self.home / "shield-snapshots").iterdir() if p.name.endswith("-b"))
        since = len(self.commands())
        self.restore("--session", str(interrupted), expect=1)
        self.assertEqual([], self.commands()[since:])

    def test_snapshot_without_a_hardware_serial_is_refused(self):
        self.change(lambda s: s["props"].pop("ro.serialno"))
        result = self.run_script("shield_snapshot.sh", "--out", str(self.home / "anon"))
        self.assertEqual(1, result.returncode)
        self.assertFalse((self.home / "anon" / "before" / "complete").exists())

    def test_clean_newest_session_needs_an_explicit_older_one(self):
        older = self.snapshot("a")
        self.snapshot("b")
        self.restore()
        self.change(lambda s: s["props"].update({"debug.nuvio.owner_fix": "0"}))
        result = self.restore(expect=1)
        self.assertIn("already restored clean; pass --session", result.stderr)
        self.restore("--session", str(older))
        self.assertEqual("1", self.state()["props"]["debug.nuvio.owner_fix"])


    def test_dry_run_after_a_clean_restore_keeps_the_session_closed(self):
        session = self.snapshot()
        self.restore()
        self.restore("--session", str(session), "--dry-run")
        self.change(lambda s: s["props"].update({"debug.nuvio.owner_new": "1"}))
        result = self.restore(expect=1)
        self.assertIn("already restored clean", result.stderr)
        self.assertEqual("1", self.state()["props"]["debug.nuvio.owner_new"])

    def test_refused_run_keeps_the_session_closed(self):
        session = self.snapshot()
        self.restore()
        self.change(lambda s: s["props"].update({"ro.serialno": "999"}))
        self.restore("--session", str(session), expect=1)
        self.assertTrue((session / "restored-clean").is_file())
        self.restore(expect=1)

    def test_newest_session_is_chosen_by_time_not_by_name(self):
        # An --out name in the old YYYYMMDD-<label> style sorts after a later default folder of the day.
        older = self.home / "shield-snapshots" / "20991231-dv99"
        result = self.run_script("shield_snapshot.sh", "--out", str(older))
        self.assertEqual(0, result.returncode, result.stderr)
        later = self.snapshot("later")
        result = self.restore("--dry-run")
        self.assertIn(f"using {later}", result.stdout)

    def test_named_setting_that_was_null_is_an_owner_action(self):
        self.change(lambda s: s["settings"]["global"].update({"wifi_display_certification_on": "null"}))
        self.snapshot()
        self.change(lambda s: s["settings"]["global"].update({"wifi_display_certification_on": "1"}))
        result = self.restore("--setting", "global/wifi_display_certification_on", expect=4)
        self.assertIn("OWNER setting global/wifi_display_certification_on was null", result.stdout)
        self.assertFalse([c for c in self.shell_commands() if c.startswith("settings put")])

    def test_empty_settings_namespace_leaves_no_baseline(self):
        self.change(lambda s: s["settings"].update({"system": {}}))
        result = self.run_script("shield_snapshot.sh", "--out", str(self.home / "empty"))
        self.assertEqual(1, result.returncode)
        self.assertIn("settings-system.txt came back empty", result.stderr)
        self.assertFalse((self.home / "empty" / "before" / "complete").exists())

    def test_adb_stderr_naming_the_serial_is_redacted(self):
        self.change(lambda s: s.update({"stderr_serial": True}))
        session = self.snapshot()
        self.assertIn("slow transport to <serial>", (session / "before" / "adb-stderr.log").read_text())

    def test_meta_counts_getprop_lines_that_did_not_parse(self):
        session = self.snapshot()
        self.assertIn("getprop_unparsed=2\n", (session / "before" / "meta").read_text())


if __name__ == "__main__":
    unittest.main()
