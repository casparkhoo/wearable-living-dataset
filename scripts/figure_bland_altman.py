"""Bland-Altman plot of each method against the EmbracePlus, from score_methods.py's minutes.csv.

Usage: python scripts/figure_bland_altman.py minutes.csv out.png
"""
import csv
import statistics as st
import sys

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

src, out = sys.argv[1], sys.argv[2]
rows = list(csv.DictReader(open(src)))
methods = [c for c in rows[0] if c not in ('minute_unix', 'reference_bpm')]
names = {'live_ppg': 'Live Analysis Engine', 'papagei': 'PaPaGei + head'}

plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 13})
fig, axes = plt.subplots(1, len(methods), figsize=(11, 4.8), dpi=200, sharey=True)
for ax, m in zip(axes, methods):
    pairs = [(float(r['reference_bpm']), float(r[m])) for r in rows if r['reference_bpm'] and r[m]]
    mean = [(a + b) / 2 for a, b in pairs]
    diff = [b - a for a, b in pairs]
    bias, sd = st.mean(diff), st.stdev(diff)
    lo, hi = bias - 1.96 * sd, bias + 1.96 * sd
    ax.scatter(mean, diff, s=28, color='#B08F00', edgecolor='#1A1A1A', linewidth=0.6, zorder=3)
    ax.axhline(0, color='#999999', lw=1)
    ax.axhline(bias, color='#1A1A1A', lw=1.6, label=f'bias {bias:+.1f} bpm')
    ax.axhline(hi, color='#1A1A1A', lw=1.2, ls='--', label=f'95% limits {lo:+.1f} to {hi:+.1f}')
    ax.axhline(lo, color='#1A1A1A', lw=1.2, ls='--')
    label = next((v for k, v in names.items() if m.startswith(k)), m)
    ax.set_title(f'{label} (n = {len(pairs)} min)')
    ax.set_xlabel('Mean of method and reference (bpm)')
    ax.legend(frameon=True, framealpha=0.9, edgecolor='none', loc='lower left', fontsize=11)
    ax.grid(alpha=0.25)
    ax.spines[['top', 'right']].set_visible(False)
    print(label, len(pairs), round(bias, 2), round(lo, 1), round(hi, 1))
axes[0].set_ylabel('Method minus EmbracePlus (bpm)')
fig.tight_layout()
fig.savefig(out, facecolor='white')
