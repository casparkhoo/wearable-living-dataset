import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  // The Playwright screenshot tests live in e2e/ and run with `npm run e2e`.
  test: { exclude: ['e2e/**', 'node_modules/**', 'dist/**'] },
});
