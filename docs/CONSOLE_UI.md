# Console UI: tokens, screenshots, fixes

## Screenshots (Playwright, mocked network)

```
cd frontend
npm install
npx playwright install chromium     # once
npm run e2e                         # -> e2e/screenshots/current (gitignored)
SHOT_DIR=e2e/screenshots/after SHOT_WIDTHS=390 SHOT_THEMES=dark SHOT_ONLY=devices npm run e2e
```

`e2e/screens.spec.ts` captures 19 screens (sign-in, devices, expanded tile, empty, stream, history, export,
studies, Get the app, settings, backend switcher, add-backend form, confirm-link modal, account menu) at 390,
768 and 1280 px, light and dark, and writes `report.json` with layout faults: horizontal scroll, popovers outside
the viewport, text cut off, title wrapping, unreachable navigation, touch targets under 40 px on phones, console
errors. Playwright starts Vite on port 5199 with fake `VITE_*` values (mock backend `https://api.mock.test`, mock
identity host). `e2e/fixtures/mock.ts` answers every backend request with the JSON in
`e2e/fixtures/backend.ts` and aborts any other external host, so nothing real is contacted. No production code
was changed for mocking: sign-in is faked by the mock `/api/me` reply. The shapes are copied from the TypeScript
interfaces, not from a live backend. E2E is not part of CI or the Vercel build (`npm run e2e` only).

Before/after images: `docs/images/console-ui/`.

## Tokens (top of `frontend/src/App.css`)

| Group | Tokens |
|---|---|
| Spacing | `--sp-1..6` = 4, 8, 12, 16, 24, 32 px; `--gutter` 28 / 16 / 12 px by width |
| Type | `--fs-xs` 11, `--fs-sm` 12, `--fs-md` 13, `--fs-lg` 15, `--fs-xl` 20, `--fs-stat` 40; weights 400/600/700 |
| Shape | `--radius-sm` 2 px (chips, rows inside cards), `--radius` 4 px (cards, controls, popovers) |
| Controls | `--control-h` 36, `--control-h-sm` 28 (40 and 40 on phones), `--chip-h` 22 |
| Cards | `--card-pad` 16, `--card-pad-lg` 24 (12 and 16 on phones), `--card-border` |
| Colour | existing `--surface*`, `--accent*`, status colours, plus `--on-accent`; dark mode overrides in one place |

Roles: heading = 15 px bold; label = 11 px bold mono uppercase; button secondary (`.conn-btn`) or primary
(`.btn-primary`, `.signin-submit`); chip = `.tile-chip`, `.tile-status`, status pill, role, count.
The "Consistency layer" at the end of `App.css` restates the shared pieces with these tokens.

## What was fixed

- Header wrapped the title to three lines and ran off the window at 1280 px; it now never wraps the title, and
  below 1300 px stacks into brand/status row plus a view row (compact three-row layout on phones).
- Navigation was hidden below 1080 px (no way to reach Stream, History, Studies, Get the app); now a tab strip.
- Backend and account popovers were clipped at narrow widths; they are clamped inside the viewport
  (`useClampToViewport`), with max-height and scroll. The modal scrolls inside the viewport.
- "Offline - last reading" chip clipped; the tile header wraps and the tile is no longer faded.
- One card style (surface, border, radius, padding), one chip style, one button style per variant, one heading
  style; the nested card in Get the app, uneven radii (2 to 22 px), glass versus flat cards, and mixed font
  sizes were removed. Settings details are label/value rows.
- Touch targets are at least 40 px on phones; inputs are 16 px on phones (no iOS zoom).
- Studies: inline styles replaced by classes; row details wrap on small screens instead of being hidden.
