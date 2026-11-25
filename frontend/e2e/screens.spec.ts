import { expect, test, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

import { mockNetwork, type MockOptions } from './fixtures/mock';

/**
 * Takes a screenshot of every console screen at three widths in light and dark,
 * and writes a layout report (horizontal overflow, popovers outside the
 * viewport, small touch targets, console errors) next to them.
 *
 *   SHOT_DIR=e2e/screenshots/before npm run e2e     (default: e2e/screenshots/current)
 *   SHOT_ONLY=devices,studies npm run e2e           (subset of screens)
 *   SHOT_WIDTHS=390 SHOT_THEMES=dark npm run e2e    (subset of widths / themes)
 */
const OUT = path.resolve(process.env.SHOT_DIR ?? 'e2e/screenshots/current');
const ONLY = (process.env.SHOT_ONLY ?? '').split(',').filter(Boolean);
const pick = <T,>(all: readonly T[], env?: string) =>
  env ? all.filter((x) => env.split(',').includes(String(x))) : all;
const WIDTHS = pick([390, 768, 1280] as const, process.env.SHOT_WIDTHS);
const THEMES = pick(['light', 'dark'] as const, process.env.SHOT_THEMES);

interface Screen {
  name: string;
  mock?: MockOptions;
  url?: string;
  /** Drive the page to the state to capture. */
  setup?: (page: Page) => Promise<void>;
  /** Selector of a popover or modal that must sit inside the viewport. */
  floating?: string;
}

const tab = (name: string) => async (page: Page) => {
  // dispatchEvent so the "before" run can still reach a tab that is hidden at narrow widths;
  // the layout report flags hidden navigation separately.
  await page.locator('.header-nav .tab-btn', { hasText: new RegExp(`^${name}$`) }).dispatchEvent('click');
};
const waitLive = async (page: Page) => {
  await page.locator('.tile-device').first().waitFor();
};

const SCREENS: Screen[] = [
  { name: 'signin', mock: { signedOut: true }, setup: async (p) => { await p.locator('.signin-card').waitFor(); } },
  {
    name: 'signin-create-account', mock: { signedOut: true },
    setup: async (p) => { await p.getByRole('tab', { name: 'Create account' }).click(); },
  },
  {
    name: 'signin-get-app', mock: { signedOut: true },
    setup: async (p) => { await p.locator('.signin-card details summary').click(); },
  },
  {
    name: 'signin-backend-switcher', mock: { signedOut: true }, floating: '.account-popover',
    setup: async (p) => { await p.locator('.backend-trigger').click(); },
  },
  { name: 'devices', setup: waitLive },
  {
    name: 'devices-tile-expanded',
    setup: async (p) => {
      await waitLive(p);
      await p.getByRole('button', { name: /Show all details for τ-Ring/ }).click();
    },
  },
  { name: 'devices-empty', mock: { empty: true }, setup: async (p) => { await p.locator('.empty-state').waitFor(); } },
  { name: 'stream', setup: async (p) => { await waitLive(p); await tab('Stream')(p); } },
  {
    name: 'history',
    setup: async (p) => { await waitLive(p); await tab('History')(p); await p.locator('.history-card').first().waitFor(); },
  },
  {
    name: 'history-expanded',
    setup: async (p) => {
      await waitLive(p); await tab('History')(p);
      await p.locator('.history-summary').first().click();
      await p.locator('.db-table').first().waitFor();
    },
  },
  { name: 'export', setup: async (p) => { await waitLive(p); await tab('Export')(p); await p.locator('.db-controls').waitFor(); } },
  {
    name: 'studies',
    setup: async (p) => { await waitLive(p); await tab('Studies')(p); await p.locator('.history-card').first().waitFor(); },
  },
  {
    name: 'studies-expanded',
    setup: async (p) => {
      await waitLive(p); await tab('Studies')(p);
      await p.locator('.history-summary').first().click();
      await p.getByText('Invite a member').waitFor();
    },
  },
  { name: 'get-the-app', setup: async (p) => { await waitLive(p); await tab('Get the app')(p); await p.locator('.app-download').waitFor(); } },
  { name: 'settings', setup: async (p) => { await waitLive(p); await tab('Settings')(p); await p.locator('.backend-list').waitFor(); } },
  {
    name: 'backend-switcher', floating: '.account-popover',
    setup: async (p) => { await waitLive(p); await p.locator('.backend-trigger').click(); await p.locator('.account-popover').waitFor(); },
  },
  {
    name: 'backend-add-form', floating: '.account-popover',
    setup: async (p) => {
      await waitLive(p);
      await p.locator('.backend-trigger').click();
      await p.getByPlaceholder(/Name \(optional\)/).fill('Lab server');
      await p.getByLabel('Backend address').fill('http://example.org');
      await p.getByRole('button', { name: 'Add and switch' }).click();
      await p.locator('.account-popover .settings-error').first().waitFor();
    },
  },
  {
    name: 'account-menu', floating: '.account-popover',
    setup: async (p) => { await waitLive(p); await p.locator('.account-trigger:not(.backend-trigger)').click(); await p.locator('.account-popover').waitFor(); },
  },
  {
    name: 'confirm-link-modal', url: '/?backend=https%3A%2F%2Flab.mock.test&name=Lab%20server', floating: '.backend-modal',
    setup: async (p) => { await p.locator('.backend-modal').waitFor(); },
  },
];

/**
 * The console scrolls inside its own panes, so a full-page screenshot would only show
 * the first screen. Make the window as tall as the tallest pane's content (capped), so the
 * screenshot shows everything a person would scroll through.
 */
async function growToContent(page: Page) {
  const extra = await page.evaluate(() => {
    let most = 0;
    for (const el of Array.from(document.querySelectorAll<HTMLElement>('.app *'))) {
      const o = getComputedStyle(el).overflowY;
      if ((o === 'auto' || o === 'scroll') && el.scrollHeight > el.clientHeight) most = Math.max(most, el.scrollHeight - el.clientHeight);
    }
    return most;
  });
  if (extra > 0) {
    const size = page.viewportSize()!;
    await page.setViewportSize({ width: size.width, height: Math.min(size.height + extra + 8, 4200) });
    await page.waitForTimeout(250);
  }
}

// What counts as a layout fault, measured in the page.
async function measure(page: Page, floating?: string) {
  return page.evaluate((floatingSelector) => {
    const vw = document.documentElement.clientWidth;
    const vh = window.innerHeight;
    const doc = document.documentElement;
    const issues: string[] = [];
    if (doc.scrollWidth > vw + 1) issues.push(`horizontal scroll: page ${doc.scrollWidth}px wide in a ${vw}px viewport`);
    const label = (el: Element) =>
      `${el.tagName.toLowerCase()}${el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).join('.') : ''}`;
    if (floatingSelector) {
      const el = document.querySelector(floatingSelector);
      if (el) {
        const r = el.getBoundingClientRect();
        if (r.left < -0.5 || r.right > vw + 0.5) issues.push(`${floatingSelector} outside viewport horizontally (${Math.round(r.left)}..${Math.round(r.right)} of ${vw})`);
        if (r.top < -0.5 || r.bottom > vh + 0.5) issues.push(`${floatingSelector} outside viewport vertically (${Math.round(r.top)}..${Math.round(r.bottom)} of ${vh})`);
      } else issues.push(`${floatingSelector} not found`);
    }
    // Anything sticking out of the right edge (ignore scroll containers' children).
    const seen = new Set<string>();
    for (const el of Array.from(document.body.querySelectorAll('*'))) {
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      if (r.right > vw + 1) {
        let clipped = false;
        for (let a: Element | null = el.parentElement; a && a !== document.body; a = a.parentElement) {
          const o = getComputedStyle(a).overflowX;
          if (o === 'auto' || o === 'scroll' || o === 'hidden' || o === 'clip') { clipped = true; break; }
        }
        const cs = getComputedStyle(el);
        if (!clipped && cs.position !== 'fixed' && !seen.has(label(el))) {
          seen.add(label(el));
          issues.push(`sticks out right: ${label(el)} (right ${Math.round(r.right)} > ${vw})`);
        }
      }
    }
    // Text clipped by its own box (ellipsis is fine, a hard cut is not).
    for (const el of Array.from(document.body.querySelectorAll<HTMLElement>('button, .tile-status, .tile-chip, .conn-status, .brand-title, .tab-btn'))) {
      const cs = getComputedStyle(el);
      if (el.scrollWidth > el.clientWidth + 2 && cs.overflowX !== 'visible' && cs.textOverflow !== 'ellipsis' && el.clientWidth > 0) {
        if (!seen.has('clip:' + label(el))) { seen.add('clip:' + label(el)); issues.push(`text cut off: ${label(el)} "${(el.textContent ?? '').trim().slice(0, 30)}"`); }
      }
    }
    // Navigation must be reachable.
    const nav = document.querySelector('.header-nav');
    if (nav) {
      const hidden = Array.from(nav.querySelectorAll('.tab-btn')).filter((b) => (b as HTMLElement).getClientRects().length === 0);
      if (hidden.length) issues.push(`navigation not reachable: ${hidden.length} of ${nav.querySelectorAll('.tab-btn').length} tabs are hidden`);
      else {
        // The strip may scroll sideways on a phone, but the strip itself must fit the window.
        const r = nav.getBoundingClientRect();
        if (r.left < -0.5 || r.right > vw + 0.5) issues.push('navigation strip outside the viewport');
      }
    }
    // Title wrapping.
    const title = document.querySelector('.brand-title, .signin-title') as HTMLElement | null;
    if (title) {
      const lines = Math.round(title.getBoundingClientRect().height / parseFloat(getComputedStyle(title).lineHeight || '20'));
      if (lines > 1) issues.push(`title wraps to ${lines} lines: ${label(title)}`);
    }
    // Touch targets on small screens.
    if (vw <= 480) {
      for (const el of Array.from(document.body.querySelectorAll<HTMLElement>('button, a[href], input, select, summary, [role=tab]'))) {
        const r = el.getBoundingClientRect();
        if (r.width === 0 || r.height === 0) continue;
        if (r.height < 39.5 || r.width < 39.5) {
          const k = 'touch:' + label(el);
          if (!seen.has(k)) { seen.add(k); issues.push(`small touch target ${Math.round(r.width)}x${Math.round(r.height)}: ${label(el)} "${(el.textContent ?? '').trim().slice(0, 24)}"`); }
        }
      }
    }
    return issues;
  }, floating);
}

const reportFile = path.join(OUT, 'report.json');
const report: Record<string, string[]> = (() => {
  try { return JSON.parse(fs.readFileSync(reportFile, 'utf8')); } catch { return {}; }
})();

for (const theme of THEMES) {
  for (const width of WIDTHS) {
    for (const screen of SCREENS) {
      if (ONLY.length && !ONLY.includes(screen.name)) continue;
      test(`${screen.name} ${width} ${theme}`, async ({ page }) => {
        const errors: string[] = [];
        page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
        page.on('pageerror', (e) => errors.push(String(e)));
        await page.setViewportSize({ width, height: width <= 480 ? 844 : width <= 800 ? 1024 : 900 });
        await page.addInitScript((t) => { try { localStorage.setItem('wearable.theme', t); } catch { /* none */ } }, theme);
        const external = await mockNetwork(page, screen.mock);
        await page.goto(screen.url ?? '/');
        await screen.setup?.(page);
        await page.waitForTimeout(400);
        await growToContent(page);
        const dir = path.join(OUT, `${width}-${theme}`);
        fs.mkdirSync(dir, { recursive: true });
        await page.screenshot({ path: path.join(dir, `${screen.name}.png`), fullPage: true });
        const issues = await measure(page, screen.floating);
        if (external.length) issues.push(`blocked external requests: ${external.join(', ')}`);
        // Network errors for deliberately unmocked or blocked hosts are noise; app errors are not.
        const appErrors = errors.filter((e) => !/Failed to load resource|net::ERR|status of 4\d\d/.test(e));
        for (const e of appErrors) issues.push(`console error: ${e.slice(0, 160)}`);
        report[`${width}-${theme}/${screen.name}`] = issues;
        fs.mkdirSync(OUT, { recursive: true });
        fs.writeFileSync(reportFile, JSON.stringify(report, null, 2));
        expect(true).toBe(true);
      });
    }
  }
}
