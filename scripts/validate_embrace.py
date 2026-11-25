#!/usr/bin/env python3
"""Score the ring's live heart rate against the EmbracePlus per-minute pulse rate.

Inputs
  --trace   output of the backend replay (one line per 5 s: "t=  35 hr=Some(62.0) ...")
              LIVE_REPLAY_TRACE=1 LIVE_REPLAY_TSV=ring.tsv \\
                cargo test replay_recorded -- --ignored --nocapture | grep '^t=' > trace.txt
  --ring    the same ring.tsv (first column: arrival ms, gives the wall-clock origin)
  --embrace the EmbracePlus *_pulse-rate.csv from the Empatica bucket (scripts/empatica_s3.py)

An EmbracePlus minute is taken to start at its timestamp. The ring value for that minute is
the median of the 5 s window estimates that had a heart rate; a minute needs at least 8
windows (of 12) to count, and at least one estimate.

Outputs a per-minute CSV (--out), a figure (--png) and the summary on stdout.
"""
import argparse
import csv
import datetime as dt
import re
import statistics as st


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--trace", required=True)
    ap.add_argument("--ring", required=True)
    ap.add_argument("--embrace", required=True)
    ap.add_argument("--out", default="validation.csv")
    ap.add_argument("--png", default="validation.png")
    ap.add_argument("--min-windows", type=int, default=8)
    args = ap.parse_args()

    origin = int(open(args.ring).readline().split("\t")[0]) / 1000.0
    ring = {}
    for line in open(args.trace):
        m = re.match(r"t=\s*(\d+) hr=(Some\(([\d.]+)\)|None)", line)
        if not m:
            continue
        hr = float(m.group(3)) if m.group(3) else None
        minute = int((origin + int(m.group(1))) // 60 * 60)
        ring.setdefault(minute, []).append(hr)

    emb = {}
    for row in csv.DictReader(open(args.embrace)):
        if row["pulse_rate_bpm"]:
            emb[int(row["timestamp_unix"]) // 1000] = float(row["pulse_rate_bpm"])

    rows, diffs = [], []
    for minute in sorted(ring):
        vals = [v for v in ring[minute] if v is not None]
        ref = emb.get(minute)
        value = st.median(vals) if vals else None
        counted = ref is not None and value is not None and len(ring[minute]) >= args.min_windows
        rows.append((minute, ref, value, len(vals), len(ring[minute]), counted))
        if counted:
            diffs.append(value - ref)

    with open(args.out, "w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["minute_utc", "embraceplus_bpm", "ring_bpm", "ring_windows_with_hr", "ring_windows", "scored"])
        for minute, ref, value, n, total, counted in rows:
            writer.writerow([dt.datetime.fromtimestamp(minute, dt.timezone.utc).strftime("%H:%M"), ref, value, n, total, int(counted)])

    print(f"minutes scored: {len(diffs)}")
    if diffs:
        print(f"bias {st.mean(diffs):+.1f} bpm, MAE {st.mean(abs(x) for x in diffs):.1f} bpm, "
              f"within 5 bpm {sum(abs(x) <= 5 for x in diffs)}/{len(diffs)}")
    covered = sum(1 for r in rows if r[3] > 0) / max(1, len(rows))
    print(f"ring gave a heart rate in {100 * covered:.0f}% of minutes")

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    xs = [dt.datetime.fromtimestamp(r[0], dt.timezone.utc) for r in rows]
    fig, ax = plt.subplots(figsize=(9, 4.2), dpi=200)
    ax.plot(xs, [r[1] if r[1] is not None else float("nan") for r in rows], "o-", color="#1A1A1A", lw=2.2, label="EmbracePlus (reference)")
    ax.plot(xs, [r[2] if r[2] is not None else float("nan") for r in rows], "s-", color="#E0B800", lw=2.2, label="τ-Ring (live estimate)")
    ax.set_ylabel("Heart rate (bpm)")
    ax.set_xlabel("Time (UTC)")
    ax.grid(alpha=0.25)
    ax.legend(frameon=False)
    ax.spines[["top", "right"]].set_visible(False)
    fig.tight_layout()
    fig.savefig(args.png)


if __name__ == "__main__":
    main()
