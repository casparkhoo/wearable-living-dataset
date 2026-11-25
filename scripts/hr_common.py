"""Pure functions shared by hr_prepare.py and hr_evaluate.py (heart-rate results against the EmbracePlus).

No database, network or worker access here, so everything is unit-tested in scripts/tests/test_hr_common.py.
"""
from __future__ import annotations

import statistics as st

import numpy as np

DAY_MS = 86_400_000
GOOD = ('good', 'fair')


def minute_of(ms) -> int:
    return int(ms) // 60_000


def day_of(ms) -> int:
    """UTC day index of a time in ms (recordings on the same day form one leave-out group)."""
    return int(ms) // DAY_MS


# ---------------------------------------------------------------- per-minute summaries and gates

def minute_table(summaries):
    """Group stored 5 s live summaries by minute.

    summaries: iterable of dicts with keys t (ms), quality, hr (rules bpm or None), shown (display bpm or None),
    movement ('still', 'moving', 'active' or None). A summary whose ppg block is null has quality None.
    Returns {minute: {'n', 'n_good', 'n_still', 'rules': [...], 'shown': [...]}}; 'rules' only holds heart rates
    from summaries rated good or fair."""
    out = {}
    for s in summaries:
        m = minute_of(s['t'])
        d = out.setdefault(m, {'n': 0, 'n_good': 0, 'n_still': 0, 'rules': [], 'shown': []})
        d['n'] += 1
        good = s.get('quality') in GOOD
        d['n_good'] += int(good)
        d['n_still'] += int(s.get('movement') == 'still')
        if good and s.get('hr') is not None:
            d['rules'].append(float(s['hr']))
        if s.get('shown') is not None:
            d['shown'].append(float(s['shown']))
    return out


def passes_gate(d, min_good, still_frac=None):
    """Quality gate for one minute of minute_table(): at least min_good summaries rated good or fair, and,
    when still_frac is given, at least that fraction of the minute's summaries with movement state 'still'."""
    if d is None or d['n_good'] < min_good:
        return False
    if still_frac is not None and (d['n'] == 0 or d['n_still'] / d['n'] < still_frac):
        return False
    return True


def median_or_none(values, min_n=3):
    return float(st.median(values)) if len(values) >= min_n else None


def per_minute_median(times_ms, values, min_n=3):
    """{minute: median} of window estimates keyed by their time (window end), minutes with at least min_n."""
    by = {}
    for t, v in zip(times_ms, values):
        by.setdefault(minute_of(t), []).append(float(v))
    return {m: float(st.median(v)) for m, v in by.items() if len(v) >= min_n}


def fuse(rules, head, gate_ok):
    """Fusion rule fixed in advance: the rules estimate when the minute passes the quality gate and the rules
    have an estimate, else the head estimate (None when neither exists). Returns (value, source)."""
    if gate_ok and rules is not None:
        return rules, 'rules'
    if head is not None:
        return head, 'head'
    return None, None


# ---------------------------------------------------------------- labels

def label_interp(centre_ms, ref, max_gap_ms=60_000):
    """Linear interpolation between minute values placed at minute start + 30 s; None outside the anchors
    or across a gap (a missing minute). ref is {minute index: bpm}."""
    pts = sorted((m * 60_000 + 30_000, v) for m, v in ref.items())
    lo = hi = None
    for p in pts:
        if p[0] <= centre_ms:
            lo = p
        elif hi is None:
            hi = p
    if lo is not None and lo[0] == centre_ms:
        return lo[1]
    if lo is None or hi is None or hi[0] - lo[0] > max_gap_ms:
        return None
    f = (centre_ms - lo[0]) / (hi[0] - lo[0])
    return lo[1] + f * (hi[1] - lo[1])


# ---------------------------------------------------------------- metrics

