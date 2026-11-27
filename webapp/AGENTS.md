# webapp/

The web player (web.tmplayer.org): SolidJS, pnpm, Vite. iPhone first, then desktop and TV.

- Commands (from `webapp/`): `pnpm check` (types), `pnpm test:unit` (all node tests, seconds),
  `pnpm e2e` (Playwright, without `@slow`), `pnpm e2e:full`. The first run needs `pnpm rules` (JDK 21,
  builds the Kotlin rules from `core/` into `src/rules`).
- Layout: `src/` the page (`screens/`, `player/`, `stream/`, `sync/`, `tg/`, `ui/`, `store.ts` and what
  it re-exports), `tmdb/` the poster proxy worker, `shared/` code both the page and that worker import (never put it under `tmdb/`: every `/tmdb/` URL is a proxy call), `functions/` the Cloudflare Pages routes, `tv/` the
  TV build, `e2e/` Playwright, `libav/` the decoder build.
- Reads from `core/`: `src/rules` (generated), `vite.config.ts` (i18n json), `skipTable.ts` and
  `formatline.ts` (mirror Kotlin). Change both sides together.
- `functions/` and `tmdb/` are bundled by the deploy job from a sparse checkout (`functions`, `tmdb`, `shared`): an import from any
  other folder must be added to that job in `.github/workflows/webapp.yml`.
- A new e2e test that counts real time is tagged `@slow`. Screenshots go into `design/` only with
  `WRITE_SHOTS=1`.
- Deploy is manual only: see docs/CI.md.
