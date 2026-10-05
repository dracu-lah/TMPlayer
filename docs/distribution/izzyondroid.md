# IzzyOnDroid, and Obtainium

Status: ready to request, with two points the maintainers are likely to raise (Sentry and the
in-app updater). Nothing requested yet.

IzzyOnDroid is an F-Droid style repository that ships the developer's own signed APK, taken from
the GitHub release, so TMPlayer does not need to change how it builds. Requests go to the
**Codeberg** tracker now: <https://codeberg.org/IzzyOnDroid/repodata/issues> (the old GitLab
project is archived and says so at the top).

## The inclusion policy, checked against TMPlayer 1.22.0

Policy: <https://gitlab.com/IzzyOnDroid/repo/-/wikis/Inclusion-Policy>. Facts below were checked on
the released `TMPlayer-1.22.0-universal.apk` (sha256 `5c6a01bd...12e9`, matches `site/latest.json`).

| Requirement | TMPlayer | |
| --- | --- | --- |
| Free and open source, OSI/FSF licence | GPL-3.0 | OK |
| For end users, source public with a clear description | GitHub, README | OK |
| APK attached to a tagged GitHub release | `v1.22.0` release, one APK | OK |
| Signed with a release key, not debuggable, not testOnly | Signed `CN=TMPlayer, OU=dev, O=dracu-lah`, cert SHA-256 `00fbf6e8bcf6080060e2653bbd44004655e65d25dd46e6d3d2bcc881c2842652`; no debuggable or testOnly flag | OK |
| Unique packageName | `com.tmplayer` | OK |
| Size: "up to 30 megabytes per app", also the per-APK cap | 26,696,817 bytes (26.7 MB) | OK, but only one version fits (they keep up to 3 when they fit), and about 3 MB of headroom. If a future APK passes 30 MB, they will ask for the per-ABI APKs again (they would take `armeabi-v7a`). |
| `usesCleartextTraffic` avoided | Not set | OK |
| No more than two tracker modules; none at all if the app "processes sensitive data" | One: the Sentry SDK | **See below** |
| No downloads of executable binaries without explicit, informed, opt-in consent | In-app APK updater | **See below** |
| Category not excluded (games, tracking-heavy social apps, etc.) | Video player; other Telegram clients are listed there (Telewatch, a TDLib watch client, with `NonFreeNet`) | OK |
| Reproducible builds | Not required; optional, gets a badge | Not set up |

### Sentry: what the code does, precisely

- `io.sentry:sentry-android-core` 8.16.0 is a dependency of every release build
  (`app/build.gradle.kts`). The released APK contains `io/sentry` classes and a compiled-in DSN
  (EU ingest host), because CI sets `SENTRY_DSN` from the repository secret.
- Sentry's auto-start ContentProvider is disabled in the manifest (`io.sentry.auto-init=false`).
- `CrashReports.start` (`app/.../data/CrashReports.kt`) is the only way the SDK starts. It does
  nothing unless the "Send crash reports" switch in Settings is on, and that switch is **off on a fresh
  install**. Turning it off later calls `Sentry.close()`, and a `beforeSend` gate drops anything
  queued after that.
- When on, it sends only crashes: performance tracing 0, session tracking off, every breadcrumb
  source off and `maxBreadcrumbs = 0`, `sendDefaultPii = false`, and the user object (where Sentry
  keeps the installation id) is removed from every event. A report holds the stack trace, app
  version, Android version and device model.

IzzyOnDroid's scanner library list types `/io/sentry` as **"Mobile Analytics"**, so the listing
will very likely carry the **Tracking** anti-feature, opt-in or not. One module is within their
"no more than two" tolerance. The risk is the stricter clause: an app that "processes sensitive
data" must have no tracking modules at all, and a reviewer may count a Telegram session as
sensitive. Choices, best first:

1. Ship as is and explain the opt-in in the request (draft below). Most likely outcome: accepted
   with `Tracking`.
2. If they refuse, or a clean listing matters: drop `sentry-android-core` from release builds (or
   behind a build flag that CI leaves off). The Settings switch already hides itself when no DSN
   is compiled in, so the app needs no other change.

### The in-app updater

The policy: an app "must not download additional executable binary files (e.g. addons,
auto-updates, etc.) without explicit user consent. Consent means it needs to be opt-in ... and
structured in a way that clearly explains to users that they're choosing to bypass the checks
performed in this repo". Apps that add a non-opt-in updater later are removed.

TMPlayer today: the update **check** (`tmplayer.org/latest.json`, falling back to the GitHub API)
runs by default and pops up a notice once per version; the **download** only happens when the
viewer presses the button, and Android's installer asks again. The notice can be switched off,
which stops the checks too (Settings, "Tell me when a new version is out"). That is per-update consent, but it is **on by default** and says
nothing about bypassing IzzyOnDroid, so expect a request to change it. Two cheap fixes, either
is enough:

