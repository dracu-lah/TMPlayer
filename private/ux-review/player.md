# UX review: player chunk (R8, 2026-11-16)

94 controls in 16 files (`private/ux-inventory/player.md`). D1 behaviour, D2 input modes, D3 motion,
D4 look, D5 words, D6 speed, D7 access, D8 common person. "ok" means checked, nothing to fix.
No change touches playback start, seek or the remedy ladder: every fix is layout, focus or text.

Commits: ef64c58 (RTL chips), 523470d (cast remote), dc5e2c4 (track picker fonts),
2b12d68 (speed page fonts), c3e80da (episodes autoplay switch).

## Cast remote (phone, tablet, desktop): CastRemote.kt, CastConverting.kt, CastPicker.kt

| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Screen-wide touch catcher (L151) | ok | fixed 523470d: was a focusable invisible click, first Tab stop | ok | ok | ok | ok | fixed 523470d: no longer a screen-wide button for TalkBack | ok |
| Back, Cast devices (RoundIcon) | ok | fixed 523470d: focus ring on Tab | ok | ok | ok | ok | ok (48 dp, named) | ok |
| Previous, -10, Play/Pause, +10, Next | ok | fixed 523470d: ring | ok (press scale, swap) | ok | ok | left: whole remote recomposes on each 1 s position tick (phone only, unmeasured, cheap) | ok | ok |
| Audio, Subtitles, Speed | ok | fixed 523470d: ring | ok | ok | left: speed reads "1×" here, "1x" in the player | ok | fixed 523470d: Subtitles announces selected | ok |
| Timebar and volume sliders | ok | ok (Material slider keys) | ok | ok | ok | ok | ok (named) | ok |
| Mute | ok | fixed 523470d: ring | ok | ok | ok | ok | ok | ok |
| Reconnect / Try again / Play here (Pill) | ok | fixed 523470d: ring | ok | fixed 523470d: wraps (FlowRow) at 2x font | ok | ok | fixed 523470d: 48 dp | ok |
| Stop casting (both layouts) | ok | fixed 523470d: ring | ok | fixed 523470d: upright layout scrolls at 2x font instead of squeezing Stop | ok | ok | fixed 523470d: 48 dp (compact was 36) | ok |
| Converting: Cancel | ok | fixed 523470d: ring | ok | fixed 523470d: progress line 2 lines + ellipsis | ok | ok | fixed 523470d: 48 dp (was 36) | ok |
| CastPageButton (detail page) | ok (FQ8: only once a TV is found) | ok (PageDiscButton) | ok | ok | ok | ok (scan stops by itself) | ok | ok |

Test: `desktop/.../cast/CastRemoteReachTest.kt` (targets upright/sideways/2x, Tab lands on a named
control, ring drawn, pills on screen at 2x, subtitles selected).

## Player chips (phone, TV): NextUpCard.kt, SkipPill.kt, PlaybackStatsOverlay.kt, PlayerActivity insets

| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Next up: Hide, Play now | ok (TV: Back hides, Play now focused) | ok | ok | fixed ef64c58: flush against the left edge right to left (only rightMargin set) | ok | ok | ok | ok |
| Skip pill | ok | ok | ok | fixed ef64c58: same RTL margin | ok | ok | ok | ok |
| Corner stack, undo and subtitle offer chips | ok | ok | ok | fixed ef64c58: inset taken from the reading direction's side | ok | ok | ok | ok |
| Stats overlay (not focusable, not clickable) | ok | ok | ok | ok (already marginEnd) | ok | ok (no tick while hidden) | ok | ok |

Test: `app/.../player/ChipSidesRtlTest.kt`.

## Track picker (phone touch controls, TV lines): TrackPickerFragment.kt

| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| In this chat, Search online, MSone lines | ok | ok (ChatSubtitlesFocusTest) | ok | ok | ok | ok | ok | ok |
| Earlier, Later, Reset timing | ok | ok | ok | fixed dc5e2c4: fixed 48/32 dp heights cut the word at 2x font | ok | ok | ok | ok |
| Timing figure, "In step" note | ok | n/a | ok | fixed dc5e2c4: fits (FitText) | ok | ok | ok | ok |
| Size, Position, Box, Edge, Face segments | ok | ok | ok (Material) | fixed dc5e2c4: names cut at 2x font | ok | ok | ok | ok |
| Colour swatches | ok | ok | left: ring appears without a fade | ok | ok | ok | ok (48 dp, named) | ok |
| Bold switch, Default look | ok | ok | ok | fixed dc5e2c4 (Default look fits) | ok | ok | ok | ok |

Test: `app/.../player/TouchAdjustFontTest.kt` (no word cut or broken inside, 1x and 2x, 360 dp).

## Speed page: PlayerSheets.kt

| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Speed tiles (phone full screen) | ok | ok | left: selected fill swaps without a fade | fixed 2b12d68: "0.75x" broke and was cut at 2x font | ok | ok | ok | ok |
| Fine minus / plus, figure | ok (greys at the ends) | ok | ok (figure rolls, fades with less motion) | fixed 2b12d68: figure fits | ok | ok | ok (56 dp, named) | ok |
| TV Slower / Faster lines | ok | ok | ok | ok | ok | ok | ok | ok |

Test: `app/.../player/SpeedSheetFontTest.kt`; PlayerExtrasRenderTest still green.

## Episodes (phone, TV, desktop): EpisodesPanel.kt, PlayerEpisodes.kt

| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Previous / Next step buttons | ok | ok (ring on TV) | ok | ok | ok | ok | ok (48 dp, test) | ok |
| Episode rows | fixed c3e80da: `state.next!!` replaced | ok (right click marks) | ok | ok | ok | ok | ok | ok |
| Autoplay switch line | ok | ok | fixed c3e80da: knob and track jumped; now slide on Motion.Fast, snap with less motion | ok | ok | ok (offset read in layout) | ok (Role.Switch, state) | ok |
| Close (touch only) | ok | ok (TV uses Back) | ok | ok | ok | ok | ok | ok |

Test: `desktop/.../player/EpisodesPanelTest.kt` (knob midway, snaps with less motion).

## More panel, external chooser, bookmarks, error sheet, PlayerControls, PlayerActivity

| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| More panel: Back handlers, scrim tap, Close, page Back | ok (Back steps a page, then closes) | ok (focus trapped on TV) | ok (slide, fade with less motion) | ok | ok | ok | ok | ok |
| External chooser: lines, Always, Just once | ok | ok (ring, FocusOnOpen) | ok | ok (FlowRow) | ok | ok | ok | ok |
| "Closed" offer: Play in TMPlayer | ok | ok | ok | ok | ok | ok | ok | ok |
| Bookmark name field | ok (Done saves, length capped) | ok (Up/Down leave on TV) | ok | ok | ok | ok | left: the field has no label of its own for TalkBack (the hint is a sibling text) | ok |
| Error sheet: Send logs, Send to developer | ok | ok | ok | left: a Row; at 2x font on a narrow phone the second may squeeze (needs a render check) | ok | ok | ok | ok |
| TV next episode button, row buttons (views) | ok | ok (TvRowFocusTest, BackStopsThePlayerTest) | ok | ok (ControlLabelsTest at 2x) | ok | ok (not touched: View row) | ok | ok |
| Orientation button | ok (cycles, said out loud, remembered) | n/a on TV | ok | ok | ok | ok | ok | ok |

## Left for the user

- Speed word: the cast remote writes "1×", the player "1x" (one place, one name): pick one.
- Speed tiles and subtitle colour swatches change their fill or ring without a fade (D3, polish).
- Error sheet's Send logs + Send to developer Row: switch to FlowRow after a 2x render check.
- Bookmark name field: give the BasicTextField its hint as a semantics label.
- Earlier shrinks more than Later at 2x font (different word lengths); fine, but they no longer match.
- The track picker's "Malayalam subtitles from MSone" line puts its icon off centre at 2x (ChoiceLine, components chunk).
- Devices not touched (rule): RTL chip margins and the cast remote ring are machine checked only.
