#!/usr/bin/env python3
"""Timed faults on real hardware: how long did each alert take?

Mark each physical fault in the phone app with an activity label whose text starts with "fault"
(for example "fault: loosen ring"): tap it when you do the action, tap it again when you undo it.
This script reads those labels and the stored live alerts (live_events) of one or more recordings
and prints, per fault, which alert codes were raised, the delay from the start of the label to the
first alert, and when it cleared. Alerts raised outside every fault label (plus a margin) are
reported as unexplained, because they may be false alerts.

The delay includes the time it takes you to tap the label after the action, usually a second or
two: read it as an upper bound with that offset, not as an exact detection time.

    python scripts/fault_session_report.py --sessions 143 144 --margin 90

Read-only. It queries the local MySQL through docker compose exec, like the other scripts here.
"""
from __future__ import annotations

import argparse
import datetime as dt
import os
import subprocess

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')


def sql(args, query):
    cmd = ['docker', 'compose', '--env-file', args.env, '-f', args.compose, 'exec', '-T', 'mysql', 'sh', '-c',
           'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" -N -B "$MYSQL_DATABASE"']
    out = subprocess.run(cmd, input=query, capture_output=True, text=True, check=True).stdout
    return [line.split('\t') for line in out.splitlines() if line.strip()]


def clock(ms):
    return dt.datetime.fromtimestamp(ms / 1000, dt.timezone.utc).strftime('%H:%M:%S')


def pair_alerts(events):
    """[(code, stream, raised_ms, cleared_ms or None)] from raised/cleared rows in time order."""
    open_alerts, out = {}, []
    for ms, kind, code, stream in sorted(events):
        key = (code, stream)
        if kind == 'alert_raised':
            open_alerts[key] = ms
        elif kind == 'alert_cleared' and key in open_alerts:
            out.append((code, stream, open_alerts.pop(key), ms))
    for (code, stream), ms in open_alerts.items():
        out.append((code, stream, ms, None))
    return sorted(out, key=lambda a: a[2])


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--env', default=os.path.join(ROOT, 'deployment', 'local', '.env'))
    ap.add_argument('--compose', default=os.path.join(ROOT, 'deployment', 'local', 'docker-compose.yml'))
    ap.add_argument('--sessions', type=int, nargs='+', required=True)
    ap.add_argument('--margin', type=float, default=90, help='seconds after a fault ends in which an alert is still attributed to it')
    ap.add_argument('--prefix', default='fault', help='label text that marks a fault (case-insensitive prefix)')
    args = ap.parse_args()

    ids = ','.join(str(s) for s in args.sessions)
    span = sql(args, f"SELECT MIN(started_at_ms), MAX(IFNULL(ended_at_ms, UNIX_TIMESTAMP()*1000)) FROM sessions WHERE id IN ({ids});")[0]
    lo, hi = int(span[0]), int(float(span[1]))
    notes = sql(args, f"SELECT label_value, started_at_ms, IFNULL(ended_at_ms, started_at_ms) FROM annotations "
                      f"WHERE label_name='activity' AND started_at_ms BETWEEN {lo - 60000} AND {hi + 60000} ORDER BY started_at_ms;")
    faults = [(v, int(s), int(e)) for v, s, e in notes if v.strip().lower().startswith(args.prefix.lower())]
    events = sql(args, f"SELECT detected_at_ms, event_type, alert_code, stream_name, session_id FROM live_events "
                       f"WHERE session_id IN ({ids}) AND event_type IN ('alert_raised','alert_cleared') ORDER BY detected_at_ms;")
    per_session: dict[str, list] = {}
    for ms, kind, code, stream, sid in events:
        per_session.setdefault(sid, []).append((int(ms), kind, code, stream))
    alerts = []
    for sid, evs in per_session.items():
        for code, stream, raised, cleared in pair_alerts(evs):
            alerts.append((sid, code, stream, raised, cleared))
    alerts.sort(key=lambda a: a[3])

    print(f'sessions {args.sessions}: {len(faults)} fault labels, {len(alerts)} alerts '
          f'({clock(lo)} to {clock(hi)} UTC)\n')
    attributed = set()
    for label, start, end in faults:
        hits = [a for a in alerts if start - 5000 <= a[3] <= end + args.margin * 1000]
        attributed.update(id(a) for a in hits)
        print(f'{label}  {clock(start)}-{clock(end)} ({(end - start) / 1000:.0f} s)')
        if not hits:
            print('    no alert raised')
        for sid, code, stream, raised, cleared in hits:
            cleared_txt = f'cleared {(cleared - end) / 1000:+.0f} s after the fault ended' if cleared else 'not cleared'
            print(f'    session {sid} {code} ({stream or "-"}): raised {(raised - start) / 1000:.1f} s after the label, {cleared_txt}')
    stray = [a for a in alerts if id(a) not in attributed]
    print(f'\nalerts outside any fault label (+{args.margin:.0f} s): {len(stray)}')
    for sid, code, stream, raised, cleared in stray:
        print(f'    session {sid} {code} ({stream or "-"}) at {clock(raised)}')


if __name__ == '__main__':
    main()
