# TMPlayer log inbox

The receiving end of the app's "Send to developer" button (reliability plan L5). The app shows what
a report holds and its size, asks first, and only then POSTs it here. The report is stored privately
for 30 days under a short code such as `TMP-7K3Q`, which the person says in the group or in an issue.

**Nothing here is deployed. The maintainer deploys it** (wrangler is a user-only action in this
repository, see the root `AGENTS.md`): no script, CI job or agent runs `wrangler` for this worker.
Until it is deployed and its address is put in the build, the app's button stays hidden.

## What it does

- `POST /v1/report`, `Content-Type: text/plain` (UTF-8), at most 2 MB (checked from the header and
  again on the bytes that arrive). Anything else is refused: 404, 405, 413, 415, 400.
- The body must start with `TMPlayer logs`, the app's own header, and be valid UTF-8.
- Rate limits: 6 a minute per address and 60 a minute for everyone together (429 with `Retry-After`).
  The address is hashed before it reaches the limiter, never stored and never logged.
- Stores `reports/TMP-XXXX.txt` in a private R2 bucket with a conditional put, so a code is never
  reused. The code uses an alphabet without 0, 1, I, L or O.
- Answers `201 {"code":"TMP-7K3Q","keepDays":30}`.
- Keeps nothing but the report: no address, no user agent, no headers, no metadata. Workers Logs is
  off (`observability` in `wrangler.jsonc`), so the platform does not record requests either.
- 30 days: a daily cron deletes older reports (`sweep` in `src/inbox.ts`) and the bucket's lifecycle
  rule below does the same, so a failed cron does not keep anything longer.
- There is no read route. Reports are read from the Cloudflare dashboard or with
  `wrangler r2 object get`.

What the report holds is decided by the app and listed on the privacy page
(`site/src/pages/privacy/index.astro`): the scrubbed app log and logcat, the last playback sessions,
the earlier run's crash, device facts and the decoders the device lists. Names, ids, phone numbers
and file names are taken out on the device before it is sent.

## Title reports ("Wrong title?", plan N1)

`POST /v1/titles`, `Content-Type: text/plain`, at most 2 KB, the same rate limits. The body is the
app's JSON for one pair: `{"v":1,"name":"<file name>","title":"<the title the viewer chose>","chat":"<only if they ticked it>","hash":"..."}`.
The viewer saw exactly that pair, and ticked the box, before the app sent it.

- Scrubbed again here (phone numbers, e-mail addresses, t.me links, @usernames), capped, and the
  hash is worked out here (`src/titles.ts`, the same rules as `TitleReport` in core), never taken
  from the sender.
- Stored as `titles/<hash>.txt`: one line `name TAB title`, plus `# chat: ...` only when the viewer
  ticked it. That is the format `scripts/names/ingest.mjs` reads: `wrangler r2 object get` a few
  and point the script at the folder. The same pair twice (any case or spacing) is one object.
- Answers `201 {"code":"NAME-7K3Q","duplicate":false,"keepDays":30}`, or `200` with `duplicate:true`.
  The code is derived from the hash, so the app shows it too and a viewer can read it out (a TV).
- Nothing else is kept: no address, user agent or header. 30 days, by the daily cron (`sweep`
  covers `reports/` and `titles/`) and by a second lifecycle rule (step 2 below).

## Feedback ("Report a problem", "Request a feature", plan HX64)

`POST /v1/feedback`, `Content-Type: text/plain`, at most 16 KB, the same rate limits. The body is
the app's JSON for one message, which the viewer saw word for word before pressing Send:
`{"v":1,"id":"<32 hex>","kind":"problem","message":"...","contact":"...","logs":"TMP-7K3Q","app":{"version":"...","platform":"...","device":"...","language":"..."}}`.

- Needed: `v` 1, `id` (32 lower case hex digits, drawn once per draft), `kind` (`problem` or
  `feature`) and `message` (1 to 4,000 characters once cleaned). `contact` is there only when the
  viewer typed a way to reply, `logs` only when they ticked "Add my logs" (the code of the log
  report the app sent to `/v1/report` just before). A `logs` value that is not a TMP code is dropped.
- Control characters go, one line fields stay one line (so a field cannot forge a line of the
  file), every field is capped, unknown fields are dropped. The message is kept as written: one
  over the cap is refused (400 `message_too_long`), never cut.
- Stored as `feedback/FB-XXXX-XXXX-<id>.txt`, a short header (kind, version, platform, device, language,
  logs code, reply contact) and then the message. The code is worked out here from a SHA-256 of
  the whole id (eight letters, so the sender cannot choose it or collide with another), so the same draft
  sent twice (a double press, a retry after a lost connection, the app killed part way) is one
  file: the second answers `200` with `duplicate:true`.
- Answers `201 {"code":"FB-7K3Q-M4XP","duplicate":false,"keepDays":30}`.
- Nothing else is kept: no address, user agent or header. 30 days, by the daily cron (`sweep`
  covers `feedback/` too) and by a third lifecycle rule (step 2 below).
- To read them: `npx wrangler r2 object get tmplayer-log-inbox/feedback/<key> --remote`, or the
  bucket's page in the dashboard (a prefix of `feedback/FB-7K3Q-M4XP` finds the one a viewer quotes).

## Deploying (the maintainer, once)

From this folder, with a Cloudflare login that may create R2 buckets. The deploy names
`--config wrangler.jsonc`: the repository root has a `wrangler.jsonc` of its own (worker `layer`)
that wins otherwise, as it does for the download mirror.

```sh
# 1. The private bucket (no public access, no custom domain)
npx wrangler r2 bucket create tmplayer-log-inbox

# 2. The 30 day lifecycle rule, as a second guard beside the daily cron
npx wrangler r2 bucket lifecycle add tmplayer-log-inbox expire-30d reports/ --expire-days 30
npx wrangler r2 bucket lifecycle add tmplayer-log-inbox expire-titles-30d titles/ --expire-days 30
npx wrangler r2 bucket lifecycle add tmplayer-log-inbox expire-feedback-30d feedback/ --expire-days 30

# 3. The worker
npx wrangler deploy --config wrangler.jsonc
```

Then build the app with the worker's address (see below), for example
`https://tmplayer-log-inbox.<account>.workers.dev/v1/report`.

Check afterwards: a `curl -X POST -H 'Content-Type: text/plain' --data 'TMPlayer logs' <address>`
answers a code; the same without the header answers 400; a 3 MB body answers 413; the object is in
the bucket and no request appears in the worker's logs.

## The app's side

`LOG_UPLOAD_URL` is a build value (`app/build.gradle.kts`): a line in
`local.properties`, or the `LOG_UPLOAD_URL` environment variable.
Empty (the default, and what forks and CI build with) hides "Send to developer". The desktop reads the
same two names for its own build (`desktop/build.gradle.kts`). The title reports go to the same host:
`LogUpload.titlesUrl` turns `https://host/v1/report` into `https://host/v1/titles`, and an empty
address hides "Wrong title?" and the question after a rename too. Feedback goes to the same host
as well (`LogUpload.feedbackUrl`, `/v1/feedback`); a build with no address keeps only the GitHub
and e-mail routes of "Report a problem".

## Tests

Pure rules, no Cloudflare and nothing to install (Node 22.6 or newer):

```sh
npm test
```
