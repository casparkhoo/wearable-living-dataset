/**
 * Which backend the console talks to, chosen at run time.
 *
 * Holds the named-backend list, the active one, and the pending ?backend= link
 * awaiting the person's confirmation. Switching resets everything held from the
 * previous backend; the console itself is remounted by the caller (keyed on the
 * active backend), which also stops the old polling loop.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';

import { setActiveBackend } from '../lib/activeBackend';
import {
  DEFAULT_PROFILE_ID,
  activeProfile,
  addAndSelect,
  allProfiles,
  builtInDefault,
  loadStore,
  originOf,
  parseBackendLink,
  removeProfile,
  saveStore,
  selectProfile,
  stripBackendLink,
  validateBackendUrl,
  cleanName,
  type BackendProfile,
  type ProfileStore,
  type StorageLike,
} from '../lib/backendProfiles';
import { resetHeld } from './useBackend';

export interface PendingLink {
  url: string;
  name: string;
  broker?: string;
}

export interface BackendProfiles {
  profiles: BackendProfile[];
  active: BackendProfile | null;
  /** Changes whenever the console must be rebuilt for another backend. */
  activeKey: string;
  hasDefault: boolean;
  select: (id: string) => void;
  /** Validate, add and select. Returns an error message, or null on success. */
  add: (url: string, name: string) => string | null;
  remove: (id: string) => void;
  pendingLink: PendingLink | null;
  linkError: string | null;
  confirmLink: () => void;
  dismissLink: () => void;
}

function safeStorage(): StorageLike | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

export function useBackendProfiles(): BackendProfiles {
  const def = useMemo(
    () =>
      builtInDefault(
        import.meta.env.VITE_BACKEND_URL as string | undefined,
        window.location.hostname,
        window.location.origin,
      ),
    [],
  );
  const [store, setStore] = useState<ProfileStore>(() => loadStore(safeStorage(), def, window.location.hostname));
  const active = activeProfile(store, def);

  // Credentials may only be attached to requests for this origin. Set during
  // render so the very first requests already see it.
  setActiveBackend(active ? originOf(active.url) : '', active?.id === DEFAULT_PROFILE_ID);

  const [pendingLink, setPendingLink] = useState<PendingLink | null>(null);
  const [linkError, setLinkError] = useState<string | null>(null);

  const commit = useCallback((next: ProfileStore) => {
    setStore((current) => {
      const before = activeProfile(current, def);
      const after = activeProfile(next, def);
      if (before?.id !== after?.id || before?.url !== after?.url) resetHeld();
      return next;
    });
    saveStore(safeStorage(), next);
  }, [def]);

  // Read the setup link once, then take the parameters out of the address bar
  // so a reload does not ask again. /setup uses ?backend= for the phone app.
  useEffect(() => {
    if (window.location.pathname === '/setup') return;
    const parsed = parseBackendLink(window.location.search);
    if (parsed.kind === 'none') return;
    const rest = stripBackendLink(window.location.search);
    try {
      window.history.replaceState(null, '', `${window.location.pathname}${rest}${window.location.hash}`);
    } catch {
      /* address bar left as it was */
    }
    if (parsed.kind === 'invalid') {
      setLinkError(`The backend link was ignored. ${parsed.reason}`);
      return;
    }
    const known = allProfiles(store, def).find((p) => p.url === parsed.url);
    if (known && known.id === active?.id) return;
    setPendingLink({ url: parsed.url, name: parsed.name, broker: parsed.broker });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const select = useCallback((id: string) => commit(selectProfile(store, def, id)), [commit, store, def]);

  const add = useCallback((rawUrl: string, name: string): string | null => {
    const check = validateBackendUrl(rawUrl);
    if (!check.ok) return check.reason;
    commit(addAndSelect(store, def, check.url, cleanName(name, check.url)));
    return null;
  }, [commit, store, def]);

  const remove = useCallback((id: string) => commit(removeProfile(store, def, id)), [commit, store, def]);

  const confirmLink = useCallback(() => {
    if (pendingLink) commit(addAndSelect(store, def, pendingLink.url, pendingLink.name, pendingLink.broker));
    setPendingLink(null);
  }, [commit, store, def, pendingLink]);

  const dismissLink = useCallback(() => {
    setPendingLink(null);
    setLinkError(null);
  }, []);

  return {
    profiles: allProfiles(store, def),
    active,
    activeKey: active ? `${active.id}|${active.url}` : 'none',
    hasDefault: Boolean(def),
    select,
    add,
    remove,
    pendingLink,
    linkError,
    confirmLink,
    dismissLink,
  };
}
