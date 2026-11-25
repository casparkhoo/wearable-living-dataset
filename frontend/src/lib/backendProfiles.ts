/**
 * Named backends the console can talk to, chosen at run time.
 *
 * The build-time VITE_BACKEND_URL becomes the built-in "default" profile
 * (named "Cloud", or "This server" when the console is served by its own
 * gateway). Everything the person adds is kept in localStorage of this browser
 * only. All functions here are pure or take the storage as a parameter so they
 * can be tested without a DOM.
 */
import { pointsAtLoopback } from './backendUrl';

export const DEFAULT_PROFILE_ID = 'default';
export const PROFILES_KEY = 'wearable.backends.v1';
const LEGACY_URL_KEY = 'wearable.backendUrl';
const MAX_NAME = 40;

export interface BackendProfile {
  id: string;
  name: string;
  url: string;
  /** Optional mqtts:// broker address handed to the phone app in the setup link. */
  broker?: string;
  builtIn?: boolean;
}

/** A broker address for the setup link: mqtts only, no credentials. */
export function validateBrokerUrl(raw: string | null | undefined): string | undefined {
  const text = (raw ?? '').trim();
  if (!text) return undefined;
  try {
    const parsed = new URL(text);
    if (parsed.protocol !== 'mqtts:' || parsed.username || parsed.password || !parsed.hostname) return undefined;
    return `${parsed.protocol}//${parsed.host}`;
  } catch {
    return undefined;
  }
}

export type UrlCheck = { ok: true; url: string } | { ok: false; reason: string };

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

function isLoopbackPage(host: string): boolean {
  return host === 'localhost' || host === '127.0.0.1' || host === '[::1]' || host === '::1';
}

/** Normalise and vet a backend address: https, or http only for this machine. */
export function validateBackendUrl(raw: string): UrlCheck {
  const text = (raw ?? '').trim();
  if (!text) return { ok: false, reason: 'Enter a backend address.' };
  let parsed: URL;
  try {
    parsed = new URL(text);
  } catch {
    return { ok: false, reason: 'That is not a valid address. Use a full address such as https://api.example.org.' };
  }
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    return { ok: false, reason: 'Only https addresses are accepted.' };
  }
  if (parsed.username || parsed.password) {
    return { ok: false, reason: 'Addresses containing a user name or password are not accepted.' };
  }
  if (parsed.protocol === 'http:' && !pointsAtLoopback(parsed.href)) {
    return { ok: false, reason: 'Plain http is only accepted for localhost or 127.0.0.1. Use https.' };
  }
  if (!parsed.hostname) return { ok: false, reason: 'The address has no host.' };
  const path = parsed.pathname.replace(/\/+$/, '');
  return { ok: true, url: `${parsed.origin}${path}` };
}

export function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

export function originOf(url: string): string {
  try {
    return new URL(url).origin;
  } catch {
    return '';
  }
}

export function cleanName(raw: string | null | undefined, fallbackUrl: string): string {
  const name = (raw ?? '')
    .replace(/[\u0000-\u001f\u007f<>]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, MAX_NAME);
  return name || hostOf(fallbackUrl);
}

