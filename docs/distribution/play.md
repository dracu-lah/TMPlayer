# Google Play: what a listing would take

Used by **Phase G** in the working plan (CP34 to CP36, U26 to U29), which is set aside while the
budget is $0 (U03). Nothing is submitted. Facts checked on 2026-10-19; recheck the dated ones
before acting on them.

## Why the GitHub APK cannot go to Play as it is

- **The in-app updater.** Play's Device and Network Abuse policy forbids an app from Play updating
  itself any other way than through Play, and `REQUEST_INSTALL_PACKAGES` is a restricted
  permission that needs a declared core use. A Play build drops both, the way the F-Droid build
  already does (`-PtmFdroid=true`, `BuildConfig.UPDATE_CHECK`, `src/fdroid/AndroidManifest.xml`).
- **Target API level.** Since 2026-08-31 new apps and updates on Play must target API 36 (Android
  16); an extension to 2026-11-01 can be asked for in the console. The TV-only floor (API 34) does
  not cover TMPlayer, which also ships to phones. The app targets 35 today
  (`app/build.gradle.kts`), so CP34 raises it and the player's Back handling moves to the
  predictive back API. <https://developer.android.com/google/play/requirements/target-sdk>
- **Foreground service types.** `FOREGROUND_SERVICE_DATA_SYNC` (downloads) and
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (background audio) each need a declaration in the console
  with a short video of the feature.
- **An AAB.** Play takes an Android App Bundle, so the release job builds one beside the APKs.

## The account

- A personal developer account costs a one time $25 and an identity check (U07, which also covers
  Android developer verification for the GitHub APK).
- A personal account made after 2023-11-13 must run a closed test with at least 12 testers, opted
  in for the last 14 days without a break, before production opens.
  <https://support.google.com/googleplay/android-developer/answer/14151465>
- Data safety form: nothing collected by the developer; crash reports opt-in (Sentry); the
  Telegram session stays on the device. It must match `site/src/pages/privacy/`.
- Reviewers need to get in without a Telegram code, hence CP35's demo mode with openly licensed
  clips.

## Lessons from another app's Play launch

Prav (a chat app) wrote up its launch: <https://vishnusanal.github.io/posts/publishing-prav-on-play-store>.
The app was finished, and it still took about six months. What applies here:

- **Plan in weeks, not days.** Most of the time went on the account, finding testers and the
  14 day test, not on code. The 14 days only start once 12 testers are opted in.
- **Line up the 12 testers first.** Collect their Google account emails (from the TMPlayer channel
  and community) before the build is ready, and ask for more than 12 since some drop out. Anyone
  who leaves resets the "without a break" count, so check the console's tester count daily.
- **Reviewer access is the usual first rejection.** Prav's first production submission was rejected
  for having no demo account. CP35's demo mode must be easy to find from the first screen, and the
  steps go in the console's "App access" section. Test it on a fresh install before submitting.
- **Script the foreground service videos early.** Prav lost time working out what to record. Data
  sync (downloads) and media playback each need a short clip; the demo mode content can be reused.
- **Decide personal or organization on purpose.** A personal account ties the app to one developer
  and shows their details publicly on the listing. An organization account avoids that but needs a
  DUNS number, which stalled Prav for weeks. Check what will be shown before U07.
- **Have a second card for the $25 fee.** Prav's card was blocked at payment.
- **Check which track counts.** Prav's post says an internal test unlocked production; the policy
  above says a closed test. Confirm in the console before starting the 14 days.

## How listings like this get removed

Other players for Telegram videos have left Play, none with a published reason: TMP (Telegram
Media Player), whose developer wrote that Google removed it "for an unknown reason", Telegram Video
Player (2024-08-25) and TelePlayer (2021-09-26). In 2015 Google removed Telegram+ under its
intellectual property and impersonation rules and restored it once it said it was unofficial.
What keeps TMPlayer clear of the same:

- **Name and look.** "TMPlayer" as the title, the app's own icon, and "unofficial, not affiliated
  with Telegram" in the description (Telegram API terms 2.3 and 2.4, see
  [telegram-apps.md](telegram-apps.md)).
- **What it is for.** A player for the videos already in your own chats. The listing, the
  screenshots and the reviewer's demo show only demo or openly licensed content, never a real
  title, and the wording rules in `AGENTS.md` (video, not film or movie) apply to every field.
