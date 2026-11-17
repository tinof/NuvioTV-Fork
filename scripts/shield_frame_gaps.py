#!/usr/bin/env python3
"""Find video frame gaps on the Shield and put memory pressure next to them (ROADMAP Q5.1).

    scripts/shield_frame_gaps.py capture --session DIR [--serial S] [--package P] [--seconds N]
    scripts/shield_frame_gaps.py analyze CAPDIR [--json]

- capture  while a title plays: `dumpsys SurfaceFlinger --latency` on the package's SurfaceView layer, a live
           `logcat -v monotonic`, and /proc/uptime, /proc/meminfo and /proc/vmstat every --mem-every seconds.
           It writes into <session>/frame-gaps/<HHMMSS>/ and then runs analyze on that folder.
- analyze  reads such a folder and reports every event, with the vmstat deltas, GC lines and SIGKILL exits
           around it, and the coverage gaps of the capture.

SurfaceFlinger rows are desiredPresent, actualPresent and frameReady, in CLOCK_MONOTONIC nanoseconds.
- app_skip: a gap in desired present. The app never queued those frames (a drop, or data or decode late).
- late_present: desired present is regular but the frame reached the screen later than the run's usual
  latency: the compositor or display presented late, and the previous frame stayed on screen.
- stall: a desired-present gap of a second or more (a pause, rebuffer or seek), kept apart from skips.
- coverage gap: two consecutive dumps that do not overlap, an empty dump or a lost layer. Frames in a gap
  were not seen, so no event is claimed there. The dump interval follows the history span of each dump.
The frame period is the median desired-present step, not the first line of the dump (the display's
refresh period, which differs at 25 fps on 50 Hz).

Clocks: the first dump's newest frame marks the start, so frames from before the capture are dropped.
/proc/uptime counts time asleep and CLOCK_MONOTONIC does not, so capture brackets /proc/timer_list's
`now at` between two uptime reads, at the start and at the end, and analyze shifts every memory sample by
that offset. logcat -v monotonic is CLOCK_MONOTONIC; meta.json records the check.

A SIGKILL exit (Zygote's "exited due to signal 9") is named lmkd only when a lowmemorykiller or lmkd line
names its pid; an ActivityManager "Killing <pid>:" line names activity-manager; anything else is unknown.

Read-only: the script resolves the pid and layer from the package, reads, and changes nothing on the box.
It never runs `dumpsys meminfo` (it caused an underrun restart during audio). Every adb call has a timeout.
The serial is never printed or saved. Run it inside an M2 session (scripts/shield_snapshot.sh first).

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
import tempfile
import time
from datetime import datetime
from pathlib import Path

EXIT_REFUSED = 1
EXIT_USAGE = 2
EXIT_DEVICE = 5
INF = 9223372036854775807
HEALTH_RE = re.compile(r"(^|\s)[VDIWEF] Watchdog\s*:|flush_work")
NEVER_RE = re.compile(r"\bdumpsys\s+meminfo\b|\bpm\s+clear\b|\bcompile\b|\buninstall\b|\binstall\b|\bsetprop\b"
                      r"|\bsettings\s+put\b|\bam\s+(force-stop|kill)\b|\binput\b")
VM_KEYS = ("allocstall", "pgscan_direct", "pgscan_kswapd", "pgsteal_direct", "pgmajfault", "pswpin", "pswpout",
           "workingset_refault")
STALL_NS = 1_000_000_000
LOG_RE = re.compile(r"\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.*)")
SIGKILL_RE = re.compile(r"Process (\d+) exited due to signal 9")
LMKD_RE = re.compile(r"(?:lowmemorykiller|lmkd)\s*:.*?\bKill(?:ing)?\s+'[^']*'\s+\((\d+)\)", re.I)
AM_KILL_RE = re.compile(r"ActivityManager\s*:\s+Killing (\d+):")
GC_RE = re.compile(r"\bGC freed\b")
BEFORE_S, AFTER_S = 2.5, 0.5


class Refused(Exception):
    pass


class DeviceStop(Exception):
    pass


def shq(text):
    return shlex.quote(text)


# ---------------------------------------------------------------- parsing (pure)

def parse_latency(text):
    """(refresh period ns or None, [(desired, actual, ready)]) without empty and pending rows."""
    lines = text.replace("\r", "").split("\n")
    period = int(lines[0].strip()) if lines and lines[0].strip().isdigit() else None
    rows = []
    for line in lines[1:]:
        parts = line.split()
        if len(parts) != 3 or not all(p.isdigit() for p in parts):
            continue
        d, a, r = map(int, parts)
        if d == 0 or a in (0, INF) or d == INF:
            continue
        rows.append((d, a, r))
    return period, rows


def pick_layer(listing, package):
    """The package's newest SurfaceView layer. The `/` keeps com.nuvio.tv.test from matching .test.shield."""
    want = re.compile(rf"^SurfaceView\s*-\s*{re.escape(package)}/")
    layers = [ln.strip() for ln in listing.replace("\r", "").split("\n") if want.match(ln.strip())]
    return layers[-1] if layers else None