def metrics(pairs, const=None):
    """pairs: [(minute, estimate, reference)]. MAE, bias, r, share within 5 and 10 bpm, Bland-Altman limits,
    estimate and reference ranges, the oracle constant (median of these reference minutes) and, when const
    is given, the MAE of that fixed constant (e.g. the mean of the training recordings' references)."""
    if not pairs:
        return None
    est = np.array([p[1] for p in pairs], float)
    ref = np.array([p[2] for p in pairs], float)
    err = est - ref
    r = float(np.corrcoef(est, ref)[0, 1]) if len(pairs) > 2 and est.std() > 0 and ref.std() > 0 else float('nan')
    sd = float(err.std(ddof=1)) if len(err) > 1 else 0.0
    out = {'minutes': len(pairs), 'mae': float(np.abs(err).mean()), 'bias': float(err.mean()), 'r': r,
           'within5': float(np.mean(np.abs(err) <= 5) * 100), 'within10': float(np.mean(np.abs(err) <= 10) * 100),
           'loa': (float(err.mean() - 1.96 * sd), float(err.mean() + 1.96 * sd)),
           'est_min': float(est.min()), 'est_max': float(est.max()),
           'ref_min': float(ref.min()), 'ref_max': float(ref.max()),
           'oracle_const_mae': float(np.abs(ref - np.median(ref)).mean())}
    if const is not None:
        out['train_const_mae'] = float(np.abs(ref - const).mean())
    return out


def beats_gate(s, min_r=0.5):
    """Save gate per held-out recording: MAE below the oracle constant and r above min_r."""
    if not s or not np.isfinite(s['r']):
        return False
    return bool(s['mae'] < s['oracle_const_mae'] and s['r'] > min_r)


# ---------------------------------------------------------------- recalibration

def fit_linear(est, ref):
    """Least-squares ref ~ slope * est + offset. With a constant estimate the slope is 0 and the offset the mean."""
    est, ref = np.asarray(est, float), np.asarray(ref, float)
    if est.std() == 0:
        return 0.0, float(ref.mean())
    slope, offset = np.polyfit(est, ref, 1)
    return float(slope), float(offset)


def fit_offset(est, ref):
    """Offset-only recalibration: ref ~ est + offset."""
    return float(np.mean(np.asarray(ref, float) - np.asarray(est, float)))


# ---------------------------------------------------------------- reference plausibility

def consecutive_jumps(ref):
    """[(minute, previous bpm, bpm, change)] for each pair of reference values in adjacent minutes."""
    ms = sorted(ref)
    return [(m, ref[p], ref[m], ref[m] - ref[p]) for p, m in zip(ms, ms[1:]) if m - p == 1]


def missing_minutes(ref, minutes):
    """Minutes (from the given set, e.g. those with ring data) that have no reference value."""
    return sorted(m for m in minutes if m not in ref)


# ---------------------------------------------------------------- ridge (weighted, standardised)

def source_weights(kinds):
    """Own rows weight 1; every other source gets a total weight equal to the number of own rows."""
    kinds = np.asarray(kinds)
    n_own = int((kinds == 'own').sum())
    w = np.ones(len(kinds))
    for k in set(kinds.tolist()) - {'own'}:
        m = kinds == k
        w[m] = n_own / m.sum() if n_own else 1.0
    return w


def ridge_fit(X, y, sw, alphas):
    """Weighted ridge on standardised features; {alpha: (raw weights, bias)} so that x @ w + b is the estimate."""
    sw = np.asarray(sw, float)
    tot = sw.sum()
    mean = (sw[:, None] * X).sum(0) / tot
    std = np.sqrt((sw[:, None] * (X - mean) ** 2).sum(0) / tot) + 1e-9
    Z = (X - mean) / std
    ym = float((sw * y).sum() / tot)
    Zw = Z * sw[:, None]
    G, b = Zw.T @ Z, Zw.T @ (y - ym)
    out = {}
    for a in alphas:
        wz = np.linalg.solve(G + a * np.eye(G.shape[0]), b)
        w = wz / std
        out[a] = (w, float(ym - mean @ w))
    return out
