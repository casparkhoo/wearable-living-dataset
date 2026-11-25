/**
 * Fixture JSON for the mocked backend used by the screenshot tests.
 *
 * The shapes follow the interfaces in src/hooks/useBackend.ts and src/App.tsx.
 * Everything here is invented: no real device address, account or study.
 */

export const API = 'https://api.mock.test';
export const SUPABASE = 'https://supabase.mock.test';

const wave = <T>(n: number, f: (i: number) => T): T[] => Array.from({ length: n }, (_, i) => f(i));

function stream(over: Record<string, unknown> = {}) {
  return {
    sample_count: 250, warming_up: false, effective_rate_hz: 25.1, nominal_rate_hz: 25, rate_ratio: 1,
    last_arrival_age_ms: 120, stalled: false, max_gap_ms: 80, jitter_ms: 4, missing: 0, duplicates: 0,
    loss_ratio: 0.004, flatline_channels: [], saturated_channels: [], ...over,
  };
}

function trace(base: number, wobble: number) {
  const now = Date.now();
  return {
    window_ms: 10000,
    ppg_channel: 'green',
    ppg: wave(250, (i) => {
      const phase = ((i / 25) * 1.2) % 1;
      return Math.exp(-(((phase - 0.17) / 0.065) ** 2)) + 0.42 * Math.exp(-(((phase - 0.45) / 0.085) ** 2));
    }),
    accel: wave(120, (i) => Math.abs(Math.sin(i / 9)) * wobble),
    hr_history: wave(60, (i) => [now - (60 - i) * 5000, base + Math.round(Math.sin(i / 6) * 4)] as [number, number]),
  };
}

export function liveSummary() {
  const now = Date.now();
  return {
    recipe_version: 'live-v3',
    window_ms: 10000,
    generated_at_ms: now,
    sessions: [
      {
        session_id: 101, device_type: 'OpenRing', device_address: 'AA:BB:CC:00:00:01',
        window_start_ms: now - 10000, window_end_ms: now,
        streams: { ppg: stream(), accel: stream({ effective_rate_hz: 50, nominal_rate_hz: 50 }) },
        ppg: { channel: 'green', sqi: 0.82, quality: 'good', hr_bpm: 72, display_hr_bpm: 72, hr_source: 'papagei' },
        movement: { relative_std: 0.01, state: 'still' },
        active_alerts: [],
        trace: trace(72, 0.2),
      },
      {
        session_id: 102, device_type: 'OpenEarable', device_address: 'AA:BB:CC:00:0E:A1',
        window_start_ms: now - 10000, window_end_ms: now,
        streams: {
          ppg: stream({ effective_rate_hz: 98.7, nominal_rate_hz: 100, loss_ratio: 0.012 }),
          accel: stream({ effective_rate_hz: 100, nominal_rate_hz: 100 }),
        },
        ppg: { channel: 'green', sqi: 0.51, quality: 'fair', hr_bpm: 69, display_hr_bpm: 70, hr_source: 'rules' },
        movement: { relative_std: 0.08, state: 'moving' },
        active_alerts: [
          { code: 'ppg_low_sqi', stream_name: 'ppg', severity: 'warning', message: 'Pulse signal quality is low on the left earbud', raised_at_ms: now - 4000 },
        ],
        trace: trace(70, 0.6),
      },
      {
        // Stalled: no sample for 95 s, so the tile says Offline and shows the held last reading.
        session_id: 103, device_type: 'OpenEarable', device_address: 'AA:BB:CC:00:0E:A2',
        window_start_ms: now - 105000, window_end_ms: now - 95000,
        streams: {
          ppg: stream({ last_arrival_age_ms: 95000, stalled: true, effective_rate_hz: 0, loss_ratio: 0.31 }),
          accel: stream({ last_arrival_age_ms: 95000, stalled: true }),
        },
        ppg: { channel: 'green', sqi: 0.33, quality: 'poor', hr_bpm: 64, display_hr_bpm: 64, hr_source: 'papagei' },
        movement: { relative_std: 0.3, state: 'active' },
        active_alerts: [
          { code: 'stream_stalled', stream_name: 'ppg', severity: 'critical', message: 'The right earbud stopped sending', raised_at_ms: now - 90000 },
          { code: 'loss_high', stream_name: 'ppg', severity: 'warning', message: 'Packet loss above 30 percent on the right earbud', raised_at_ms: now - 60000 },
        ],
        trace: trace(64, 1.1),
      },
    ],
    recent_alerts: [
      { code: 'stream_stalled', stream_name: 'ppg', severity: 'critical', message: 'The right earbud stopped sending', state: 'raised', session_id: 103, device_type: 'OpenEarable', detected_at_ms: now - 90000 },
      { code: 'ppg_low_sqi', stream_name: 'ppg', severity: 'warning', message: 'Pulse signal quality is low on the left earbud', state: 'raised', session_id: 102, device_type: 'OpenEarable', detected_at_ms: now - 4000 },
      { code: 'loss_high', stream_name: 'ppg', severity: 'warning', message: 'Packet loss above 30 percent on the right earbud', state: 'raised', session_id: 103, device_type: 'OpenEarable', detected_at_ms: now - 60000 },
      { code: 'ppg_low_sqi', stream_name: 'ppg', severity: 'info', message: 'Pulse signal quality is back to normal on the ring', state: 'cleared', session_id: 101, device_type: 'OpenRing', detected_at_ms: now - 120000 },
      { code: 'movement', stream_name: 'accel', severity: 'info', message: 'Movement detected on the left earbud', state: 'raised', session_id: 102, device_type: 'OpenEarable', detected_at_ms: now - 200000 },
    ],
  };
}

