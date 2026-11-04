#!/usr/bin/env python3
"""Measure the Shield without changing it (ROADMAP M1.2).

    scripts/shield_measure.py check    [--serial S] [--package P ...]
    scripts/shield_measure.py start    --session DIR --package P [--force-cold]
    scripts/shield_measure.py ui       --session DIR --package P [--journey J] [--repeat N] [--details-trace]
    scripts/shield_measure.py playback --session DIR --package P [--seeks N] [--play S] [--already-playing]

- check     reads only: the box is mdarcy, the logs are healthy, which packages are installed and in front.
- start     a cold start with `am start -W`, only when the app is not running. --force-cold force-stops a
            test build (com.nuvio.tv.test.<suffix>) first; a daily app is never force-stopped.
- ui        D-pad journeys (the ones in NuvioNavigationBenchmark.kt) on the app in front: `dumpsys gfxinfo
            <pkg> reset`, the keys, then the gfxinfo summary and framestats, the JankStats JANK lines and the
            NuvioDetails stages of the details journey. --shots saves where each journey ended: the key
            sequences fit this app's Home, and a journey that ends elsewhere measures the wrong screen.
- playback  click -> PLAYBACK_STARTED (the owner starts a title on the TV), SEEK_READY per seek, dropped
            frames, iec_health underruns, AudioTrack restartIfDisabled and NuvioPosFreeze stalls.

The device-safety protocol (ROADMAP M2) holds: start, ui and playback need --session, a folder that
scripts/shield_snapshot.sh completed (before/complete), and write their results to its measure/. The script
never runs `dumpsys meminfo` (it caused an underrun restart during audio), never compiles, clears or
force-stops a daily app and never sets a property aimed at one. --details-trace (test builds only) sets
log.tag.NuvioDetails to VERBOSE for the run and puts back the value the session's first run saved
(measure/details-prop-before.txt), since shield_restore.sh restores only debug.nuvio.*. Every adb call has a timeout. The
serial and the stream hosts in the logs are never printed or saved.

What a field cannot show is null with a reason in `missing`: the upstream app logs no PLAYBACK_STARTED,
the details stages need the NuvioDetails trace, and release builds log at most one JANK line a second, so
the jank rate comes from gfxinfo, never from counting JANK lines.

Exit codes: 0 done; 1 refused; 2 usage; 5 the Shield did not answer or logged Watchdog/flush_work: stop,
no retry, tell the owner (M2: only a power cycle recovers it).
"""
import argparse
import json
import os
import re
import shlex
import statistics
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

EXIT_REFUSED = 1
EXIT_USAGE = 2
EXIT_DEVICE = 5
TEST_PREFIX = "com.nuvio.tv.test."
DAILY_DEFAULTS = ("com.nuvio.tv.test", "com.stremio.one")
DETAILS_PROP = "log.tag.NuvioDetails"
# M2's stop lines: the Watchdog tag, whatever it says, and flush_work.
HEALTH_RE = re.compile(r"(^|\s)[VDIWEF] Watchdog\s*:|flush_work")
# Commands the script must never send, whatever the package.
NEVER_RE = re.compile(r"\bdumpsys\s+meminfo\b|\bpm\s+clear\b|\bcompile\b|\buninstall\b|\binstall\b")
# The M1 journeys of NuvioNavigationBenchmark.kt, fitted to this app's Home (checked on the Shield
# 2026-11-03): each starts and ends on Home with focus on the first Continue watching card. Continue
# watching opens the stream picker, so details opens the first card of the row below it. The sidebar is
# home, discover, search, library, calendar, settings. (key, pause after it in ms).
JOURNEYS = {
    "home-row": [("DPAD_RIGHT", 120)] * 12 + [("DPAD_LEFT", 120)] * 12,
    "rows-down": [("DPAD_DOWN", 180), ("DPAD_UP", 180)] * 5,
    "sidebar": [("DPAD_LEFT", 250), ("DPAD_RIGHT", 250)] * 6,
    "details": [("DPAD_DOWN", 400), ("DPAD_CENTER", 3000), ("DPAD_DOWN", 250), ("DPAD_UP", 250),
                ("BACK", 1500), ("DPAD_UP", 400)],
    "settings": [("DPAD_LEFT", 300)] + [("DPAD_DOWN", 180)] * 5 + [("DPAD_CENTER", 1000)]
    + [("DPAD_DOWN", 180)] * 2 + [("DPAD_CENTER", 1000), ("BACK", 500), ("BACK", 1000)],
}
# PLAYBACK_STARTED logs under TTFF_STAGE (PlaybackStartedStage), SEEK_READY under PlayerViewModel.
PLAYBACK_LOG_TAGS = ("TTFF_STAGE", "PlayerViewModel", "NuvioExoEvent", "IecPassthrough", "AudioTrack", "NuvioPosFreeze")


