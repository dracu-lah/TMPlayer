# TV key ownership

One table says what each remote key means in each TV context, so two screens never claim the same
key. Written by WP-69 of the UX plan; the test is
`app/src/test/java/com/tmplayer/tvux/TvKeyOwnershipTest.kt`.

Read it before binding a key on Android TV or Fire TV (web TV keys follow in `webapp/src/tv/remote.ts`
where they apply). A new binding, or a key that changes meaning, edits its row here and its test in
the same change.

## How to read it

- A cell marked "today" is what the code does now and a test pins it.
- A cell naming a WP changes there: that package updates this row and turns the cell into a test.
- The last column is the fallback for a remote that lacks the key: everything reachable with the
  arrows and OK alone.
- "Row down" is the player's transport row hidden, "row up" is it shown.
- A tile acts on Menu on the key down (`Holdable.kt`); the player acts on it on the key up where the
  quirk says so (Fire TV), so that a held Menu can still become the handover. A test of a Menu
  binding sends both the down and the up. A keyboard's Shift+F10 equals Menu on a phone or tablet
  (`Holdable.kt`).

## The table

| Key | Bar | Tile, chat row, picker tile | Long list, nothing focused | Settings rail and pane | Player, row down | Player, row up | Player panels (More, pickers) | Arrows-and-OK fallback |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Menu | account menu, on release (today) | item options (today) | account menu where the bar is shown, else the profile picker with two profiles or more (today) | the profile picker with two profiles or more, as no bar is shown there (today) | player menu where the quirk says so (today) | player menu (today) | closes the panel (today, check) | OK on the picture; long OK on a tile; the video grid header's "Options for {name}" button (WP-14); the details page's buttons; More button |
| Info | nothing | item options (today) | nothing | nothing | details on a short Info, report stutter on a held one (today; the first short Info says so once through the hint registry, WP-21) | same | nothing | long OK; More, Help (today, WP-89) |
| Long OK | picture: account menu (today, plain OK does the same) | item options (today) | nothing | nothing | the "open with" chooser after 1.5 s, only when the press began over the bare picture (WP-19, today) | plain OK (WP-19, today) | nothing | More, Open in another app |
| Back | leaves to Home or exits (today) | to the bar, or to the top first in the chat list and the video grid once more than 2 screens down (today, WP-12) | top first, then leave (today, WP-12) | pane to rail (a sub-page closes first), rail out (today, WP-07, `SettingsPaneTvFocusTest`) | leave (today) | row down (today) | closes the panel, focus on its opener | n/a |
| Search | TV search (today) | TV search (today: the browse shell answers it, a chat's videos and Downloads go to it from `MainActivity`, WP-22) | TV search (today, WP-22) | nothing | nothing | nothing | nothing | the bar's Search tab |
| CC, Audio | nothing | nothing | nothing | nothing | CC toggles subtitles (last used track, or off), a second press within 3 s or a held CC opens the picker (WP-19, today); Audio opens its picker (today) | same | nothing | the row's subtitle and audio buttons |
| Page, Channel | nothing | screenful (today, WP-12; Fast forward and Rewind too) | screenful (today, WP-12) | section jump: first row of the next section, Up: this section then the one before (today, WP-75, `SettingsPaneTvFocusTest`) | Page: chapter, or 60 s without chapters, once per press (WP-18); Channel: previous or next video (today) | same | group jump in More (WP-20) | Back to top; row buttons; More entries (today, WP-89) |
| Play/Pause, Stop | nothing | nothing | nothing | nothing | toggle (today); Stop leaves (today) | same | Play/Pause, Play, Pause and Stop still work under the panel and the pickers (WP-19, today) | OK on the play button |
| Left, Right hold | move (today, tested on tiles only) | move, stale repeats dropped (WP-13) | same | rail and pane: move, stale repeats dropped; Right or OK on the rail enters the pane (today, WP-07) | 10 s, live ramp, gated (WP-18); with the scan option (WP-79, behind Settings, off) a held key from its 3rd repeat moves the bar's thumb and a time card and seeks once on release | bar: 10 s, ramped scrub, stale repeats dropped, one commit (WP-18); a scan under way keeps the keys | move | n/a |
| Rewind hold | nothing | screenful (today, WP-12) | screenful (today, WP-12) | nothing | jump back, ramped (today); with the scan option (WP-79, behind Settings, off) it scans like a held Left | same | group jump in More (WP-20) | Left |
| Any key, paused 10 s on a TV | n/a | n/a | n/a | n/a | the row makes way for a small "Paused" mark (WP-21): any key but Play/Pause, Play, Pause, Stop, Fast forward, Rewind, Next, Previous, CC and volume only raises the row, the play disc holding the remote, its repeats and release taken too (PB2-03, `PlayerRemoteKeys.PauseMarkKeys`): Back does not leave, the arrows do not seek, OK does not resume | n/a | n/a | n/a |

## Player actions without a key

Nothing in the television's player works only through a key the Google TV remote lacks or a hold
nobody sees (WP-89, designer M3). Each action a key does has a path with the arrows and OK: a row
button (Down from the scrub bar, then Left or Right) or a line of More (the row's last button).
`PlayerKeyFallbackTest` holds the list.

| Action | Key | Arrows and OK |
| --- | --- | --- |
| Subtitles on, off or the picker | CC | the row's Subtitles button |
| Audio track | Audio | the row's Audio button |
| Speed, and 2x while held | held Fast forward | the row's Speed button; More, Speed |
| Previous and next video | Channel keys | the row's Previous and Next buttons, while there is one to go to |
| A chapter, or a minute | Page keys | More, Next chapter and Previous chapter (a file with chapters); Left and Right otherwise |
| Details | Info | More, Playback details |
| Report a stutter | held Info | More, Report stutter |
| The handover to another app | long OK, held Menu | More, Open in another app (left off for a chat that restricts saving) |
| Stop | Stop | Back |
| The keys themselves | none | More, Remote keys |

## What the code does today, beyond the table

Found while pinning the table (WP-69):

- **Held Menu and held OK in the player.** A held Menu hands the video to another app
  (`isLongPressOnTheVideo`, `openInAnotherApp`, a chooser, never a direct launch). On a television a
  held OK does it only after 1.5 s and only when the press began over the bare picture (row down,
  no skip pill, no next-up card, no offer): with the row up a held OK is a plain OK, so a slow press
  on a button never leaves the app (WP-19).
- **Skip pill (WP-19).** Row down: OK skips while the pill shows (its second line reads "OK to
  skip"); Up raises the row and never skips. Row up: the pill is focusable, Up from the scrub bar
  lands on it (its line reads "Up, then OK to skip" until the remote is there), OK skips, Down goes
  back to the bar. A Previous episode button stands left of Next on the row while the chat has an
  earlier episode.
- **Panels.** Only the More panel closes on Menu (where the quirk says so); the track pickers hand
  every key to their own view. In the More panel on a TV, Page Up and Page Down (and Channel,
  Fast forward, Rewind) jump between its groups (`PlayerMorePanel`, `PlayerMenu.pageTarget`, WP-20):
  down to the next group's first line, up to this group's first line, then the one before.
- **Stop, Channel and Media Next, Previous and Skip.** Stop saves the place and leaves like Back.
  Channel up and down, Next, Previous and Skip step the video (`PlayerRemoteKeys.episodeStep`), once
  per press. Page Up and Page Down step a chapter, or a minute without
  chapters, once per press, on a TV only (`PlayerRemoteKeys.pageStep`, WP-18). On a phone, a tablet,
  a Chromebook, the desktop and the web a keyboard's Page Up and Page Down are the previous and next
  episode (`PlayerKeyMap`, the desktop's `PlayerKeys`), as the desktop and the web had them before
  WP-28; with none to go to the player says so. One meaning per kind of device: the chapter step is
  a television remote's only, whatever sent the key there (PB-03).
- **Info.** A short Info (released before the long press) shows the details; a held Info reports a
  stutter.
- **Menu elsewhere.** Besides the tile (`Holdable.kt`) and the player: the profile picker's tiles
  (options, on key up), the Add chats rows (star), and `MainActivity.onKeyUp`, which opens the
  profile picker for a Menu nobody took when the device has two or more profiles. Since WP-02 that
  fallback opens the account menu first (`TvBarSignals.askAccountMenu()`, answered by the bar for every
  profile count) and keeps the picker for where no bar is shown (Settings, a phone). The bar
  (`TvTopBar.kt`) takes Menu on its release while the remote is inside it, and the account menu
  (`TvMenu.kt`, `closeOnMenu`) closes on a Menu release; a tile's Menu is never seen by either, since
  `Holdable.kt` takes both its press and its release.
- **Left and Right.** The table's "move (today)" is tested on tiles. In the player (WP-18) a TV
  remote jumps 10 s both ways over the bare picture (`PlayerRemoteKeys.remoteBaseMs`; a phone's
  keyboard keeps 5 back and 10 forward, and `Skip.BACK_MS` still feeds Media3 and the
  notification), a held key ramps through `RemoteQuirks.holdStepMs` (x1, x3 after 2 s, x6 after
  5 s, capped at a 200th of the length, 10 s to 120 s) behind the 150 ms gate, and the pace shows
  beside the total, to one decimal. On a TV's focused bar `FineTimeBar.onKeyDown` takes the same
  steps, drops stale
  repeats, and Media3 commits one seek a second after the last key (`FineTimeBarKeysTest`).
  The scan option (WP-79, Settings, off by default) turns a held Left, Right or Rewind, from its
  3rd repeat, into a scrub of the bar's thumb with a time card and no frames fetched; the seek
  lands once when the key is let go, or 400 ms after the last repeat (`RemoteScan`).
- **The pause mark (WP-21).** Paused 10 s on a television, the row goes and a small "Paused" mark
  takes its place, so the first key only brings the row back (PB2-03); with the row up the keys
  are the row's again, so Back puts the row down and the next Back leaves.

- **Options without a Menu key (WP-14).** A video tile and a History row keep Menu, Info and a held OK
  for their options (`Holdable.kt`) and OK plays at once. A remote with none of those (Google TV)
  has the "Options for {name}" button in the video grid's header (beside Sort, hidden until a tile
  has had the remote; it brings the tile into view first and opens the same menu), the details
  page's own row (Play, Mark as watched, Watch later, Add to list, Download, one Right apart), and
  the tile's focus tip "Hold OK or press Menu for options" for its first five focuses
  (`HintIds.TILE_OPTIONS`, through the hint registry). Watch later is no tab in the TV bar: it is
  in the account menu and in History. Web TV: a held OK or the context key opens the card menu,
  and the chat page's header has the same Options button (`ui/CardMenu.tsx`); Back from a menu it
  opened lands on the card, as on Android. The Options button is the video grid's only
  (`MediaGridScreen`): it does not scroll a web grid first, since the card menu carries its video.
  Home, search results and list tiles have no tip and no pill: they rely on Menu or a held OK (and
  the details page's row), so a Google TV remote reaches their options from the video's page.

## Who may claim a key today

The test lists, per key, the source files that bind it. A file not on the list that starts binding
the key fails the test: add it here with its context first.

| Key | Bound in |
| --- | --- |
| Menu | `Holdable.kt`, `ProfilePicker.kt`, `AddChatsScreen.kt`, `MainActivity.kt`, `PlayerActivity.kt`, `TvTopBar.kt`, `TvMenu.kt`, `TvAccountSheet.kt` |
| Info | `Holdable.kt`, `PlayerActivity.kt` and `PlayerRemoteKeys.kt` (short Info), `PlaybackMarks.kt` (held) |
| Search | `BrowseScreen.kt`, `MainActivity.kt` (a chat's videos and Downloads, WP-22), `SearchKeyGuard.kt` (swallows it on a phone) |
| Page, Channel | `PageKeys.kt` (every long list: the key names live there only), `PlayerActivity.kt` and `PlayerRemoteKeys.kt` (Channel steps the video, Page a chapter or a minute) |
| Stop | `PlayerActivity.kt`, `PlayerRemoteKeys.kt` |
