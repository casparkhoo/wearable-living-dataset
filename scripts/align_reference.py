#!/usr/bin/env python3
"""Align a ring recording with the Empatica EmbracePlus and score it against it.

The EmbracePlus is the control: its pulse rate is taken as true and the ring's
estimate is the thing measured. The two never meet in the app, so this script
joins them afterwards on wall-clock time.

    python scripts/align_reference.py ring.csv embrace_pulse_rate.csv \
        [--ref-value pulse_rate_bpm] [--offset-s 0] [--estimate-offset embrace_activity.csv]

ring.csv has one row per sample: received_ms, green, ir, ax, ay, az (see
scripts/export_ring_session.sh). The reference file is any CSV with a time
column (unix seconds or milliseconds, or ISO 8601) and a value column. The
column names are detected, and can be named with --ref-time and --ref-value
because the Download Hub's exact headers have not been checked against a real
export yet.

Outputs a per-window table (CSV, --out) and a summary on stdout: bias, MAE,
the share of windows within 5 bpm, and coverage (how often the ring gave a
rate at all).
"""
import argparse
import csv
import datetime as dt
import sys

import numpy as np

FS = 25.0               # the ring's configured rate; the signal is resampled to it
WINDOW_S = 10.0
HOP_S = 5.0
BAND_BPM = (40.0, 180.0)
EDGE_MARGIN_BPM = 4.0   # same rule as backend/src/live.rs
FAIR_SQI = 0.35
NO_PULSE_PCT = 0.005
STILL_MOVEMENT = 5.0    # mean |d|accel|| per sample below which the hand counts as still


def read_ring(path):
    rows = []
    with open(path, newline="") as handle:
        for line in csv.reader(handle, delimiter=","):
            if len(line) >= 6:
                try:
                    rows.append([float(v) for v in line[:6]])
                except ValueError:
                    continue          # header
    data = np.array(rows)
    if len(data) < FS * WINDOW_S:
        sys.exit("ring file has too few samples")
    return data


def parse_time(value):
    value = value.strip()
    try:
        number = float(value)
    except ValueError:
        stamp = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        if stamp.tzinfo is None:
            stamp = stamp.replace(tzinfo=dt.timezone.utc)
        return stamp.timestamp()
    if number > 1e17:
        return number / 1e9
    if number > 1e14:
        return number / 1e6
    if number > 1e11:
        return number / 1e3
    return number


def read_reference(path, time_col=None, value_col=None):
    with open(path, newline="") as handle:
        reader = csv.DictReader(handle)
        names = reader.fieldnames or []
        if time_col is None:
            time_col = next((n for n in names if "unix" in n.lower()), None) or \
                next((n for n in names if "time" in n.lower()), names[0])
        if value_col is None:
            candidates = [n for n in names if n != time_col and "iso" not in n.lower()
                          and "time" not in n.lower()]
            value_col = candidates[-1]
        times, values = [], []
        for row in reader:
            try:
                stamp = parse_time(row[time_col])
                value = float(row[value_col])
                times.append(stamp)
                values.append(value)
            except (ValueError, KeyError):
                continue
    order = np.argsort(times)
    return np.array(times)[order], np.array(values)[order], time_col, value_col


def moving_average(x, n):
    # Pad with the edge values: zero padding puts a step at each end that is
    # far larger than the pulse and dominates everything downstream.
    pad = n // 2
    padded = np.concatenate([np.full(pad, x[0]), x, np.full(n - pad - 1, x[-1])])
    return np.convolve(padded, np.ones(n) / n, mode="valid")


def estimate_hr(window):
    """(bpm or None, sqi, perfusion %) for one window of PPG at FS.

    The same rule as the live analysis: detrend, take the strongest repeat of
    the signal inside the heart-rate band by autocorrelation, reject a rate at
    the band's edge, and report nothing when the pulse amplitude is noise.
    """
    smooth = window - moving_average(window, int(FS * 1.5))
    baseline = abs(window.mean())
    perfusion = 100.0 * smooth.std() / baseline if baseline else 0.0
    if perfusion < NO_PULSE_PCT or smooth.std() == 0:
        return None, 0.0, perfusion
    x = smooth - smooth.mean()
    corr = np.correlate(x, x, mode="full")[len(x) - 1:]
    corr = corr / corr[0]
    lo = int(FS * 60 / BAND_BPM[1])
    hi = int(FS * 60 / BAND_BPM[0])
    lag = lo + int(np.argmax(corr[lo:hi + 1]))
    sqi = float(corr[lag])
    bpm = 60.0 * FS / lag
    inside = BAND_BPM[0] + EDGE_MARGIN_BPM <= bpm <= BAND_BPM[1] - EDGE_MARGIN_BPM
    if sqi >= FAIR_SQI and inside:
        return bpm, sqi, perfusion
    return None, sqi, perfusion