def parse_mem(text):
    """One sample from `cat /proc/uptime /proc/meminfo /proc/vmstat`; allocstall_* are summed."""
    sample = {}
    lines = text.replace("\r", "").split("\n")
    first = lines[0].split() if lines else []
    if not first:
        return None
    try:
        sample["uptime"] = float(first[0])
    except ValueError:
        return None
    for line in lines[1:]:
        parts = line.replace(":", " ").split()
        if len(parts) < 2 or not parts[1].isdigit():
            continue
        key, value = parts[0], int(parts[1])
        if key in ("MemAvailable", "MemFree", "SwapFree", "Cached"):
            sample[key] = value  # kB
        elif key.startswith("allocstall"):
            sample["allocstall"] = sample.get("allocstall", 0) + value
        elif key in VM_KEYS:
            sample[key] = value
    return sample


def parse_timer_now(text):
    m = re.search(r"now at (\d+) nsecs", text)
    return int(m.group(1)) if m else None


def parse_logcat(lines):
    """[(monotonic seconds, pid, line)] from `logcat -v monotonic` lines."""
    out = []
    for line in lines:
        m = LOG_RE.match(line)
        if m:
            out.append((float(m.group(1)), int(m.group(2)), line.rstrip()))
    return out


def merge_dumps(dumps, start_ns):
    """Frames from every dump after start_ns, and the coverage gaps between dumps as (from_ns, to_ns, why)."""
    rows, gaps = {}, []
    # With no start mark (start_ns 0) the first dump cannot be checked for overlap.
    last_max, pending_empty = (start_ns if start_ns > 0 else None), None
    for dump in dumps:
        if not dump["rows"]:
            if pending_empty is None:
                pending_empty = dump.get("why") or "empty dump"
            continue
        kept = [row for row in dump["rows"] if row[0] > start_ns]
        if not kept:
            continue  # nothing new since the start mark, and the dump overlaps it
        low = min(row[0] for row in kept)
        if pending_empty is not None:
            gaps.append((last_max if last_max is not None else low, low, pending_empty))
            pending_empty = None
        elif last_max is not None and min(row[0] for row in dump["rows"]) > last_max:
            gaps.append((last_max, low, "dumps do not overlap"))
        for d, a, r in kept:
            rows[d] = (a, r)
        newest = max(row[0] for row in kept)
        last_max = newest if last_max is None else max(last_max, newest)
    if pending_empty is not None:
        gaps.append((last_max or start_ns, None, pending_empty))
    return rows, gaps


def in_gap(t0, t1, gaps):
    return any(g0 <= t0 and (g1 is None or t1 <= g1) for g0, g1, _ in gaps)


