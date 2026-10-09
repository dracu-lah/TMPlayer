# TODO

State on 2026-10-10. Everything is pushed. The full plan is `docs/PLAN.md`.

## Done in this session (for the release notes)

- Episodes list in the player (phone, TV, desktop), with autoplay, previous and next, and mark watched.
- Labels that were cut short in German, Russian and Malayalam fit; the support prompts in all 20 languages.
- Light theme: button labels (Open Saved Messages, Retry, onboarding Next and more) were invisible, now fixed.
- Sidebar rows have a gap on TV, phone and desktop; the TV rail keeps the 48 dp pitch so it still fits.
- TV Home: See all and its count sit on the row heading, aligned with the header; Down never stops on it.
- First sign in card: Home text scrolls clear of it, and it is one row on a short screen.
- Removed as half done or as chores: Skip intro, the per show next-up order, the Series / All files
  setting, the videos hidden note, dead settings and about 50 unused strings.
- Site: honest about the unsigned Windows installer, download links survive the GitHub API limit, a 404
  page, 1.24 features, the Keep Android Open bar on phones, new screenshots and demo videos.
- Release workflow attaches `TMPlayer.apk`; `tmplayer.org/apk` redirects to it (works from the next release).

## Release

- [ ] Cut the release. The user approved one on 2026-10-09 despite the cadence guard (1.24.0 was
  2026-10-06); it is now a later day, so confirm again first. Release notes go in the annotated tag.
- [ ] After the release: the Telegram post, the site's update feed, the AUR package, and check that
  `tmplayer.org/apk` downloads.

## Testing

- Emulators only, never the real TV stick. Both AVDs (`tmplayer_phone_api36`, `tmplayer_tv_api36`) are signed
  in to the user's Telegram in the debug build; keep it with `adb install -r` of debug builds.
- [x] Desktop, 2026-10-10. AppImage built from main with the real keys: self test passes, opens signed in
  on Home, TDLib reaches Ready, exits clean. MSI from CI on main: installs, launches on Windows 11 in
  the light theme with readable onboarding buttons, uninstalls clean; CI checked the 1.24.0 upgrade.
  Not covered: sign in and playback on Windows (CI builds carry no API keys), clicking through the
  AppImage (no input tool on this machine).

## On hold

- [ ] F-Droid: needs a decision on the Telegram API key. Nothing about F-Droid in the app, site or README.
- [ ] `/signing` says the SignPath application is pending; update it when it is approved.
