import { useEffect, useState } from 'react';
import { currentAccessToken } from '../lib/supabase';
import { mayAttachCredentials } from '../lib/activeBackend';

export interface SensorPacket {
  device: string;
  address: string;
  type: string;
  timestamp: number;
  serverTimestamp: number;
  sampleRateHz: number;
  data: Record<string, number>;
}

export interface DeviceState {
  address: string;
  firstSeenAt: number;
  lastSeenAt: number;
  totalPackets: number;
  recentArrivals: number[];
  byType: Record<string, SensorPacket>;
}

export interface LogEntry {
  time: number;
  device: string;
  type: string;
  summary: string;
}

export interface BackendState {
  backendConnected: boolean;
  devices: Record<string, DeviceState>;
  log: LogEntry[];
  error: string | null;
  /**
   * The backend is reachable but refused the credential. This is not a fault:
   * it is what a signed-out console should see, and reporting it as an error
   * sends people looking for a broken server.
   */
  unauthorized: boolean;
  health: BackendHealth | null;
  monitor: MonitorSummary | null;
  /** Live analysis of active recordings; null when the backend predates it. */
  live: LiveSummary | null;
}

export interface LiveStreamHealth {
  sample_count: number;
  warming_up: boolean;
  effective_rate_hz: number | null;
  nominal_rate_hz: number | null;
  rate_ratio: number | null;
  last_arrival_age_ms: number;
  stalled: boolean;
  max_gap_ms: number | null;
  jitter_ms: number | null;
  missing: number | null;
  duplicates: number | null;
  loss_ratio: number | null;
  flatline_channels: string[];
  saturated_channels: string[];
}

export interface LiveAlert {
  code: string;
  stream_name: string | null;
  severity: 'critical' | 'warning' | 'info' | string;
  message: string;
  raised_at_ms?: number | null;
}

export interface LiveAlertEvent extends LiveAlert {
  session_id: number;
  device_type: string;
  state: 'raised' | 'cleared';
  detected_at_ms: number;
}

export interface LiveSession {
  session_id: number;
  device_type: string;
  device_address: string;
  window_start_ms: number;
  window_end_ms: number;
  streams: Record<string, LiveStreamHealth>;
  /**
   * hr_bpm is the rule-based estimate (what is stored as live_ppg). display_hr_bpm is the
   * heart rate to show, and hr_source says which method produced it ('papagei' or 'rules').
   */
  ppg: {
    channel: string; sqi: number; quality: 'good' | 'fair' | 'poor'; hr_bpm: number | null;
    hr_reason?: string | null; hr_reason_text?: string | null;
    display_hr_bpm?: number | null; hr_source?: 'papagei' | 'rules' | null;
  } | null;
  movement: { relative_std: number; state: 'still' | 'moving' | 'active' } | null;
  active_alerts: LiveAlert[];
  /** Heart rate from the model worker (PaPaGei-S + a regression head), when it runs. */
  model?: LiveModelEstimate;
  /** Last window of pulse waveform and movement, and the heart-rate trend, to plot. */
  trace?: LiveTrace;
}

export interface LiveModelEstimate {
  model_id: string;
  head_id: string;
  head_version: string | null;
  validation_status: string | null;
  runtime: string | null;
  hr_bpm: number;
  window_end_ms: number;
  inference_ms: number | null;
}

export interface LiveTrace {
  window_ms: number;
  ppg_channel: string | null;
  ppg: number[];
  accel: number[];
  /** [time_ms, bpm] over roughly the last five minutes. */
  hr_history: [number, number][];
  /** The model's heart rate over the same period, when it runs. */
  model_hr_history?: [number, number][];
  /** History of the displayed heart rate (PaPaGei or rules, as display_hr_bpm). */
  display_hr_history?: [number, number][];
}

/**
 * The last reading each recording had while its data was arriving. When a wearable's
 * stream stalls (a short Bluetooth or network drop) the backend stops computing its
 * pulse and trace; the console keeps showing these, frozen, and the tile's status
 * says Delayed or Offline, instead of the heart rate and waveform vanishing.
 */