def classify(rows, gaps, refresh_ns):
    """Events from the merged frames, and the frame period used."""
    ds = sorted(rows)
    steps = [b - a for a, b in zip(ds, ds[1:]) if not in_gap(a, b, gaps)]
    if not steps:
        return [], None, None
    period = statistics.median(steps)
    vsync = refresh_ns or period
    base = statistics.median(rows[d][0] - d for d in ds)
    events = []
    late_run = None
    for i, d in enumerate(ds):
        after_gap = False
        if i:
            prev = ds[i - 1]
            dd = d - prev
            # The frame that ends a skip or stall is part of that event: its desired present is not regular.
            after_gap = dd > 1.5 * period
            if not in_gap(prev, d, gaps):
                if dd >= STALL_NS:
                    events.append({"kind": "stall", "t_ns": d, "desired_gap_ms": dd / 1e6})
                elif dd > 1.5 * period:
                    events.append({"kind": "app_skip", "t_ns": d, "frames": round(dd / period) - 1,
                                   "desired_gap_ms": dd / 1e6})
        a, r = rows[d]
        late = (a - d) - base
        if late > 0.5 * vsync and not after_gap:
            frames = max(1, round(late / vsync))
            if late_run and late_run["last_ns"] == ds[i - 1]:
                late_run["frames"] = max(late_run["frames"], frames)
                late_run["late_ms"] = max(late_run["late_ms"], late / 1e6)
                late_run["last_ns"] = d
                late_run["ready_late"] = late_run["ready_late"] or r > d
            else:
                late_run = {"kind": "late_present", "t_ns": d, "frames": frames, "late_ms": late / 1e6,
                            "ready_late": r > d, "last_ns": d}
                events.append(late_run)
        else:
            late_run = None
    for e in events:
        e.pop("last_ns", None)
    events.sort(key=lambda e: e["t_ns"])
    return events, period, base


def kills(logs):
    """SIGKILL exits with their killer: lmkd only when an lmkd line names the pid."""
    lmkd = [(t, int(m.group(1))) for t, _, ln in logs for m in [LMKD_RE.search(ln)] if m]
    am = [(t, int(m.group(1))) for t, _, ln in logs for m in [AM_KILL_RE.search(ln)] if m]
    out = []
    for t, _, ln in logs:
        m = SIGKILL_RE.search(ln)
        if not m:
            continue
        pid = int(m.group(1))
        if any(p == pid and abs(t - lt) <= 5 for lt, p in lmkd):
            who = "lmkd"
        elif any(p == pid and abs(t - lt) <= 5 for lt, p in am):
            who = "activity-manager"
        else:
            who = "unknown"
        out.append({"t": t, "pid": pid, "killer": who})
    return out


def vm_window(mems, t):
    """vmstat deltas over the memory interval that holds t (monotonic s)."""
    for a, b in zip(mems, mems[1:]):
        if a["t"] <= t <= b["t"]:
            delta = {k: b[k] - a[k] for k in VM_KEYS if k in a and k in b}
            delta["availMb"] = b.get("MemAvailable", 0) // 1024
            delta["swapFreeMb"] = b.get("SwapFree", 0) // 1024
            return delta
    return None


