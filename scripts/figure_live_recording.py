#!/usr/bin/env python3
"""Figure of one recorded ring session as the live analysis saw it (for the presentation).

  python scripts/figure_live_recording.py --ring ring.tsv --trace trace.txt --out live.png

--ring   tab-separated export, first column arrival ms, second the green PPG channel
--trace  the replay trace (one line per 5 s: "t=  35 hr=Some(62.0) sqi=0.61 ... move=Some("still")")

Top panel: 20 s of the green PPG around the cleanest pulse window.
Bottom panel: the heart rate estimate for every 5 s window; grey bands are windows with no
estimate (hand moving, weak pulse or no pulse found).
"""
import argparse
import re

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

INK, MUTED, BAND = "#1A1A1A", "#4D4D4D", "#E3E3E3"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ring", required=True)
    ap.add_argument("--trace", required=True)
    ap.add_argument("--out", default="live.png")
    args = ap.parse_args()

    ts, green = [], []
    for line in open(args.ring):
        p = line.split("\t")
        ts.append(int(p[0]))
        green.append(float(p[1]))
    ts, green = np.array(ts) / 1000.0, np.array(green)
    origin = ts[0]

    wins = []
    for line in open(args.trace):
        m = re.match(r"t=\s*(\d+) hr=(Some\(([\d.]+)\)|None) sqi=([\d.]+)", line)
        if m:
            wins.append((int(m.group(1)), float(m.group(3)) if m.group(3) else None, float(m.group(4))))
    t = np.array([w[0] for w in wins]) / 60.0
    hr = np.array([w[1] if w[1] is not None else np.nan for w in wins])
    covered = np.mean(~np.isnan(hr))

    runs = [wins[i - 3:i + 1] for i in range(3, len(wins)) if all(w[1] is not None for w in wins[i - 3:i + 1])]
    run = max(runs, key=lambda r: min(w[2] for w in r))
    best = (run[-1][0], float(np.median([w[1] for w in run])), min(w[2] for w in run))
    lo, hi = origin + best[0] - 20, origin + best[0]
    sel = (ts >= lo) & (ts < hi)
    x, y = ts[sel] - lo, green[sel]
    hz = len(x) / 20.0
    k = max(3, int(round(0.25 * hz)))
    w = int(round(1.5 * hz))
    smooth = np.convolve(y, np.ones(k) / k, mode="same")
    base = np.convolve(y, np.ones(w) / w, mode="same")
    cut = w
    pulse = (smooth - base)[cut:-cut]
    x = x[cut:-cut]

    fig, (a, b) = plt.subplots(2, 1, figsize=(12, 6.2), dpi=200, gridspec_kw={"height_ratios": [1, 1.25]})
    a.plot(x, pulse, color=INK, lw=1.8)
    a.set_title(f"Pulse waveform, 20 s ({best[1]:.0f} bpm estimated, lowest signal quality {best[2]:.2f})", loc="left", fontsize=13, color=INK)
    a.set_xlabel("seconds")
    lo_, hi_ = np.percentile(pulse, [1, 99])
    a.set_ylim(lo_ - 0.15 * (hi_ - lo_), hi_ + 0.15 * (hi_ - lo_))
    a.set_yticks([])
    a.spines[["top", "right", "left"]].set_visible(False)

    step = 5 / 60.0
    for ti, h in zip(t, hr):
        if np.isnan(h):
            b.axvspan(ti - step, ti, color=BAND, lw=0)
    b.plot(t, hr, "o", color=INK, ms=4)
    b.set_xlim(0, t.max())
    b.set_ylim(40, 110)
    b.set_ylabel("bpm", color=MUTED)
    b.set_xlabel("minutes since the recording started")
    b.set_title(f"Heart rate per 5 s window: {covered * 100:.0f}% of windows have an estimate (grey: none)", loc="left", fontsize=13, color=INK)
    b.grid(axis="y", alpha=0.25)
    b.spines[["top", "right"]].set_visible(False)

    fig.tight_layout()
    fig.savefig(args.out, facecolor="white")


if __name__ == "__main__":
    main()
