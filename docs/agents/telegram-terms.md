# Telegram's terms and the stores

TMPlayer stays a client Telegram and the stores have no reason to remove. Check these whenever a
change touches sign in, what is shown from a chat, the manifest, the updater or any store text
(details and sources in `docs/distribution/telegram-apps.md` and `docs/distribution/play.md`):

- **Telegram API terms.** The app's own `api_id` from secrets, never a sample one. No "Telegram"
  in any app or store title and no paper plane logo; "unofficial, not affiliated with Telegram" in
  every description. Telegram's sponsored messages stay shown in channels. Nothing is done on the
  user's behalf without them knowing, and read state is never faked or hidden.
- **Telegram's own restrictions stay on.** Never set TDLib's `ignore_platform_restrictions`, and
  keep `MediaMapper` skipping messages with a restriction reason.
- **A store build has no updater.** Google Play and Amazon get a file without the in-app updater
  and without `REQUEST_INSTALL_PACKAGES`, as the F-Droid build already is; only the GitHub APK
  updates itself.
- **Google Play** wants API 36 as the target for new apps and updates since 2026-08-31 (the app
  targets 35), a declaration for each foreground service type, an AAB, and a demo reviewers can
  use without a Telegram code. It is set aside while the budget is $0 (working plan, Phase G).
- **Amazon** is Fire OS only: the Appstore left other phones on 2025-08-20, and Vega OS sticks run
  neither the APK nor sideloaded apps.
- **Developer verification.** Since 2026-09-30 certified devices in Brazil, Indonesia, Singapore
  and Thailand install only apps from registered developers, everywhere in 2027. Registering
  `com.tmplayer` and the release key (U07, U08, CP15) is what keeps the GitHub APK installable.
