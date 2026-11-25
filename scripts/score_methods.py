#!/usr/bin/env python3
"""Score every heart-rate method in a dataset export against a reference device.

    python scripts/score_methods.py experiment_12.zip --reference pulse-rate.csv [--out scores]

Input
  export   a dataset export (GET /api/experiments/:id/export, or
           `wearable-backend export`), or a derived_windows CSV
           (GET /api/evidence-sessions/:id/derived?format=csv). Each distinct
           (method_id, method_version, run) is scored as its own method.
  --reference  one or more EmbracePlus *_pulse-rate.csv files
           (timestamp_unix in ms, pulse_rate_bpm), from scripts/empatica_s3.py.

Method
  The reference gives one value per minute, so each method is reduced to one
  value per minute: the median of its window estimates whose centre falls in
  that minute, counted when at least --min-estimates windows had a heart rate.
  For every method, on exactly the minutes it scored:
    coverage   share of reference minutes within the recording it scored
    bias, SD, 95% limits of agreement (Bland-Altman), MAE, RMSE, Pearson r,
    share within 5 bpm
    best constant  the MAE of the single value that fits those reference
               minutes best (their median). A method that does not beat it has
               not shown it tracks heart rate at all; it is the baseline that
               exposes a method which only predicts the average.
  Methods are also compared head to head on the minutes all of them scored,
  and per protocol phase when the export has annotations.

Output: summary.json, minutes.csv, a printed table and agreement.png.
"""
import argparse
import csv
import io
import json
import math
import os
import statistics as st
import sys
import zipfile
from collections import defaultdict


def read_export(path):
    """Derived rows and annotations from an export zip or a plain CSV."""
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            names = set(archive.namelist())
            if 'derived/derived_windows.csv' not in names:
                sys.exit('export has no derived/derived_windows.csv (derived results not enabled?)')
            rows = list(csv.DictReader(io.TextIOWrapper(archive.open('derived/derived_windows.csv'), 'utf-8')))
            notes = []
            if 'annotations.csv' in names:
                notes = list(csv.DictReader(io.TextIOWrapper(archive.open('annotations.csv'), 'utf-8')))
        return rows, notes
    return list(csv.DictReader(open(path, newline=''))), []


