# TMPlayer plan: the phone player grows up, and the app comes to Windows, Linux and macOS

Date: 2026-10-03. Written against commit 8476456 (release 1.16.0). This is a plan and nothing
else: nothing in it has been implemented, and nothing should be until the user says so. It is
written so that work can start from this file alone after a context reset.

How it was built: a four-way audit (the codebase, the desktop toolchain, the touch conventions of
thirteen mobile players, the keyboard and window conventions of ten desktop players). Every
version number and every "X does Y" below was checked against a primary source on 2026-10-03
unless it is marked *unverified*. The user's pasted gesture blueprint (YouTube style tap, double
tap, hold, swipes) is folded into Part A, with its gaps filled and its unwanted parts listed with
the reason each was dropped.

Scope asked for:

1. The phone player is missing ordinary behaviour: buttons disappear in portrait, a tap does not
   pause, there is no big centre play and pause, and play, pause and seek have no obvious feedback.
   Part A fixes that and reviews the rest of the touch surface against the other players.
2. The next release line should also run on Windows, Linux and macOS. The developer has only a
   Linux machine. Part B is the whole desktop plan: architecture, playback engine, OS integration,
   packaging, signing, testing without the hardware, and a phased delivery.
3. Part C is housekeeping found on the way. Part D lists the decisions that are the user's to
   make. Part E is the status checklist.

Paths are under `app/src/main/java/com/tmplayer/` unless they contain `res/` or start with
another root. Line numbers are at commit 8476456 and will drift.

---

## Summary of what changes, in one screen

**Phone player (Part A)**

- A centre cluster (previous, back 10, play or pause, forward 10, next) appears with the
  controls, the way every phone player draws it. The bottom row shrinks to the five buttons that
  fit a 360 dp portrait screen without scrolling; everything else moves to an overflow menu and a
  top bar. Nothing is hidden by width any more.
- Play and pause get a feedback flash in the centre of the picture, a morphing icon on the button,
  and the paused state pins the controls up (already true). Double taps get the YouTube half-screen
  ripple with accumulating "+20 s". Holding gets a pill that stays while the finger does.
- Single tap stays "show the controls", which every mainstream player does and the pasted
  blueprint insists on, with a new setting *Tap the picture to play or pause* for anyone who wants
  the Telegram Desktop behaviour. (Decision D1 below; the user asked for tap to pause, this is the
  honest reading of the field.)
- New: lock mode, remaining time toggle, start over on resume, next episode overlay before the end,
  48 dp timebar, system gesture exclusion, auto enter picture in picture, haptics, and five player
  settings (double tap seconds, hold speed, tap action, auto hide seconds, gesture switches).
- Removed from the blueprint as unwanted: swipe up to go fullscreen, swipe down to minimise, two
  finger and three finger gestures, seek thumbnails (no trickplay source), and "override the
  system orientation lock" semantics (the rotate button already exists).
- TV is untouched. Every change is gated on the touch form factor.

**Desktop (Part B)**

- The app becomes a Kotlin Multiplatform build: a `core` module (TDLib, repositories, settings,
  models; already nearly platform free), a `ui` module (Compose Multiplatform screens), the
  existing Android app, and a new desktop app.
- TDLib needs no native work: the wrapper already in use, `dev.g000sha256:tdl-coroutines`, ships
  a JVM artifact with TDLib natives for Linux x64 and arm64, macOS x64 and arm64, and Windows x64
  and arm64 (verified by listing the 15.0.0 jar). The app moves from 9.0.0 to 15.0.0 (TDLib 1.8.61
  to 1.8.67).
- Playback is libmpv through `org.openani.mediamp` 0.5.0 (Compose Multiplatform 1.12, hardware
  decoding on all three OSes, ASS and PGS subtitles, DTS and TrueHD, a seekable custom byte source
  for the growing TDLib file). Media3 stays on Android untouched.
- Builds and installers: GitHub Actions on the three runners, signed and notarized through
  Hydraulic Conveyor (free for GPL projects) so nothing needs a Mac or a PC on the desk.
- Delivery is phased: Linux alpha first, Windows and macOS from CI with rented macOS hours before
  each release, then OS integration (media keys, sleep inhibit, deep links). Realistic effort is
  eight to twelve weeks of focused work; see B9 for the breakdown and D3 for the release train.

---

# Part A: the phone player

## A0. What exists today (audit)

Gestures (`player/PlayerGestures.kt`, phone only, fed from `PlayerActivity.dispatchTouchEvent`
at 634):

| Gesture | Today | Where |
|---|---|---|
| Single tap | Toggles the controls (`onSingleTapConfirmed`, 100) | works |
| Double tap, left third | Back 5 s (`Skip.BACK_MS`), HUD "◀◀ 5 s" | works, but the rewind button says "10" and "Back 10 seconds" (`player_controls.xml:153,172`) |
| Double tap, right third | Forward 10 s | works |
| Double tap, middle third | Play or pause, HUD "❙❙" or "▶" for 900 ms | works, feedback is a small text chip |
| Long press | 2x while held, only when the controls are hidden; HUD "2x ▶▶" vanishes after 900 ms while the finger is still down | works, feedback wrong |
| Horizontal drag | Scrub, full width = 3 min, seek on release, HUD "1:23:45 +30s" | works |
| Vertical drag, left 3/7 | Brightness, HUD "☀ 63%" | works |
| Vertical drag, right 3/7 | Volume, HUD "♪ 63%" | works |
| Pinch | Fit, Crop, Stretch, one stop per pinch | works |
| Keyboard, D-pad | handled (`handleTouchDeviceKey`, 1694) | works |

Overlay (`res/layout/player_controls.xml`, `player/PlayerControls.kt`): one bottom cluster for
phone and TV. Title, subtitle, Media3 `DefaultTimeBar` (26 dp touch height), then one row holding
a `HorizontalScrollView` of nine 48 dp buttons (play or pause, rewind, forward, previous, next,
subtitles, audio, speed, picture shape, rotate) and the "position / duration" text. A wall clock
sits top left; the download chip and rebuffer chip sit top right. There is no centre cluster, no
top bar, no back button, no overflow.

