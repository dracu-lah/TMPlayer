# Player declutter and a smarter support ask (plan)

Date: 2026-10-06. Status: plan only, nothing implemented. Checkpoints CP37 to CP39 in `docs/PLAN.md`.
Source: three read-only audits of the Android player (phone and TV), the desktop player, and the
support reminder shipped in CP10c (9c53d0e). Line numbers below were true on 2026-10-06 and will drift.

## 1. Principle

One visible path per action. Gestures, remote keys and keyboard shortcuts are free, because they take no
screen space, so a button or menu entry only stays when it is the discoverable route and nothing else on
screen does the same job. A row of buttons that the remote or a gesture already covers is clutter, a menu
line that repeats a button is clutter, and a debug-flavoured feature on the main menu is clutter.

CP10d already applied this to the TV transport row. This plan finishes the job on phone and desktop and
sweeps what CP10d left on TV.

## 2. Decisions for you (answers change the work)

| # | Question | Recommendation |
|---|----------|----------------|
| D1 | Phone centre cluster: keep back 5 s and forward 10 s buttons, or leave only play/pause plus previous/next? Double tap does both. | Remove the two seek buttons. Keep the disc and the episode steps. A first-run hint ("double tap to jump") covers discoverability. |
| D2 | Phone Lock and Picture in picture: row buttons (landscape only) or overflow only? | Overflow only. Both are rare, and portrait never had the row buttons anyway. |
| D3 | Desktop wall clock in the top bar: remove or keep as a setting? | Remove. The OS has a clock; TV keeps its clock because a TV has none on screen. |
| D4 | Desktop "Details" panel (live stats for bug reports): keep or hide? | Keep, but move under a "Playback options" page, away from the main menu. |
| D5 | Support ask: how many times before we stop for good? | Three asks at most, then never (see section 6). |
| D6 | Add a cheaper non-money ask (star on GitHub, tell a friend) as the second rung? | Yes. |

Anything you do not answer takes the recommendation.

## 3. Android phone: what goes

Files: `PlayerControls.kt`, `PlayerActivity.kt` (`showOverflow`, around line 2759), `PlayerMenu.kt`,
`PlayerFeedback.kt`, `TrackPickerFragment.kt`, `res/layout/player_controls.xml`, `res/layout/activity_player.xml`.

Remove from the controls:
- `center_rewind` and `center_forward` with their labels (D1). Double tap left and right, the hold-to-fast-forward
  and the keys keep working. Seek HUDs stay.
- `control_lock` and `control_pip` from the bottom row (D2). They stay in the overflow, and the overflow
  entries stop being conditional on width.
- `control_scale` from the bottom row. Aspect moves into the overflow as one "Picture shape" entry that opens
  the existing sheet. Pinch stays as the shortcut. The row ends as subtitles, audio, speed, rotate: four buttons.
  Rotate stays because several phones report a portrait window (see the phone memory note), where it is the only way.

Remove from the overflow:
- "Speed": the row button opens the same sheet.
- "Start over": the resume notice offers it for the first seconds, and the bar reaches 0. Check first that the
  notice really carries the button on phone; if not, add it there rather than keeping the menu line.

After the sweep the overflow reads: Lock screen, Picture in picture, Picture shape, Volume boost, Sleep timer,
Open in another app, Load subtitle file, Copy link, Save to downloads, Mark watched, Playback details (last).

Merge or tidy:
- `gesture_hud` (the second text-feedback TextView, `PlayerActivity.kt` near 1212) and the `PlayerFeedback` pills
  do the same job. Route every `showGestureFeedback` caller (orientation, scale, boost, "From the start", remote
  jump, digit jump) through `PlayerFeedback`, then delete the TextView.
- Subtitle "Look" controls exist twice in `TrackPickerFragment` (Line rows near 283 and a Compose block near
  495). Keep one renderer.
- Delete the stale "status pill" comment in `player_controls.xml` (lines 56 to 60) and make `controls_clock`
  `gone` by default in XML so `PlayerControls` stops toggling it on phone.
- Next-up card "Hide" button: keep on phone, drop on TV where Back dismisses it (steering through two buttons
  with the D-pad is a worse path than Back).

## 4. Android TV: what goes

