# Shortcuts, remote keys and gestures

Every key, remote button and gesture the app answers, per platform, with the file that owns it. Audited
2026-11-16 (UX interaction plan, task T3). The player's own sheet ("?") is built from
`PlayerKeys.sheetGroups` and read back through the key table by `PlayerKeysTest`, so that list cannot
promise a key the player lacks; this file is the wider map and the rules behind it.

## Rules (what a change must keep)

1. **Esc closes one layer.** A dialog, then a menu or pane, then Settings or a page, then (desktop,
   full screen) the window's full screen. Never two at once. `ShortcutsAuditTest`.
2. **Dialogs take Esc themselves.** `TmAlertDialog` (ui `Floating.kt`) closes on a bare Esc whichever
   layer draws it (a desktop dialog layer, a tablet's hardware keyboard). The press is spent there, so
   the page under it never hears it. `Backspace` is the remote's Back in a dialog on the big screen.
3. **Focus returns to the opener.** After a dialog or a sheet closes the control that opened it has
   focus again (tested for the desktop in `ShortcutsAuditTest` and for the remote in `BigScreenKeysTest`).
   The player calls `refocus()` after each of its own sheets and prompts.
4. **The keys everyone owns are never taken.** Ctrl+C, V, X, Z, A and Y (Cmd on a Mac) do nothing in the
   shell or the player (`ShortcutsAuditTest`, `PlayerKeysTest`). Ctrl+F is Find and focuses the page's
   search, as everywhere. A text field being typed into keeps every plain key (the player's note,
   time and subtitle prompts say so through `PlayerKeyRoute.Route.typing`).
5. **No keyboard trap.** Tab and Shift+Tab walk every control; a layer that needs the arrows (a
   remote's dialog, the player's side panel) hands them on at its edge or closes with Esc.
6. **Back goes one level up and never asks.** No "press again to leave" at the top level (removed from
   `MainActivity` on 2026-11-16; the login screen still has one, see Left).
7. **Targets are 48 dp on touch.** Exceptions are listed at the end.
8. **A gesture has a button twin** (table on the phone, below).

## Desktop (Windows, Linux, macOS): the window

Command is Ctrl, or Cmd on a Mac. Read in `ShellState.onPreviewKey` (works inside a text field) and
`ShellState.onKey` (what nothing focused wanted).

| Key | Does | Notes |
| --- | --- | --- |
| Command+K | Open or close search across every chat | |
| Command+F, `/` | Focus the page's search box, or open the search across every chat where the page has none (WP-51) | `/` is not taken inside a text field, and counts where it needs Shift (German, Nordic) |
| `?`, F1 | The shell's keyboard sheet (Settings, Help has the same as a row), again to close | read by the character typed, so `/` on a German key (Shift+7) stays a search; the player has its own sheet |
| Command+=, Command+-, Command+0 | Interface size larger, smaller, back to Default | Huge (1.5) is a desktop-only step after Largest (WP-50); not read in the player, where Ctrl+= and Ctrl+- are the sound delay |
| Command+Shift+A | Choose chats | Ctrl+A alone is the picker's Select all |
| Command+, | Settings | |
| Command+J | Downloads, again to close it | the floating bar's Downloads button; Ctrl+Shift+J is not taken |
| Command+1, Command+2 | Home, Chats | only in the installed app (`display-mode: standalone`, Safari's Home Screen app): in a browser tab these two switch tabs and are left to the browser |
| Command+R, F5 | Refresh the page | only where the page can refresh |
| F11, Ctrl+Cmd+F (Mac) | Full screen | |
| Command+Q | Quit | Linux tiling window managers have no close button |
| Cmd+W, Cmd+M (Mac) | Close, minimise the window | elsewhere the window manager's own |
| Esc, Backspace, Alt+Left, Cmd+[ , mouse Back | Back | see the Esc order below; Backspace and Alt+Left are the text field's while one is focused |
| Arrows, Home, End, PageUp, PageDown, Ctrl+Home, Ctrl+End | Move through a grid or list | `KeyboardNav`: no wrap at the edges, Home and End go to the row's ends |
| Enter, double click | Open or play (per the "detail first" setting) | |
| Menu key, Shift+F10, right click | The item's menu | |
| Ctrl+A, Shift+click, Ctrl+click, Esc | Select all, a range, toggle, clear a selection | owned by T2 (`GridSelection`) |
| Tab, Shift+Tab | Next, previous control | focus ring shows from the first key (`FocusVisibility`) |

### Esc, in order

1. A dialog (`TmAlertDialog`, a confirm, rename, poster picker).
2. The menu or palette over the page: the profile menu, Command+K palette (clears its text first).
3. A detail pane, then a series or list page, then Settings (a modal page): `BackStack`, newest first.
4. A selection or a search box with text (clears first, then closes).
5. Full screen (only when none of the above is open, `ShellState.transientLayerOpen`).
6. In the player: see the player's order below.

## Desktop: the player

Source of truth: `PlayerKeys.actionFor`. `?` opens the grouped sheet. Mac swaps Ctrl for Cmd on window
keys. When the player is open the window hands it the keys even if Compose's focus lost the picture
(`PlayerKeyRoute`), except while a prompt, menu or text field has them.

| Key | Does |
| --- | --- |
| Space, K, Enter, P, media play/pause | Play or pause |
| Left, Right / J, L | Seek 5 s / 10 s |
| Shift+Left, Shift+Right, Ctrl+Left, Ctrl+Right (no chapters) | 1 min (Ctrl with chapters is the chapter step) |
| Ctrl+Alt+Left, Ctrl+Alt+Right (Cmd+Shift+Alt on a Mac) | 5 min |
| Alt+Left, Alt+Right | 10 s (the shell's Back chord is the player's seek here) |
| Command+Left, Command+Right | Previous, next chapter |
| 0 to 9, Home, End | Jump to that tenth, the start, the end |
| Up, Down, wheel | Volume (Shift+wheel seeks when "Mouse wheel seeks" is on) |
| M | Mute |
| Shift+N, Shift+P, PageDown, PageUp, media next, previous | Next, previous episode |
| ], [ , Shift+., Shift+, , = and - | Speed up, down; Backspace resets it |
| , and . while paused | Frame step |
| V, Shift+V, C | Subtitles next, previous, on or off |
| A, Shift+A, Shift+3 | Audio next, previous |
| Z, X, Shift+Z | Subtitles earlier, later |
| Shift+F, Shift+G | Subtitle size smaller, bigger |
| Ctrl+-, Ctrl+= | Sound earlier, later |
| F, F11, Alt+Enter, double click | Full screen |
| W | Picture shape (fit, fill, stretch) |
| Alt+=, Alt+-, Alt+0, Alt+Backspace, Ctrl+wheel | Zoom in, out, reset |
| S, Shift+S | Screenshot with, without subtitles |
| R | A to B repeat |
| B | Bookmark |
| G | Skip intro, recap or credits (while the pill shows) |
| Command+G | Go to a typed time |
| Shift+Backspace | Back to where you were before a long jump |
| I | Playback details |
| T, Command+T | Always on top |
| Command+P | Small always on top window (not Back; Ctrl+P again brings the window back) |
| Command+Q | Quit |
| Shift+/ | The shortcut sheet |
| Esc, Backspace | Back (the player or the video, see below) |

Chords with Ctrl, Alt or Cmd that the table does not list do nothing, so Ctrl+C, V, X, Z, A and F are
never read as a player key. The one window key the player takes over is Alt+Left (a seek, not Back).

### Esc, in the player

Locked: every key is the lock's (hold Enter to unlock). Otherwise, in order: the episode list; the side
panel (Esc closes, Backspace goes up a page); a sheet (shortcuts, details, online subtitles); a note or
time prompt (a dialog, closes itself); full screen (Esc leaves it, never the video); otherwise Back,
which stops the video, saves the place and returns to the page that opened it (2026-11-16: the desktop's in-app
mini bar is gone; the phone follows with T12).

## Web app (web.tmplayer.org): the window

Letter chords follow the letter on the keycap, or the key's place (K, J, F, A) on a layout without Latin letters (Cyrillic, Greek). Owned by `webapp/src/ui/shellKeys.ts` (the chord table, tested in `shellKeys.test.ts`, walked by
`e2e/shell-keys.spec.ts`) and the arrows by `webapp/src/ui/VirtualGrid.tsx`. Command is Ctrl, or Cmd on
a Mac, iPhone and iPad (a phone or tablet with a keyboard gets the same keys). The TV skin
(`data-form="tv"`) and the player keep their own keys and ignore these. A dialog (the confirm, a
card's menu, a sheet) is its own layer: nothing here answers while one is open.

| Key | Does | Notes |
| --- | --- | --- |
| Command+K | Search across every chat; again goes back | works inside a text field; overrides the browser's own (the address bar's search); a held key acts once; back from a page opened by a link goes Home, never off the app |
| Command+F, `/` | Focus the page's search box (a chat's opens first) | on a page with no search box the browser keeps Find; `/` is not taken inside a text field, and counts where it needs Shift (German, Nordic) |
| Command+Shift+A | Choose chats | overrides the browser's own (tab search) |
| Command+J | Downloads (the Get the app card); again goes back | overrides the browser's own (its downloads list) |
| Command+1, Command+2 | Home, Chats | a browser window keeps Cmd or Ctrl and a digit for its tabs; the keys work in the installed app (PWA) and where the browser lets the page have them |
| Command+, | Settings | |
| Arrows, Home, End, PageUp, PageDown | Move through a grid or list of cards or chats | no wrap; Left and Right go on to the next row, Up and Down keep the column, Home and End go to the row's ends, Ctrl+Home and Ctrl+End to the first and last (Home, End and the Page keys are spent even at an edge, so the window never scrolls away from the focused card); a 3,000 chat list scrolls to the item and takes the keyboard when it is drawn |
| Menu key, Shift+F10 | The focused card's menu | |
| Enter | Open the card | |
| Ctrl+A, Shift+click, Ctrl+click | Select all, a range, toggle | History's select mode and Choose chats only: no other grid has an action to run on a selection (the web keeps no downloads) |
| Tab, Shift+Tab | Next, previous control | |

Never taken (the browser's own, or the system's): Cmd or Ctrl with W, T, N, Q, R, L, P, S, C, V, X, Z, Y,
A (except Shift+A above), H, D, plus F5, F11 and Alt+Left. The page never reads them, so they keep
working in every browser.

### Esc, in order (web)

1. A dialog (the shared confirm, a card's menu, a sheet), which closes alone.
2. A menu over the page (the You menu, a sort menu), which gives the keyboard back to its button.
3. A search box with words: clears them; empty, it leaves the page (Search, a chat's search), one step back inside the app or Home when the page was the first one opened.
4. History's select mode (clears the ticks and leaves it). A video's page has no Esc: its Close button and the browser's Back go up a level (left to the video page's owner, WB3).

## Big screen on a computer (remote, `--tv` or Layout: TV)

| Key | Does |
| --- | --- |
| Arrows | Move focus; Up from the top row goes to the bar; focus comes back to the item left (`focusRestorer`) |
| Enter, OK, Space | Press the focused control |
| Esc, Backspace, remote Back | Up one level: dialog, menu, page to its place on the bar, Home, then leave full screen |
| Remote Home | Home, focus on the bar's Home |
| Media keys, Rewind, Fast forward, Next, Previous, Stop | Play, pause, 10 s, episodes, leave the video (`PlayerRemote`) |
| Player: Up, Down | Raise the controls with focus in them; Back puts focus back on the picture and keeps the controls |

## Android TV and Fire TV: remote

Player (`PlayerActivity.dispatchKeyEvent`).

| Key | Over the bare picture | With the row up |
| --- | --- | --- |
| OK, Enter | Play or pause | Press the focused control; on the bar play or pause; hold OK on the picture opens "Play in another app" |
| Left, Right | Seek (small jump, side flash) | Walk the row; on the bar a stride |
| Up | Take "Back to" or the subtitle offer if one stands, else raise the bar | Walk |
| Down | Raise the row on its first button | Walk |
| Back | Dismiss an offer, the next up card, or the row; then leave the video (paused or playing: it stops) | Hides the row |
| Fast forward (hold) | Hold speed until released | same |
| Rewind, Fast forward, Play, Pause, Play/Pause, Next, Previous | As named (10 s jumps, episodes) | same |
| Back 10 and Forward 10 beside the play disc | not on screen | Left and Right from the disc reach them, OK jumps 10 s (`Skip.BUTTON_MS`), Down goes to the bar |
| Captions, Audio track, Aspect, 3D keys | The picker or step the remote names | same |
| 0 to 9 | Jump to that tenth | same |
| Menu (Fire TV) | Player options (long press: play in another app) | Closes More options |
| Info held | Report stutter | |
| Locked | Everything is swallowed; hold OK to unlock; volume passes | |

Elsewhere: the remote walks everything by focus; Back goes up one level and leaves the app from the top
level with no confirmation (since 2026-11-16); a dialog takes the remote on its safest button; a banner
or a line that is not a question is reached with Up from the top or Down from the bottom (`TvReach`),
Back or the way back puts the remote where it was. Idle focus restoration uses `focusRestorer` on bars,
rows and tabs.

## Phone and tablet

Gestures in the player (`PlayerGestures`), each with its visible twin:

| Gesture | Does | Button twin |
| --- | --- | --- |
| Tap | Raise or hide the controls (or play and pause, by setting) | the play button |
| Double tap, left or right | Skip back or forward, with a running total | the Back 10 and Forward 10 buttons beside play, the bar |
| Double tap, middle | Play or pause | the play button |
| Hold | Fast play until the finger lifts | the Speed button |
| Drag sideways | Scrub, finer when the finger moves away from the bar | the scrub bar |
| Drag up or down, left edge | Brightness | the system's quick settings (no in-app button) |
| Drag up or down, right edge | Volume | the volume keys, the system slider |
| Pinch, then one or two finger drag | Zoom and pan the picture | the picture shape button |
| Back gesture or button | Closes the topmost sheet, then the row (only when a sheet is open; else the video, paused or playing, and the sound stops with it: `BackStopsThePlayerTest`) | the bar's back arrow |
| Lock | Everything but the unlock hold is ignored | the lock button |

The six icons under the controls (Subtitles, Sound, Speed, the shape in force, Rotate, Lock) are icons
only: the words under them (`ControlLabels`, first three openings) were turned off on 2026-11-19, and the
name is a long press or TalkBack away. The row remembers the last focused
button after a sheet closes (`PlayerControls.focusRow`, `TvRowFocusTest`). The desktop's tooltips with
their keys are the `player.*_hint` strings on `OverlayButton`/`Tip` and were already in place.

Keyboard on a tablet or a phone (a Bluetooth keyboard, headset or remote): while the row is down, Left
and Right seek, Space, Enter, OK and media play pause toggle playback, digits jump to a tenth. Esc is
Back everywhere (`MainActivity.onKeyDown`, not on a TV). Tab and the arrows walk any screen. A dialog
closes on Esc (`TmAlertDialog`).

Back: the first page leaves the app (no confirmation). Predictive back is on
(`enableOnBackInvokedCallback`); the top level now claims no press so the system's back-to-home
animation plays. Screens that claim Back (a sheet, a search, a selection) close first.

## Targets under 48 dp (touch)

Fixed 2026-11-16: the episode panel buttons (44), the Free up space buttons (44) and the subtitle
colour swatches (44). Test: `EpisodesPanelTest` ("48 dp tall").

Still under 48 dp, with the reason:

| Control | Size | Why |
| --- | --- | --- |
| Desktop player overlay buttons (`OverlayButton`) | 36 dp | a mouse and keyboard surface, never touch; the hit area grows with the hover ring |
| Series list rows on a phone (`Series.kt`) | 40 dp | T2's list screens; reported to that task |
| The play glyph inside the 72 dp centre button | 40 dp | the glyph only, the button is 72 dp |
| Login screen controls | not audited | T1's input pass |

## Left (not done in this pass)

- The login screen's own "press Back again to leave" (`LoginScreen.kt`, `ExitOnBack`): remove it as the
  top level's was; T1 owns that file. Its string is `login.press_back_again`.
- The player's Back callback always claims the press, so the system's predictive back animation does
  not play from the player; it needs the callback disabled when Back would only finish the activity.
- A device pass of the remote on a real TV and the tablet keyboard: none was made (no emulator by rule).
- Brightness on the phone has no in-app button twin (system quick settings only).
