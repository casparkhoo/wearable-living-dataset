#!/usr/bin/env python3
"""Score the heart rate the live system showed against the EmbracePlus, per minute and per activity.

For one session, the shown heart rate (display_hr_bpm, else hr_bpm) of each stored live summary
is reduced to its median per minute. It is compared with the EmbracePlus pulse-rate file for the
same minute, overall and separately for minutes spent still (sitting, standing, lying down,
playing the piano) and active (walking, tennis, skipping, stairs, washing...). The best constant
(the median of the reference minutes in each group) is reported as the baseline. Read-only.

    python scripts/score_live_display.py --session 147 --reference <pulse-rate.csv>
"""
from __future__ import annotations

import argparse
import csv
import os
import statistics as st
import subprocess

import numpy as np

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
STILL = ('sitting', 'standing', 'lying', 'piano')


def sql(args, query):
    cmd = ['docker', 'compose', '--env-file', args.env, '-f', args.compose, 'exec', '-T', 'mysql', 'sh', '-c',
           'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" -N -B "$MYSQL_DATABASE"']
    out = subprocess.run(cmd, input=query, capture_output=True, text=True, check=True).stdout
    return [line.split('\t') for line in out.splitlines() if line.strip()]


def stats(name, est, ref):
    est, ref = np.asarray(est, float), np.asarray(ref, float)
    if len(est) < 3:
        print(f'  {name}: {len(est)} minutes'); return
    r = float(np.corrcoef(est, ref)[0, 1]) if est.std() > 0 and ref.std() > 0 else float('nan')
    const = float(np.mean(np.abs(ref - np.median(ref))))
    print(f'  {name}: {len(est)} minutes, MAE {np.mean(np.abs(est - ref)):.1f}, bias {np.mean(est - ref):+.1f}, '
          f'r {r:.2f}, constant MAE {const:.1f}, estimate range {est.min():.0f}-{est.max():.0f}, reference range {ref.min():.0f}-{ref.max():.0f}')


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--env', default=os.path.join(ROOT, 'deployment', 'local', '.env'))
    ap.add_argument('--compose', default=os.path.join(ROOT, 'deployment', 'local', 'docker-compose.yml'))
    ap.add_argument('--session', type=int, required=True)
    ap.add_argument('--reference', required=True)
    ap.add_argument('--field', default='display_hr_bpm', help='display_hr_bpm (shown) or hr_bpm (rules)')
    ap.add_argument('--out')
    args = ap.parse_args()
    ref = {int(float(r['timestamp_unix']) // 60000): float(r['pulse_rate_bpm'])
           for r in csv.DictReader(open(args.reference)) if r['pulse_rate_bpm'] != ''}
    rows = sql(args, "SELECT detected_at_ms, JSON_EXTRACT(details,'$.ppg." + args.field + "') FROM live_events "
                     f"WHERE session_id={args.session} AND event_type='summary';")
    per: dict[int, list[float]] = {}
    for ms, v in rows:
        if v not in ('NULL', 'null', ''):
            per.setdefault(int(ms) // 60000, []).append(float(v))
    est = {m: st.median(v) for m, v in per.items() if len(v) >= 3}
    s = sql(args, f"SELECT started_at_ms, ended_at_ms FROM sessions WHERE id={args.session};")[0]
    lo, hi = int(s[0]), int(float(s[1]))
    notes = sql(args, "SELECT LOWER(label_value), started_at_ms, IFNULL(ended_at_ms,started_at_ms) FROM annotations "
                      f"WHERE label_name='activity' AND started_at_ms BETWEEN {lo - 60000} AND {hi + 60000};")
    def group(minute):
        t0, t1 = minute * 60000, minute * 60000 + 60000
        best, best_ov = None, 0
        for lab, a, b in notes:
            ov = min(t1, int(b)) - max(t0, int(a))
            if ov > best_ov: best, best_ov = lab, ov
        if best is None or best_ov < 30000 or 'ring' in best: return None
        return 'still' if any(k in best for k in STILL) else 'active'
    common = sorted(set(est) & set(ref))
    groups = {m: group(m) for m in common}
    print(f'session {args.session}, field {args.field}: {len(est)} minutes with an estimate, {len(common)} also in the reference')
    stats('all minutes', [est[m] for m in common], [ref[m] for m in common])
    for g in ('still', 'active'):
        ms = [m for m in common if groups[m] == g]
        stats(g, [est[m] for m in ms], [ref[m] for m in ms])
    if args.out:
        with open(args.out, 'w') as f:
            f.write('minute_utc_ms,estimate,reference,group\n')
            for m in common: f.write(f'{m * 60000},{est[m]:.1f},{ref[m]:.0f},{groups[m] or ""}\n')


if __name__ == '__main__':
    main()
