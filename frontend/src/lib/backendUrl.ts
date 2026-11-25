/** Hostnames that mean "this browser's own machine". */
function isLoopbackHost(host: string): boolean {
  return host === 'localhost' || host === '127.0.0.1' || host === '[::1]' || host === '::1';
}

export function pointsAtLoopback(url: string): boolean {
  try {
    return isLoopbackHost(new URL(url.includes('://') ? url : `http://${url}`).hostname);
  } catch {
    return false;
  }
}

/**
 * Decide which backend the console should talk to on startup.
 *
 * A saved address normally wins, because it is an explicit choice. The
 * exception is a loopback address on a deployed origin: that is a leftover from
 * local development, it cannot reach anything useful, and a public page
 * requesting it makes the browser ask the person to grant access to services
 * running on their own device. Ignoring it there avoids a prompt that should
 * never have been raised, and falls back to the deployment's configured
 * backend.
 *
 * Returning an empty string is deliberate: on a deployed origin with nothing
 * configured, the console should say it has no backend rather than quietly
 * reaching for the visitor's machine.
 */
export function resolveInitialBackendUrl(
  stored: string | null,
  configured: string | undefined,
  currentHost: string,
): string {
  const servedLocally = isLoopbackHost(currentHost);
  if (stored && (servedLocally || !pointsAtLoopback(stored))) return stored;
  // A console served by the same gateway as the API (deployment/local) calls it
  // on its own origin, so the address never has to be baked into the build.
  if (configured === 'same-origin') {
    return typeof window === 'undefined' ? '' : window.location.origin;
  }
  if (configured) return configured;
  return servedLocally ? 'http://127.0.0.1:8080' : '';
}
