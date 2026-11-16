# TV box detection plan (HX78, 2026-11-05)

Generic Android TV boxes (T95, X96, H96, MXQ, Tanix; Amlogic, Allwinner, Rockchip on AOSP tablet
builds) open TMPlayer in the phone layout. Goal: they get the TV layout by themselves where the
signals allow it, and in every other case the user can switch with one remote press or one setting.

## Progress / handoff (read this first)

- Done 2026-11-05 (one Opus session, user away): phases 1 to 5 in `ee0f019` (FormRules + test)
  and `8964eea` (signals, Layout setting, FormRestart `:restart` process, nudge in
  `LayoutNudgeHost` over every screen, uiMode override + landscape lock for forced TV, `tm-form`
  log and report device line). Test APK `~/Downloads/TMPlayer-1.28.4-test-tvbox.apk`
  (com.tmplayer.test, 1280404) for the T95 owner.
- Checked on AVD `tmp_t95` (API 35 google_apis, no accel/touch/battery, D-pad): detected TV
  (`no-accel+notouch+nobattery`), MainActivity uiMode type 4, Layout row and sheet by remote,
  restart to Phone and back, reverse nudge on tap. Phone AVD: D-pad nudge, Switch to TV, landscape.
- Differences from the plan: no separate restart dialog (the sheet's note says it restarts);
  nudge also shows on sign in; null battery broadcast counts as unknown, not "no battery".
- Not done: player with forced TV not opened (needs a Telegram video), idle CPU on the AVD,
  focus unit test, docs/FEATURES.md line, the other 20 locales (user runs langsync).
- Next: the tester's `tm-form` line from Save logs on the real T95; adjust FormRules if it says
  Phone. Model: `/model sonnet`, effort medium.
- Gotchas: `FormFactor` is cached per process and read in `App.onCreate` (Motion.television,
  evenVolume) before any activity, so the override must be readable synchronously there
  (SharedPreferences, like `AppLocales`, not DataStore). `values-television` resources only follow
  the system uiMode.

| Phase | What | Commit |
| --- | --- | --- |
| 1 | Pure classifier + `FormFactorTest` profiles | |
| 2 | Android signal reader, wire into `FormFactor`, startup log line | |
| 3 | Interface setting (Automatic / TV / Phone) + restart | |
| 4 | D-pad nudge in the phone layout | |
| 5 | Forced TV resources (uiMode context wrap) for MainActivity and PlayerActivity | |
| 6 | T95-like AVD, D-pad focus run, idle CPU, Mi TV and phone regression | |

## Why it happens (facts)

- `ui/src/androidMain/kotlin/com/tmplayer/data/FormFactor.kt:43` answers TV only on
  `FEATURE_LEANBACK` or `UI_MODE_TYPE_TELEVISION`. Uncertified boxes report neither.
- About 108 call sites read `FormFactor.isTv/form` (App, MainActivity, player, haptics, DeviceQuirks,
  DeviceFit, CastSupport, FrameRateMatch, DeviceDecode, ...), so one answer change switches the whole app.
- `app/src/main/res/values-television/` (leanback player theme, `dimens_player.xml`) is chosen by the
  system uiMode, not by `FormFactor`.

## Research (2026-11-05)

- No public `pm list features` dump for a T95 (Allwinner H616, Android 10, many board revisions).
- Ugoos AM9 Pro (Android 14) reports `android.hardware.touchscreen` and no leanback
  (github.com/johnneerdael/fdroid-tester/pull/9): the touchscreen **feature** is not trustworthy.
- VLC: `isTv = leanback || (!chromebook && !touchscreenFeature)`, plus a manual "TV UI" preference
  (`showTvUi = !overrideTvUI && device.isTv || tvUI`). A manual switch is industry practice.
- TVSniffer (MIT): no accelerometer means TV ("no TV has one, almost every phone or tablet does").
- Same complaint in other apps: Channels (Dune 4K), Tablo (asks for a leanback toggle).

## Decision rule

```
tv = override ?: auto
auto =
  leanback                                   -> TV   (certified Android TV, Google TV)
  uiMode == TELEVISION                       -> TV
  pc || chromebook (arc)                     -> Phone (desktop-ish Android, keyboard and mouse)
  watch                                      -> Phone (never runs TMPlayer, but never TV)
  !accelerometer && (noTouchInput || noBattery) -> TV
  else                                       -> Phone
```

- `noTouchInput` = `Configuration.touchscreen == TOUCHSCREEN_NOTOUCH` (derived by the window manager
  from real input devices), not `hasSystemFeature("android.hardware.touchscreen")` (faked by box ROMs).
- `noBattery` = sticky `ACTION_BATTERY_CHANGED` with `EXTRA_PRESENT == false`, or no sticky intent.
- `accelerometer` = `SensorManager.getDefaultSensor(TYPE_ACCELEROMETER) != null`.
- Fire OS: `FireOs.detect` already covers Fire TV; keep its result ahead of the heuristic (Fire TV
  has leanback anyway).
- Every read is `runCatching`, a failure counts as "unknown" and leans Phone (today's behaviour).

Why two of three and not one: a cheap tablet can lack an accelerometer (but has touch and a battery);
a box ROM can fake touch or battery (but rarely an accelerometer). The override and nudge cover the rest.

## Phase 1: pure classifier (core or ui commonMain, no Android types)

- `data class FormSignals(leanback, uiModeTv, pc, chromebook, watch, accelerometer: Boolean?, touchInput: Boolean?, battery: Boolean?)`,
  nullable = unknown.
- `object FormRules { fun classify(s: FormSignals): DeviceForm; fun reason(s): String }` (reason for logs).
- `FormFactorTest` (jvmTest), one case per profile:
  - Chromecast with Google TV, Mi TV stick, Fire TV (leanback) -> TV
  - T95 honest (no leanback, notouch, no battery, no accel) -> TV
  - T95 faking touch feature but notouch config, no accel -> TV
  - Ugoos-like (touch config reported, battery absent, no accel) -> TV
  - box faking touch config and battery, no accel -> Phone (override/nudge case, documented)
  - phone, tablet, tablet without accelerometer (touch + battery) -> Phone
  - tablet with BT keyboard -> Phone
  - Chromebook (arc), Android desktop (`type.pc`), Waydroid (pc-like, no accel, mouse) -> Phone
  - all unknown -> Phone
- STOP: `./gradlew :ui:jvmTest --tests '*FormFactor*'` (or the module the rules land in), commit.

## Phase 2: Android signal reader

- `FormSignalsAndroid.read(context)` in `ui/src/androidMain/.../data/`, each signal in `runCatching`.
- `FormFactor.compute` = override (Phase 3) ?: `FormRules.classify(read(context))`.
- Log once at start: `tm-form: tv=true reason=no-accel+notouch override=none` so Send logs from a box
  reporter shows why. Add the same line to the log export header if one exists (grep `LogActions`).
- Check the callers that assumed "TV means certified Android TV" and must keep the old test:
  - `TvChannelsPublisher` already checks `FEATURE_LEANBACK` itself: keep.
  - `DeviceQuirks.remote` / `RemoteQuirks`: review keys on AOSP boxes (Back, Menu, Info) with the
    generic remote; no box-specific change unless a test shows one.
  - `FrameRateMatch`, `DeviceDecode`, `CastSupport`: confirm nothing calls an Android TV only API
    behind `isTv` without an SDK or feature guard (Android 6.0 floor, `LegacyApiCompat.kt`).
- STOP: unit tests, `./gradlew :app:assembleDebug`, commit.

## Phase 3: Interface setting

- Storage: SharedPreferences `form_prefs`, key `interface` = `auto|tv|phone`, read synchronously in
  `FormFactor.compute`. Not in the settings backup and `allowBackup=false` already: a phone restoring a
  TV box backup must not get the TV layout.
- `FormFactor.override(Boolean)` stays for the promo fixture; the user override is a separate input.
- Settings row (find the display or general group in `SettingsGroups.kt`; phone and TV both):
  "Interface: Automatic (TV) / TV / Phone". Shows what Automatic picked. Shown on every Android device
  (a tablet owner may want TV, a box may be misdetected as either way).
- Changing it: dialog "TMPlayer restarts to switch layouts" with Restart now / Cancel, then restart
  the task (`Intent.makeRestartActivityTask` + `Runtime.exit` after the prefs `commit()`), because
  `Motion.television`, `SettingsDefaults.evenVolume` and the player's leanback fragment are decided at
  process start. Player not running at that moment (the setting lives outside the player).
- Escape hatch on the wrong layout: the setting must be reachable with a D-pad in the phone layout and
  by touch/mouse in the TV layout. Verify both with a focus test (Phase 6).
- Strings in `en.json` (keys sorted): `settings_interface`, `settings_interface_auto`,
  `settings_interface_tv`, `settings_interface_phone`, `settings_interface_restart_title`,
  `settings_interface_restart_body`, `settings_interface_restart_now`. No langsync (user runs it).
- Desktop and web: no setting (desktop has its own layout, web decides by viewport). Note in
  `docs/FEATURES.md` that the switch is Android only.

## Phase 4: D-pad nudge

- In the phone layout, `MainActivity.dispatchKeyEvent`: the first `DPAD_UP/DOWN/LEFT/RIGHT/CENTER`
  key with no touch `MotionEvent` seen in this process, and override == auto, and not dismissed
  before -> show a card "Using a remote? Switch to the TV layout" with Switch / Not now / Never.
- Shown once per process, "Never" stored in `form_prefs`. Not shown when a hardware keyboard is
  attached and touch was used (tablet with keyboard), nor on a pc/chromebook.
- The card takes focus so the remote can answer it at once; Switch goes through the Phase 3 restart.
- Strings: `form_nudge_title`, `form_nudge_body`, `form_nudge_switch`, `form_nudge_later`, `form_nudge_never`.
- Reverse case (TV layout on a touch tablet): a touch on a TV-layout screen when override == auto
  and auto was decided by the heuristic (not leanback) -> same card, "Switch to the phone layout".

## Phase 5: forced TV resources

- `MainActivity` and `PlayerActivity` `attachBaseContext`: when `FormFactor.isTv` and the system
  uiMode is not television, wrap with `createConfigurationContext` setting
  `uiMode = (uiMode and UI_MODE_TYPE_MASK.inv()) or UI_MODE_TYPE_TELEVISION`; the reverse for a forced
  Phone on a real TV. Keep the locale handling `AppLocales` already does (wrap after it).
- Check: leanback `PlaybackSupportFragment` and `values-television/themes.xml` load on the T95 AVD;
  `dimens_player.xml` values match the TV ones; `onConfigurationChanged` does not reset it (re-apply).
- Risk: leanback on a non-TV system has been fine in other apps, but untested here. If it breaks,
  fall back to the phone player on forced TV and log it, rather than ship a broken player.

## Phase 6: checks

- AVD "tmp-t95": tablet profile 1920x1080 landscape, API 29, `hw.accelerometer=no`, `hw.screen=no-touch`
  (if the emulator rejects it, `hw.screen=touch` and rely on the battery signal), `hw.battery=no`,
  `hw.dPad=yes`, `hw.keyboard=yes`, no leanback system image (Google APIs tablet). Run headless with
  `-gpu host -no-window`, workspace 10 rule.
- On it: TV layout at first start, D-pad focus test pass (`performKeyInput` `Key.DirectionDown` +
  `assertIsFocused()`), player opens with the leanback row, Settings row reachable, override to Phone
  and back.
- Idle CPU on the AVD after Home (`/proc/<pid>/stat`), as for the Mi TV.
- Regression: Mi TV (leanback, no change expected), phone emulator not needed per memory, phone unit
  profiles cover it. Promo screenshot fixture still forces TV via `FormFactor.override`.
- Manual leftovers for the user: one real AOSP box (T95 or the tester's), with Send logs to read the
  `tm-form` line; an air-mouse remote clicking in the TV layout.

## Edge cases (and where handled)

- Box fakes every signal: nudge (P4) and setting (P3).
- Tablet without accelerometer: touch + battery keep it Phone (P1 test).
- Tablet with keyboard or mouse: touch config present, stays Phone; nudge suppressed after a touch.
- Chromebook, Android desktop, Waydroid: excluded by pc/arc features (P1).
- Override restored from a backup on another device: not backed up (P3).
- Switching while a download runs: the restart dialog says downloads resume (TDLib resumes them);
  check `DownloadService` survives the process restart, else block the switch until it ends.
- Sensor or battery reads throwing on odd ROMs: `runCatching`, unknown leans Phone (P2).
- Overscan on HDMI boxes: TV layout already pads with `Tv.SafeV`; nothing new.
- No Play Services on boxes: cast and anything GMS already guarded (check in P2 caller review).

## Caveats

- Not verified on a real T95; the signals come from other boxes and AOSP defaults.
- A misdetected device still starts once in the wrong layout before the nudge or setting fixes it.
- Changing the layout costs an app restart.