def read_reference(paths):
    ref = {}
    for path in paths:
        for row in csv.DictReader(open(path, newline='')):
            if row.get('pulse_rate_bpm'):
                ref[int(row['timestamp_unix']) // 1000 // 60 * 60] = float(row['pulse_rate_bpm'])
    return ref


def method_key(row, by_run):
    """A method is its id and version; with --by-run each run is kept apart too."""
    key = f"{row['method_id']} {row['method_version']}"
    if by_run:
        run = row.get('run_id') or 'live'
        key += f" [{'live' if run == 'live' else 'run ' + run}]"
    return key


def latest_runs(rows):
    """Keep, per recording and method, only the newest recompute (or the live rows if none),
    so a method rerun under a newer version is scored once, as its newest version."""
    newest = {}
    for row in rows:
        group = (row['session_id'], row['method_id'])
        run = int(row['run_id']) if row.get('run_id') else -1
        newest[group] = max(newest.get(group, -1), run)
    return [r for r in rows
            if (int(r['run_id']) if r.get('run_id') else -1) == newest[(r['session_id'], r['method_id'])]]


def per_minute(rows, min_estimates, by_run=False):
    """{method: {minute: (median bpm, estimates, windows)}} and each method's time span."""
    buckets = defaultdict(lambda: defaultdict(list))
    span = {}
    for row in rows:
        key = method_key(row, by_run)
        centre = (int(row['window_start_ms']) + int(row['window_end_ms'])) / 2000.0
        minute = int(centre // 60 * 60)
        hr = float(row['hr_bpm']) if row.get('hr_bpm') not in (None, '') else None
        buckets[key][minute].append(hr)
        session_spans = span.setdefault(key, {})
        lo, hi = session_spans.get(row['session_id'], (centre, centre))
        session_spans[row['session_id']] = (min(lo, centre), max(hi, centre))
    out = {}
    for key, minutes in buckets.items():
        out[key] = {}
        for minute, values in minutes.items():
            got = [v for v in values if v is not None]
            if len(got) >= min_estimates:
                out[key][minute] = (st.median(got), len(got), len(values))
    return out, span


def agreement(pairs):
    """Agreement statistics for [(estimate, reference)]."""
    n = len(pairs)
    if n == 0:
        return {'minutes': 0}
    diffs = [e - r for e, r in pairs]
    refs = [r for _, r in pairs]
    ests = [e for e, _ in pairs]
    bias = st.mean(diffs)
    sd = st.stdev(diffs) if n > 1 else 0.0
    best_constant = st.median(refs)
    out = {
        'minutes': n,
        'bias': round(bias, 2),
        'sd': round(sd, 2),
        'loa_low': round(bias - 1.96 * sd, 1),
        'loa_high': round(bias + 1.96 * sd, 1),
        'mae': round(st.mean(abs(d) for d in diffs), 2),
        'rmse': round(math.sqrt(st.mean(d * d for d in diffs)), 2),
        'within_5_bpm': round(sum(abs(d) <= 5 for d in diffs) / n, 3),
        'reference_range': [min(refs), max(refs)],
        'reference_sd': round(st.stdev(refs), 2) if n > 1 else 0.0,
        'best_constant_bpm': best_constant,
        'best_constant_mae': round(st.mean(abs(best_constant - r) for r in refs), 2),
    }
    out['beats_best_constant'] = out['mae'] < out['best_constant_mae']
    if n >= 3 and st.pstdev(refs) > 0 and st.pstdev(ests) > 0:
        out['pearson_r'] = round(st.correlation(ests, refs), 3)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('export', nargs='+', help='one or more exports or derived CSVs')
    ap.add_argument('--reference', nargs='+', required=True)
    ap.add_argument('--out', default='scores')
    ap.add_argument('--min-estimates', type=int, default=3,
                    help='window estimates a minute needs to count (default 3 of the ~12 in a minute)')
    ap.add_argument('--session', type=int, help='only this recording')
    ap.add_argument('--by-run', action='store_true',
                    help='score every run separately; default: the newest run per recording and method')
    ap.add_argument('--heads-dir', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'ml-inference-server', 'heads'),
                    help='where head manifests are, to warn when a head is scored on its own training data')
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    args.trained_on = {}
    if os.path.isdir(args.heads_dir):
        for name in os.listdir(args.heads_dir):
            if name.endswith('.json'):
                manifest = json.load(open(os.path.join(args.heads_dir, name)))
                recordings = manifest.get('training', {}).get('recordings')
                if recordings:
                    args.trained_on[manifest['head_id']] = recordings

    rows, notes = [], []
    for path in args.export:
        more_rows, more_notes = read_export(path)
        rows += more_rows
        notes += more_notes
    if args.session is not None:
        rows = [r for r in rows if int(r['session_id']) == args.session]
    if not rows:
        sys.exit('no derived windows to score')
    if not args.by_run:
        rows = latest_runs(rows)
    ref = read_reference(args.reference)
    methods, span = per_minute(rows, args.min_estimates, args.by_run)

    summary = {'reference_minutes': len(ref), 'min_estimates': args.min_estimates, 'methods': {}}
    for key in sorted(methods):
        in_span = [m for m in ref if any(lo - 60 < m <= hi for lo, hi in span[key].values())]
        pairs = [(methods[key][m][0], ref[m]) for m in in_span if m in methods[key]]
        stats = agreement(pairs)
        stats['coverage'] = round(len(pairs) / len(in_span), 3) if in_span else None
        stats['reference_minutes_in_span'] = len(in_span)
        summary['methods'][key] = stats

    common = set(ref)
    for key in methods:
        common &= set(methods[key])
    summary['head_to_head'] = {
        'minutes': len(common),
        'methods': {key: agreement([(methods[key][m][0], ref[m]) for m in sorted(common)]) for key in sorted(methods)},
    }

    phases = defaultdict(list)
    for note in notes:
        if note.get('ended_at_ms'):
            phases[f"{note['label_name']}={note['label_value']}"].append(
                (int(note['started_at_ms']) / 1000, int(note['ended_at_ms']) / 1000))
    if phases:
        summary['by_phase'] = {}
        for phase, ranges in sorted(phases.items()):
            inside = [m for m in ref if any(a <= m + 30 < b for a, b in ranges)]
            summary['by_phase'][phase] = {
                key: agreement([(methods[key][m][0], ref[m]) for m in inside if m in methods[key]])
                for key in sorted(methods)
            }

    warnings = []
    ref_sds = [s.get('reference_sd', 0) for s in summary['methods'].values() if s['minutes'] > 1]
    if ref_sds and max(ref_sds) < 5:
        warnings.append('The reference varies by less than 5 bpm (SD) over the scored minutes, so the '
                        'statistics cannot separate a method that tracks heart rate from one that predicts '
                        'the average. Record with rest and exercise phases.')
    for head in args.trained_on or []:
        sessions = sorted({r['session_id'] for r in rows if head in r['method_id']})
        warnings.append(f"{head} was trained on recordings {args.trained_on[head]}; scores on recordings "
                        f"{sessions} are in-sample if they overlap. Use leave-one-recording-out "
                        "(scripts/papagei_posthoc.py) for a fair figure.")
    summary['warnings'] = warnings

    with open(os.path.join(args.out, 'summary.json'), 'w') as handle:
        json.dump(summary, handle, indent=2)
    with open(os.path.join(args.out, 'minutes.csv'), 'w', newline='') as handle:
        writer = csv.writer(handle)
        keys = sorted(methods)
        writer.writerow(['minute_unix', 'reference_bpm'] + keys)
        for minute in sorted(set(ref) | {m for k in keys for m in methods[k]}):
            writer.writerow([minute, ref.get(minute, '')] +
                            [methods[k][minute][0] if minute in methods[k] else '' for k in keys])

    print(f"{'method':<52} {'min':>4} {'cover':>6} {'bias':>6} {'LoA':>14} {'MAE':>5} {'RMSE':>5} {'r':>6} {'const MAE':>9}")
    for key, s in summary['methods'].items():
        if not s['minutes']:
            print(f"{key:<52} {0:>4}")
            continue
        loa = f"{s['loa_low']:+.1f}..{s['loa_high']:+.1f}"
        print(f"{key:<52} {s['minutes']:>4} {s['coverage'] or 0:>6.0%} {s['bias']:>+6.1f} {loa:>14} "
              f"{s['mae']:>5.1f} {s['rmse']:>5.1f} {s.get('pearson_r', float('nan')):>6.2f} "
              f"{s['best_constant_mae']:>9.1f}{'' if s['beats_best_constant'] else '  (no better than a constant)'}")
    print(f"head to head on {len(common)} common minutes: " + ', '.join(
        f"{k.split(' ')[0]} MAE {v.get('mae', float('nan')):.1f}" for k, v in summary['head_to_head']['methods'].items()))
    for warning in warnings:
        print('WARNING:', warning)

    plot(summary, methods, ref, os.path.join(args.out, 'agreement.png'))


def plot(summary, methods, ref, path):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import datetime as dt
    keys = [k for k in sorted(methods) if summary['methods'][k]['minutes']]
    if not keys:
        return
    colours = ['#B08F00', '#1A1A1A', '#2F6DB5', '#B5412F', '#4F8A3A']
    fig, axes = plt.subplots(1, 2, figsize=(14, 4.6), dpi=200, gridspec_kw={'width_ratios': [1.6, 1]})
    a, b = axes
    minutes = sorted(ref)
    a.plot([dt.datetime.fromtimestamp(m, dt.timezone.utc) for m in minutes], [ref[m] for m in minutes],
           'o-', color='#7A7A7A', lw=2, ms=4, label='Reference')
    for colour, key in zip(colours, keys):
        ms = sorted(methods[key])
        a.plot([dt.datetime.fromtimestamp(m, dt.timezone.utc) for m in ms], [methods[key][m][0] for m in ms],
               's', color=colour, ms=4, label=key)
        pairs = [(methods[key][m][0], ref[m]) for m in ms if m in ref]
        b.scatter([(e + r) / 2 for e, r in pairs], [e - r for e, r in pairs], s=14, color=colour)
        s = summary['methods'][key]
        for y in (s['bias'], s['loa_low'], s['loa_high']):
            b.axhline(y, color=colour, lw=1, ls='-' if y == s['bias'] else '--', alpha=0.7)
    a.set_title('Per-minute heart rate', loc='left')
    a.set_ylabel('bpm')
    a.legend(frameon=False, fontsize=8)
    b.set_title('Bland-Altman: bias and 95% limits', loc='left')
    b.set_xlabel('mean of method and reference (bpm)')
    b.set_ylabel('method - reference (bpm)')
    for ax in axes:
        ax.grid(alpha=0.25)
        ax.spines[['top', 'right']].set_visible(False)
    fig.tight_layout()
    fig.savefig(path, facecolor='white')


if __name__ == '__main__':
    main()
