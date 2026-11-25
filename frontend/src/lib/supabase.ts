/**
 * Supabase client, used for authentication only.
 *
 * Supabase owns credentials. It does not hold any research data: experiments,
 * evidence, studies and roles all live in the backend's MySQL schema, and the
 * backend decides what this user may do by reading its own tables. The Data API
 * is disabled on the project, so this client only ever talks to `/auth/v1`.
 *
 * Configure with:
 *   VITE_SUPABASE_URL=https://<project-ref>.supabase.co
 *   VITE_SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
 *
 * The publishable key is designed to be shipped in a client. Never put a
 * Supabase `secret` / `service_role` key here.
 */
import { createClient, type SupabaseClient, type Session } from '@supabase/supabase-js';

const url = (import.meta.env.VITE_SUPABASE_URL as string | undefined)?.trim() ?? '';
const publishableKey =
  (
    (import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY as string | undefined) ??
    // The older name, for a project still issuing anon keys.
    (import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined)
  )?.trim() ?? '';

/** True when sign-in is available at all. */
export const supabaseConfigured = Boolean(url && publishableKey);

export const supabase: SupabaseClient | null = supabaseConfigured
  ? createClient(url, publishableKey, {
      auth: {
        persistSession: true,
        autoRefreshToken: true,
        detectSessionInUrl: true,
      },
    })
  : null;

/**
 * The current access token, refreshed if it has expired.
 *
 * Returns null when sign-in is unavailable or nobody is signed in, so callers
 * can fall back to the deployment-wide token during the transition.
 */
export async function currentAccessToken(): Promise<string | null> {
  if (!supabase) return null;
  try {
    const { data, error } = await supabase.auth.getSession();
    if (error) return null;
    return data.session?.access_token ?? null;
  } catch {
    // Never let an auth failure take out a page that can still render.
    return null;
  }
}

export type { Session };