def analyze(cap):
    cap = Path(cap)
    meta = json.loads((cap / "meta.json").read_text())
    dumps = []
    for entry in meta["dumps"]:
        text = (cap / entry["file"]).read_text() if entry.get("file") else ""
        period, rows = parse_latency(text)
        dumps.append({"rows": rows, "period": period, "why": entry.get("why")})
    start_ns = meta["start_ns"]
    rows, gaps = merge_dumps(dumps, start_ns)
    refresh = next((d["period"] for d in dumps if d["period"]), None)
    events, period, base = classify(rows, gaps, refresh)
    offset = meta.get("mono_minus_uptime_s", 0.0)
    mems = []
    mem_path = cap / "mem.jsonl"
    if mem_path.exists():
        for line in mem_path.read_text().splitlines():
            sample = json.loads(line)
            sample["t"] = sample["uptime"] + offset
            mems.append(sample)
    mems.sort(key=lambda s: s["t"])
    log_path = cap / "logcat.txt"
    logs = parse_logcat(log_path.read_text(errors="replace").splitlines()) if log_path.exists() else []
    pid = meta.get("pid")
    kill_list = kills(logs)
    for e in events:
        t = e["t_ns"] / 1e9
        e["t"] = round(t, 3)
        e["vm"] = vm_window(mems, t)
        e["gc"] = [ln[:200] for lt, p, ln in logs if p == pid and t - BEFORE_S <= lt <= t + AFTER_S and GC_RE.search(ln)]
        e["sigkill"] = [k for k in kill_list if t - BEFORE_S <= k["t"] <= t + AFTER_S]
        del e["t_ns"]
    ds = sorted(rows)
    end_ns = meta.get("end_ns") or (ds[-1] if ds else start_ns)
    gap_list = [{"from": round(g0 / 1e9, 3), "to": round((g1 or end_ns) / 1e9, 3), "why": why} for g0, g1, why in gaps]
    if meta.get("log_ended_early"):
        gap_list.append({"from": None, "to": None, "why": "logcat ended early: kills and GC lines are incomplete"})
    counts = {k: sum(1 for e in events if e["kind"] == k) for k in ("app_skip", "late_present", "stall")}
    totals = None
    if len(mems) >= 2:
        totals = {k: mems[-1][k] - mems[0][k] for k in VM_KEYS if k in mems[0] and k in mems[-1]}
        avail = [s["MemAvailable"] // 1024 for s in mems if "MemAvailable" in s]
        totals["availMb_min"], totals["availMb_max"] = (min(avail), max(avail)) if avail else (None, None)
        totals["seconds"] = round(mems[-1]["t"] - mems[0]["t"], 1)
    result = {
        "package": meta.get("package"),
        "layer": meta.get("layer"),
        "frames": len(rows),
        "span_s": round((ds[-1] - ds[0]) / 1e9, 1) if ds else 0,
        "capture_s": round((end_ns - start_ns) / 1e9, 1),
        "frame_period_ms": round(period / 1e6, 3) if period else None,
        "refresh_period_ms": round(refresh / 1e6, 3) if refresh else None,
        "usual_latency_ms": round(base / 1e6, 1) if base is not None else None,
        "counts": counts,
        "coverage_gaps": gap_list,
        "clock": {"mono_minus_uptime_s": offset, "drift_s": meta.get("clock_drift_s"),
                  "log_clock_ok": meta.get("log_clock_ok")},
        "vmstat": totals,
        "sigkill": {"total": len(kill_list),
                    "by_killer": {k: sum(1 for x in kill_list if x["killer"] == k) for k in ("lmkd", "activity-manager", "unknown")}},
        "events": events,
        "warnings": [],
    }
    if period and refresh and abs(period - refresh) / refresh > 0.05:
        result["warnings"].append(f"frame period {period / 1e6:.3f} ms differs from the refresh period "
                                  f"{refresh / 1e6:.3f} ms; late presents are counted in refresh periods")
    if meta.get("clock_drift_s") and abs(meta["clock_drift_s"]) > 0.5:
        result["warnings"].append(f"the box slept during the capture (clock drift {meta['clock_drift_s']:.1f} s)")
    if meta.get("complete") is False:
        result["warnings"].append("the capture was cut short: the report covers the frames up to its last dump")
    if meta.get("log_clock_ok") is False:
        result["warnings"].append("logcat's monotonic clock did not match CLOCK_MONOTONIC at the start")
    result["gate"] = {"app_skip": counts["app_skip"] > 0, "late_present": counts["late_present"] > 0,
                      "coverage_gaps": len(gap_list)}
    return result


def report(result):
    out = []
    out.append(f"frames={result['frames']} span={result['span_s']}s capture={result['capture_s']}s "
               f"frame period={result['frame_period_ms']}ms refresh={result['refresh_period_ms']}ms "
               f"usual latency={result['usual_latency_ms']}ms")
    c = result["counts"]
    out.append(f"events: app_skip={c['app_skip']} late_present={c['late_present']} stall={c['stall']}")
    out.append(f"coverage gaps: {len(result['coverage_gaps'])}")
    for g in result["coverage_gaps"]:
        out.append(f"  {g['from']} -> {g['to']}: {g['why']}")
    if result["vmstat"]:
        out.append(f"vmstat: {result['vmstat']}")
    out.append(f"SIGKILL exits: {result['sigkill']}")
    for w in result["warnings"]:
        out.append(f"warning: {w}")
    for e in result["events"]:
        head = f"{e['kind']} t={e['t']:.3f}"
        if "frames" in e:
            head += f" frames={e['frames']}"
        if "desired_gap_ms" in e:
            head += f" desiredGap={e['desired_gap_ms']:.1f}ms"
        if "late_ms" in e:
            head += f" late={e['late_ms']:.1f}ms readyLate={e['ready_late']}"
        out.append(head)
        out.append(f"  vm: {e['vm']}")
        if e["sigkill"]:
            out.append(f"  sigkill: {e['sigkill']}")
        for ln in e["gc"]:
            out.append(f"  {ln}")
    return "\n".join(out)


# ---------------------------------------------------------------- device

class Device:
    def __init__(self, serial, timeout, adb="adb"):
        self.serial = serial
        self.timeout = timeout
        self.adb = adb

    def redact(self, text):
        host = self.serial.rsplit(":", 1)[0]
        return text.replace(self.serial, "<serial>").replace(host, "<serial>")

    def out(self, command, ok_codes=(0,)):
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
        return proc.stdout

    def log_stream(self, path):
        return LogStream(self, path)


class LogStream:
    """A live `adb logcat -v monotonic` into path, from now on."""

    def __init__(self, device, path):
        self.errors = tempfile.TemporaryFile()
        self.redact = device.redact
        with open(path, "wb") as writer:
            self.proc = subprocess.Popen([device.adb, "-s", device.serial, "logcat", "-v", "monotonic", "-T", "1"],
                                         stdout=writer, stderr=self.errors)

    def alive(self):
        return self.proc.poll() is None

    def stop(self):
        """True when the stream was still running, False when it had ended by itself."""
        time.sleep(0.3)
        ended = self.proc.poll()
        if ended is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self.errors.close()
        return ended is None


def connect(device):
    if device.out("getprop ro.product.device").strip() != "mdarcy":
        raise Refused("the device is not the Shield (ro.product.device is not mdarcy)")
    if any(HEALTH_RE.search(ln) for ln in device.out("logcat -d -v threadtime -t 2000").splitlines()):
        raise DeviceStop("the Shield logged Watchdog or flush_work; stop and tell the owner")


def clock_offset(device):
    """CLOCK_MONOTONIC minus /proc/uptime in seconds, from a timer_list read between two uptime reads."""
    u1 = float(device.out("cat /proc/uptime").split()[0])
    mono = parse_timer_now(device.out("cat /proc/timer_list"))
    u2 = float(device.out("cat /proc/uptime").split()[0])
    if mono is None:
        raise Refused("/proc/timer_list has no `now at` line: cannot align the clocks")
    return mono / 1e9 - (u1 + u2) / 2, mono


def require_session(session):
    session = Path(session).expanduser()
    if not (session / "before" / "complete").is_file():
        raise Refused(f"{session} has no completed snapshot: run scripts/shield_snapshot.sh first (M2)")
    out = session / "frame-gaps" / datetime.now().strftime("%H%M%S")
    out.mkdir(parents=True)
    return out


def capture(device, out, package, seconds, mem_every=2.5, max_dump_every=8.0,
            clock=time.monotonic, sleep=time.sleep):
    connect(device)
    layer = pick_layer(device.out("dumpsys SurfaceFlinger --list"), package)
    if not layer:
        raise Refused(f"no SurfaceView layer for {package}: is a title playing?")
    pid_text = device.out(f"pidof {shq(package)}", ok_codes=(0, 1)).split()
    pid = int(pid_text[0]) if pid_text else None
    offset, mono_ns = clock_offset(device)
    first = device.out(f"dumpsys SurfaceFlinger --latency {shq(layer)}")
    _, first_rows = parse_latency(first)
    if not first_rows:
        raise Refused(f"the layer has no presented frames yet: is {package} playing video?")
    start_ns = max(row[0] for row in first_rows)
    tail = parse_logcat(device.out("logcat -d -v monotonic -t 1").splitlines())
    log_clock_ok = bool(tail) and tail[-1][0] <= mono_ns / 1e9 + 1.0
    stream = device.log_stream(out / "logcat.txt")
    meta = {"package": package, "layer": layer, "pid": pid, "start_ns": start_ns, "mono_minus_uptime_s": offset,
            "log_clock_ok": log_clock_ok, "dumps": [], "layers": [layer], "complete": False}
    write_meta(out, meta)
    t_end = clock() + seconds
    next_mem = next_dump = clock()
    dump_every = 2.0
    n = 0
    with open(out / "mem.jsonl", "w") as mem_file:
        try:
            while clock() < t_end:
                now = clock()
                if now >= next_mem:
                    sample = parse_mem(device.out("cat /proc/uptime /proc/meminfo /proc/vmstat"))
                    if sample:
                        mem_file.write(json.dumps(sample) + "\n")
                        mem_file.flush()
                    next_mem = now + mem_every
                if now >= next_dump:
                    layer, dump_every = dump(device, out, meta, layer, package, n, dump_every, max_dump_every)
                    n += 1
                    next_dump = now + dump_every
                sleep(max(0.05, min(next_mem, next_dump, t_end) - clock()))
            dump(device, out, meta, layer, package, n, dump_every, max_dump_every)
        finally:
            alive = stream.stop()
    meta["log_ended_early"] = not alive
    end_offset, end_mono = clock_offset(device)
    meta["clock_drift_s"] = end_offset - offset
    meta["end_ns"] = end_mono
    meta["complete"] = True
    write_meta(out, meta)
    log_lines = (out / "logcat.txt").read_text(errors="replace").splitlines()
    if any(HEALTH_RE.search(ln) for ln in log_lines):
        raise DeviceStop("the Shield logged Watchdog or flush_work during the capture; stop and tell the owner")
    return out


def write_meta(out, meta):
    """meta.json after every dump, so a capture cut short (the app killed, adb gone) can still be analyzed."""
    tmp = out / "meta.json.tmp"
    tmp.write_text(json.dumps(meta, indent=2) + "\n")
    os.replace(tmp, out / "meta.json")


def dump(device, out, meta, layer, package, n, dump_every, max_dump_every):
    """One --latency dump; an empty dump re-resolves the layer. Returns the layer and the next interval."""
    name = f"lat_{n:04d}.txt"
    text = device.out(f"dumpsys SurfaceFlinger --latency {shq(layer)}")
    _, rows = parse_latency(text)
    entry = {"file": name, "layer": layer}
    if not rows:
        entry["why"] = "empty dump (layer gone or recreated)"
        fresh = pick_layer(device.out("dumpsys SurfaceFlinger --list"), package)
        if fresh and fresh != layer:
            layer = fresh
            meta["layers"].append(fresh)
    (out / name).write_text(text)
    meta["dumps"].append(entry)
    write_meta(out, meta)
    if len(rows) >= 2:
        span = (max(r[0] for r in rows) - min(r[0] for r in rows)) / 1e9
        dump_every = max(1.0, min(max_dump_every, 0.4 * span))
    return layer, dump_every


# ---------------------------------------------------------------- main

def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("capture")
    c.add_argument("--session", required=True, help="a folder scripts/shield_snapshot.sh completed")
    c.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"), help="the Shield (host:5555); never printed")
    c.add_argument("--package", default="com.nuvio.tv.test.shield")
    c.add_argument("--seconds", type=float, default=600)
    c.add_argument("--mem-every", type=float, default=2.5)
    c.add_argument("--adb", default="adb")
    c.add_argument("--adb-timeout", type=float, default=float(os.environ.get("SHIELD_ADB_TIMEOUT", "30")))
    a = sub.add_parser("analyze")
    a.add_argument("capdir")
    a.add_argument("--json", action="store_true")
    args = p.parse_args(argv)
    try:
        if args.cmd == "capture":
            if not args.serial:
                raise Refused("no serial: pass --serial or set ANDROID_SERIAL")
            out = require_session(args.session)
            device = Device(args.serial, args.adb_timeout, args.adb)
            capture(device, out, args.package, args.seconds, args.mem_every)
            result = analyze(out)
            (out / "analysis.json").write_text(json.dumps(result, indent=2) + "\n")
            print(f"capture: {out}")
            print(report(result))
        else:
            result = analyze(args.capdir)
            print(json.dumps(result, indent=2) if args.json else report(result))
    except DeviceStop as e:
        print(f"stop: {e}", file=sys.stderr)
        return EXIT_DEVICE
    except Refused as e:
        print(f"refused: {e}", file=sys.stderr)
        return EXIT_REFUSED
    return 0


if __name__ == "__main__":
    sys.exit(main())
