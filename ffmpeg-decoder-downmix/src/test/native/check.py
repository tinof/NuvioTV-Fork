#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Runs the host transcode cases of ffmpeg_jni.cc and checks them. See README.md.

Run with `python3 -I check.py --driver build/host_jni_test --work <dir>`.
Decoding uses Homebrew FFmpeg (`--ffmpeg`, default /opt/homebrew/opt/ffmpeg-full/bin/ffmpeg) with
-xerror -err_detect crccheck+explode, so a header or XLL CRC error fails the case.
"""
import argparse
import array
import os
import subprocess
import sys

TARGET_DTS = 2
TARGET_DTS_HD = 3
UNIT = 512
AAC_FRAME = 1024
TRIM = 1024  # priming of FFmpeg's native AAC encoder
# Tight bound of a stereo unit with a 2012-byte core (DTSHDMA_MAX_UNIT_BYTES_24).
MAX_UNIT_BYTES = 5369
# End-of-stream loss without the last-sample flag: FIFO remainder (511) + resampler (about 108)
# + the frame held back for alignment (512), in 48 kHz samples.
MAX_UNFLAGGED_LOSS = 1131

failures = []


def check(cond, what):
    print(("ok    " if cond else "FAIL  ") + what)
    if not cond:
        failures.append(what)


class Env:
    def __init__(self, args):
        self.driver = os.path.abspath(args.driver)
        self.ffmpeg = args.ffmpeg
        self.ffprobe = os.path.join(os.path.dirname(args.ffmpeg), "ffprobe")
        self.work = os.path.abspath(args.work)
        os.makedirs(self.work, exist_ok=True)

    def path(self, name):
        return os.path.join(self.work, name)

    def make_aac(self, name, rate, channels, seconds=2.5, loud=False):
        """A low tone per channel plus an impulse at 48 kHz sample 10000 (opposite signs L/R).
        loud: full-scale square waves instead, whose AAC decode overshoots and clips."""
        out = self.path(name)
        if os.path.exists(out):
            return out
        n = int(10000 * rate / 48000)
        exprs = [
            "0.05*sin(2*PI*440*t)+if(eq(n\\,%d)\\,0.9\\,0)" % n,
            "0.04*sin(2*PI*660*t)+if(eq(n\\,%d)\\,-0.8\\,0)" % n,
        ][:channels]
        if loud:
            exprs = ["0.999*sgn(sin(2*PI*1000*t))", "0.999*sgn(sin(2*PI*1500*t))"][:channels]
        subprocess.run([self.ffmpeg, "-nostdin", "-hide_banner", "-v", "error", "-y",
                        "-filter_complex", "aevalsrc=exprs='%s':s=%d:d=%s" % ("|".join(exprs), rate, seconds),
                        "-c:a", "aac", "-b:a", "256k", "-f", "adts", out], check=True)
        return out

    def run(self, aac, target, tag, extra=()):
        out, oracle = self.path(tag + ".dts"), self.path(tag + ".s32")
        cmd = [self.driver, "--in", aac, "--target", str(target), "--trim", str(TRIM),
               "--out", out, "--oracle", oracle] + list(extra)
        p = subprocess.run(cmd, capture_output=True, text=True)
        with open(self.path(tag + ".log"), "w") as f:
            f.write(p.stderr)
        check(p.returncode == 0, "%s: driver ran (rc=%d)" % (tag, p.returncode))
        stats = {}
        for line in p.stdout.splitlines():
            fields = dict(kv.split("=", 1) for kv in line.split())
            if "segment" in fields:
                key = "segment%s" % fields["segment"]
            else:
                key = line.split("_", 1)[0] if line.startswith(("hd_", "seek_")) else "run"
            stats[key] = {k: int(v) for k, v in fields.items()}
        return out, oracle, stats, p.stderr

    def decode(self, dts, core_only=False):
        """Decodes a raw DTS stream to interleaved s32 with every check FFmpeg has."""
        cmd = [self.ffmpeg, "-nostdin", "-hide_banner", "-v", "error", "-xerror",
               "-err_detect", "crccheck+explode"]
        if core_only:
            cmd += ["-core_only", "1"]
        cmd += ["-f", "dts", "-i", dts, "-f", "s32le", "-"]
        p = subprocess.run(cmd, capture_output=True)
        return p.returncode, s32(p.stdout), p.stderr.decode(errors="replace")

    def probe(self, dts):
        p = subprocess.run([self.ffprobe, "-v", "error", "-f", "dts", "-show_entries",
                            "stream=profile,channels,sample_rate", "-of", "default=nw=1", dts],
                           capture_output=True, text=True)
        return dict(line.split("=", 1) for line in p.stdout.splitlines() if "=" in line)


def s32(data):
    a = array.array("i")
    a.frombytes(data[: len(data) // 4 * 4])
    if sys.byteorder != "little":
        a.byteswap()
    return a


def read_s32(path):
    with open(path, "rb") as f:
        return s32(f.read())


def channel(samples, ch, nch):
    return samples[ch::nch]


def peak(x):
    best, at = -1, -1
    for i, v in enumerate(x):
        if abs(v) > best:
            best, at = abs(v), i
    return at


def snr_db(ref, test, lag=0):
    """Signal-to-error ratio of test against ref shifted by lag samples."""
    import math
    sig = err = 0.0
    for i in range(max(0, -lag), min(len(ref), len(test) - lag)):
        r = float(ref[i])
        sig += r * r
        d = r - float(test[i + lag])
        err += d * d
    return 99.0 if err == 0 else 10 * math.log10(sig / err)


def check_alignment(tag, oracle, decoded, nch):
    """The decoded core peaks where the PCM sent to the core encoder peaks: no 512-sample lag."""
    for ch in range(nch):
        o, d = channel(oracle, ch, nch), channel(decoded, ch, nch)
        po, pd = peak(o), peak(d)
        check(abs(po - pd) <= 1, "%s: ch%d core peak %d at the input peak %d" % (tag, ch, pd, po))
        aligned, late = snr_db(o, d, 0), snr_db(o, d, UNIT)
        # The core is lossy (about 25 dB around the impulse, 46 dB on the tones alone); one frame
        # late, the same signal scores 11 dB or less.
        check(aligned > 20 and aligned > late + 10,
              "%s: ch%d core SNR %.1f dB aligned vs %.1f dB one frame late" % (tag, ch, aligned, late))


def case_eos(env, target, aac, rate, nch, packets, tag):
    """Flagged end of stream: nothing lost, only the last unit's padding added."""
    out, oracle_path, st, _ = env.run(aac, target, tag)
    oracle = read_s32(oracle_path)
    run, seg = st["run"], st["segment0"]
    frames = seg["encoder_frames"]
    check(run["failures"] == 0 and run["frame_mismatch"] == 0,
          "%s: encoded-sample count moves 512 per emitted unit" % tag)
    check(seg["bad_core_size"] == 0 and seg["unparsed"] == 0, "%s: every core is 2012 bytes (n7.0.2 at 1509 kbit/s)" % tag)
    check(seg["units"] == frames - 1,
          "%s: units %d = frames sent %d - 1 (pre-roll dropped, zero frame flushes the last)" % (tag, seg["units"], frames))
    check(len(oracle) == frames * UNIT * nch_out(target, nch), "%s: oracle holds every sent frame" % tag)
    if rate == 48000:
        real = packets * AAC_FRAME - TRIM
        check(seg["units"] * UNIT == (real + UNIT - 1) // UNIT * UNIT,
              "%s: %d units cover all %d input samples (no loss)" % (tag, seg["units"], real))
    return out, oracle, st


