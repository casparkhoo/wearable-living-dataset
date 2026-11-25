#!/usr/bin/env python3
"""Stage 1 of the heart-rate results (hr_evaluate.py is stage 2): read the ring recordings that have EmbracePlus
minutes from the local instance's database, cut 10 s green windows, embed them with PaPaGei-S in the running worker,
and cache everything outside the repository (~/data_public/cache_hr). Read-only towards the database and worker.

    python scripts/hr_prepare.py
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..')
sys.path.insert(0, HERE)

import train_head_from_rules as r          # noqa: E402  (sql helper)
import train_head_public as tp             # noqa: E402  (embed_windows)
from train_head_wide import window_ends, cut_window   # noqa: E402

CACHE = os.path.join(os.path.expanduser('~'), 'data_public', 'cache_hr')
# Ring sessions that overlap EmbracePlus pulse-rate minutes (found by mapping every reference file in tmp/ onto
# the ring sessions), with the last reference minute + 2 min as the upper time bound (session 67 runs for days).
SESSIONS = {55: None, 60: None, 65: None, 66: None, 67: 1793675220000, 140: None, 147: None, 148: None}


def green(base, sid, lo, hi):
    rows = r.sql(base, f"SELECT backend_received_timestamp_ms, sample_index, JSON_EXTRACT(channel_values,'$.green') "
                       f"FROM stream_samples WHERE session_id={sid} AND stream_name='ppg' "
                       f"AND backend_received_timestamp_ms BETWEEN {lo} AND {hi} ORDER BY id;")
    # Sample order, not insertion order (fix of 25 Nov 2026, see train_head_from_rules.sample_order).
    s = r.sample_order([(int(ts), int(i), float(g)) for ts, i, g in rows
                        if ts != 'NULL' and i != 'NULL' and g not in ('NULL', 'null')])
    return np.array([q[0] for q in s], float), np.array([q[2] for q in s], float)


def num(x):
    return None if x in ('NULL', 'null', '', None) else float(x)


def txt(x):
    return None if x in ('NULL', 'null', '', None) else x


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--env', default=os.path.join(ROOT, 'deployment', 'local', '.env'))
    ap.add_argument('--compose', default=os.path.join(ROOT, 'deployment', 'local', 'docker-compose.yml'))
    ap.add_argument('--procs', type=int, default=3)
    args = ap.parse_args()
    base = tp.compose_base(args.env, args.compose)
    os.makedirs(CACHE, exist_ok=True)

    sessions = {int(a): (int(b), int(float(c))) for a, b, c in r.sql(
        base, "SELECT s.id, s.started_at_ms, s.ended_at_ms FROM sessions s JOIN devices d ON d.id=s.device_id "
              "WHERE d.device_type='OpenRing' AND s.ended_at_ms IS NOT NULL;")}
    meta = {'sessions': {k: list(v) for k, v in sessions.items()}, 'per_session': {}}
    summaries, stored, notes = {}, {}, {}
    raws, keys = [], []
    for sid, cap in SESSIONS.items():
        lo, hi = sessions[sid]
        if cap:
            hi = min(hi, cap)
        rows = r.sql(base, "SELECT detected_at_ms, JSON_UNQUOTE(JSON_EXTRACT(details,'$.ppg.quality')), "
                           "JSON_EXTRACT(details,'$.ppg.sqi'), JSON_EXTRACT(details,'$.ppg.hr_bpm'), "
                           "JSON_EXTRACT(details,'$.ppg.display_hr_bpm'), "
                           "JSON_UNQUOTE(JSON_EXTRACT(details,'$.movement.state')) FROM live_events "
                           f"WHERE session_id={sid} AND event_type='summary' AND detected_at_ms BETWEEN {lo} AND {hi};")
        summaries[sid] = [{'t': int(a), 'quality': txt(q), 'sqi': num(s), 'hr': num(h), 'shown': num(d), 'movement': txt(m)}
                          for a, q, s, h, d, m in rows]
        stored[sid] = [(int(e), float(h)) for e, h in r.sql(
            base, f"SELECT window_end_ms, hr_bpm FROM derived_windows WHERE session_id={sid} "
                  f"AND method_id='papagei_s+hr_ring_stretch_v2' AND hr_bpm IS NOT NULL;")]
        notes[sid] = [(lab, int(a), int(float(b))) for lab, a, b in r.sql(
            base, "SELECT LOWER(label_value), started_at_ms, IFNULL(ended_at_ms, started_at_ms) FROM annotations "
                  f"WHERE label_name='activity' AND started_at_ms BETWEEN {lo - 60000} AND {hi + 60000};")]
        t, v = green(base, sid, lo, hi)
        n = 0
        for end in window_ends(t.min(), t.max()):
            x = cut_window(t, v, end, 200)
            if x is not None and np.isfinite(x).all() and x.std() > 1e-6:
                raws.append(x); keys.append((sid, end)); n += 1
        meta['per_session'][sid] = {'bounds': [lo, hi], 'samples': int(len(t)), 'windows': n,
                                    'summaries': len(summaries[sid]), 'stored_v2_rows': len(stored[sid])}
        print(sid, meta['per_session'][sid], flush=True)

    t0 = time.time()
    emb = tp.embed_windows(base, np.array(raws), procs=args.procs)
    meta['embed_seconds'] = round(time.time() - t0, 1)
    ok = np.isfinite(emb).all(1)
    print(f'embedded {ok.sum()} of {len(raws)} windows in {meta["embed_seconds"]} s', flush=True)
    np.savez_compressed(os.path.join(CACHE, 'windows.npz'), X=emb[ok],
                        session=np.array([k[0] for k in keys])[ok], end=np.array([k[1] for k in keys])[ok])
    with open(os.path.join(CACHE, 'db.json'), 'w') as f:
        json.dump({'meta': meta, 'summaries': summaries, 'stored_v2': stored, 'annotations': notes}, f)


if __name__ == '__main__':
    main()
