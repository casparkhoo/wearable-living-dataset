import type { Page } from '@playwright/test';
import * as fx from './backend';

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': '*',
  'access-control-allow-methods': '*',
};

export interface MockOptions {
  /** Answer /api/me as signed out (the console shows the sign-in screen). */
  signedOut?: boolean;
  /** No live sessions and no samples (the empty state). */
  empty?: boolean;
}

/**
 * Answer every request the console makes. Only the local dev server is let through;
 * the mock backend and the mock identity host are fulfilled here, and any other
 * host is aborted, so a test can never reach a real service.
 * Returns the list of blocked external URLs (should stay empty).
 */
export async function mockNetwork(page: Page, options: MockOptions = {}): Promise<string[]> {
  const external: string[] = [];
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    const reply = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', headers: CORS, body: JSON.stringify(body) });

    if (url.hostname === '127.0.0.1' || url.hostname === 'localhost') return route.fallback();
    if (route.request().method() === 'OPTIONS') return route.fulfill({ status: 204, headers: CORS });

    if (url.origin === fx.API) {
      const p = url.pathname;
      if (p === '/health') return reply(fx.health);
      if (p === '/api/me') return reply(options.signedOut ? { authenticated: false, via: 'none' } : fx.identity);
      if (p === '/api/samples/recent') {
        return reply(options.empty ? { samples: [], summary: { stored_samples: 0, known_devices: 0 } } : fx.samples());
      }
      if (p === '/api/live/summary') {
        return reply(options.empty ? { ...fx.liveSummary(), sessions: [], recent_alerts: [] } : fx.liveSummary());
      }
      if (p === '/api/studies') return reply(fx.studies);
      if (/^\/api\/studies\/\d+$/.test(p)) return reply(fx.studyDetail);
      if (/^\/api\/studies\/\d+\/members$/.test(p)) return reply(fx.members);
      if (p === '/api/experiments') return reply(fx.experiments);
      if (/^\/api\/experiments\/\d+\/details$/.test(p)) return reply(fx.experimentDetails());
      if (p === '/api/db/overview') return reply(fx.dbOverview);
      if (p === '/api/post-processing/options') return reply({ features: ['green', 'ir', 'red'] });
      if (p === '/api/ml/health') return reply({ enabled: true, status: 200, server: { status: 'ok', model_loaded: true } });
      return reply({ error: `unmocked ${p}` }, 404);
    }
    if (url.origin === fx.SUPABASE) return reply({ error: 'mock identity host' }, 401);
    external.push(url.href);
    return route.abort();
  });
  return external;
}
