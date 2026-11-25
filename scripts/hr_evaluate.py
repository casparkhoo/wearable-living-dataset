#!/usr/bin/env python3
"""Stage 2 of the heart-rate results: score the ring's heart rate against the EmbracePlus, held out only.

Reads the cache written by hr_prepare.py (~/data_public/cache_hr), the stretched rule-labelled windows cached by
train_head_wide.py (~/data_public/cache_wide/stretch.npz) and the EmbracePlus pulse-rate files under tmp/.
Writes outputs/hr_results/{tables.md, results.json, minutes.csv}. Saves a head only if the gate below passes.

    python scripts/hr_evaluate.py

Everything below in CAPITALS was fixed before any held-out number was computed:
  * Quality gate per minute (from the 5 s live summaries, about 12 per minute): GATE_GOOD summaries rated good or
    fair (a summary with a null ppg block counts as not good); "still" adds: at least STILL_FRAC of the minute's
    summaries have movement.state == 'still' ('moving', 'active' and null are not still). Sensitivity: 4 and 8.
  * Estimates per minute: median of at least 3 values (rules: hr_bpm of good/fair summaries; shown: display_hr_bpm;
    heads: window outputs, windows keyed by their end time).
  * Fusion: the rules estimate when the minute passes the quality gate (GATE_GOOD, no still condition) and the
    rules have an estimate, else the head.
  * Leave-out unit: the UTC day (55/60/65, 66/67, 140, 147/148), so recordings minutes apart are never split
    between training and test. Stretched windows from the held-out day are dropped as well.
  * Head trained on EmbracePlus labels: interpolated labels (minute value at minute start + 30 s), ridge, alpha
    from ALPHAS by leave-one-day-out inside the training days (mean of per-day window MAE). Save candidate:
    CANDIDATE ('embrace+stretch': own windows weight 1, stretched windows the same total weight).
  * Save gate: the candidate beats the oracle constant (median of the held-out reference minutes) on BOTH
    held-out wide-range recordings 140 and 147, with r > 0.5 on each.
"""
from __future__ import annotations

import csv
import glob
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..')
sys.path.insert(0, HERE)
from hr_common import (beats_gate, consecutive_jumps, day_of, fit_linear, fit_offset, fuse, label_interp,  # noqa: E402
                       metrics, minute_of, minute_table, missing_minutes, passes_gate, per_minute_median,
                       ridge_fit, source_weights)

CACHE = os.path.join(os.path.expanduser('~'), 'data_public', 'cache_hr')
STRETCH = os.path.join(os.path.expanduser('~'), 'data_public', 'cache_wide', 'stretch.npz')
OUT = os.path.join(ROOT, 'outputs', 'hr_results')
HEADS = os.path.join(ROOT, 'ml-inference-server', 'heads')

GATE_GOOD = 6
GATE_SENSITIVITY = (4, 8)
STILL_FRAC = 0.75
ALPHAS = (1, 10, 100, 1_000, 10_000, 100_000)
CANDIDATE = 'embrace+stretch'
VARIANTS = ('embrace', 'embrace+stretch')
GATE_SESSIONS = (140, 147)
HEAD_ID = 'hr_ring_embrace_v1'
V2_TRAINING = {55, 60, 65, 68, 70, 76, 94, 105, 108, 110, 118}   # from hr_ring_stretch_v2.json
STILL_WORDS = ('sitting', 'standing', 'lying', 'piano')


def ref_files():
    one = lambda pat: sorted(glob.glob(os.path.join(ROOT, pat)))[0]
    oct23 = os.path.join(ROOT, 'tmp', 'embrace', 'pulse-rate.csv')
    nov03 = os.path.join(ROOT, 'tmp', 'embrace_1103', 'pulse-rate.csv')
    nov23 = one('tmp/empatica_nov23/*pulse-rate.csv')
    nov24 = one('tmp/empatica_nov24c/*pulse-rate.csv')
    return {55: oct23, 60: oct23, 65: oct23, 66: nov03, 67: nov03, 140: nov23, 147: nov24, 148: nov24}


