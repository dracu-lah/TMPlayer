# Distribution channels

Drafts for getting TMPlayer listed beyond GitHub and tmplayer.org. Nothing here has been submitted;
each file says what to send and where. Facts were checked on 2026-10-05 against the 1.22.0 release
(`site/latest.json`).

| Channel | Platform | Status | Effort | What you do |
| --- | --- | --- | --- | --- |
| [Obtainium](izzyondroid.md#obtainium) | Android | Works today | None | Optionally add the "Get it on Obtainium" badge to the README and site |
| [IzzyOnDroid](izzyondroid.md) | Android | Ready to request | Low | Decide on the updater (hide it for store installs, or add a sentence), then file the Codeberg issue from the draft |
| [winget](winget.md) | Windows | Manifests ready ([winget/](winget/)) | Low | PR to microsoft/winget-pkgs, or `wingetcreate new <msi url>`; later a release.yml job |
| [Awesome lists](awesome-lists.md) | All | Entries drafted for 7 lists | Low | One PR or issue per list; offa's list after IzzyOnDroid |
| [AlternativeTo](telegram-apps.md#alternativeto) | All | Listing text drafted | Low | Create the entry, mark it as an alternative to Kodi, VLC, Telegram Desktop, Tevegram, Echogram |
| [XDA thread](telegram-apps.md#xda-forums) | Android, desktop | Post drafted | Low | Post it, then answer the thread now and then |
| [Uptodown](telegram-apps.md#uptodown) | Android | Notes | Low, but per release | Developers Console account, upload the APK each release |
| [telegram.org/apps](telegram-apps.md#telegramorgapps) | All | No public path | n/a | Nothing; optional note to @PressBot |
| [Flathub](flathub.md) | Linux | Not advisable now | High | Would need a from-source offline build of the app, TDLib, mpv and FFmpeg |
| [F-Droid main](telegram-apps.md#f-droid-main-repository) | Android | Not recommended | High | IzzyOnDroid covers the same clients |

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
