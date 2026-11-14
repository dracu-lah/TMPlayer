# Seeing crashes and stutter (S3)

Where bugs show up, what each source sees, and what it costs. Short on purpose.

## The sources

| Source | Who it covers | What it sees | Cost |
| --- | --- | --- | --- |
| Play Console, Android vitals | Installs from Google Play only (Play is set aside, see [play.md](play.md)) | Crash and ANR rates by device, Android version and app version, with stack traces; no SDK, no consent card | Free |
| Sentry | Viewers who said Yes ([crash-consent.md](crash-consent.md)) on any install (GitHub, F-Droid, desktop) | Each crash, ANR, native crash and handled error, cut to the allowed fields (`CrashReportScrubber`, `CrashContext`); stutter reports (`StutterReport`) | Free plan, 5,000 events a month |
| Send logs / log upload (L5, another task) | One viewer, when they press it | That device's recent log, scrubbed, shown before it leaves: the steps before a stutter or a wrong title, which a crash report never holds | Free, per send |

- **Android vitals** is the only one that needs no consent card and covers everybody on Play. The
  bad behaviour thresholds are a user-perceived crash rate of 1.09% and ANR rate of 0.47% of daily
  users; above them Play shows the app less. Check it weekly once Play ships.
- **Sentry vs the log upload:** Sentry answers "what crashed, on which chipset, how often", for
  everyone who opted in, automatically. The log upload answers "what happened on this one device",
  only when that viewer sends it. A stutter without a crash is seen by Sentry only as a
  `StutterReport` (format, decoder, measured speed); its story is in the log.

## Weekly: top stutter reports by chipset

Every stutter event has the logger `tmplayer.stutter` and, since S3, a `chipset` tag beside
`decoder`, `decoder_type`, `codec`, `bit_depth`, `symptom`, `source` and `buffered_ahead_ms`.

1. Sentry, Issues, search `logger:tmplayer.stutter`, last 7 days, sort by events.
2. On each issue, Tags: `chipset`, `decoder`, `symptom`, `source`. A chipset that is high on several
   issues is the PR1 candidate list (known-bad devices).
3. With Discover on the plan, one table instead: query `logger:tmplayer.stutter`, columns
   `tags[chipset]`, `tags[decoder]`, `tags[symptom]`, `count()`, last 7 days, sorted by count.
   The same through the API:
   `GET /api/0/organizations/<org>/events/?query=logger:tmplayer.stutter&field=tags[chipset]&field=tags[symptom]&field=count()&sort=-count&statsPeriod=7d`.
4. Read `source` and `buffered_ahead_ms` first: `remote` with little buffered is a slow line, not a
   slow decoder (the 2026-11 reports had hardware decoders and no drops).
5. Crashes by chipset: search `chipset:<value>` on all issues, or the `chipset` tag on one crash.

## Quota math (`CrashLimits`, `StutterLimits`)

- Free plan: 5,000 events a month for all devices together; stutter events count like errors.
- One device at most: 10 reports a day of any kind, each crash once a day (`CrashLimits.MAX_PER_DAY`),
  so 300 a month in a crash loop. About 16 devices crashing on every start all month fill the plan.
- Stutter: one per kind of video (codec, bit depth, size bucket) per device per day
  (`StutterLimits`); in practice one to three kinds a device.
- A normal month is far lower: 1,000 opted-in devices sending 5 events each is the whole plan.
- When usage passes about 60% by mid month (Sentry, Stats): turn on or tighten spike protection,
  lower `MAX_PER_DAY`, or drop handled errors (`CrashReports.handled`) before paying for a plan.
  Re-check this after S1 ships, since the first-run card should raise the number who say Yes.