CP10d took out the transport buttons. What is left to sweep:
- The row still carries `control_scale` and `control_pip` next to the More button. Move both into the More menu.
  The row becomes the timebar plus subtitles, audio, speed, More.
- More menu: drop the Speed entry (the row has it). Picture in picture stays in the menu, now its only home.
  Net effect: no action appears twice.
- Previous and next episode: audit says they show on the row and in the More menu and on media keys. Keep the
  More menu entries (cheap remotes have no media keys) and the keys, remove the row buttons.
- Dead code: `control_lock` and `control_rotate` are never visible on TV. Remove the TV branches that
  configure them.
- "Remote keys" page (11 rows) stays as the last More entry, but also show it once on the first playback as a
  dismissible hint, so nobody has to go looking.
- Keep: the wall clock (top left), the paused cue, the jump HUD, the side feedback icons.

Verify on the TV emulator (promo flavor): D-pad walk across the shortened row with no dead focus, Down from the
bar lands on Subtitles, More opens, Back closes in order. Update `INSTALL.md`'s remote table.

## 5. Desktop: what goes

Files: `desktop/src/main/kotlin/com/tmplayer/desktop/player/PlayerOverlay.kt` (PO), `PlayerScreen.kt` (PS),
`PlayerKeys.kt`.

Controls:
- Back 10 s and forward 10 s (PO 317, 328): remove. J, L, the arrows, the wheel and the timebar cover it.
  Previous and next episode stay, and only appear when there is a neighbour.
- Wall clock (PO 287): remove (D3).
- Shape button (PO 388): remove from the bar. It has no key and cycles blindly. The Shape page stays in the menu.
- Speed pill (PO 382): keep, but a click opens the speed list instead of cycling blindly, and the Speed entry
  leaves the menu. Keys stay.

Main menu (PS 673 to 701). Today it has 17 entries. Remove every entry that duplicates a bar button or key:
Play/Pause, Audio, Subtitles, Speed, Shape, Fullscreen, Mini player, Start over (the resume notice has it),
Details and Shortcuts as separate lines. What remains, in this order:
1. Always on top
2. Playback options (new page): Downmix, Volume boost, Sleep timer, Ignore clicks, Details (D4)
3. Subtitle style and timing (new page): Size, Position, Box and the two delay nudges, which today bury the
   subtitle list
4. Telegram items when they apply: Copy link, Save, Open elsewhere, Mark watched
5. Help (one entry): opens the shortcut sheet

Overlays:
- The shortcut sheet has 27 rows. Group it (Playback, Audio and subtitles, Window, Rare) and put the ten that
  matter first.
- NextUpCard (PO 850) and the Countdown status sheet (PO 907) both offer "Play now" for the same next episode.
  Make the card the only prompt while video is still showing; the sheet is only for the ended state with no
  card. Confirm the trigger logic before cutting, the audit did not read it.

## 6. Support asks, version 2

### What exists (CP10c, shipped, verified in code)
- Entry in Settings and About on phone, TV and desktop, with a QR code for each link (TV shows QR only).
- One card, rules in `core/.../SupportReminder.kt`: needs 14 days since first launch and 10 plays, then a
  60 day snooze that starts the moment the card is shown, "Not now" and "Don't ask again".
- Phone and TV: shown only on the Chats screen, 4 seconds after the shell appears, never in the player (it is a
  separate activity). Desktop: overlay, hidden during playback.
- One master switch, `SupportReminder.enabled`, fed by `BuildConfig.SUPPORT_LINKS` (hard-coded true). Play
  flavor was dropped, so the switch is kept only for a future store build.
- No telemetry on the card. The real 14 day and 10 play trigger has not been seen on a device.

### What is weak
1. Time and play count only. A "play" is any video start, including re-opening the same one for a second.
2. No cap. It repeats every 60 days forever, and ignoring the card is treated as "Not now".
3. Shown at a random quiet moment, not a good one. People give when something just worked.
4. One copy, one ask, money only.
5. No "I already supported" answer, so a donor can only choose "Don't ask again", and nothing says thanks.
6. No way to learn whether it works, and no privacy-friendly way to find out.
7. Existing users' "install date" is the day they got the update.

