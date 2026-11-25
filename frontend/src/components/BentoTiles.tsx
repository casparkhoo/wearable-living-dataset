import type { LiveSession } from '../hooks/useBackend';
import { shownHeartRate, shownHeartRateHistory, shownHeartRateSource } from '../hooks/useBackend';

/** Small tiles that sit beside the Glasses view in the Devices bento grid. */

function Sparkline({ values }: { values: number[] }) {
  if (values.length < 2) return <div className="tile-empty">no trend yet</div>;
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const span = hi - lo || 1;
  const points = values
    .map((v, i) => `${((i / (values.length - 1)) * 100).toFixed(2)},${(26 - ((v - lo) / span) * 22).toFixed(2)}`)
    .join(' ');
  return (
    <svg className="tile-spark" viewBox="0 0 100 30" preserveAspectRatio="none" aria-hidden="true">
      <polyline points={points} fill="none" stroke="currentColor" strokeWidth="1.6" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

/** Heart rate, pulse quality and the five-minute trend. */
export function HeartTile({ session, device }: { session: LiveSession | null; device?: string }) {
  const shown = shownHeartRate(session);
  const hr = shown != null ? Math.round(shown) : null;
  const source = shownHeartRateSource(session);
  const quality = session?.ppg?.quality ?? null;
  const history = shownHeartRateHistory(session).map(([, bpm]) => bpm);
  return (
    <section className="tile tile-heart">
      <div className="tile-label">{[device, 'Heart rate', source].filter(Boolean).join(' · ')}</div>
      <div className="tile-big">
        <span className="tile-heart-icon" aria-hidden="true">♥</span>
        <span className="tile-num">{hr ?? '--'}</span>
        <span className="tile-unit">bpm</span>
      </div>
      <div className="tile-row">
        <span className={`tile-chip ${quality ?? ''}`}>{quality ? signalLabel(quality) : 'no signal'}</span>
        {session?.ppg && <span className="tile-dim">SQI {session.ppg.sqi.toFixed(2)}</span>}
      </div>
      {hr == null && session?.ppg?.hr_reason_text && <div className="tile-reason">{session.ppg.hr_reason_text}</div>}
      <Sparkline values={history} />
    </section>
  );
}

/** Movement, delivery rate, loss and alerts for the live recording. */
export function SignalTile({ session, device }: { session: LiveSession | null; device?: string }) {
  const ppg = session?.streams?.ppg;
  const rows: [string, string][] = [
    ['Movement', session?.movement?.state ?? '—'],
    ['Rate', ppg?.effective_rate_hz != null ? `${ppg.effective_rate_hz.toFixed(1)} Hz` : '—'],
    ['Loss', ppg?.loss_ratio != null ? `${(ppg.loss_ratio * 100).toFixed(1)} %` : '—'],
    ['Alerts', session ? String(session.active_alerts.length) : '—'],
  ];
  return (
    <section className="tile tile-signal">
      <div className="tile-label">{device ? `${device} · Signal` : 'Signal'}</div>
      <dl className="tile-list">
        {rows.map(([name, value]) => (
          <div key={name} className="tile-list-row">
            <dt>{name}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

/**
 * Whether a recording wearable is sending, from the backend's own per-stream health:
 * the newest sample on any of its streams, as the backend saw it. Counting a device's
 * samples among the newest 500 from all devices (as the cards did) made the ring
 * flicker offline whenever the earbuds' faster streams filled that window.
 */
export function liveDeviceStatus(session: LiveSession | null | undefined): 'online' | 'delayed' | 'offline' {
  const streams = Object.values(session?.streams ?? {});
  if (streams.length === 0) return 'offline';
  const newest = Math.min(...streams.map((stream) => stream.last_arrival_age_ms));
  if (newest <= 6000) return 'online';
  if (newest <= 20000) return 'delayed';
  return 'offline';
}

/** One recording wearable: heart rate (with its method), signal and movement together. */
export function DeviceLiveTile({ session, device, onExpand }: { session: LiveSession; device: string; onExpand?: () => void }) {
  const shown = shownHeartRate(session);
  const source = shownHeartRateSource(session);
  const status = liveDeviceStatus(session);
  const ppg = session.ppg;
  const health = session.streams?.ppg ?? Object.values(session.streams ?? {})[0];
  const history = shownHeartRateHistory(session).map(([, bpm]) => bpm);
  const age = Math.min(...Object.values(session.streams ?? {}).map((stream) => stream.last_arrival_age_ms));
  return (
    <section className={`tile tile-device ${status}`}>
      <div className="tile-device-head">
        <span className="tile-label">{device}</span>
        {onExpand && (
          <button type="button" className="tile-expand" onClick={onExpand} aria-label={`Show all details for ${device}`}>
            Details ▾
          </button>
        )}
        <span className={`tile-status ${status}`}>
          {status === 'online' ? 'Online' : status === 'delayed' ? `Delayed · ${Math.round(age / 1000)} s · paused` : 'Offline · last reading'}
        </span>
      </div>
      <div className="tile-big">
        <span className="tile-heart-icon" aria-hidden="true">♥</span>
        <span className="tile-num">{shown != null ? Math.round(shown) : '--'}</span>
        <span className="tile-unit">bpm{source ? ` · ${source}` : ''}</span>
      </div>
      <div className="tile-row">
        <span className={`tile-chip ${ppg?.quality === 'poor' ? 'fair' : ppg?.quality ?? ''}`}>{ppg ? signalLabel(ppg.quality) : 'no pulse data'}</span>
        <span className="tile-chip">{session.movement?.state ?? 'movement —'}</span>
        {session.active_alerts.length > 0 && (
          <span className="tile-chip poor">{session.active_alerts.length} alert{session.active_alerts.length === 1 ? '' : 's'}</span>
        )}
      </div>
      {shown == null && ppg?.hr_reason_text && <div className="tile-reason">{ppg.hr_reason_text}</div>}
      <div className="tile-dim">
        {health?.effective_rate_hz != null ? `${health.effective_rate_hz.toFixed(1)} Hz` : '— Hz'}
        {health?.loss_ratio != null ? ` · loss ${(health.loss_ratio * 100).toFixed(1)} %` : ''}
        {ppg ? ` · SQI ${ppg.sqi.toFixed(2)}` : ''}
      </div>
      {/* Movement shown by default beside the heart-rate trend. */}
      <div className="tile-traces">
        <div><div className="tile-trace-label">Heart rate</div><Sparkline values={history} /></div>
        <div><div className="tile-trace-label">Movement</div><Sparkline values={session.trace?.accel ?? []} /></div>
      </div>
    </section>
  );
}

/** Signal quality in neutral words: "poor" read as a fault during ordinary wear. */
export function signalLabel(quality: string): string {
  return quality === 'good' ? 'signal good' : quality === 'fair' ? 'signal ok' : 'signal weak';
}
