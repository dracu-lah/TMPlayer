# Distribution channels

Reference drafts for getting TMPlayer listed beyond GitHub and tmplayer.org. **Status and next steps
live in the maintainer's working plan** (Phase C and the U tasks, kept off GitHub); this folder only keeps the material
those steps use.

| File | Used by |
| --- | --- |
| [winget/](winget/) and [winget.md](winget.md) | U13 first submission; the release job's `winget` step after that |
| [telegram-apps.md](telegram-apps.md) | U17 AlternativeTo, U18 Uptodown, U19 XDA (a fact list only) |
| [flathub.md](flathub.md) | Why there is no Flathub package (PR #10513, closed) |
| [aur.md](aur.md) | The AUR package `tmplayer-bin`: one-time setup, then published by the release job |
| [amazon.md](amazon.md) | U23 Amazon Appstore listing (text, images, content rating) |
| [play.md](play.md) | Phase G Google Play (set aside), and Android developer verification for every APK |

IzzyOnDroid was decided against (see PLAN); Obtainium installs straight from the GitHub releases.

## Already done in the repository

- `fastlane/metadata/android/en-US/`: title, short and full description, the icon and
  `changelogs/<versionCode>.txt`, one per release. F-Droid style clients read this.

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