**The portrait bug.** A 360 dp wide phone has about 312 dp for the row after padding. Nine
buttons at 52 dp each need 468 dp. The row scrolls sideways, so speed, picture shape and rotate
are off screen and nothing says so. The comment in the XML calls this deliberate ("a phone held
upright scrolls it"); it is the thing the user is reporting.

**Why the play and pause feedback is weak.** `togglePlayback` (524) shows "❙❙" in the same 16 sp
text chip every other gesture uses. There is no large icon, no animation on the button, and when
the controls are hidden nothing on the picture says the video is paused.

**Orientation trap on the test phone.** The Redmi reports a portrait window while drawing
sideways (see the project memory, and `sheetGoesSideways()` at 2129). Any portrait versus
landscape branch keyed on window shape takes the wrong path there. The layout in A3 is designed
to fit a 360 dp width so that it needs no such branch at all.

Things already right and kept: paused pins the row up; 3.5 s auto hide; 200 ms fades; system bars
ride with the controls; subtitles lift above the row; insets and cutout handled; PiP on home;
audio focus and headset unplug handled; resume positions; episode stepping; the 8 s autoplay
countdown at the end.

## A1. Principles for the touch surface

1. **The first tap is safe.** With the controls hidden, a single tap reveals them and does nothing
   else. This is YouTube, Netflix, VLC, MX, Next Player, Just Player, Jellyfin and the blueprint.
   Pause is then one tap on a 72 dp button in the middle of the screen. The alternative, tap to
   pause, is a setting (D1).
2. **Every gesture answers where the finger is.** Side gestures draw on their side, the centre
   draws in the centre, and the answer stays on screen as long as the gesture does.
3. **One state machine.** Tap, double tap, hold, drag and pinch cannot fire together. The arming
   rules below are explicit so that a wobbling thumb never turns a scrub into a volume change.
4. **Nothing under the system's own gestures.** No horizontal seek may start inside the back
   swipe insets; nothing tappable sits under the bottom home inset; only the timebar strip asks
   for gesture exclusion.
5. **TV is not touched.** Every change here is behind `FormFactor.isTv(context)` being false. The
   TV key model settled in 1.16.0 (instant arrows, OK opens the row on the bar, digits) stays.

## A2. The gesture map, final

| Gesture | Zone | Action | Feedback | Status |
|---|---|---|---|---|
| Single tap | anywhere | Toggle controls. Ignored while a double tap counter is live. With *Tap to play or pause* on: play or pause, and the controls do not toggle | Controls fade 200 ms; with the setting on, the centre flash (A4.1) | change |
| Double tap | left 35 % | Seek back N s (setting, default 10) and start or extend a counter | Half oval ripple on the left, three chevrons animating in sequence, label "N seconds" that counts up on each tap: 10, 20, 30 | change |
| Double tap | right 35 % | Seek forward N s | Mirror of the above | change |
| Double tap | centre 30 % | Play or pause | Centre flash (A4.1) | keep |
| Triple and further taps | same side within 750 ms of the last | Add N s each, keep the counter alive; a tap on the other side resets the counter | Label updates in place | new |
| Long press | anywhere on the picture, controls up or down, but not on a button | Hold speed (setting, default 2x) while the finger is down; release restores the chosen speed | Haptic tick on arm; pill at top centre "2x" with a small ▶▶ that stays until release; pill fades 300 ms after release | change (today only with controls hidden, and the pill vanishes) |
| Horizontal drag | anywhere except inside the left and right system gesture insets | Scrub. Full width = 3 min, applied on release (keeps TDLib's download window still) | Centre HUD "+0:30" over "1:23:45 / 2:10:00", and the timebar alone peeks at the bottom with the scrubber moving, so the viewer sees where in the film they are going | change (HUD and bar peek) |
| Vertical drag | left 3/7 of the width | Brightness 2 % to 100 % | A vertical pill on the left: icon at the bottom, bar, percentage on top; 1 s linger | change (bar instead of text) |
| Vertical drag | right 3/7 | Volume 0 to 100 % | Same pill on the right | change |
| Vertical drag | centre 1/7 | nothing | | keep |
| Pinch | anywhere | Fit, Crop, Stretch, one stop per pinch | Label of the stop, centre, 900 ms | keep |
| Back (system) | | Controls up: hide them. Otherwise leave | | keep |
| Lock | overflow menu item, and a button in the landscape bottom row | Every gesture and every button is off except a single unlock pill; the screen orientation freezes; after 2.5 s the pill fades and a tap brings it back | "Locked" toast; the pill reads "Tap to unlock" | new |

Numbers: double tap zones 35 / 30 / 35 (Next Player and Just Player; today thirds). Counter
window 750 ms (VLC, Next Player). Arming for drags stays at 4 % of the view height, direction lock
stays at 2:1. Hold arms at the system long press timeout (about 400 ms); a drag that starts
before the hold arms cancels the hold, a hold that armed ignores later movement.

Defaults chosen against the field: double tap 10 s both ways (YouTube, Netflix, Telegram, VLC,
Next Player, Just Player, Jellyfin, Infuse; only Google Photos uses 5 s). Today the phone goes
back 5 s because it shares `Skip.BACK_MS` with the TV's instant arrow. The phone gets its own
value from the new setting; the TV constants do not change (the TV model was settled by the user
in 1.16.0 and this plan does not reopen it).

### The state machine (what the pasted pseudocode becomes)

```
Idle
  down            -> Pressed(x, y, t)
Pressed
  up before 300ms -> if a tap is pending on the same side within 750ms: DoubleTap(side)
                     else: PendingTap (resolves to SingleTap after the double tap timeout)
  move > arm      -> classify: |dy| > 2|dx| -> VerticalDrag(side by x), else HorizontalDrag
                     (start inside a system gesture inset and horizontal -> Idle, let the system have it)
  second finger   -> Pinch (cancels everything else)
  held > longPress and no move -> Holding
DoubleTap(side)
  side tap again within 750ms -> counter += 1, retrigger the ripple
  750ms silence   -> Idle
Holding
  up              -> restore speed -> Idle
VerticalDrag / HorizontalDrag
  up or cancel    -> commit (seek) -> Idle
Locked
  everything      -> show unlock pill, swallow
```

Already true in `PlayerGestures`: the classification is measured from the down point, not
summed; `onSingleTapConfirmed` already waits out the double tap window. What is new is the side
counter, the lock state, the hold working with controls visible, the inset check, and the
"ignore the single tap while a counter is live" rule.

## A3. The overlay, laid out so that nothing is ever missing

One layout, drawn to fit a 360 dp width, so it never has to ask which way the phone is held (the
Redmi would lie). The same composition stretches across a landscape width; two optional buttons
appear in the bottom row when the width allows (`controls_cluster.width >= 560 dp`, measured on
the overlay itself, not on the window).

```
Portrait, 360 dp                          Landscape, 780 dp
┌────────────────────────────┐            ┌──────────────────────────────────────────────────┐
│ ←  Title · S02E04      ⋮   │ top bar    │ ←  Title · S02E04                    10:42    ⋮  │
│                    10:42   │ (clock)    │                                                  │
│                 ▣ 62% 3MB/s│ chip       │                                      ▣ 62% 3MB/s │
│                            │            │                                                  │
│    ⏮   ↺10   ▶   10↻   ⏭  │ centre     │          ⏮     ↺10     ▶     10↻     ⏭          │
│                            │            │                                                  │
│ 0:42              1:30:00  │ times      │ 0:42                                    1:30:00  │
│ ━━━━━━●──────────────────  │ timebar    │ ━━━━━━━━━●───────────────────────────────────── │
│ [CC] [♪] [1x] [⤢] [⟳]      │ bottom row │ [CC] [♪] [1x] [⤢] [⟳]            [🔒] [⧉]        │
└────────────────────────────┘            └──────────────────────────────────────────────────┘
```

**Top bar** (new, 56 dp, under the status bar inset): back arrow (finishes the activity, the way
every phone player has one; today the only way out is the system back), title and subtitle on
one or two lines, the wall clock (moves here from the floating top left), and an overflow button.
The download chip and the rebuffer chip stay in the top right corner under the bar; they already
show only with the controls.

**Overflow menu** (new, a Material 3 dropdown on the phone): Lock controls, Picture in picture,
Open in another app (today reachable only through a key long press), Playback speed as a list
(0.5 to 2.0, the same seven stops as `PlaybackSpeed`), Start over, Playback details (resolution,
codec, bitrate, from `StreamStats`, cheap to show here even though the TV panel was removed).

**Centre cluster** (new, phone only): previous episode (48 dp, hidden until episodes are found,
as today), back 10 (56 dp, with the figure inside the glyph as today), play or pause (72 dp
filled circle, 40 dp glyph), forward 10 (56 dp), next episode (48 dp). Gaps 24 dp. The buffering
spinner replaces the play glyph inside the same circle while the player is buffering, which is
what Next Player and Media3's own control view do. The cluster is vertically centred on the
picture, the scrims are the existing top and bottom gradients plus a 30 % black overall tint
while the controls are up (Next Player), so white glyphs read on a bright frame.

**Times line** (moved): elapsed left, duration right, above the bar, the way YouTube and Next
Player draw it. Tapping the duration toggles remaining time ("-1:29:18"), persisted
(`SettingsStore.showRemainingTime`). This removes "position / duration" from the button row,
which is what made the row too wide.

**Timebar**: stays Media3's `DefaultTimeBar` (it draws the buffered run, which matters here).
`touch_target_height` goes from 26 dp to 48 dp, bar height 4 dp, scrubber 12 / 18 dp as today.
The bar's strip is registered with `setSystemGestureExclusionRects` on API 29+ so a drag that
starts near the left edge of the bar does not become a system back.

**Bottom row**: subtitles, audio, speed (the chip shows the current value, "1x", and a tap
cycles as today; the long list lives in the overflow), picture shape, rotate. Five 48 dp buttons
with 4 dp gaps is 256 dp, which fits 312 dp with room. When the row is at least 560 dp wide, lock
and picture in picture join on the right. The `HorizontalScrollView` stays as a safety net for a
280 dp screen but is never expected to scroll.

**What leaves the bottom row**: play or pause, rewind, forward, previous, next (to the centre),
the time text (to the times line). The rotate button keeps its three state icon.

**TV**: the TV keeps `controls_cluster` exactly as it is: title, bar, the nine button row, time
text. The new views (top bar, centre cluster, times line, overflow) are `gone` when
`FormFactor.isTv`. The one overlay for both form factors stays one file; the phone and the TV
show different parts of it. `PlayerControls.focusRow()` and the D-pad focus chain are not
touched.

**Insets and cutouts**: the top bar pads by `systemBars ∪ displayCutout` top; the bottom row by
the bottom inset; the cluster by the side insets, all through the existing `insetTheControls`
listener (494). Android 15 forces edge to edge and cutout mode always for `targetSdk 35`, which
the activity already handles.

## A4. Feedback, so the viewer always knows what just happened

### A4.1 Play and pause

- **Centre flash**, phone only: a 72 dp translucent circle with the play or pause glyph scales
  from 0.8 to 1.0 and fades out over 400 ms in the middle of the picture, every time playback
  toggles by a gesture, a key or a headset button while the controls are hidden. YouTube and
  Google Photos do exactly this. With the controls up, the big centre button is itself the
  feedback.
- **The button morphs**: play to pause and back through an `AnimatedVectorDrawable` path morph
  (two AVDs, one per direction, built with Shape Shifter; Material Symbols are static so they are
  drawn once as paths with equal command counts). 250 ms. Applies to the centre button on the
  phone; the TV row keeps its two static Solar glyphs.
- **Paused state**: the controls pin up (already), the centre button shows ▶, and the picture
  gets the 30 % tint. If the viewer hides the controls with a tap while paused, a small ▶ pill
  stays in the top right so a paused black frame never looks like a crash (Netflix has no such
  pill; VLC and Next Player keep the controls; this is the compromise for a player whose first
  frame can be a loading sheet).
- **Haptics**: `HapticFeedbackConstants.CONFIRM` on play or pause, `CLOCK_TICK` on each double
  tap step and on the hold arming. A setting switches them off together with the system's own
  haptic setting being respected.

### A4.2 Double tap seek

A half oval the height of the picture and 35 % of its width on the tapped side, white at 20 %
alpha, with a ripple from the touch point (650 ms), three chevrons that light up in sequence
(750 ms cycle, the YouTube pattern, reimplemented not copied), and the label "10 seconds",
"20 seconds", ... under them. The oval and the label stay while taps keep coming and fade 300 ms
after the 750 ms counter expires. The present text chip "◀◀ 5 s" goes.

### A4.3 Hold

A pill at the top centre, "2x" with a small ▶▶, visible for the whole hold and 300 ms after, with
the haptic tick on arm. Today the HUD hides itself after 900 ms while the finger is still down,
which reads as the gesture having ended.

### A4.4 Scrub (horizontal drag)

Centre HUD in two lines: "+0:30" (delta, signed) over "1:23:45 / 2:10:00" (target over duration).
The timebar alone peeks at the bottom while the drag lasts (the rest of the controls stay down),
so the viewer sees the scrubber travel. No thumbnails: there is no trickplay source for a
Telegram file, and generating one would mean decoding the whole download. Dropped from the
blueprint on purpose.

### A4.5 Brightness and volume

A vertical pill, 32 dp wide and up to 200 dp tall, on the same side as the finger (VLC, Jellyfin;
Next Player puts it opposite, both are defensible, same side keeps the "answer where the finger
is" rule): percentage on top, filled bar, icon at the bottom (sun, speaker, muted speaker at 0).
Lingers 1 s after release. Replaces the "☀ 63%" text chip.

### A4.6 Buffering and errors

Mid play rebuffering: the centre spinner inside the play button circle when the controls are up,
and the existing rebuffer chip top right when they are down. The loading and error sheet is
unchanged.

### A4.7 Next episode, before the end

Thirty seconds before the end of an episode that has a successor, a card slides in bottom right:
the next title, "Next episode in 10", **Play now** and **Hide**. Hiding cancels autoplay for this
episode only. The existing 8 s countdown on the finished sheet stays as the fallback when the card
was hidden or the video ended early. Jellyfin and Netflix both do this; it is the single most
asked for thing in a series player.

### A4.8 Resume

The loading sheet already says "Resuming from 1:02:14". It gains a **Start over** button beside
the resume notice, and the overflow menu gets *Start over* for the whole playback. Today there is
no way to go to zero except dragging.

## A5. System integration gaps on the phone

| Item | Today | Plan |
|---|---|---|
| Picture in picture entry | `onUserLeaveHint` | Android 12+: `setAutoEnterEnabled(true)` while playing, cleared while paused, plus `setSourceRectHint` from the video view's bounds so the transition is the picture and not a grey box; keep `onUserLeaveHint` for API 26 to 30. Actions: play or pause, back 10, forward 10, within `getMaxNumPictureInPictureActions()`; the media session already supplies the rest |
| Background audio | none (`onStop` pauses) | Backlog: a setting *Keep playing audio in the background* that moves the player into a `MediaSessionService` with `foregroundServiceType="mediaPlayback"`. Not in this release: the series player use case is video, and a service changes the lifecycle of everything in `PlayerActivity` |
| Headset unplug, audio focus, keep screen on | handled | keep |
| Orientation | Follow, Landscape, Portrait cycle; portrait videos switch to Follow | keep; add that lock mode freezes the current orientation. Note for later: on Android 16 with `targetSdk 36`, orientation requests are ignored on screens 600 dp and wider, so the button becomes a phone only tool and tablets rely on resize mode |
| System gesture conflicts | nothing | timebar exclusion rect (A3), horizontal seek refused inside the left and right `systemGestures` insets, nothing tappable under the bottom inset |
| Media session | in the activity | keep; the centre cluster's play or pause shares the same `togglePlayback` so the notification, the lock screen and the picture agree |

## A6. Settings (new section *Player* in `SettingsScreen.kt`, phone only unless noted)

| Setting | Values | Default | Key |
|---|---|---|---|
| Tap the picture | Shows the controls / Plays or pauses | Shows the controls | `player_tap_action` |
| Double tap to seek | 5, 10, 15, 30 s | 10 | `double_tap_seconds` |
| Hold to speed up | Off, 1.5x, 2x, 3x | 2x | `hold_speed` |
| Hide the controls after | 2, 3.5, 5, 8 s, Never | 3.5 | `controls_timeout_s` |
| Swipe gestures | Seek, Brightness, Volume, each a switch | all on | `gesture_seek`, `gesture_brightness`, `gesture_volume` |
| Remember brightness | on/off: reapply the last in player brightness next time | off | `remember_brightness` |
| Haptics | on/off | on | `player_haptics` |
| Show remaining time | toggled from the player, listed here for completeness | off | `show_remaining_time` |

Playback speed, picture shape and orientation are already persisted. The Settings screen is
Compose and already has the section pattern (`SectionTitle`, switch rows, choice dialogs).

## A7. Implementation plan for Part A

Order chosen so that each step ships on its own and can be screenshot tested on the Redmi.

A7.1 **Fix the row** (one evening). Move play, rewind, forward, previous, next and the time text
out of `controls_buttons`; add `controls_center` and `controls_times` includes to
`player_controls.xml`; wire them in `PlayerControls` (the same lambdas, new view ids); gate the
new views on `!isTv` in `PlayerControls.init`; raise the bar's touch height to 48 dp; add the
exclusion rect in `insetTheControls`. Fix the "10" glyph and label to read the configured value.
TV: no visible change (verify with the `wm size 1920x1080` capture trick).

A7.2 **Top bar and overflow** (one evening). Back arrow, title, clock, overflow with Lock, PiP,
Open in another app, Speed list, Start over, Playback details. `showGestureFeedback` stops being
used for play or pause.

A7.3 **Feedback views** (two evenings). New `player/PlayerFeedback.kt` owning: `CenterFlash`,
`DoubleTapIndicator` (left and right), `HoldPill`, `ScrubHud` (with the timebar peek),
`SideLevelPill` (brightness and volume). All plain Views over `player_root`, drawn above the
overlay and below the status sheet. `gesture_hud` keeps serving the TV and the remaining text
cases (digit jumps, scale and speed labels). The AVD morph for the centre button.

A7.4 **Gesture state machine** (two evenings). Extend `PlayerGestures`: tap action setting,
side counters with the 750 ms window, hold with controls visible, the inset refusal, the lock
state, haptics. Unit test the classifier by feeding synthetic `MotionEvent`s (Robolectric is not
in the project; a thin interface over the detector callbacks makes the state machine testable on
the JVM, which also means it can later be shared with desktop's mouse model).

A7.5 **Lock mode, Next Up card, Start over, remaining time, PiP auto enter, settings**
(two evenings). Each is small and independent.

A7.6 **Screenshots and docs**. New phone screenshots through the promo build, WebP at quality 88
per CLAUDE.md, README and site updated, FEATURES.md "Is a phone app on a phone" paragraph updated,
INSTALL.md gesture table.

Verification: on the Redmi over adb (taps are unreliable there; verify the layout by screencap
and the gestures by hand). Items to check by eye: no row scrolls in portrait; the centre cluster
clears the subtitle area; the top bar clears the hole punch; lock mode swallows everything; the
TV capture is pixel identical to 1.16.0 for the controls.

## A8. What was dropped from the pasted blueprint, and why

| Blueprint item | Decision | Reason |
|---|---|---|
| Swipe up = fullscreen, swipe down = exit fullscreen or minimise to a mini player | Dropped | The player is already a fullscreen activity with no inline mode and no mini player; vertical swipes are brightness and volume, which every third party player maps that way. The rotate button and "follow the video" cover orientation |
| Thumbnail preview while scrubbing | Dropped | No trickplay or storyboard exists for a Telegram file; making one means decoding the download |
| "Respect the system orientation lock, provide a button to override" | Already there | `control_rotate` cycles Follow, Landscape, Portrait; Follow uses `FULL_USER`, which respects the system lock |
| Vibrate on chapter markers | Dropped | No chapters are read from the container today; backlog item "chapters from MKV" would bring it |
| Two finger and three finger gestures (YouTube chapters, MX speed, VLC screenshot) | Dropped | No player agrees on them and they collide with pinch |
| 2x speed only (fixed) | Setting | VLC, Jellyfin and Next Player make it configurable; 2x stays the default |
| Single tap must never pause | Kept as default; setting for the other | D1 |
| Double tap left = rewind 10 | Adopted (today 5) | consensus; D7 |

---

# Part B: Windows, Linux and macOS

## B0. How hard it is, honestly

The 2026-08-11 plan dropped desktop as "feasible but a real project". That is still the right
description; what changed is that two of the three hard parts have been solved by libraries since:

- **TDLib on the desktop JVM is solved.** `tdl-coroutines-jvm` 15.0.0 (Maven Central, Apache 2,
  released 2026-09-04) bundles `libtdjsonjava` for linux/x64, linux/arm64, macos/x64,
  macos/arm64, windows/x64, windows/arm64 (plus OpenSSL and zlib DLLs on Windows), with the same
  Kotlin API the app calls today. The jar is 109 MB because it carries all six. TDLib's file
  download logic (`downloadFile` with offset, `downloaded_prefix_size`, the window move) is the
  same C++ on every platform, so `TdDataSource`'s logic ports unchanged.
- **Video playback with the right codecs is solved, with caveats.** `org.openani.mediamp` 0.5.0
  (2026-09-19) wraps libmpv for JVM on Windows x64 and arm64, macOS x64 and arm64, and Linux x64,
  renders into Skia so Compose controls draw over the video, decodes HEVC, AC3, E-AC3, TrueHD,
  DTS and DTS-HD, renders ASS, SRT and PGS subtitles, has hardware decoding (D3D11VA,
  VideoToolbox, VAAPI), playback speed with pitch correction, and accepts a custom seekable byte
  source (`SeekableInputMediaData`). Caveats: it is pre 1.0; its authors note the Linux runtime
  was "wired in scripts, not yet verified on a Linux host" as of its dependency notes, which is
  exactly the machine the developer has, so it will be verified first; Linux rendering is X11 and
  GLX (XWayland on Wayland desktops); the Linux runtime is GPL 3 because of mpv's X11 code,
  which is fine for a GPL 3 app.
- **The UI is the real work.** The player is Android Views (`activity_player.xml`,
  `PlayerActivity` at 2,645 lines), every screen under `ui/` imports Android, and
  `MainActivity` hand rolls navigation on `BackHandler`. Of roughly 27,000 lines of Kotlin, about
  2,900 are platform free today, and another 900 nearly so (the `ChatRepository` and
  `MediaMapper` only carry `@Immutable`). The data layer is 7,000 lines with 45 Android imports,
  most of them `Context`, `Log`, `Build` and DataStore, which are mechanical to lift.

What this means: the desktop app is a second front end over a shared core, not a port of the
Android front end. The browse and settings screens can be made multiplatform Compose with
moderate effort; the player is a new implementation over a new engine on the desktop and stays
Media3 on Android; the services (downloads, notifications, updates, crash reports) each get a
desktop counterpart or are consciously left out.

Effort, for a single developer who knows the code: 8 to 12 weeks of focused work to a release
that is good on Linux and "works, reviewed by testers" on Windows and macOS. B9 gives the phases.

## B1. Architecture: one core, two front ends

```
settings.gradle.kts
  :core          Kotlin Multiplatform (android + jvm). TDLib client, repositories, models,
                 settings, resume records, cache policy, media naming, fuzzy search, the
                 streaming byte window logic. No UI, no Android types.
  :ui            Compose Multiplatform (android + jvm). Theme, browse, settings, auth, downloads,
                 the shared player overlay composable and the gesture/keyboard state machines.
                 Takes platform services through interfaces.
  :app           The existing Android application (phone + TV). Media3 player, PlayerActivity,
                 DownloadService, notifications, PiP, leanback track picker, Sentry Android.
  :desktop       Compose Desktop application. mediamp/libmpv player, window management,
                 OS services (paths, sleep inhibit, media keys, single instance, deep links),
                 Conveyor packaging.
```

### B1.1 What moves into `:core` (and what it costs)

| Today | Move | Change needed |
|---|---|---|
| `data/Td.kt` (733 lines) | yes | `Context` only supplies `filesDir` and `DiskSpace`: replace with a `Paths` interface (`databaseDir`, `filesDir`, `freeBytes()`); `Build.MODEL` and `VERSION.RELEASE` become `DeviceInfo` (expect/actual); `BuildConfig` API id and hash become a `Credentials` value injected by each app; `Log` becomes a tiny `Logger` facade |
| `ChatRepository`, `MediaMapper`, `AuthReducer`, `SponsoredMessageRepository`, `MediaName`, `Fuzzy`, `SizeFilter`, `ResumeRecord`, `CacheShelf`, `MeteredPolicy`, `LocalFileAvailability`, `ChatSnapshot`, `CardLayout`, `Appearance`, `TdlResultExt`, `Failures` | yes | `@Immutable` comes from `androidx.compose.runtime`, which is multiplatform, so it can stay; `Failures` loses one `Log.w` |
| `SettingsStore` (DataStore) | yes | `androidx.datastore:datastore-preferences-core` is multiplatform; replace the `Context.preferencesDataStore` delegate with `PreferenceDataStoreFactory.createWithPath(okio path)`; the keys and all the `suspend fun set…` stay |
| `player/DownloadWindow`, `StreamStats`, `PlaybackSpeed`, `Skip`, `VideoScale`, `Reload`, `AudioDownmix` | yes | already pure |
| `TdDataSource` | split | the Media3 `BaseDataSource` shell stays in `:app`; the window logic (`awaitBytesAt`, restart rules, the timeouts) moves to `core` as `TdByteWindow`, which both the Media3 source and the desktop `SeekableInputMediaData` call |
| `OfflineDownloads` state flows and queue persistence | yes | the `Intent` sends and `startForegroundService` stay in `:app` behind a `DownloadRunner` interface; the desktop runner is a coroutine in the app process |
| `Updates` (GitHub release polling, `isNewer`, asset selection) | yes | `HttpURLConnection` and `org.json` are JVM; `Build.SUPPORTED_ABIS` becomes a platform string; installing stays per platform |
| `Thumbnails` | split | TDLib minithumbnail bytes decode to `ImageBitmap` through `BitmapFactory` on Android and Skia `Image.makeFromEncoded` on desktop; cache logic shared |
| `NetworkMonitor` | interface | Android actual as today; desktop actual reports "unmetered, online" (Windows has a metered flag, but it is not worth a native call in the first release) |
| `FormFactor` | extend | `Phone`, `Tv`, `Desktop`; the ~30 `isTv` call sites become a `when` only where the desktop differs |

Dependency bump that comes with it: `tdl-coroutines` 15.0.0 depends on Kotlin stdlib 2.4.10, so
Kotlin moves from 2.3.10 to 2.4.x, which drags AGP (8.13.2 is fine with Kotlin 2.4 per the AGP
compatibility table; *unverified* for the exact pair, check at the time) and the Compose BOM.
TDLib 1.8.61 to 1.8.67 carries breaking API changes in several releases between; `Td.kt`,
`ChatRepository`, `AuthReducer` and `MediaMapper` will need named argument fixes. Do this bump
first and alone, ship it as an Android point release, and only then start the module split.

### B1.2 What moves into `:ui`

The Compose screens under `ui/` with these substitutions: `LocalContext` becomes injected
services; `BackHandler` becomes `:ui`'s own `BackStack` with an Android actual that registers a
`BackHandler` and a desktop actual that handles Esc and the mouse back button; `Toast` becomes a
snackbar host; `painterResource(R.drawable…)` becomes `compose.components.resources`
(`Res.drawable…`); `Bitmap` becomes `ImageBitmap`; `Build` based dynamic colour becomes an
`Appearance` actual (desktop: no wallpaper colour, follows system dark mode).

`androidx.tv:tv-material` is Android only. The TV screens keep their tv-material components in
`:app` (they are the 50 `androidx.tv` imports, mostly `BrowseScreen`, `TvMenu`, `TvConfirm`,
`TvSearchField`). The phone screens and the desktop screens share the Material 3 code.

Navigation: keep the hand rolled `Screen` sealed interface; it already works without a NavHost
and that is simpler than adopting `navigation-compose` 2.10.0-beta01 on desktop.

Versions: Compose Multiplatform 1.12.1 (2026-09-22, stable on desktop), which maps to Jetpack
Compose 1.12.1; Material3 on desktop is `1.12.0-alpha03` mapped to Jetpack Material3
1.5.0-alpha22, so any Material3 API the Android screens use must exist there (the screens use
ordinary components, expected fine). Kotlin 2.4.x. JDK 21 to build (jpackage needs 17+; the
project already builds with 21).

### B1.3 The player on desktop

Interface in `:ui`, `PlaybackEngine`: `play`, `pause`, `seekTo`, `speed`, `tracks`,
`selectTrack`, `volume`, `mute`, `state: StateFlow<PlaybackState>` (position, duration, buffered,
playing, buffering, error, video size), `setSource(MediaSource)`. Android actual wraps the
existing `ExoPlayer`; desktop actual wraps `MediampPlayer` from `mediamp-mpv-desktop`.

Desktop byte source: `TdMediaData : SeekableInputMediaData` whose `read(offset, buffer)` calls
`TdByteWindow.awaitBytesAt(offset)` and reads from TDLib's partial file with `RandomAccessFile`,
whose `size` is `file.size` from TDLib, and whose `close` cancels the download the way the
Android source does. mpv probes seekability by seeking to 0 right after open, which the window
already tolerates. Risk to measure early: mpv's demuxer blocking on a slow `read` after a far
seek; mediamp's own source is built for this, and the Android path already shows TDLib can be
re aimed in under a second on a good line.

Rendering: mediamp renders into Skia (Metal on macOS, D3D11 shared texture on Windows with an
OpenGL read back fallback, GLX on Linux). No `SwingPanel`, no heavyweight surface, so the overlay
composable draws over the video without `compose.interop.blending`.

Things Media3 does on Android that the desktop engine does differently:

| Topic | Android (unchanged) | Desktop with libmpv |
|---|---|---|
| Codecs | hardware video, nextlib FFmpeg for DTS, TrueHD, E-AC3 | mpv's bundled FFmpeg does everything; hwdec `auto-safe` by default, a setting to force software |
| Subtitles | own `SubtitleView`, PGS and VobSub | mpv renders in frame (libass); style settings map to `sub-font-size`, `sub-color`, `sub-border-size` |
| Audio downmix | `AudioDownmix` folds for a stick with two speakers | mpv `audio-channels=auto-safe`; a setting *Downmix to stereo* sets `audio-channels=stereo` |
| Speed | 0.5 to 2.0 | mpv allows 0.1 to 100; the UI keeps the same seven stops plus [ and ] fine steps |
| Frame rate match | TV only | none on desktop (compositors handle it) |
| Resize mode | `RESIZE_MODE_FIT/ZOOM/FILL` | mpv `panscan` 0 (Fit), 1 (Crop), and `keepaspect=no` (Stretch) |

Fallback if mediamp fails on Linux and cannot be fixed with a pull request: a thin JNA binding
over libmpv (the official `mpv-examples/libmpv/java` sample is the model) with
`MPV_RENDER_API_TYPE_SW` into an `ImageBitmap`. Simpler, CPU bound, no HDR tone mapping, works
the same on all three OSes. Keep it as the plan B and time box the mediamp trial to one week.

### B1.4 Platform services in `:desktop`

| Service | Linux | Windows | macOS | Library or approach |
|---|---|---|---|---|
| Paths | `$XDG_CONFIG_HOME`, `$XDG_DATA_HOME`, `$XDG_CACHE_HOME` | `%APPDATA%`, `%LOCALAPPDATA%` | `~/Library/Application Support/<id>`, `~/Library/Caches/<id>` | `net.harawata:appdirs`. TDLib database in data, TDLib files in cache with a size cap, settings in config |
| Sleep and screensaver inhibit while playing | D-Bus `org.freedesktop.ScreenSaver.Inhibit`, then `org.freedesktop.PowerManagement`, then `org.gnome.SessionManager` (VLC's order); `xdg-screensaver suspend` fallback | `SetThreadExecutionState(ES_CONTINUOUS \| ES_DISPLAY_REQUIRED)` from one long lived thread (jna-platform `Kernel32`) | `IOPMAssertionCreateWithName(kIOPMAssertionTypePreventUserIdleDisplaySleep)` via JNA on IOKit; `caffeinate -w <pid>` as the fallback | own `KeepAwake` interface; inhibit only while playing, release on pause |
| Media keys and now playing | MPRIS 2 over `dbus-java` (`org.mpris.MediaPlayer2.tmplayer`) | SMTC needs WinRT: a small C ABI helper DLL built on the Windows runner, loaded by JNA; phase 3 | `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter` via JNA `objc_msgSend`; phase 3 | Linux in phase 1 (pure JVM); Windows and macOS in phase 3; JNativeHook is the fallback, not the plan |
| Single instance and `tg:` links | lock file plus local socket (`unique4j`); `.desktop` with `MimeType=x-scheme-handler/tg;` | same socket; registry `HKCU\Software\Classes\tg` | LaunchServices delivers URLs through `Desktop.setOpenURIHandler`; `CFBundleURLTypes` in Info.plist | Conveyor sets `app.url-schemes = [ tg ]` on all three. Accept pasted `t.me` links too. Never claim `https` |
| Dark mode | portal `org.freedesktop.appearance color-scheme` | registry | `NSAppearance` | Compose 1.12 polls the system theme on Windows and macOS; Linux through `jSystemThemeDetector` or the portal |
| Native file dialogs (Save video as) | FileKit 0.16 (`io.github.vinceglb:filekit-dialogs`) | same | same | |
| Notifications (download finished) | `org.freedesktop.Notifications` | AWT tray message or none in v1 | needs a signed bundle | optional; tray icon off by default (GNOME has no tray) |
| Window state | remember floating bounds separately from maximised and fullscreen; validate against current screens on restore | same | keep the window decorated so native fullscreen (Spaces) works; transparent title bar client properties for the look | `rememberWindowState`, serialised on close |
| Fullscreen | `WindowPlacement.Fullscreen` | same; never combine with `undecorated` | AppKit native fullscreen through Skiko | |
| Mini floating player | second undecorated `alwaysOnTop` window with `WindowDraggableArea`, corner remembered | same | same | no OS PiP exists for a JVM app on any of the three; the mini window is what Telegram Desktop ships too |
| Crash reports (opt in) | `io.sentry:sentry` (JVM) | same | same | the options block from `CrashReports.kt` moves to core; `SentryAndroid.init` stays in `:app` |
| Updates | GitHub release check stays (shared code); install via Conveyor (deb and apt repo) | MSIX background updates | Sparkle 2 appcast | Conveyor generates all three; the in app "Update" button opens the download page instead of installing |

## B2. Desktop UX specification

### B2.1 Window and navigation

- Default window 1280 x 800, minimum 960 x 600, remembered. Dark theme follows the system; the
  Settings theme choice (already a setting) overrides.
- Left sidebar above 1000 dp width (Chats, Favourites, Continue watching, Downloads, Settings),
  icon rail below. Grid columns from `GridCells.Adaptive` on a poster width, with a poster size
  step in the toolbar. Vertical scrollbar through `rememberScrollbarAdapter`.
- The player replaces the browse content in the same window (Netflix, Plex, Jellyfin, Stremio all
  do this); a pop out button opens the mini window. Leaving the player returns to the page and
  scroll position it came from.
- Esc ladder: exit fullscreen, then clear search if the field has focus, then back. Backspace,
  Alt+Left (Windows, Linux), Cmd+[ (macOS) and the mouse back button also go back.
- Hover on a tile: 200 to 400 ms intent, 1.05 scale, a Play button and a "..." overflow. Right
  click (Ctrl+click on macOS) opens the same overflow: Play, Resume, Play from start, Mark as
  watched, Download, Remove download, Copy Telegram link, Open in Telegram, Info.
- Search: "/" and Ctrl+F (Cmd+F) focus the field, search as you type with the existing fuzzy
  matcher. Settings: Ctrl+, (Cmd+,) and the macOS app menu. "?" opens the shortcut sheet.
- Keyboard in grids: arrows move a cell, Home and End the row, Page Up and Down a page, Enter
  opens, Space or P plays, no wrap (WAI-ARIA grid). Off screen lazy items are not focusable, so
  selection is index based with `animateScrollToItem` then `requestFocus()`.
- macOS menu bar: TMPlayer (About, Settings Cmd+,, Hide, Quit), File (Open Telegram link),
  Playback (the shortcut table), View (Enter Full Screen Ctrl+Cmd+F, Always on Top), Window
  (Minimize, Zoom, Mini player), Help. Built with Compose `MenuBar`; `java.awt.Desktop` handlers
  for About, Preferences and Quit.
- First launch: QR code first (the phone's TDLib `requestQrCodeAuthentication` flow already in
  `AuthReducer`), with "Log in by phone number" underneath, then the existing 2FA screen. No
  onboarding carousel on desktop; the overview screen is a Help menu item.

### B2.2 Player keyboard

Primary keys follow the YouTube and Jellyfin letters (browser muscle memory); mpv and VLC
punctuation works as silent aliases; all of it is ignored while a text field has focus.

| Action | Primary | Aliases |
|---|---|---|
| Play or pause | Space, K | P, Enter, media Play/Pause, single click |
| Seek 5 s | Left, Right | |
| Seek 10 s | J, L | Alt+Left/Right, media Rewind/FastForward |
| Seek 60 s | Shift+Left, Shift+Right | Ctrl+Left/Right |
| Seek 5 min | Ctrl+Alt+Left/Right (Cmd+Shift+Alt on macOS) | |
| Frame step while paused | , and . | |
| Jump to 0 to 90 % | 0 to 9 | Home, End |
| Volume 5 % | Up, Down | 9 and 0, wheel |
| Mute | M | |
| Fullscreen | F | F11, Alt+Enter, Ctrl+Cmd+F, double click |
| Exit fullscreen | Esc | |
| Subtitles next / previous / toggle | S / Shift+S / C | V, J (mpv) |
| Audio track next / previous | A / Shift+A | B, # |
| Speed up / down / reset | ] / [ / Backspace (only when speed is not 1) | > <, + - |
| Next / previous episode | Shift+N / Shift+P | Page Down / Page Up |
| Always on top | Ctrl+T (Ctrl+Cmd+T) | T |
| Mini player | Ctrl+P (Ctrl+Cmd+P) | I |
| Stats | I | Shift+I |
| Back to browse | Esc when not fullscreen, Backspace | Alt+Left, Cmd+[, mouse back |
| Quit | Ctrl+Q (Cmd+Q) | |
| Shortcut sheet | ? | |

Hardware media keys arrive through MPRIS, SMTC and Now Playing (B1.4); a global key hook is not
used.

### B2.3 Player mouse

- Single click on the video: play or pause, deferred 300 ms so a double click does not flicker
  (Jellyfin, Stremio, Telegram Desktop). This is the desktop convention and differs from the phone
  default on purpose: a mouse has no accidental taps.
- Double click: fullscreen. Wheel: volume 5 % (setting to make it seek); horizontal wheel or
  Shift+wheel: seek 10 s. Right click: the same menu as the overflow button (play or pause,
  audio, subtitles, speed, picture shape, fullscreen, always on top, copy link, show in chat,
  download). Drag a subtitle file onto the video to load it. Mouse back button: back.
- Cursor and controls hide together 3 s after the last movement in fullscreen; windowed, the
  controls hide but the cursor stays. Hovering the timebar shows the time at the cursor. No
  thumbnails (same reason as the phone).

### B2.4 Player overlay on desktop

The same composable as the new phone overlay (A3) with desktop additions: a volume button with a
hover slider at the bottom right, fullscreen and mini player buttons, the download chip in the
same corner, no rotate button, no brightness. The centre cluster shows on hover and on pause. The
Next Up card, start over, remaining time toggle, lock (as "ignore clicks on the video", rarely
wanted, lives in the overflow) all carry over.

### B2.5 Feature parity, feature by feature

| FEATURES.md item | Desktop plan |
|---|---|
| QR login, phone login, 2FA | shared `AuthReducer`; QR drawn from zxing (pure Java) into an `ImageBitmap` |
| Chats, media only view, thumbnails, infinite scroll | shared repositories; Skia decodes the minithumbnails |
| Streams while downloading, seek re aims | `TdByteWindow` plus `TdMediaData` |
| Download the whole video first | same setting, same TDLib call |
| MKV, MP4, AVI, TS; PGS, VobSub; DTS, TrueHD, E-AC3 | libmpv; also HDR tone mapping, which Android does not do |
| Episode names, previous and next, autoplay countdown | shared `MediaName`; Next Up card |
| Resume positions, Continue watching, favourites, last chat | shared `SettingsStore` |
| One video on the device (TV rule), Downloads screen (phone rule) | desktop keeps everything up to a cache size cap (default 10 GB, setting), shows a Downloads page, and adds *Save a copy to…* through a native dialog since TDLib owns `files_directory` |
| Rows or tiles, size limits, resolution badge | shared |
| Refresh buttons, forgiving search | shared |
| Voice search | not on desktop (no portable speech API); the microphone button is hidden |
| Keeps itself current | GitHub check shared; installation through the OS package (Conveyor) |
| Sign out clears everything | shared; desktop also deletes the TDLib directories |
| Crash reports opt in | Sentry JVM |

## B3. Packaging, signing and CI, from a Linux machine only

### B3.1 What the toolchain can and cannot do

- The Compose Gradle plugin packages `Dmg` and `Pkg` on macOS, `Exe` and `Msi` on Windows, `Deb`
  and `Rpm` on Linux, and only on the matching host: no cross compilation. It does not make
  AppImage or Flatpak (`createDistributable` gives the raw app image to wrap by hand).
- GitHub Actions gives `ubuntu-latest`, `windows-latest` (Windows Server 2025, VS 2026) and
  `macos-latest` (macOS 26 on Apple Silicon since mid 2026). Free for a public repository. Intel
  macOS runners are being retired; an x64 macOS build needs `macos-15-intel` while it lasts, or
  Conveyor (which builds both from Linux).
- **Hydraulic Conveyor** builds and signs everything from Linux: Windows MSIX plus an `.exe`
  installer and background updates, macOS signed and notarized app bundles with Sparkle updates
  (Conveyor refuses to make a DMG by design; it ships a zip and a download page), Linux deb with
  an apt repository plus a tarball. It is free for open source projects under an OSI licence,
  which TMPlayer is. It needs the certificates as secrets, nothing more. This is the recommended
  path (D5); the plugin's own matrix build is the fallback and should be kept working for local
  builds.

### B3.2 Signing

| OS | Without signing | With signing |
|---|---|---|
| macOS | macOS 15 and 26 refuse to open an unsigned or un notarized app ("Apple could not verify…"); the Control click bypass was removed in macOS 15; the user has to go to Privacy and Security, Open Anyway, per app. Unsigned macOS is a support burden, not a release | Apple Developer Program, 99 USD a year, Developer ID Application certificate, notarization through Conveyor or `notarizeDmg` on the macOS runner |
| Windows | SmartScreen "Windows protected your PC", More info, Run anyway on every machine until reputation accrues; corporate policy may block | Azure Artifact Signing (formerly Trusted Signing) about 10 USD a month, individuals only in the USA and Canada, organisations in EU and UK too; or an OV certificate 150 to 300 USD a year on a hardware token; or SignPath Foundation, free for open source projects on application. EV no longer skips SmartScreen |
| Linux | nothing required | apt repository signing key, generated by Conveyor |

Decision D4 asks whether to pay for Apple. Recommendation: yes before the first public macOS
build; ship Windows unsigned at first with the SmartScreen steps in INSTALL.md and apply to
SignPath Foundation in parallel.

### B3.3 CI shape

- `ci.yml`: add `:core:jvmTest`, `:ui:jvmTest`, `:desktop:packageDistributionForCurrentOS` on the
  three runners as a matrix (smoke: the app starts headless with `-Dcompose.application.…`, loads
  TDLib, prints its version, runs a 10 s software decode of a bundled test clip). macOS runners
  have no GPU to rely on; hardware decoding is tested by people, not CI.
- `release.yml`: on a `v*` tag, build the Android APKs as today, then one job on
  `ubuntu-latest` running Conveyor with the signing secrets, uploading installers for the three
  OSes to the same GitHub release, plus `SHA256SUMS`. Version comes from the tag as today.
  Corresponding source: the release already ships nextlib's; add the mediamp runtime build
  scripts and the libmpv, FFmpeg, libass and libplacebo source tarballs for the Linux GPL runtime
  (a `desktop-corresponding-source-<v>.tar.gz`), and extend `THIRD_PARTY_NOTICES.md`.
- Installer size estimate: jlinked JRE 60 to 70 MB, Compose and app 20 MB, libmpv runtime 40 to
  60 MB, TDLib natives 30 to 80 MB per OS. Expect 150 to 220 MB installers. The `tdl-coroutines`
  jar carries all six native builds (109 MB); a Gradle task that strips the other platforms'
  entries from the jar before packaging brings each installer down by about 70 MB (phase 2 task;
  unverified that Conveyor's jlink step tolerates a repacked jar, test it).

### B3.4 Testing without a Windows or macOS machine

1. CI on all three runners for every push: packaging, signing, notarization, TDLib native loading,
   JVM start, software decode smoke test, screenshot of the first window uploaded as an artifact
   (Compose Desktop can render headless to a PNG through `ImageComposeScene`).
2. Windows: a licensed Windows 11 VM under KVM on the Linux machine (Microsoft's evaluation ISO
   is free for 90 days) for the real D3D11 and WASAPI path. Wine runs the installer and the JVM for
   a smoke test but is not a release gate.
3. macOS: no legal VM on non Apple hardware. Rent a Mac mini by the hour before each release
   candidate: Scaleway M4 at about EUR 0.22 an hour with a 24 hour minimum (about EUR 5 a day),
   MacStadium or AWS for longer. A used M1 Mac mini pays for itself in a few months if desktop
   becomes a maintained target.
4. Testers: the release page asks for two or three people per OS; a *Playback details* panel in
   the player (resolution, codec, hwdec in use, mpv version, TDLib version) makes their reports
   useful.
5. Everything native goes through one `NativeInventory` that logs on start which libraries loaded
   from where, so a bad report says exactly which piece is missing.

## B4. Licensing

TMPlayer is GPL 3. `tdl-coroutines` is Apache 2 (TDLib itself is Boost). mediamp is Apache 2 with
LGPL 2.1 libmpv and FFmpeg runtimes on Windows and macOS and a GPL 3 runtime on Linux; all
compatible. Obligations: ship corresponding source for the GPL runtime (B3.3), list every bundled
library in `THIRD_PARTY_NOTICES.md`, keep "unofficial" in the app's description wherever the
Telegram API terms ask for it (already the case).

## B5. Risks and how each is retired early

| Risk | Likelihood | Retire by |
|---|---|---|
| mediamp's Linux runtime does not run on the developer's machine (authors say unverified on a Linux host) | medium | Week 1 spike: a 50 line Compose Desktop app playing a local MKV through `mediamp-mpv-desktop`; if it fails, file upstream and switch to plan B (JNA, software render) |
| libmpv stalls or gives up when `read` blocks across a far seek on a slow line | medium | Week 2: `TdMediaData` against a real channel, measure seek latency, compare with Android |
| Kotlin 2.4 and TDLib 1.8.67 break the Android build | certain, small | Do the bump first, alone, release it on Android |
| Wayland: XWayland rendering is fine but fractional scaling makes the window blurry at 1.5x | medium | Setting to override `sun.java2d.uiScale`; document; wait for JBR Wayland toolkit |
| Material3 alpha on desktop lacks an API the Android screens use | low | Compile `:ui` for jvm in week 3 and fix as found |
| Gatekeeper refuses an unsigned macOS build | certain | Pay for the certificate before the first macOS release, or do not ship macOS |
| Installer size over 200 MB puts people off | medium | Strip foreign natives from the TDLib jar; ProGuard on release (the plugin supports it) |
| Second overlay drifts from the first (the project unified them on purpose in 1.14.0) | medium | The phone overlay in A3 is written as the shared composable from the start; the TV keeps the XML until the composable has proven itself on the phone |

## B6. Phased delivery

**Phase 0, groundwork (1 to 2 weeks).** Bump tdl-coroutines to 15.0.0 and Kotlin to 2.4.x on
Android, release as 1.17.x. Lift `:core` out of `:app` (B1.1) with the Android app unchanged in
behaviour; CI runs `:core:jvmTest`. The mediamp Linux spike (B5 row 1) runs in parallel.

**Phase 1, Linux alpha (3 to 4 weeks).** `:ui` with the browse, settings, auth and downloads
screens compiling for jvm; `:desktop` with window, sidebar, grid, keyboard navigation, the
player over mediamp with `TdMediaData`, the overlay, the keyboard table, MPRIS, sleep inhibit,
paths, single instance. Deb and tarball from CI. Tag `2.0.0-alpha.1`; Linux only, announced as
such.

**Phase 2, Windows and macOS from CI (2 weeks plus testing).** Conveyor configuration, signing
secrets, installers for all three in the release, the native inventory, the smoke tests on the
three runners, a rented macOS day. Tag `2.0.0-beta.1`.

**Phase 3, OS integration and polish (2 weeks).** SMTC and Now Playing helpers, `tg:` links,
dark mode on Linux through the portal, mini player, notifications, drag and drop, the shortcut
sheet, Playback details, size trimming. Tag `2.0.0`.

**Phase 4, after release.** Flatpak on Flathub (manifest builds libmpv from source; JVM through
the `org.freedesktop.Sdk.Extension.openjdk` extension), AppImage, Intel macOS if asked, the TV
overlay migrating to the shared composable.

Part A is independent of all of this and is scheduled first (D3): it is one to two weeks and the
user is waiting for it.

---

# Part C: housekeeping found during the audit

- `player_controls.xml:153,172`: the rewind button shows "10" and says "Back 10 seconds"; the jump
  is 5 s. Fixed by A7.1.
- `.github/workflows/release.yml` packages NextLib corresponding source for `1.8.0-0.9.0` while
  the app depends on `nextlib-media3ext:1.10.1-0.13.0`. `THIRD_PARTY_NOTICES.md` carries the same
  stale version. Update both to the pinned tag and commit of 1.10.1-0.13.0.
- `activity_player.xml:396` says the gesture HUD is never raised on a TV; TV arrows, digits and
  speed changes raise it. Fix the comment.
- `docs/ARCHITECTURE.md` still describes Media3's leanback transport; `PlaybackSpeed.kt`'s KDoc
  still says the phone uses Media3's settings sheet; `dimens_player.xml` comments mention the
  leanback row. Update after A7.
- The pasted blueprint's "swipe up for fullscreen" assumption suggests the site or README should
  say in one line what the phone gestures are; INSTALL.md has the remote table, it gains the
  touch table.

---

# Part D: decisions for the user

D1. **Single tap.** Default "show the controls" with a *Tap the picture to play or pause*
    setting (recommended, matches YouTube, Netflix, VLC, Telegram's Android app and the pasted
    blueprint), or default "play or pause" (Telegram Desktop, Apple's player for the centre). The
    plan assumes the first.

D2. **Phone overlay technology.** Write the new phone overlay in Compose inside a `ComposeView`
    over the player so that it is the same composable the desktop uses (recommended; the gesture
    code stays in `PlayerGestures`), or extend the XML Views and write the desktop overlay
    separately later. The plan assumes Compose. TV stays XML either way.

D3. **Release train.** Recommended: 1.17.0 = Part A plus the TDLib bump (two to three weeks),
    then the 2.0.0 line for desktop in the phases above. Alternative: one release with everything,
    which means nothing ships for about three months.

D4. **Spend.** Apple Developer Program (99 USD a year) before the first macOS build; Windows
    signing (free via SignPath Foundation if accepted, else about 10 USD a month or an OV
    certificate); a rented Mac day per release candidate (about EUR 5). Say which of the three.

D5. **Packaging tool.** Conveyor (free for GPL, signs from Linux, no DMG, no rpm, no Flatpak) or
    the Compose plugin's own matrix (DMG and rpm, signing only on the macOS runner, more YAML).
    The plan assumes Conveyor with the plugin kept working for local builds.

D6. **Intel macOS.** The runners are Apple Silicon; mediamp and tdl both ship x64 macOS natives,
    so an x64 build is possible through Conveyor. Ship it or not.

D7. **Double tap seconds.** 10 s both ways on the phone (the field), leaving the TV's instant
    arrows at +10 and -5 as settled in 1.16.0.

D8. **Background audio on the phone.** Backlog as planned, or pull it into 1.17.0.

---

# Part E: status checklist (update as work lands)

Decisions taken on 2026-10-03 when the user said "start implementation" without picking from
Part D: the recommended option everywhere (D1 tap shows the controls with a setting, D3 1.17.0
first, D7 10 s on the phone), except D2: the phone overlay extends the XML layout rather than
moving to Compose, because it keeps the television's file and focus chain untouched. The desktop
port will need the overlay as a composable later; that is now a Phase 1 task.

Part A, phone player (commit 81240ba, not yet released, not run on a real phone)
- [x] A7.1 Row fixed: centre cluster, times line, 48 dp bar, exclusion rect, rewind label
- [x] A7.2 Top bar and overflow (back, clock, lock, PiP, open with, speed list, start over, details)
- [x] A7.3 Feedback views: centre flash, morphing glyph (drawn in code, `PlayPauseIcon`, not an AVD), double tap half-moon, hold pill, scrub card (its own bar instead of peeking the real one), side level pills
- [x] A7.4 Gesture state machine: tap setting, side counters, hold with controls up, inset refusal, haptics, JVM tests for zones, counter and settings. Also fixed: a press on a control no longer counts as a tap on the picture
- [x] A7.5 Lock mode, Next Up card, Start over (overflow only, not on the loading sheet), remaining time, PiP auto enter with source rect, Player settings section. "Remember brightness" was not built
- [x] A7.6 FEATURES.md and INSTALL.md touch table. New README and site screenshots not taken (the promo player fixture, `PromoPlayerActivity`, can produce them)
- [x] TV layout checked on an emulator at 1920x1080: unchanged apart from the rewind glyph reading its real 5 s
- [ ] On a real phone: gestures by hand, PiP auto enter, lock, Next Up card at the end of a real episode

Part B, desktop
- [x] Phase 0: tdl-coroutines 15.0.0 and Kotlin 2.4.20 landed (commit 8505a3d; also forced compileSdk 37 with AGP 8.13.2's warning suppressed)
  - [x] Android release: 1.17.0 shipped (commit 9583eed)
  - [x] `:core` extracted (commit de3c623). Kotlin Multiplatform, android target through AGP's `com.android.kotlin.multiplatform.library` plugin plus a jvm target; `commonMain` is plain JVM Kotlin, which a source set shared only by JVM targets may be. Packages unchanged. The Compose compiler plugin is applied (no UI) so the moved models keep the stability `:app` inferred for them. CI and the release workflow run `:core:jvmTest` (241 tests, the 23 test files that covered moved code)
    - Moved as the B1.1 table says: `Td` (through `Paths`, `DeviceInfo`, `Credentials`, `Logger` in `com.tmplayer.platform`), the repositories and models, `Failures`, `SettingsStore` (takes a `DataStore`; Android opens it with `createWithPath` at the delegate's old file, `filesDir/datastore/tmplayer.preferences_pb`), the pure player helpers plus `TouchPrefs`, `DownloadRequest` (its intent half stays in `:app`), `OfflineDownloads` behind `DownloadRunner`, `Updates` (`configure(installedVersion, abis, connectivity)`, `download(release, dir)`; installing stays in `:app`), `NetworkStatus` with a `Connectivity` interface that `NetworkMonitor` implements, `DiskInfo`, and a `DeviceForm` enum (`Phone`, `Tv`, `Desktop`) that `FormFactor.form(context)` answers
    - `TdDataSource` split: `TdByteWindow` in `:core` (window, update subscription, `awaitBytesAt`, re-ask and restart rules, timeouts, reading the partial file); the Media3 `BaseDataSource` shell stays
    - Left in `:app`, all Android bound: `Thumbnails` (B1.1 splits it at the `BitmapFactory`/Skia decode; not needed until `:ui`), `CrashReports` (Sentry Android; B1.4 moves only its options block, with the desktop's Sentry), `WatchCache`, `RoomOnDisk`, `StorageSplit`, `DiskSpace`, `ShareMedia`, `PhoneCountries` and `DownloadService` (Context, `StatFs`, `FileProvider`, `TelephonyManager`, the service). The `isTv` call sites still go through `FormFactor`; turning them into a `when` on `DeviceForm` belongs with `:ui`
  - [x] mediamp Linux spike (2026-10-03, standalone, not in the repo): plan B is not needed. `mediamp-api` and `mediamp-mpv` 0.5.0 from Maven Central; the native runtime (`mediamp-mpv-runtime-<os>-<arch>`) must be named by hand, it is not transitive, and it carries its own libmpv 2.6, FFmpeg, libass and libplacebo (73 MB unpacked on Linux), so no system libraries are used. Plays H.264 and HEVC 1080p MKV, AC3 5.1, embedded ASS in frame, Compose controls over the picture (GLX under XWayland)
    - Growing file at 2 MB/s, seek from 4 s to 45 s: with the window re-aimed at 600 ms latency, 2.4 s to first frame when the MKV Cues sit at the end (two hops: Cues, then target) and 1.8 s when they sit at the front. A read blocked for 75 s resumed 1.1 s after the bytes landed: mpv never gives up
    - Gotchas carried into Phase 1: no buffering signal while a read blocks after a seek (the player keeps its own "seek pending" flag); prefetch the file tail so the Cues hop is not paid on the first seek; `prepareLibraries` into a stable app directory or 73 MB is unpacked into /tmp per launch; `_JAVA_AWT_WM_NONREPARENTING=1` on tiling window managers; hardware decoding fails on Fedora because the bundled libva looks in Debian's driver path (software decode is smooth; report upstream)
  - [x] `:desktop` skeleton (commit 23bfa6d): Compose Desktop 1.12.1 window over `:core`, credentials from `local.properties`, appdirs paths, mediamp runtimes for Linux x64, Windows x64 and both macOS. TDLib loads from the jvm jar on Linux and reaches sign in
- [ ] Phase 1: `:ui` on jvm; `:desktop` browse, player, keyboard, MPRIS, sleep inhibit, paths, single instance; `2.0.0-alpha.1` for Linux
  - [x] `:ui` (commits 16501a7, 1f6d790, 5a91f13): Kotlin Multiplatform, android plus jvm. The shared Compose code sits in `ui/src/shared/kotlin`, compiled into both targets (against the BOM on Android, against Compose Multiplatform 1.12.1 on desktop) so the phone keeps its Compose and Material3 versions; platform halves in `androidMain`/`jvmMain`. Moved: palette and `TmMaterialTheme`, icons, skeletons, `UiState`, `QrCode` and `Thumbnails` (decode split), `MediaArt`, `ChatListViewModel`, `MediaListViewModel`, browse sections and the chat filter. New: `com.tmplayer.ui.nav.BackHandler` (Android wraps the activity's; desktop a `BackStack` for Esc, Backspace, Alt+Left, Cmd+[ and the mouse back button), `rememberToast` (snackbar on desktop), `deviceForm()`
  - [x] Desktop browse shell (b65b5ee): sidebar above 1000 dp, rail below, sign in (QR, phone, code, 2FA), chats, adaptive grid with poster size step and scrollbars, hover lift with Play and overflow, right click menu (Play, Play from start, Download, Remove download, Copy link), Downloads with an in-process `DownloadRunner`, a desktop subset of Settings, Esc ladder, "/" and Ctrl+F, Ctrl+,. The screens are lean desktop copies, not the phone's own: those draw through tv-material and carry Android services, so moving them would have changed the phone and the TV. Drift risk, same as the overlay's
  - [x] Desktop player (commit after b65b5ee, `desktop/.../player/`): `TdMediaData` over `TdByteWindow` with the Matroska tail prefetched, `MpvPlaybackEngine` behind a `PlaybackEngine` interface, own "seek pending" state, the B2.4 overlay, the B2.2 keyboard (where an alias collided with a primary key the primary won: 9 and 0, J, I, Alt+Left), the B2.3 mouse, resume, Next Up and autoplay, per series tracks, speed and shape, Playback details. `:desktop:runPlayerDev --args="--file clip.mkv [--growing 4]"` plays local files through the same path. The deferred items landed on the `desktop-gaps` branch (next line)
  - [x] Player leftovers (`desktop-gaps`): volume, mute and downmix kept across launches in `desktop.properties` (`DesktopPrefs`, beside the shared store, which stays the phone's); a *Mouse wheel seeks* setting (Shift swaps back); *Force software decoding*, on by default on Linux, where the bundled libva looks in Debian's driver path and a driver that does load may be built against another libva, so a silent wrong picture is the risk rather than a visible failure; Windows and macOS keep mpv's `auto-safe` with `hwdec-software-fallback=yes`. On this Fedora machine `auto-safe` also falls back to software, so neither default fails outright. Copy link and Download in the player's menu; a dropped .srt, .ass, .ssa, .vtt or .sub is loaded and selected (`sub-add`; `--sub` in the dev player); the watch cache follows the phone's `WatchCache` rules (`DesktopWatchCache`: the player claims every video it opens and the previous cached one goes, downloads, the queue and open players are spared, a launch sweep takes old strays, Settings has Clear cache). Not B1.4's 10 GB cap: the task asked for the phone's one video rule
  - [x] Mini player (`desktop-gaps`): Ctrl+P, the button and the menu entry shrink the window itself to a 16:9 always on top picture a quarter of the screen wide, bottom right, and back. One window rather than B1.4's second undecorated one, because the libmpv surface would have to move between compositions; `WindowMemory` is frozen meanwhile. Verified in the dev player on sway with the window floating (a tiled window is sway's to size); not seen on Windows, GNOME or KDE
  - [x] OS services (809dbe3, `desktop/.../os/`): `KeepAwake` (D-Bus ScreenSaver, PowerManagement, GNOME SessionManager, then `systemd-inhibit`; Windows `SetThreadExecutionState`; macOS IOKit or `caffeinate`), MPRIS 2 over dbus-java 5.2.2, `SingleInstance` over a Unix domain socket, `WindowMemory`, `NativeInventory`. MPRIS and the sway fallback verified live; Windows and macOS compile only. Known gap: on sway, swayidle's monitor off timeout only obeys Wayland idle inhibit, which an XWayland window cannot send
  - [x] Wired together (dacd298): `PlayerHost` keeps the screen awake while playing and feeds MPRIS; fullscreen through the window; a second launch raises the first; window remembered; the packaged Linux app image starts and reaches sign in
  - [x] CI (0e10b86): `:desktop:test` and unsigned packages on all three runners (deb and tarball, MSI, DMG), green on the first run. The mpv runtime is the host's only
  - [ ] A real account on Linux: sign in, browse, stream, seek, resume, Next Up, downloads; every mouse handler by hand. The handlers themselves are now driven through `PlayerScreen` in a ComposeUiTest over a fake engine (`PlayerMouseTest`): the deferred single click, double click fullscreen, the wheel with and without the setting, controls hiding after 3 s and returning on a move, the timebar's hover time and single seek on release, the volume slider on hover, the right click menu. Found and fixed: the timebar's hover time needed a second movement after the pointer entered
  - [x] Grid arrow key navigation (WAI-ARIA grid, `KeyboardNav`): arrows without wrap, Home and End the row, Ctrl+Home and Ctrl+End the grid, Page Up and Down a screen of rows in the same column, index based focus that scrolls first, a focus ring, Enter, Space or P plays, the context menu key or Shift+F10 opens the overflow; the chat list moves the same way (Enter opens, S stars); Down from a search field enters the results. The keys are read in the preview pass, since the lazy layout's scrolling eats Page Up and Down. `BrowseKeyboardTest` drives it
  - [x] The "?" sheet checked: it is now built per OS (Cmd on a Mac) and per the wheel setting, gained Home and End, and `ShortcutSheetTest` reads every key it names back through `PlayerKeys` and wants a row for every action a key reaches
  - [ ] The thumbnail band seen in the fake data render checked against real thumbnails
  - [x] Hardware decoding default decided: software on Linux by default (above), with the setting to try the graphics card
  - [ ] Report the libva driver path upstream to mediamp
  - [x] Windows correctness pass: `TdByteWindow` (in `:core`, unchanged on Android) and the desktop's range fetch read the partial file through a NIO `FileChannel`, because `RandomAccessFile` on Windows opens without delete sharing and so blocked TDLib's rename of a finished download for as long as the video was open. Links and folders open through `java.awt.Desktop` with `rundll32`, `open` and `xdg-open` behind it (`OpenExternal`); Downloads rows gained Show in folder. Checked and left as they are: `SingleInstance` (AF_UNIX sockets work on Windows 10 1803 and later; a launch that cannot reach the first instance exits quietly), `WindowMemory`'s rename fallback, `NativeInventory`'s `/proc` read (Linux only), D-Bus and `xdg-screensaver` (behind `OsInfo.isLinux`), appdirs paths, extension checks lower cased. Compiled only: nothing has run on Windows yet
  - [x] Update notice: at most once a day, with a switch in Settings, the desktop asks GitHub's latest release and, when it is newer than `BuildInfo.VERSION` (semver order, so 2.0.0 beats 2.0.0-alpha.1) and carries a package for this OS, shows a corner notice with Download (the release page) and Not now. Never installs. Its own small fetch (`DesktopUpdates`) rather than `:core`'s `Updates`, which picks an APK and fails without one
  - [ ] Tag `2.0.0-alpha.1`
- [ ] Phase 2: Conveyor, signing, three OS installers, native inventory, smoke tests, macOS day; `2.0.0-beta.1`
- [ ] Phase 3: SMTC, Now Playing, `tg:` links, Linux dark mode, notifications, size trimming; `2.0.0` (the mini player, subtitle drag and drop and the shortcut sheet landed early, in Phase 1)
- [ ] Phase 4 backlog: Flatpak, AppImage, Intel macOS, TV overlay on the shared composable

Part C
- [x] NextLib corresponding source version in release.yml and THIRD_PARTY_NOTICES.md (v1.10.1-0.13.0, 773c841)
- [x] Stale comments and docs listed above; tdl-coroutines notice moved to 15.0.0 / TDLib 1.8.67
