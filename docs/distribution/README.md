# Distribution channels

Reference drafts for getting TMPlayer listed beyond GitHub and tmplayer.org. **Status and next steps
live in the maintainer's working plan** (Phase C and the U tasks, kept off GitHub); this folder only keeps the material
those steps use.

| File | Used by |
| --- | --- |
| [winget/](winget/) and [winget.md](winget.md) | U13 first submission; the release job's `winget` step after that |
| [store-text.md](store-text.md) | The one source for store text: short and full description, what's new, feature lines, support line, each store's limits |
| [telegram-apps.md](telegram-apps.md) | U17 AlternativeTo, U18 Uptodown, U19 XDA (a fact list only) |
| [flathub.md](flathub.md) | Why there is no Flathub package (PR #10513, closed) |
| [aur.md](aur.md) | The AUR package `tmplayer-bin`: one-time setup, then published by the release job |
| [amazon.md](amazon.md) | U23 Amazon Appstore listing (text, images, content rating) |
| [play.md](play.md) | Phase G Google Play (set aside), and Android developer verification for every APK |
| [crash-consent.md](crash-consent.md) | S1: crash reports asked once at first run, and what an opt-out default would change |
| [crash-visibility.md](crash-visibility.md) | S3: Android vitals, Sentry and the log upload; the weekly stutter list; Sentry quota |
| [libvlc-spike.md](libvlc-spike.md) | PR2 and PR7: libVLC's version, size, 16 KB, licence, F-Droid and Play answers |

## Desktop install warnings

The Windows MSI is unsigned and the macOS DMG is ad hoc signed and not notarized, so SmartScreen and
Gatekeeper warn on the first open. The free help is the site page tmplayer.org/install-warnings/ (steps
for both, drawn step windows, the PowerShell line and the `xattr` command); store and package-manager
listings (winget, AlternativeTo, Uptodown) may link it where they mention a warning. Signing (Azure Trusted
Signing or SignPath for Windows, an Apple Developer ID and notarization for the Mac) waits until the user
funds it (decision D6); when it lands, update that page, `/signing/` and the notes in `INSTALL.md`.

IzzyOnDroid was decided against (see PLAN); Obtainium installs straight from the GitHub releases.

## Already done in the repository

- `fastlane/metadata/android/en-US/`: title, short and full description, the icon and
  `changelogs/<versionCode>.txt`, one per release. F-Droid style clients read this.

## iPhone and iPad

TMPlayer for iPhone and iPad is a player for the videos already in your own Telegram chats, like
the Android app, and it is unofficial: not affiliated with Telegram. It reaches people by
sideloading, from a GitHub release tagged `apple-v...` (how it is built and released:
[docs/IOS.md](../IOS.md)). Channels:

| Channel | State | Notes |
| --- | --- | --- |
| AltStore | Offered | Add the source `https://raw.githubusercontent.com/dracu-lah/TMPlayer/main/altstore/source.json`; each release adds its version and the app offers the update |
| SideStore | Offered | The same source file |
| Sideloadly | Offered | The IPA from the release, signed with the user's own Apple ID; no automatic updates |
| LiveContainer | Offered | The same IPA, installed into LiveContainer; no automatic updates; not yet tested |
| TrollStore | Not offered | It works only on a few older iOS versions, none that this app runs on |
| AltStore PAL (EU), TestFlight, App Store | Later | They need an Apple Developer account and a signed, notarized build; the support links in the app would also have to go from an App Store build |

Copy for any of these follows [telegram-terms.md](../agents/telegram-terms.md) and the rule for
public text: "video", never "film" or "movie"; no real titles; never "uploader", "movie channel" or
"watch any movie"; "unofficial, not affiliated with Telegram" in every description; and posts link
tmplayer.org/legal. The source's own `description` and `localizedDescription` already say so
(`altstore/source.json`); a change to them is checked against the same rules. The app's name has
no "Telegram" in it and the icon is TMPlayer's own, as on Android.

## Points that cut across channels

- **Telegram's terms.** The rules every listing follows (no "Telegram" in a title, "unofficial" in
  every description, sponsored messages shown, no updater in a store build) are in `AGENTS.md`,
  "Telegram's terms and the stores".
- **Store builds.** A store file is built on the `-PtmFdroid=true` switch (PLAN CP34), which drops
  the updater, `REQUEST_INSTALL_PACKAGES` and Cast, as the
  F-Droid build does.
- **Release cadence.** Uptodown and Amazon add a manual step per release. winget is automatic once
  the first submission is merged: the release job's `winget` step skips itself until the
  `WINGET_TOKEN` secret is set.
