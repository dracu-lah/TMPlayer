# Distribution channels

Reference drafts for getting TMPlayer listed beyond GitHub and tmplayer.org. **Status and next steps
live in [docs/PLAN.md](../PLAN.md)** (Phase C and the U tasks); this folder only keeps the material
those steps use.

| File | Used by |
| --- | --- |
| [winget/](winget/) and [winget.md](winget.md) | CP13, CP16, U13 |
| [telegram-apps.md](telegram-apps.md) | U17 AlternativeTo, U18 Uptodown, U19 XDA (a fact list only) |
| [flathub.md](flathub.md) | U16, Flathub PR #10513 |
| [amazon.md](amazon.md) | U23 Amazon Appstore listing (text, images, content rating) |
| [izzyondroid.md](izzyondroid.md) | Set aside (see PLAN), kept in case the policy changes |

## Already done in the repository

- `fastlane/metadata/android/en-US/`: title, short and full description, `changelogs/12200.txt`
  and the icon. IzzyOnDroid (and F-Droid clients through it) read this. Add
  `changelogs/<versionCode>.txt` with every release from now on.
- **Screenshots are not committed yet.** fdroidserver accepts only png, jpg and jpeg for metadata
  images (`ALLOWED_EXTENSIONS` in `fdroidserver/update.py`), while the repository keeps every
  screenshot as WebP (CLAUDE.md), and PNG copies would add about 4 MB to the history for no
  reader until IzzyOnDroid takes the app. Right before the inclusion request, convert them
  losslessly (pixel-identical) and commit them in that change:

  ```sh
  D=fastlane/metadata/android/en-US/images; mkdir -p $D/phoneScreenshots $D/tvScreenshots
  magick site/screenshots/phone-chats.webp $D/phoneScreenshots/1.png
  magick site/screenshots/phone-grid-light.webp $D/phoneScreenshots/2.png
  magick site/screenshots/phone-grid.webp $D/phoneScreenshots/3.png
  magick site/screenshots/tv-grid.webp $D/tvScreenshots/1.png
  magick site/screenshots/tv-chats-light.webp $D/tvScreenshots/2.png
  magick site/screenshots/tv-settings.webp $D/tvScreenshots/3.png
  ```

## Points that cut across channels

- **"Unofficial" everywhere.** Telegram's API terms require stores to say the app uses the
  Telegram API, and forbid "Telegram" in the title. Every draft here keeps "TMPlayer" as the name
  and says "unofficial, not affiliated with Telegram, needs your own account".
- **"No ads" needs one honest caveat.** The app has no ads of its own, but it shows Telegram's
  sponsored messages in public channels because the API terms (3.3) require it. The drafts say so.
- **Sentry is in the public APK.** Opt-in and off by default, but the SDK and a DSN are compiled in,
  and IzzyOnDroid's scanner types it as "Mobile Analytics". Expect a Tracking label there.
- **Release cadence.** winget and Uptodown add a step per release (winget can be automated).
  None of this needs a new release.