- Hide the update notice when the app was installed by an F-Droid style client
  (`PackageManager.getInstallSourceInfo(packageName).installingPackageName` on Android 11 and up,
  `getInstallerPackageName` below that, is for example `org.fdroid.fdroid`, `com.looker.droidify`
  or `com.machiav3lli.fdroid`), or simply whenever some app store, rather than the system package
  installer, did the install.
- Or keep it, and add a line to the popup such as "This downloads the APK from GitHub, outside
  the store you installed TMPlayer from." That meets the "clearly explains" part of the rule.

Doing the first before requesting avoids a round trip.

### Anti-features to expect on the listing

- **NonFreeNet**: it depends on Telegram's servers, which are not free software. Every Telegram
  client there carries it.
- **Tracking**: the Sentry SDK, as above, unless it is removed.
- Possibly **Ads**: Telegram's own sponsored messages in public channels are shown, as Telegram's
  API terms (3.3) require of third party clients. Mention it up front so it is not a surprise.

### Metadata Izzy will read

IzzyOnDroid reads Fastlane metadata from the repository, so `fastlane/metadata/android/en-US/`
(title, short and full description, `changelogs/<versionCode>.txt`, icon) is now in place; the
screenshots are converted from the site's WebPs right before the request (see README.md). Add `changelogs/<versionCode>.txt` with each release from now on,
for example from the tag message, at most 500 characters.

## Draft request (Codeberg issue, use their "app inclusion" template if offered)

**Title:** Add TMPlayer (com.tmplayer)

```
App name: TMPlayer
Package name: com.tmplayer
Source: https://github.com/dracu-lah/TMPlayer
License: GPL-3.0
Website: https://tmplayer.org
APK: GitHub releases, one universal APK per release
     (https://github.com/dracu-lah/TMPlayer/releases, file TMPlayer-<version>-universal.apk,
     26.7 MB for 1.22.0, armeabi-v7a and arm64-v8a)
Category: Multimedia / Video player
Fastlane metadata: fastlane/metadata/android/en-US in the repository

Description:
TMPlayer plays the videos in your own Telegram chats and channels on Android TV and phones,
starting while the file is still downloading. One APK for TV (remote friendly UI) and phone.
It is an unofficial Telegram client built on TDLib, not affiliated with Telegram, and needs
the user's own Telegram account.

I am the developer.

Things you will see in the scan, so here they are up front:

- NonFreeNet: it talks to Telegram's servers.
- Sentry SDK (io.sentry): crash reporting, strictly opt-in. Auto-init is disabled in the
  manifest; the SDK is only started when the user turns "Send crash reports" on in Settings, which
  is off on a fresh install. When on, it sends crashes only: no sessions, no performance
  tracing, no breadcrumbs, no IP address, no user or installation id
  (app/src/main/java/com/tmplayer/data/CrashReports.kt).
- Update check: the app can tell the user that a newer version is on GitHub. It never
  downloads anything unless the user presses the button, and the notice can be turned off,
  which stops the checks. [If done before filing: When installed from an F-Droid client the
  notice is hidden.]
- Sponsored messages: in public channels the app shows Telegram's own sponsored messages,
  because Telegram's API terms require third party clients to show them. The app has no ads
  of its own.
- Native libraries are prebuilt TDLib (via tdl-coroutines) and FFmpeg audio decoders
  (via NextLib), both open source.
```

## Obtainium

Obtainium installs straight from GitHub releases, so it works today with nothing to submit.

Verified: `https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/dracu-lah/TMPlayer`
returns HTTP 200 and serves Obtainium's redirect page ("Redirecting to Obtainium", with a fallback
button and a link to the repository). Obtainium's deep link docs
(<https://wiki.obtainium.imranr.dev/deep_links/>) list `obtainium://add/<url>` as the single app
form; the `redirect?r=` wrapper is what makes it clickable from a web page or a README.

Badge: Obtainium asks projects to self host its badge image, `assets/graphics/badge_obtainium.png`
from <https://github.com/ImranR98/Obtainium/tree/main/assets/graphics> (the raw file exists).
Copy it into the repository (for example `art/badge_obtainium.png`), then:

```markdown
[<img src="art/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/dracu-lah/TMPlayer)
```

HTML, for the site:

```html
<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/dracu-lah/TMPlayer">
  <img src="/badge_obtainium.png" alt="Get it on Obtainium" height="54">
</a>
```

Once the app is on IzzyOnDroid, its own badge and a version shield are available:
`https://img.shields.io/endpoint?url=https://apt.izzysoft.de/fdroid/api/v1/shield/com.tmplayer&label=IzzyOnDroid`.