class Refused(Exception):
    pass


class DeviceStop(Exception):
    pass


def is_test_package(package):
    return package.startswith(TEST_PREFIX)


def shq(value):
    return shlex.quote(value)


# ---------------------------------------------------------------- parsers (pure, tested on the JVM-free side)

def logcat_message(line):
    """`MM-DD hh:mm:ss.mmm pid tid L Tag: message` -> (tag, message); None for other lines."""
    m = re.match(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+([^:]+?)\s*: (.*)$", line)
    return (m.group(1), m.group(2)) if m else None


def keyvalues(text):
    return dict(re.findall(r"(\w+)=([^\s,]+)", text))


def parse_gfxinfo(text):
    """The summary block of `dumpsys gfxinfo <pkg>`; None when the app rendered nothing."""
    def number(pattern):
        m = re.search(pattern, text, re.MULTILINE)
        return float(m.group(1)) if m else None

    total = number(r"^\s*Total frames rendered:\s*(\d+)")
    if total is None:
        return None
    janky = number(r"^\s*Janky frames:\s*(\d+)")
    result = {
        "frames": int(total),
        "janky": int(janky) if janky is not None else None,
        "janky_pct": round(100 * janky / total, 2) if janky is not None and total else None,
    }
    for pct in (50, 90, 95, 99):
        result[f"p{pct}_ms"] = number(rf"^\s*{pct}th percentile:\s*(\d+)ms")
    for key, label in (("missed_vsync", "Missed Vsync"), ("slow_ui", "Slow UI thread"),
                       ("deadline_missed", "Frame deadline missed")):
        value = number(rf"^\s*Number {label}:\s*(\d+)")
        result[key] = int(value) if value is not None else None
    return result


def parse_framestats(text):
    """PROFILEDATA rows of `dumpsys gfxinfo <pkg> framestats`: INTENDED_VSYNC to FRAME_COMPLETED per frame."""
    durations = []
    header = None
    for line in text.splitlines():
        line = line.strip().rstrip(",")
        if line.startswith("Flags,"):
            header = line.split(",")
            continue
        if header is None or not line or not line[0].isdigit():
            if line.startswith("---PROFILEDATA---") and durations:
                header = None
            continue
        cells = line.split(",")
        row = dict(zip(header, cells))
        try:
            if int(row.get("Flags", "1")) != 0:
                continue
            start, end = int(row["IntendedVsync"]), int(row["FrameCompleted"])
        except (KeyError, ValueError):
            continue
        if end > start:
            durations.append((end - start) / 1e6)
    if not durations:
        return None
    ordered = sorted(durations)
    def at(pct):
        return round(ordered[min(len(ordered) - 1, round(pct / 100 * (len(ordered) - 1)))], 2)
    return {
        "frames": len(durations),
        "p50_ms": at(50),
        "p95_ms": at(95),
        "p99_ms": at(99),
        "over_16ms": sum(1 for d in durations if d > 16.7),
    }


def parse_am_start(text):
    fields = dict(re.findall(r"^(\w+):\s*(.*)$", text, re.MULTILINE))
    def ms(key):
        return int(fields[key]) if fields.get(key, "").isdigit() else None
    return {
        "status": fields.get("Status"),
        "launch_state": fields.get("LaunchState"),
        "total_ms": ms("TotalTime"),
        "wait_ms": ms("WaitTime"),
    }


def parse_jank(lines):
    """JankStats JANK lines. On release builds the app logs at most one a second: a count, not a rate."""
    durations = []
    for line in lines:
        parsed = logcat_message(line)
        if parsed and parsed[0] == "JankStats":
            m = re.match(r"JANK: (\d+)ms", parsed[1])
            if m:
                durations.append(int(m.group(1)))
    return {
        "lines": len(durations),
        "max_ms": max(durations) if durations else None,
        "median_ms": statistics.median(durations) if durations else None,
    }


def parse_details(lines):
    """NuvioDetails stage lines -> {journey: {stage: elapsedMs}} (the first time of each stage)."""
    journeys = {}
    for line in lines:
        parsed = logcat_message(line)
        if not parsed or parsed[0] != "NuvioDetails":
            continue
        kv = keyvalues(parsed[1])
        if "stage" in kv and "journey" in kv:
            journeys.setdefault(kv["journey"], {}).setdefault(kv["stage"], float(kv["elapsedMs"]))
    return journeys


def parse_playback(lines):
    started, seeks = [], []
    dropped = restarts = freezes = 0
    underruns = None
    for line in lines:
        parsed = logcat_message(line)
        if not parsed:
            continue
        tag, message = parsed
        if message.startswith("PLAYBACK_STARTED:"):
            kv = keyvalues(message)
            started.append({
                "click_to_playing_ms": int(kv["clickToPlayingMs"]),
                "first_frame_to_playing_ms": int(kv["firstFrameToPlayingMs"]),
                "afr_track_held": kv.get("afrTrackHeld") == "true",
                "paused_at_first_frame": kv.get("pausedAtFirstFrame") == "true",
                "seq": int(kv.get("seq", "0")),
            })
        elif message.startswith("SEEK_READY:"):
            kv = keyvalues(message)
            seeks.append({"ms": int(kv["ms"]), "via": kv.get("via")})
        elif tag == "NuvioExoEvent" and "name=dropped_video_frames" in message:
            details = re.search(r"details=(\S+)", message)
            dropped += int(keyvalues(details.group(1)).get("count", "0")) if details else 0
        elif tag == "IecPassthrough" and message.startswith("iec_health "):
            value = int(keyvalues(message).get("underruns", "0"))
            underruns = value if underruns is None else max(underruns, value)
        elif tag == "AudioTrack" and "restartIfDisabled" in message:
            restarts += 1
        elif tag == "NuvioPosFreeze" and message.startswith("FREEZE_BEGIN"):
            freezes += 1
    return {
        "playback_started": started,
        "seek_ready": seeks,
        "dropped_frames": dropped,
        "iec_underruns_max": underruns,
        "restart_if_disabled": restarts,
        "freezes": freezes,
    }


def unhealthy(lines):
    return [line for line in lines if HEALTH_RE.search(line)]


def scrub(line, serial):
    """Drops the stream host and the Shield's address from a saved log line."""
    line = re.sub(r"host=\S+", "host=<host>", line)
    host = serial.rsplit(":", 1)[0] if serial else ""
    return line.replace(host, "<serial>") if host else line


# ---------------------------------------------------------------- device

class Device:
    def __init__(self, serial, timeout, adb="adb"):
        self.serial = serial
        self.timeout = timeout
        self.adb = adb

    def redact(self, text):
        host = self.serial.rsplit(":", 1)[0]
        return text.replace(self.serial, "<serial>").replace(host, "<serial>")

    def shell(self, command, ok_codes=(0,)):
        if NEVER_RE.search(command):
            raise Refused(f"refused to send: {command}")
        try:
            proc = subprocess.run([self.adb, "-s", self.serial, "shell", command], capture_output=True,
                                  text=True, encoding="utf-8", errors="replace", timeout=self.timeout)
        except subprocess.TimeoutExpired:
            raise DeviceStop(f"adb did not answer in {self.timeout} s: {self.redact(command)}")
        if proc.returncode not in ok_codes:
            if ("device" in proc.stderr and "not found" in proc.stderr) or "offline" in proc.stderr:
                raise DeviceStop(f"the Shield is not reachable: {self.redact(proc.stderr.strip())}")
            raise Refused(f"`{self.redact(command)}` failed ({proc.returncode}): {self.redact(proc.stderr.strip())}")
        return proc.stdout, proc.returncode

    def out(self, command):
        return self.shell(command)[0]

    def now(self):
        """The device clock in logcat's -T format."""
        return self.out("date +%m-%d_%H:%M:%S").strip().replace("_", " ") + ".000"

    def logcat_since(self, since):
        return self.out(f"logcat -d -v threadtime -T {shq(since)}").splitlines()

    def key(self, name):
        self.out(f"input keyevent KEYCODE_{name}")

    def stream(self, since, tags):
        return LogStream(self, since, tags)

    def shot(self, path):
        """A screenshot of the TV to the host, for checking where a journey ended."""
        remote = "/sdcard/shield_measure.png"
        self.out(f"screencap -p {remote}")
        try:
            proc = subprocess.run([self.adb, "-s", self.serial, "pull", remote, str(path)], capture_output=True,
                                  timeout=self.timeout * 4)
        except subprocess.TimeoutExpired:
            raise DeviceStop("adb pull of the screenshot did not finish")
        finally:
            self.out(f"rm -f {remote}")
        if proc.returncode != 0:
            raise Refused("adb pull of the screenshot failed")

    def foreground(self):
        text = self.out("dumpsys activity activities")
        m = re.search(r"mResumedActivity: ActivityRecord\{\S+ \S+ ([\w.]+)/", text)
        return m.group(1) if m else None

    def installed(self, package):
        return f"package:{package}" in self.out(f"pm list packages {shq(package)}").split()

    def running(self, package):
        return self.shell(f"pidof {shq(package)}", ok_codes=(0, 1))[1] == 0


def connect(args):
    if not args.serial:
        raise Refused("no serial: pass --serial or set ANDROID_SERIAL")
    device = Device(args.serial, args.adb_timeout, args.adb)
    if device.out("getprop ro.product.device").strip() != "mdarcy":
        raise Refused("the device is not the Shield (ro.product.device is not mdarcy)")
    bad = unhealthy(device.out("logcat -d -v threadtime -t 2000").splitlines())
    if bad:
        raise DeviceStop("the Shield logged Watchdog or flush_work; stop and tell the owner")
    return device


def require_session(args):
    session = Path(args.session).expanduser()
    if not (session / "before" / "complete").is_file():
        raise Refused(f"{session} has no completed snapshot: run scripts/shield_snapshot.sh first (M2)")
    out = session / "measure"
    out.mkdir(exist_ok=True)
    return out


def save(out, name, result, lines, serial):
    stamp = datetime.now().strftime("%H%M%S")
    (out / f"{name}-{stamp}.json").write_text(json.dumps(result, indent=2) + "\n")
    if lines is not None:
        (out / f"{name}-{stamp}-logcat.txt").write_text("".join(scrub(line, serial) + "\n" for line in lines))
    return out / f"{name}-{stamp}.json"


class LogStream:
    """A live, tag-filtered `adb logcat` from [since] on. logd's chatty prunes the busiest uid once the
    256 KiB main buffer fills, and the app logs enough (MetaRepository, TmdbService) that a `logcat -d`
    after a journey missed every JankStats and NuvioDetails line on 2026-11-03; a live reader gets each
    line as it is written."""

    def __init__(self, device, since, tags):
        import tempfile
        # The child appends through its own O_APPEND handle and the reader has another one: a shared
        # file offset would let a line written while lines() reads land on top of the earlier ones.
        fd, path = tempfile.mkstemp(prefix="shield_measure_", suffix=".log")
        os.close(fd)
        try:
            writer = open(path, "ab")
            self.file = open(path, encoding="utf-8", errors="replace")
        finally:
            os.unlink(path)
        filters = [f"{tag}:V" for tag in (*tags, "Watchdog")] + ["*:S"]
        # `adb logcat` passes each argument as is: quotes around the -T time reach logcat and fail.
        self.errors = tempfile.TemporaryFile()
        self.redact = getattr(device, "redact", lambda text: text)
        with writer:
            self.proc = subprocess.Popen([device.adb, "-s", device.serial, "logcat", "-v", "threadtime",
                                          "-T", since, *filters], stdout=writer, stderr=self.errors)

    def lines(self):
        self.file.seek(0)
        return [line.rstrip("\n") for line in self.file.read().splitlines() if line.strip()]

    def stop(self):
        """The lines so far. A live logcat ends only when stopped here: one that exited by itself
        captured nothing after that, so the run fails with its stderr instead of reporting zeros."""
        time.sleep(0.3)
        ended = self.proc.poll()
        if ended is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        lines = self.lines()
        self.file.close()
        self.errors.seek(0)
        stderr = self.errors.read().decode("utf-8", "replace").strip()
        self.errors.close()
        if ended is not None:
            raise Refused(f"the live logcat ended early ({ended}): {self.redact(stderr) or 'no stderr'}")
        return lines


def finish_health(device, since, lines=None):
    lines = device.logcat_since(since) if lines is None else lines
    if unhealthy(lines):
        raise DeviceStop("the Shield logged Watchdog or flush_work during the run; stop and tell the owner")
    return lines


def pause(args, ms):
    if ms and args.pause_scale:
        time.sleep(ms / 1000 * args.pause_scale)


# ---------------------------------------------------------------- commands

def cmd_check(args, device):
    packages = args.package or [*DAILY_DEFAULTS, "com.nuvio.tv.test.shield"]
    result = {
        "device": "mdarcy",
        "healthy": True,
        "foreground": device.foreground(),
        "installed": {p: device.installed(p) for p in packages},
        "running": {p: device.running(p) for p in packages},
        "details_trace": device.out(f"getprop {DETAILS_PROP}").strip() or "",
    }
    print(json.dumps(result, indent=2))
    return 0


def cmd_start(args, device):
    out = require_session(args)
    package = args.package
    if not device.installed(package):
        raise Refused(f"{package} is not installed")
    missing = {}
    if device.running(package):
        if args.force_cold and is_test_package(package):
            device.out(f"am force-stop {shq(package)}")
            pause(args, 1000)
        else:
            reason = ("daily app is running; it is never force-stopped" if not is_test_package(package)
                      else "app is running; pass --force-cold to stop the test build first")
            result = {"command": "start", "package": package, "cold": None, "missing": {"cold": reason}}
            print(json.dumps(result, indent=2))
            save(out, "start", result, None, args.serial)
            return 0
    component = device.out(
        "cmd package resolve-activity --brief -a android.intent.action.MAIN "
        f"-c android.intent.category.LEANBACK_LAUNCHER {shq(package)}").strip().splitlines()[-1]
    if "/" not in component:
        raise Refused(f"no TV launcher activity for {package}")
    since = device.now()
    launch = parse_am_start(device.out(f"am start -W -n {shq(component)}"))
    lines = finish_health(device, since)
    if launch["total_ms"] is None:
        missing["cold"] = "am start -W printed no TotalTime"
    result = {"command": "start", "package": package, "cold": launch, "missing": missing}
    print(json.dumps(result, indent=2))
    save(out, "start", result, lines, args.serial)
    return 0


def run_journey(args, device, journey):
    device.out(f"dumpsys gfxinfo {shq(args.package)} reset")
    since = device.now()
    stream = device.stream(since, ("JankStats", "NuvioDetails"))
    try:
        for key, after_ms in JOURNEYS[journey]:
            device.key(key)
            pause(args, after_ms)
        pause(args, 500)
        summary = parse_gfxinfo(device.out(f"dumpsys gfxinfo {shq(args.package)}"))
        frames = parse_framestats(device.out(f"dumpsys gfxinfo {shq(args.package)} framestats"))
    finally:
        streamed = stream.stop()
    finish_health(device, since, device.logcat_since(since) + streamed)
    mine = [line for line in streamed if (logcat_message(line) or ("",))[0] in ("JankStats", "NuvioDetails")]
    missing = {}
    if summary is None:
        missing["gfxinfo"] = "gfxinfo has no frames for the package"
    result = {"journey": journey, "gfxinfo": summary, "framestats": frames, "jank_lines": parse_jank(mine)}
    if journey == "details":
        stages = parse_details(mine)
        result["details_stages"] = stages or None
        if not stages:
            missing["details_stages"] = ("no NuvioDetails stage lines: the trace is off "
                                         f"({DETAILS_PROP} is not VERBOSE) or the app has no DetailEntryTrace")
    result["missing"] = missing
    return result, mine


def wake_from_dream(args, device):
    """The screensaver (a DreamActivity) covers the app after a few idle minutes; one WAKEUP ends it."""
    text = device.out("dumpsys activity activities")
    if re.search(r"mResumedActivity: ActivityRecord\{[^}]*DreamActivity", text):
        device.key("WAKEUP")
        pause(args, 1500)


def cmd_ui(args, device):
    out = require_session(args)
    wake_from_dream(args, device)
    if device.foreground() != args.package:
        raise Refused(f"{args.package} is not in front: open its Home screen on the TV first")
    journeys = list(JOURNEYS) if args.journey == "all" else [args.journey]
    old_prop = None
    if args.details_trace:
        if not is_test_package(args.package):
            raise Refused("--details-trace sets a property; only a test build (com.nuvio.tv.test.<suffix>) allows it")
        # The first run of a session saves the value it found: a run killed before its finally (an
        # interrupt on 2026-11-04 did this) left VERBOSE, and the next run would keep it as "the old value".
        saved = out / "details-prop-before.txt"
        if not saved.exists():
            saved.write_text(device.out(f"getprop {DETAILS_PROP}").strip() + "\n")
        old_prop = saved.read_text().strip()
        device.out(f"setprop {DETAILS_PROP} VERBOSE")
    runs, kept = [], []
    try:
        for journey in journeys:
            for repeat in range(args.repeat):
                result, lines = run_journey(args, device, journey)
                result["repeat"] = repeat + 1
                if args.shots:
                    shot = out / f"ui-{datetime.now().strftime('%H%M%S')}-{journey}-{repeat + 1}.png"
                    device.shot(shot)
                    result["shot"] = shot.name
                runs.append(result)
                kept += lines
                if device.foreground() != args.package:
                    raise Refused(f"{args.package} left the front after {journey}; stopping")
    finally:
        if old_prop is not None:
            device.out(f"setprop {DETAILS_PROP} {shq(old_prop)}")
    result = {"command": "ui", "package": args.package, "details_trace": bool(args.details_trace), "runs": runs}
    for run in runs:
        g = run["gfxinfo"] or {}
        print(f"{run['journey']:<10} #{run['repeat']} frames={g.get('frames')} janky={g.get('janky_pct')}% "
              f"p90={g.get('p90_ms')}ms p99={g.get('p99_ms')}ms jank_lines={run['jank_lines']['lines']}"
              + (f" missing={','.join(run['missing'])}" if run["missing"] else ""))
    print(save(out, "ui", result, kept, args.serial))
    return 0


def cmd_playback(args, device):
    out = require_session(args)
    since = device.now()
    missing = {}
    stream = device.stream(since, PLAYBACK_LOG_TAGS)
    try:
        if not args.already_playing:
            print(f"Start a title in {args.package} on the TV now (waiting up to {args.wait_start} s).", flush=True)
            deadline = time.monotonic() + args.wait_start
            while True:
                if unhealthy(stream.lines()):
                    raise DeviceStop("the Shield logged Watchdog during the run; stop and tell the owner")
                if parse_playback(stream.lines())["playback_started"]:
                    break
                if time.monotonic() >= deadline:
                    missing["playback_started"] = ("no PLAYBACK_STARTED line: the upstream app does not log it, "
                                                   "or the start did not finish in time")
                    break
                pause(args, args.poll * 1000)
        pause(args, args.play * 1000)
        for _ in range(args.seeks):
            device.key(args.seek_key)
            pause(args, args.seek_gap * 1000)
    finally:
        streamed = stream.stop()
    finish_health(device, since, device.logcat_since(since) + streamed)
    mine = [line for line in streamed if (logcat_message(line) or ("",))[0] in PLAYBACK_LOG_TAGS]
    parsed = parse_playback(mine)
    started, seeks = parsed["playback_started"], parsed["seek_ready"]
    if args.seeks and len(seeks) < args.seeks:
        missing["seek_ready"] = f"{len(seeks)} SEEK_READY lines for {args.seeks} seeks"
    if parsed["iec_underruns_max"] is None:
        missing["iec_underruns_max"] = "no iec_health line: the IEC packer was not in use"
    result = {"command": "playback", "package": args.package, **parsed, "missing": missing}
    first = started[0] if started else {}
    seek_ms = [s["ms"] for s in seeks]
    print(f"click->playing={first.get('click_to_playing_ms')}ms seek_ready_median="
          f"{statistics.median(seek_ms) if seek_ms else None}ms dropped={result['dropped_frames']} "
          f"underruns={result['iec_underruns_max']} restartIfDisabled={result['restart_if_disabled']} "
          f"freezes={result['freezes']}" + (f" missing={','.join(missing)}" if missing else ""))
    print(save(out, "playback", result, mine, args.serial))
    return 0


def parser():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"), help="the Shield (host:5555); never printed")
    p.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    p.add_argument("--adb-timeout", type=float, default=float(os.environ.get("SHIELD_ADB_TIMEOUT", "30")))
    p.add_argument("--pause-scale", type=float, default=1.0, help=argparse.SUPPRESS)
    sub = p.add_subparsers(dest="command", required=True)
    check = sub.add_parser("check")
    check.add_argument("--package", action="append")
    for name in ("start", "ui", "playback"):
        s = sub.add_parser(name)
        s.add_argument("--session", required=True, help="folder with a completed shield_snapshot.sh snapshot")
        s.add_argument("--package", required=True)
    sub.choices["start"].add_argument("--force-cold", action="store_true")
    ui = sub.choices["ui"]
    ui.add_argument("--journey", choices=[*JOURNEYS, "all"], default="all")
    ui.add_argument("--repeat", type=int, default=1)
    ui.add_argument("--details-trace", action="store_true")
    ui.add_argument("--shots", action="store_true", help="save a screenshot where each journey ends")
    pb = sub.choices["playback"]
    pb.add_argument("--already-playing", action="store_true")
    pb.add_argument("--wait-start", type=float, default=120)
    pb.add_argument("--poll", type=float, default=2)
    pb.add_argument("--play", type=float, default=60)
    pb.add_argument("--seeks", type=int, default=0)
    pb.add_argument("--seek-key", default="DPAD_RIGHT")
    pb.add_argument("--seek-gap", type=float, default=8)
    return p


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        device = connect(args)
        return {"check": cmd_check, "start": cmd_start, "ui": cmd_ui, "playback": cmd_playback}[args.command](args, device)
    except Refused as error:
        print(f"refused: {error}", file=sys.stderr)
        return EXIT_REFUSED
    except DeviceStop as error:
        print(f"STOP: {error}", file=sys.stderr)
        return EXIT_DEVICE


if __name__ == "__main__":
    sys.exit(main())
