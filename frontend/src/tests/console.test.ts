import { beforeEach, describe, expect, it } from 'vitest';

import { holdLastGood, resetHeldForTests, type LiveSession, type LiveSummary } from '../hooks/useBackend';
import { friendly } from '../lib/names';
import { pointsAtLoopback, resolveInitialBackendUrl } from '../lib/backendUrl';
import { buildSchemeLink, buildSetupLink, formatBytes, readApkInfo } from '../lib/appSetup';

function session(id: number, over: Partial<LiveSession> = {}): LiveSession {
  return {
    session_id: id, device_type: 'ring', device_address: 'a',
    window_start_ms: 0, window_end_ms: 1, streams: {}, ppg: null, movement: null,
    active_alerts: [], ...over,
  } as LiveSession;
}
function summary(sessions: LiveSession[]): LiveSummary {
  return { recipe_version: 'v', window_ms: 1, generated_at_ms: 1, sessions, recent_alerts: [] };
}
const ppg = { channel: 'g', sqi: 1, quality: 'good' as const, hr_bpm: 70 };
const trace = (over = {}) => ({ window_ms: 1, ppg_channel: 'g', ppg: [1, 2], accel: [3], hr_history: [[1, 70]] as [number, number][], ...over });

describe('holdLastGood', () => {
  beforeEach(() => resetHeldForTests());

  it('keeps pulse, movement and trace when the session stalls', () => {
    holdLastGood(summary([session(1, { ppg, movement: { relative_std: 1, state: 'still' }, trace: trace() })]));
    const out = holdLastGood(summary([session(1)]))!;
    expect(out.sessions[0].ppg).toEqual(ppg);
    expect(out.sessions[0].movement?.state).toBe('still');
    expect(out.sessions[0].trace?.ppg).toEqual([1, 2]);
  });

  it('prefers fresh values over held ones', () => {
    holdLastGood(summary([session(1, { ppg })]));
    const fresh = { ...ppg, hr_bpm: 80 };
    expect(holdLastGood(summary([session(1, { ppg: fresh })]))!.sessions[0].ppg?.hr_bpm).toBe(80);
  });

  it('merges trace arrays, falling back per array when empty', () => {
    holdLastGood(summary([session(1, { trace: trace() })]));
    const out = holdLastGood(summary([session(1, { trace: trace({ ppg: [], accel: [9], hr_history: [] }) })]))!;
    expect(out.sessions[0].trace?.ppg).toEqual([1, 2]);
    expect(out.sessions[0].trace?.accel).toEqual([9]);
    expect(out.sessions[0].trace?.hr_history).toEqual([[1, 70]]);
  });

  it('a failed poll keeps the last picture, and null before any data is null', () => {
    expect(holdLastGood(null)).toBeNull();
    const first = holdLastGood(summary([session(1, { ppg })]));
    expect(holdLastGood(null)).toBe(first);
  });

  it('forgets sessions that are gone', () => {
    holdLastGood(summary([session(1, { ppg })]));
    holdLastGood(summary([]));
    expect(holdLastGood(summary([session(1)]))!.sessions[0].ppg).toBeNull();
  });
});

describe('names', () => {
  it('renames OpenRing everywhere', () => {
    expect(friendly('OpenRing and OpenRing')).toBe('τ-Ring and τ-Ring');
    expect(friendly('Polar')).toBe('Polar');
  });
});

describe('backendUrl', () => {
  it('detects loopback addresses', () => {
    expect(pointsAtLoopback('http://127.0.0.1:8080')).toBe(true);
    expect(pointsAtLoopback('localhost:8080')).toBe(true);
    expect(pointsAtLoopback('https://api.example.org')).toBe(false);
    expect(pointsAtLoopback('')).toBe(false);
  });
  it('ignores a saved loopback address on a deployed origin', () => {
    expect(resolveInitialBackendUrl('http://localhost:8080', 'https://api.x.org', 'console.x.org')).toBe('https://api.x.org');
  });
  it('honours a saved address otherwise, and local dev defaults', () => {
    expect(resolveInitialBackendUrl('https://mine.org', 'https://api.x.org', 'console.x.org')).toBe('https://mine.org');
    expect(resolveInitialBackendUrl('http://localhost:1', undefined, 'localhost')).toBe('http://localhost:1');
    expect(resolveInitialBackendUrl(null, undefined, 'localhost')).toBe('http://127.0.0.1:8080');
    expect(resolveInitialBackendUrl(null, undefined, 'console.x.org')).toBe('');
  });
});

describe('appSetup', () => {
  it('builds the https and scheme links with encoded values', () => {
    expect(buildSetupLink('https://c.org/', 'https://api.org/', 'mqtts://b.org:8883'))
      .toBe('https://c.org/setup?backend=https%3A%2F%2Fapi.org&broker=mqtts%3A%2F%2Fb.org%3A8883');
    expect(buildSchemeLink('https://api.org')).toBe('wearablesetup://configure?backend=https%3A%2F%2Fapi.org');
  });
  it('refuses empty or non-https backends', () => {
    expect(buildSetupLink('https://c.org', '')).toBeNull();
    expect(buildSetupLink('https://c.org', 'http://127.0.0.1:8080')).toBeNull();
    expect(buildSchemeLink('')).toBeNull();
  });
  it('degrades when no APK url is set', () => {
    expect(readApkInfo({})).toBeNull();
    const info = readApkInfo({ VITE_APK_URL: 'https://x/a.apk', VITE_APK_SHA256: 'ABC', VITE_APK_SIZE_BYTES: '2097152' })!;
    expect(info.sha256).toBe('abc');
    expect(formatBytes(info.sizeBytes!)).toBe('2.0 MB');
  });
});
