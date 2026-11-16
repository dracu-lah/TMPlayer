# Haptics plan (2026-10-21)

Branch `worktree-agent-ae79ecf0273775fde`, WIP commit `3b2b901` (helper, tick rewiring, tests).
Done on 2026-10-21 by the Home agent (commit 865446e on its branch): all 16 rows are in. Not added: GESTURE_THRESHOLD_ACTIVATE for pull to refresh.

## Which devices have haptics

| Device | Motor | What we do |
| --- | --- | --- |
| Android phones | yes, nearly all | in scope |
| Android tablets | most (some cheap ones have none: `hasVibrator()` false) | in scope, gated on `hasVibrator()` |
| Android TV boxes, Google TV, Fire TV sticks | none; remotes have no motor either | nothing (TV gate, `FormFactor.isTv`) |
| Gamepads on Android (TV or phone) | some rumble via `InputDevice.getVibratorManager()` (API 31+) | later option, not now |
| Desktop with mouse and keyboard | none | nothing; Compose desktop has no haptics API |
| Desktop with a gamepad | some rumble, needs a native library (SDL, XInput) | not planned |
| macOS Force Touch trackpad | yes, only through AppKit `NSHapticFeedbackManager` | not planned (no Compose API) |

Scope: phones and tablets. Gamepad rumble is a later option (one more branch in `View.haptic`
reading the focused `InputDevice`), not worth it for a video player now.

## The toggle

Already exists: Settings "Vibrate on tap" (`TouchPrefs.haptics`, DataStore key `player_haptics`, in
backups, default on; `App.kt` copies it into `Micro.haptics`, `PlayerActivity` into
`PlayerFeedback.hapticsEnabled`). It already covers the app's toggles as well as the player. No new
storage, string or Settings row is needed, so nothing collides with the agent moving Settings into
the profile. The system's own touch feedback / vibration intensity setting is honoured on top,
because `FLAG_IGNORE_GLOBAL_SETTING` is never passed.

## The helper (done, 3b2b901)

- `ui/src/shared/kotlin/com/tmplayer/ui/components/Haptics.kt`: `enum class Haptic` (Tap, KeyTap,
  LongPress, Confirm, Reject, ToggleOn, ToggleOff, Tick, FrequentTick, GestureStart, GestureEnd) and
  `object Haptics` with `constant(event, sdk): Int?` and `allowed(enabled, television, hasVibrator)`.
  `HapticFeedbackConstants` values mirrored as consts (checked with javap on android-37).
- `ui/src/androidMain/.../HapticsAndroid.kt`: `@Composable rememberHaptics(): (Haptic) -> Unit` on
  `LocalView`, and `View.haptic(event, enabled = Micro.haptics)` for the View based player.
  Vibrator presence asked once (VibratorManager on 31+, Vibrator before).
- `ui/src/jvmMain/.../HapticsDesktop.kt`: `rememberHaptics()` is a no-op.
- `Micro.kt`: `rememberToggleTick()` and `rememberStepTick()` now go through `rememberHaptics()`, so
  every existing caller (star, Watched, Watch later, cast remote toggle, pull to refresh, copies
  sheet, fine speed stepper) gains the vibrator gate and the shared fallbacks.
- `ui/src/jvmTest/.../HapticsTest.kt`: mapping per API level, TV / no vibrator / setting off gates.
  Passes (`./gradlew --max-workers=2 :ui:jvmTest --tests '*HapticsTest'`).
  The Android half (`HapticsAndroid.kt`) is NOT compiled yet: run `:app:compileDebugKotlin`.

### Mapping and fallbacks (minSdk 26)

| Haptic | API 34+ | API 30 to 33 | API 27 to 29 | API 26 |
| --- | --- | --- | --- | --- |
| Tap | VIRTUAL_KEY | same | same | same |
| KeyTap | KEYBOARD_TAP | same | same | same |
| LongPress | LONG_PRESS | same | same | same |
| Confirm | CONFIRM | CONFIRM | VIRTUAL_KEY | VIRTUAL_KEY |
| Reject | REJECT | REJECT | LONG_PRESS (heavier, unlike success) | LONG_PRESS |
| ToggleOn | TOGGLE_ON | VIRTUAL_KEY | VIRTUAL_KEY | VIRTUAL_KEY |
| ToggleOff | TOGGLE_OFF | CLOCK_TICK | CLOCK_TICK | CLOCK_TICK |
| Tick | SEGMENT_TICK | CLOCK_TICK | CLOCK_TICK | CLOCK_TICK |
| FrequentTick | SEGMENT_FREQUENT_TICK | TEXT_HANDLE_MOVE | TEXT_HANDLE_MOVE | CLOCK_TICK |
| GestureStart | GESTURE_START | GESTURE_START | CLOCK_TICK | CLOCK_TICK |
| GestureEnd | GESTURE_END | GESTURE_END | nothing | nothing |

## Audit: what exists, what is missing

Already present (keep):
- Long press menus (`holdable` / `combinedClickable` in Holdable.kt, DownloadsScreen RowCard,
  Series, EpisodesPanel, HomePane, PhoneSearch, MediaGridScreen): Compose foundation plays
  LONG_PRESS itself. Do not add a second buzz.
- Star / Watched / Watch later (MediaGridScreen ~1241, DetailHero ~284, CastRemote ~445): toggle tick.
- Pull to refresh (PullRefresh.kt ~48): tick when the pull passes the release point.
- Player (PlayerFeedback.kt): double tap seek step, hold for 2x start, scrub rate change, via its own
  `haptic(confirm)` (CLOCK_TICK / CONFIRM), gated by `hapticsEnabled`.
