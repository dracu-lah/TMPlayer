# TMPlayer master plan

One file for everything still to do. It merges the 2.0 roadmap (`docs/superpowers/specs/2026-10-05-roadmap.md`,
removed in the commit that added this file), the listing plan artifact
(https://claude.ai/artifact/7vQ2jWqry627JP3GU6AMfQ) and the untracked `DISTRIBUTION_PLANS.md` notes. Written
2026-10-06 against `124523d` (1.22.1).

## How to use this file

**You (the user):** tick a box in the Status board when something is done, or tell Claude "CP07 done" or
"U05 done". After every checkpoint, run `/clear` and start the next session with:

> continue the plan

**Claude, at the start of a fresh session:**
1. Read only this "How to use" section, the Status board and the Standing rules (`sed -n '1,/^## Claude checkpoints/p' docs/PLAN.md`).
2. Pick the first unticked CP whose "Needs" are met, or the one the user named.
3. Read only that CP's section (`grep -n '^### CP' docs/PLAN.md`, then read that range) and the Design reference
   section it points to. Do not read the rest.
4. Do the work. At the STOP line: build and test as the CP says, commit, tick the box here with the commit hash,
   push, and report in at most five lines. Then wait. Do not start the next CP in the same session.
5. If a CP turns out bigger than one session, split it (CP07a, CP07b) in this file and stop at the split.

## Status board

Tick `[x]` and add the commit or date. `U` items are yours, `CP` items are Claude's. `[-]` is set aside.

**Order (user, 2026-10-06):** every code checkpoint first (CP24, CP26, CP17, CP13, CP18, then CP11 and CP25), releases held until that is done, then the outside tasks.

**Phase A. Compliance before any more listings (no release needed to commit; ships with the next release)**
- [x] CP01 Crash reports and privacy page match each other (c6b3e22)
- [x] CP02 Wording: "unofficial" everywhere, site titles, release notes text, legal Terms (ae52f5a)
- [x] CP03 Third-party notices complete, a real desktop corresponding source script, notices inside every package (97a0f3f)
- [x] CP04 Respect Telegram "restrict saving content" and self-destructing media (572287c, not yet tested on a protected channel)
- [x] CP05 About screen on phone, TV and desktop (49b4c6f)
- [x] U01 Two Sentry settings (2026-10-06: IP storage off, default scrubbers on; DSN host `.ingest.de.sentry.io`, EU, privacy page updated)
- [ ] U02 Player logo's source (label settled 2026-10-06: GPL-3.0-or-later everywhere, already true in README, winget, AUR, metainfo and the MSI; libraries keep their own licences)
- [x] U03 Budget decision: $0 only (2026-10-06). Phase G dropped, no Certum
- [x] U03a No macOS (2026-10-06): not built, tested, packaged or promised anywhere; the CI job, the DMG target and the icns are gone
- [ ] U04 (optional) Commit email `hello@tmplayer.org`
- [ ] U05 (optional) DMARC record

**Phase B. The "Player" release (earliest 2026-10-13, cadence guard)**
- [x] CP06 Subtitle and audio delay, subtitle style (905d009, desktop verified; phone and TV not yet tested on a device)
- [x] CP07a Volume boost, sleep timer and "still watching?" on Android, TV next-up card (cf88a64, TV card and menu checked on the emulator; phone and real audio not yet tested on a device)
- [x] CP07b Volume boost, sleep timer and "still watching?" on desktop (83b0789, menu and card checked in the dev player; the bundled FFmpeg has no dynaudnorm, so the boost is gain only for now)
- [x] CP08 Resume rules, desktop player extras (b47cddc, rules and saved tracks, speed and delays unit tested on both stores; help sheet, A-B loop, chapters and a real screenshot checked in the dev player; resume restore on Android not yet tested on a device)
- [x] CP09 Empty and error states with actions, recent searches (7b4656e, every state checked on the phone and TV emulator fixtures and the desktop render test; real slow Telegram and search recording not yet tested on a device)
- [x] CP10 Remove after watching, free space, TV focus and labels (6d4d36f, toggle, free space, focus scale and 48/27 margins checked on the phone and TV emulator fixtures and the desktop render test; real deletion after a watched download not yet tested on a device)
- [x] CP10a Sidebar: version beside the logo, compact accordion groups (phone, TV, desktop) (b5d6468, draft, awaiting user review; TV rail folded and open, D-pad walk, phone drawer and desktop light and dark checked on the emulator fixtures and the render test. The TV rail has no icons only mode, so none was added)
- [x] CP10b One theme on every platform: Android TV modal borders, surfaces and the rest match phone and desktop (f7fd2bf, draft, awaiting user review; shared Floating and Focus tokens, every dialog, sheet and menu with the same hairline, fill and 60 per cent dim; audit and before and after shots of the eight screens on the TV and phone emulators and the desktop render test, light and dark)
- [x] CP10c Support the project: sponsor button and QR code on every platform, a gentle reminder, site section (9c53d0e, entry, QR codes and forced card checked on the phone and TV emulator fixtures and the desktop render test, every code decoded with zbarimg; site section at 390 and 1440 in headless Chromium; the real 14 day and 10 play trigger not yet seen on a device)
- [x] CP10d TV player: drop the on-screen play, back 5 s and forward 10 s buttons the remote already covers (ab2f4b9, row, paused cue, jump HUD, menu and D-pad walk checked on the TV emulator fixture; real playback keys not yet tested on a device)
- [ ] CP11 Release prep and device verification
- [ ] CP12 Release "Player" (only when the user says so) and post-release checks
- [ ] U06 Check the 1.22.1 post on @tmplayerapp went out

**Phase C. Distribution (each submission only on the user's go-ahead)**
- [x] CP13 winget manifests for the new release (c1c994f, 1.24.0 MSI downloaded, SHA-256 matches the release digest and latest.json, ProductCode read from its Property table, all three files validate against the 1.12.0 schemas; nothing submitted, next is U13)
- [-] CP14 Windows signing in release.yml (set aside 2026-10-06: installers stay unsigned)
- [ ] CP15 Android verification token in the APK (needs U08)
- [ ] CP16 winget auto-update job (needs the first winget PR merged and U14)
- [x] CP17 F-Droid MR: get the pipeline green (2026-10-09, fork pipeline 2930107122 on 1bc08d44 green in all 9 jobs incl. build and check apk, recipe on 1.24.0; but the MR was closed by linsui on 2026-10-06 for not following the template, so reopening it or opening a new one is the user's call, see CP17)
- [x] CP18 Obtainium badge on README and download page (93c8a38, official badge trimmed losslessly to 564x168, shown at 161x48 under Android phone in all 21 languages with the translated alt, and in the README; download page checked at 390x844, 844x390 and 1440 in headless Chromium, English and Arabic)
- [ ] U07 Android developer account and identity check
- [ ] U08 Register `com.tmplayer` and the signing key, send Claude the token
- [x] U09 Pick the Windows signing route: stay unsigned (2026-10-06)
- [-] U10 SignPath Foundation: send them references (set aside with CP14)
- [-] U11 Certum: buy and validate (dropped, U03 is $0)
- [-] U12 Signing secrets in the GitHub `release` environment (set aside with CP14)
- [ ] U13 wingetcreate on a Windows PC, submit, sign the CLA
- [ ] U14 `WINGET_CREATE_GITHUB_TOKEN` secret
- [ ] U15 Awesome lists: watch the 7 open PRs, file the Android TV FOSS issue
- [ ] U16 Flathub PR #10513: answer or let it go
- [ ] U17 AlternativeTo
- [ ] U18 Uptodown (after CP05)
- [ ] U19 XDA thread (written by you)
- [ ] U20 Search Console and Bing
- [ ] U21 Downloader code and Amazon developer account

**Phase D. The "Languages" release**
- [x] CP19 P1 i18n infrastructure (f7ba39d, catalog, generated `L`, Translator, ICU subset, CLDR plurals for all 17 languages, formatter, resolution, `LocalStrings` and en-XA covered by the :core tests; TV promo home unchanged on the emulator. API: add a key to en.json, read `LocalStrings.current.key` in Compose and `L.key` elsewhere)
- [x] CP20 P2 shared onboarding and the desktop promo fixture (728ccef, tour pages checked on the phone and TV emulator fixtures, the desktop render test and en-XA; gating unit tested. Site wiring for the desktop shots is done but left uncommitted, see the report; the real first run on a device not yet seen)
- [x] CP21 P3 extract phone and TV text (7d0b42a, 783 new keys over :app and :ui, formatter switch, notifications, track picker and accessibility labels; English compared with the phone and TV emulator fixtures, en-XA shows only :core text, brand names and demo data; 1 leftover by the literal scan, the "TMPlayer" brand)
- [x] CP22 P4 extract desktop and core text (d05ff55, 611 new keys over :desktop and :core, including the :core labels phone and TV showed in English; English compared and en-XA checked on browse, Settings, the player menu, the "?" sheet, the update popup and the sign out dialog in the desktop render test; `--self-test` reads every catalog; 0 leftovers by `scripts/i18n-leftovers.py`, protocol strings, brand names and TDLib error text are allowlisted with reasons; no tray exists on the desktop, so none was extracted)
- [x] CP23 P5 language setting, what's new, feedback, localized metadata (94d8d4c, language resolution, the "Now in" card rule, lastSeenVersion and the issue and mailto URLs unit tested against a test-only `de` catalog; Settings row, picker, a live switch to en-XA and to Arabic, the card, What's new, Report a problem with its QR decoded, and right to left including the player overlay checked on the phone and TV emulator fixtures and the desktop render test; desktop scripts render from system fonts, so the package grew 0 bytes and no font notice was needed; `WhatsNew.VERSION` and the `whatsnew.highlight_*` keys move with each release; the PWA manifest goes to CP26 with the site; the star prompt was left out; the real first run of a new version and Android's own language page not yet seen on a device)
- [ ] CP24 P6 translations (go-ahead given 2026-10-06, 16 languages). All 16 translated by Claude directly, then Persian (fa), Traditional Chinese (zh-TW), Polish (pl) and Bengali (bn) added the same day, 21 languages in all (app catalog 1696 strings, site 940) on 2026-10-06 after the three-language cut; machine quality, no native review yet. Still open: review pass (Telegram and Fire OS menu names, Spanish "tú"), review request on issue #2, desktop fonts for CJK, Devanagari and Malayalam, TV overflow check for de and ru. Desktop fonts: bundle subset Noto fonts (user, 2026-10-09). New strings from CP40 to CP43: Claude translates them directly into all 20 locales, no langsync (user, 2026-10-09)
- [ ] CP25 P7 verification, then release "Languages" on request
- [x] CP26 P14b site localization and the remaining site pages (ac034f6 and ef9a156 had already built it: data-i18n, 20 languages under site/<lang>/, hreflang with x-default, og:locale, sitemap alternates, header picker, suggestion bar without redirect, the CI --check, /translate/ and the three guides; 93c8a38 closes the last gap, two code-only links that left empty strings falling back to English. The "Now in" line and the macOS promise stay out on purpose (259ff14, 0d1bdf6). /, /es/, /download/, /ar/download/ and /fire-tv/ checked at 390x844, 844x390 and 1440 in headless Chromium)
- [ ] U22 Review hi and ml; ask the issue #2 author to review es

**Phase E. The "Browse" release**
- [x] CP27 P9 series view (5fb2c07, MediaName and grouping unit tested on real name shapes; series tiles, season tabs, episode list, phone sheet, TV page with a D-pad walk and the Series and All files switch checked on the phone and TV emulator fixtures, desktop dropdown on the render test, light and dark; real chats and caption-only uploads not yet seen on a device)
- [x] CP28 P10 Home rows (1c9a085, row building unit tested for order, the limit of 10, dedupe and empty favourites; Home checked on the phone and TV emulator fixtures with a D-pad walk across and down the rows and on the desktop render test, light and dark; on a 1 GB TV emulator profile a D-pad scroll of a non-debuggable promo build had 5.1 per cent janky frames, p90 30 ms, against 6.0 per cent for the chat grid; real Telegram rows and Recently added not yet seen on a device)
- [x] CP29 P10 detail panel and search across all chats (c777290, action list, file name facts, scope and result splitting unit tested, the search view model with a fake source; panel opened by long press on the phone, OK hold and the info key on the TV, right click and the menu key on the desktop, in the grid, Home rows and search results, light and dark; TV D-pad walk reaches the facts, stays in the pane, Back returns focus to the tile; all-chats search over the promo fixture's fake searchMessages on phone, TV and desktop. Series tiles open the series view rather than a panel. Real searchMessages results and a protected channel not yet seen on a device)
- [x] CP30 P11 Fire TV, trickplay, Watch Next, then release "Browse" on request (a7ed256, release not done, on request. Fire OS detection, remote quirks, voice fallback, the trickplay range and memory rules and the Watch Next entry builder unit tested, Fire OS by faked manufacturer, model and feature flags since it cannot be emulated; trickplay while scrubbing in and past the downloaded range, both new settings and the Play next row on the Android TV launcher, opened from there into the player, checked on the TV emulator with the promo fixture; on tmplayer_tv_1gb trickplay is off and Settings says why; the Fire TV keyboard hint and the missing Watch Next switch checked with Fire OS faked in the promo build. Android only, desktop trickplay not built. Amazon pack in docs/distribution/amazon.md. Real Fire TV remote, Alexa mic and real partial-file thumbnails not yet tested on a device)
- [ ] U23 Amazon Appstore listing submission (after CP30)

**Phase F. The "Online" release**
- [x] U24 Rotate the OpenSubtitles key (2026-10-06: new key in the `OPENSUBTITLES_API_KEY` repo secret and local.properties; TMDB_API_KEY was already a repo secret since 2026-08-07)
- [x] CP31 P12 OpenSubtitles (a0c195b, hash and name search, download, quota from 429, 406 and remaining=0, revoked key, expired token, rate limits, cache hits, TTL and purge, SubDL fallback unit tested over a fake HTTP layer; one live search by name with the real key found 50 results, no sign in and no download; Settings section, sign in prompt, "Search online" in the picker, results and every disabled state checked on the phone and TV emulator fixtures and the desktop render test, a canned download loaded on the TV fixture. A real sign in, a real download and the hash of a real stream not yet tested on a device)
- [x] CP32 P13 TMDB, TVmaze, AniList, then release "Online" on request (3cab226, release not done, on request. Matching (film by title and year, series with season and episode, anime via AniList, `{tmdb-123}`), the strict no match, a 429 pause, a refused key, cache hits, TTL, purge and the English fallback unit tested over a fake HTTP layer; live calls found Big Buck Bunny on TMDB with the real key (overview fell back to English from ml), Breaking Bad S01E02 on TVmaze and Frieren on AniList, and a bad key was refused; detail panel with poster and overview, series page with its poster, Home with posters, the Settings section with the key override and its states, and About with the TMDB logo and notice checked on the phone and TV emulator fixtures and the desktop render test. Off by default, turned on from Settings or the detail panel. Real chats, real poster loads on a 1 GB stick and a viewer's own key not yet tested on a device)
- [ ] U25 MSone reply (issue #5). If yes, add CP33 for an MSone provider

**Phase G. Google Play (dropped 2026-10-06, U03 is $0; kept for reference)**
- [-] U26 Line up 15 testers (start early, it gates production)
- [-] CP34 Store flavor without the updater, targetSdk 36, AAB in the release
- [-] CP35 Reviewer demo mode, TV ad links as QR, TV banner with the name, Report and sensitive media
- [-] CP36 Play listing images (outside the repo)
- [-] U27 Create the app in Play Console and fill in the forms
- [-] U28 Closed test for 14 days
- [-] U29 Production, then opt in to Android TV

**Phase H. Player declutter and support asks (planned 2026-10-06, design: docs/design/2026-10-06-player-declutter-and-support-asks-plan.md)**
- [x] CP37 Android player declutter (693edbc, phone and TV; phone row is subtitles, audio, speed, rotate, with Lock, PiP and Picture shape in the overflow, Start over is an offer on the resume, TV row is subtitles, audio, speed, More, with Picture shape in More; checked on the phone and TV emulators with the promo build, D-pad walk with no dead focus, unit tests green. Still pending for a real device: double tap and pinch gestures, the first-run hint, the resume offer over a real resume, and the other locales still carry the unused back_seconds, forward_seconds and playback_speed_now keys (kept in en.json because CatalogTest fails otherwise; drop them when the locales are synced). Left as is: the two subtitle Look renderers, since touch and D-pad each use one. CP40 still owes the TV More episode lines)
- [x] CP38 Desktop player declutter: bar, main menu, shortcut sheet (e07f7cb; the NextUpCard and Countdown sheet were already exclusive, no cut; Picture shape moved into Playback options; unused en.json strings kept until langsync prunes the locales)
- [x] CP39 Support asks v2: positive moments, three asks at most, thank-you state, tmplayer.org/support (45d6037, ladder, counters, triggers and the site page done; ladder unit tested, phone rungs 1 to 3 on the emulator, desktop render test, site at 390 and 1440; TV and the phone thank-you state not shot on an emulator, none was free; the site page is English only, like legal)
- [ ] CP40 Episodes button and modal in the player (phone, TV, desktop), after CP37 and CP38
- [ ] CP43 Sidebar with fewer entries: fold the eight chat sections into two entries with filter chips (before CP41)
- [ ] CP41 Setting to hide Telegram's default chat groups everywhere, with warning prompts, plus a sweep for settings that lack one
- [ ] CP42 Minimal onboarding: six tour pages down to two, one first-sign-in card, deferred hints
- [ ] CP44 Device pass for CP37 to CP43: install on the phone and the Mi TV stick, hunt wasted space and anything broken

## Standing rules

- **Stop at every CP.** Commit, tick, push, report, wait for `/clear`.
- **Never without the user saying so in that session:** cut a release or tag, submit to a
  store, registry or list, upload to an existing GitHub release, or post anywhere.
- **Releases:** run the cadence guard first (`gh release list --limit 10`). 1.20.0, 1.21.0, 1.22.0 and 1.22.1
  went out on 2026-10-04 and 2026-10-05, so nothing before 2026-10-13. Write the release notes in the annotated
  tag message: the update popup and the Telegram post both read it.
- **Push freely** to main.
- **No em or en dashes** anywhere (CLAUDE.md has the grep). Screenshots are WebP q88; `site/og.png` and
  `site/icon-512.png` stay PNG.
- **Commits:** no Claude co-author trailer.
- **Strings:** until CP19 lands, new UI text is plain English literals; CP21 and CP22 extract them. From CP19 on,
  every new string goes into `en.json`.
- **Bulk edits** use grep, sed, perl and jq (Design reference R9), not hand edits.
- **Every CP's verification baseline:** `./gradlew build`, the `:core` jvmTest suite, and the dash grep.
  TV tests use the TV emulator, desktop tests `./gradlew :desktop:runPlayerDev --args="--file clip.mkv"`, site
  screenshots use headless Chromium.
- **Screenshots and device tests (user, 2026-10-06):** emulators first. Phone UI screenshots and checks run on the
  phone emulator (`tmplayer_phone_api36`) with the promo flavor, TV on `tmplayer_tv_api36` with the promo flavor
  (fake data, no login). Use the POCO (adb `d3f7da23`) only for what an emulator cannot show: real Telegram data
  and login state, hardware decoding and codecs, audio output (volume boost, Dolby), real storage and battery.
  On the POCO install only the release-signed APK with `adb install -r` (keeps the Telegram login) or the promo
  build (`com.tmplayer.promo`, installs alongside); never the plain debug build, which would wipe the login.
  Each CP's "Done when" names which screenshots it needs; keep them outside the repo unless they replace a
  README or site screenshot (then WebP q88 per CLAUDE.md).

## Claude checkpoints

### CP01 Crash reports and privacy page match each other
**Why:** the privacy page, store text and in-app toggle say a crash report is only a stack trace, app version,
Android version and device model, with no identifier. Commit `2d73eeb` only nulled `device.id` and
`device.name` and redacted some paths. The SDK still sends timezone, battery, memory, storage, screen and
connection details, and the OS and app contexts carry more.
**Do:**
- `app/src/main/java/com/tmplayer/data/CrashReports.kt` `setBeforeSend`: keep only the exception and stack
  trace, `release`, OS name and version, and device model and manufacturer. Drop or null every other device
  field (timezone, locale, battery, charging, memory, storage, screen, orientation, connection type, boot
  time, simulator flag), app context extras (start time, permissions, in-foreground), `event.serverName`,
  `event.tags` that are not ours, `event.contexts` other than os, device and app, and the per-install
  `installationId` wherever it lands. Remove the stray trailing whitespace lines.
- Redact file paths in exception messages and in stack frame `vars`, and anything that looks like a Telegram
  file name (any token ending in a video extension).
- A unit test that builds a full `SentryEvent`, runs the callback and asserts the allowed field list exactly.
- `site/privacy/index.html` (2d73eeb touched it; check every point and finish what is missing):
  - covers phones, Android TV, Windows and Linux: where the session, cache and downloads live, that
    uninstalling leaves them, that titles show in the system media controls (MediaSession, MPRIS, SMTC);
  - a "Delete your data" section: sign-out keeps downloads unless the box is ticked, the desktop folders,
    and telegram.org/deactivate for the Telegram account;
  - GDPR basics: who runs it, consent as the basis for crash reports, retention, processors (Sentry, Vercel,
    GitHub), how to withdraw consent, the right to complain, a link to Telegram's privacy policy;
  - sponsored message views and clicks are reported to Telegram; the update check reveals the version and IP;
  - the effective date and a short change list;
  - the EU storage claim matches U01's answer (if U01 is not done, leave a TODO comment and say so).
**Done when:** the test passes; the privacy page lists exactly what the callback keeps; headless Chromium shot
of `/privacy/` at 390 and 1280 wide.
**STOP**

### CP02 Wording: "unofficial" everywhere, site titles, release notes text, legal Terms
**Why:** Telegram API terms 2.2 want the intro to say the app uses the Telegram API. Only the Android
`IntroScreen.kt` says it today; desktop has nothing.
**Do:**
- Desktop sign-in or first screen: the same line as Android, "TMPlayer is an unofficial app that uses the
  Telegram API. It is not made by Telegram." Use one shared constant or the same wording on both.
- Site `<title>`s and `og:title`: "Unofficial Telegram video player" instead of "Telegram video player" on
  every page (`site/*.html`, `site/*/index.html`).
- `.github/workflows/release.yml` line ~372 says "Installed copies update themselves". Make it "Installed copies
  offer each update", the same as SECURITY.md. Same text in `scripts/post-release-telegram.py`.
- The FAQ: "nothing passes through anything of mine" becomes "no media or account data passes through".
- The analytics note: "a first-party proxy to Vercel Web Analytics, processed by Vercel".
- `/legal/`: the sponsored messages caveat, and a short Terms section with the GPL no-warranty clause and a
  link to Telegram's terms (check what 2d73eeb already added).
- grep for "No ads" and "no ads" across site, README and fastlane; every one carries the sponsored messages
  caveat.
**Done when:** grep shows no bare "No ads" and no "update themselves"; Chromium shots of `/`, `/legal/`, `/faq/`.
**STOP**

### CP03 Third-party notices, desktop corresponding source, notices inside packages
**Why:** this is the one real licence blocker. The desktop builds bundle mediamp's mpv runtime (libmpv, GPL on
Linux; FFmpeg 8, LGPL; libplacebo, libass, dav1d; on Windows glib, fribidi, libiconv, libintl and the GCC
runtime) without source. Commit `2d73eeb` added a placeholder: `scripts/build_mediamp_source_archive.sh` only
echoes, its versions are wrong (mpv 0.38, FFmpeg 7.0), `THIRD_PARTY_NOTICES.md` credits the wrong author
("Khang Le"; mediamp is `org.openani.mediamp`, Open Ani) and links
`releases/download/v1.21.0/mediamp-corresponding-source-0.5.0.tar.gz`, which does not exist.
**Needs:** U02 for the licence label (default GPL-3.0-or-later if the user has not answered) and the logo source.
**Do:**
- Find mediamp 0.5.0's runtime build scripts (the open-ani/mediamp repo at the 0.5.0 tag) and read the exact
  library versions they build for Windows and Linux.
- Rewrite the script (rename to `scripts/build-mediamp-source.sh`, kebab case like the others): download the
  mediamp tag tarball plus the exact source tarball of every bundled library, verify SHA-256 sums pinned in the
  script, pack `mediamp-corresponding-source-0.5.0.tar.gz` with a README listing each library, version,
  licence and upstream URL.
- release.yml: a guard like the nextlib one that fails the release if `mediamp` in `libs.versions.toml` moves
  away from the version the archive covers, and a "mediamp source" link in the notes next to the nextlib one.
  Uploading the archive itself needs the user's go-ahead (CP12).
- `THIRD_PARTY_NOTICES.md`: fix the mediamp entry; a desktop section with every library above; Sentry (MIT),
  OpenSSL 1.1.1w inside Android TDLib with its acknowledgement, Compose Multiplatform, Skiko, JNA, dbus-java,
  FileKit, the bundled Temurin 21 runtime (GPL-2.0 with Classpath Exception); "Solar Icons by 480 Design,
  CC BY 4.0, modified"; the player logo per U02.
- One licence label everywhere (README, winget, AUR, metainfo, MSI): GPL-3.0-or-later unless U02 says otherwise.
- Add a GPLv3 section 7 additional permission for OpenSSL to our own code (a short `LICENSE` addendum and a
  README line), since the FSF treats OpenSSL 1.x as GPL-incompatible.
- Ship `LICENSE` and `THIRD_PARTY_NOTICES.md` inside the MSI, AppImage, tarball and AUR package
  (`desktop/build.gradle.kts` app resources plus `desktop/packaging/`), and show them from About (CP05).
**Done when:** the script runs end to end in the scratchpad and produces the archive (do not upload);
`./gradlew :desktop:packageDistributionForCurrentOS` (or the Linux tarball task) contains both files.
**STOP**

### CP04 Respect "restrict saving content" and self-destructing media
**Why:** Telegram API terms 1.4, and `/legal/` already promises the app "does not bypass Telegram
permissions". Today nothing checks it, and desktop writes downloads to the visible Downloads folder.
**Do:**
- In `:core`, read TDLib `message.can_be_saved` and `chat.has_protected_content`, and treat messages with a
  `self_destruct_type` as not playable.
- Protected: streaming still works (it is what the official clients do), but Download, "Keep", share and
  "open in another player" are hidden on phone, TV and desktop, and an existing download of such a file is not
  moved to the downloads folder (it stays in the cache and is evicted as cache).
- Skip self-destructing media in the grid, with the hidden count in the existing "N hidden" line.
- Tests in `:core` for the filter.
**Done when:** a protected test channel shows no Download action on phone and desktop. If no protected channel
is available, say so and test with a forced flag.
**Result (572287c):** built and unit tested; no phone was attached and no protected channel tried, so check one
in CP11. Known gaps: rows rebuilt from stored records (Continue watching, Watched, Downloads) do not carry the
flag, so their actions ask `Td.maySave` when pressed, and the move into Downloads checks it again. Files already
in Downloads keep Show in folder and Open with. `./gradlew build` fails on lint errors that were already there
(UnsafeOptInUsageError in PlayerControls and App, NewApi in themes.xml); run it with `-x lint` until fixed.
**STOP**

### CP05 About screen on phone, TV and desktop
**Why:** GPL-3 section 5(d) wants appropriate legal notices in the UI; Uptodown's terms want contact details in
the app; the TV has no browser, so the Privacy and Lawful use links do nothing there today.
**Do:**
- Settings, then About, built once in `:ui` shared code where possible: version and build, "GPL-3.0-or-later,
  no warranty", links to source, `THIRD_PARTY_NOTICES`, the corresponding source (nextlib and mediamp),
  Privacy, Lawful use, hello@tmplayer.org, the Telegram channel and group, Discussions, Sponsors and Buy Me a
  Coffee ("voluntary, unlocks nothing").
- On TV every link is a QR code (reuse the QR component in `:ui`), including the existing Privacy and Lawful
  use rows.
- The notices file is also viewable offline inside the app (bundled asset).
**Done when:** screenshots of About on phone, TV emulator and desktop.
**STOP**

### CP06 Subtitle and audio delay, subtitle style
**Design:** R4. **Do:**
- Delay in ±0.1 s steps in the track menu on every platform, remembered per file in `ResumeRecord`.
  Android: a `SubtitleView` timing shim for text, an `AudioProcessor` offset for audio. Desktop: mpv
  `sub-delay` and `audio-delay`.
- Subtitle style: size S, M, L, XL; background box on or off; position; a live preview in Settings and the
  player menu. Replaces the fixed `0.065` in `PlayerActivity.kt` and `sub-font-size` 46 in
  `MpvPlaybackEngine.kt`. Stored in `SettingsStore` so all platforms share the key names.
- TV menus are D-pad reachable (`PlayerTvMenu.kt`).
**Done when:** a clip with an offset subtitle can be brought into sync on phone, TV emulator and desktop.
**STOP**

### CP07 Volume boost, sleep timer and "still watching?", TV next-up card
**Design:** R4. **Do:**
- Volume boost or night mode: a gain plus compressor `AudioProcessor` on Android (next to the PCM buffer
  floor work), mpv `volume-max=150` and `af=dynaudnorm` on desktop. A toggle in the player menu.
- Sleep timer in the player menu, not on screen (keeps the 1.16.0 decision). "Still watching?" after N
  autoplayed episodes (default 3) or 2 h with no input: a full-screen card that pauses playback and stops
  downloads.
- Port `buildNextUpCard` to the TV branch with D-pad focus on "Play now".

Split in two (2026-10-06): CP07a is everything above on Android (phone and TV) plus the shared rules
in `:core` (`StillWatching`, `SleepTimer`, the `volume_boost` setting). CP07b is the desktop half:
`volume-max=150` and `af=dynaudnorm` behind the same Volume boost toggle, the sleep timer in the
player menu, and "Still watching?" from the same `StillWatching` rules, pausing mpv and the queue.
**STOP**

### CP08 Resume rules, desktop player extras
**Design:** R4. **Do:**
- Read `ResumeRecord.kt` first and only fill gaps: no resume point in the first 3 min or the last 8 %;
  watched at 90 %; save tracks, speed and delays with the position every 10 s as well as on pause and quit.
- Desktop: frame step (`.` and `,`), screenshot (`s`, saved to Pictures/TMPlayer), A-B repeat, chapter
  navigation, and the `?` help sheet updated. No desktop frame-rate matching.
**STOP**

### CP09 Empty and error states with actions, recent searches
**Design:** R5. **Do:**
- Through `StateScaffold` and `UiState`: "N videos hidden by the size limits [Show them]", "Telegram is slow to
  answer [Retry]", and a first-load tip.
- Recent searches: the last 5 as chips, on every platform, stored in `SettingsStore`, with a clear action.
**STOP**

### CP10 Remove after watching, free space, TV focus and labels
**Design:** R5. **Do:**
- Downloads: a "Remove after watching" toggle (deletes a download when it is marked watched), and "x GB free"
  on the download action.
- TV: 1.05 focus scale on tiles, check the 48/27 dp overscan margins, content descriptions for the actionable
  icons among the 54 null-labelled ones (decorative ones stay null).
**STOP**

### CP10a Sidebar: version beside the logo, compact accordion groups
**Why:** the user asked on 2026-10-06 for the app version next to the icon in the sidebar, and for a more
compact sidebar where related entries fold into groups like an accordion.
**Where:** TV rail `NavRail`, `RailHeading`, `RailItem` in `app/.../ui/browse/BrowseScreen.kt`; phone drawer
`DrawerBrand`, `DrawerDestinations`, `DrawerSeparator`, `DrawerFooter` in `app/.../ui/browse/TouchNav.kt`;
desktop `Sidebar` and `Rail` in `desktop/.../ui/Shell.kt`; the tab list `BrowseTab` in
`ui/src/shared/kotlin/com/tmplayer/ui/browse/BrowseSections.kt`.
**Do:**
- Version: the brand row shows the logo, "TMPlayer" and the version (`BuildConfig.VERSION_NAME` on Android,
  the desktop app version) in a smaller muted style. When an update is available, the existing amber "Update"
  entry stays where it is; the version text does not turn into a button.
- Groups (proposal, show the user a screenshot before polishing): **Watch** (Continue, Watched, Favourites,
  Downloads), **Chats** (Recent, Unread, Saved, Channels, Groups, People, All chats, Archived), **Folders** (the
  account's Telegram folders, only when there are any), then Settings and Update pinned at the bottom.
  Define the grouping once in `:ui` shared code, next to `BrowseTab`, so all three platforms use the same model.
- Accordion: each group heading folds and unfolds with a chevron; the group holding the current destination
  is always open; open or closed state is remembered in `SettingsStore`; Watch starts open, the rest closed.
- Tighter rows: smaller vertical padding and a smaller icon to text gap, keeping TV targets at least 48 dp
  high and text at least 14 sp (v1 design §4, `docs/design/2026-08-07-v1-design.md`).
- TV: a group heading is focusable; OK toggles it; D-pad down from an open heading enters the group; the
  collapsed rail (icons only) shows group icons and expands on focus as today. No focus traps.
- Phone drawer: the same groups; the account footer stays.
- Desktop: the same groups; the collapsed icon rail keeps working.
**Done when:** screenshots of the rail on the TV emulator (collapsed and expanded), the phone drawer, and the
desktop sidebar in light and dark, with one group closed; D-pad walk on TV reaches every entry.
**STOP**

### CP10b One theme on every platform (Android TV looks different)
**Why:** the user asked on 2026-10-06: the Android TV build does not look like phone and desktop. Modals and
sheets lack the borders the other platforms have, and surfaces, corners and accents drift.
**Where:** `app/.../ui/theme/Theme.kt`, the TV components in `app/.../ui/components/` (`TvMenu.kt`,
`TvConfirm.kt`, `TmButton.kt`, `TvSearchField.kt`, `StateScaffold.kt`), the desktop theme in `desktop/`, and
shared code in `ui/src/shared`.
**Do:**
- First an audit: screenshots of the same screens (browse grid, a modal, a menu, a confirm dialog, Settings,
  player menu, sign-in) on the TV emulator, the POCO and desktop, light and dark, side by side. List every
  difference: colours, surface tones, border width and colour, corner radius, elevation, typography, focus
  and pressed states. Show the user the list before changing anything.
- Pull the shared values (colours, border, radius, spacing) into one token set in `:ui` shared code, so the
  phone `MaterialTheme`, the TV `androidx.tv.material3` theme and desktop all read from it.
- Modals, dialogs, menus and sheets on TV get the same border and radius as phone and desktop. The TV focus
  ring stays (it is how the D-pad shows where you are), but it is drawn from the same tokens.
**Done when:** the audit screens again on all three, light and dark, with no unexplained differences.
**STOP**

### CP10c Support the project: sponsor button, QR code and a gentle reminder
**Why:** the user asked on 2026-10-06: the apps have no way to sponsor. GitHub Sponsors
(`github.com/sponsors/dracu-lah`) and Buy Me a Coffee (`buymeacoffee.com/nevil.dev`) are only in the README
and `.github/FUNDING.yml`.
**Do:**
- A "Support TMPlayer" entry in Settings and in the About screen from CP05 on phone, TV and desktop. Phone and
  desktop open the links. TV shows a QR code for each instead of opening a browser (reuse
  `ui/src/shared/kotlin/com/tmplayer/ui/auth/QrCode.kt`). The QR is useful on phone and desktop too, as a
  "scan on another device" option.
- A friendly reminder, shown rarely: a small dismissible card (not a blocking dialog, never during playback)
  after real use, for example 14 days since install and 10 plays, then not again for 60 days. "Not now" and
  "Don't ask again" both work. Counters and the opt-out live in `SettingsStore`.
- The site: a support section on the home page and the download page with the same two links.
- Keep CP35 in mind: the future `play` flavor drops donation links, so put the entry and the reminder behind a
  single switch that flavor can turn off.
**Done when:** screenshots of the entry and the QR on TV, phone and desktop, and the reminder forced on with a
debug flag.
**STOP**

### CP10d TV player: drop the redundant centre buttons
**Why:** the user asked on 2026-10-06 whether the TV player's play, back 5 s and forward 10 s buttons are
needed, since the remote already does all three, and said to remove them if they are not.
**Where:** `app/src/main/res/layout/player_controls.xml` (`controls_center`: `center_previous`,
`center_rewind`, `center_play_pause`, `center_forward`, `center_next`; and the bottom row `controls_buttons`)
and `PlayerControls.kt`. Desktop's layout for comparison: `desktop/.../player/PlayerScreen.kt`.
**Do:**
- TV has no touch gestures, but the D-pad already does the job: left and right on the bare picture jump
  back 5 s and forward 10 s, OK opens the row, OK on the focused timebar plays and pauses, and the remote's
  play key works too. So on TV, hide the big centre cluster. Keep it on the phone, where it is the tap target.
- The user then said (2026-10-06) that on TV the remote plus the feedback icons are enough: no transport
  buttons at all. So on TV also drop play and pause, back 5 s and forward 10 s from the bottom row. What stays:
  the timebar and the non transport buttons (tracks, subtitles, speed and the rest of the menu).
- Make sure every remote action shows its icon: the existing side HUD for the 5 s and 10 s jumps, and a brief
  centre play or pause glyph when OK or the play key toggles playback. Paused keeps showing a pause cue.
- Previous and next episode: the remote's media keys, plus entries in the player menu if they are not there.
- Check D-pad focus: OK still opens the row on the timebar, Down reaches the buttons, no dead focus spots.
  Update INSTALL.md's remote table if it lists the removed buttons.
**Done when:** TV emulator screenshots of the row while playing and paused, the play and pause cue, the jump
HUD, and a D-pad walk across the row.
**STOP**

### CP11 Release prep and device verification
**Do:**
- Run the whole Phase A and B set on the POCO, the TV emulator and desktop. Fix what turns up.
- `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, the draft tag message (highlights in plain
  English), and the version bump, all committed but not tagged.
- If CP14 is done, check the signing step's dry path.
- Report the proposed version number and wait.
**STOP**

### CP12 Release "Player" and post-release checks
**Needs:** the user saying "release" in that session, and the cadence guard passing (2026-10-13 or later).
**Do:** tag with the annotated message, push, watch the run. Then, each only with the user's OK: upload
`mediamp-corresponding-source-0.5.0.tar.gz` (built by `scripts/build-mediamp-source.sh`) to that release, then set `TAG`
in release.yml's mediamp step to that tag so later releases link there; check the Telegram post, `site/latest.json` and the
changelog commit; `gh attestation verify` one file; bump the AUR PKGBUILD (from this release on its
`package()` also installs the notices, which older tarballs lack).
**STOP**

### CP13 winget manifests for the new release
**Do:** in `docs/distribution/winget/`: `ManifestVersion` and the three schema headers to 1.12.0; add
`PrivacyUrl: https://tmplayer.org/privacy/`; point the URL, `InstallerSha256` and `ProductCode` at the new
release's MSI (download it and hash it; the 1.22.0 hash in `DISTRIBUTION_PLANS.md` is stale); fix `winget.md`,
which says `wingetcreate new` takes `--submit`. Then U13.
**STOP**

### CP14 Windows signing in release.yml
**Needs:** U09 and either U10 (SignPath accepted) or U11 (Certum issued), plus U12.
**Do:** in the Windows job: build the app image, sign `TMPlayer.exe` with an RFC 3161 timestamp, build the MSI
from that image, sign the MSI, `signtool verify /pa` both. The release stops if the secrets are missing, rather
than shipping unsigned. Signing happens before the attestation and the feed SHA-256. SignPath route: their
GitHub Action plus the manual approval in SignPath. Certum route: SimplySign with the TOTP seed from a secret.
Update `/security/` and `/signing/`, which say the installer is unsigned. The DLLs inside JARs stay unsigned.
**STOP**

### CP15 Android verification token in the APK
**Needs:** U08's token file. **Do:** put it in `app/src/main/assets/` exactly as given, build a signed release
APK through CI (a `workflow_dispatch` build or the next release), tell the user which file to upload.
**STOP**

### CP16 winget auto-update job
**Needs:** the first winget PR merged and U14. **Do:** a job after `publish` on `windows-latest` that runs
`wingetcreate update dracu-lah.TMPlayer --version <v> --urls <msi url> --submit`, reading the token from the
`WINGET_CREATE_GITHUB_TOKEN` env var (never on the command line), `continue-on-error: true`, skipped when the
secret is unset.
**STOP**

### CP17 F-Droid MR: get the pipeline green
**State:** https://gitlab.com/fdroid/fdroiddata/-/merge_requests/51285, opened 2026-10-05, no reviewer
comments yet. Pipeline 2913691203 (commit 88842c2d) failed in 5 jobs: `fdroid build` (Gradle BUILD FAILED),
`checkupdates` ("Couldn't find any version information": the recipe needs an `UpdateCheckData` or a parsable
versionName, since ours is computed in `build.gradle.kts`), `schema validation`, `fdroid lint` and
`fdroid rewritemeta` (the recipe's format). Read the jobs with
`glab api projects/dracu-lah%2Ffdroiddata/jobs/<id>/trace`. Recipe on branch `add-com-tmplayer` of `dracu-lah/fdroiddata`, `metadata/com.tmplayer.yml`. Known
problems: `tdl-coroutines 15.0.0` needs compileSdk 37 (`platforms;android-37.0`); installing it under `sudo:`
leaves root-only files, so it moved to `prebuild: sdkmanager "platforms;android-37.0"`; with `subdir: app`
Gradle does not read the root `gradle.properties`, so `android.suppressUnsupportedCompileSdk=37.0` goes in
`gradleprops`.
**Do:** read the failed job log with `glab`, fix the recipe, push to the MR branch (pushing to our own fork is
fine, commenting on the MR needs the user's OK). Expect reviewer questions on prebuilt native libraries
(TDLib from tdl-coroutines, nextlib FFmpeg) and Sentry (Tracking anti-feature even when opt-in). Report those
honestly rather than hiding them. If the prebuilt TDLib is a hard no for F-Droid, say so and recommend closing.
**Result (2026-10-09):** fork pipeline 2930107122 (commit 1bc08d44 on `add-com-tmplayer`) is green in all nine
jobs, `fdroid build` and `check apk` included. What fixed it:
- The real build error: AGP installed build-tools 35 in the middle of the build, and an SDK install mid-run
  leaves that run unable to resolve `android-37` (reproduced locally with F-Droid's own `sdkmanager` from PyPI).
  `prebuild: sdkmanager "platforms;android-37.0" "build-tools;35.0.0"` puts both in place first. No sudo, no
  copies, no hand-written `package.xml`, no `gradleprops` (Gradle does read the root `gradle.properties`).
- `output: build/outputs/apk/release/app-universal-release-unsigned.apk`, since the release build makes
  three APKs (two ABIs plus universal) with one versionCode.
- `UpdateCheckData: app/build.gradle.kts|Code.*\?:\s*(\d+)|.|Name.*\?:\s*"([\d.]+)"` with
  `UpdateCheckMode: Tags ^v[0-9.]+$` and `AutoUpdateMode: Version` (the old `Version v%v` fails the schema).
- Category `Video` does not exist; now `Multimedia` and `Online Media Player`.
- Recipe moved to 1.24.0, `commit` is the full hash `4341b1a7...` as the template asks.

**Still open, for the user:**
- The MR itself is **closed**: linsui closed it on 2026-10-06, "Merge Request template is not followed". The
  pipeline above ran on the fork branch, not on the MR. Reopening it (or a new MR) needs the "App inclusion"
  template filled in as the description: what the app does with screenshots, why it is needed, and the
  checklist ticked. Nothing was posted or reopened.
- Reviewer decisions the green pipeline does not settle: the APK ships prebuilt `libtdjsonjava.so` (TDLib from the
  tdl-coroutines AAR) and prebuilt FFmpeg (`libavcodec`, `libavutil`, `libswresample`, `libswscale`,
  `libmedia3ext` from nextlib). The scanner does not look inside Maven AARs, but F-Droid's inclusion policy
  wants native code built from source, so expect a request to build TDLib and FFmpeg from source (srclibs) or a
  refusal. Sentry is opt-in, which may still draw a `Tracking` anti-feature. Reproducible builds are not set up
  (F-Droid would sign with its own key), and the template asks for a reason if not.
**STOP**

### CP18 Obtainium badge
**Do:** download the official badge from Obtainium's repo, trim its transparent edge losslessly, host it under
`site/`, add it to the README and the download page at 161×48.
**STOP**

### CP19 P1 i18n infrastructure
**Design:** R1. **Do:** catalog `core/src/commonMain/resources/i18n/en.json`; Gradle codegen to `object L`;
`Translator` with per-key English fallback, the ICU subset, CLDR plurals for en, es, pt, hi, ml, the locale
formatter, track-language names; resolution order; `LocalStrings` in `ui/src/shared`; a debug-only `en-XA`
pseudo-locale. Tests: resolution, plurals, placeholders, fallback, catalog consistency (keys, placeholders,
ICU validity, no dashes).
**Done when:** build green, tests pass, nothing visible changes.
**STOP**

### CP20 P2 shared onboarding and the desktop promo fixture
**Design:** R2. **Do:** move the pager and page model from `app/.../ui/onboarding/OverviewScreen.kt` and
`app/.../ui/auth/IntroScreen.kt` into `ui/src/shared/kotlin/.../onboarding/`; pages Language, What TMPlayer is,
Sign in, Chats, Videos; wording per form factor; `OnboardingImage` actuals; gating via `overviewSeen`; desktop
`Shell.kt` shows the tour before `SignInScreen`; Settings "Show the walkthrough again"; a desktop promo fixture
that captures light and dark shots of sign-in, chats, grid and player. Those shots also become the missing
desktop screenshots in the README and on the site (WebP q88).
**Done when:** tour screenshots on phone, TV and desktop.
**STOP**

### CP21 P3 extract phone and TV text
**Design:** R9. Over `:app` and `:ui`: notifications and channels, `TrackPickerFragment`, accessibility labels,
the formatter switch. **Done when:** English unchanged, `en-XA` shows no leftovers. If it runs long, split by
module and stop at the split.
**STOP**

### CP22 P4 extract desktop and core text
`:desktop` (tray, Windows notifications and SMTC, update popup, keyboard help) and `:core` user-facing errors, plus the :core labels the phone and TV still show in English (ThemeChoice, PlaybackSpeed, SleepTimer, SyncDelays, SubtitleStyle, TouchPrefs, DiskInfo.freeLabel, WatchedWhen, ChatKind, UpdateWords);
`--self-test` loads every catalog.
**STOP**

### CP23 P5 language setting, what's new, feedback, localized metadata
**Design:** R1, R3. **Do:** the Settings language row everywhere, Android `locales_config.xml` and
`LocaleManager`, the auto-switch and the one-time "Now in Español" card; what's new after an update
(`lastSeenVersion`, translatable highlights, Settings "What's new" and "Changelog"); "Report a problem"
(prefilled GitHub issue URL, QR on TV, mailto fallback); localize the About from CP05; Linux `.desktop`
`Name[xx]`, AppStream, PWA manifest; a pseudo-locale overflow pass; Noto Devanagari and Malayalam on desktop
(with a notices entry if fonts are bundled). Optional: the one-time "Star TMPlayer on GitHub" prompt after
10 plays.
**STOP**

### CP24 P6 translations
**Go-ahead given 2026-10-06:** the user said to translate into the most common languages with langsync now and
review while using the app. **Do:** empty `{}` files for es-419, pt-BR, fr, de, it, ru, uk, tr, id, vi, ar, zh-CN, ja, ko, hi, ml
(check how langsync maps `zh-CN` and `es-419` before the run); `langsync --dry-run`, then `langsync`; a review pass (Spanish "tú", leftovers); CONTRIBUTING "Add a translation"
and an issue template; a review request on issue #2. Every language stays visible in the picker, reviewed or not (user, 2026-10-06).
**STOP**

### CP25 P7 verification and the "Languages" release
Device matrix in en, es and en-XA: onboarding, Settings language change without restart, `es-CL` system locale
auto-switch and card, player menus with the D-pad, desktop with `LANG=es_CL.UTF-8`. Fix what turns up. Release
only on request, after the cadence guard.
**STOP**

### CP26 P14b site localization and the remaining site pages
**Design:** R8. `data-i18n` on the pages and `app.js`; `scripts/build-site-i18n.py` renders `site/<lang>/` with
`lang`, `hreflang` and `x-default`, `og:locale`, sitemap entries, a header language picker, a suggestion bar, no
auto-redirect; CI fails on stale output. Also `/translate/`, the three SEO guides, "Now in Español, Português,
हिन्दी, മലയാളം" on the home page. `langsync -c langsync.site.json` for the same 16 languages (go-ahead given 2026-10-06).
**Done when:** Chromium shots of `/`, `/es/`, `/download/`, `/fire-tv/` at phone and desktop widths; hreflang,
sitemap and JSON-LD validate.
**STOP**

### CP27 P9 series view
**Design:** R5. Extend `MediaName.kt` with `1x02`, `Ep 02`, the anime `- 02` form and the caption as a second
source, with unit tests from real names; group by series key into one tile; season tabs and an episode list
with progress and watched ticks; phone bottom sheet, desktop dropdown; "Series" and "All files" toggle.
**STOP**

### CP28 P10 Home rows
**Design:** R5. A new first destination on every device: Continue, one row per favourite chat, "Recently added
across chats"; lazy rows of 10; rail and drawer keep their tabs. Smooth on a 1 GB emulator profile.
**STOP**

### CP29 P10 detail panel and search across all chats
**Design:** R5. Detail panel on OK-hold, the info key, right-click or long press (side pane on TV and desktop,
sheet on phone). Search: a "Videos in all chats" scope through TDLib `searchMessages` with a video filter,
results split into Chats and Videos.
**STOP**

### CP30 P11 Fire TV, trickplay, Watch Next, then the "Browse" release
**Design:** R4, R6. Fire OS detection for remote quirks and the voice-search fallback; trickplay from the
already-downloaded range only, behind a setting, off on 1 GB devices; optional Watch Next behind a setting.
Amazon listing pack (text, screenshots, content rating) in `docs/distribution/`. Release on request.
**STOP**

### CP31 P12 OpenSubtitles
**Design:** R7. **Needs:** U24.
**STOP**

### CP32 P13 TMDB, TVmaze, AniList, then the "Online" release
**Design:** R7. Includes the detail panel poster and the attribution in About. Release on request.
**STOP**

### CP34 Store flavor, targetSdk 36, AAB
**Needs:** U03 = pay, and the user confirming Play. Flavors `github` (as today) and `play` (no install
permission, no update check, no GitHub release links, no donation links). targetSdk 36: Android 16 stops sending
Back to apps that target it, so the player's Back handling moves to the predictive back API and gets tested.
release.yml also builds an `.aab`. Version codes stay shared with GitHub builds.
**STOP**

### CP35 Reviewer demo mode and Play policy items
A demo mode in the `play` build with a few openly licensed clips (Big Buck Bunny) so a reviewer can use it
without a Telegram code; sponsored links on TV as a QR code (TV ads must not link out); a TV banner showing the
name "TMPlayer"; a "Report" action for chats; sensitive media hidden by default.
**STOP**

### CP36 Play listing images
Outside the repo (Play takes PNG): icon 512×512 full square; feature graphic 1024×500; 2 to 8 phone shots
cropped to 1080×2160; TV shots 1920×1080; TV banner 1280×720 with the name. Only own or openly licensed video
on screen; no "movies" or "series" wording, no "S02E04" example.
**STOP**

### CP37 Android player declutter
**Design:** sections 2 to 4 of `docs/design/2026-10-06-player-declutter-and-support-asks-plan.md` (decisions D1, D2
default to the recommendations unless you say otherwise). Phone first, then TV.
**Do:** remove phone centre seek buttons, row Lock, PiP and Scale (Scale and Lock and PiP into the overflow),
overflow Speed and Start over; route `gesture_hud` through `PlayerFeedback`; one renderer for the subtitle Look
controls; stale XML comment and `controls_clock` default; TV row down to subtitles, audio, speed, More, with
Scale and PiP in More and no row episode buttons; drop dead TV Lock and Rotate code; first-run hints for double
tap and TV remote keys; update `INSTALL.md` and `docs/FEATURES.md`.
**Done when:** phone and TV emulator (promo flavor) screenshots of the new rows and menus, a D-pad walk with no dead
focus, tests green; real gestures and pinch listed as pending for CP11.
**STOP**

### CP38 Desktop player declutter
**Design:** section 5 of the same document (D3, D4). Runs in parallel with CP37 (disjoint files).
**Do:** remove 10 s buttons, wall clock and the Shape button; the speed pill opens the list; main menu reduced to
Always on top, Playback options, Subtitle style and timing, Telegram items, Help; group and trim the shortcut
sheet; one next-episode prompt instead of card plus countdown sheet.
**Done when:** desktop render test screenshots of the bar, main menu and each new page.
**STOP**

### CP39 Support asks v2
**Design:** section 6 of the same document (D5, D6). Replaces the flat 14 day, 10 play rule of CP10c.
**Do:** completed-watch and watch-time counters in `SettingsStore`; the three-rung ladder in `SupportReminder`
with "Later" and "I already support"; trigger on player close after a finished watch or a finished download,
never in the player; thank-you About state; `tmplayer.org/support/?utm_source=` page and QR targets; debug flag per
rung; one closing support line in the Telegram release post; tests.
**Done when:** unit tests for the ladder, screenshots of each rung and the thank-you state on phone, TV and
desktop, and the site page at 390 and 1440 wide in headless Chromium.
**STOP**

### CP40 Episodes button and modal in the player
**Why:** the user asked for an Episodes button in the empty bottom right of the controls row, opening an episodes
modal with everything about switching episodes (2026-10-06; the original request was never written down, so the
exact feature list is still open, see below).
**Where:** Android `PlayerControls.kt`, `player_controls.xml`, `PlayerActivity.kt` (`findEpisodes`, `playEpisode`,
`stepEpisode`); desktop `PlayerOverlay.kt`. Reuse `SeasonsSection`, `EpisodeRow` and the `SeriesSheet` code from
browse (`ui/src/shared/.../browse/`) so the modal matches the series view.
**Do:**
- An "Episodes" button at the right end of the row (phone, TV) and of the bar (desktop), visible only when the
  file belongs to a series.
- The modal: season tabs, the current episode highlighted and scrolled into view, per row the title, watched
  tick or progress and length. Previous and next at the top. Tapping a row plays it through the same loader,
  resume and watch cache path as the next-episode button.
- TV: D-pad focus starts on the current episode, Back closes the modal first, no dead focus. Desktop: keyboard
  navigable, Esc closes.
- Once it exists, remove the TV More-menu previous and next lines and the TV row episode buttons (CP37 leaves
  them until then). Phone centre previous and next stay.
- Extras (user, 2026-10-09: all four): an autoplay next toggle in the modal (the existing autoplay setting,
  shown here too); mark watched or unwatched from a row (long press on phone, OK hold on TV, right click on
  desktop); a next-up order choice, episode number or upload order in the chat, remembered per series; and Skip
  intro. Telegram files carry no intro markers, so Skip intro is learned: a "Set intro end here" action in the
  modal stores the position per series, and later episodes of that series show a "Skip intro" pill until that
  point passes (Enter or OK on TV, tap on phone, click on desktop). Clearing it lives in the same place.
**Done when:** emulator screenshots of the button and modal on phone and TV (current episode highlighted, a
season switch, a watched tick), the desktop render test, a D-pad walk, and tests green.
**STOP**

### CP41 Hide Telegram's default chat groups, and prompts where they are missing
**Order:** after CP43, so it hides the shorter Chats group (see CP43).
**Why:** the user asked on 2026-10-06 for a Settings option that hides the default Telegram grouping from the
sidebar and from other places, removes favourites that this affects, shows proper alert prompts for features
like it, and adds the same prompts where they are missing.
**Main goal (user, 2026-10-06):** the sidebar shows only the custom groups the viewer made (their Telegram
folders), and none of the app's built-in ones. Everything below serves that: nothing built-in is left over,
chats outside every folder are not listed anywhere except search, and the Watch group (the viewer's own
watching, not a Telegram grouping) stays. Open for the user: should Watch also be optional? Default: no.
**What "default grouping" means (confirmed 2026-10-06):** the user's friend wants the app's fixed groupings
disabled completely, keeping only the custom ones. Default: the Chats group of the sidebar built by
`browseSections` in `ui/src/shared/.../browse/BrowseSections.kt`: Recent, Unread, Saved, Channels, Groups,
People, All and Archived (the `ChatKind` split in `ChatRepository.kt`). Custom: the account's own Telegram
folders (the Folders group), which stay. Watch (Home, Continue, Watched, Favourites, Downloads) stays too. All
eight default sections go together, with no per-section choice. With no folders on the account the sidebar
would hold Watch only, so the toggle's prompt says to make folders in Telegram first, and Home and the empty
states point there instead of at "All".
**Do:**
- A Settings toggle "Hide Telegram's default groups" (name to settle), off by default, stored in `SettingsStore`.
  When on, `browseSections` and `navGroups` leave the Chats group out on phone drawer, TV rail and desktop side
  bar, and every other surface that lists those sections stops offering them (the section switchers in
  `BrowseScreen`, the Home empty states and hints that point at "All" or "Channels", search scope, and the
  desktop dropdown). With the group gone, chats are reached through Folders, Favourites, search and Home.
- Favourites: chats are favourited by id (`favorite_chats`), so switching the option on must not leave a
  favourite the viewer can no longer reach. Work out which favourited chats would become unreachable (in no
  Telegram folder), list the count in the prompt and, on confirm, remove them from favourites in one write.
  Switching it off does not bring them back, and the prompt says so.
- Prompts: a confirm dialog before the switch takes effect, in the app's existing dialogs (`TmAlertDialog` on
  touch, `TvConfirm` with focus on Cancel on TV, the desktop equivalent), with a plain explanation, the
  favourites count, how to undo it, and a destructive Confirm label. Turning it off needs no prompt.
- Sweep for missing prompts: audit Settings and the app for actions that remove data or are hard to undo and
  have no confirm, for example sign out, clear cache or downloads, clear all favourites, mark all watched or
  reset the watched list, change storage location, turn off crash reports, remove a downloaded file, language
  change that restarts. Write the list into this section, add a prompt to each one that lacks it, reuse one
  shared confirm component per platform.
- Strings go into `en.json` only; leave other locales and never run langsync.
**Done when:** unit tests for the section filtering and the unreachable favourites calculation, emulator
screenshots on phone and TV of the toggle, the prompt with a favourites count and the shortened sidebar,
the desktop render test, and the sweep list with each added prompt shown once.
**STOP**

### CP42 Minimal onboarding
**Why:** the user asked on 2026-10-06 to check what the onboarding lacks and whether pages can merge so a new user
sees fewer steps. Audit of the shared tour (`ui/src/shared/.../onboarding/Onboarding.kt`, `OnboardingTour.kt`,
`PostersSwitch.kt`, the phone and TV `OnboardingScreen.kt`, desktop `Shell.kt`).
**Today:** six tour pages on every platform (Language, About, Sign in, Chats, Videos, Posters), then the sign in
screens (phone number, code, optional 2FA; QR first on TV and desktop), then the shell. Skip is hidden until
after About (the Telegram API terms line lives there). Three of the six pages (Sign in, Chats, Videos) describe
screens the user is about to see. The Posters page is a notice rather than a choice, because
`PostersDefaultOn` turns online lookups on at tour start, so a skipped tour enables them without the user ever
seeing the explanation.
**Do:**
- Tour of two pages instead of six. Page 1 "Welcome": the three honest lines (unofficial, no server of ours,
  nothing collected), the Telegram API terms line, a compact language row ("English, change", system language
  preselected) instead of a whole page, and a visible "Look up posters and descriptions online" switch with
  one line on what is sent. This replaces the hidden default: the switch is on screen, so choosing is real.
  Page 2 "How it works": one illustrated page saying videos come from your own Telegram chats and channels, to
  forward a video to Saved Messages to get a first one in, folders and favourites in one line, how the sign in
  works (phone number or QR), then Start. The standalone Sign in, Chats and Videos pages go.
- Move the "Help translate" block off page 1 into Settings and About; the language card ("Now in X") stays as
  the second language prompt.
- One contextual card after the first sign in, not a tour page, because it needs the account: when the account
  has Telegram folders, ask "Show everything" or "Only my folders" (the CP41 setting), with the CP41 prompt
  rules. No folders: skip it. Depends on CP41.
- First-run empty state on the chat list and Home: say how to get a first video (forward to Saved Messages),
  with the existing strings made visible instead of buried in an empty state.
- Hints deferred to first use, shown once and dismissible: double tap to jump and the swipe gestures on the
  first phone playback, the OK, hold-for-favourite and remote keys on TV (shares work with CP37).
- Kept as they are, on purpose: notification permission on the first download, crash reports off by default and
  only in Settings, storage location only in Settings, the support card and the update, language and what's new
  cards held until the shell. No storage permission exists to ask for.
- Tour state: bump the "overview seen" flag so existing users are not shown the new tour, and keep the
  Telegram terms acknowledgement working for a fresh install that taps Start.
- Strings go into `en.json` only; leave other locales and never run langsync. The shorter tour makes the old
  keys unused, so remove them from `en.json` and note that translations of the new keys wait for the user.
**Done when:** emulator screenshots on phone and TV of both tour pages, the first sign in card (with and without
folders) and the first-run empty state, the desktop render test of the tour, a check that a skipped or finished
tour leaves posters exactly as the switch says, unit tests for the tour state, and the old `Onboarding` page
count updated in docs and the site's first-run description if it lists steps.
**STOP**

### CP43 Sidebar with fewer entries
**Why:** the user asked on 2026-10-06 whether sidebar sections can be grouped instead of hidden, so there are
fewer menu entries. CP10a already made the groups fold like an accordion; this goes further and removes
entries rather than only tucking them away. Run it before CP41, which then hides a smaller group.
**Today:** `browseSections` lists Home, Continue, Watched, Favourites, Recent, Unread, Saved, Channels, Groups,
People, All, Archived, then the folders, plus Downloads. Six of the eight Chats entries are only filters of the
same chat list (`filterChats` in `BrowseSections.kt`: Recent is a sort, Unread, Channels, Groups and People
filter by `unreadCount` or `ChatKind`).
**Do:**
- Chats group becomes two entries: "Chats" (one list) and "Saved messages" (kept apart because it is the
  video library most people use). Inside "Chats" a row of filter chips: All, Unread, Channels, Groups, People,
  with a sort toggle for Recent, and Archived as a last chip. The chosen chip is remembered in `SettingsStore`.
- Watch group: Continue and Watched become one "History" entry with two tabs (Continue, Watched), since both are
  lists of videos you already played. Home, Favourites and Downloads stay. Net: eleven fixed entries plus
  folders become six.
- Keep the shared model: define the entries and chips once in `:ui` shared code next to `BrowseTab`, so phone
  drawer, TV rail and desktop side bar stay identical. `BrowseSection`, saved screen state, deep links, empty
  states and hints that name a removed tab (All, Channels, Recent) are updated to point at the chip.
- TV: the chip row is D-pad reachable (Up from the list header, Left and Right between chips, OK selects),
  no dead focus, and it keeps the 48 dp target and 14 sp text floor.
- Folders group unchanged. CP41 then hides "Chats" and "Saved messages" (the default grouping) and leaves
  History, Watch and the folders; update its text if the entries differ when it starts.
- CP10a's accordion state and the drawer, rail and desktop layouts stay; remove unused headings only if a
  group ends up with one entry.
- Strings go into `en.json` only; leave other locales and never run langsync.
**Done when:** unit tests for the chip filters and the saved selection, screenshots of the shorter sidebar and
the chip row on phone and TV, the desktop render test, a D-pad walk, and the old tab names gone from docs.
**STOP**

### CP44 Device pass: layout, wasted space, nothing broken
**Why:** the user asked on 2026-10-06 to commit the changes, install them on the phone and on Android TV, and look
for extra spaces that can be used and anything that looks broken. Repeat after each batch of CP37 to CP43 lands;
it also takes the "pending for CP11" device checks those CPs list.
**Rules (from the standing rules and memory):** done from the main session, not a subagent (subagents are
refused installs on real devices). Release-signed APKs only, `adb install -r`, never the debug build on the
phone (same package, other key, wipes the Telegram login). The stick is armeabi-v7a on Android 9 and needs the
release-signed universal APK; it is the `MiTV-AESP0` at `10.27.27.33:5555` over adb. The phone is the POCO and is
shared with the user: ask or check it is connected, set media volume to 0 before any playback test and confirm it
with `cmd media_session volume --stream 3 --get`. Emulators first (`tmplayer_phone_api36`, `tmplayer_tv_api36`,
promo flavor); the devices are for real data, codecs, audio and storage. Verify the installed versionCode with
aapt2 (see the stale versionCode note) before trusting a test.
**Do:**
- Build the release APK(s) from the merged main, install on the phone and on the stick, keep the login.
- Walk every changed screen at 1:1 with screenshots: player controls row and overflow, TV row and More menu,
  support card rungs, sidebar and chips, hide-default-groups setting and its prompt, onboarding, episodes
  modal. Phone in portrait and landscape; TV on the stick's 1080p.
- Look for: unused gaps and padding, controls that are too small or crowded, text clipped or overlapping,
  content that does not use the freed room (for example a shorter sidebar leaving a dead strip, a shorter
  player row leaving an empty band), focus that gets lost on the D-pad, anything that looks broken, then fix
  what is found or list it.
- Record what was verified and what was not in the plan board line.
**First pass, 2026-10-06 (release build 1.23.0-test28, versionCode 12330, merged CP37, CP38, CP39; installed with
`adb install -r`, login kept; phone POCO M2 Pro `d3f7da23`, 1080x2400; stick Mi TV `10.27.27.33`, 1080p):**
- Checked and fine: phone Home, the detail page, the player in portrait and landscape (centre cluster is
  previous, play/pause, next; bottom row subtitles, audio, speed, rotate), the phone overflow, TV Home with the
  folded rail and the version beside the logo, TV detail page, the TV row (subtitles, audio, speed, More plus the
  clock), the TV More menu, TV Settings, About and help, and the TV Support dialog (QR decodes to
  `tmplayer.org/support/?utm_source=settings&utm_medium=app`).
- Findings, open:
  1. Phone landscape, controls up: the subtitle line runs through the timebar and the time labels. This is the
     existing `liftSubtitles` tradeoff (it never climbs into the centre buttons), so little room is left
     sideways. Options: hide the subtitles while the row is up in a short window, or give them a scrim. Not changed.
  2. Phone portrait: the previous and next captions (S01E03, S01E05) sit on the same line as the subtitles and
     collide with them when the controls are up. Same fix as 1.
  3. Phone landscape overflow: 11 entries scroll and cut off at "Load a subtitle file". Merge or drop entries
     (Copy link, Save, Mark watched, Open in another app) or split it into two groups.
  4. Empty right half of the controls row on phone and TV. This is the slot CP40 fills with Episodes.
  5. Home in landscape and on TV: a Continue row with one item fills a third of the width and leaves the rest
     empty. Fine with more items, but worth a wider card or a second row (hint plus Favourites) at this size.
  6. The "Sign out of Telegram" row on TV Settings is the default focus on arrival at the bottom of the list and
     has no visible confirm on a single OK; CP41's prompt sweep covers it.
**Fixes after the first pass (2026-10-06, user: fix all, defaults taken):** 1 and 2: the subtitle cue stands down
(fades) while the controls are up when it cannot climb clear of the row or would reach the centre buttons and
their episode captions; 3: the phone overflow drops Copy link and Picture shape (the pinch is the shortcut), 9
entries left; 4: a lone Continue card is 1.5 times wider, and the empty controls slot stays for CP40's Episodes
button; 5: already covered, Sign out opens a confirm with Cancel focused first, so only the landing focus remains,
which is ordinary D-pad scrolling. Not yet seen on a device.
- Not tested: real gestures and pinch, the resume offer pill and the first-run hint over a real resume (the
  first resume here showed the loader line "Resuming from 13:52", not the offer; check whether the offer shows
  once playback is ready), the TV rungs of the support card (they need the forced debug flag), phone
  thank-you state, Picture shape sheet, and the desktop pass. Media volume on the phone was set to 0 for the
  test and put back to 14.
**Done when:** screenshots of each changed screen on both devices were viewed, findings fixed or listed, and
the pending device checks of CP37 to CP43 are ticked or carried forward by name.
**STOP**

## Your tasks

### U01 Two Sentry settings
1. sentry.io, the TMPlayer project, **Settings → Security & Privacy**.
2. Turn on **Prevent Storing of IP Addresses**, and **Data Scrubber** with **Use Default Scrubbers**.
3. **Client Keys (DSN):** does the host end in `.de.sentry.io`? Tell Claude yes or no (the privacy page
   says "stored in the EU").

### U02 Licence label and logo source
- One label: the README says GPL-3.0, winget, AUR, metainfo and the MSI say GPL-3.0-or-later. Default if you
  say nothing: GPL-3.0-or-later.
- Find the exact SVG Repo page the player logo came from and its licence. Only the source can say whether it
  may be relabelled GPL.

### U03 Budget decision
`DISTRIBUTION_PLANS.md` says strict $0 (no Play fee, no paid certificate). The listing plan recommended Certum
(about EUR 49 a year) and Google Play ($25 once). Pick one:
- **$0:** Windows signing only if SignPath accepts; no Google Play (Phase G dropped). Android developer
  verification (U07) may still need the $25 account; check whether Google's free hobbyist option applies.
- **Pay:** Certum now, SignPath as a free bonus, Google Play later.

### U04 (optional) Commit email
235 commits carry nevilnicks4321@gmail.com. Past ones stay. For future ones:
`git config user.email "hello@tmplayer.org"` (or your GitHub noreply address).

### U05 (optional) DMARC
DNS TXT record `_dmarc`, value `v=DMARC1; p=none; rua=mailto:hello@tmplayer.org`.

### U06 Check the 1.22.1 Telegram post
The 1.22.1 run finished green on 2026-10-05; it was the first real run of the announcement step. Look at
t.me/tmplayerapp. If nothing was posted, tell Claude (the step is `continue-on-error`, so a failure would not
show red).

### U07 Android developer account
Enforced since 2026-09-30 in Brazil, Indonesia, Singapore and Thailand, worldwide in 2027, for GitHub APKs too.
1. play.google.com/console/signup with the Google account that should own TMPlayer.
2. **Personal** account (an organization needs a D-U-N-S number).
3. The one-time $25 fee (see U03), legal name, address, phone, government ID. Your address is not shown
   because the app is free; your name, country and developer email are.
4. Wait a few days for the identity check.

### U08 Register com.tmplayer and the key
1. In the console, **Android developer verification**, register an app distributed outside Play.
2. Package: `com.tmplayer`
3. Certificate SHA-256 (`CN=TMPlayer, OU=dev, O=dracu-lah`):
   `00:FB:F6:E8:BC:F6:08:00:60:E2:65:3B:BD:44:00:46:55:E6:5D:25:DD:46:E6:D3:D2:BC:C8:81:C2:84:26:52`
   (check: `apksigner verify --print-certs TMPlayer-1.22.1-universal.apk`)
4. Copy the token file the console gives you and send it to Claude (CP15). Upload the signed APK it builds.

### U09 Pick the Windows signing route
Azure Artifact Signing is out (individuals in US and Canada only).
- **SignPath Foundation:** free, publisher shows "SignPath Foundation", manual approval per release. Already
  applied (policy page at tmplayer.org/signing). Small projects are often declined.
- **Certum Open Source cloud:** about EUR 49 a year, your real name, needs ID and a utility bill.
- **SSL.com IV with eSigner:** about $309 a year, official GitHub Action.
A certificate puts your name on the installer; the SmartScreen warning still shows until reputation builds.

### U10 SignPath: send them references
On 2026-10-05 Phillip Deng (oss-support@signpath.org) wrote that they could not find enough public references
and asked for articles, forum posts or community mentions before a final decision. Reply from the same inbox
with the evidence list (draft given in the 2026-10-06 session): the two infoek.cz articles, the merged
awesome-kotlin and awesome-compose-multiplatform entries, release download counts (about 480 across 1.16.0 to
1.22.0 on 2026-10-06), 19 stars and 2 forks, issues #2, #3 and #5 opened by three different users, the
Telegram channel. Small projects are often declined even so; then decide on U11 or stay unsigned.
If accepted: create the project in SignPath, tell Claude (CP14).

### U11 Certum (only if paying)
1. shop.certum.eu, "Open Source Code Signing in the Cloud", buy.
2. Activate: your name exactly as on your ID, email, project URL `https://github.com/dracu-lah/TMPlayer`.
3. Identity check, plus a utility bill in your name (ask support before paying if you have none).
4. Install **SimplySign**, register it. **Save the TOTP seed when it shows the QR**; CI needs it and it cannot
   be recovered.
5. Tell Claude when the certificate is issued.

### U12 Signing secrets
Repo **Settings → Environments → release → Environment secrets**. Claude gives the exact names in CP14.
Recommended: add yourself as a **Required reviewer** on that environment.

### U13 winget submission (after CP13)
1. Windows Terminal (not PowerShell ISE): `winget install wingetcreate`, reopen, `wingetcreate token --store`.
   (Token route: a classic token with only `public_repo`; fine-grained tokens do not work.)
2. Copy the three files to `manifests\d\dracu-lah\TMPlayer\<version>`.
3. Optional test: `winget settings --enable LocalManifestFiles` (admin), then
   `winget validate --manifest <folder>` and `winget install --manifest <folder>`.
4. `wingetcreate submit --prtitle "New package: dracu-lah.TMPlayer version <version>" <folder>`
5. Reply to the CLA bot with `@microsoft-github-policy-service agree`. Answer `Needs-Author-Feedback` within
   5 days. "Telegram" may trigger a trademark review (Policy-Test-2.2); the "unofficial" wording is the
   defence. Never replace a release file after submitting.

### U14 winget token secret
After the first winget PR merges: a classic token with `public_repo` as repo secret
`WINGET_CREATE_GITHUB_TOKEN`. Then CP16.

### U15 Awesome lists
Merged: Heapy/awesome-kotlin#1184, mahozad/awesome-compose-multiplatform#5.
Open since 2026-10-05, answer any review comments: ebertti/awesome-telegram#331, kalanakt/awesome-telegram#101,
offa/android-foss#785 (the maintainer wants to give the project more time before merging; if asked about spyware: Sentry is off by default and only starts when the user turns it
on, and after CP01 sends only the stack trace, versions and device model), krzemienski/awesome-video#139,
fmhy/edit#6585, stax76/awesome-mpv#56 (slow list), pcqpcq/open-source-android-apps#496.
Not filed yet: **Awesome Android TV FOSS Apps**, as an issue form (their README link is dead):
github.com/Generator/Awesome-Android-TV-FOSS-Apps/issues/new?template=feature-request.yml
- Title `[App Request]: TMPlayer`; Name TMPlayer; Source https://github.com/dracu-lah/TMPlayer; Category Media
  Player; Website https://tmplayer.org; Remote Support Full; Launcher Icon Yes.
- Description:
  `- **TMPlayer:** Streams the videos in your Telegram chats while they download, with subtitles, audio tracks and episode autoplay. Unofficial Telegram client, needs your own account [[Source](https://github.com/dracu-lah/TMPlayer)] [[Website](https://tmplayer.org)]`

### U16 Flathub PR #10513
The bot closed it because the checklist used `[✓]` instead of `- [x]`. The body was fixed and a reopen was
requested on 2026-10-05; it is still closed, and a reviewer (ostfriese4) replied that the linked video
`demo-phone.mp4` "is not taken on Linux". Reopening needs a real screen recording of the Flatpak build running
on Linux. Flathub needs offline from-source builds of the app, TDLib, mpv
and FFmpeg, which the listing plan judged too heavy. Either wait for a maintainer, or let it go (AppImage,
tarball and AUR cover Linux). Do not open a new PR; the bot says to comment instead.

### U17 AlternativeTo
1. Sign up at alternativeto.net (ideally hello@tmplayer.org), verify the email.
2. User icon, **Suggest new application**. Name `TMPlayer`. Tagline:
   `Watch the videos in your Telegram chats on your TV, phone or computer, while they download.`
3. Website https://tmplayer.org; License Free, Open Source ticked; Platforms Windows, Linux, Android, Android
   Tablet, Android TV, Fire TV (not "AppImage"); Category Video & Movies; Features media player, video player,
   Telegram, streaming, subtitle support, remote control. **Not** "ad-free" and not "no registration".
   Icon `site/icon-512.png`; screenshots `tv-grid.webp`, `tv-chats.webp`, `phone-grid.webp` from
   `site/screenshots/`.
4. Description (no links allowed):
   > TMPlayer is a free and open source video player for the videos in your own Telegram chats and channels. It runs on Android TV, Fire TV, Android phones, Windows and Linux, and starts playing within seconds while the file is still downloading; seeking re-aims the download instead of waiting. Sign in on a TV by scanning a QR code with your phone. Pick a chat and only its playable videos appear, as a poster grid with what you were watching first. It plays MKV, MP4, AVI and TS with embedded subtitles and multiple audio tracks, recognises episode names and moves on to the next episode, and remembers where you stopped. No ads of its own (Telegram's sponsored messages appear in public channels, as Telegram requires), no account except your Telegram one, no server in between.
   >
   > TMPlayer is an unofficial app, not made by or affiliated with Telegram. It uses the Telegram API through TDLib and needs your own Telegram account.
5. Alternatives: Telegram, Kodi, VLC Media Player, optionally Unigram and Telegram X. The $5 priority review
   buys speed, not approval.

### U18 Uptodown (after CP05)
1. en.uptodown.com/developers-console, organization "TMPlayer", hello@tmplayer.org. No ID check.
2. **Apps → Add new app**, Android, the universal APK from the GitHub release.
3. Icon `site/icon-512.png`, name TMPlayer, category Video, website, PEGI 3, author your name.
4. Short description (70 chars): `Play your Telegram videos on TV and phone while they download`
5. Full description: `fastlane/metadata/android/en-US/full_description.txt` plus
   `Source code (GPL-3.0): https://github.com/dracu-lah/TMPlayer`.
6. Final Release, Min SDK 26, changelog from fastlane. Licensing: Free; **Ads: Yes** (sponsored messages);
   licence GPL 3.0, License Text URL `https://github.com/dracu-lah/TMPlayer/blob/main/LICENSE`, Source Code
   URL the repo.

### U19 XDA thread
XDA bans AI-written posts (rule 15): write it yourself; `docs/distribution/telegram-apps.md` is only a fact list.
Register at xdaforums.com, put Sponsors and Buy Me a Coffee in your signature (the only place allowed), read the
rules, search "TMPlayer" first, post once in **Android Apps and Games**. Title:
`[APP][8.0+][Android TV, phone, Windows, Linux] TMPlayer: stream your Telegram videos while they download (free, open source)`.
Include the "unofficial, not affiliated with Telegram" line, live download links, source, the Telegram channel.
For each release, edit the first post.

### U20 Search Console and Bing
The google-site-verification TXT is already on the domain: confirm the property, submit
`https://tmplayer.org/sitemap.xml`, then in Bing Webmaster Tools use "Import from Google Search Console".

### U21 Downloader code and Amazon developer account
Register a Downloader short code for `https://tmplayer.org/download/` at aftvnews.com; tell Claude the code
(it replaces the DOWNLOADER-CODE marker on `/fire-tv/`). Create the Amazon developer account for U23.

### U22 Translation reviews (CP24)
Review the languages you read as you use the app; ask the issue #2 author to review es-419.

### U23 Amazon Appstore listing (after CP30)
Submit using the pack Claude prepares in `docs/distribution/`.

### U24 Rotate the OpenSubtitles key
It was pasted in chat on 2026-10-05. Rotate it on opensubtitles.com and update the `OPENSUBTITLES_API_KEY`
secret before CP31.

### U25 MSone
Permission email sent 2026-10-05. Nothing MSone-related is built (not even a link) until they agree.

### U26 to U29 Google Play (only if paying)
- **U26** Ask the Telegram group for 15 or more people with Android phones or TVs who will stay opted in for 14
  days; collect their Play Gmail addresses.
- **U27** Play Console: app TMPlayer, App, Free. **App signing: upload the existing release key with PEPK before
  any testing release** (cannot change later; keeps GitHub and Play builds interchangeable). Privacy policy
  `https://tmplayer.org/privacy`. App access: the demo mode. Ads: Yes. Content rating honestly (expect Teen or
  Mature). Target audience 18+. Data safety: phone number and user IDs collected, sent to Telegram, required;
  crash logs optional, Sentry as processor; encrypted in transit; deletion by sign-out and Telegram account
  deletion. Foreground service `dataSync` with a short screen video of a download. No donation links.
- **U28** Closed testing track, upload the AAB, add testers, they stay 14 days in a row, then apply for
  production (about 7 days review).
- **U29** Production with a staged rollout (20 %), then **Advanced settings → Form factors → Android TV**, TV
  screenshots and banner, a separate TV review.

## Design reference

### R1 Localization
- Catalog `core/src/commonMain/resources/i18n/<tag>.json`, `en.json` is the source of truth; keys namespaced by
  screen; ICU MessageFormat (`{count, plural, one {# video} other {# videos}}`).
- Translations are written by Claude straight into the catalogs (langsync was removed 2026-10-06: Google's free
  endpoint rate-limited it to nothing, and it cannot write Arabic's six plural forms). Product names stay in
  Latin script, `{placeholders}` and ICU structure are kept, and `:core:jvmTest` checks both.
- Codegen: a Gradle task in `:core` turns `en.json` into `object L` with typed accessors (`L.onboardingSkip`,
  `L.videosHidden(count)`), so a missing key fails the build.
- `Translator`: classpath catalogs, per-key English fallback; ICU subset (`{arg}`, `plural`, `select`) with CLDR
  plural rules for en, es, pt, hi, ml, no ICU4J; a locale-aware formatter for sizes, durations, dates and
  numbers; ISO 639 track names in the UI language (`Locale("de").getDisplayLanguage(active)`).
- Resolution on every device: saved `LANGUAGE` in `SettingsStore` (empty = system), else the system list
  matched by language then region (`es-CL` to `es-419`, `pt-PT` to `pt-BR`), else English. Desktop reads
  `SettingsStore` via `DesktopPlatform.kt`.
- Compose: `LocalStrings` in `ui/src/shared` fed by a `StateFlow`, so a change recomposes without restart. Text
  outside Compose (notifications and channel names, `TrackPickerFragment`, tray, Windows notifications, SMTC)
  reads `Translator`.
- Android: `res/xml/locales_config.xml` plus `android:localeConfig`; `LocaleManager.setApplicationLocales` on
  API 33+; `values-<tag>/strings.xml` for `app_name`; `PhoneCountries.kt` via `Locale.getDisplayCountry`.
- Track defaults: last pick for this series, then user preference, then UI language.
- Decisions: auto-switch to a supported system language plus a one-time "Now in Español, change in Settings"
  card; contributions by GitHub PR on the JSON with CI `:core:jvmTest`, no Weblate; screenshots English only;
  first wave es-419 (issue author reviews), pt-BR, hi, ml (user reviews); unreviewed languages hidden.
- **Changed 2026-10-06 (user):** ship the most common languages at once and review in use: es-419, pt-BR, fr, de, it, ru, uk, tr, id, vi, ar, zh-CN, ja, ko, hi, ml.
  Machine translations are visible in the picker (no hiding). This widens the earlier choices: CLDR plural
  rules for all 16 (ru, uk and ar have the extra categories; ja, ko, zh, vi and id have one form); right to
  left layout for `ar` (Compose mirrors on its own; check the XML player overlay, icons that point a
  direction, and the timebar); desktop fonts for CJK, Arabic, Devanagari and Malayalam (Noto, with a notices
  entry; subset or load from the system if the package grows too much, and say how much it grew).

### R2 Shared onboarding
Pages: Language (system preselected, each language named in itself, "Help translate"); What TMPlayer is
(unofficial, no server, no data collected); Sign in; Chats; Videos. Wording by form factor (touch, remote, mouse
and keyboard). `OnboardingImage` actuals in `ui/src/androidMain` (existing drawables) and `ui/src/jvmMain` (WebP).
Gating `SettingsStore.overviewSeen`, `markOverviewSeen`, `replayOverview` everywhere. Desktop promo fixture
mirrors `app/src/promo/`.

### R3 App shell
What's new: `lastSeenVersion`, a dismissible sheet on the first run of a new version with bundled, translatable
highlights. Feedback: a prefilled GitHub issue URL (version, device, OS, language, no personal data), mailto
fallback, QR on TV. About: see CP05. Star prompt: once, after 10 completed plays, never repeats (optional).

### R4 Player
Delays ±0.1 s per file; subtitle style S/M/L/XL, box, position, live preview; volume boost via Android gain and
compressor `AudioProcessor`, mpv `volume-max=150` and `af=dynaudnorm`; sleep timer in the menu; "still watching"
after N autoplays or 2 h idle, pauses and stops downloads; TV next-up card with D-pad focus on "Play now";
resume rules (Kodi model): none in the first 3 min or last 8 %, watched at 90 %, save tracks, speed and delay
every 10 s and on pause and quit; desktop frame step, screenshot, A-B repeat, chapters, `?` sheet. Trickplay:
thumbnails from the downloaded range only (Android `MediaMetadataRetriever.getScaledFrameAtTime(OPTION_CLOSEST_SYNC)`,
desktop a second mpv instance or an mpv screenshot at a position), off on 1 GB devices, behind a setting.

### R5 Browse
Series view from `MediaName.kt` SxxEyy plus new forms, one tile per series ("Show · 2 seasons · 18 eps"),
season tabs, episode list with progress and ticks, "Series" and "All files" toggle. Home: Continue (thumbnails
and progress), a row per favourite chat, "Recently added across chats", lazy rows of 10, rail and drawer keep
their tabs. Detail panel: cleaned title, filename, caption as synopsis, resolution, codecs, tracks, size,
Resume `34:10` or Start over, Download, Mark watched, TMDB poster and overview when enabled. Search: last 5
recent searches as chips, a "Videos in all chats" scope via TDLib `searchMessages` with a video filter, results
split into Chats and Videos. Empty states via `StateScaffold` and `UiState`. Downloads: "Remove after
watching", "x GB free". TV: 1.05 focus scale, 48/27 dp overscan check, labels for actionable icons. Watch Next:
`androidx.tvprovider` `WatchNextProgram` CONTINUE and NEXT, works on stock Android TV launchers, retired on
Google TV in 2027, behind a setting.

### R6 Fire TV
Detect `amazon.hardware.fire_tv` for remote key quirks and the voice-search fallback; test on a Fire TV emulator
or device; Amazon listing reuses the existing screenshots.

### R7 Online metadata (zero cost at any scale)
- **OpenSubtitles.com REST v1:** one free app key; the terms forbid making users enter their own key; downloads
  count against each user's own free account (10 to 20 a day, 5 per IP anonymously); 5 req/s per IP, login
  1 req/s; the hash match (size plus 64 KB head and tail) is the reliable route; exclude `ai_translated` and
  `machine_translated` by default; es and pt deep, hi thin, ml very thin.
- **TMDB:** free for non-commercial use (donations stay voluntary and unlock nothing); about 40 req/s per IP;
  cache at most 6 months, purge on termination; attribution required; es and pt-BR good, hi partial, ml sparse.
- **TVmaze:** no key, at least 20 calls per 10 s, CC BY-SA with attribution. **AniList:** no key, about 30/min.
  **SubDL:** free key, 2,000 req/day, has the Subscene archive, better for hi and ml.
- **Subtitles design:** the app key injected by CI from `OPENSUBTITLES_API_KEY` (builds without it hide the
  feature); the user signs in with their own OpenSubtitles account under Settings → Online subtitles, token
  stored like other credentials; search by hash (head and tail via TDLib byte ranges, works mid-download), then
  TMDB/IMDb id, then parsed title and episode; machine and AI subtitles off by default (a labelled toggle);
  language from the subtitle preference then the UI language; "N of M downloads left today" from `remaining`
  and `reset_time`; optional SubDL fallback with a user-entered SubDL key.
- **Metadata design:** TMDB (CI key from `TMDB_API_KEY`, user can override in Settings), TVmaze for episodes,
  AniList for anime; off by default, on in Settings or from the detail panel; matching on the cleaned title plus
  year or SxxEyy, `{tmdb-123}` in a filename forces a match; UI language with English fallback; About shows the
  TMDB notice and logo (less prominent than our branding) and the TVmaze link.
- **Shared:** an on-device cache keyed by file id or hash, TTL at most 6 months, a purge-all action;
  single-flight per item, debounce, backoff on 429; posters at the right size into the image disk cache.
- **Never stop working midway (user requirement):** no core feature depends on a provider; cached results stay;
  fallbacks TMDB → TVmaze/AniList → filename and OpenSubtitles → SubDL → local `.srt`; on 401/403 the provider is
  disabled with a clear message ("Online subtitles unavailable right now", "Add your own TMDB key"); keys rotate
  through updates; the app and site call these "online extras". Skipped providers: OMDb, Trakt, Fanart.tv,
  SubSource, Wyzie.
- **MSone (issue #5):** about 3,400 releases, IMDb id on every page, direct `.srt` via `?wpdmdl=`, WP REST API
  blocked, no reuse licence. Nothing is built until they agree; never a scraper. If they agree: a provider
  searching by title and year or IMDb id, episode pick inside season packs, a "via MSone" credit.
- **Verification:** cache hits, a forced 429 for quota exhaustion, key-revoked behaviour.

### R8 Site
Done already: OS-detected download, `/changelog/` and `feed.xml` via `scripts/build-changelog.py` (rerun after
footer edits), `/fire-tv/`, `/formats/`, `/security/`, `/signing/`, JSON-LD, community and donate footers.
Still to do (CP26): localization as in CP26; `/translate/`; SEO guides "Watch Telegram videos on Android TV",
"...on Fire TV", "Telegram on TV without casting"; a home numbers strip if not complete (stars, downloads from
`scripts/download-counts.sh`, languages, "0 ads of our own, 0 trackers", "free forever"); testimonials only with
permission; the language switcher in the footer.

### R9 Scripted bulk extraction
1. Find: `grep -rnoP` per module for `Text("`, `text =`, `label =`, `title =`, `contentDescription =`, toasts and
   notifications, into a scratchpad TSV.
2. Name: a script proposes keys; review once per module.
3. Catalog: `jq` merges into `en.json` (sorted). Only `en.json` is written by hand or script.
4. Replace: `perl -pi -e` or `sed -i` on exact call shapes, plus imports.
5. Check: build, `git diff --stat`, re-grep; leftovers go to an allowlist or a manual list (interpolations,
   plurals, concatenations, Views, words needing two translations).
6. Site: the same, `perl` adds `data-i18n`, `jq` writes `site/i18n/en.json`.

### R10 Critical files
- core: `core/build.gradle.kts`, `core/src/commonMain/kotlin/com/tmplayer/data/{SettingsStore,MediaName,ResumeRecord,ChatRepository,SizeFilter}.kt`
- ui: `ui/src/shared/kotlin/com/tmplayer/ui/{browse/BrowseSections.kt,browse/BrowseViewModel.kt,components/Skeleton.kt,components/UiState.kt}`
- app: `app/src/main/java/com/tmplayer/{MainActivity.kt,ui/onboarding/OverviewScreen.kt,ui/auth/IntroScreen.kt,ui/settings/*,ui/browse/TouchNav.kt}`,
  `player/{PlayerActivity.kt,PlayerTvMenu.kt,PlayerGestures.kt,FrameRateMatch.kt,TrackPickerFragment.kt,ExternalSubtitle.kt}`,
  `data/{DownloadService.kt,PhoneCountries.kt,CrashReports.kt}`
- desktop: `desktop/src/main/kotlin/com/tmplayer/desktop/{ui/Shell.kt,ui/ShellState.kt,ui/Settings.kt,ui/MediaGrid.kt,ui/UpdatePopup.kt,player/MpvPlaybackEngine.kt,player/PlayerKeys.kt,player/PlayerScreen.kt,SelfTest.kt}`, `desktop/packaging/`
- site: `site/*.html`, `site/app.js`, `site/sitemap.xml`, `site/site.webmanifest`
- CI: `.github/workflows/release.yml`, `ci.yml`

## Done before this plan (for context, do not redo)

- Tier 1 (2026-10-05): plan and langsync configs (`1c4635e`); community and donate links (`ffa496b`); Telegram
  release announcements in release.yml (`a4960ef`, first real run was 1.22.1); English site pages (`ffa496b`);
  distribution drafts, fastlane text, Obtainium link (`760b7ad`); build provenance attestations (`1e55b5f`);
  repo hygiene (`f6a08fb`).
- Partial compliance pass `2d73eeb`: Android intro "unofficial" line, `dependenciesInfo` off, Gradle wrapper
  checksum, some wording, a first crash scrub, privacy page edits, `/signing/`. CP01 to CP03 finish it.
- Accounts: Telegram channel t.me/tmplayerapp and group t.me/tmplayer_chat, release bot and secrets, Discussions,
  GitHub Sponsors (tiers live), Buy Me a Coffee, hello@tmplayer.org (ImprovMX on GoDaddy DNS), `TMDB_API_KEY`
  and `OPENSUBTITLES_API_KEY` secrets, repo social preview, launch tweet.
- Releases: 1.22.1 (2026-10-05) fixed the TV player crash. The 50 MB and no-upper-limit size defaults are in it.
- Submissions made on 2026-10-05: see U15, U16, CP17, U10.

## Set aside

- **IzzyOnDroid:** their policy rejects vibe-coded apps and the form asks the AI assistance level; an honest
  answer means a decline. Obtainium covers the same users. A draft exists in `docs/distribution/izzyondroid.md`
  if the policy changes.
- **telegram.org/apps:** no submission path; only @PressBot, which is for journalists.
- **Casting, profiles, kids mode, preview autoplay, recommendation rows, hero carousels, quality choice,
  audio-fingerprint skip intro, theme engines:** deliberately not copied from competitors.
