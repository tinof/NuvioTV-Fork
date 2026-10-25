"""A fake Shield behind `adb` for the shield_snapshot.sh and shield_restore.sh tests.

The state is JSON at $FAKE_ADB_STATE; every argv is appended as a JSON line to $FAKE_ADB_LOG.
`adb shell <string>` is split the way the device shell splits it (shlex), and a pipe, `;`, `&&` or a
redirection is refused: the scripts filter on the host. Hooks in the state:
- hang_on: a substring of the joined argv; that call sleeps past the scripts' timeout.
- tick_volatile: every `settings list global` changes the volatile keys.
- home_restores_mode: HOME also sets the display back to `home_mode`.
- watchdog_after_home: HOME also logs a Watchdog line.
- foreground_after: the foreground becomes this package after the first `dumpsys activity activities`.
- stderr_serial: every shell call also prints a warning naming the serial on stderr, as adb can.
"""

import hashlib
import json
import os
import shlex
import sys
import time

LAUNCHER = "com.google.android.tvlauncher"
OPERATORS = {"|", "||", ";", "&", "&&", ">", ">>", "<"}


def load():
    with open(os.environ["FAKE_ADB_STATE"]) as handle:
        return json.load(handle)


def save(state):
    with open(os.environ["FAKE_ADB_STATE"], "w") as handle:
        json.dump(state, handle)


def log(argv):
    with open(os.environ["FAKE_ADB_LOG"], "a") as handle:
        handle.write(json.dumps(argv) + "\n")


def display_dump(state):
    d = state["display"]
    modes = ", ".join(f"{{id={i}, width={w}, height={h}, fps={f}}}" for i, w, h, f in d["modes"])
    fps = next(f for i, _, _, f in d["modes"] if i == d["active"])
    rate = round(fps)
    return (
        "DISPLAY MANAGER (dumpsys display)\n"
        f"    mActiveModeId={d['active']}\n"
        "    mActiveColorMode=0\n"
        f"    mDefaultModeId={d['default']}\n"
        f"    mDisplayModeSpecs={{baseModeId={d['active']} primaryRefreshRateRange=[{rate} {rate}]}}\n"
        f"    mDesiredDisplayModeSpecs={{baseModeId={d['active']} primaryRefreshRateRange=[{rate} {rate}]}}\n"
        f'    mBaseDisplayInfo=DisplayInfo{{"Built-in", displayId 0, mode {d["active"]}, '
        f"defaultMode {d['default']}, modes [{modes}], rotation 0}}\n"
    )


def shell(state, words):
    cmd = words[0] if words else ""
    out = []
    if cmd == "getprop" and len(words) == 1:
        out = [f"[{k}]: [{v}]" for k, v in sorted(state["props"].items())]
        out += state.get("getprop_extra", [])
    elif cmd == "getprop":
        out = [state["props"].get(words[1], "")]
    elif cmd == "setprop" and len(words) == 3:
        state["props"][words[1]] = words[2]
    elif cmd == "settings" and words[1] == "list":
        ns = state["settings"][words[2]]
        if words[2] == "global" and state.get("tick_volatile"):
            ns["zen_mode_config_etag"] = str(int(ns.get("zen_mode_config_etag", "0")) + 1)
        out = [f"{k}={v}" for k, v in ns.items()]
    elif cmd == "settings" and words[1] == "put":
        state["settings"][words[2]][words[3]] = words[4]
    elif cmd == "settings" and words[1] == "delete":
        state["settings"][words[2]].pop(words[3], None)
    elif cmd == "dumpsys" and words[1:] == ["display"]:
        return display_dump(state), 0
    elif cmd == "dumpsys" and words[1:] == ["activity", "activities"]:
        out = [
            "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)",
            f"    mResumedActivity: ActivityRecord{{24df0e6 u0 {state['foreground']}/.Main t16048}}",
        ]
        if state.get("foreground_after"):
            state["foreground"] = state.pop("foreground_after")
    elif cmd == "pm" and words[1:3] == ["list", "packages"]:
        out = [f"package:{p} versionCode:{v}" for p, v in state["packages"].items()]
    elif cmd == "pm" and words[1] == "path":
        path = state["paths"].get(words[2])
        if path is None or words[2] not in state["packages"]:
            return "", 1
        out = [f"package:{path}"]
    elif cmd == "sha256sum":
        content = state["files"].get(words[1])
        if content is None:
            return f"sha256sum: {words[1]}: No such file or directory\n", 1
        out = [f"{hashlib.sha256(content.encode()).hexdigest()}  {words[1]}"]
    elif cmd == "cat" and words[1:] == ["/proc/uptime"]:
        out = [f"{state['uptime']} 836726.48"]
    elif cmd == "logcat" and "-g" in words:
        # Android 11 prints the largest whole unit: 1024 KiB is "1 MiB".
        out = [
            f"{b}: ring buffer is {k // 1024 if k % 1024 == 0 else k} {'MiB' if k % 1024 == 0 else 'KiB'} "
            "(12 KiB consumed), max entry is 5120 B, max payload is 4068 B"
            for b, k in state["logcat"].items()
        ]
    elif cmd == "logcat" and "-G" in words:
        size = words[words.index("-G") + 1]
        kib = int(size[:-1]) * (1024 if size.endswith("M") else 1)
        targets = [words[words.index("-b") + 1]] if "-b" in words else ["main", "system", "crash"]
        for b in targets:
            state["logcat"][b] = kib
    elif cmd == "logcat" and "-d" in words:
        out = state.get("logcat_lines", [])
    elif cmd == "input" and words[1:] == ["keyevent", "KEYCODE_HOME"]:
        state["foreground"] = LAUNCHER
        if state.get("home_restores_mode"):
            state["display"]["active"] = state["home_mode"]
        if state.get("watchdog_after_home"):
            state.setdefault("logcat_lines", []).append("W Watchdog: *** WATCHDOG KILLING SYSTEM PROCESS: Blocked in handler")
    else:
        return f"/system/bin/sh: {cmd}: not found\n", 127
    return "".join(line + "\n" for line in out), 0


def main(argv):
    log(argv)
    state = load()
    hang = state.get("hang_on")
    if hang and hang in " ".join(argv):
        time.sleep(10)
    if argv[:1] == ["connect"]:
        print(f"connected to {argv[1]}")
        return 0
    if argv[:1] != ["-s"] or argv[1] != state["serial"]:
        sys.stderr.write(f"error: device '{argv[1] if len(argv) > 1 else ''}' not found\n")
        return 1
    rest = argv[2:]
    if rest[:1] == ["pull"]:
        with open(rest[2], "w") as handle:
            handle.write(state["files"][rest[1]])
        return 0
    if rest[:1] != ["shell"]:
        sys.stderr.write(f"fake adb: unsupported {rest}\n")
        return 1
    lexer = shlex.shlex(" ".join(rest[1:]), posix=True, punctuation_chars=True)
    lexer.whitespace_split = True
    words = list(lexer)
    if any(word in OPERATORS for word in words):
        sys.stderr.write(f"fake adb: device-side shell operator in {rest[1:]}\n")
        return 2
    text, rc = shell(state, words)
    save(state)
    if state.get("stderr_serial"):
        sys.stderr.write(f"adb: warning: slow transport to {state['serial']}\n")
    (sys.stdout if rc == 0 else sys.stderr).write(text)
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