const heldSessions = new Map<number, LiveSession>();
let heldSummary: LiveSummary | null = null;

/** Forget everything held; called when the console switches backend. */
export function resetHeld(): void {
  heldSessions.clear();
  heldSummary = null;
}

/** Kept for the existing tests. */
export const resetHeldForTests = resetHeld;

export function holdLastGood(next: LiveSummary | null): LiveSummary | null {
  if (!next) return heldSummary; // a failed poll keeps the last picture
  const sessions = next.sessions.map((session) => {
    const held = heldSessions.get(session.session_id);
    const trace = session.trace;
    const merged: LiveSession = held
      ? {
          ...session,
          ppg: session.ppg ?? held.ppg,
          movement: session.movement ?? held.movement,
          model: session.model ?? held.model,
          trace: trace || held.trace
            ? {
                ...(held.trace ?? {}),
                ...(trace ?? {}),
                ppg: trace?.ppg?.length ? trace.ppg : held.trace?.ppg ?? [],
                accel: trace?.accel?.length ? trace.accel : held.trace?.accel ?? [],
                hr_history: trace?.hr_history?.length ? trace.hr_history : held.trace?.hr_history ?? [],
                display_hr_history: trace?.display_hr_history?.length
                  ? trace.display_hr_history : held.trace?.display_hr_history,
              } as LiveTrace
            : undefined,
        }
      : session;
    heldSessions.set(session.session_id, merged);
    return merged;
  });
  const present = new Set(next.sessions.map((session) => session.session_id));
  for (const id of [...heldSessions.keys()]) if (!present.has(id)) heldSessions.delete(id);
  heldSummary = { ...next, sessions };
  return heldSummary;
}

export interface LiveSummary {
  recipe_version: string;
  window_ms: number;
  generated_at_ms: number;
  sessions: LiveSession[];
  recent_alerts: LiveAlertEvent[];
}

export interface MonitorSummary {
  stored_samples: number;
  known_devices: number;
}

interface BackendHealth {
  status: string;
  database: string;
  mqtt_broker_url: string;
  mqtt_topic_filter: string;
  ml_inference_enabled: boolean;
  ml_inference_url: string;
}

interface BackendSample {
  id: number;
  session_id: number;
  device_id: number;
  device_type: string;
  device_address: string | null;
  raw_packet_id: number | null;
  stream_name: string;
  received_timestamp_ms: number;
  sample_rate_hz: number | null;
  channel_values: Record<string, number>;
}

const ARRIVAL_WINDOW = 20;
const LOG_MAX = 80;

/**
 * Every backend call goes through here, so this is where the credential is
 * chosen.
 *
 * A signed-in user's Supabase access token wins, because it identifies a person
 * and lets the backend scope studies and evidence to them. The deployment-wide
 * bearer token is the fallback while clients are being migrated; it carries no
 * identity, so study endpoints refuse it. Once every client signs in, the
 * backend can retire it with AUTH_ALLOW_SHARED_TOKEN=false and this fallback
 * simply stops matching.
 */
export async function backendFetch(
  input: RequestInfo | URL,
  init: RequestInit = {},
): Promise<Response> {
  const headers = new Headers(init.headers);

  // Credentials go only to the backend the console is currently pointed at;
  // the shared deployment token only to the built-in one.
  const target = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
  const allowed = mayAttachCredentials(target);
  let token = '';
  if (allowed.session) {
    const accessToken = await currentAccessToken();
    let stored: string | null = null;
    if (allowed.sharedToken) {
      try {
        stored = localStorage.getItem('wearable.backendToken');
      } catch {
        stored = null;
      }
    }
    token =
      accessToken ??
      stored ??
      (allowed.sharedToken ? (import.meta.env.VITE_BACKEND_TOKEN as string | undefined) : undefined) ??
      '';
  }

  if (token.trim()) headers.set('Authorization', `Bearer ${token.trim()}`);
  return fetch(input, { ...init, headers });
}