- Copies sheet choice (Copies.kt ~364), fine speed stepper (PlayerSheets.kt ~403): tick.

Missing, to add (one small call each: `val play = rememberHaptics()` or the Micro ticks):

| # | Where | Moment | Haptic | Why |
| --- | --- | --- | --- | --- |
| 1 | SettingsScreen.kt `ToggleRow` (~3043): `onClick` and the M3Switch | switch flipped | `rememberToggleTick()(!checked)` | one edit covers every settings switch; Material's Switch is silent |
| 2 | SettingsScreen.kt `TickRow` (~2752) | checkbox ticked | ToggleOn/Off | same |
| 3 | SettingsScreen.kt `StepperRow` touch arrows (~2807) | value stepped | Tick | each step felt, like the speed stepper |
| 4 | SettingsScreen.kt `TouchRangeRow` RangeSlider (~2403) | snapped low/high changes while dragging | FrequentTick only when `SizeFilter.snap(...)` changes | a tick per notch, not per pixel |
| 5 | ui ChoiceRow.kt `ChoiceRow` (radio, ~67) and ChoicePopover.kt `ChoiceRow` (~179) | a new choice picked (not already selected) | Tick | covers language, subtitle, audio, sort, search sheets; check PlayerSheets does not tick on top |
| 6 | BrowseScreen.kt `TouchFilterChip` (~1896), PhoneSearch.kt FilterChips (~606, ~614) | chip picked / cleared | ToggleOn / ToggleOff | filter chips are toggles |
| 7 | PhoneSearch.kt `SwitchLine` (~650), DownloadsScreen.kt remove after watching row (~1330), TrackPickerFragment.kt background box (~513), SetupChoices.kt `ChoiceSwitch` (~251) | switch / segment flipped | ToggleOn/Off (segments: Tick) | same as settings |
| 8 | DownloadsScreen.kt picking checkbox (~1799) | item ticked in picking mode | ToggleOn/Off | multi select answers the hand |
| 9 | CastRemote.kt Sliders (~328 volume, ~463 seek) | volume step / seek drag | FrequentTick per whole volume step; GestureStart/End for seek | phone as remote, felt without looking |
| 10 | ui lock/PinPad.kt `press` / `delete` (~185, ~191) | digit / delete | KeyTap | a keypad |
| 11 | PinPad.kt `submit` (~140): `chosen == pin`, `PinCheck.Right` | PIN accepted | Confirm | success |
| 12 | PinPad.kt: `lockPinsDiffer`, `PinCheck.Wrong`, `PinCheck.Waiting` | refused | Reject | error felt before it is read |
| 13 | LoginScreen.kt `LoginScreen` (~94): `LaunchedEffect(submitError) { if (it != null) Reject }`, `LaunchedEffect(state is AuthState.Ready) { if (it) Confirm }` | wrong code or number / signed in | Reject / Confirm | the two moments sign in is about |
| 14 | PlayerActivity.kt `lockScreen` (~5124) / `unlockScreen` (~5250) | locked / unlocked | Confirm / ToggleOff via `findViewById<View>(R.id.player_root).haptic(..., touchPrefs.haptics)` | the lock is invisible to a hand on a lap |
| 15 | PlayerFeedback.kt `haptic(confirm)` (~244) | existing player ticks | `root.haptic(if (confirm) Haptic.Confirm else Haptic.Tick, hapticsEnabled)`; hold start GestureStart, hold end GestureEnd | one mapping and the vibrator gate for the player |
| 16 | AccentSwatches.kt | colour picked | Tick | a choice taken |

Decided against (on purpose):
- Ordinary button presses (TmButton, Play, Download, icon buttons, tabs, navigation): no buzz.
  Android and Material do not buzz plain taps; ripple and press scale answer them, and a motor on
  every tap makes confirm and reject mean less.
- Download complete, update ready, anything finishing in the background: no buzz; no finger is on
  the screen and the notification channel vibrates by the user's own settings.
- Swipe actions: none exist (no SwipeToDismiss). If one is added: GestureStart at the threshold,
  Confirm on release past it.
- Player brightness and volume drag: no per step tick (too many); a Tick at 0 and 100 per cent later.
- Gamepad rumble: later option.

## Remaining work (done 2026-10-21)

1. Compile the Android half: `./gradlew --max-workers=2 :app:compileDebugKotlin`, fix anything in
   `HapticsAndroid.kt`.
2. Add rows 1 to 16 (rows 1 to 4 are in SettingsScreen.kt, which the Settings agent is moving:
   keep each to one or two added lines).
3. Rerun `:ui:jvmTest`, the dash grep, then the device pass.

## What the device pass should feel (POCO phone, API 34 constants)

- Settings switches: a crisp click on, a softer one off. Steppers: a tick per step.
- Size range slider: a light tick at each snapped size, nothing between.
- Picking a language, subtitle or sort line: one tick.
- PIN: a key tap per digit, a firm REJECT on a wrong PIN, CONFIRM on the right one.
- Sign in: reject on a wrong code; confirm when it signs in.
- Player lock: confirm on lock, a light tick on unlock.
- "Vibrate on tap" off, or the system's touch feedback off: nothing anywhere. TV emulator and
  desktop: nothing, no crash.

## UX details noticed

- "Vibrate on tap" sits in the player part of Settings but governs the whole app; when Settings
  moves, it belongs with the general touch settings.
- Pull to refresh ticks with SEGMENT_TICK at the threshold; GESTURE_THRESHOLD_ACTIVATE (23, API 34)
  is the more exact constant if it is ever added to `Haptic`.
- The range slider writes the store only on release (right); its tick must follow the local
  snapped value, not the store.