- **Telegram's own restrictions.** TDLib hides posts restricted for the platform unless
  `ignore_platform_restrictions` is set, and `MediaMapper` skips any message with a restriction
  reason. Never set that option in a store build.
- **A way to report.** CP35's Report action for chats, since Play treats an app that shows other
  people's posts as user generated content.

## What the research found (2026-11-02)

No source states why TMP, Telegram Video Player or TelePlayer left Play; nothing public was found
beyond the TMP developer's own words. The documented cases of look-alike and player apps show the
likely routes:

- **Telegram+ (2015).** Removed for "intellectual property and impersonation" after Google asked
  for proof the client was authorized. Telegram said it had not asked for the removal. It came
  back with an all caps "unofficial" disclaimer and one feature cut at Telegram's request.
  <https://www.androidpolice.com/2015/03/03/telegram-from-the-former-dev-of-whatsapp-pulled-from-the-play-store-by-google-for-impersonation-of-telegram/>
- **Perfect Player (2019).** A pay-TV company's copyright complaint said the app let people watch
  its channels. The app ships with no playlists, yet it was removed until a counter-notice. A
  player with no content of its own is not safe from a complaint.
  <https://torrentfreak.com/google-play-removes-perfect-player-after-bogus-copyright-complaint-191019/>
- **Downloader (browser app).** Removed under the "Unauthorized Use of Copyrighted Content"
  policy after an infringement notice. <https://torrentfreak.com/?p=235348>
- **Pirate Bay front ends (2014).** Pulled with no notice and no chance to fix, appeals refused,
  policy strikes on the accounts. Too many strikes end the developer account.
- **User generated content.** An app that shows other people's posts must let users report and
  block content and users, and moderate it. <https://support.google.com/googleplay/android-developer/answer/9876937>

So the three routes are: look-alike or impersonation, a copyright complaint about what the app can
play, and the user generated content rules. A removal can also come without a stated reason.

## Don't dos

- **Don't use Telegram's name, logo or colours as the app's identity.** Title "TMPlayer", own
  icon, and "unofficial, not affiliated with Telegram" in the first line of the description.
- **Don't show real titles, posters or channel names** in the listing, screenshots, the demo or
  the reviewer notes. Demo clips only, openly licensed.
- **Don't say "watch movies", "free movies", "stream any film" or "uploader"**, in any field or
  language. A player for the videos already in your own chats, nothing more.
- **Don't ship or link content.** No built-in channel list, no search that finds public channels
  for the viewer, no link to a channel that carries copyrighted video. Perfect Player shows an
  empty player still gets a complaint.
- **Don't set `ignore_platform_restrictions`** and don't remove the `MediaMapper` restriction
  skip in the Play build.
- **Don't keep the updater, `REQUEST_INSTALL_PACKAGES`, donation or sponsor links** in the Play
  flavor. Play policy bans self updates and outside payment prompts.
- **Don't submit without reviewer access.** The demo mode is found from the first screen and the
  steps are in "App access". Test it on a fresh install.
- **Don't skip Report and Block.** Chats and their videos get a Report action and a hide option
  before the first submission.
- **Don't let the Data safety form drift** from the privacy page or the real behaviour (Sentry is
  opt in, the Telegram session stays on the device).
- **Don't change the foreground service types or permissions** after approval without a new
  declaration and video.
- **Don't ignore a complaint.** Reply within the stated days, with a counter-notice if the claim is
  wrong. Don't upload a second listing or a second account to get around a removal: that
  endangers the whole developer account.
- **Don't let strikes pile up.** After any warning, fix the cause in the next update before it
  repeats.
- **Don't test with a real account in a recording.** Videos and screenshots use a throwaway
  account with demo chats.
- **Don't let a release silently change the store build.** Every Play update re-reads this list
  and the Telegram terms check in [telegram-apps.md](telegram-apps.md).

## Developer verification (also for the GitHub APK)

Since 2026-09-30 certified Android devices in Brazil, Indonesia, Singapore and Thailand install
only apps from registered developers, and Google plans the same everywhere in 2027. Unregistered
apps still install through adb or an advanced flow, which most people will not use. Registering
`com.tmplayer` and the release key (U07, U08, CP15) keeps the GitHub, Obtainium and site downloads
working, Play or no Play.
<https://android-developers.googleblog.com/2026/06/android-developer-verification.html>
