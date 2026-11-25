import { useEffect, useState } from 'react';
import type { LiveSession } from '../hooks/useBackend';
import { shownHeartRate } from '../hooks/useBackend';
import { signalLabel } from './BentoTiles';
import { friendly } from '../lib/names';

/**
 * The Even G1 dashboard drawn as the glasses show it: the same 576 x 136 layout as the
 * picture the phone sends to the lenses (clock, recording state, heart rate with the
 * pulse waveform, then status cells and any alert), in bright green on black.
 */

const W = 576;
const H = 136;
const TOP = 6;
const BOTTOM = H - 6;

function heartPath(cx: number, cy: number, size: number): string {
  return [
    `M ${cx} ${cy + size * 0.45}`,
    `C ${cx - size * 0.9} ${cy - size * 0.05} ${cx - size * 0.45} ${cy - size * 0.65} ${cx} ${cy - size * 0.2}`,
    `C ${cx + size * 0.45} ${cy - size * 0.65} ${cx + size * 0.9} ${cy - size * 0.05} ${cx} ${cy + size * 0.45}`,
    'Z',
  ].join(' ');
}

function wavePath(values: number[], left: number, top: number, right: number, bottom: number): string | null {
  if (values.length < 4) return null;
  let lo = Infinity;
  let hi = -Infinity;
  for (const v of values) {
    lo = Math.min(lo, v);
    hi = Math.max(hi, v);
  }
  const span = hi - lo < 1e-6 ? 1 : hi - lo;
  // Light smoothing, as on the phone, so noise does not read as detail.
  const smooth = values.map((_, i) => {
    let sum = 0;
    let n = 0;
    for (let k = -2; k <= 2; k++) {
      const j = i + k;
      if (j >= 0 && j < values.length) {
        sum += values[j];
        n++;
      }
    }
    return sum / n;
  });
  return smooth
    .map((v, i) => {
      const x = left + ((right - left) * i) / (smooth.length - 1);
      const y = bottom - ((v - lo) / span) * (bottom - top);
      return `${i === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`;
    })
    .join(' ');
}

export interface GlassesViewProps {
  session: LiveSession | null;
  cloudOnline: boolean;
  /** Every recording wearable; with several, the lenses show a row for each. */
  sessions?: LiveSession[];
}

/** Short name for a wearable's row, as the phone draws it: RING, EAR5803. */
function rowName(session: LiveSession, all: LiveSession[]): string {
  if (session.device_type === 'OpenRing') return 'RING';
  if (session.device_type === 'OpenEarable') {
    const same = all.filter((other) => other.device_type === 'OpenEarable').length;
    return same > 1 ? `EAR${session.device_address.slice(-5).replace(':', '')}` : 'EAR';
  }
  return session.device_type.slice(0, 6).toUpperCase();
}

function rowSignal(session: LiveSession): string {
  const quality = session.ppg?.quality;
  return quality === 'good' ? 'good' : quality === 'fair' ? 'ok' : quality ? 'weak' : '';
}