def read_ref(path):
    vals, reasons = {}, {}
    with open(path, newline='') as f:
        for row in csv.DictReader(f):
            m = int(float(row['timestamp_unix'])) // 60_000
            if row['pulse_rate_bpm']:
                vals[m] = float(row['pulse_rate_bpm'])
            else:
                reasons[m] = row.get('missing_value_reason') or ''
    return vals, reasons


def activity_group(minute, notes):
    """Annotation group of a minute as in score_live_display.py: label with the largest overlap (>= 30 s)."""
    t0, t1 = minute * 60_000, minute * 60_000 + 60_000
    best, ov_best = None, 0
    for lab, a, b in notes:
        ov = min(t1, b) - max(t0, a)
        if ov > ov_best:
            best, ov_best = lab, ov
    if best is None or ov_best < 30_000 or 'ring' in best:
        return None, best
    return ('still' if any(k in best for k in STILL_WORDS) else 'active'), best


def pairs_of(est, ref, minutes):
    return [(m, est[m], ref[m]) for m in sorted(minutes) if m in est and m in ref and est[m] is not None]


def fmt(s, cov_den=None):
    if not s:
        return '| 0 | | | | | | | | |'
    cov = f"{s['minutes']}/{cov_den} ({100 * s['minutes'] / cov_den:.0f}%)" if cov_den else f"{s['minutes']}"
    tc = f"{s['train_const_mae']:.1f}" if 'train_const_mae' in s else ''
    r = f"{s['r']:.2f}" if np.isfinite(s['r']) else 'n/a'
    return (f"| {cov} | {s['mae']:.1f} | {s['bias']:+.1f} | {r} | {s['within5']:.0f}% / {s['within10']:.0f}% | "
            f"{s['loa'][0]:+.0f} to {s['loa'][1]:+.0f} | {s['est_min']:.0f}-{s['est_max']:.0f} | "
            f"{s['ref_min']:.0f}-{s['ref_max']:.0f} | {s['oracle_const_mae']:.1f} | {tc} |")


HDR = ('| minutes (coverage) | MAE | bias | r | within 5 / 10 | Bland-Altman 95% | estimate range | reference range | '
       'oracle constant MAE | training-mean constant MAE |')
SEP = '|' + '---|' * 10


