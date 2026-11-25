import { useState, useCallback, useEffect, useRef } from 'react';
import {
  backendFetch,
  useBackend,
  computeRate,
  DeviceState,
  LiveAlert,
  LiveAlertEvent,
  LiveSession,
  LiveTrace,
  LiveSummary,
  shownHeartRate,
  shownHeartRateSource,
} from './hooks/useBackend';
import { useAuth } from './hooks/useAuth';
import { StudiesPanel } from './components/StudiesPanel';
import { GlassesHero, demoSession } from './components/GlassesView';
import { DeviceLiveTile, liveDeviceStatus } from './components/BentoTiles';
import { DeviceGlyph } from './components/DeviceGlyph';
import { friendly } from './lib/names';
import { AccountMenu } from './components/AccountMenu';
import { SignInScreen } from './components/SignInScreen';
import { BrandMark } from './components/BrandMark';
import { PixelField } from './components/PixelField';
import { Chevron } from './components/Chevron';
import { AppDownload } from './components/AppDownload';
import { BackendList, BackendLinkPrompt, BackendSwitcher } from './components/BackendSwitcher';
import { useBackendProfiles, type BackendProfiles } from './hooks/useBackendProfiles';
import { mixedContentIssue } from './lib/backendProfiles';

// ─── Device identity ────────────────────────────────────────────────────────

const DEVICE_META: Record<string, { color: string; short: string; blurb: string }> = {
  OpenEarable: { color: '#3b7dd8', short: 'Ear', blurb: 'Open-source ear-worn sensing platform' },
  OpenRing: { color: '#1f9d72', short: 'Ring', blurb: 'PPG + motion sensing ring' },
  'Even Glasses': { color: '#8b5cf6', short: 'Glasses', blurb: 'Smart glasses' },
  HeartRateMonitor: { color: '#d65745', short: 'HR strap', blurb: 'Bluetooth heart-rate strap' },
};

function deviceMeta(name: string) {
  return DEVICE_META[name] ?? { color: '#9a8f80', short: name.slice(0, 4), blurb: 'Wearable device' };
}

// ─── Sensor field metadata ──────────────────────────────────────────────────

const FIELD_META: Record<string, { label: string; unit: string; scale?: number }> = {
  accel_x: { label: 'Acc X', unit: 'raw' },
  accel_y: { label: 'Acc Y', unit: 'raw' },
  accel_z: { label: 'Acc Z', unit: 'raw' },
  gyro_x: { label: 'Gyro X', unit: 'raw' },
  gyro_y: { label: 'Gyro Y', unit: 'raw' },
  gyro_z: { label: 'Gyro Z', unit: 'raw' },
  mag_x: { label: 'Mag X', unit: 'µT' },
  mag_y: { label: 'Mag Y', unit: 'µT' },
  mag_z: { label: 'Mag Z', unit: 'µT' },
  ppg_green: { label: 'Green', unit: 'ct' },
  ppg_ir: { label: 'IR', unit: 'ct' },
  ppg_red: { label: 'Red', unit: 'ct' },
  ppg_ambient: { label: 'Ambient', unit: 'ct' },
  pressure: { label: 'Pressure', unit: 'hPa' },
  temperature: { label: 'Temp', unit: 'raw' },
};

// Keys carried in packets that are metadata, not displayable measurements.
const META_KEYS = new Set([
  'deviceTimestamp',
  'ppg_sample_count',
  'ppg_data_size',
  'ppg_sample_interval_us',
  'raw',
]);

// Semantic display groups. Both OpenEarable (separate IMU/PPG/TEMP_BARO packets)
// and OpenRing (one bundled PPG packet with motion + temperature) collapse into
// these categories purely from the field keys present — so the viewer renders
// either device correctly regardless of how packets are split.
const CATEGORIES: { id: string; label: string; keys: string[] }[] = [
  { id: 'ppg', label: 'PPG', keys: ['ppg_green', 'ppg_ir', 'ppg_red', 'ppg_ambient'] },
  { id: 'motion', label: 'Motion', keys: ['accel_x', 'accel_y', 'accel_z', 'gyro_x', 'gyro_y', 'gyro_z'] },
  { id: 'mag', label: 'Magnetometer', keys: ['mag_x', 'mag_y', 'mag_z'] },
  { id: 'env', label: 'Environment', keys: ['temperature', 'pressure'] },
];

// ─── Helpers ──────────────────────────────────────────────────────────────────

interface MergedField {
  value: number;
  rate: number; // sampleRateHz of the packet that carried this key
  type: string; // originating sensor type
}

/**
 * Flatten the latest packet of every sensor type into a single key→value map.
 * Handles OpenEarable (multiple typed packets) and OpenRing (one fat packet)
 * uniformly. Metadata and non-numeric values are dropped.
 */
function mergeLatest(state: DeviceState): Record<string, MergedField> {
  const out: Record<string, MergedField> = {};
  for (const type of Object.keys(state.byType)) {
    const pkt = state.byType[type];
    for (const [k, v] of Object.entries(pkt.data)) {
      if (META_KEYS.has(k) || typeof v !== 'number' || !Number.isFinite(v)) continue;
      out[k] = { value: v, rate: pkt.sampleRateHz, type };
    }
  }
  return out;
}

function fmtVal(v: number, key: string): string {
  const scale = FIELD_META[key]?.scale;
  const scaledVal = scale ? v * scale : v;
  if (key.startsWith('ppg_')) return Math.round(scaledVal).toLocaleString();
  if (Math.abs(scaledVal) >= 1000) return scaledVal.toFixed(0);
  return scaledVal.toFixed(3);
}

/**
 * Compact elapsed time: one unit, not a running total.
 *
 * The previous form kept counting minutes, so a device last seen in August
 * read "64067m 19s" — accurate, unreadable, and long enough to wrap the line
 * it sits on.
 */
function timeSince(ms: number): string {
  const s = Math.floor((Date.now() - ms) / 1000);
  if (s < 1) return '<1s';
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h`;
  const d = Math.floor(h / 24);
  return d < 365 ? `${d}d` : `${Math.floor(d / 365)}y`;
}

function fmtTime(ms: number): string {
  const d = new Date(ms);
  const p = (n: number, w = 2) => String(n).padStart(w, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(Math.floor(d.getMilliseconds() / 10))}`;
}

// ─── Sub-components ────────────────────────────────────────────────────────────