### The new design
**When it may appear.** Only at a positive moment, on the screen the viewer returns to, never in the player:
- the player closes after an episode or film was watched to the end (90 percent or more), or
- a download finishes while the app is open on Chats or Downloads.
Never in the first 7 days, never twice in one session, never behind an update popup, language notice, error
sheet or login. On TV, only when the D-pad is idle on Home, and Back means "Later".

**What counts as use.** Replace raw plays with completed watches (90 percent or more) plus accumulated watch
time, stored locally in `SettingsStore` next to the existing `support_*` keys. A video re-opened within an hour
counts once.

**The ladder, three asks at most.**
| Ask | Earliest | Content | Buttons |
|-----|----------|---------|---------|
| 1 | 7 days and 5 completed watches | "You have watched N hours here. TMPlayer has no ads and no account of ours. If it earns a spot, a coffee keeps it going." | Support, Later, I already support |
| 2 | 30 days after ask 1 | Cheaper ask (D6): star the repo, or tell a friend (share sheet with the site link), with a small "or chip in" line | Star, Share, Later, I already support |
| 3 | 90 days after ask 2 | Short honest note that it is the last time we ask | Support, Done |
After ask 3 the card never shows again. "I already support" and "Done" both set the permanent off flag and
turn the About group heading into "Thanks for supporting TMPlayer", which costs nothing and feels right.
"Later" moves to the next rung's earliest date. Ignoring the card counts as having asked, as today.

**Copy rules.** Say it is voluntary and unlocks nothing (keeps the TMDB non-commercial terms safe, already
the wording everywhere). No guilt, no countdown, no "your support helps us" filler. Two copy variants per rung
are kept in `en.json` so wording can change without code. Do not run langsync; other locales are yours.

**Measuring without spying.** No in-app telemetry. Route the links through `tmplayer.org/support/?utm_source=card1|
card2|card3|settings|about`, a static page on the site (Vercel) that lists both donate links, the star link
and the share link. Vercel analytics already counts page views, so you see which entry point converts and
which ask rung does, with nothing added to the app and nothing to disclose in the privacy page beyond the
new URL. QR codes point at the same page. GitHub Sponsors and Buy Me a Coffee stay the two destinations.

**Other places to be visible (not popups).**
- The About screen's support group stays, plus the new thank-you state.
- The site home, download, changelog and release posts keep their Support section.
- The Telegram release post gets one closing line with the support page, in `release.yml`'s message.
- Optional, your call: a UPI QR on the support page if you want to reach donors who do not use GitHub or cards.
  It is a page change, no app change.

**Switch.** Keep `SupportReminder.enabled` as the only gate. Add a debug flag for each rung
(`--ei support_rung 2`, desktop `-Dtmplayer.supportRung=2`) so every card can be screenshotted without waiting.

**Tests.** `SupportReminderTest` grows: a ladder walk with a fake clock, Later spacing, cap at three, "I already
support" being permanent, completed-watch and watch-time counting, no ask inside the first 7 days, none twice
in one session. Emulator screenshots of each rung and of the thank-you About state on phone and TV, plus the
desktop render test.

## 7. Order and cost

- CP37 Android player declutter (phone and TV). Same files as each other, so one session, in this order:
  phone, then TV. Medium. Needs a real-device pass for gestures and pinch (batch with CP11).
- CP40 Episodes button and modal in the player (fills the freed bottom right slot, replaces the TV row and
  More-menu episode lines). After CP37 and CP38; feature list still to be confirmed.
- CP38 Desktop player declutter. Disjoint files from CP37, can run in parallel. Small to medium.
- CP39 Support asks v2 plus the site support page. Touches `core` and the shells, not the player, so it can
  run in parallel with CP37 and CP38. Medium.
Each CP ends with emulator or desktop render screenshots (WebP per CLAUDE.md), `INSTALL.md` and
`docs/FEATURES.md` where they list a removed control, a dash check, and unit tests where logic changed.
No release is part of this: the 7 day release cadence guard applies, so these ride the next batched release.

## 8. Risks
- Removing a visible control hurts people who never learned the gesture. Mitigation: first-run hint for double
  tap and for the TV remote keys, shown once.
- Strings left unused after removal: delete from `en.json` only, leave other locales to you.
- Support asks are reputation-sensitive. The cap of three, the thank-you state and "I already support" are what
  keep it from feeling like a nag; do not loosen them to chase numbers.