def main():
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(CACHE, 'db.json')) as f:
        db = json.load(f)
    win = np.load(os.path.join(CACHE, 'windows.npz'))
    sessions = sorted(int(s) for s in db['summaries'])
    bounds = {int(k): v['bounds'] for k, v in db['meta']['per_session'].items()}
    all_sessions = {int(k): v for k, v in db['meta']['sessions'].items()}
    day = {s: day_of(bounds[s][0]) for s in sessions}
    files = ref_files()
    ref, reasons, mt, notes = {}, {}, {}, {}
    for s in sessions:
        vals, rs = read_ref(files[s])
        lo, hi = minute_of(bounds[s][0]), minute_of(bounds[s][1])
        ref[s] = {m: v for m, v in vals.items() if lo <= m <= hi}
        reasons[s] = {m: v for m, v in rs.items() if lo <= m <= hi}
        mt[s] = minute_table(db['summaries'][str(s)])
        notes[s] = [tuple(x) for x in db['annotations'][str(s)]]
    ring_minutes = {s: {m for m, d in mt[s].items() if d['n'] >= 6} for s in sessions}
    den = {s: len([m for m in ring_minutes[s] if m in ref[s]]) for s in sessions}
    groups = {s: {m: activity_group(m, notes[s]) for m in ring_minutes[s] | set(ref[s])} for s in sessions}
    other_mean = {s: float(np.mean([v for o in sessions if day[o] != day[s] for v in ref[o].values()])) for s in sessions}
    res = {'prereg': {'gate_good': GATE_GOOD, 'still_frac': STILL_FRAC, 'sensitivity': GATE_SENSITIVITY,
                      'alphas': ALPHAS, 'candidate': CANDIDATE, 'gate_sessions': GATE_SESSIONS},
           'reference_minutes': {s: den[s] for s in sessions}}
    md = ['# Heart-rate tables (generated by scripts/hr_evaluate.py)', '',
          'Per-minute scoring against the EmbracePlus. Coverage = scored minutes / minutes with ring summaries and a '
          'reference value. Oracle constant = median of the scored reference minutes (a strong baseline, it knows '
          'the answer\'s level); training-mean constant = mean of the reference on the other days.', '']

    # ------------------------------------------------ head outputs (stretch_v2 recomputed from embeddings)
    v2 = np.load(os.path.join(HEADS, 'hr_ring_stretch_v2.npz'))
    X, wsess, wend = win['X'].astype(np.float64), win['session'], win['end']
    v2_out = (X @ v2['weights'].reshape(-1) + float(v2['bias'].reshape(-1)[0]))
    check = {}
    for s in (140, 147, 148):
        stored = dict((int(e), h) for e, h in db['stored_v2'][str(s)])
        mine = {int(e): v for e, v, ss in zip(wend, v2_out, wsess) if ss == s}
        se = np.array(sorted(stored)); a, b = [], []
        for e, v in mine.items():
            j = np.argmin(np.abs(se - e))
            if abs(se[j] - e) <= 2_500:
                a.append(v); b.append(stored[int(se[j])])
        check[s] = {'matched': len(a), 'r': float(np.corrcoef(a, b)[0, 1]), 'mean_abs_diff': float(np.mean(np.abs(np.subtract(a, b))))}
    res['v2_recompute_check'] = check
    md += ['## Check: stretch_v2 recomputed from embeddings vs the stored live outputs', '',
           '| session | matched windows (end within 2.5 s) | r | mean abs difference (bpm) |', '|---|---|---|---|']
    md += [f"| {s} | {c['matched']} | {c['r']:.3f} | {c['mean_abs_diff']:.2f} |" for s, c in check.items()] + ['']

    est = {s: {} for s in sessions}
    for s in sessions:
        d = mt[s]
        est[s]['rules'] = {m: (float(np.median(x['rules'])) if len(x['rules']) >= 3 else None) for m, x in d.items()}
        est[s]['shown'] = {m: (float(np.median(x['shown'])) if len(x['shown']) >= 3 else None) for m, x in d.items()}
        sel = wsess == s
        est[s]['head_v2'] = per_minute_median(wend[sel], v2_out[sel])
        sv = db['stored_v2'][str(s)]
        est[s]['head_v2_live'] = per_minute_median([e for e, _ in sv], [h for _, h in sv]) if sv else {}
    md += ['Per-minute medians of the two agree better than single windows: ' + '; '.join(
        f"session {s}: r {np.corrcoef(*zip(*[(est[s]['head_v2'][m], est[s]['head_v2_live'][m]) for m in est[s]['head_v2_live'] if m in est[s]['head_v2']]))[0, 1]:.2f}, "
        f"mean abs difference {np.mean([abs(est[s]['head_v2'][m] - est[s]['head_v2_live'][m]) for m in est[s]['head_v2_live'] if m in est[s]['head_v2']]):.1f} bpm"
        for s in (140, 147, 148)) + '. The live engine builds its own model windows (its sample timeline and rate '
        'estimate), so the offline windows (arrival time, squeezed to 250 samples) are an approximation; '
        '"head_v2_live" rows below use the stored live outputs.', '']

    # ------------------------------------------------ (1) quality-gated scoring
    gates = {'none': lambda d: True, f'quality>={GATE_GOOD}': lambda d: passes_gate(d, GATE_GOOD),
             f'quality>={GATE_GOOD}+still': lambda d: passes_gate(d, GATE_GOOD, STILL_FRAC)}
    for k in GATE_SENSITIVITY:
        gates[f'quality>={k}'] = (lambda kk: lambda d: passes_gate(d, kk))(k)
        gates[f'quality>={k}+still'] = (lambda kk: lambda d: passes_gate(d, kk, STILL_FRAC))(k)
    res['gated'] = {}
    pooled = {}
    md += ['## (1) Quality-gated scoring', '',
           f'Gate fixed in advance: at least {GATE_GOOD} of the minute\'s 5 s summaries rated good or fair; "+still": '
           f'also at least {STILL_FRAC:.0%} of them with movement state still. 4 and 8 are the declared sensitivity thresholds.',
           'stretch_v2 rows on 55/60/65 are not held out (it was trained on rule labels from them) and are left out.', '']
    for s in sessions:
        md += [f'### Session {s} (reference {min(ref[s].values()):.0f}-{max(ref[s].values()):.0f} bpm, {den[s]} minutes)', '',
               '| estimate | gate ' + HDR, '|---|---' + SEP]
        for name in ('rules', 'shown', 'head_v2', 'head_v2_live'):
            if name == 'head_v2' and s in V2_TRAINING:
                continue
            if not any(v is not None for v in est[s][name].values()):
                continue
            for g, fn in gates.items():
                ms = [m for m in ring_minutes[s] if fn(mt[s].get(m))]
                pp = pairs_of(est[s][name], ref[s], ms)
                pooled.setdefault((name, g), []).append((s, pp))
                sm = metrics(pp, other_mean[s])
                res['gated'][f'{s}|{name}|{g}'] = sm
                md.append(f'| {name} | {g} ' + fmt(sm, den[s]))
        md.append('')
    md += ['### Pooled over recordings (each recording\'s own oracle constant)', '',
           'rules: all 8 recordings (the rules are not trained); shown and head_v2_live: 140, 147, 148; head_v2 '
           '(offline): 66, 67, 140, 147, 148.', '',
           '| estimate | gate | recordings with minutes | minutes / reference minutes | MAE | bias | within 5 / 10 | '
           'per-recording constant MAE |', '|---|---|---|---|---|---|---|---|']
    res['pooled'] = {}
    for (name, g), lst in pooled.items():
        allp = [p for _, pp in lst for p in pp]
        dd = sum(den[s] for s, _ in lst)
        if not allp:
            md.append(f'| {name} | {g} | 0 | 0/{dd} | | | | |'); continue
        err = np.array([p[1] - p[2] for p in allp])
        cerr = np.concatenate([np.abs(np.array([p[2] for p in pp]) - np.median([p[2] for p in pp])) for _, pp in lst if pp])
        row = {'recordings': sum(1 for _, pp in lst if pp), 'minutes': len(allp), 'den': dd, 'mae': float(np.abs(err).mean()),
               'bias': float(err.mean()), 'within5': float(np.mean(np.abs(err) <= 5) * 100),
               'within10': float(np.mean(np.abs(err) <= 10) * 100), 'const_mae': float(cerr.mean())}
        res['pooled'][f'{name}|{g}'] = row
        md.append(f"| {name} | {g} | {row['recordings']} | {row['minutes']}/{dd} ({100 * row['minutes'] / dd:.0f}%) | "
                  f"{row['mae']:.1f} | {row['bias']:+.1f} | {row['within5']:.0f}% / {row['within10']:.0f}% | {row['const_mae']:.1f} |")
    md.append('')

    # ------------------------------------------------ (2) recalibration of stretch_v2 (held-out sessions only)
    rec_s = [66, 140, 147, 148]
    pairs_v2 = {s: pairs_of(est[s]['head_v2'], ref[s], ring_minutes[s]) for s in rec_s}
    res['recal'] = {}
    md += ['## (2) Linear recalibration of stretch_v2, leave-one-day-out', '',
           'Fitted on the per-minute (estimate, reference) pairs of the other days, applied to the held-out day.',
           '"est-on-ref slope" is the slope of the estimate regressed on the reference within the recording '
           '(about 0 means the head output does not move with the pulse).', '',
           '| held out | variant | fitted slope | fitted offset | ' + HDR[2:], '|---|---|---|---' + SEP]
    for s in rec_s:
        tr = [p for o in rec_s if day[o] != day[s] for p in pairs_v2[o]]
        e_tr = np.array([p[1] for p in tr]); r_tr = np.array([p[2] for p in tr])
        sl, off = fit_linear(e_tr, r_tr)
        o_only = fit_offset(e_tr, r_tr)
        for vname, f, a, b in (('raw', lambda e: e, '', ''), ('offset only', lambda e: e + o_only, '1', f'{o_only:+.1f}'),
                               ('slope+offset', lambda e: sl * e + off, f'{sl:.2f}', f'{off:+.1f}')):
            pp = [(m, f(e), r) for m, e, r in pairs_v2[s]]
            sm = metrics(pp, other_mean[s])
            res['recal'][f'{s}|{vname}'] = sm
            md.append(f'| {s} | {vname} | {a} | {b} ' + fmt(sm, den[s]))
    md += ['', '| session | bias of raw stretch_v2 | est-on-ref slope | r |', '|---|---|---|---|']
    for s in rec_s:
        e = np.array([p[1] for p in pairs_v2[s]]); r = np.array([p[2] for p in pairs_v2[s]])
        slope = float(np.polyfit(r, e, 1)[0])
        res['recal'][f'{s}|diag'] = {'bias': float((e - r).mean()), 'est_on_ref_slope': slope}
        md.append(f'| {s} | {(e - r).mean():+.1f} | {slope:.2f} | {np.corrcoef(e, r)[0, 1]:.2f} |')
    md += ['', 'Offset fitted on one recording, applied to another (MAE on the target, raw MAE in brackets):', '',
           '| fitted on \\ applied to | ' + ' | '.join(str(s) for s in rec_s) + ' |', '|---|' + '---|' * len(rec_s)]
    for a in rec_s:
        o = fit_offset([p[1] for p in pairs_v2[a]], [p[2] for p in pairs_v2[a]])
        cells = []
        for b in rec_s:
            mm = np.mean([abs(e + o - r) for _, e, r in pairs_v2[b]]); raw = np.mean([abs(e - r) for _, e, r in pairs_v2[b]])
            cells.append(f'{mm:.1f} ({raw:.1f})' + (' *same*' if a == b else ''))
        md.append(f'| {a} (offset {o:+.1f}) | ' + ' | '.join(cells) + ' |')
    md.append('')

    # ------------------------------------------------ (3) head trained on EmbracePlus labels, leave-one-day-out
    st_ = np.load(STRETCH)
    sX, sy, sg = st_['X'].astype(np.float64), st_['y'].astype(float), st_['group'].astype(int)
    sday = np.array([day_of(all_sessions[int(g)][0]) for g in sg])
    centres = wend - 5_000
    lab = np.array([np.nan if (v := label_interp(c, ref[int(s)])) is None else v for c, s in zip(centres, wsess)])
    wday = np.array([day[int(s)] for s in wsess])
    has = np.isfinite(lab)
    days = sorted(set(day.values()))

    def train(train_days, variant, alpha=None):
        own = has & np.isin(wday, train_days)
        Xs, ys, kinds = [X[own]], [lab[own]], ['own'] * int(own.sum())
        dd = [wday[own]]
        if variant == 'embrace+stretch':
            keep = np.isin(sday, train_days) | ~np.isin(sday, days)    # older days always; reference days only if training
            Xs.append(sX[keep]); ys.append(sy[keep]); kinds += ['stretch'] * int(keep.sum()); dd.append(sday[keep])
        Xa, ya, ka, da = np.concatenate(Xs), np.concatenate(ys), np.array(kinds), np.concatenate(dd)
        cv = {}
        if alpha is None:
            per = {a: [] for a in ALPHAS}
            for vd in train_days:
                val = (ka == 'own') & (da == vd)
                if val.sum() < 10:
                    continue
                trm = da != vd
                fits = ridge_fit(Xa[trm], ya[trm], source_weights(ka[trm]), ALPHAS)
                for a, (w, b) in fits.items():
                    per[a].append(float(np.mean(np.abs(Xa[val] @ w + b - ya[val]))))
            cv = {a: float(np.mean(v)) for a, v in per.items() if v}
            alpha = min(cv, key=cv.get)
        w, b = ridge_fit(Xa, ya, source_weights(ka), [alpha])[alpha]
        return w, b, alpha, cv, ya

    res['embrace_head'] = {}
    lodo_pred = {v: {} for v in VARIANTS}
    md += ['## (3) Head trained on EmbracePlus labels, leave-one-day-out', '',
           f'Save candidate fixed in advance: {CANDIDATE}. Alpha chosen inside the training days only.', '',
           '| variant | held out | minutes | alpha | ' + HDR[2:], '|---|---|---|---' + SEP]
    for variant in VARIANTS:
        for d in days:
            w, b, alpha, cv, ytr = train([x for x in days if x != d], variant)
            tmean = float(np.mean([v for o in sessions if day[o] != d for v in ref[o].values()]))
            for s in [x for x in sessions if day[x] == d]:
                sel = wsess == s
                pm = per_minute_median(wend[sel], X[sel] @ w + b)
                lodo_pred[variant][s] = pm
                for subset in ('all', 'still (annotation)', 'active (annotation)', 'still (movement>=75%)'):
                    if subset == 'all':
                        ms = ring_minutes[s]
                    elif subset == 'still (movement>=75%)':
                        ms = [m for m in ring_minutes[s] if passes_gate(mt[s].get(m), 0, STILL_FRAC)]
                    else:
                        want = subset.split()[0]
                        ms = [m for m in ring_minutes[s] if groups[s].get(m, (None,))[0] == want]
                    sm = metrics(pairs_of(pm, ref[s], ms), tmean)
                    res['embrace_head'][f'{variant}|{s}|{subset}'] = dict(sm or {}, alpha=alpha) if sm else None
                    if sm:
                        md.append(f'| {variant} | {s} | {subset} | {alpha:g} ' + fmt(sm, den[s]))
    md.append('')
    gate_rows = {s: res['embrace_head'].get(f'{CANDIDATE}|{s}|all') for s in GATE_SESSIONS}
    passed = all(beats_gate(gate_rows[s]) for s in GATE_SESSIONS)
    res['save_gate'] = {'passed': passed, 'rows': gate_rows}
    md += [f'**Save gate** ({CANDIDATE} on 140 and 147, all minutes: MAE below the oracle constant and r > 0.5): '
           + ('PASSED' if passed else 'not passed') + '. ' +
           '; '.join(f"{s}: MAE {gate_rows[s]['mae']:.1f} vs constant {gate_rows[s]['oracle_const_mae']:.1f}, r {gate_rows[s]['r']:.2f}"
                     for s in GATE_SESSIONS if gate_rows[s]), '']

    # ------------------------------------------------ (4) fusion
    res['fusion'] = {}
    md += ['## (4) Fusion: rules when the minute passes the quality gate, else the head', '',
           '| session | head used | estimate ' + HDR, '|---|---|---' + SEP]
    for s in sessions:
        heads = [('embrace+stretch (held out)', lodo_pred[CANDIDATE][s])]
        if s not in V2_TRAINING:
            heads.insert(0, ('stretch_v2', est[s]['head_v2']))
        for hname, hp in heads:
            fused, src = {}, {}
            for m in ring_minutes[s]:
                v, so = fuse(est[s]['rules'].get(m), hp.get(m), passes_gate(mt[s].get(m), GATE_GOOD))
                if v is not None:
                    fused[m], src[m] = v, so
            for ename, e in (('head alone', hp), ('fused', fused)):
                sm = metrics(pairs_of(e, ref[s], ring_minutes[s]), other_mean[s])
                res['fusion'][f'{s}|{hname}|{ename}'] = sm
                md.append(f'| {s} | {hname} | {ename} ' + fmt(sm, den[s]))
            n_rules = sum(1 for m in fused if src[m] == 'rules' and m in ref[s])
            res['fusion'][f'{s}|{hname}|n_rules'] = n_rules
    md.append('')

    # ------------------------------------------------ (5) the reference itself
    res['reference'] = {}
    md += ['## (5) The EmbracePlus reference within each recording', '',
           '| session | ring minutes | with reference | missing (reasons) | adjacent pairs | abs change median | >=10 bpm | >=20 bpm | largest |',
           '|---|---|---|---|---|---|---|---|---|']
    jumps_all = []
    for s in sessions:
        rm = ring_minutes[s]
        miss = missing_minutes(ref[s], rm)
        rs = {}
        for m in miss:
            rs[reasons[s].get(m, 'no row')] = rs.get(reasons[s].get(m, 'no row'), 0) + 1
        j = [x for x in consecutive_jumps(ref[s]) if x[0] in rm or x[0] - 1 in rm]
        ch = np.abs([x[3] for x in j]) if j else np.array([0.0])
        for x in j:
            jumps_all.append((s,) + x + (groups[s].get(x[0], (None, None))[1], groups[s].get(x[0] - 1, (None, None))[1]))
        res['reference'][s] = {'ring_minutes': len(rm), 'with_ref': den[s], 'missing': len(miss), 'reasons': rs,
                               'pairs': len(j), 'median_abs_change': float(np.median(ch)),
                               'n_ge10': int((ch >= 10).sum()), 'n_ge20': int((ch >= 20).sum()), 'max': float(ch.max())}
        R = res['reference'][s]
        md.append(f"| {s} | {R['ring_minutes']} | {R['with_ref']} | {R['missing']} {rs if rs else ''} | {R['pairs']} | "
                  f"{R['median_abs_change']:.0f} | {R['n_ge10']} | {R['n_ge20']} | {R['max']:.0f} |")
    md += ['', 'Changes of 10 bpm or more between adjacent reference minutes, with the annotated activity:', '',
           '| session | minute (UTC) | from | to | change | activity in previous minute | activity in this minute |', '|---|---|---|---|---|---|---|']
    import datetime as dt
    for s, m, a, b, c, act, prev in jumps_all:
        if abs(c) >= 10:
            md.append(f"| {s} | {dt.datetime.utcfromtimestamp(m * 60).strftime('%Y-%m-%d %H:%M')} | {a:.0f} | {b:.0f} | {c:+.0f} | {prev or ''} | {act or ''} |")
    res['reference_jumps'] = jumps_all
    md.append('')

    # ------------------------------------------------ per-minute CSV
    with open(os.path.join(OUT, 'minutes.csv'), 'w', newline='') as f:
        wr = csv.writer(f)
        wr.writerow(['session', 'minute_utc_ms', 'reference', 'annotation', 'summaries', 'good_or_fair', 'still',
                     'rules', 'shown', 'stretch_v2_offline', 'stretch_v2_live', 'embrace_stretch_lodo', 'reference_missing_reason'])
        for s in sessions:
            for m in sorted(ring_minutes[s] | set(ref[s])):
                d = mt[s].get(m, {'n': 0, 'n_good': 0, 'n_still': 0})
                g = lambda v: '' if v is None else f'{v:.1f}'
                wr.writerow([s, m * 60_000, g(ref[s].get(m)), groups[s].get(m, (None, None))[1] or '', d['n'], d['n_good'],
                             d['n_still'], g(est[s]['rules'].get(m)), g(est[s]['shown'].get(m)),
                             g(est[s]['head_v2'].get(m)) if s not in V2_TRAINING else '', g(est[s]['head_v2_live'].get(m)),
                             g(lodo_pred[CANDIDATE][s].get(m)), reasons[s].get(m, '')])

    with open(os.path.join(OUT, 'tables.md'), 'w', encoding='utf8') as f:
        f.write('\n'.join(md) + '\n')
    with open(os.path.join(OUT, 'results.json'), 'w') as f:
        json.dump(res, f, indent=1, default=lambda o: o.tolist() if hasattr(o, 'tolist') else str(o))
    print('save gate', 'PASSED' if passed else 'not passed', {s: (r and (round(r['mae'], 1), round(r['oracle_const_mae'], 1), round(r['r'], 2))) for s, r in gate_rows.items()})

    if passed:
        w, b, alpha, cv, ytr = train(days, CANDIDATE)
        np.savez(os.path.join(HEADS, f'{HEAD_ID}.npz'), weights=w.reshape(-1, 1).astype(np.float32),
                 bias=np.array([b], dtype=np.float32))
        manifest = {'head_id': HEAD_ID, 'head_version': '2026-11-24',
                    'display_name': 'Heart rate (PaPaGei-S + ridge, EmbracePlus-labelled + rule-labelled stretched)',
                    'base_model_id': 'papagei_s', 'input_dimension': 512, 'output_type': 'regression', 'unit': 'bpm',
                    'weights_file': f'{HEAD_ID}.npz',
                    'task_description': 'Heart rate from a 10 s ring PPG window; trained on ring windows labelled with the '
                                        'EmbracePlus per-minute pulse rate (interpolated) plus time-stretched rule-labelled windows.',
                    'training': {'recordings': sessions, 'ridge_alpha': alpha, 'labels': 'EmbracePlus pulse rate, interpolated'},
                    'validation': {'method': 'leave-one-day-out, per-minute median vs EmbracePlus',
                                   **{str(s): gate_rows[s] for s in GATE_SESSIONS}},
                    'validation_status': 'pilot: one wearer'}
        with open(os.path.join(HEADS, f'{HEAD_ID}.json'), 'w', encoding='utf8') as f:
            json.dump(manifest, f, indent=2)
        print('saved', HEAD_ID)


if __name__ == '__main__':
    main()