function CategoryBlock({ label, fields, color }: { label: string; fields: [string, MergedField][]; color: string }) {
  if (fields.length === 0) return null;
  const rate = fields[0][1].rate;
  return (
    <div className="category">
      <div className="category-head">
        <span className="category-dot" style={{ background: color }} />
        <span className="category-label">{label}</span>
        {rate > 0 && <span className="category-rate">{rate} Hz</span>}
      </div>
      <div className="field-grid">
        {fields.map(([k, f]) => {
          const meta = FIELD_META[k];
          return (
            <div className="field" key={k}>
              <span className="field-label">{meta?.label ?? k}</span>
              <span className="field-value">
                {fmtVal(f.value, k)}
                {meta?.unit && <span className="field-unit">{meta.unit}</span>}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

const STREAM_LABEL: Record<string, string> = {
  ppg: 'PPG',
  accel: 'Accel',
  gyro: 'Gyro',
  mag: 'Mag',
  temperature: 'Temp',
  pressure: 'Pressure',
};

const SEVERITY_RANK: Record<string, number> = { critical: 3, warning: 2, info: 1 };

function worstSeverity(alerts: LiveAlert[]): string | null {
  return alerts.reduce<string | null>(
    (worst, alert) =>
      (SEVERITY_RANK[alert.severity] ?? 0) > (SEVERITY_RANK[worst ?? ''] ?? 0) ? alert.severity : worst,
    null,
  );
}

/** The live session for a device card: same type, and same address when known. */
/**
 * Which live recording the Glasses view and tiles describe when several run at once (the
 * ring and an earable): one with a heart rate, then a good signal, then the ring, so the
 * most trustworthy reading is shown rather than whichever was listed first.
 */
function liveDeviceName(session: LiveSession, all: LiveSession[]): string {
  const name = friendly(session.device_type);
  const same = all.filter((other) => other.device_type === session.device_type).length;
  // Two earbuds share a type: tell them apart by the end of the address.
  return same > 1 && session.device_address ? `${name} ${session.device_address.slice(-5)}` : name;
}

function pickHeroSession(sessions: LiveSession[]): LiveSession | null {
  let best: LiveSession | null = null;
  let bestScore = -1;
  for (const session of sessions) {
    if (!session.ppg) continue;
    // A readable pulse by the rules' standard: PaPaGei gives a number for any
    // wearable with a trace of pulse, which made an earbud win over the ring.
    const score = (session.ppg.hr_bpm != null ? 4 : 0)
      + (session.ppg.quality === 'good' ? 2 : session.ppg.quality === 'fair' ? 1 : 0)
      + (session.device_type === 'OpenRing' ? 0.5 : 0);
    if (score > bestScore) {
      best = session;
      bestScore = score;
    }
  }
  return best ?? sessions[0] ?? null;
}

function liveSessionFor(live: LiveSummary | null, name: string, address: string): LiveSession | null {
  const candidates = live?.sessions.filter((session) => session.device_type === name) ?? [];
  return (
    candidates.find((session) => session.device_address.toUpperCase() === address.toUpperCase()) ??
    candidates[0] ??
    null
  );
}

/** A line plot that rescales to its own data; the shape matters, not the units. */
function Sparkline({ values, label, unit, color, fixedRange }: {
  values: number[];
  label: string;
  unit?: string;
  color: string;
  fixedRange?: [number, number];
}) {
  const width = 300;
  const height = 56;
  let body = <text x={width / 2} y={height / 2} textAnchor="middle" className="trace-empty">no data</text>;
  if (values.length > 1) {
    const lo = fixedRange ? fixedRange[0] : Math.min(...values);
    const hi = fixedRange ? fixedRange[1] : Math.max(...values);
    const span = hi - lo || 1;
    const points = values
      .map((v, i) => `${((i / (values.length - 1)) * width).toFixed(1)},${(height - 4 - ((v - lo) / span) * (height - 8)).toFixed(1)}`)
      .join(' ');
    body = <polyline points={points} fill="none" stroke={color} strokeWidth="1.5" strokeLinejoin="round" />;
  }
  return (
    <div className="trace">
      <div className="trace-head">
        <span>{label}</span>
        {unit && <span className="trace-unit">{unit}</span>}
      </div>
      <svg viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" className="trace-svg">{body}</svg>
    </div>
  );
}

/**
 * The live algorithm's heart rate and the model's on one time axis and one bpm scale,
 * so the two can be compared at a glance. Gaps are windows with no estimate.
 */
function HeartRateCompare({ live, model }: { live: [number, number][]; model: [number, number][] }) {
  const width = 300;
  const height = 56;
  const all = [...live, ...model];
  if (all.length < 2) {
    return <Sparkline values={[]} label="Heart rate" unit="last 5 min" color="var(--red)" />;
  }
  const t0 = Math.min(...all.map(([t]) => t));
  const t1 = Math.max(...all.map(([t]) => t));
  const lo = Math.min(...all.map(([, v]) => v)) - 2;
  const hi = Math.max(...all.map(([, v]) => v)) + 2;
  const x = (t: number) => (((t - t0) / (t1 - t0 || 1)) * width).toFixed(1);
  const y = (v: number) => (height - 4 - ((v - lo) / (hi - lo)) * (height - 8)).toFixed(1);
  const line = (series: [number, number][]) => series.map(([t, v]) => `${x(t)},${y(v)}`).join(' ');
  return (
    <div className="trace">
      <div className="trace-head">
        <span>Heart rate: <span style={{ color: 'var(--red)' }}>rules</span>{model.length > 0 && <> · <span style={{ color: 'var(--accent)' }}>PaPaGei</span></>}</span>
        <span className="trace-unit">{`${Math.round(lo + 2)}–${Math.round(hi - 2)} bpm, last 5 min`}</span>
      </div>
      <svg viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" className="trace-svg">
        {live.length > 1 && <polyline points={line(live)} fill="none" stroke="var(--red)" strokeWidth="1.5" strokeLinejoin="round" />}
        {model.length > 1 && <polyline points={line(model)} fill="none" stroke="var(--accent)" strokeWidth="1.5" strokeDasharray="4 3" strokeLinejoin="round" />}
      </svg>
    </div>
  );
}

/** Pulse waveform, movement and heart-rate trend from the backend's last window. */
function LiveTraces({ trace }: { trace: LiveTrace }) {
  const seconds = Math.round(trace.window_ms / 1000);
  return (
    <div className="live-traces">
      <Sparkline values={trace.ppg} label="Pulse waveform" unit={`last ${seconds} s`} color="var(--green)" />
      <Sparkline values={trace.accel} label="Movement" unit={`last ${seconds} s`} color="var(--accent)" />
      <HeartRateCompare live={trace.hr_history} model={trace.model_hr_history ?? []} />
    </div>
  );
}

/**
 * Headline live analysis: heart rate, pulse quality and movement, computed by
 * the backend over the last ten seconds, plus any alerts currently raised.
 */
function LiveSummaryBlock({ session }: { session: LiveSession }) {
  const ppg = session.ppg;
  const movement = session.movement;
  return (
    <div className="live-block">
      {shownHeartRate(session) == null && ppg?.hr_reason_text && <div className="live-reason">{ppg.hr_reason_text}</div>}
      <div className="live-metrics">
        <div className="metric" title={shownHeartRateSource(session) === 'PaPaGei' && session.model
          ? [session.model.head_id, session.model.head_version, session.model.runtime, session.model.validation_status].filter(Boolean).join(' · ')
          : 'Rule-based estimate (Live Analysis Engine)'}>
          <span className="metric-val">{shownHeartRate(session) != null ? Math.round(shownHeartRate(session)!) : '—'}</span>
          <span className="metric-label">{shownHeartRateSource(session) ? `HR bpm · ${shownHeartRateSource(session)}` : 'HR bpm'}</span>
        </div>
        {/* The other method, for comparison. */}
        {shownHeartRateSource(session) === 'PaPaGei' ? (
          <div className="metric" title="Rule-based estimate (Live Analysis Engine)">
            <span className="metric-val">{ppg?.hr_bpm ? Math.round(ppg.hr_bpm) : '—'}</span>
            <span className="metric-label">rules bpm</span>
          </div>
        ) : session.model && (
          <div className="metric" title={[session.model.head_id, session.model.head_version, session.model.runtime, session.model.validation_status].filter(Boolean).join(' · ')}>
            <span className="metric-val">{Math.round(session.model.hr_bpm)}</span>
            <span className="metric-label">PaPaGei bpm</span>
          </div>
        )}
        <div className="metric">
          <span className={`metric-val live-quality ${ppg?.quality ?? ''}`}>{ppg?.quality ?? '—'}</span>
          <span className="metric-label">{ppg ? `PPG · SQI ${ppg.sqi.toFixed(2)}` : 'PPG'}</span>
        </div>
        <div className="metric">
          <span className="metric-val">{movement?.state ?? '—'}</span>
          <span className="metric-label">movement</span>
        </div>
      </div>
      {session.trace && <LiveTraces trace={session.trace} />}
      {session.active_alerts.length > 0 && (
        <ul className="live-alerts">
          {session.active_alerts.map((alert) => (
            <li key={`${alert.code}:${alert.stream_name ?? ''}`} className={`live-alert ${alert.severity}`}>
              <span className="live-alert-dot" />
              {friendly(alert.message)}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Per-stream transport health, shown with the device's readings. */
function LiveStreamsBlock({ session, color }: { session: LiveSession; color: string }) {
  const streams = Object.entries(session.streams);
  if (streams.length === 0) return null;
  return (
    <div className="category">
      <div className="category-head">
        <span className="category-dot" style={{ background: color }} />
        <span className="category-label">Stream health</span>
        <span className="category-rate">
          last {Math.round((session.window_end_ms - session.window_start_ms) / 1000)} s
        </span>
      </div>
      <table className="live-streams">
        <thead>
          <tr>
            <th>Stream</th>
            <th>Rate</th>
            <th>Loss</th>
            <th>Max gap</th>
            <th>State</th>
          </tr>
        </thead>
        <tbody>
          {streams.map(([name, health]) => {
            const state = health.stalled
              ? 'stalled'
              : health.warming_up
                ? 'warming up'
                : health.flatline_channels.length > 0
                  ? `flat: ${health.flatline_channels.join(', ')}`
                  : health.saturated_channels.length > 0
                    ? `saturating: ${health.saturated_channels.join(', ')}`
                    : 'ok';
            return (
              <tr key={name}>
                <td>{STREAM_LABEL[name] ?? name}</td>
                <td>
                  {health.effective_rate_hz != null ? health.effective_rate_hz.toFixed(1) : '—'}
                  {health.nominal_rate_hz ? <span className="field-unit">/ {health.nominal_rate_hz} Hz</span> : null}
                </td>
                <td>{health.loss_ratio != null ? `${(health.loss_ratio * 100).toFixed(1)}%` : '—'}</td>
                <td>{health.max_gap_ms != null ? `${health.max_gap_ms} ms` : '—'}</td>
                <td className={state === 'ok' ? 'live-ok' : 'live-bad'}>{state}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** Recent raised and cleared alerts across every visible recording. */
function LiveAlertFeed({ events }: { events: LiveAlertEvent[] }) {
  if (events.length === 0) return null;
  return (
    <section className="live-feed">
      <div className="panel-title">
        Live alerts <span className="stream-count">{events.length}</span>
      </div>
      <ul>
        {events.slice(0, 12).map((event, index) => (
          <li
            key={`${event.detected_at_ms}:${event.code}:${index}`}
            className={`live-alert ${event.severity} ${event.state}`}
          >
            <span className="log-time">{fmtTime(event.detected_at_ms)}</span>
            <span className="live-alert-dot" />
            <span className="live-feed-message">{friendly(event.message)}</span>
            <span className="live-feed-state">{event.state}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function DeviceCard({
  name,
  state,
  expanded,
  onToggle,
  live,
}: {
  name: string;
  state: DeviceState;
  expanded: boolean;
  onToggle: (name: string) => void;
  live: LiveSession | null;
}) {
  const meta = deviceMeta(name);
  // Recording devices: the backend's per-stream health (see liveDeviceStatus). Others:
  // the newest sample seen, with enough margin for batched, relayed delivery.
  const isStale = live ? liveDeviceStatus(live) === 'offline' : Date.now() - state.lastSeenAt > 10000;
  const rate = computeRate(state.recentArrivals);
  const uptime = Math.floor((state.lastSeenAt - state.firstSeenAt) / 1000);

  const merged = mergeLatest(state);
  const alertLevel = live ? worstSeverity(live.active_alerts) : null;

  const used = new Set<string>();

  const grouped = CATEGORIES.map((cat) => {
    const fields = cat.keys
      .filter((k) => merged[k] !== undefined)
      .map((k) => {
        used.add(k);
        return [k, merged[k]] as [string, MergedField];
      });
    return { ...cat, fields };
  }).filter((c) => c.fields.length > 0);

  // Any numeric key we didn't anticipate still gets shown rather than dropped.
  const otherFields = Object.keys(merged)
    .filter((k) => !used.has(k))
    .map((k) => [k, merged[k]] as [string, MergedField]);

  const channelCount = grouped.reduce((total, group) => total + group.fields.length, 0)
    + otherFields.length;

  return (
    <div
      className={`device-card ${isStale ? 'stale' : 'live'} ${expanded ? 'expanded' : 'collapsed'}`}
      style={{ '--device-color': meta.color } as React.CSSProperties}
    >
      <button
        type="button"
        className="device-head"
        onClick={() => onToggle(name)}
        aria-expanded={expanded}
        title={expanded ? 'Collapse device' : 'Expand device'}
      >
        <div className="device-id">
          <span className="device-avatar" style={{ background: meta.color }}><DeviceGlyph name={name} /></span>
          <div className="device-id-text">
            <div className="device-name" title={state.address || undefined}>
              {friendly(name)}
              {alertLevel && <span className={`live-badge ${alertLevel}`}>{live?.active_alerts.length}</span>}
            </div>
            <div className={`device-state ${isStale ? 'off' : 'on'}`}>
              <span className="live-dot" />
              {isStale ? `Offline · ${timeSince(state.lastSeenAt)} ago` : 'Online'}
            </div>
          </div>
        </div>
        <div className="device-head-right">
          <span className="device-chevron"><Chevron open={expanded} /></span>
        </div>
      </button>

      <div className="device-metrics">
        <div className="metric">
          <span className="metric-val">{rate.toFixed(1)}</span>
          <span className="metric-label">pkt/s</span>
        </div>
        <div className="metric">
          <span className="metric-val">{state.totalPackets.toLocaleString()}</span>
          <span className="metric-label">recent samples</span>
        </div>
        <div className="metric">
          <span className="metric-val">{uptime}s</span>
          <span className="metric-label">uptime</span>
        </div>
      </div>

      {live && !isStale && <LiveSummaryBlock session={live} />}

      <div className={`device-fold ${expanded ? 'open' : ''}`}>
        <div className="device-fold-inner">
          <div className="categories">
          {live && <LiveStreamsBlock session={live} color={meta.color} />}
          {grouped.map((c) => (
            <CategoryBlock key={c.id} label={c.label} fields={c.fields} color={meta.color} />
          ))}
          {otherFields.length > 0 && <CategoryBlock label="Other" fields={otherFields} color={meta.color} />}
            {grouped.length === 0 && otherFields.length === 0 && (
              <div className="no-fields">Connected — awaiting sensor frames…</div>
            )}
          </div>
        </div>
      </div>
      {!expanded && (
        <button type="button" className="device-collapsed-hint" onClick={() => onToggle(name)}>
          {channelCount > 0
            ? `${channelCount} channel${channelCount === 1 ? '' : 's'} — show readings`
            : 'Awaiting sensor frames — show detail'}
        </button>
      )}
    </div>
  );
}

function StreamLog({ log }: { log: ReturnType<typeof useBackend>['log'] }) {
  const bottomRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [log.length]);

  return (
    <aside className="stream-log">
      <div className="panel-title stream-head">
        Ingestion Stream
        <span className="stream-count">{log.length}</span>
      </div>
      <div className="stream-body">
        {log.length === 0 && <div className="stream-empty">No packets yet</div>}
        {[...log].reverse().map((entry, i) => (
          <div className="log-entry" key={i}>
            <span className="log-time">{fmtTime(entry.time)}</span>
            <span className="log-device" style={{ color: deviceMeta(entry.device).color }}>
              {deviceMeta(entry.device).short}
            </span>
            <span className="log-type">{entry.type}</span>
            <span className="log-summary">{entry.summary}</span>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>
    </aside>
  );
}

// ─── Main App ──────────────────────────────────────────────────────────────────

interface ProcessedRow {
  sample_offset: number;
  relative_time_s: number;
  timestamp_ms: number;
  values: Record<string, number>;
}

interface ProcessedWindow {
  window_index: number;
  segment_index: number;
  device_recording_id: number;
  device_id: number;
  device_type: string;
  measurement_config_id: number | null;
  source_start_ms: number;
  source_end_ms: number;
  source_start_sample_index: number | null;
  source_end_sample_index: number | null;
  source_sample_rate_hz: number;
  source_sample_count: number;
  target_sample_rate_hz: number;
  target_sample_count: number;
  clock_source: string | null;
  clock_quality: string | null;
  feature_units: Record<string, string>;
  activity_labels: {
    id: number;
    scope: string;
    label_name: string;
    label_value: string;
    started_at_ms: number;
    ended_at_ms: number | null;
    coverage_fraction: number;
  }[];
  rows: ProcessedRow[];
}

interface PostProcessingResult {
  manifest: Record<string, unknown> & {
    stream_name?: string;
    features?: string[];
    window_length_s?: number;
    stride_s?: number;
    target_sample_rate_hz?: number | null;
  };
  summary: {
    segments_detected: number;
    windows_returned: number;
    windows_rejected_missing_features: number;
    windows_rejected_label_coverage: number;
    windows_rejected_ambiguous_labels: number;
  };
  windows: ProcessedWindow[];
}

interface PostProcessingOptions {
  features: string[];
}

type ThemeMode = 'light' | 'dark';

interface DbOverview {
  table_counts?: { table: string; rows: number }[];
  stream_summary?: {
    stream_name: string;
    rows: number;
    first_ms: number | null;
    last_ms: number | null;
    min_hz: number | null;
    max_hz: number | null;
  }[];
  devices?: { id: number; device_type: string; device_address: string | null; created_at: string }[];
}

interface MlHealth {
  enabled?: boolean;
  status?: number | string;
  ml_inference_url?: string;
  server?: {
    status?: string;
    model_loaded?: boolean;
  };
  error?: string;
}

interface MlResult {
  window?: {
    start_ms: number;
    end_ms: number;
    channel: string;
    sample_count: number;
    sample_rate: number;
  };
  result?: {
    embeddings?: unknown;
    embedding_dim?: number;
    preprocessing?: {
      pipeline_id: string;
      pipeline_version: string;
      source_sample_rate_hz: number;
      source_sample_count: number;
      window_duration_s: number;
      target_sample_rate_hz: number;
      target_sample_count: number;
      normalization: string;
      quality?: { source_std?: number; source_range?: number; flatline?: boolean };
    };
    status?: string;
    device?: string;
    error?: string;
  };
  error?: string;
  detail?: string;
}

// Keep the model UI implemented but out of the research workflow until its
// input contract, CSV validation, and result provenance are ready.
interface ExperimentSummary {
  id: number;
  experiment_label: string;
  protocol_name?: string;
  protocol_version?: string;
  created_at: string;
  linked_sessions: number;
  status?: string;
  started_at_ms?: number | null;
  ended_at_ms?: number | null;
  /** Who recorded it, and which study it belongs to. Present once the
      identity/tenancy migration has been applied. */
  owner_user_id?: number | null;
  owner_label?: string | null;
  study_id?: number | null;
  study_name?: string | null;
}

interface ExperimentDetails {
  experiment: ExperimentSummary & {
    operator_label?: string | null;
    notes?: string | null;
    started_at_ms?: number | null;
  };
  device_recordings: {
    id: number;
    session_label?: string | null;
    status: string;
    started_at_ms: number;
    ended_at_ms?: number | null;
    device_type: string;
    device_name?: string | null;
    device_address?: string | null;
    raw_packet_count: number;
    stream_sample_count: number;
  }[];
  sensor_configurations: {
    id: number;
    device_recording_id: number;
    command_name: string;
    requested_streams?: string[] | null;
    effective_streams?: string[] | null;
    requested_sample_rate_hz?: number | null;
    effective_sample_rate_hz?: number | null;
    device_config?: unknown;
    started_at_ms: number;
    ended_at_ms?: number | null;
  }[];
  activity_labels: {
    id: number;
    device_recording_id?: number | null;
    scope?: 'experiment' | 'device_recording';
    applies_to_all_device_recordings?: boolean;
    label_name: string;
    label_value: string;
    started_at_ms: number;
    ended_at_ms?: number | null;
  }[];
  raw_packets: {
    id: number;
    device_recording_id: number;
    packet_type?: string | null;
    received_timestamp_ms: number;
    sample_rate_hz?: number | null;
    parse_status: string;
    payload: unknown;
  }[];
  stream_samples: {
    id: number;
    device_recording_id: number;
    raw_packet_id?: number | null;
    stream_name: string;
    received_timestamp_ms: number;
    sample_rate_hz?: number | null;
    sample_index?: number | null;
    channel_values: unknown;
  }[];
  evidence_limit: number;
}

function toDatetimeLocal(ms: number): string {
  const d = new Date(ms);
  const p = (n: number, w = 2) => String(n).padStart(w, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function fromDatetimeLocal(value: string): number | null {
  const ms = new Date(value).getTime();
  return Number.isFinite(ms) ? ms : null;
}

function formatJson(value: unknown): string {
  return JSON.stringify(value);
}

function flattenEmbedding(value: unknown): number[] {
  if (typeof value === 'number') return Number.isFinite(value) ? [value] : [];
  if (!Array.isArray(value)) return [];
  return value.flatMap(flattenEmbedding);
}

/**
 * Owns the backend choice. The console below is rebuilt whenever it changes, so
 * polling stops, held readings, history, exports and cached identity from one
 * backend can never appear under another's name.
 */
export default function App() {
  const backends = useBackendProfiles();
  return (
    <>
      <Console key={backends.activeKey} backends={backends} />
      <BackendLinkPrompt backends={backends} />
    </>
  );
}

function Console({ backends }: { backends: BackendProfiles }) {
  const backendUrl = backends.active?.url ?? '';
  // The broker address for the phone setup link: the build-time one belongs to
  // the built-in backend; others carry their own if their link gave one.
  const brokerUrl = backends.active?.id === 'default'
    ? (import.meta.env.VITE_BROKER_URL as string | undefined)?.trim() || undefined
    : backends.active?.broker;
  const mixedContent = mixedContentIssue(window.location.protocol, backendUrl);
  const [theme, setTheme] = useState<ThemeMode>(() => {
    const saved = localStorage.getItem('wearable.theme');
    if (saved === 'light' || saved === 'dark') return saved;
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  });
  // Devices start collapsed so the monitor shows every device at a glance;
  // the readings are one click away rather than filling the window.
  const [expandedDevices, setExpandedDevices] = useState<Set<string>>(new Set());
  // Live tiles opened to show all details, by device recording.
  const [expandedTiles, setExpandedTiles] = useState<Set<number>>(new Set());
  const toggleTile = useCallback((sessionId: number) => {
    setExpandedTiles((current) => {
      const next = new Set(current);
      if (next.has(sessionId)) next.delete(sessionId); else next.add(sessionId);
      return next;
    });
  }, []);
  const toggleDevice = useCallback((name: string) => {
    setExpandedDevices((current) => {
      const next = new Set(current);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  }, []);
  const [activeTab, setActiveTab] = useState<'devices' | 'stream' | 'history' | 'processing' | 'studies' | 'ml' | 'settings' | 'app'>(window.location.pathname === '/setup' ? 'app' : 'devices');
  const [, setTick] = useState(0);
  const [dbOverview, setDbOverview] = useState<DbOverview | null>(null);
  const [processingResult, setProcessingResult] = useState<PostProcessingResult | null>(null);
  const [dbStart, setDbStart] = useState(() => toDatetimeLocal(Date.now() - 5 * 60 * 1000));
  const [dbEnd, setDbEnd] = useState(() => toDatetimeLocal(Date.now()));
  const [dbStream, setDbStream] = useState('ppg');
  const [dbFeatures, setDbFeatures] = useState<string[]>(['green']);
  const [availableFeatures, setAvailableFeatures] = useState<string[]>([]);
  const [featureOptionsLoading, setFeatureOptionsLoading] = useState(false);
  const [processingExperimentId, setProcessingExperimentId] = useState('');
  const [processingDeviceRecordingId, setProcessingDeviceRecordingId] = useState('');
  const [processingRecordings, setProcessingRecordings] = useState<ExperimentDetails['device_recordings']>([]);
  const [windowLengthSeconds, setWindowLengthSeconds] = useState(10);
  const [windowStrideSeconds, setWindowStrideSeconds] = useState(10);
  const [targetSampleRate, setTargetSampleRate] = useState(125);
  const [windowLimit, setWindowLimit] = useState(100);
  const [experiments, setExperiments] = useState<ExperimentSummary[]>([]);
  const [historyDetails, setHistoryDetails] = useState<Record<number, ExperimentDetails>>({});
  const [historyExpanded, setHistoryExpanded] = useState<Set<number>>(new Set());
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [dbLoading, setDbLoading] = useState(false);
  const [dbError, setDbError] = useState<string | null>(null);
  const [mlStart, setMlStart] = useState(() => toDatetimeLocal(Date.now() - 60 * 1000));
  const [mlEnd, setMlEnd] = useState(() => toDatetimeLocal(Date.now()));
  const [mlChannel, setMlChannel] = useState('green');
  const [mlLimit, setMlLimit] = useState(1250);
  const [mlHealth, setMlHealth] = useState<MlHealth | null>(null);
  const [mlResult, setMlResult] = useState<MlResult | null>(null);
  const [mlLoading, setMlLoading] = useState(false);
  const [mlError, setMlError] = useState<string | null>(null);

  const auth = useAuth(backendUrl.replace(/\/+$/, ''));
  // Signed out the console is not shown, so polling would only produce a
  // stream of 401s against a backend that is working correctly.
  const consoleLocked = auth.configured && !auth.identity?.authenticated;
  const { backendConnected, devices, log, error, unauthorized, health, live } =
    useBackend(consoleLocked ? '' : backendUrl);

  // Tick every second so stale indicators update without waiting for packets.
  useEffect(() => {
    const id = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('wearable.theme', theme);
  }, [theme]);





  /**
   * Download an experiment's dataset export (zip with manifest and checksums).
   * Fetched with the signed-in user's token, so the browser cannot simply follow
   * a link; the bytes are handed to it as a file instead.
   */
  const downloadExport = async (experimentId: number, includeRaw: boolean) => {
    try {
      const url = `${backendUrl.replace(/\/+$/, '')}/api/experiments/${experimentId}/export${includeRaw ? '?raw=1' : ''}`;
      const response = await backendFetch(url);
      if (!response.ok) {
        let message = `Export returned ${response.status}`;
        try {
          message = (await response.json())?.error ?? message;
        } catch {
          /* not JSON */
        }
        window.alert(message);
        return;
      }
      const blob = await response.blob();
      const link = document.createElement('a');
      link.href = URL.createObjectURL(blob);
      link.download = `experiment_${experimentId}${includeRaw ? '_raw' : ''}.zip`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(link.href), 10_000);
    } catch (caught) {
      window.alert(caught instanceof Error ? caught.message : 'Export failed.');
    }
  };

  const loadExperimentHistory = useCallback(async () => {
    setHistoryLoading(true);
    setHistoryError(null);
    try {
      const response = await backendFetch(`${backendUrl.replace(/\/+$/, '')}/api/experiments`);
      const payload: { experiments?: ExperimentSummary[]; error?: string } = await response.json();
      if (!response.ok) throw new Error(payload.error ?? `experiments returned ${response.status}`);
      setExperiments(payload.experiments ?? []);
    } catch (err) {
      setHistoryError(err instanceof Error ? err.message : String(err));
    } finally {
      setHistoryLoading(false);
    }
  }, [backendUrl]);

  const toggleExperimentHistory = useCallback(async (experimentId: number) => {
    if (historyExpanded.has(experimentId)) {
      setHistoryExpanded((current) => {
        const next = new Set(current);
        next.delete(experimentId);
        return next;
      });
      return;
    }
    setHistoryExpanded((current) => new Set(current).add(experimentId));
    if (historyDetails[experimentId]) return;
    setHistoryLoading(true);
    setHistoryError(null);
    try {
      const response = await backendFetch(`${backendUrl.replace(/\/+$/, '')}/api/experiments/${experimentId}/details?limit=100`);
      const payload: ExperimentDetails & { error?: string } = await response.json();
      if (!response.ok) throw new Error(payload.error ?? `experiment details returned ${response.status}`);
      setHistoryDetails((current) => ({ ...current, [experimentId]: payload }));
    } catch (err) {
      setHistoryError(err instanceof Error ? err.message : String(err));
    } finally {
      setHistoryLoading(false);
    }
  }, [backendUrl, historyDetails, historyExpanded]);

  useEffect(() => {
    if (activeTab === 'history' || activeTab === 'processing') void loadExperimentHistory();
  }, [activeTab, loadExperimentHistory]);

  useEffect(() => {
    if (activeTab !== 'processing') return;
    const startMs = fromDatetimeLocal(dbStart);
    const endMs = fromDatetimeLocal(dbEnd);
    if (startMs === null || endMs === null || endMs <= startMs || !dbStream) return;
    const controller = new AbortController();
    setFeatureOptionsLoading(true);
    const params = new URLSearchParams({ start_ms: String(startMs), end_ms: String(endMs), stream_name: dbStream });
    if (processingExperimentId) params.set('experiment_id', processingExperimentId);
    if (processingDeviceRecordingId) params.set('device_recording_id', processingDeviceRecordingId);
    backendFetch(`${backendUrl.replace(/\/+$/, '')}/api/post-processing/options?${params.toString()}`, { signal: controller.signal })
      .then(async (response) => {
        const payload: PostProcessingOptions & { error?: string } = await response.json();
        if (!response.ok) throw new Error(payload.error ?? `feature options returned ${response.status}`);
        setAvailableFeatures(payload.features ?? []);
        setDbFeatures((selected) => selected.filter((feature) => (payload.features ?? []).includes(feature)));
      })
      .catch((err) => { if (!(err instanceof DOMException && err.name === 'AbortError')) setAvailableFeatures([]); })
      .finally(() => { if (!controller.signal.aborted) setFeatureOptionsLoading(false); });
    return () => controller.abort();
  }, [activeTab, backendUrl, dbEnd, dbStart, dbStream, processingDeviceRecordingId, processingExperimentId]);

  const selectProcessingExperiment = useCallback(async (experimentId: string) => {
    setProcessingExperimentId(experimentId);
    setProcessingDeviceRecordingId('');
    setProcessingRecordings([]);
    setProcessingResult(null);
    setDbError(null);
    if (!experimentId) return;

    const selected = experiments.find((experiment) => String(experiment.id) === experimentId);
    if (selected?.started_at_ms) setDbStart(toDatetimeLocal(selected.started_at_ms));
    if (selected?.ended_at_ms) setDbEnd(toDatetimeLocal(selected.ended_at_ms));

    try {
      const response = await backendFetch(`${backendUrl.replace(/\/+$/, '')}/api/experiments/${experimentId}/details?limit=1`);
      const details: ExperimentDetails & { error?: string } = await response.json();
      if (!response.ok) throw new Error(details.error ?? `experiment details returned ${response.status}`);
      setProcessingRecordings(details.device_recordings);
      if (details.experiment.started_at_ms) setDbStart(toDatetimeLocal(details.experiment.started_at_ms));
      if (details.experiment.ended_at_ms) setDbEnd(toDatetimeLocal(details.experiment.ended_at_ms));
      if (details.device_recordings.length === 1) {
        setProcessingDeviceRecordingId(String(details.device_recordings[0].id));
      }
    } catch (err) {
      setDbError(err instanceof Error ? err.message : String(err));
    }
  }, [backendUrl, experiments]);

  const loadPostProcessedData = useCallback(async () => {
    const startMs = fromDatetimeLocal(dbStart);
    const endMs = fromDatetimeLocal(dbEnd);
    if (startMs === null || endMs === null) {
      setDbError('Choose a valid start and end time.');
      return;
    }
    if (endMs < startMs) {
      setDbError('End time must be after start time.');
      return;
    }
    if (dbFeatures.length === 0) {
      setDbError('Select at least one feature to post-process.');
      return;
    }

    setDbLoading(true);
    setDbError(null);
    setProcessingResult(null);
    try {
      const normalized = backendUrl.replace(/\/+$/, '');
      const params = new URLSearchParams({
        start_ms: String(startMs),
        end_ms: String(endMs),
        stream_name: dbStream,
        features: dbFeatures.join(','),
        window_length_s: String(windowLengthSeconds),
        stride_s: String(windowStrideSeconds),
        target_sample_rate_hz: String(targetSampleRate),
        limit_windows: String(windowLimit),
      });
      if (processingExperimentId) params.set('experiment_id', processingExperimentId);
      if (processingDeviceRecordingId) params.set('device_recording_id', processingDeviceRecordingId);

      const [overviewResp, experimentsResp, processingResp] = await Promise.all([
        backendFetch(`${normalized}/api/db/overview`),
        backendFetch(`${normalized}/api/experiments`),
        backendFetch(`${normalized}/api/post-processing/query?${params.toString()}`),
      ]);

      if (!overviewResp.ok) throw new Error(`overview returned ${overviewResp.status}`);
      if (!experimentsResp.ok) throw new Error(`experiments returned ${experimentsResp.status}`);
      const processingPayload: PostProcessingResult & { error?: string; detail?: string } = await processingResp.json();
      if (!processingResp.ok) throw new Error(processingPayload.detail ?? processingPayload.error ?? `post-processing returned ${processingResp.status}`);

      const overviewPayload: DbOverview = await overviewResp.json();
      const experimentPayload: { experiments?: ExperimentSummary[] } = await experimentsResp.json();
      setDbOverview(overviewPayload);
      setExperiments(experimentPayload.experiments ?? []);
      setProcessingResult(processingPayload);
    } catch (err) {
      setDbError(err instanceof Error ? err.message : String(err));
    } finally {
      setDbLoading(false);
    }
  }, [backendUrl, dbEnd, dbFeatures, dbStart, dbStream, processingDeviceRecordingId, processingExperimentId, targetSampleRate, windowLengthSeconds, windowLimit, windowStrideSeconds]);

  const exportProcessedCsv = useCallback(async () => {
    if (!processingResult || processingResult.windows.length === 0) {
      setDbError('Run a post-processing query before exporting.');
      return;
    }
    const escape = (value: unknown) => `"${String(value ?? '').replace(/"/g, '""')}"`;
    const features = processingResult.manifest.features ?? [];
    const header = ['window_index', 'device_recording_id', 'device_id', 'device_type', 'measurement_config_id', 'source_start_ms', 'source_end_ms', 'source_start_sample_index', 'source_end_sample_index', 'source_sample_rate_hz', 'clock_source', 'clock_quality', 'activity_label', 'label_coverage_fraction', 'activity_labels', 'sample_offset', 'relative_time_s', 'timestamp_ms', 'target_sample_rate_hz', ...features];
    const rows = processingResult.windows.flatMap((window) => window.rows.map((row) => [
      window.window_index,
      window.device_recording_id,
      window.device_id,
      window.device_type,
      window.measurement_config_id,
      window.source_start_ms,
      window.source_end_ms,
      window.source_start_sample_index,
      window.source_end_sample_index,
      window.source_sample_rate_hz,
      window.clock_source,
      window.clock_quality,
      window.activity_labels[0]?.label_value ?? '',
      window.activity_labels[0]?.coverage_fraction ?? 1,
      JSON.stringify(window.activity_labels ?? []),
      row.sample_offset,
      row.relative_time_s,
      row.timestamp_ms,
      window.target_sample_rate_hz,
      ...features.map((feature) => row.values[feature]),
    ]));
    const csv = [header, ...rows].map((row) => row.map(escape).join(',')).join('\r\n');
    const csvHash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(csv))))
      .map((byte) => byte.toString(16).padStart(2, '0'))
      .join('');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob); const link = document.createElement('a');
    const stem = `wearable_post_processing_${fromDatetimeLocal(dbStart) ?? 'start'}_${fromDatetimeLocal(dbEnd) ?? 'end'}`;
    link.href = url; link.download = `${stem}.csv`;
    link.click(); URL.revokeObjectURL(url);

    const exportManifest = {
      ...processingResult.manifest,
      export: {
        csv_filename: `${stem}.csv`,
        csv_sha256: csvHash,
        csv_row_count: rows.length,
      },
    };
    const manifestBlob = new Blob([JSON.stringify(exportManifest, null, 2)], { type: 'application/json' });
    const manifestUrl = URL.createObjectURL(manifestBlob); const manifestLink = document.createElement('a');
    manifestLink.href = manifestUrl; manifestLink.download = `${stem}.manifest.json`;
    manifestLink.click(); URL.revokeObjectURL(manifestUrl);
  }, [dbEnd, dbStart, processingResult]);

  const checkMlHealth = useCallback(async () => {
    setMlError(null);
    try {
      const normalized = backendUrl.replace(/\/+$/, '');
      const resp = await backendFetch(`${normalized}/api/ml/health`);
      const payload: MlHealth = await resp.json();
      setMlHealth(payload);
      if (!resp.ok) {
        setMlError(payload.error ?? `ML health returned ${resp.status}`);
      }
    } catch (err) {
      setMlError(err instanceof Error ? err.message : String(err));
    }
  }, [backendUrl]);

  const runMlInference = useCallback(async () => {
    const startMs = fromDatetimeLocal(mlStart);
    const endMs = fromDatetimeLocal(mlEnd);
    if (startMs === null || endMs === null) {
      setMlError('Choose a valid start and end time.');
      return;
    }
    if (endMs < startMs) {
      setMlError('End time must be after start time.');
      return;
    }

    setMlLoading(true);
    setMlError(null);
    setMlResult(null);
    try {
      const normalized = backendUrl.replace(/\/+$/, '');
      const params = new URLSearchParams({
        start_ms: String(startMs),
        end_ms: String(endMs),
        channel: mlChannel,
        limit: String(mlLimit),
      });
      const resp = await backendFetch(`${normalized}/api/ml/infer/window?${params.toString()}`);
      const payload: MlResult = await resp.json();
      setMlResult(payload);
      if (!resp.ok) {
        setMlError(payload.detail ?? payload.error ?? payload.result?.error ?? `Inference returned ${resp.status}`);
      }
    } catch (err) {
      setMlError(err instanceof Error ? err.message : String(err));
    } finally {
      setMlLoading(false);
    }
  }, [backendUrl, mlChannel, mlEnd, mlLimit, mlStart]);

  const deviceKeys = Object.keys(devices);
  // The recording the Glasses view and the tiles beside it describe.
  const heroSession: LiveSession | null = new URLSearchParams(window.location.search).has('glasses-demo')
    ? demoSession()
    : pickHeroSession(live?.sessions ?? []);
  // Wearables in the current recording that are sending something analysable.
  const liveRecordingSessions = (live?.sessions ?? []).filter((session) => session.ppg || session.movement
    || Object.keys(session.streams ?? {}).length > 0);
  const mlEmbedding = flattenEmbedding(mlResult?.result?.embeddings);

  const statusLabel = backendConnected
    ? 'Online'
    : unauthorized
    ? 'Sign in required'
    : error
    ? 'Offline'
    : backendUrl
    ? 'Connecting…'
    : 'Offline';

  // Signed out there is one thing to do, so show only that.
  if (consoleLocked) {
    return (
      <div className="app">
      <PixelField />
        <div className="signin-backend-bar">
          <BackendSwitcher backends={backends} />
        </div>
        <SignInScreen auth={auth} backendUrl={backendUrl} brokerUrl={brokerUrl} />
      </div>
    );
  }

  return (
    <div className="app">
      <PixelField />
      <header className="header">
        <div className="shell">
          <div className="brand">
            <BrandMark />
            <div className="brand-text">
              <div className="brand-title">Wearable ML Console</div>
              <div className="brand-sub">Wearable research console</div>
            </div>
          </div>

          <nav className="header-nav" aria-label="Application views">
            <button
              className={`tab-btn ${activeTab === 'devices' ? 'active' : ''}`}
              onClick={() => setActiveTab('devices')}
            >
              Devices
            </button>
            <button
              className={`tab-btn ${activeTab === 'stream' ? 'active' : ''}`}
              onClick={() => setActiveTab('stream')}
            >
              Stream
            </button>
            <button
              className={`tab-btn ${activeTab === 'history' ? 'active' : ''}`}
              onClick={() => setActiveTab('history')}
            >
              History
            </button>
            <button
              className={`tab-btn ${activeTab === 'processing' ? 'active' : ''}`}
              onClick={() => setActiveTab('processing')}
            >
              Export
            </button>
            <button
              className={`tab-btn ${activeTab === 'studies' ? 'active' : ''}`}
              onClick={() => setActiveTab('studies')}
            >
              Studies
            </button>
            <button
              className={`tab-btn ${activeTab === 'app' ? 'active' : ''}`}
              onClick={() => setActiveTab('app')}
            >
              Get the app
            </button>
            <button
              className={`tab-btn ${activeTab === 'settings' ? 'active' : ''}`}
              onClick={() => setActiveTab('settings')}
            >
              Settings
            </button>
          </nav>

          <div className="header-right">
            <div
              className={`conn-status ${
                backendConnected ? 'connected' : error && !unauthorized ? 'error' : ''
              }`}
              title={`Backend ${statusLabel}`}
            >
              <span className="conn-dot" />
              {statusLabel}
            </div>
            <BackendSwitcher backends={backends} />
            <AccountMenu auth={auth} />
            <button
              className="theme-toggle"
              type="button"
              onClick={() => setTheme((current) => current === 'dark' ? 'light' : 'dark')}
              aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
              title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
            >
              <span aria-hidden="true">{theme === 'dark' ? '☀' : '◐'}</span>
              {theme === 'dark' ? 'Light' : 'Dark'}
            </button>
          </div>
        </div>
      </header>
      {mixedContent && !backendConnected && (
        <div className="backend-banner" role="alert">{mixedContent}</div>
      )}

      {activeTab === 'devices' ? (
        <div className="main devices-view">
          <div className="devices-layout">
          <div className="device-col bento">
            {heroSession && <GlassesHero session={heroSession} cloudOnline={backendConnected} sessions={liveRecordingSessions} />}
            {heroSession && (live?.recent_alerts?.length ?? 0) > 0 && (
              <div className="bento-alerts">
                <LiveAlertFeed events={live?.recent_alerts ?? []} />
              </div>
            )}
            {/* One tile per recording wearable: heart rate, signal and movement together. */}
            {liveRecordingSessions.map((session) => {
              const label = liveDeviceName(session, liveRecordingSessions);
              if (!expandedTiles.has(session.session_id)) {
                return (
                  <DeviceLiveTile key={session.session_id} session={session} device={label}
                    onExpand={() => toggleTile(session.session_id)} />
                );
              }
              // Expanded: everything the device card shows (both heart rates, waveform,
              // movement and heart-rate charts, alerts, per-stream health, every channel).
              const state = devices[session.device_type];
              return (
                <div key={session.session_id} className="bento-expanded">
                  <div className="bento-expanded-head">
                    <span className="tile-label">{label} · all details</span>
                    <button type="button" className="tile-expand" onClick={() => toggleTile(session.session_id)}>Collapse ▴</button>
                  </div>
                  {state ? (
                    <DeviceCard name={session.device_type} state={state} expanded onToggle={() => toggleTile(session.session_id)} live={session} />
                  ) : (
                    <>
                      <LiveSummaryBlock session={session} />
                      <LiveStreamsBlock session={session} color="var(--accent)" />
                    </>
                  )}
                </div>
              );
            })}
            {deviceKeys.length === 0 ? (
              <div className="empty-state">
                <div className="empty-icon" />
                <div className="empty-title">No devices streaming</div>
                <div className="empty-body">
                  {backendConnected
                    ? 'Backend connected. Start streaming from the Android app and point its MQTT broker at this PC on port 1883.'
                    : 'Start the Rust backend, then connect this viewer to its HTTP API.'}
                </div>
                {!backendConnected && (
                  <code className="empty-hint">cargo run --manifest-path backend\Cargo.toml</code>
                )}
              </div>
            ) : (
              <>
                <div className="device-grid">
                  {deviceKeys.filter((name) => !liveRecordingSessions.some((session) => session.device_type === name)).map((name) => (
                    <DeviceCard
                      key={name}
                      name={name}
                      state={devices[name]}
                      expanded={expandedDevices.has(name)}
                      onToggle={toggleDevice}
                      live={liveSessionFor(live, name, devices[name].address)}
                    />
                  ))}
                </div>
              </>
            )}
          </div>
          {deviceKeys.length > 0 && !heroSession && (
            <aside className="alerts-panel">
              <LiveAlertFeed events={live?.recent_alerts ?? []} />
            </aside>
          )}
          </div>


        </div>
      ) : activeTab === 'stream' ? (
        <div className="main stream-view">
          <StreamLog log={log} />
        </div>
      ) : activeTab === 'history' ? (
        <main className="settings-view db-view">
          <section className="settings-panel db-panel">
            <div className="settings-head">
              <div>
                <div className="settings-title">Experiment History</div>
              </div>
              <button className="conn-btn" onClick={loadExperimentHistory} disabled={historyLoading}>
                {historyLoading ? 'Loading' : 'Refresh'}
              </button>
            </div>
            {historyError && <div className="settings-error">{historyError}</div>}
            <div className="history-list">
              {!historyLoading && experiments.length === 0 && (
                <div className="stream-empty">No saved experiments were returned by the backend.</div>
              )}
              {experiments.map((experiment) => {
                const expanded = historyExpanded.has(experiment.id);
                const details = historyDetails[experiment.id];
                return (
                  <article className="history-card" key={experiment.id}>
                    <button className="history-summary" onClick={() => void toggleExperimentHistory(experiment.id)}>
                      <span className="history-chevron">
                        <Chevron open={expanded} />
                      </span>
                      <span className="history-name">#{experiment.id} · {experiment.experiment_label}</span>
                      <span className="history-owner">
                        {experiment.owner_label ?? 'Unattributed'}
                      </span>
                      <span className="history-study">
                        {experiment.study_name ?? 'No study'}
                      </span>
                      <span>{experiment.status ?? (experiment.ended_at_ms ? 'stopped' : 'active')}</span>
                      <span>{experiment.linked_sessions} device recording{experiment.linked_sessions === 1 ? '' : 's'}</span>
                    </button>
                    {expanded && (
                      <div className="history-detail">
                        {!details ? (
                          <div className="stream-empty">Loading experiment evidence…</div>
                        ) : (
                          <>
                            <div className="db-section btn-row">
                              <button className="conn-btn btn-primary" onClick={() => void downloadExport(experiment.id, false)}>
                                Export dataset
                              </button>
                              <button className="conn-btn" onClick={() => void downloadExport(experiment.id, true)}>
                                Export with raw packets
                              </button>
                            </div>
                            <div className="db-section">
                              <div className="panel-title">Device Recordings <span className="stream-count">{details.device_recordings.length}</span></div>
                              <div className="db-table-wrap">
                                <table className="db-table">
                                  <thead><tr><th>ID</th><th>Device</th><th>Address</th><th>Status</th><th>Started</th><th>Raw packets</th><th>Stream samples</th></tr></thead>
                                  <tbody>{details.device_recordings.map((recording) => (
                                    <tr key={recording.id}>
                                      <td>{recording.id}</td><td>{recording.device_type}</td><td>{recording.device_address ?? '-'}</td>
                                      <td>{recording.status}</td><td>{fmtTime(recording.started_at_ms)}</td>
                                      <td>{recording.raw_packet_count.toLocaleString()}</td><td>{recording.stream_sample_count.toLocaleString()}</td>
                                    </tr>
                                  ))}</tbody>
                                </table>
                              </div>
                            </div>
                            <div className="db-section">
                              <div className="panel-title">Sensor Configurations <span className="stream-count">{details.sensor_configurations.length}</span></div>
                              <div className="db-table-wrap">
                                <table className="db-table">
                                  <thead><tr><th>ID</th><th>Recording</th><th>Command</th><th>Requested streams</th><th>Requested rate</th><th>Effective rate</th></tr></thead>
                                  <tbody>{details.sensor_configurations.length === 0 ? <tr><td colSpan={6}>No sensor configuration was recorded.</td></tr> : details.sensor_configurations.map((config) => (
                                    <tr key={config.id}><td>{config.id}</td><td>{config.device_recording_id}</td><td>{config.command_name}</td><td>{config.requested_streams?.join(', ') ?? '-'}</td><td>{config.requested_sample_rate_hz ?? 'Per stream'}</td><td>{config.effective_sample_rate_hz ?? 'Unvalidated'}</td></tr>
                                  ))}</tbody>
                                </table>
                              </div>
                            </div>
                            <div className="db-section">
                              <div className="panel-title">Activity Labels <span className="stream-count">{details.activity_labels.length}</span></div>
                              <div className="db-table-wrap">
                                <table className="db-table">
                                  <thead><tr><th>ID</th><th>Label</th><th>Value</th><th>Scope</th><th>Start</th><th>End</th></tr></thead>
                                  <tbody>{details.activity_labels.length === 0 ? <tr><td colSpan={6}>No activity labels.</td></tr> : details.activity_labels.map((label) => (
                                    <tr key={label.id}><td>{label.id}</td><td>{label.label_name}</td><td>{label.label_value}</td><td>{label.scope === 'device_recording' ? `Device recording ${label.device_recording_id}` : 'All device recordings'}</td><td>{fmtTime(label.started_at_ms)}</td><td>{label.ended_at_ms ? fmtTime(label.ended_at_ms) : 'Active'}</td></tr>
                                  ))}</tbody>
                                </table>
                              </div>
                            </div>
                            <div className="db-section">
                              <div className="panel-title">Raw Packets <span className="stream-count">latest {details.raw_packets.length} / limit {details.evidence_limit}</span></div>
                              <div className="db-table-wrap evidence-table">
                                <table className="db-table">
                                  <thead><tr><th>ID</th><th>Recording</th><th>Type</th><th>Received</th><th>Hz</th><th>Status</th><th>Payload</th></tr></thead>
                                  <tbody>{details.raw_packets.length === 0 ? <tr><td colSpan={7}>No raw packets.</td></tr> : details.raw_packets.map((packet) => (
                                    <tr key={packet.id}><td>{packet.id}</td><td>{packet.device_recording_id}</td><td>{packet.packet_type ?? '-'}</td><td>{fmtTime(packet.received_timestamp_ms)}</td><td>{packet.sample_rate_hz ?? '-'}</td><td>{packet.parse_status}</td><td><code title={formatJson(packet.payload)}>{formatJson(packet.payload)}</code></td></tr>
                                  ))}</tbody>
                                </table>
                              </div>
                            </div>
                            <div className="db-section">
                              <div className="panel-title">Native Stream Samples <span className="stream-count">latest {details.stream_samples.length} / limit {details.evidence_limit}</span></div>
                              <div className="db-table-wrap evidence-table">
                                <table className="db-table">
                                  <thead><tr><th>ID</th><th>Recording</th><th>Raw packet</th><th>Stream</th><th>Received</th><th>Index</th><th>Hz</th><th>Channel values</th></tr></thead>
                                  <tbody>{details.stream_samples.length === 0 ? <tr><td colSpan={8}>No stream samples.</td></tr> : details.stream_samples.map((sample) => (
                                    <tr key={sample.id}><td>{sample.id}</td><td>{sample.device_recording_id}</td><td>{sample.raw_packet_id ?? '-'}</td><td>{sample.stream_name}</td><td>{fmtTime(sample.received_timestamp_ms)}</td><td>{sample.sample_index ?? '-'}</td><td>{sample.sample_rate_hz ?? '-'}</td><td><code title={formatJson(sample.channel_values)}>{formatJson(sample.channel_values)}</code></td></tr>
                                  ))}</tbody>
                                </table>
                              </div>
                            </div>
                          </>
                        )}
                      </div>
                    )}
                  </article>
                );
              })}
            </div>
          </section>
        </main>
      ) : activeTab === 'app' ? (
        <main className="settings-view">
          <section className="settings-panel">
            <AppDownload backendUrl={backendUrl} brokerUrl={brokerUrl} />
          </section>
        </main>
      ) : activeTab === 'studies' ? (
        <main className="settings-view db-view">
          <section className="settings-panel db-panel">
            <div className="settings-head">
              <div>
                <div className="settings-title">Studies</div>
              </div>
            </div>
            <StudiesPanel
              backendUrl={backendUrl}
              identity={auth.identity}
              onIdentityChanged={() => void auth.refreshIdentity()}
            />
          </section>
        </main>
      ) : activeTab === 'processing' ? (
        <main className="settings-view db-view">
          <section className="settings-panel db-panel">
            <div className="settings-head">
              <div>
                <div className="settings-title">Post-processing &amp; Export</div>
                <div className="settings-sub">
                  Choose the evidence and window settings, build the windows, then export
                  them with their manifest.
                </div>
              </div>
            </div>

            <div className="db-controls">
              <label className="settings-field db-field">
                <span>Experiment</span>
                <select className="conn-input settings-input" value={processingExperimentId} onChange={(e) => void selectProcessingExperiment(e.target.value)}>
                  <option value="">Select an experiment</option>
                  {experiments.map((experiment) => (
                    <option key={experiment.id} value={experiment.id}>
                      #{experiment.id} — {experiment.experiment_label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="settings-field db-field">
                <span>Device recording</span>
                <select className="conn-input settings-input" value={processingDeviceRecordingId} disabled={!processingExperimentId || processingRecordings.length === 0} onChange={(e) => { setProcessingDeviceRecordingId(e.target.value); setProcessingResult(null); }}>
                  <option value="">All recordings in experiment</option>
                  {processingRecordings.map((recording) => (
                    <option key={recording.id} value={recording.id}>
                      #{recording.id} — {recording.device_name ?? recording.device_type}{recording.device_address ? ` (${recording.device_address})` : ''}
                    </option>
                  ))}
                </select>
              </label>
              <label className="settings-field db-field">
                <span>Start Time</span>
                <input className="conn-input settings-input" type="datetime-local" step="1" value={dbStart} onChange={(e) => setDbStart(e.target.value)} />
              </label>
              <label className="settings-field db-field">
                <span>End Time</span>
                <input className="conn-input settings-input" type="datetime-local" step="1" value={dbEnd} onChange={(e) => setDbEnd(e.target.value)} />
              </label>
              <label className="settings-field db-field">
                <span>Stream</span>
                <select className="conn-input settings-input" value={dbStream} onChange={(e) => setDbStream(e.target.value)}>
                  <option value="ppg">ppg</option>
                  <option value="accel">accel</option>
                  <option value="gyro">gyro</option>
                  <option value="temperature">temperature</option>
                  <option value="pressure">pressure</option>
                  <option value="mag">mag</option>
                  <option value="other">other</option>
                </select>
              </label>
              <label className="settings-field db-field">
                <span>Features</span>
                <select className="conn-input settings-input" multiple size={Math.min(Math.max(availableFeatures.length, 2), 6)} value={dbFeatures} onChange={(e) => setDbFeatures(Array.from(e.currentTarget.selectedOptions, (option) => option.value))}>
                  {availableFeatures.map((feature) => <option key={feature} value={feature}>{feature}</option>)}
                </select>
                <small>{featureOptionsLoading ? 'Loading recorded channels…' : availableFeatures.length ? 'Select one or more channels (Ctrl/Cmd-click for multiple).' : 'No numeric channels found for this stream and time range.'}</small>
              </label>
              <label className="settings-field db-field">
                <span>Window length (seconds)</span>
                <input className="conn-input settings-input" type="number" min="0.1" step="0.1" value={windowLengthSeconds} onChange={(e) => setWindowLengthSeconds(Number(e.target.value))} />
              </label>
              <label className="settings-field db-field">
                <span>Window stride (seconds)</span>
                <input className="conn-input settings-input" type="number" min="0.1" step="0.1" value={windowStrideSeconds} onChange={(e) => setWindowStrideSeconds(Number(e.target.value))} />
              </label>
              <label className="settings-field db-field">
                <span>Target sample rate (Hz)</span>
                <input className="conn-input settings-input" type="number" min="0.1" step="0.1" value={targetSampleRate} onChange={(e) => setTargetSampleRate(Number(e.target.value))} />
              </label>
              <label className="settings-field db-field">
                <span>Maximum windows</span>
                <input className="conn-input settings-input" type="number" min="1" max="1000" value={windowLimit} onChange={(e) => setWindowLimit(Number(e.target.value))} />
              </label>
            </div>

            <div className="db-actions">
              <button
                className="conn-btn btn-primary"
                onClick={loadPostProcessedData}
                disabled={dbLoading || !processingExperimentId}
                title={
                  processingExperimentId
                    ? 'Build windows from the selected evidence'
                    : 'Select an experiment first'
                }
              >
                {dbLoading ? 'Processing…' : 'Run post-processing'}
              </button>
              <button
                className="conn-btn"
                onClick={exportProcessedCsv}
                disabled={dbLoading || !processingResult}
                title={
                  processingResult
                    ? 'Download the windows and their manifest'
                    : 'Run post-processing first'
                }
              >
                Export CSV + manifest
              </button>
              <span className="db-actions-hint">
                {!processingExperimentId
                  ? 'Select an experiment to begin.'
                  : !processingResult
                    ? 'Run post-processing to build labelled windows.'
                    : 'Windows ready — export them with their manifest.'}
              </span>
            </div>

            {dbError && <div className="settings-error">{dbError}</div>}

            {dbOverview && (
              <div className="db-overview">
                {(dbOverview.table_counts ?? []).map((item) => (
                  <div className="settings-detail" key={item.table}>
                    <span>{item.table}</span>
                    <code>{item.rows.toLocaleString()} rows</code>
                  </div>
                ))}
              </div>
            )}

            {dbOverview?.stream_summary && (
              <div className="db-section">
                <div className="panel-title">Stream Summary</div>
                <div className="db-table-wrap">
                  <table className="db-table">
                    <thead>
                      <tr>
                        <th>Stream</th>
                        <th>Rows</th>
                        <th>First</th>
                        <th>Last</th>
                        <th>Hz</th>
                      </tr>
                    </thead>
                    <tbody>
                      {dbOverview.stream_summary.map((row) => (
                        <tr key={row.stream_name}>
                          <td>{row.stream_name}</td>
                          <td>{row.rows.toLocaleString()}</td>
                          <td>{row.first_ms ? fmtTime(row.first_ms) : '-'}</td>
                          <td>{row.last_ms ? fmtTime(row.last_ms) : '-'}</td>
                          <td>{row.min_hz === row.max_hz ? row.min_hz ?? '-' : `${row.min_hz ?? '-'}-${row.max_hz ?? '-'}`}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}


            {experiments.length > 0 && (
              <div className="db-section">
                <div className="panel-title">Past Experiments <span className="stream-count">{experiments.length}</span></div>
                <div className="db-table-wrap">
                  <table className="db-table">
                    <thead><tr><th>ID</th><th>Experiment</th><th>Protocol</th><th>Status</th><th>Device recordings</th></tr></thead>
                    <tbody>{experiments.map((experiment) => (
                      <tr key={experiment.id}>
                        <td>{experiment.id}</td>
                        <td>{experiment.experiment_label}</td>
                        <td>{experiment.protocol_name ?? '-'}</td>
                        <td>{experiment.status ?? (experiment.ended_at_ms ? 'stopped' : 'active')}</td>
                        <td>{experiment.linked_sessions}</td>
                      </tr>
                    ))}</tbody>
                  </table>
                </div>
              </div>
            )}

            {processingResult && (
              <div className="db-overview">
                <div className="settings-detail"><span>Continuity segments</span><code>{processingResult.summary.segments_detected}</code></div>
                <div className="settings-detail"><span>Windows returned</span><code>{processingResult.summary.windows_returned}</code></div>
                <div className="settings-detail"><span>Rejected: missing features</span><code>{processingResult.summary.windows_rejected_missing_features}</code></div>
                <div className="settings-detail"><span>Rejected: partial/unlabelled</span><code>{processingResult.summary.windows_rejected_label_coverage}</code></div>
                <div className="settings-detail"><span>Rejected: ambiguous labels</span><code>{processingResult.summary.windows_rejected_ambiguous_labels}</code></div>
                <div className="settings-detail"><span>Storage</span><code>Query-time only</code></div>
              </div>
            )}

            <div className="db-section">
              <div className="panel-title">Processed Windows <span className="stream-count">{processingResult?.windows.length ?? 0}</span></div>
              <div className="db-table-wrap">
                <table className="db-table">
                  <thead>
                    <tr>
                      <th>Window</th>
                      <th>Device recording</th>
                      <th>Device</th>
                      <th>Source interval</th>
                      <th>Source</th>
                      <th>Output</th>
                      <th>Activity label</th>
                      <th>Features</th>
                    </tr>
                  </thead>
                  <tbody>
                    {!processingResult || processingResult.windows.length === 0 ? (
                      <tr><td colSpan={8}>Run post-processing to create fully labelled model-ready windows from preserved evidence.</td></tr>
                    ) : processingResult.windows.map((window) => (
                      <tr key={window.window_index}>
                        <td>{window.window_index}</td>
                        <td>{window.device_recording_id}</td>
                        <td>{window.device_type}</td>
                        <td>{fmtTime(window.source_start_ms)}–{fmtTime(window.source_end_ms)}</td>
                        <td>{window.source_sample_count} @ {window.source_sample_rate_hz} Hz<br /><code>{window.source_start_sample_index ?? '?'}–{window.source_end_sample_index ?? '?'}</code></td>
                        <td>{window.target_sample_count} @ {window.target_sample_rate_hz} Hz</td>
                        <td>{window.activity_labels[0]?.label_value ?? '-'}</td>
                        <td><code>{formatJson(processingResult.manifest.features ?? [])}</code></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </section>
        </main>
      ) : activeTab === 'ml' ? (
        <main className="settings-view db-view">
          <section className="settings-panel db-panel">
            <div className="settings-head">
              <div>
                <div className="settings-title">Models</div>
                <div className="settings-subtitle">Run model-specific post-processing over preserved evidence. PaPaGei returns an embedding; a prediction is shown only when a validated model head is installed.</div>
              </div>
              <div className={`conn-status ${mlHealth?.server?.model_loaded ? 'connected' : mlError ? 'error' : ''}`}>
                <span className="conn-dot" />
                {mlHealth?.enabled === false ? 'Disabled' : mlHealth?.server?.model_loaded ? 'Model Ready' : 'Unknown'}
              </div>
            </div>

            <div className="db-controls">
              <label className="settings-field db-field">
                <span>Start Time</span>
                <input className="conn-input settings-input" type="datetime-local" step="1" value={mlStart} onChange={(e) => setMlStart(e.target.value)} />
              </label>
              <label className="settings-field db-field">
                <span>End Time</span>
                <input className="conn-input settings-input" type="datetime-local" step="1" value={mlEnd} onChange={(e) => setMlEnd(e.target.value)} />
              </label>
              <label className="settings-field db-field">
                <span>PPG Channel</span>
                <select className="conn-input settings-input" value={mlChannel} onChange={(e) => setMlChannel(e.target.value)}>
                  <option value="green">green</option>
                  <option value="red">red</option>
                  <option value="ir">ir</option>
                </select>
              </label>
              <label className="settings-field db-field">
                <span>Sample Limit</span>
                <input className="conn-input settings-input" type="number" min="1" max="10000" value={mlLimit} onChange={(e) => setMlLimit(Number(e.target.value))} />
              </label>
            </div>

            <div className="ml-actions">
              <button className="conn-btn" onClick={checkMlHealth}>
                Check ML Health
              </button>
              <button className="conn-btn btn-primary" onClick={runMlInference} disabled={mlLoading}>
                {mlLoading ? 'Running' : 'Run Window Inference'}
              </button>
            </div>

            {mlError && <div className="settings-error">{mlError}</div>}

            <div className="settings-grid">
              <div className="settings-detail">
                <span>Backend ML</span>
                <code>{health?.ml_inference_enabled ? 'Enabled' : 'Disabled'}</code>
              </div>
              <div className="settings-detail">
                <span>ML Server</span>
                <code>{mlHealth?.ml_inference_url ?? health?.ml_inference_url ?? 'Unknown'}</code>
              </div>
              <div className="settings-detail">
                <span>Model Loaded</span>
                <code>{mlHealth?.server?.model_loaded === undefined ? 'Unknown' : String(mlHealth.server.model_loaded)}</code>
              </div>
              <div className="settings-detail">
                <span>Server Device</span>
                <code>{mlResult?.result?.device ?? 'No inference yet'}</code>
              </div>
            </div>

            {mlResult && (
              <div className="db-section">
                <div className="panel-title">Encoder Run</div>
                <div className="db-overview">
                  <div className="settings-detail">
                    <span>Status</span>
                    <code>{mlResult.result?.status ?? mlResult.detail ?? mlResult.error ?? 'Unknown'}</code>
                  </div>
                  <div className="settings-detail">
                    <span>Database Samples Loaded</span>
                    <code>{mlResult.window?.sample_count ?? '-'}</code>
                  </div>
                  <div className="settings-detail">
                    <span>Source Window</span>
                    <code>{mlResult.result?.preprocessing ? `${mlResult.result.preprocessing.source_sample_count} samples / ${mlResult.result.preprocessing.window_duration_s}s @ ${mlResult.result.preprocessing.source_sample_rate_hz} Hz` : '-'}</code>
                  </div>
                  <div className="settings-detail">
                    <span>Model Input</span>
                    <code>{mlResult.result?.preprocessing ? `${mlResult.result.preprocessing.target_sample_count} samples @ ${mlResult.result.preprocessing.target_sample_rate_hz} Hz` : '-'}</code>
                  </div>
                  <div className="settings-detail">
                    <span>Preprocessing</span>
                    <code>{mlResult.result?.preprocessing?.normalization ?? 'None'}</code>
                  </div>
                  <div className="settings-detail">
                    <span>Source Quality</span>
                    <code>{mlResult.result?.preprocessing?.quality?.flatline ? 'Rejected flatline' : mlResult.result?.preprocessing?.quality?.source_std?.toFixed(2) ?? '-'}</code>
                  </div>
                  <div className="settings-detail">
                    <span>Database Rate</span>
                    <code>{mlResult.window?.sample_rate ?? '-'} Hz</code>
                  </div>
                  <div className="settings-detail">
                    <span>Encoder output</span>
                    <code>{mlEmbedding.length ? `${mlEmbedding.length} dimensions` : 'None'}</code>
                  </div>
                </div>
                {mlEmbedding.length > 0 && (
                  <div className="embedding-preview">
                    <div className="panel-title">Interpretation</div>
                    <code>Feature vector returned for export or downstream use; not stored as evidence and not a physiological prediction.</code>
                  </div>
                )}
              </div>
            )}
          </section>
        </main>
      ) : (
        <main className="settings-view">
          <section className="settings-panel">
            <div className="settings-head">
              <div>
                <div className="settings-title">Backend Connection</div>
              </div>
              <div className={`conn-status ${backendConnected ? 'connected' : error ? 'error' : ''}`}>
                <span className="conn-dot" />
                {statusLabel}
              </div>
            </div>

            <div className="settings-grid">
              <div className="settings-field">
                <span>Backends</span>
                <BackendList backends={backends} />
              </div>

              <div className="settings-detail">
                <span>Active URL</span>
                <code>{backends.active ? `${backends.active.name}: ${backendUrl}` : 'Not connected'}</code>
              </div>
              <div className="settings-detail">
                <span>Database</span>
                <code>{health?.database ?? 'Unavailable'}</code>
              </div>
              <div className="settings-detail">
                <span>MQTT Broker</span>
                <code>{health?.mqtt_broker_url ?? 'Unavailable'}</code>
              </div>
              <div className="settings-detail">
                <span>MQTT Topic Filter</span>
                <code>{health?.mqtt_topic_filter ?? 'Unavailable'}</code>
              </div>
              <div className="settings-detail">
                <span>ML Inference</span>
                <code>{health?.ml_inference_enabled ? 'Enabled' : 'Disabled'}</code>
              </div>
            </div>

            {error && <div className="settings-error">{error}</div>}
          </section>
        </main>
      )}
    </div>
  );
}