export function GlassesView({ session, cloudOnline, sessions }: GlassesViewProps) {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(id);
  }, []);

  const clock = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
  const shown = shownHeartRate(session);
  const hr = shown != null ? String(Math.round(shown)) : '--';
  const reasonShort: Record<string, string> = { no_pulse: 'no pulse found', moving: 'reading paused', weak: 'signal weak' };
  const quality = session?.ppg
    ? (shown == null && session.ppg.hr_reason ? reasonShort[session.ppg.hr_reason] ?? 'signal weak' : signalLabel(session.ppg.quality))
    : 'no signal';
  const movement = session?.movement?.state ?? '';
  const rateHz = session?.streams?.ppg?.effective_rate_hz;
  const rate = rateHz != null ? `${Math.round(rateHz)} Hz` : '';
  const alerts = session?.active_alerts ?? [];
  const alertText = `${alerts.length} ${alerts.length === 1 ? 'alert' : 'alerts'}`;
  const banner = alerts.length > 0 ? `! ${friendly(alerts[0].message)}`.slice(0, 44) : '';
  const live = session != null;

  // Same proportions as the phone's picture (GlassesImage): smaller bars, and a
  // row per wearable when several are recording.
  const height = BOTTOM - TOP;
  const barH = height * 0.21;
  const footH = height * 0.2;
  const midTop = TOP + barH + 3;
  const midBottom = BOTTOM - footH - 3;
  const midH = midBottom - midTop;
  const rows = (sessions ?? []).filter((one) => one.ppg).slice(0, 3);
  const multi = rows.length > 1;
  const split = multi ? 300 : 190;
  // The full 10 s trace, exactly as the device tiles plot it.
  const wave = wavePath(session?.trace?.ppg ?? [], split + 12, midTop + 3, W - 12, midBottom - 3);
  const cells = [movement, friendly(session?.device_type ?? ''), rate, cloudOnline ? 'cloud' : 'offline', alertText];
  const cell = W / cells.length;

  return (
    <svg
      className={`glasses-svg ${live ? 'live' : 'idle'}`}
      viewBox={`0 0 ${W} ${H}`}
      role="img"
      aria-label="What the glasses show"
    >
      <rect x="0" y="0" width={W} height={H} fill="#020604" />
      <g fill="currentColor" stroke="currentColor" fontFamily="'Cascadia Mono','SF Mono',ui-monospace,monospace">
        {/* Top bar */}
        <text x="12" y={TOP + barH * 0.82} fontSize={barH * 0.95} fontWeight="700" stroke="none">{clock}</text>
        <text x={W - 12} y={TOP + barH * 0.8} fontSize={barH * 0.8} fontWeight="700" textAnchor="end" stroke="none">
          {live ? 'LIVE' : 'waiting'}
        </text>
        <circle
          cx={W - 12 - (live ? 4 : 7) * barH * 0.8 * 0.62 - 12}
          cy={TOP + barH * 0.52}
          r={barH * 0.2}
          fill={live ? 'currentColor' : 'none'}
          strokeWidth="2"
        />
        <line x1="0" y1={TOP + barH} x2={W} y2={TOP + barH} strokeWidth="2" />

        {/* Heart rate: a row per wearable, or one large reading */}
        {multi ? rows.map((one, i) => {
          const rowH = midH / rows.length;
          const base = midTop + rowH * (i + 0.78);
          const value = shownHeartRate(one);
          const text = value != null ? String(Math.round(value)) : '--';
          return (
            <g key={one.session_id}>
              <text x="12" y={base} fontSize={rowH * 0.5} fontWeight="700" stroke="none">{rowName(one, rows)}</text>
              <path d={heartPath(92, base - rowH * 0.26, rowH * 0.36)} stroke="none" />
              <text x="106" y={base} fontSize={rowH * 0.7} fontWeight="700" stroke="none">{text}</text>
              <text x={106 + text.length * rowH * 0.42 + 4} y={base} fontSize={rowH * 0.42} stroke="none">
                bpm  {rowSignal(one)}
              </text>
            </g>
          );
        }) : (
          <>
            <path d={heartPath(20, midTop + midH * 0.32, midH * 0.36)} stroke="none" />
            <text x="44" y={midTop + midH * 0.62} fontSize={midH * 0.62} fontWeight="700" stroke="none">{hr}</text>
            <text x={44 + hr.length * midH * 0.37 + 8} y={midTop + midH * 0.62} fontSize={midH * 0.28} stroke="none">bpm</text>
            <text x="12" y={midBottom - 1} fontSize={midH * 0.26} stroke="none">{quality}</text>
          </>
        )}
        <line x1={split} y1={midTop + 2} x2={split} y2={midBottom - 2} strokeWidth="2" />

        {/* Waveform */}
        {wave ? (
          <path d={wave} fill="none" strokeWidth="3" strokeLinejoin="round" strokeLinecap="round" />
        ) : (
          <line x1={split + 12} y1={(midTop + midBottom) / 2} x2={W - 12} y2={(midTop + midBottom) / 2} strokeWidth="1" />
        )}

        {/* Footer */}
        <line x1="0" y1={BOTTOM - footH} x2={W} y2={BOTTOM - footH} strokeWidth="2" />
        {banner ? (
          <>
            <rect x="0" y={BOTTOM - footH + 3} width={W} height={footH - 3} stroke="none" />
            <text x="12" y={BOTTOM - footH * 0.2} fontSize={footH * 0.7} fontWeight="700" fill="#020604" stroke="none">
              {banner}
            </text>
          </>
        ) : (
          cells.map((text, i) => (
            <g key={i}>
              {i > 0 && <line x1={i * cell} y1={BOTTOM - footH + 5} x2={i * cell} y2={BOTTOM - 2} strokeWidth="2" />}
              <text
                x={i * cell + cell / 2}
                y={BOTTOM - footH * 0.2}
                fontSize={footH * 0.66}
                textAnchor="middle"
                stroke="none"
              >
                {text}
              </text>
            </g>
          ))
        )}
      </g>
    </svg>
  );
}

/** The dashboard in a dark panel with a caption; shown at the top of the devices view. */
export function GlassesHero({ session, cloudOnline, sessions }: GlassesViewProps) {
  return (
    <section className="glasses-hero" aria-label="Glasses view">
      <div className="glasses-hero-head">
        <span className="glasses-hero-title">GLASSES VIEW</span>
        <span className="glasses-hero-caption">
          {session ? `${(sessions ?? []).filter((one) => one.ppg).length > 1 ? 'all wearables' : friendly(session.device_type)} · what the Even G1 shows when you look up` : 'waiting for a live recording'}
        </span>
      </div>
      <div className="glasses-screen">
        <GlassesView session={session} cloudOnline={cloudOnline} sessions={sessions} />
      </div>
    </section>
  );
}

/**
 * A made-up live session with a synthetic 72 bpm pulse, shown when the page is opened
 * with ?glasses-demo so the view can be looked at without a recording.
 */
export function demoSession(): LiveSession {
  const ppg = Array.from({ length: 250 }, (_, i) => {
    const phase = ((i / 25) * 1.2) % 1;
    return Math.exp(-(((phase - 0.17) / 0.065) ** 2)) + 0.42 * Math.exp(-(((phase - 0.45) / 0.085) ** 2));
  });
  return {
    session_id: 0,
    device_type: 'OpenRing',
    device_address: 'demo',
    window_start_ms: 0,
    window_end_ms: 10000,
    streams: {
      ppg: {
        sample_count: 250, warming_up: false, effective_rate_hz: 25, nominal_rate_hz: 25, rate_ratio: 1,
        last_arrival_age_ms: 0, stalled: false, max_gap_ms: 80, jitter_ms: 4, missing: 0, duplicates: 0,
        loss_ratio: 0, flatline_channels: [],
      },
    },
    ppg: { channel: 'green', sqi: 0.82, quality: 'good', hr_bpm: 72 },
    movement: { relative_std: 0.01, state: 'still' },
    active_alerts: [],
    trace: { window_ms: 10000, ppg_channel: 'green', ppg, accel: [], hr_history: [] },
  } as unknown as LiveSession;
}
