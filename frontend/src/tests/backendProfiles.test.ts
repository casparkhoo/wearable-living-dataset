import { describe, expect, it } from 'vitest';

import {
  DEFAULT_PROFILE_ID,
  PROFILES_KEY,
  activeProfile,
  addAndSelect,
  builtInDefault,
  loadStore,
  mixedContentIssue,
  parseBackendLink,
  removeProfile,
  saveStore,
  selectProfile,
  stripBackendLink,
  validateBackendUrl,
  type StorageLike,
} from '../lib/backendProfiles';
import { mayAttachCredentials, setActiveBackend } from '../lib/activeBackend';

function memory(initial: Record<string, string> = {}): StorageLike {
  const data = { ...initial };
  return {
    getItem: (k) => (k in data ? data[k] : null),
    setItem: (k, v) => { data[k] = v; },
    removeItem: (k) => { delete data[k]; },
  };
}
const def = builtInDefault('https://api.example.org/', 'console.example.org', 'https://console.example.org')!;

describe('validateBackendUrl', () => {
  it('accepts https and normalises', () => {
    expect(validateBackendUrl(' https://API.example.org/ ')).toEqual({ ok: true, url: 'https://api.example.org' });
    expect(validateBackendUrl('https://x.org/api/')).toEqual({ ok: true, url: 'https://x.org/api' });
  });
  it('allows http only for loopback', () => {
    expect(validateBackendUrl('http://localhost:8080').ok).toBe(true);
    expect(validateBackendUrl('http://127.0.0.1:8080').ok).toBe(true);
    expect(validateBackendUrl('http://example.org').ok).toBe(false);
    expect(validateBackendUrl('http://192.168.1.5').ok).toBe(false);
  });
  it('rejects credentials, other schemes and junk', () => {
    expect(validateBackendUrl('https://user:pw@example.org').ok).toBe(false);
    expect(validateBackendUrl('https://user@example.org').ok).toBe(false);
    expect(validateBackendUrl('javascript:alert(1)').ok).toBe(false);
    expect(validateBackendUrl('ftp://example.org').ok).toBe(false);
    expect(validateBackendUrl('not a url').ok).toBe(false);
    expect(validateBackendUrl('').ok).toBe(false);
  });
  it('does not treat a look-alike host as loopback', () => {
    expect(validateBackendUrl('http://localhost.evil.org').ok).toBe(false);
  });
});

describe('link parsing', () => {
  it('reads backend, name and broker', () => {
    expect(parseBackendLink('?backend=https%3A%2F%2Fapi.lab.org&name=Lab&broker=mqtts%3A%2F%2Fm.lab.org%3A8883')).toEqual({
      kind: 'add', url: 'https://api.lab.org', name: 'Lab', broker: 'mqtts://m.lab.org:8883',
    });
  });
  it('defaults the name to the host and drops a bad broker', () => {
    const out = parseBackendLink('?backend=https://api.lab.org&broker=http://x');
    expect(out).toMatchObject({ kind: 'add', name: 'api.lab.org', broker: undefined });
  });
  it('reports none and invalid', () => {
    expect(parseBackendLink('?tab=x').kind).toBe('none');
    expect(parseBackendLink('?backend=http://evil.org').kind).toBe('invalid');
    expect(parseBackendLink('?backend=https://u:p@evil.org').kind).toBe('invalid');
  });
  it('strips link parameters only', () => {
    expect(stripBackendLink('?backend=https://a.b&name=x&keep=1')).toBe('?keep=1');
    expect(stripBackendLink('?backend=https://a.b')).toBe('');
  });
});

describe('builtInDefault', () => {
  it('names the build-time backend Cloud', () => {
    expect(def).toMatchObject({ id: DEFAULT_PROFILE_ID, name: 'Cloud', url: 'https://api.example.org' });
  });
  it('same-origin means this server', () => {
    expect(builtInDefault('same-origin', 'gw', 'http://gw:8080')).toMatchObject({ name: 'This server', url: 'http://gw:8080' });
  });
  it('has no default on a deployed origin with nothing configured', () => {
    expect(builtInDefault('', 'console.example.org', 'https://console.example.org')).toBeNull();
    expect(builtInDefault(undefined, 'localhost', 'http://localhost:5173')).toMatchObject({ name: 'Local' });
  });
});