export function samples() {
  const now = Date.now();
  const out: Record<string, unknown>[] = [];
  let id = 1;
  const add = (device_type: string, address: string, stream_name: string, channel_values: Record<string, number>, rate: number, age: number) =>
    out.push({
      id: id++, session_id: 1, device_id: 1, device_type, device_address: address, raw_packet_id: id,
      stream_name, received_timestamp_ms: now - age, sample_rate_hz: rate, channel_values,
    });
  for (let k = 0; k < 6; k += 1) {
    add('OpenRing', 'AA:BB:CC:00:00:01', 'ppg', { green: 51200 + k * 31, ir: 80100 + k * 12, red: 61200 + k * 8 }, 25, k * 40);
    add('OpenRing', 'AA:BB:CC:00:00:01', 'accel', { x: 0.01 * k, y: -0.02, z: 0.98 }, 50, k * 20);
    add('OpenEarable', 'AA:BB:CC:00:0E:A1', 'ppg', { green: 40100 + k * 20, ir: 50200, red: 43000 }, 100, k * 10);
    add('OpenEarable', 'AA:BB:CC:00:0E:A1', 'accel', { x: 0.1, y: 0.0, z: 0.97 }, 100, k * 10);
    add('OpenEarable', 'AA:BB:CC:00:0E:A1', 'temperature', { value: 33.1 }, 1, k * 1000);
  }
  return { samples: out, summary: { stored_samples: 1843201, known_devices: 3 } };
}

export const health = {
  status: 'ok', database: 'mysql 8.4 (wearable)', mqtt_broker_url: 'mqtts://broker.mock.test:8883',
  mqtt_topic_filter: 'wearable/#', ml_inference_enabled: true, ml_inference_url: 'http://ml.mock.test:8000',
};

export const identity = {
  authenticated: true, via: 'supabase', user_id: 7, email: 'researcher@example.org',
  display_name: 'Test Researcher', participant_code: 'P-0007', platform_role: 'researcher', mfa_enrolled: false,
  studies: [
    { id: 1, name: 'Sleep and heart rate pilot', status: 'active', my_role: 'study_admin', member_count: 6 },
    { id: 2, name: 'Walking and PPG quality', status: 'recruiting', my_role: 'participant', member_count: 3 },
  ],
};

export const studies = {
  studies: [
    { id: 1, name: 'Sleep and heart rate pilot', status: 'active', consent_version: 'v2', ethics_reference: 'ETH-2026-014', my_role: 'study_admin', member_count: 6 },
    { id: 2, name: 'Walking and PPG quality', status: 'recruiting', consent_version: 'v1', ethics_reference: null, my_role: 'participant', member_count: 3 },
    { id: 3, name: 'A study with a deliberately long name to check that the row wraps instead of overflowing the card', status: 'draft', consent_version: null, ethics_reference: null, my_role: 'researcher', member_count: 1 },
  ],
};

