# Player small features (FQ10, FQ11, TV pause clock, desktop Back)

Written 2026-11-16, before the code. Four small items from the feature queue, each checked against the
feature admission gate. Look first found that three of them already exist, so the work is smaller than the
queue says and mostly a rename, a pin by test and one deletion.

## What Raju hits

- Night mode for sound is called "Night mode". Nobody knows it quiets loud scenes; the name says when, not what.
- On the desktop, Back from a playing video leaves it playing in a bar over the page. People who press Back
  want to leave (T12 decided the same for the phone).
- Pure black and the TV pause clock were asked for as new; both ship already.

## Findings from the code (look before adding)

| Item | State today | Decision |
| --- | --- | --- |
| FQ10 sound | `VolumeBoostProcessor` (Android) has a night path, the More panel has "Night mode" on phone, TV and desktop, stored as `night_mode`, off by default, remembered. Off is a plain copy (no timers, no work). Desktop (mpv) asks for `acompressor` through `af`; the bundled FFmpeg (libavfilter 11.4, mediamp) carries only `aformat`, `anull`, `aresample`: no `acompressor`, `dynaudnorm`, `volume`. Desktop gets the track's own Dolby range control and a dialogue lift on a surround fold. Web has no audio chain. | Rename the visible label to "Quiet loud scenes" (new keys, old ones to DEAD-STRINGS). Keep one switch, one stored value. Pin the off path by test. |
| FQ11 black | Shipped 2026-10-19 as the "Pure black" switch under the theme (not a fourth segment, which was the earlier design and was replaced): phone, TV, desktop, onboarding, web theme list. Contrast and focus ring tests cover light, dark and black for every accent. | Nothing to build. Recorded in FEATURES. |
| TV pause clock | `controls_clock` top start on Android TV, the web TV player draws the same; the row is pinned up for a whole pause so the clock is there. No "ends at" anywhere. | Nothing to build. Pin by test (visible on TV when paused, absent on a phone, no "ends"). |
| Desktop Back | Back docks a playing video into the in-app bar (`MiniPlayerState`, `MiniBarHost`). | Back stops and returns to the opener. |

## The new flow (desktop Back)

Back, Esc, Backspace, Alt+Left, Cmd+[ and the mouse back button from the player: the resume point is saved by
the close path as before, the engine is released, the page that opened the player (detail, chat, Home) is
shown. Ctrl+P (the small always on top window) is unchanged and is not Back. The phone's bar is T12's job and
stays until that lands: `MiniPlayerState`, the shared `MiniPlayerBar` and the Settings row stay for it.

| | Phone | TV | Desktop | Web |
| --- | --- | --- | --- | --- |
| Quiet loud scenes | More panel row, processor | More menu row, focusable, OK toggles | player menu entry, partial (no compressor in this build) | none, no audio chain |
| Pure black | switch under theme | same page, focus ring tested | same | theme list entry |
| Pause clock | none (status bar) | top start, row pinned on pause | none | TV player, same clock |
| Back from player | unchanged here (T12) | unchanged (never docked) | stop, close, opener | unchanged |

## Edge cases

- Desktop Back while buffering, on the failure sheet, ended, asking "Still watching?", casting or locked: all
  of these already closed instead of docking, so the one path is now the only path. Locked still refuses Back.
- A download for the video keeps going; it is the downloads' own job.
- Two quick Backs: `closePlayer` is idempotent (nowPlaying is null after the first).
- Ctrl+P window active then Back: the window returns to its size first (`MiniPlayerWindow.leave` on dispose).
- A stored `mini_player` value stays readable (the phone still uses it); the desktop never reads it.
- Quiet loud scenes switched on mid video ramps its gain (no click); off ramps back and then copies bytes.

## Caveats

- Desktop "Quiet loud scenes" does less than Android until the FFmpeg build has `acompressor`: it lifts
  Dolby tracks and surround folds only, and its detail line says so. Decision for the user: keep it (a hidden
  control would take a working feature from people who use it) or hide it on desktop.
- Web has no night mode: no player audio chain to hang it on.
