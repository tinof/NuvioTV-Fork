"""Summarize results/grid44k.jsonl into a table. usage: python3 -I summarize.py results/grid44k.jsonl"""
import json, sys

rows = [json.loads(l) for l in open(sys.argv[1])]
base = next(r for r in rows if r["tag"] == "default")["cpu_s_60s"]


def m(r):
    t = {x["f"]: x for x in r["tones"]}
    inband = max(t[f]["worst_spur_db"] for f in t if f <= 20000)
    return {
        "tag": r["tag"], "lib": r.get("lib"), "ripple": r["ripple_db"], "g20k": r["gain_top_db"],
        "spur<=20k": inband, "spur21k": t[21000]["worst_spur_db"], "spur21.8k": t[21800]["worst_spur_db"],
        "c21.8k": t[21800]["carrier_db"], "noise_img": r.get("noise_image_db"), "noise_img_pk": r.get("noise_image_peak_db"),
        "delay": r["delay_get_delay_out"], "cpu_x": r["cpu_s_60s"] / base, "cpu_ms": r["cpu_s_60s"] * 1000,
    }


ms = [m(r) for r in rows]
hdr = ["tag", "ripple", "g20k", "spur<=20k", "spur21k", "spur21.8k", "c21.8k", "noise_img", "noise_img_pk", "delay", "cpu_ms", "cpu_x"]
filt = sys.argv[2] if len(sys.argv) > 2 else "all"
print(" | ".join(hdr))
for x in ms:
    ok = x["ripple"] <= 0.01 and x["spur<=20k"] <= -120 and x["spur21k"] <= -120
    if filt == "pass" and not ok and x["tag"] not in ("default", "soxr28", "default_brew"):
        continue
    print(" | ".join(f"{x[h]:.4g}" if isinstance(x[h], float) else str(x[h]) for h in hdr) + ("  PASS" if ok else ""))