function streamType(streamName: string): string {
  switch (streamName) {
    case 'ppg':
      return 'PPG';
    case 'accel':
    case 'gyro':
    case 'mag':
      return 'IMU';
    case 'temperature':
    case 'pressure':
      return 'TEMP_BARO';
    default:
      return streamName.toUpperCase();
  }
}

function toDisplayData(sample: BackendSample): Record<string, number> {
  const values = sample.channel_values ?? {};
  switch (sample.stream_name) {
    case 'ppg':
      return {
        ...(typeof values.green === 'number' ? { ppg_green: values.green } : {}),
        ...(typeof values.ir === 'number' ? { ppg_ir: values.ir } : {}),
        ...(typeof values.red === 'number' ? { ppg_red: values.red } : {}),
        ...(typeof values.ambient === 'number' ? { ppg_ambient: values.ambient } : {}),
      };
    case 'accel':
      return vectorData(values, 'accel');
    case 'gyro':
      return vectorData(values, 'gyro');
    case 'mag':
      return vectorData(values, 'mag');
    case 'temperature':
      return typeof values.value === 'number' ? { temperature: values.value } : {};
    case 'pressure':
      return typeof values.value === 'number' ? { pressure: values.value } : {};
    default:
      return Object.fromEntries(
        Object.entries(values).filter(([, value]) => typeof value === 'number')
      );
  }
}

function vectorData(values: Record<string, number>, prefix: string): Record<string, number> {
  return {
    ...(typeof values.x === 'number' ? { [`${prefix}_x`]: values.x } : {}),
    ...(typeof values.y === 'number' ? { [`${prefix}_y`]: values.y } : {}),
    ...(typeof values.z === 'number' ? { [`${prefix}_z`]: values.z } : {}),
  };
}

function summarise(packet: SensorPacket): string {
  const d = packet.data;
  const has = (k: string) => typeof d[k] === 'number';
  if (has('ppg_green') || has('ppg_ir') || has('ppg_red')) {
    return `G=${d.ppg_green?.toFixed(0) ?? '-'} IR=${d.ppg_ir?.toFixed(0) ?? '-'} R=${d.ppg_red?.toFixed(0) ?? '-'}`;
  }
  if (has('accel_x')) {
    return `acc(${d.accel_x?.toFixed(2)}, ${d.accel_y?.toFixed(2)}, ${d.accel_z?.toFixed(2)})`;
  }
  if (has('temperature') || has('pressure')) {
    return `temp=${d.temperature?.toFixed(1) ?? '-'} raw  pressure=${d.pressure?.toFixed(1) ?? '-'} hPa`;
  }
  const shown = Object.entries(d).filter(([, value]) => typeof value === 'number');
  if (shown.length === 0) return '-';
  return shown.slice(0, 2).map(([key, value]) => `${key}=${value.toFixed(2)}`).join(' ');
}

function buildDeviceState(samples: BackendSample[]): Pick<BackendState, 'devices' | 'log'> {
  const devices: Record<string, DeviceState> = {};
  const log: LogEntry[] = [];

  for (const sample of [...samples].reverse()) {
    const data = toDisplayData(sample);
    if (Object.keys(data).length === 0) continue;

    const device = sample.device_type || `Device ${sample.device_id}`;
    const time = sample.received_timestamp_ms;
    const type = streamType(sample.stream_name);
    const packet: SensorPacket = {
      device,
      address: sample.device_address ?? '',
      type,
      timestamp: time,
      serverTimestamp: time,
      sampleRateHz: sample.sample_rate_hz ?? 0,
      data,
    };

    const existing = devices[device] ?? {
      address: sample.device_address ?? '',
      firstSeenAt: time,
      lastSeenAt: time,
      totalPackets: 0,
      recentArrivals: [],
      byType: {},
    };

    const previousPacket = existing.byType[type];
    devices[device] = {
      address: existing.address || sample.device_address || '',
      firstSeenAt: Math.min(existing.firstSeenAt, time),
      lastSeenAt: Math.max(existing.lastSeenAt, time),
      totalPackets: existing.totalPackets + 1,
      recentArrivals: [...existing.recentArrivals, time].slice(-ARRIVAL_WINDOW),
      byType: {
        ...existing.byType,
        [type]: previousPacket
          ? { ...packet, data: { ...previousPacket.data, ...packet.data } }
          : packet,
      },
    };

    log.push({
      time,
      device,
      type,
      summary: summarise(packet),
    });
  }

  return { devices, log: log.reverse().slice(0, LOG_MAX) };
}

