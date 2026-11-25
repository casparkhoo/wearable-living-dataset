/**
 * Account control in the top bar.
 *
 * Only reachable once signed in, because SignInScreen gates the console before
 * that. It shows who is signed in, their platform role and their studies, and
 * offers sign-out.
 */
import { useEffect, useRef, useState } from 'react';

import type { AuthState } from '../hooks/useAuth';
import { useClampToViewport } from '../hooks/useClampToViewport';

interface Props {
  auth: AuthState;
}

export function AccountMenu({ auth }: Props) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);
  useClampToViewport(popoverRef, open);

  const signedIn = Boolean(auth.identity?.authenticated);
  const label = !auth.configured
    ? 'Sign-in unavailable'
    : signedIn
      ? (auth.identity?.email ?? auth.identity?.display_name ?? 'Signed in')
      : auth.session
        ? 'Backend not linked'
        : 'Signed out';

  // Close on outside click and on Escape, so the menu never traps focus.
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      if (!containerRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  // Close once a session exists.
  useEffect(() => {
    if (signedIn) setOpen(false);
  }, [signedIn]);

  return (
    <div className="account-menu" ref={containerRef}>
      <button
        type="button"
        className={`conn-status account-trigger ${signedIn ? 'connected' : ''}`}
        onClick={() => setOpen((value) => !value)}
        aria-haspopup="dialog"
        aria-expanded={open}
        title={label}
      >
        <span className="conn-dot" />
        <span className="account-label">{label}</span>
      </button>

      {open && (
        <div className="account-popover" role="dialog" aria-label="Account" ref={popoverRef}>
          {!auth.configured ? (
            <div className="auth-hint">
              No identity provider is configured for this deployment, so sign-in is
              unavailable.
            </div>
          ) : signedIn ? (
            <>
              <div className="auth-row">
                <div className="auth-avatar" aria-hidden="true">
                  {(auth.identity?.email ?? auth.identity?.display_name ?? '?')
                    .charAt(0)
                    .toUpperCase()}
                </div>
                <div className="auth-identity">
                  <div className="auth-name">
                    {auth.identity?.email ?? auth.identity?.display_name}
                  </div>
                  <div className="auth-meta">
                    {auth.identity?.platform_role && (
                      <span className="auth-role">
                        {auth.identity.platform_role.replace('_', ' ')}
                      </span>
                    )}
                  </div>
                </div>
              </div>

              <div className="account-studies">
                {auth.identity?.studies?.length ? (
                  <>
                    <div className="account-section-label">Studies</div>
                    {auth.identity.studies.map((study) => (
                      <div className="account-study" key={study.id}>
                        <span>{study.name}</span>
                        <span className="account-study-role">
                          {study.my_role ?? 'admin'}
                        </span>
                      </div>
                    ))}
                  </>
                ) : (
                  <div className="auth-hint">Not a member of any study yet.</div>
                )}
              </div>

              <div className="auth-actions">
                <button
                  type="button"
                  className="conn-btn"
                  onClick={() => void auth.signOut()}
                >
                  Sign out
                </button>
              </div>
            </>
          ) : null}
        </div>
      )}
    </div>
  );
}