def nch_out(target, nch):
    return 2 if target == TARGET_DTS_HD else nch


def run_core(env):
    for rate, nch in ((48000, 2), (48000, 1), (44100, 2)):
        tag = "core_%d_%dch" % (rate, nch)
        aac = env.make_aac("in_%d_%d.aac" % (rate, nch), rate, nch)
        packets = count_adts(aac)
        out, oracle, st = case_eos(env, TARGET_DTS, aac, rate, nch, packets, tag)
        rc, dec, err = env.decode(out)
        check(rc == 0, "%s: strict decode ok %s" % (tag, err.strip()[:200]))
        check(len(dec) == len(oracle) - UNIT * nch,
              "%s: decoded %d samples = sent minus the zero frame" % (tag, len(dec) // nch))
        check_alignment(tag, oracle, dec, nch)
        if rate == 48000 and nch == 2:
            case_unflagged(env, TARGET_DTS, aac, nch, packets, "core_noflag")
            case_reset(env, TARGET_DTS, aac, nch, "core_reset")
            for name, start, drop in (("core_seek_mid", 80 * 21333 + 5000, False),
                                      ("core_seek_on", 81 * 64000 // 3, True)):
                case_seek(env, TARGET_DTS, aac, nch, start, name, drop)


def case_unflagged(env, target, aac, nch, packets, tag):
    """No last-sample flag: no flush, so the FIFO remainder and the held frame are lost (bounded)."""
    out, oracle_path, st, _ = env.run(aac, target, tag, ["--no-eos-flag"])
    seg = st["segment0"]
    real = packets * AAC_FRAME - TRIM
    lost = real - seg["units"] * UNIT
    check(0 < lost <= MAX_UNFLAGGED_LOSS, "%s: %d samples lost without the flag (bound %d)" % (tag, lost, MAX_UNFLAGGED_LOSS))
    check(seg["units"] == seg["encoder_frames"] - 1, "%s: the last sent frame stays held" % tag)


def case_reset(env, target, aac, nch, tag):
    """A seek: the reset drops what the encoder holds; after it, packet 0 is dropped again."""
    out, oracle_path, st, _ = env.run(aac, target, tag, [
        "--reset-at", "50", "--resume-from", "70",
        "--out-after-reset", env.path(tag + "_after.dts"),
        "--oracle-after-reset", env.path(tag + "_after.s32")])
    before, after = st["segment0"], st["segment1"]
    check(before["units"] == before["encoder_frames"] - 1,
          "%s: before the seek, %d units for %d frames (the held frame dropped by the reset)" % (tag, before["units"], before["encoder_frames"]))
    check(after["units"] == after["encoder_frames"] - 1,
          "%s: after the seek, %d units for %d frames (pre-roll dropped again)" % (tag, after["units"], after["encoder_frames"]))
    n = nch_out(target, nch)
    for name in ("", "_after"):
        oracle = read_s32(env.path(tag + name + ".s32"))
        rc, dec, err = env.decode(env.path(tag + name + ".dts"), core_only=True)
        check(rc == 0 and len(dec) == len(oracle) - UNIT * n,
              "%s%s: decoded length is sent minus one frame" % (tag, name))
        check_alignment(tag + name, oracle, dec, n)
        if target == TARGET_DTS_HD:
            rc, dec, err = env.decode(env.path(tag + name + ".dts"))
            check(rc == 0 and dec == oracle[: len(dec)] and len(dec) == len(oracle) - UNIT * n,
                  "%s%s: XLL bit-exact against the PCM sent after the reset" % (tag, name))


def case_seek(env, target, aac, nch, start_us, tag, expect_drop):
    """A seek that resumes at packet 70 with a later target: the input-time skip and the
    output-time discard together make the output start at the first unit at or after the target."""
    out, oracle_path, st, _ = env.run(aac, target, tag, [
        "--reset-at", "50", "--resume-from", "70", "--start-us", str(start_us),
        "--out-after-reset", env.path(tag + "_after.dts"),
        "--oracle-after-reset", env.path(tag + "_after.s32")])
    seek, after, run = st["seek"], st["segment1"], st["run"]
    unit_us = UNIT * 1000000 // 48000
    first_us, first = seek["seek_first_unit_us"], seek["seek_first_unit"]
    check(run["frame_mismatch"] == 0, "%s: dropped units still count as encoded samples" % tag)
    if expect_drop:
        # The target on a packet: that packet is accepted, and its first unit is the one held
        # back from the packet before, 10.67 ms early.
        check(seek["seek_dropped_by_output"] >= 1,
              "%s: %d units of an accepted packet dropped by their output time" % (tag, seek["seek_dropped_by_output"]))
    check(first_us + 20 >= start_us and first_us - unit_us + 20 < start_us,
          "%s: first unit out at %d us, the first one at or after the target %d" % (tag, first_us, start_us))
    n = nch_out(target, nch)
    oracle = read_s32(env.path(tag + "_after.s32"))
    expected = oracle[first * UNIT * n: len(oracle) - UNIT * n]
    check(after["units"] * UNIT * n == len(expected),
          "%s: %d units out from unit %d to the end" % (tag, after["units"], first))
    rc, core, err = env.decode(env.path(tag + "_after.dts"), core_only=True)
    check(rc == 0 and len(core) == len(expected), "%s: the cut stream decodes" % tag)
    for ch in range(n):
        o, d = channel(expected, ch, n), channel(core, ch, n)
        aligned, late = snr_db(o, d, 0), snr_db(o, d, UNIT)
        check(aligned > 20 and aligned > late + 10,
              "%s: ch%d the cut lands on a unit boundary (SNR %.1f dB vs %.1f)" % (tag, ch, aligned, late))
    if target == TARGET_DTS_HD:
        rc, dec, err = env.decode(env.path(tag + "_after.dts"))
        check(rc == 0 and dec == expected, "%s: XLL bit-exact from the first unit kept" % tag)


def count_adts(path):
    data = open(path, "rb").read()
    pos = n = 0
    while pos + 7 <= len(data):
        length = ((data[pos + 3] & 3) << 11) | (data[pos + 4] << 3) | (data[pos + 5] >> 5)
        pos += length
        n += 1
    return n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--driver", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--ffmpeg", default="/opt/homebrew/opt/ffmpeg-full/bin/ffmpeg")
    ap.add_argument("--targets", default="core,hd")
    args = ap.parse_args()
    env = Env(args)
    targets = args.targets.split(",")
    if "core" in targets:
        run_core(env)
    if "hd" in targets:
        run_hd(env)
    print("\n%s" % ("ALL HOST CHECKS PASSED" if not failures else "%d CHECKS FAILED" % len(failures)))
    return 1 if failures else 0


def run_hd(env):
    for rate, nch, loud in ((48000, 2, False), (48000, 1, False), (44100, 2, False), (48000, 2, True)):
        tag = "hd_%d_%dch%s" % (rate, nch, "_loud" if loud else "")
        aac = env.make_aac("in_%d_%d%s.aac" % (rate, nch, "_loud" if loud else ""), rate, nch, loud=loud)
        packets = count_adts(aac)
        out, oracle, st = case_eos(env, TARGET_DTS_HD, aac, rate, nch, packets, tag)
        seg, hd, run = st["segment0"], st["hd"], st["run"]
        check(run["channel_count"] == 2 and run["sample_rate"] == 48000,
              "%s: decoder reports 48000 Hz 2 ch" % tag)
        check(seg["hd_units"] == seg["units"] and seg["core_only_units"] == 0,
              "%s: every unit is core + ExSS" % tag)
        check(hd["hd_writer_errors"] == 0 and hd["hd_clipped"] == 0,
              "%s: no writer errors, nothing clipped by the writer" % tag)
        check(seg["max_unit"] <= MAX_UNIT_BYTES and hd["hd_max_unit"] == seg["max_unit"],
              "%s: largest unit %d <= tight bound %d" % (tag, seg["max_unit"], MAX_UNIT_BYTES))
        info = env.probe(out)
        check(info.get("profile") == "DTS-HD MA" and info.get("channels") == "2",
              "%s: probes as %s %s ch" % (tag, info.get("profile"), info.get("channels")))
        rc, dec, err = env.decode(out)
        check(rc == 0 and "XLL" not in err, "%s: strict XLL decode ok %s" % (tag, err.strip()[:200]))
        check(len(dec) == len(oracle) - UNIT * 2 and dec == oracle[: len(dec)],
              "%s: XLL decodes bit-exactly to the PCM sent to the core encoder" % tag)
        check(all(v & 0xFF == 0 for v in oracle), "%s: the core encoder got the 24-bit values" % tag)
        if nch == 1:
            check(channel(oracle, 0, 2) == channel(oracle, 1, 2) and channel(dec, 0, 2) == channel(dec, 1, 2),
                  "%s: dual mono, L == R bit for bit" % tag)
        else:
            check(channel(dec, 0, 2) != channel(dec, 1, 2), "%s: L and R stay apart" % tag)
        if not loud:
            rc, core, err = env.decode(out, core_only=True)
            check(rc == 0 and len(core) == len(dec), "%s: the core alone decodes to the same length" % tag)
            check_alignment(tag + " core", oracle, core, 2)
        if rate == 48000 and nch == 2 and not loud:
            case_unflagged(env, TARGET_DTS_HD, aac, nch, packets, "hd_noflag")
            case_reset(env, TARGET_DTS_HD, aac, nch, "hd_reset")
            for name, start, drop in (("hd_seek_mid", 80 * 21333 + 5000, False),
                                      ("hd_seek_on", 81 * 64000 // 3, True)):
                case_seek(env, TARGET_DTS_HD, aac, nch, start, name, drop)


if __name__ == "__main__":
    sys.exit(main())
