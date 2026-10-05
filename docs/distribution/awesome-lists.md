# Awesome lists

Status: entries drafted, no PRs opened. Stars and push dates checked with `gh api` on 2026-10-05.
TMPlayer itself has 17 stars at the time of writing, which matters for lists with a popularity bar.

Order is best fit first. One PR per list, titled with the app name (for example
`Add TMPlayer`), never `Update README.md`.

## 1. Awesome Android TV FOSS Apps

- Repo: <https://github.com/Generator/Awesome-Android-TV-FOSS-Apps> (1,554 stars, last push 2026-09-14)
- How to submit: **not by PR.** The README's Contributing section links to an issue form:
  <https://github.com/awesome-android-tv-foss-apps/.github/issues/new>.
- Section: `### Media Player` (alphabetical; it goes between Nova Player and VLC).
- Rules: none written beyond the legend. The legend has `⚠️ App includes non-free components`;
  TMPlayer has none, so no marker. It has a TV launcher banner and full remote support, so no
  `📺` or `🖱️` either.
- Entry, in the list's own format:

```markdown
- **TMPlayer:** Streams the videos in your Telegram chats while they download, with subtitles, audio tracks and episode autoplay. Unofficial Telegram client, needs your own account [[Source](https://github.com/dracu-lah/TMPlayer)] [[Website](https://tmplayer.org)]
```

Add `[[IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/com.tmplayer)]` after Website once
it is listed there (that is the link form this list uses for Izzy).

## 2. awesome-telegram

- Repo: <https://github.com/ebertti/awesome-telegram> (5,958 stars, last push 2026-09-21)
- Rules (`contributing.md` and `AGENTS.md`): related to Telegram; single concise sentence; added
  at the **bottom** of the right section; title case; descriptive PR title; the commit body links
  the repository. The separator between link and description **must be the en dash character**
  (U+2013), not a hyphen; their review rules request changes for a hyphen.
- Section: `## Tools` (there is no clients section; Tools holds apps and utilities). Add at the
  bottom, after VideoDownloaderBot.
- Entry. This repo's writing rules forbid typing that dash in our own files, so it is written
  here as `{EN DASH}`; replace it with the real character in the PR:

```markdown
 * [TMPlayer](https://github.com/dracu-lah/TMPlayer) {EN DASH} Unofficial open source video player for Android TV, phones, Windows and Linux that streams the videos in your Telegram chats while they download.
```

(Note the list's leading space before `*`, as in its other entries.)

## 3. Android FOSS (offa)

- Repo: <https://github.com/offa/android-foss> (11,327 stars, last push 2026-10-01)
- Rules (`CONTRIBUTING.md`): FOSS licence, source available, "Keeps Privacy: No Advertisement, no
  Spyware", **no proprietary elements**, stable, maintained, documented, free of charge;
  alphabetical; link F-Droid first, IzzyOnDroid second, **never Google Play or other third party
  repos**, and an IzzyOnDroid link only if the package is not marked NonFreeComp.
- Fit: borderline until two things are settled. Telegram's servers are a non-free network
  (several Telegram forks are listed in Messaging, so that alone is accepted), and the opt-in
  Sentry SDK may be read as "spyware" by a strict reviewer. Wait until IzzyOnDroid has listed it,
  so the entry can link there, and mention the opt-in in the PR body.
- Section: `### • Video Player` (alphabetical: after Nova Video Player, before VLC).
- Entry, before IzzyOnDroid:

```markdown
* [**TMPlayer**](https://github.com/dracu-lah/TMPlayer)
```

  after IzzyOnDroid lists it:

```markdown
* [**TMPlayer**](https://github.com/dracu-lah/TMPlayer) <sup>**[[IzzyOnDroid](https://apt.izzysoft.de/packages/com.tmplayer)]**</sup>
```

## 4. open-source-android-apps (pcqpcq)

- Repo: <https://github.com/pcqpcq/open-source-android-apps> (10,519 stars, last push 2026-10-05)
- Rules (`CONTRIBUTING.md`): open source only; one primary category; one row per app in a
  6 column table, alphabetical by name; one change per commit. Easiest route is their **Actions,
  Add New App, Run workflow** form (fills stars, language and licence itself), or edit the file by
  hand. Star count is refreshed by their bot, so write the current number.
- Section: `categories/android_tv.md` (alphabetical: after NuvioTV, before SmartTubeNext).
- Entry:

```markdown
| [**TMPlayer**](https://github.com/dracu-lah/TMPlayer) | Streams the videos in your Telegram chats on Android TV and phones while they download. | `Kotlin` | `GPL-3.0` | 17 | [![Download](https://img.shields.io/badge/Download-APK-blue)](https://github.com/dracu-lah/TMPlayer/releases/latest) |
```

## 5. awesome-mpv

- Repo: <https://github.com/stax76/awesome-mpv> (2,284 stars, last push 2026-02-04, so slower to
  merge)
