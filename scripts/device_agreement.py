#!/usr/bin/env python3
"""How closely do the devices of one recording agree on heart rate, with no reference device?

Reads the stored live summaries (live_events) of several sessions, takes the shown heart rate
(display_hr_bpm, else hr_bpm) of each, reduces it to the median per minute, and compares every
pair of devices on the minutes where both have a value: mean difference, mean absolute difference,
Pearson r and the number of minutes. Agreement between devices is not accuracy: two sensors can
agree and both be wrong, and the same model sees both signals. Read-only.

    python scripts/device_agreement.py --sessions 145=ring 144=earbudA 146=earbudB
"""
from __future__ import annotations

import argparse
import itertools
import os
import statistics as st
import subprocess

import numpy as np

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')


def sql(args, query):
    cmd = ['docker', 'compose', '--env-file', args.env, '-f', args.compose, 'exec', '-T', 'mysql', 'sh', '-c',
           'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" -N -B "$MYSQL_DATABASE"']
    out = subprocess.run(cmd, input=query, capture_output=True, text=True, check=True).stdout
    return [line.split('\t') for line in out.splitlines() if line.strip()]


def per_minute(args, session):
    rows = sql(args, "SELECT detected_at_ms, "
                     "COALESCE(JSON_EXTRACT(details,'$.ppg.display_hr_bpm'), JSON_EXTRACT(details,'$.ppg.hr_bpm')) "
                     f"FROM live_events WHERE session_id={session} AND event_type='summary';")
    minutes: dict[int, list[float]] = {}
    for ms, hr in rows:
        if hr in ('NULL', 'null', ''):
            continue
        minutes.setdefault(int(ms) // 60000, []).append(float(hr))
    return {m: st.median(v) for m, v in minutes.items() if len(v) >= 3}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--env', default=os.path.join(ROOT, 'deployment', 'local', '.env'))
    ap.add_argument('--compose', default=os.path.join(ROOT, 'deployment', 'local', 'docker-compose.yml'))
    ap.add_argument('--sessions', nargs='+', required=True, help='session=name pairs')
    ap.add_argument('--out', help='write the per-minute table (CSV) here')
    args = ap.parse_args()
    series = {}
    for item in args.sessions:
        sid, name = item.split('=')
        series[name] = per_minute(args, int(sid))
        print(f'{name}: {len(series[name])} minutes with a heart rate')
    for a, b in itertools.combinations(series, 2):
        common = sorted(set(series[a]) & set(series[b]))
        if len(common) < 3:
            print(f'{a} vs {b}: {len(common)} common minutes')
            continue
        x = np.array([series[a][m] for m in common])
        y = np.array([series[b][m] for m in common])
        r = float(np.corrcoef(x, y)[0, 1]) if x.std() > 0 and y.std() > 0 else float('nan')
        print(f'{a} vs {b}: {len(common)} minutes, mean difference {np.mean(x - y):+.1f} bpm, '
              f'mean absolute difference {np.mean(np.abs(x - y)):.1f}, r {r:.2f}, '
              f'{a} range {x.min():.0f}-{x.max():.0f}, {b} range {y.min():.0f}-{y.max():.0f}')
    if args.out:
        minutes = sorted(set().union(*[set(s) for s in series.values()]))
        with open(args.out, 'w') as f:
            f.write('minute_utc_ms,' + ','.join(series) + '\n')
            for m in minutes:
                f.write(f'{m * 60000},' + ','.join(f'{series[n][m]:.1f}' if m in series[n] else '' for n in series) + '\n')


if __name__ == '__main__':
    main()
