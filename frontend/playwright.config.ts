import { defineConfig } from '@playwright/test';

/**
 * Screenshot and layout checks for the console. Run with `npm run e2e`.
 * Not part of the production build or of CI: see docs/CONSOLE_UI.md.
 *
 * The console is served by Vite on a throwaway port, built against a mock
 * backend and a mock identity host. The tests answer both with fixtures
 * (e2e/fixtures) and abort every other external host.
 */
const PORT = 5199;

export default defineConfig({
  testDir: './e2e',
  testMatch: /.*\.spec\.ts/,
  timeout: 120_000,
  workers: 1,
  reporter: [['list']],
  use: { baseURL: `http://127.0.0.1:${PORT}`, browserName: 'chromium' },
  webServer: {
    command: `npx vite --host 127.0.0.1 --port ${PORT} --strictPort`,
    url: `http://127.0.0.1:${PORT}`,
    reuseExistingServer: false,
    timeout: 60_000,
    env: {
      VITE_BACKEND_URL: 'https://api.mock.test',
      VITE_SUPABASE_URL: 'https://supabase.mock.test',
      VITE_SUPABASE_PUBLISHABLE_KEY: 'sb_publishable_mock_not_a_real_key',
      VITE_APK_URL: 'https://downloads.mock.test/wearable.apk',
      VITE_APK_VERSION: '0.9.0',
      VITE_APK_SIZE_BYTES: '31457280',
      VITE_APK_SHA256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    },
  },
});