- Rules: no CONTRIBUTING file; entries are `- [Name](link) - Description.` and the Media Player
  sections end with "Based on <languages/toolkit>.". The relevance is the desktop app, which plays
  through libmpv.
- Section: `# Streaming Tools` (front ends that feed mpv from a service, next to the Jellyfin and
  Plex shims). Alternatively `## Cross-platform` under Media Player; Streaming Tools is the more
  honest fit.
- Entry:

```markdown
- [TMPlayer](https://github.com/dracu-lah/TMPlayer) - Unofficial Telegram video player for Windows and Linux (and Android TV) that streams the videos in your chats while they download, based on Kotlin/Compose Multiplatform/TDLib.
```

## 6. Awesome Kotlin (kotlin.link)

- Repo: <https://github.com/Heapy/awesome-kotlin> (11,386 stars, last push 2026-10-04; the old
  `KotlinBy/awesome-kotlin` URL redirects here)
- Rules (`.github/contributing.md`): edit `src/main/resources/links/<Category>.awesome.kts` on
  `main` and open a PR; the site rebuilds from it.
- Section: `Android.awesome.kts`, `subcategory("Projects")`, appended at the end of that block.
- Entry:

```kotlin
    link {
      github = "dracu-lah/TMPlayer"
      desc = "Telegram video player for Android TV, phones, Windows and Linux that streams while it downloads. Built on TDLib, Media3, Compose and Compose Multiplatform."
      setTags("kotlin", "android-tv", "jetpack-compose", "compose-multiplatform", "media3", "tdlib", "telegram")
    }
```

## 7. awesome-compose-multiplatform (mahozad)

- Repo: <https://github.com/mahozad/awesome-compose-multiplatform> (107 stars, last push 2026-09-24)
- Rules: none written. Table `| Stars | Name | Description |`, roughly sorted by stars inside
  each section. Small list, but a fair fit, since the desktop app is Compose Multiplatform and
  shares `:core` and `:ui` with Android.
- Section: `### Applications`, as the last row (lowest stars).
- Entry:

```markdown
| ⭐17 | [TMPlayer](https://github.com/dracu-lah/TMPlayer) | Telegram video player for Android TV, phones, Windows and Linux |
```

## Checked and not a fit

- **terrakok/kmp-awesome** (5,882 stars): libraries only; its rules require iOS support,
  Maven Central publishing and about 50 stars.
- **luong-komorebi/Awesome-Linux-Software** (25,618 stars): archived, read only.
- **albertomosconi/foss-apps** (1,203 stars): last push 2024-03-06.
- **fiedri/awesome-android-apps** (114 stars, active): takes entries as a JSON object per app
  (`name`, `host`, `stars_link`, ...) or through an issue template at
  `fiedri/awesome-mobile-apps`. Fine to add later; low reach.
- **awesome-selfhosted**: not a fit (TMPlayer is not a server).
- No maintained "awesome Android TV" list exists besides #1; `awesome-androidtv` style names
  either 404 or have single digit stars.
