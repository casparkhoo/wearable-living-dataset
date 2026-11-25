#!/usr/bin/env python3
"""Cut labelled 10 s accelerometer windows from a local stack's database and embed them with MOMENT.

Reads (read-only, through `docker compose exec` like train_head_from_rules.py):
  * `annotations` rows with label_name='activity' (a start, an end and a label, per experiment),
  * `stream_samples` rows of stream 'accel' for every OpenRing / OpenEarable session of those
    experiments.
and writes one .npz (default: outside the repository, see --out) that
`scripts/train_activity_head.py` reads. Nothing is uploaded; no weights are written to the repo.

Windowing: for every session the samples are ordered as stored (raw packet, sample index) and a
10 s window ending every 5 s is the samples whose backend arrival time falls in the 10 s before
the window end. A window needs at least 80% of the nominal sample count (a dropout rejects it)
and is kept only if one activity label covers at least 90% of it and no *different* label
overlaps it (overlapping contradictory annotations exist; those windows are dropped, not
guessed). Arrival times are batchy (several samples share one arrival), so a window's edges are
accurate to a packet, roughly a second.

Each kept window stores: the raw samples resampled to 512 points per axis (documented in
ml-inference-server/moment_encoder.py), the handcrafted statistics of the raw samples, the MOMENT
embedding of each channel (x, y, z, magnitude), and the labels / grouping ids.

Version 2 (24 Nov 2026): free-text labels are normalised (`normalise_label`), mapped to a movement
INTENSITY class (`INTENSITY`, declared before any result was looked at: still / light / vigorous),
and the 90% cover / no-contradiction rule is applied at the intensity level (so a window that
crosses sitting -> standing is kept, one that crosses walking -> sitting is not). Faults ("loose
ring") and typing are excluded and break a bout. Every window also stores its bout id (a run of
abutting annotations of the same intensity class, used by leave-one-bout-out), the activity that
covers most of it, a gravity-free "dynamic magnitude" channel embedding, and the extra handcrafted
features of scripts/moment_features.py.

    set MOMENT_DIR=<folder holding MOMENT-1-small>   (see docs/IMU_MODEL.md)
    python scripts/moment_windows.py --out <scratch>/imu_windows_v2.npz
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from collections import defaultdict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..')
sys.path.insert(0, os.path.join(ROOT, 'ml-inference-server'))
import moment_encoder as me  # noqa: E402
sys.path.insert(0, HERE)
import moment_features as mf  # noqa: E402

WINDOW_MS = 10_000
STEP_MS = 5_000
NOMINAL_HZ = {'OpenRing': 25.0, 'OpenEarable': 50.0}
MIN_FILL = 0.8
MIN_COVER = 0.9
BOUT_GAP_MS = 3_000     # annotations of one intensity class closer than this form one bout

# Declared before any result was looked at (docs/IMU_MODEL.md).
INTENSITY = {
    'sitting': 'still', 'lying down': 'still', 'standing': 'still', 'piano': 'still',
    'walking': 'light', 'washing': 'light', 'stairs': 'light',
    'tennis': 'vigorous', 'skipping': 'vigorous', 'jogging': 'vigorous', 'running': 'vigorous',
    'star jumps': 'vigorous', 'pushups': 'vigorous',
}
EXCLUDED = {'loose ring': 'fault (the ring was not worn tightly), not an activity',
            'typing': 'hands move while the body is still; not in the declared mapping'}
CLASSES = ('still', 'light', 'vigorous')


def normalise_label(text):
    """Free-text annotation -> canonical activity name (typos included), or the input lower-cased."""
    t = ' '.join(str(text).lower().split())
    for key, canon in (('skipp', 'skipping'), ('pian', 'piano'), ('washing', 'washing'), ('tennis', 'tennis'),
                       ('lopse', 'loose ring'), ('loose', 'loose ring'), ('push', 'pushups'),
                       ('jog', 'jogging'), ('star', 'star jumps'), ('typing', 'typing'),
                       ('lying', 'lying down'), ('stair', 'stairs')):
        if key in t:
            return canon
    return t


def intensity_of(activity):
    """'still' / 'light' / 'vigorous', or None for excluded or unknown labels (they block windows)."""
    return INTENSITY.get(activity)


def sql(base, query):
    cmd = base + ['exec', '-T', 'mysql', 'sh', '-c',
                  'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" -N -B "$MYSQL_DATABASE"']
    out = subprocess.run(cmd, input=query, capture_output=True, text=True, check=True).stdout
    return [line.split('\t') for line in out.splitlines() if line.strip()]


def fetch_labels(base):
    rows = sql(base, "SELECT experiment_id, label_value, started_at_ms, ended_at_ms FROM annotations "
                     "WHERE label_name='activity' AND ended_at_ms IS NOT NULL;")
    out = defaultdict(list)
    for e, v, a, b in rows:
        out[int(e)].append((int(a), int(b), normalise_label(v)))
    return out


def fetch_sessions(base, experiments):
    ids = ','.join(str(e) for e in experiments)
    rows = sql(base, f"SELECT s.id, s.experiment_id, d.device_type FROM sessions s JOIN devices d ON d.id=s.device_id "
                     f"WHERE s.experiment_id IN ({ids}) AND d.device_type IN ('OpenRing','OpenEarable');")
    return [(int(i), int(e), t) for i, e, t in rows]


def fetch_accel(base, session_id):
    rows = sql(base, f"SELECT backend_received_timestamp_ms, JSON_EXTRACT(channel_values,'$.x'), "
                     f"JSON_EXTRACT(channel_values,'$.y'), JSON_EXTRACT(channel_values,'$.z') "
                     f"FROM stream_samples WHERE session_id={session_id} AND stream_name='accel' "
                     f"AND backend_received_timestamp_ms IS NOT NULL ORDER BY raw_packet_id, sample_index;")
    return np.array([[float(v) for v in r] for r in rows if 'NULL' not in r and 'null' not in r], dtype=np.float64)


def label_window(intervals, start, end):
    """The one label that covers >= MIN_COVER of [start, end] with no different label overlapping, else None."""
    overlapping = [(max(a, start), min(b, end), lab) for a, b, lab in intervals if a < end and b > start]
    labels = {lab for _, _, lab in overlapping}
    if len(labels) != 1:
        return None
    lab = labels.pop()
    # union of the same-label intervals (duplicates and abutting segments are common)
    spans = sorted((a, b) for a, b, _ in overlapping)
    covered, cur_a, cur_b = 0, spans[0][0], spans[0][1]
    for a, b in spans[1:]:
        if a <= cur_b:
            cur_b = max(cur_b, b)
        else:
            covered += cur_b - cur_a
            cur_a, cur_b = a, b
    covered += cur_b - cur_a
    return lab if covered >= MIN_COVER * (end - start) else None


def intensity_intervals(intervals):
    """[(a, b, activity)] -> [(a, b, intensity-or-'excluded')]; excluded/unknown labels block windows."""
    return [(a, b, intensity_of(lab) or 'excluded') for a, b, lab in intervals]


def dominant_activity(intervals, start, end):
    cover = defaultdict(int)
    for a, b, lab in intervals:
        o = min(b, end) - max(a, start)
        if o > 0:
            cover[lab] += o
    return max(cover, key=cover.get) if cover else None


def make_bouts(intervals):
    """Merge annotations into bouts: runs of one intensity class with gaps < BOUT_GAP_MS and no
    other (or excluded) label in between. Returns [(a, b, intensity, bout_index)]."""
    iv = sorted((a, b, intensity_of(lab) or 'excluded') for a, b, lab in intervals)
    bouts = []
    for a, b, c in iv:
        if bouts and bouts[-1][2] == c and a - bouts[-1][1] < BOUT_GAP_MS:
            bouts[-1][1] = max(bouts[-1][1], b)
        else:
            bouts.append([a, b, c])
    return [(a, b, c, i) for i, (a, b, c) in enumerate(bouts) if c != 'excluded']


def windows_for_session(samples, intervals, hz):
    """Yield (end_ms, raw[n,3], intensity, activity, bout) for labelled, sufficiently full windows."""
    bouts = make_bouts(intervals)
    iv_int = intensity_intervals(intervals)
    if len(samples) == 0:
        return
    ts = samples[:, 0]
    ts_sorted = np.maximum.accumulate(ts)            # arrival order, made monotone
    first, last = int(ts_sorted[0]) + WINDOW_MS, int(ts_sorted[-1])
    need = int(MIN_FILL * hz * WINDOW_MS / 1000)
    for end in range(first, last + 1, STEP_MS):
        lab = label_window(iv_int, end - WINDOW_MS, end)
        if lab is None or lab == 'excluded':
            continue
        lo = np.searchsorted(ts_sorted, end - WINDOW_MS, side='right')
        hi = np.searchsorted(ts_sorted, end, side='right')
        if hi - lo < need:
            continue
        raw = samples[lo:hi, 1:4]
        mid = end - WINDOW_MS // 2
        bout = next((i for a, b, c, i in bouts if a - BOUT_GAP_MS <= mid <= b + BOUT_GAP_MS and c == lab), -1)
        yield end, raw, lab, dominant_activity(intervals, end - WINDOW_MS, end), bout


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--env', default=os.path.join(ROOT, 'deployment', 'local', '.env'))
    ap.add_argument('--compose', default=os.path.join(ROOT, 'deployment', 'local', 'docker-compose.yml'))
    ap.add_argument('--out', required=True, help='output .npz (keep it outside the repository)')
    args = ap.parse_args()
    base = ['docker', 'compose', '--env-file', args.env, '-f', args.compose]

    labels = fetch_labels(base)
    sessions = fetch_sessions(base, sorted(labels))
    encoder = me.MomentEncoder()
    keep = defaultdict(list)
    for sid, exp, dev in sessions:
        samples = fetch_accel(base, sid)
        n = 0
        for end, raw, lab, act, bout in windows_for_session(samples, labels[exp], NOMINAL_HZ[dev]):
            ch = me.window_channels(raw)
            dyn = me.resample_window(np.linalg.norm(raw - raw.mean(axis=0), axis=1)).T
            keep['channels'].append(ch)
            keep['emb'].append(encoder.embed(ch))
            keep['emb_dyn'].append(encoder.embed(dyn)[0])
            keep['extra'].append(mf.extra_features(raw, NOMINAL_HZ[dev]))
            keep['activity'].append(act)
            keep['bout'].append(f'{exp}:{bout}')
            keep['stats'].append(me.window_stats(raw))
            keep['label'].append(lab)
            keep['intensity'].append(lab)
            keep['session'].append(sid)
            keep['experiment'].append(exp)
            keep['device'].append(dev)
            keep['end_ms'].append(end)
            keep['hz'].append(NOMINAL_HZ[dev])
            n += 1
        print(f'session {sid} (exp {exp}, {dev}): {len(samples)} samples -> {n} labelled windows', flush=True)

    arrays = {k: np.array(v) for k, v in keep.items()}
    arrays['meta'] = np.array(json.dumps({'window_ms': WINDOW_MS, 'step_ms': STEP_MS, 'min_fill': MIN_FILL,
                                          'min_cover': MIN_COVER, 'nominal_hz': NOMINAL_HZ,
                                          'channels': list(me.CHANNELS), 'stats': list(me.STAT_NAMES),
                                          'extra': list(mf.EXTRA_NAMES), 'intensity_map': INTENSITY,
                                          'excluded': EXCLUDED, 'bout_gap_ms': BOUT_GAP_MS}))
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    np.savez_compressed(args.out, **arrays)
    print(f'wrote {args.out}: {len(arrays["label"])} windows')


if __name__ == '__main__':
    main()