def ring_windows(ring):
    """Resample to a uniform rate and score every window. Times are unix seconds."""
    t = ring[:, 0] / 1000.0
    tu = np.arange(t[0], t[-1], 1.0 / FS)
    ppg = np.interp(tu, t, ring[:, 1])
    accel = np.vstack([np.interp(tu, t, ring[:, 3 + i]) for i in range(3)]).T
    movement = np.abs(np.diff(np.linalg.norm(accel, axis=1), prepend=0.0))
    n = int(WINDOW_S * FS)
    out = []
    for start in range(0, len(tu) - n, int(HOP_S * FS)):
        bpm, sqi, perfusion = estimate_hr(ppg[start:start + n])
        out.append({
            "start": tu[start],
            "end": tu[start + n - 1],
            "ring_bpm": bpm,
            "sqi": sqi,
            "perfusion_pct": perfusion,
            "movement": float(movement[start:start + n].mean()),
        })
    return out, tu, movement


def estimate_offset(ring_tu, ring_movement, act_t, act_v, max_shift_s=30):
    """How many seconds the reference clock is ahead of the ring's, found by
    cross-correlating how much each device was moving. Only meaningful when the
    recording contains clear movement."""
    grid = np.arange(max(ring_tu[0], act_t[0]), min(ring_tu[-1], act_t[-1]), 1.0)
    if len(grid) < 60:
        return None
    a = np.interp(grid, ring_tu, moving_average(ring_movement, int(FS)))
    b = np.interp(grid, act_t, act_v)
    a = (a - a.mean()) / (a.std() or 1.0)
    b = (b - b.mean()) / (b.std() or 1.0)
    best = (None, -2.0)
    for shift in range(-max_shift_s, max_shift_s + 1):
        x, y = (a[shift:], b[:len(b) - shift]) if shift >= 0 else (a[:shift], b[-shift:])
        if len(x) > 30:
            r = float(np.mean(x * y))
            if r > best[1]:
                best = (shift, r)
    return best


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("ring")
    parser.add_argument("reference")
    parser.add_argument("--ref-time")
    parser.add_argument("--ref-value")
    parser.add_argument("--offset-s", type=float, default=0.0,
                        help="seconds to add to the reference clock to match the ring's")
    parser.add_argument("--estimate-offset", metavar="ACTIVITY_CSV",
                        help="reference movement file (e.g. accelerometer standard deviation) to estimate the clock offset from")
    parser.add_argument("--out", help="write the per-window table here")
    args = parser.parse_args()

    ring = read_ring(args.ring)
    windows, tu, movement = ring_windows(ring)
    rt, rv, tcol, vcol = read_reference(args.reference, args.ref_time, args.ref_value)
    print(f"reference: {len(rt)} rows, time column '{tcol}', value column '{vcol}'")
    offset = args.offset_s
    if args.estimate_offset:
        at, av, _, _ = read_reference(args.estimate_offset)
        shift = estimate_offset(tu, movement, at, av)
        if shift and shift[0] is not None:
            offset = float(shift[0])
            print(f"estimated clock offset {offset:+.0f} s (movement correlation {shift[1]:.2f})")
        else:
            print("offset could not be estimated; using --offset-s")
    rt = rt + offset

    table = []
    for w in windows:
        inside = (rt >= w["start"]) & (rt <= w["end"])
        w["ref_bpm"] = float(rv[inside].mean()) if inside.sum() else None
        table.append(w)

    scored = [w for w in table if w["ref_bpm"] is not None]
    answered = [w for w in scored if w["ring_bpm"] is not None]
    print(f"windows {len(table)}; with a reference {len(scored)}; ring gave a rate in {len(answered)}")
    if not scored:
        print("no overlap in time between the ring and the reference; check the clock offset")
    for label, subset in (("all windows", answered),
                          ("still windows", [w for w in answered if w["movement"] < STILL_MOVEMENT])):
        if not subset:
            print(f"{label}: none")
            continue
        err = np.array([w["ring_bpm"] - w["ref_bpm"] for w in subset])
        print(f"{label}: n={len(err)}  bias {err.mean():+.1f} bpm  MAE {np.abs(err).mean():.1f} bpm  "
              f"within 5 bpm {100 * np.mean(np.abs(err) <= 5):.0f}%")
    if scored:
        print(f"coverage (ring answered where the reference existed): {100 * len(answered) / len(scored):.0f}%")

    if args.out:
        with open(args.out, "w", newline="") as handle:
            writer = csv.writer(handle)
            writer.writerow(["start_unix_s", "ring_bpm", "ref_bpm", "sqi", "perfusion_pct", "movement"])
            for w in table:
                writer.writerow([f"{w['start']:.1f}", w["ring_bpm"], w["ref_bpm"],
                                 f"{w['sqi']:.3f}", f"{w['perfusion_pct']:.4f}", f"{w['movement']:.1f}"])


if __name__ == "__main__":
    main()
