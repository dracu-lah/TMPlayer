# UX interaction plan: search, inputs, lists, filters, keyboard, D-pad, gestures

Started 2026-11-16 (user: "you've done a lot of ux mistakes ... all types like dpad touch, gestures,
keyboard shortcuts everywhere"). The visual pass is `docs/ux-audit.md` (U19); this one is about
behaviour: what a control does when pressed, cleared, left, repeated, or driven without a touch.

## Rules learned (sources in the last section)

Search and inputs
1. A search box has a visible clear control; clearing keeps focus in the box (phone, desktop).
2. Escape clears first, then closes (desktop). Back clears first, then closes (phone, TV remote).
3. The query is per visit: leaving the screen clears it, except while a page opened from a result is
   up (Back returns to the results). Main Search keeps its 2 minute rule.
4. IME action matches the field: Search for a search box, Next between fields, Done at the end; the
   keyboard type matches the content (url, number, password); no autocorrect on codes and names.
5. No results says what to try (remove a filter, check spelling) and offers the one-tap way back.
6. Recent searches (if shown) have a per-item remove and a Clear all; none shown for private chats.
7. Ctrl+F (or `/`) focuses the search box on desktop; never rebind Ctrl+C, V, Z, F, A.

Lists, selection, filters
8. Ctrl+A selects all, Shift+click a range, Ctrl+click toggles, Esc clears the selection; a count shows.
9. A selection that changes while work runs is stable; empty selection disables the action.
10. A filter or sort is visible as chips or a label, one tap to clear, "Clear all" when two or more.
11. After Back, scroll position and the focused row come back; a removed row hands focus to a neighbour.
12. Empty, loading, error and huge states exist for every list (3,000 chats, 100,000 videos).

Remote, keyboard, gestures
13. D-pad: every control reachable, focus always visible, no trap, Back goes one level up, no exit
    confirmation, focus returns to the control that opened a dialog or sheet.
14. Keyboard: Tab order follows reading order, no keyboard trap, Esc closes the topmost transient
    layer only (dialog, menu, sheet), Enter activates, Space toggles, arrows move in lists.
15. Touch: targets 48 dp, a gesture always has a visible button twin, predictive back works,
    swipe and long-press actions are discoverable, every press answers (haptic or pressed state).

## Product bar (user, 2026-11-16)

"Inspect everything, beautiful looking, easy to use as a common man app." So every control is judged by
someone who has never used a media app: plain words (no jargon, no internal names such as "TDLib" or
"cache window"), one obvious next step per screen, defaults that work without opening Settings, big
targets, nothing destructive without a clear sentence, and the same look and motion everywhere.

## Redesign over workaround (user, 2026-11-16)

"Even if it is a completely new design and aspects for better usability, do that instead of
workarounding in the same stuff." So when a flaw is structural (wrong layout, too many steps, a
setting that should not exist, a screen that does the wrong job), do not patch it: redesign it.

- Order: shared parts first (tokens, shared components), then the screens, so nothing is built twice.
- Before building a redesign, write `design/redesign/<name>.md`: the problem Raju hits, the new flow,
  phone, TV (remote and focus), desktop (keyboard) and web in one table, edge cases (empty, huge,
  failing, interrupted), and a before shot. Then build, then an after shot. Small, clear fixes skip
  the note.
- Keep TMPlayer's identity (colour, type, shapes in the design system); take ideas from other apps,
  never copy their look. Every redesign ships on all platforms that have the feature (parity).
- Delete what the redesign replaces (old rows, dead strings, unused composables): no parallel old
  and new versions. A removed setting keeps a safe default; a stored value stays readable.
- Big ones (a new Home, a new player overlay, merged screens) stop at the design note for the
  user's OK; everything else goes ahead and is logged under "Decisions made by Claude".

## Review dimensions for every control (the exhaustive pass)

Inventory: `node scripts/ux-inventory.mjs` writes `private/ux-inventory/<chunk>.md`, one checkbox per
interactive control (about 2,000 in 11 chunks, see INDEX.md). It is generated and git-ignored: read it
from the main tree at `/home/dracu/projects/personal/TMPlayer/private/ux-inventory/`. For each line, check:

- D1 Behaviour: what it does on press, repeat, cancel, empty, huge and failing data; rules 1 to 15 above.
- D2 Input modes: touch, D-pad (focus order, visible ring, no trap), keyboard and mouse (Tab, Enter,
  Space, Esc, shortcuts, hover, right-click where the platform expects it), gestures with a button twin.
