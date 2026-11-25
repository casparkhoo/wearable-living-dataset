#!/usr/bin/env python3
"""Score a recording against the EmbracePlus over a sub-window chosen without looking at the errors.

The window is the N consecutive minutes in which the live algorithm produced a heart
rate in the most minutes (a signal-quality criterion); agreement with the reference
plays no part in choosing it, so the result is not cherry-picked. The full recording
is scored too, for comparison.

    python scripts/window_validation.py experiment_36.zip --reference pulse-rate.csv --minutes 30 15
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from score_methods import agreement, latest_runs, per_minute, read_export, read_reference  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('export')
    ap.add_argument('--reference', required=True)
    ap.add_argument('--minutes', type=int, nargs='+', default=[30])
    ap.add_argument('--min-estimates', type=int, default=3)
    args = ap.parse_args()

    rows, _ = read_export(args.export)
    rows = latest_runs(rows)
    ref = read_reference([args.reference])
    methods, _ = per_minute(rows, args.min_estimates)
    live_key = next(k for k in methods if k.startswith('live_ppg'))
    model_key = next((k for k in methods if k.startswith('papagei')), None)

    # Minutes of the recording that the reference covers.
    all_minutes = sorted({m for k in methods for m in methods[k]} & set(ref))
    span = list(range(all_minutes[0], all_minutes[-1] + 60, 60))

    def report(label, minutes):
        out = {'label': label, 'from': minutes[0], 'to': minutes[-1] + 60, 'minutes': len(minutes)}
        for name, key in (('live', live_key), ('papagei', model_key)):
            if key:
                out[name] = agreement([(methods[key][m][0], ref[m]) for m in minutes if m in methods[key] and m in ref])
        return out

    results = [report(f'full ({len(span)} min)', span)]
    for n in args.minutes:
        if n >= len(span):
            continue
        best = max(range(len(span) - n + 1),
                   key=lambda i: (sum(span[j] in methods[live_key] for j in range(i, i + n)), -i))
        results.append(report(f'{n} min, most live coverage', span[best:best + n]))

    import datetime as dt
    for r in results:
        t = lambda s: dt.datetime.fromtimestamp(s, dt.timezone.utc).strftime('%H:%M')
        print(f"\n{r['label']}: {t(r['from'])}–{t(r['to'])} UTC")
        for name in ('live', 'papagei'):
            s = r.get(name)
            if not s or not s.get('minutes'):
                continue
            print(f"  {name:8s} scored {s['minutes']:2d} min  bias {s['bias']:+.1f}  LoA {s['loa_low']:+.1f}..{s['loa_high']:+.1f}  "
                  f"MAE {s['mae']:.1f}  RMSE {s['rmse']:.1f}  within5 {s['within_5_bpm']:.0%}  r {s.get('pearson_r', float('nan')):.2f}  "
                  f"| constant MAE {s['best_constant_mae']:.1f}  ref {s['reference_range'][0]:.0f}-{s['reference_range'][1]:.0f} bpm (SD {s['reference_sd']})")
    return results


if __name__ == '__main__':
    main()
