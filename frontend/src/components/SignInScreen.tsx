/**
 * The whole console before sign-in.
 *
 * One card with three modes rather than three screens: signing in, creating an
 * account and asking for a reset are the same two fields in different
 * combinations, and switching between them should not feel like navigating.
 *
 * Rendering the full console signed out would leave every panel polling and
 * failing on 401, which reads as a broken system when nothing is wrong.
 */
import { useState } from 'react';

import type { AuthState } from '../hooks/useAuth';
import { BrandMark } from './BrandMark';
import { AppDownload } from './AppDownload';

type Mode = 'signin' | 'signup' | 'reset';

interface Props {
  auth: AuthState;
  backendUrl: string;
  brokerUrl?: string;
}

const MIN_PASSWORD = 8;

export function SignInScreen({ auth, backendUrl, brokerUrl }: Props) {
  const [mode, setMode] = useState<Mode>('signin');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [localError, setLocalError] = useState<string | null>(null);

  const sessionWithoutAccount = Boolean(auth.session) && !auth.identity?.authenticated;

  const switchMode = (next: Mode) => {
    setMode(next);
    setNotice(null);
    setLocalError(null);
    setPassword('');
    setConfirm('');
  };

  const submit = async () => {
    setLocalError(null);
    setNotice(null);

    if (mode === 'reset') {
      if (!email.trim()) return;
      const message = await auth.resetPassword(email);
      if (message) setNotice(message);
      return;
    }

    if (mode === 'signup') {
      // Checked here so the person is told before a round trip, not after.
      if (password.length < MIN_PASSWORD) {
        setLocalError(`Use at least ${MIN_PASSWORD} characters.`);
        return;
      }
      if (password !== confirm) {
        setLocalError('The two passwords do not match.');
        return;
      }
      const message = await auth.signUp(email, password);
      if (message) {
        setNotice(message);
        setMode('signin');
        setPassword('');
        setConfirm('');
      }
      return;
    }

    await auth.signIn(email, password);
  };

  const canSubmit =
    Boolean(email.trim()) &&
    (mode === 'reset' || Boolean(password)) &&
    (mode !== 'signup' || Boolean(confirm)) &&
    !auth.loading;

  const action =
    mode === 'signin' ? 'Sign in' : mode === 'signup' ? 'Create account' : 'Send reset link';

  return (
    <div className="signin-screen">
      <div className="signin-card">
        <div className="signin-head">
          <BrandMark size={44} />
          <h1 className="signin-title">Wearable ML Console</h1>
        </div>

        {auth.configured ? (
          <>
            <div className="signin-tabs" role="tablist">
              <button
                type="button"
                role="tab"
                aria-selected={mode === 'signin'}
                className={`signin-tab ${mode === 'signin' ? 'active' : ''}`}
                onClick={() => switchMode('signin')}
              >
                Sign in
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={mode === 'signup'}
                className={`signin-tab ${mode === 'signup' ? 'active' : ''}`}
                onClick={() => switchMode('signup')}
              >
                Create account
              </button>
            </div>

            <div className="auth-fields">
              <label className="signin-label" htmlFor="signin-email">
                Email
              </label>
              <input
                id="signin-email"
                className="conn-input signin-input"
                type="email"
                autoComplete="username"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                onKeyDown={(event) => event.key === 'Enter' && canSubmit && void submit()}
                placeholder="you@example.org"
                spellCheck={false}
                autoFocus
              />

              {mode !== 'reset' && (
                <>
                  <label className="signin-label" htmlFor="signin-password">
                    Password
                  </label>
                  <input
                    id="signin-password"
                    className="conn-input signin-input"
                    type="password"
                    autoComplete={mode === 'signup' ? 'new-password' : 'current-password'}
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    onKeyDown={(event) => event.key === 'Enter' && canSubmit && void submit()}
                    placeholder={mode === 'signup' ? `At least ${MIN_PASSWORD} characters` : '••••••••'}
                  />
                </>
              )}

              {mode === 'signup' && (
                <>
                  <label className="signin-label" htmlFor="signin-confirm">
                    Confirm password
                  </label>
                  <input
                    id="signin-confirm"
                    className="conn-input signin-input"
                    type="password"
                    autoComplete="new-password"
                    value={confirm}
                    onChange={(event) => setConfirm(event.target.value)}
                    onKeyDown={(event) => event.key === 'Enter' && canSubmit && void submit()}
                    placeholder="••••••••"
                  />
                </>
              )}
            </div>

            <button
              type="button"
              className="signin-submit"
              disabled={!canSubmit}
              onClick={() => void submit()}
            >
              {auth.loading ? 'Working…' : action}
            </button>

            {(localError || auth.error) && (
              <div className="auth-error">{localError ?? auth.error}</div>
            )}
            {notice && <div className="signin-success">{notice}</div>}

            {sessionWithoutAccount && (
              <div className="signin-note">
                You are signed in to the identity provider, but the backend did not
                recognise the session. It is configured at <code>{backendUrl || 'no URL'}</code>.
              </div>
            )}

            <div className="signin-links">
              {mode === 'reset' ? (
                <button type="button" className="link-btn" onClick={() => switchMode('signin')}>
                  Back to sign in
                </button>
              ) : (
                <button type="button" className="link-btn" onClick={() => switchMode('reset')}>
                  Forgot your password?
                </button>
              )}
            </div>

            <p className="signin-foot">
              {mode === 'signup'
                ? 'New accounts join as participants. A study admin invites you to a study before you can record into it.'
                : 'Signing in attributes your recordings to you and gives access to the studies you belong to.'}
            </p>
          </>
        ) : (
          <div className="signin-note">
            No identity provider is configured for this deployment, so sign-in is
            unavailable. Set <code>VITE_SUPABASE_URL</code> and{' '}
            <code>VITE_SUPABASE_PUBLISHABLE_KEY</code> and redeploy.
          </div>
        )}
        <details className="signin-note">
          <summary>Get the Android app</summary>
          <AppDownload backendUrl={backendUrl} brokerUrl={brokerUrl} compact />
        </details>
      </div>
    </div>
  );
}