- D3 Motion: every state change animates (press, focus, expand, appear, remove, navigate) using the
  shared Motion tokens and honouring Reduce motion; no animation that blocks input or runs while idle.
- D4 Look: spacing, type scale, radius, colour tokens, icon size and weight, alignment, truncation and
  wrapping at the largest font, dark and light, RTL; no one-off numbers where a token exists.
- D5 Words: plain, short, consistent term for the same thing everywhere, en.json only.
- D6 Speed: recomposition and frame cost (use the `compose-performance` skill: stability report,
  state reads in the frame phase, lazy keys), work on the main thread, idle CPU on the TV, startup cost.
- D7 Access: content descriptions, 48 dp targets, contrast, focus announcements, font scale 200%.
- D8 Common person: would a first time user know what this does without reading anything?

Skills for the pass (already installed): `impeccable` (polish, animation, hierarchy), `compose-performance`,
`visual-diff` (before and after screenshots, animation filmstrip), `web-design-guidelines` (web app and site).

## Waves (user wants speed; the locked rule caps it at 3 workers at once, each in its own worktree)

| Wave | Workers | Chunks |
| --- | --- | --- |
| 0 (running) | T1, T2, T3 | rule based pass above |
| 1 | R1, R2, R3 | desktop-ui-1, phone-browse-1, settings |
| 2 | R4, R5, R6 | desktop-ui-2, phone-browse-2, flows |
| 3 | R7, R8, R9 | desktop-ui-3 + components, player, web-1 + web-2 |

Each R worker writes `private/ux-review/<chunk>.md`: per control the verdict on D1 to D8, what was
fixed (commit), what is left and why. A wave starts when the previous one is merged, so two workers
never edit the same file. Every fix gets a machine check (render, focus, scale or unit test) first.

## Skills

No install needed: `impeccable` and `web-design-guidelines` (already available) cover review and
polish. An unvetted third-party skill is a supply-chain risk for no gain. Workers may call them.

## Tasks (3 workers, own worktree each, `files:` do not overlap)

Each worker: audit its surface against the rules above on phone, TV, desktop and web (read the
code, then fix), write a failing machine test first where a behaviour is wrong (focus/D-pad test,
Compose UI test, render test), fix, commit locally section-wise, list what is left in its
Progress section. No phone or emulator pass (user rule).

### T1 Search boxes and text inputs  (model: sonnet, depends: none)
files: `app/src/main/java/com/tmplayer/ui/settings/SettingsScreen.kt`, `SettingsSearchBox.kt`,
`desktop/src/main/kotlin/com/tmplayer/desktop/ui/Settings.kt`, the search page and every
`TextField`/`OutlinedTextField` outside the files owned by T2 and T3, plus their tests.
Done when:
- Settings search is cleared on leaving Settings (phone and desktop), with a Compose test per
  platform: type, open a result page, Back keeps the query, leave Settings, return, box empty.
- Every search/input box obeys rules 1 to 7 (list the boxes found, fix or say why not).
- IME actions and keyboard types reviewed for every field (sign in phone/code/password, proxy,
  TMDB key, rename, filters).
- Edge cases: rotation keeps the query while Settings is up; TV has no box (no change).

### T2 Lists, selection, filters, sort, empty states  (model: sonnet, depends: none)
files: `app/src/main/java/com/tmplayer/ui/browse/**` (except search), `ui/downloads/**`,
`desktop/.../ui/GridSelection.kt`, `Downloads.kt`, the chat and library list screens, their tests.
Done when: rules 8 to 12 hold on phone, TV and desktop, with a D-pad test for focus restore after
Back and after removing a row, a Ctrl+A / Shift+click / Esc desktop test, a filter-clear test, and
an empty-state check for each list.

### T3 Keyboard shortcuts, remote and gestures in the player and shell  (model: sonnet, depends: none)
files: `app/src/main/java/com/tmplayer/player/**`, `desktop/.../player/**`, `desktop/.../ui/Shell.kt`,
`desktop/.../Main.kt`, dialog and sheet components under `ui/src/shared/**`, `MainActivity.kt`
(only the back handling, minimal edits), their tests.
Done when: rules 13 to 15 hold; a table of every shortcut and remote key per platform is written
to `docs/shortcuts.md` (it exists only if missing: read first), conflicts with the universal
shortcuts fixed, Esc closes one layer at a time, focus returns to the opener after a dialog/sheet,
predictive back and the back gesture checked, targets under 48 dp listed or fixed.

