/**
 * Sign-in state for the console.
 *
 * The session comes from Supabase; who the person actually *is* on this
 * platform comes from the backend's `/api/me`, which reports the local account,
 * its platform role and the studies it can see. The two are deliberately
 * separate: a valid Supabase session does not by itself grant anything.
 */
import { useCallback, useEffect, useRef, useState } from 'react';

import { supabase, supabaseConfigured, type Session } from '../lib/supabase';
import { backendFetch } from './useBackend';

export interface BackendIdentity {
  authenticated: boolean;
  via: string;
  user_id?: number;
  email?: string | null;
  display_name?: string | null;
  participant_code?: string | null;
  platform_role?: string;
  mfa_enrolled?: boolean;
  studies?: Array<{
    id: number;
    name: string;
    status: string;
    my_role: string | null;
    member_count: number;
  }>;
}

export interface AuthState {
  configured: boolean;
  loading: boolean;
  session: Session | null;
  identity: BackendIdentity | null;
  error: string | null;
  signIn: (email: string, password: string) => Promise<void>;
  /** Create an account. Resolves with a message to show, or null on failure. */
  signUp: (email: string, password: string) => Promise<string | null>;
  /** Send a password-reset email. Resolves with a message, or null on failure. */
  resetPassword: (email: string) => Promise<string | null>;
  signOut: () => Promise<void>;
  refreshIdentity: () => Promise<void>;
}

export function useAuth(backendUrl: string): AuthState {
  const [session, setSession] = useState<Session | null>(null);
  const [identity, setIdentity] = useState<BackendIdentity | null>(null);
  const [loading, setLoading] = useState<boolean>(supabaseConfigured);
  const [error, setError] = useState<string | null>(null);

  // Track the Supabase session, including refreshes and sign-out in another tab.
  useEffect(() => {
    if (!supabase) {
      setLoading(false);
      return;
    }
    let active = true;
    supabase.auth
      .getSession()
      .then(({ data }) => {
        if (!active) return;
        setSession(data.session);
        setLoading(false);
      })
      .catch(() => active && setLoading(false));

    const { data: subscription } = supabase.auth.onAuthStateChange((_event, next) => {
      setSession(next);
    });
    return () => {
      active = false;
      subscription.subscription.unsubscribe();
    };
  }, []);

  // Which backend the latest identity request was for; an answer from a
  // backend the console has since left is dropped.
  const latestBackend = useRef(backendUrl);
  latestBackend.current = backendUrl;

  const refreshIdentity = useCallback(async () => {
    const asked = backendUrl;
    if (!asked) {
      setIdentity(null);
      return;
    }
    try {
      const response = await backendFetch(`${asked}/api/me`);
      if (latestBackend.current !== asked) return;
      if (!response.ok) {
        setIdentity(null);
        return;
      }
      const body = (await response.json()) as BackendIdentity;
      if (latestBackend.current === asked) setIdentity(body);
    } catch {
      if (latestBackend.current === asked) setIdentity(null);
    }
  }, [backendUrl]);

  // Ask the backend who we are whenever the session or backend changes.
  useEffect(() => {
    void refreshIdentity();
  }, [refreshIdentity, session?.access_token]);

  const signIn = useCallback(async (email: string, password: string) => {
    if (!supabase) {
      setError('Sign-in is not configured for this deployment.');
      return;
    }
    setError(null);
    setLoading(true);
    try {
      const { error: signInError } = await supabase.auth.signInWithPassword({
        email: email.trim(),
        password,
      });
      if (signInError) setError(signInError.message);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Could not sign in.');
    } finally {
      setLoading(false);
    }
  }, []);

  const signUp = useCallback(async (email: string, password: string) => {
    if (!supabase) {
      setError('Sign-up is not configured for this deployment.');
      return null;
    }
    setError(null);
    setLoading(true);
    try {
      const { data, error: signUpError } = await supabase.auth.signUp({
        email: email.trim(),
        password,
      });
      if (signUpError) {
        setError(signUpError.message);
        return null;
      }
      // With email confirmation on, no session comes back and the account is
      // unusable until the link is followed. Say so rather than appearing to
      // succeed and then refusing to sign in.
      return data.session
        ? 'Account created. You are signed in.'
        : 'Account created. Check your email to confirm it, then sign in.';
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Could not create the account.');
      return null;
    } finally {
      setLoading(false);
    }
  }, []);

  const resetPassword = useCallback(async (email: string) => {
    if (!supabase) {
      setError('Password reset is not configured for this deployment.');
      return null;
    }
    setError(null);
    setLoading(true);
    try {
      const { error: resetError } = await supabase.auth.resetPasswordForEmail(email.trim(), {
        redirectTo: window.location.origin,
      });
      if (resetError) {
        setError(resetError.message);
        return null;
      }
      // Deliberately the same message whether or not the address exists, so
      // this cannot be used to discover who holds an account.
      return 'If that address has an account, a reset link is on its way.';
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Could not send the reset email.');
      return null;
    } finally {
      setLoading(false);
    }
  }, []);

  const signOut = useCallback(async () => {
    if (!supabase) return;
    setError(null);
    await supabase.auth.signOut();
    setIdentity(null);
  }, []);

  return {
    configured: supabaseConfigured,
    loading,
    session,
    identity,
    error,
    signIn,
    signUp,
    resetPassword,
    signOut,
    refreshIdentity,
  };
}
