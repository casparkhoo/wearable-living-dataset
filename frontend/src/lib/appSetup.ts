/**
 * Android app distribution and the setup link the phone reads at first launch.
 * The format is specified in docs/APK_DISTRIBUTION.md and must stay stable:
 *   https://<console-host>/setup?backend=<https url>&broker=<mqtts url>
 *   wearablesetup://configure?backend=<https url>&broker=<mqtts url>
 * Values are percent-encoded; `broker` is optional.
 */
export interface ApkInfo {
  url: string;
  version: string;
  sha256: string;
  sizeBytes: number | null;
}

export function readApkInfo(env: Record<string, string | undefined>): ApkInfo | null {
  const url = (env.VITE_APK_URL ?? '').trim();
  if (!url) return null;
  const size = Number(env.VITE_APK_SIZE_BYTES);
  return {
    url,
    version: (env.VITE_APK_VERSION ?? '').trim(),
    sha256: (env.VITE_APK_SHA256 ?? '').trim().toLowerCase(),
    sizeBytes: Number.isFinite(size) && size > 0 ? size : null,
  };
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function query(backend: string, broker?: string): string {
  const parts = [`backend=${encodeURIComponent(backend)}`];
  if (broker) parts.push(`broker=${encodeURIComponent(broker)}`);
  return parts.join('&');
}

/** Setup link served by the console itself; null when no usable backend is known. */
export function buildSetupLink(origin: string, backend: string, broker?: string): string | null {
  if (!backend || !/^https:\/\//i.test(backend) || !origin) return null;
  return `${origin.replace(/\/+$/, '')}/setup?${query(backend.replace(/\/+$/, ''), broker?.trim())}`;
}

export function buildSchemeLink(backend: string, broker?: string): string | null {
  if (!backend || !/^https:\/\//i.test(backend)) return null;
  return `wearablesetup://configure?${query(backend.replace(/\/+$/, ''), broker?.trim())}`;
}
