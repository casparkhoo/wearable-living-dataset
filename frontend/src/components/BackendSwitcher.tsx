/**
 * Always-visible backend indicator (name and host) with a one-click selector,
 * plus the confirmation prompt for ?backend= links.
 */
import { useEffect, useRef, useState } from 'react';

import type { BackendProfiles } from '../hooks/useBackendProfiles';
import { hostOf, mixedContentIssue } from '../lib/backendProfiles';
import { useClampToViewport } from '../hooks/useClampToViewport';

export function BackendList({ backends }: { backends: BackendProfiles }) {
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [error, setError] = useState<string | null>(null);
  const issue = backends.active ? mixedContentIssue(window.location.protocol, backends.active.url) : null;

  const submit = () => {
    const problem = backends.add(url, name);
    setError(problem);
    if (!problem) {
      setName('');
      setUrl('');
    }
  };

  return (
    <div className="backend-list">
      {backends.profiles.length === 0 && (
        <div className="auth-hint">No backend is configured for this deployment. Add one below.</div>
      )}
      {backends.profiles.map((profile) => {
        const isActive = profile.id === backends.active?.id;
        return (
          <div className={`backend-row ${isActive ? 'active' : ''}`} key={profile.id}>
            <button
              type="button"
              className="backend-pick"
              onClick={() => !isActive && backends.select(profile.id)}
              aria-pressed={isActive}
              title={profile.url}
            >
              <span className="conn-dot" />
              <span className="backend-name">{profile.name}</span>
              <span className="backend-host">{hostOf(profile.url)}</span>
            </button>
            {!profile.builtIn && (
              <button
                type="button"
                className="backend-remove"
                onClick={() => backends.remove(profile.id)}
                aria-label={`Remove ${profile.name}`}
                title="Remove"
              >
                ×
              </button>
            )}
          </div>
        );
      })}

      <div className="account-section-label">Add a backend</div>
      <input
        className="conn-input"
        placeholder="Name (optional), for example Local"
        value={name}
        onChange={(e) => setName(e.target.value)}
        maxLength={40}
      />
      <input
        className="conn-input"
        placeholder="https://api.example.org or http://localhost:8080"
        value={url}
        onChange={(e) => setUrl(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && submit()}
        spellCheck={false}
        aria-label="Backend address"
      />
      <button type="button" className="conn-btn btn-primary" onClick={submit} disabled={!url.trim()}>
        Add and switch
      </button>
      {error && <div className="settings-error">{error}</div>}
      {issue && <div className="settings-error">{issue}</div>}
      <div className="auth-hint">
        Sign-in is shared: this console signs you in with its own identity project, and every
        backend you switch to must accept it. A backend that uses a different project will refuse
        you and you will see the sign-in screen; use that backend&apos;s own console instead. Your
        sign-in token is only ever sent to the selected backend.
      </div>
    </div>
  );
}

export function BackendSwitcher({ backends }: { backends: BackendProfiles }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);
  const active = backends.active;
  useClampToViewport(popoverRef, open);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  // Switching rebuilds the console; close the menu so it does not linger.
  useEffect(() => setOpen(false), [backends.activeKey]);

  return (
    <div className="account-menu backend-switcher" ref={ref}>
      <button
        type="button"
        className="conn-status account-trigger backend-trigger"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="dialog"
        aria-expanded={open}
        title={active ? `Backend: ${active.name} (${active.url}). Click to switch.` : 'No backend selected'}
      >
        <span className="backend-label">Backend</span>
        <strong>{active?.name ?? 'None'}</strong>
        {active && <span className="backend-host">{hostOf(active.url)}</span>}
        <span aria-hidden="true">▾</span>
      </button>
      {open && (
        <div className="account-popover" role="dialog" aria-label="Choose backend" ref={popoverRef}>
          <BackendList backends={backends} />
        </div>
      )}
    </div>
  );
}

/** Asks before a ?backend= link changes where the console sends data and credentials. */
export function BackendLinkPrompt({ backends }: { backends: BackendProfiles }) {
  const { pendingLink, linkError } = backends;
  if (linkError) {
    return (
      <div className="backend-banner" role="alert">
        <span>{linkError}</span>
        <button type="button" className="conn-btn" onClick={backends.dismissLink}>Dismiss</button>
      </div>
    );
  }
  if (!pendingLink) return null;
  return (
    <div className="backend-modal-wrap" role="dialog" aria-modal="true" aria-label="Use this backend?">
      <div className="backend-modal">
        <div className="settings-title">Use this backend?</div>
        <p>A link asks this console to connect to:</p>
        <p className="backend-modal-host"><code>{hostOf(pendingLink.url)}</code></p>
        <p className="auth-hint">
          Name: {pendingLink.name}. Full address: {pendingLink.url}. If you continue, the console
          will send your sign-in token and read your data from this server, and remember it in
          this browser. Only continue if you trust whoever sent you the link.
        </p>
        <div className="auth-actions">
          <button type="button" className="conn-btn" onClick={backends.dismissLink}>Cancel</button>
          <button type="button" className="conn-btn btn-primary" onClick={backends.confirmLink}>
            Use {hostOf(pendingLink.url)}
          </button>
        </div>
      </div>
    </div>
  );
}
