#!/usr/bin/env python3
"""Does a recording's PPG carry the heart rate at all? A deliberately simple check.

For one stored PPG stream (ring green, earbud infrared, ...) take 10 s windows every 5 s, treat the
samples that arrived in each window as evenly spaced, remove the trend, and read the strongest
frequency between 0.7 and 3.3 Hz (42-198 bpm) from a Hann-windowed spectrum. The per-minute median
of those peaks is compared with the EmbracePlus per-minute pulse rate.

This is a diagnostic, not the system's method: it has no quality gate, no motion rejection and no
tracking. If even this peak follows the reference during exercise, the signal holds the pulse and a
model or the rules engine could in principle recover it; if it does not, the signal is dominated by
motion. Read-only: it queries the local MySQL through docker compose exec.

    python scripts/ppg_spectral_check.py --session 141 --channel ir \
        --reference tmp/empatica_nov23/<...>_pulse-rate.csv
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import statistics as st
import subprocess

import numpy as np

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
WINDOW_MS, STEP_MS = 10_000, 5_000


def sql(args, query):
    cmd = ['docker', 'compose', '--env-file', args.env, '-f', args.compose, 'exec', '-T', 'mysql', 'sh', '-c',
           'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" -N -B "$MYSQL_DATABASE"']
    out = subprocess.run(cmd, input=query, capture_output=True, text=True, check=True).stdout
    return [line.split('\t') for line in out.splitlines() if line.strip()]


def read_reference(path):
    ref = {}
    for row in csv.DictReader(open(path, newline='')):
        if row['pulse_rate_bpm'] not in ('', None):
            ref[int(float(row['timestamp_unix']) // 60000)] = float(row['pulse_rate_bpm'])
    return ref


def peak_bpm(values, seconds):
    x = np.asarray(values, float)
    if len(x) < 64 or np.ptp(x) == 0:
        return None
    rate = len(x) / seconds
    x = x - np.polyval(np.polyfit(np.arange(len(x)), x, 2), np.arange(len(x)))
    x = x * np.hanning(len(x))
    n = 1 << 14
    spectrum = np.abs(np.fft.rfft(x, n)) ** 2
    freqs = np.fft.rfftfreq(n, 1 / rate)
    band = (freqs >= 0.7) & (freqs <= 3.3)
    if not band.any():
        return None
    return float(freqs[band][np.argmax(spectrum[band])] * 60)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--env', default=os.path.join(ROOT, 'deployment', 'local', '.env'))
    ap.add_argument('--compose', default=os.path.join(ROOT, 'deployment', 'local', 'docker-compose.yml'))
    ap.add_argument('--session', type=int, required=True)
    ap.add_argument('--channel', required=True, help='ppg channel: green, ir, red')
    ap.add_argument('--reference', required=True)
    ap.add_argument('--out', help='write the per-minute table here')
    args = ap.parse_args()

    rows = sql(args, f"SELECT backend_received_timestamp_ms, IFNULL(sample_index, -1), JSON_EXTRACT(channel_values, '$.{args.channel}') "
                     f"FROM stream_samples WHERE session_id={args.session} AND stream_name='ppg' ORDER BY id;")
    rows = [(int(a), int(i), float(x)) for a, i, x in rows if x not in ('NULL', 'null')]
    if rows and all(i >= 0 for _, i, _ in rows):
        # Sample order, not insertion order (fix of 25 Nov 2026, see train_head_from_rules.sample_order).
        from train_head_from_rules import sample_order
        rows = [(a, i, x) for a, i, x, _ in sample_order(rows)]
    t = np.array([q[0] for q in rows], float)
    v = np.array([q[2] for q in rows], float)
    ref = read_reference(args.reference)
    per_minute: dict[int, list[float]] = {}
    start = t.min()
    while start + WINDOW_MS <= t.max():
        sel = (t >= start) & (t < start + WINDOW_MS)
        if sel.sum() >= 0.6 * 25 * 10:
            bpm = peak_bpm(v[sel], WINDOW_MS / 1000)
            if bpm is not None:
                per_minute.setdefault(int((start + WINDOW_MS / 2) // 60000), []).append(bpm)
        start += STEP_MS
    table = [(m, st.median(vals), len(vals), ref[m]) for m, vals in sorted(per_minute.items())
             if m in ref and len(vals) >= 3]
    if not table:
        print('no overlapping minutes')
        return
    est = np.array([r[1] for r in table])
    tru = np.array([r[3] for r in table])
    mae = float(np.mean(np.abs(est - tru)))
    const = float(np.mean(np.abs(tru - np.median(tru))))
    r = float(np.corrcoef(est, tru)[0, 1]) if len(table) > 2 and est.std() > 0 else float('nan')
    print(f'session {args.session} channel {args.channel}: {len(table)} minutes, MAE {mae:.2f} bpm, '
          f'bias {float(np.mean(est - tru)):+.2f}, r {r:.2f}, best-constant MAE {const:.2f}, '
          f'estimate range {est.min():.0f}-{est.max():.0f}, reference range {tru.min():.0f}-{tru.max():.0f}')
    if args.out:
        with open(args.out, 'w', newline='') as f:
            w = csv.writer(f)
            w.writerow(['minute_utc', 'peak_bpm', 'windows', 'reference_bpm'])
            for m, e, n, rr in table:
                w.writerow([m * 60000, f'{e:.1f}', n, f'{rr:.0f}'])


if __name__ == '__main__':
    main()