### T4 Streaming storage: two tabs "Keep video" | "Save space"  (model: sonnet, depends: T1 merged)
User picked option A on 2026-11-16, without "the": row title "While streaming", tabs `Keep video` and
`Save space`, `Save space` selected on a fresh install (already committed, `d78d4df`). Same stored
boolean `low_storage_mode` (on = Save space), no migration. Reuse the existing segmented control
(`SettingsScreen.kt`, desktop `SettingsRows.kt`), do not add a new one.
files: `app/.../ui/settings/SettingsScreen.kt` (row at ~1703), desktop `SettingsRows.kt` and
`StorageSettings.kt`, `PlayerSheets.kt` and the `settingsLowStorage*` / `mainLowStorage*` /
`mainNoRoomLowStorage` / `playerNotEnoughRoomLowStorage` strings in `en.json` (the prompt text names
the mode: "Switch to Save space?"), `DownloadsScreen.kt` and desktop `Downloads.kt` call sites,
`LowStorageRenderTest.kt`, `docs/FEATURES.md`, `docs/settings-audit.md`.
Done when: phone, TV (D-pad Left/Right moves between tabs, focus ring visible) and desktop (arrows,
Tab) show the control with a detail line under the active tab ("Needs room for the whole video.
Seeking back is instant." / "Keeps only what you are watching now. Seeking back downloads it
again."); render tests per form factor; focus test; the setup's storage step and the "Recommended
for this device" card use the same words; `Notice.LowStorage` (the free space alert) keeps its name.
Edge: a download kept on purpose is never windowed, whatever the tab (say so in the detail line).

### T5 Chat picker: recommended list misses video channels, no counts  (model: sonnet, depends: T2 merged)
User, 2026-11-16: the recommended band in the chat picker (Add chats and the setup's chat step) leaves out
channels that hold a lot of videos; the video counts may be inaccurate, so show none, neither in
Recommended nor in the normal list. files: `ui/src/shared/.../ui/browse/AddChatsScreen.kt`,
`ChatSuggestions` and `ChatPicking` (core), their tests, `en.json` count strings, desktop and web picker
(`webapp/src/screens` SetupChats, AddChats). Done when: root cause of the misses found and written here
(suspects: the scan budget per refresh, a chat read cut off at a page, channels not yet opened, a minimum
count, folder or archive chats skipped, private-chat title rules), a `SyntheticAccount` test with a
channel of 20,000 videos that is recommended, counts removed from rows and from the band header on phone,
TV, desktop and web with dead strings deleted, ranking explained in one plain line to the user.
Edge cases: a chat with a few videos stays out of Recommended but findable; scale 3,000 chats within budget.

### T6 Mini player check  (model: haiku, depends: Raju phone pass)
User asked 2026-11-16 whether the in-app mini player (video keeps playing while browsing) is missing. Docs
say it exists on the phone (`docs/mini-player-phone.md`, `MiniDockBar`, `MiniPlayerBar`) and desktop
(Ctrl+P), never on TV (by design, `MiniPlayerState.allowedOn`). Verify on the phone with the Raju pass; if it
is not showing, find why.

### T7 Player engines and the automatic remedy ladder  (model: opus high, depends: research doc)
From `private/PLAYER-RELIABILITY-RESEARCH.md` (agent X1): detection signals and the one-switch-per-video
ladder; PR1, PR2, PR2b, PR3 in `private/PLAYER-RELIABILITY-PLAN.md`. Also: Low storage mode does not start
a film on phone and TV (HX79 evidence note): diagnose with logcat first, fix, add a test that a rolling
window starts.

### D1 Support cards: Buy Me a Coffee and UPI  (model: sonnet, running since 2026-11-16)
Themed cards and dialogs with the official logos, regional order (IN: UPI first), icon popup through the
existing `SupportReminder` nag. Design note `design/redesign/support-cards.md`.

## Not in this pass
Store text, the site.

## Progress / handoff
- 2026-11-16: plan written. Fresh-install default for the streaming mode is committed separately
  (`armSetup`). Workers T1, T2, T3 start from that commit.
- 2026-11-16 T3 done (branch worktree-agent-a443a0c6839e96372, local, not pushed):
  - `19715dc` Esc closes a `TmAlertDialog` on every platform (`escapeCloses`), and on the desktop the layer
    over the page (Settings, detail pane, menu, palette) closes before full screen (`ShellState.transientLayerOpen`);
    `ShortcutsAuditTest` (dialog Esc and focus back on the opener, Esc order, Ctrl+C/V/X/Z/A untouched, Tab).
  - `055eb76` no exit confirmation at the top level in `MainActivity` (rule 13; the system back-to-home
    animation now plays; dead string `main.back_again` removed from en.json); 48 dp for the episode panel
    buttons (`EpisodesPanelTest` fails without it), the Free up space buttons and the subtitle swatches.
  - `e4cb9e1` `docs/shortcuts.md` (a table per platform, Esc order, targets under 48 dp), `929b5c1` PlayerKeysTest.
  - Checks: `:desktop:test` for ShortcutsAudit, BigScreen*, BrowseKeyboard, PlayerKeys, EpisodesPanel; `:app:compileDebugKotlin`.
    No app unit test for the MainActivity change (the whole Root composes); no device or emulator.
  - Left: `LoginScreen.kt` `ExitOnBack` still asks "press Back again" (T1's file); the player's back callback
    always claims Back (no predictive back animation from the player); brightness has no button twin;
    Series.kt phone rows are 40 dp (T2); desktop overlay buttons are 36 dp (mouse only).
  - Gotcha: `private/` is git-ignored and lives only in the main tree; Compose Desktop dialogs did not close on
    Esc in `runComposeUiTest` before `escapeCloses`; `press()` in desktop key tests needs a focused node (use `requestFocus` first).

- 2026-11-16 T2 (worktree agent-a8fbeec1c7c3f1840, local commits 57b52aa, f52246b, 6ca92f2):
  done: desktop chat grid Ctrl+A / Ctrl+Space / Enter+Space pick while selecting (ListSelectionKeysTest);
  desktop Downloads now on the shared GridSelection (Ctrl+A from nothing, Ctrl+click, Shift+click
  range, Ctrl+Space, Esc) and a deleted row hands keyboard focus to a neighbour (RowFocusHandoff,
  DownloadsSelectionKeysTest); rowAfterRemoving moved to ui/shared RowHandoff.kt (TV Downloads uses
  it); note design/redesign/list-selection.md. Audit: Android History/Downloads/TV focus restore,
  filter notes with Clear, empty states already existed and were left alone.
  left: focus handoff after removal in desktop History grid and chat list (RowFocusHandoff is ready
  to wear); Android phone/TV chat grid selection with a hardware keyboard (Ctrl+A); web grid
  selection; a Filters "Clear all" chip row when 2+ filters (FilterNote has one Clear today);
  scale test for 100,000 downloads selection.
  gotchas: Compose test mouse has no modifier keys: use performKeyInput keyDown, click, keyUp;
  a removed row loses focus BEFORE and the data change lands near it, hence the 1 s window match.

## Decisions made by Claude
- Three workers, not more: the files are split by surface and `MainActivity.kt` is shared, so only
  T3 touches it and only for back handling.
- T3: Esc handling lives in the shared `TmAlertDialog` (one place, all platforms) instead of per dialog; full screen yields to a layer over the page on the desktop; the top level's exit confirmation is deleted rather than reworded. No design note: small, clear fixes.

## Sources
- Search patterns: https://carbondesignsystem.com/patterns/search-pattern ,
  https://developer.apple.com/design/human-interface-guidelines/search-fields.md ,
  https://design.visa.com/patterns/search
- Android TV navigation: https://developer.android.com/training/tv/start/navigation ,
  https://developer.android.com/design/ui/tv/guides/foundations/navigation-on-tv
- Desktop keyboard: https://developer.android.com/design/ui/desktop/guides/interaction/keyboard ,
  https://developer.gnome.org/hig/reference/keyboard.html ,
  https://w3.org/WAI/ARIA/apg/patterns/listbox
- IME actions: https://developer.android.com/training/keyboard-input/style
- Gaps (not found in the searches): official focus-restore guidance, inline validation and predictive
  back. Check m3.material.io and developer.android.com directly before relying on them.
