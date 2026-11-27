# workers/

Two Cloudflare Workers: `download-mirror` (serves release files) and `log-inbox` (receives user logs).

- Publishing a worker is the user's step (or the manual workflow, see docs/CI.md). Print the exact
  command instead of running it.
- Secrets are set once with the wrangler secret command; never in files or logs.
- `log-inbox` has tests: `pnpm -C workers/log-inbox test`.