export function useBackend(baseUrl: string): BackendState {
  const [state, setState] = useState<BackendState>({
    backendConnected: false,
    devices: {},
    log: [],
    error: null,
    unauthorized: false,
    health: null,
    monitor: null,
    live: null,
  });

  useEffect(() => {
    if (!baseUrl) return;

    let cancelled = false;
    const normalized = baseUrl.replace(/\/+$/, '');

    async function poll() {
      try {
        const [healthResp, samplesResp, liveResp] = await Promise.all([
          backendFetch(`${normalized}/health`),
          backendFetch(`${normalized}/api/samples/recent?limit=500`),
          // Live analysis is optional: an older backend answers 404 and the
          // monitor simply shows raw readings as before.
          backendFetch(`${normalized}/api/live/summary`).catch(() => null),
        ]);

        if (healthResp.status === 401 || samplesResp.status === 401) {
          if (!cancelled) {
            setState((prev) => ({
              ...prev,
              backendConnected: false,
              unauthorized: true,
              error: null,
            }));
          }
          return;
        }
        if (!healthResp.ok) throw new Error(`health returned ${healthResp.status}`);
        if (!samplesResp.ok) throw new Error(`samples returned ${samplesResp.status}`);

        const health: BackendHealth = await healthResp.json();
        const samplePayload: { samples: BackendSample[]; summary?: MonitorSummary } = await samplesResp.json();
        const { devices, log } = buildDeviceState(samplePayload.samples ?? []);
        // A poll that was superseded by a backend switch must not touch the
        // held state, or the old backend's sessions would reappear.
        const livePayload = liveResp?.ok ? await liveResp.json() : null;
        if (cancelled) return;
        const live = holdLastGood(livePayload);

        if (!cancelled) {
          setState({
            backendConnected: true,
            devices,
            log,
            error: null,
            unauthorized: false,
            health,
            monitor: samplePayload.summary ?? null,
            live,
          });
        }
      } catch (error) {
        if (!cancelled) {
          setState((prev) => ({
            ...prev,
            backendConnected: false,
            unauthorized: false,
            error: error instanceof Error ? error.message : String(error),
          }));
        }
      }
    }

    // One request round at a time: a slow backend must not be asked again
    // while it is still answering, or the polls pile up on it.
    let timer: number | undefined;
    const loop = async () => {
      await poll();
      if (!cancelled) timer = window.setTimeout(loop, 1000);
    };
    loop();
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [baseUrl]);

  return state;
}

export function computeRate(arrivals: number[]): number {
  if (arrivals.length < 2) return 0;
  const span = (arrivals[arrivals.length - 1] - arrivals[0]) / 1000;
  if (span <= 0) return 0;
  return (arrivals.length - 1) / span;
}

/** The heart rate to show for a live session (PaPaGei when the backend prefers it). */
export function shownHeartRate(session: LiveSession | null | undefined): number | null {
  const ppg = session?.ppg;
  if (!ppg) return null;
  if (ppg.display_hr_bpm !== undefined) return ppg.display_hr_bpm ?? null;
  return ppg.hr_bpm ?? null;
}

/** Which method produced shownHeartRate, for labelling it. */
export function shownHeartRateSource(session: LiveSession | null | undefined): string {
  const source = session?.ppg?.hr_source;
  return source === 'papagei' ? 'PaPaGei' : source === 'rules' ? 'rules' : '';
}

/** The trend that matches shownHeartRate. */
export function shownHeartRateHistory(session: LiveSession | null | undefined): [number, number][] {
  const trace = session?.trace;
  if (!trace) return [];
  return (trace.display_hr_history ?? trace.hr_history ?? []) as [number, number][];
}