describe('profile store', () => {
  it('adds, selects, dedupes and removes', () => {
    let s = loadStore(null, def);
    expect(activeProfile(s, def)?.id).toBe(DEFAULT_PROFILE_ID);
    s = addAndSelect(s, def, 'http://localhost:8080', 'Local');
    expect(activeProfile(s, def)?.name).toBe('Local');
    const again = addAndSelect(s, def, 'http://localhost:8080', 'Other');
    expect(again.saved).toHaveLength(1);
    expect(addAndSelect(s, def, 'https://api.example.org', '').activeId).toBe(DEFAULT_PROFILE_ID);
    const id = s.activeId;
    s = selectProfile(s, def, DEFAULT_PROFILE_ID);
    expect(s.activeId).toBe(DEFAULT_PROFILE_ID);
    expect(selectProfile(s, def, 'nope').activeId).toBe(DEFAULT_PROFILE_ID);
    s = selectProfile(s, def, id);
    s = removeProfile(s, def, id);
    expect(s.saved).toHaveLength(0);
    expect(s.activeId).toBe(DEFAULT_PROFILE_ID);
  });
  it('round-trips through storage and survives garbage', () => {
    const st = memory();
    saveStore(st, addAndSelect(loadStore(st, def), def, 'https://b.org', 'B'));
    const back = loadStore(st, def);
    expect(activeProfile(back, def)).toMatchObject({ name: 'B', url: 'https://b.org' });
    expect(loadStore(memory({ [PROFILES_KEY]: '{not json' }), def).activeId).toBe(DEFAULT_PROFILE_ID);
    const hostile = JSON.stringify({
      saved: [{ id: 'x', name: 'b', url: 'http://evil.org' }, { id: 'default', url: 'https://c.org' }],
      activeId: 'x',
    });
    const s = loadStore(memory({ [PROFILES_KEY]: hostile }), def);
    expect(s.saved).toHaveLength(0);
    expect(s.activeId).toBe(DEFAULT_PROFILE_ID);
  });
  it('tolerates storage that throws', () => {
    const boom: StorageLike = {
      getItem() { throw new Error('x'); },
      setItem() { throw new Error('x'); },
      removeItem() { throw new Error('x'); },
    };
    expect(loadStore(boom, def).activeId).toBe(DEFAULT_PROFILE_ID);
    expect(() => saveStore(boom, { saved: [], activeId: DEFAULT_PROFILE_ID })).not.toThrow();
  });
  it('migrates the old single saved address, but not a stale loopback on a deployed origin', () => {
    const s = loadStore(memory({ 'wearable.backendUrl': 'https://old.org' }), def, 'console.example.org');
    expect(activeProfile(s, def)?.url).toBe('https://old.org');
    const stale = loadStore(memory({ 'wearable.backendUrl': 'http://127.0.0.1:8080' }), def, 'console.example.org');
    expect(stale.saved).toHaveLength(0);
  });
});

describe('mixed content and credentials', () => {
  it('flags http backends from an https page', () => {
    expect(mixedContentIssue('https:', 'http://example.org')).toMatch(/blocks/);
    expect(mixedContentIssue('https:', 'http://localhost:8080')).toMatch(/Safari/);
    expect(mixedContentIssue('https:', 'https://a.org')).toBeNull();
    expect(mixedContentIssue('http:', 'http://localhost:8080')).toBeNull();
  });
  it('attaches credentials only to the active origin', () => {
    setActiveBackend('https://b.org', false);
    expect(mayAttachCredentials('https://b.org/api/me')).toEqual({ session: true, sharedToken: false });
    expect(mayAttachCredentials('https://a.org/api/me')).toEqual({ session: false, sharedToken: false });
    setActiveBackend('https://api.example.org', true);
    expect(mayAttachCredentials('https://api.example.org/health').sharedToken).toBe(true);
  });
});
