/**
 * The backend the console is currently pointed at, readable from plain
 * functions (backendFetch) that have no access to React state.
 *
 * Requests only ever carry credentials to this origin, so a stale call to a
 * backend the person has just switched away from cannot hand it their token.
 */
let activeOrigin = '';
let activeIsDefault = false;

export function setActiveBackend(origin: string, isDefault: boolean): void {
  activeOrigin = origin;
  activeIsDefault = isDefault;
}

export function mayAttachCredentials(requestUrl: string): { session: boolean; sharedToken: boolean } {
  let origin = '';
  try {
    origin = new URL(requestUrl).origin;
  } catch {
    return { session: false, sharedToken: false };
  }
  const match = Boolean(activeOrigin) && origin === activeOrigin;
  // The deployment-wide bearer token belongs to the built-in backend only.
  return { session: match, sharedToken: match && activeIsDefault };
}
