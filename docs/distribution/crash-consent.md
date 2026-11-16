# Crash reports: consent, and the opt-out switch that is not on

Not legal advice. Check any change of basis with whoever you trust for GDPR.

## What ships (S1, 2026-11-15)

- Crash reports stay **consent**: off until the viewer says yes. Nothing starts Sentry before that.
- Nothing asks in the setup after sign in (R3: that setup is one question, which chats have your videos) and
  there is no card on the chat list: the one-time "Help fix crashes?" card (`CrashAskCard`, `CrashAskHost`) was
  removed. The question is asked in one place: the "Send crash reports" row in Settings (Privacy and lock), which
  says what is sent, and after a crash (the per-crash question). `CrashAsk` (core) keeps only
  `OPT_OUT_DEFAULT_ON`; `crash_ask_done` and any stored answer stay readable (`SettingsStore.crashReportsAsked`).
- The answer is Settings' "Send crash reports" switch; the per-crash question stays for anyone at No.

## The alternative: on by default with an opt-out (NOT enabled)

`CrashAsk.OPT_OUT_DEFAULT_ON` (core, `pureMain/.../data/CrashAsk.kt`) is `false`. It is the default
`SettingsStore.crashReports` falls back to when no answer is stored. Setting it to `true` alone is
**not** enough and must not be done alone. Everything that would have to change with it:

1. **Privacy page** (`site/src/pages/privacy/index.astro`): the legal basis moves from consent to
   legitimate interest, with the balancing reasons, the easy opt-out named, and a dated entry under
   Changes. The section title "Crash reporting, only with your consent" and the processors line
   ("only if you opt in") change too.
2. **First-run notice**: the card stops being a question and says reports are on, with Turn off
   as the first control, before any report can leave. `CrashAsk.decide` stays the gate; the copy
   (`setup.crash_ask_*`), `setup.crash_body` and `settings.crash_reports_off` change.
3. **Play Data safety**: "Crash logs" and "Diagnostics" become collected by default (still optional
   for the user, not shared, encrypted in transit). See [play.md](play.md), "Data safety after S1".
4. **F-Droid**: default tracking is an anti-feature there. The F-Droid build (`-PtmFdroid=true`)
   must keep the default **off**, so the constant becomes a build flag (a `BuildConfig` field read by
   the settings default), not a constant.
5. **Store text and docs**: `amazon.md`, `telegram-apps.md`, the fastlane descriptions and the site
   copy say "Crash reports are sent only if you agree": all of them change.
6. **Backups and sign out**: unchanged (the answer stays per device), but a sign out that clears
   settings would turn reports back on, so the privacy page must say so.
7. **Tests**: `CrashAskTest` asserts the constant is `false`; flipping it fails that test on purpose.