/** A page served over https cannot call plain http (except loopback, in most browsers). */
export function mixedContentIssue(pageProtocol: string, backendUrl: string): string | null {
  if (pageProtocol !== 'https:' || !/^http:\/\//i.test(backendUrl)) return null;
  return pointsAtLoopback(backendUrl)
    ? 'This console is served over https and this backend is plain http on your own machine. Chrome, Edge and Firefox allow that; Safari blocks it. If it stays offline, use the console served by the gateway instead.'
    : 'This console is served over https, so the browser blocks calls to a plain http backend. Use an https address.';
}

export type LinkParse =
  | { kind: 'none' }
  | { kind: 'invalid'; reason: string }
  | { kind: 'add'; url: string; name: string; broker?: string };

/** Read ?backend=<url>&name=<label> from a query string. */
export function parseBackendLink(search: string): LinkParse {
  const params = new URLSearchParams(search);
  const raw = params.get('backend');
  if (raw === null) return { kind: 'none' };
  const check = validateBackendUrl(raw);
  if (!check.ok) return { kind: 'invalid', reason: check.reason };
  return { kind: 'add', url: check.url, name: cleanName(params.get('name'), check.url), broker: validateBrokerUrl(params.get('broker')) };
}

/** The same query string with the link parameters removed. */
export function stripBackendLink(search: string): string {
  const params = new URLSearchParams(search);
  params.delete('backend');
  params.delete('name');
  params.delete('broker');
  const rest = params.toString();
  return rest ? `?${rest}` : '';
}

export interface ProfileStore {
  /** Profiles the person saved, not including the built-in default. */
  saved: BackendProfile[];
  activeId: string;
}

export function builtInDefault(
  configured: string | undefined,
  pageHost: string,
  pageOrigin: string,
): BackendProfile | null {
  const value = (configured ?? '').trim();
  if (value === 'same-origin') {
    return pageOrigin
      ? { id: DEFAULT_PROFILE_ID, name: 'This server', url: pageOrigin.replace(/\/+$/, ''), builtIn: true }
      : null;
  }
  if (value) {
    // The operator's own build setting is trusted even if it would not pass
    // the stricter check applied to addresses typed by a visitor.
    const check = validateBackendUrl(value);
    const url = check.ok ? check.url : value.replace(/\/+$/, '');
    return { id: DEFAULT_PROFILE_ID, name: 'Cloud', url, builtIn: true };
  }
  return isLoopbackPage(pageHost)
    ? { id: DEFAULT_PROFILE_ID, name: 'Local', url: 'http://127.0.0.1:8080', builtIn: true }
    : null;
}

export function allProfiles(store: ProfileStore, def: BackendProfile | null): BackendProfile[] {
  return def ? [def, ...store.saved] : [...store.saved];
}

export function activeProfile(store: ProfileStore, def: BackendProfile | null): BackendProfile | null {
  const list = allProfiles(store, def);
  return list.find((p) => p.id === store.activeId) ?? list[0] ?? null;
}

function sanitiseSaved(value: unknown): BackendProfile[] {
  if (!Array.isArray(value)) return [];
  const seen = new Set<string>();
  const out: BackendProfile[] = [];
  for (const item of value) {
    if (!item || typeof item !== 'object') continue;
    const { id, name, url, broker } = item as Record<string, unknown>;
    if (typeof id !== 'string' || typeof url !== 'string' || id === DEFAULT_PROFILE_ID || seen.has(id)) continue;
    const check = validateBackendUrl(url);
    if (!check.ok) continue;
    seen.add(id);
    out.push({
      id,
      name: cleanName(typeof name === 'string' ? name : '', check.url),
      url: check.url,
      broker: validateBrokerUrl(typeof broker === 'string' ? broker : undefined),
    });
  }
  return out;
}

let counter = 0;
export function newId(): string {
  counter += 1;
  return `p${Date.now().toString(36)}${counter.toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

export function loadStore(
  storage: StorageLike | null,
  def: BackendProfile | null,
  pageHost = '',
): ProfileStore {
  const empty: ProfileStore = { saved: [], activeId: DEFAULT_PROFILE_ID };
  if (!storage) return empty;
  try {
    const raw = storage.getItem(PROFILES_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as { saved?: unknown; activeId?: unknown };
      const saved = sanitiseSaved(parsed.saved);
      const activeId = typeof parsed.activeId === 'string' ? parsed.activeId : DEFAULT_PROFILE_ID;
      const list = allProfiles({ saved, activeId }, def);
      // An unknown id falls back to the first profile rather than to nothing.
      return list.some((p) => p.id === activeId)
        ? { saved, activeId }
        : { saved, activeId: list[0]?.id ?? DEFAULT_PROFILE_ID };
    }
    // One-time migration of the single saved address used before profiles existed.
    const legacy = storage.getItem(LEGACY_URL_KEY);
    if (legacy) {
      const check = validateBackendUrl(legacy);
      const staleLoopback = !isLoopbackPage(pageHost) && check.ok && pointsAtLoopback(check.url);
      if (check.ok && !staleLoopback && check.url !== def?.url) {
        const profile: BackendProfile = { id: newId(), name: cleanName('', check.url), url: check.url };
        return { saved: [profile], activeId: profile.id };
      }
    }
  } catch {
    /* unreadable storage: start from the default */
  }
  return empty;
}

export function saveStore(storage: StorageLike | null, store: ProfileStore): void {
  if (!storage) return;
  try {
    storage.setItem(PROFILES_KEY, JSON.stringify(store));
    storage.removeItem(LEGACY_URL_KEY);
  } catch {
    /* private mode or quota: the choice just will not be remembered */
  }
}

/** Add a profile (or select the one that already has this address) and make it active. */
export function addAndSelect(
  store: ProfileStore,
  def: BackendProfile | null,
  url: string,
  name: string,
  broker?: string,
): ProfileStore {
  const existing = allProfiles(store, def).find((p) => p.url === url);
  if (existing) return { ...store, activeId: existing.id };
  const profile: BackendProfile = { id: newId(), name: cleanName(name, url), url, broker };
  return { saved: [...store.saved, profile], activeId: profile.id };
}

export function selectProfile(store: ProfileStore, def: BackendProfile | null, id: string): ProfileStore {
  return allProfiles(store, def).some((p) => p.id === id) ? { ...store, activeId: id } : store;
}

export function removeProfile(store: ProfileStore, def: BackendProfile | null, id: string): ProfileStore {
  const saved = store.saved.filter((p) => p.id !== id);
  const activeId = store.activeId === id ? (def ? DEFAULT_PROFILE_ID : saved[0]?.id ?? DEFAULT_PROFILE_ID) : store.activeId;
  return { saved, activeId };
}