export const studyDetail = {
  id: 1, name: 'Sleep and heart rate pilot', description: 'Overnight ring and earbud recordings.', status: 'active',
  consent_version: 'v2', ethics_reference: 'ETH-2026-014', created_by_user_id: 7, active_members: 6, experiments: 12,
};

export const members = {
  members: [
    { user_id: 7, participant_code: 'P-0007', display_name: 'Test Researcher', role: 'study_admin', status: 'active', consent_version: 'v2', consent_recorded: true, joined_at: '2026-10-01', withdrawn_at: null },
    { user_id: 8, participant_code: 'P-0008', display_name: 'Participant Eight', role: 'participant', status: 'active', consent_version: 'v2', consent_recorded: true, joined_at: '2026-10-03', withdrawn_at: null },
    { user_id: 9, participant_code: 'P-0009', display_name: null, role: 'participant', status: 'invited', consent_version: null, consent_recorded: false, joined_at: null, withdrawn_at: null },
  ],
};

export const experiments = {
  experiments: [
    { id: 41, experiment_label: 'Night 3, ring and earbuds', created_at: '2026-11-20T21:00:00Z', linked_sessions: 3, status: 'stopped', started_at_ms: Date.now() - 36e5, ended_at_ms: Date.now() - 18e5, owner_user_id: 8, owner_label: 'P-0008', study_id: 1, study_name: 'Sleep and heart rate pilot' },
    { id: 42, experiment_label: 'Walk around the campus lake with a long label to test wrapping', created_at: '2026-11-22T10:00:00Z', linked_sessions: 1, status: 'active', started_at_ms: Date.now() - 6e5, ended_at_ms: null, owner_user_id: null, owner_label: null, study_id: null, study_name: null },
  ],
};

export function experimentDetails() {
  const t = Date.now() - 36e5;
  return {
    experiment: { ...experiments.experiments[0], operator_label: 'Operator', notes: null },
    device_recordings: [
      { id: 201, status: 'stopped', started_at_ms: t, ended_at_ms: t + 18e5, device_type: 'OpenRing', device_address: 'AA:BB:CC:00:00:01', raw_packet_count: 45012, stream_sample_count: 90024 },
      { id: 202, status: 'stopped', started_at_ms: t, ended_at_ms: t + 18e5, device_type: 'OpenEarable', device_address: 'AA:BB:CC:00:0E:A1', raw_packet_count: 180300, stream_sample_count: 360600 },
    ],
    sensor_configurations: [
      { id: 1, device_recording_id: 201, command_name: 'start_streaming', requested_streams: ['ppg', 'accel'], effective_streams: ['ppg', 'accel'], requested_sample_rate_hz: null, effective_sample_rate_hz: 25, started_at_ms: t },
    ],
    activity_labels: [
      { id: 1, scope: 'experiment', label_name: 'activity', label_value: 'sleep', started_at_ms: t, ended_at_ms: null },
    ],
    raw_packets: [
      { id: 1, device_recording_id: 201, packet_type: 'ppg', received_timestamp_ms: t + 10, sample_rate_hz: 25, parse_status: 'ok', payload: { green: 51200, ir: 80100, red: 61200, seq: 1, extra: 'a long payload that should be clipped inside its cell and not widen the page' } },
    ],
    stream_samples: [
      { id: 1, device_recording_id: 201, raw_packet_id: 1, stream_name: 'ppg', received_timestamp_ms: t + 10, sample_index: 0, sample_rate_hz: 25, channel_values: { green: 51200, ir: 80100, red: 61200 } },
    ],
    evidence_limit: 100,
  };
}

export const dbOverview = {
  table_counts: [{ table: 'raw_packets', rows: 1843201 }, { table: 'stream_samples', rows: 3686402 }],
  stream_summary: [{ stream_name: 'ppg', rows: 1200000, first_ms: Date.now() - 864e5, last_ms: Date.now(), min_hz: 25, max_hz: 100 }],
  devices: [{ id: 1, device_type: 'OpenRing', device_address: 'AA:BB:CC:00:00:01', created_at: '2026-10-01' }],
};
