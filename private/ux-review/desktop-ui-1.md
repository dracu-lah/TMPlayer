# Review: desktop-ui-1 (R1, 2026-11-16, 259 controls, 29 files)

Legend: ok = judged, nothing to change. fixed = commit on this branch (see the handoff block in
private/UX-INTERACTION-PLAN.md for hashes). left = reason given.
Machine checks: `desktop/src/test/kotlin/com/tmplayer/desktop/ui/ControlReviewR1Test.kt` (8 tests: roles,
Enter, focus ring pixels, checkbox state, scrim not a Tab stop, Ctrl+A, per-item remove).

## Player bar and overlay (PlayerOverlay.kt, 59 controls)
| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| OverlayButton (back, more, prev/next, subtitles, audio, mute, mini, close) | ok | fixed: Tab ring (white, only for keyboard focus), role Button | ok (hover wash) | fixed: one OverlayHover token instead of 5 copies of 0x33FFFFFF | ok | ok | fixed: role Button | ok |
| Play/pause disc | ok | fixed: ring, role | ok (glyph turn, reduced motion respected) | ok | ok | ok | ok (state and live region existed) | ok |
| Speed pill ("1x") | ok | fixed: ring, role | ok | ok | ok | ok | fixed: description "Speed: 1x" (it read "1x" alone) | ok (tooltip) |
| Shape button | ok | fixed (an OverlayButton) | ok (pop) | ok | ok | ok | ok | ok |
| Time left / total toggle | ok | fixed: ring, role | ok | ok | fixed: tooltip and click label from existing playerShowTimeRemaining / playerShowTotalLength | ok | fixed: click label | fixed: was an unmarked clickable number |
| Skip pill, Undo-seek chip | ok | fixed: ring, role | ok (slide, press scale, reduced) | fixed: hover token | ok | ok | fixed: role | ok |
| Episodes pill | ok | fixed | ok | fixed | ok | ok | fixed | ok |
| Menu rows (Entry) | ok | ok (Material menu focus) | ok | ok | ok | ok | fixed: the ticked row is `selected` for a reader | ok |
| Menu panel Back line and Close | ok | fixed: ring, role, click label "Back" | ok | ok | ok | ok | fixed | ok |
| Volume slider | ok (not focusable on purpose) | ok | ok | ok | ok | ok | left: no slider description (Material default); the mute button beside it carries the name | ok |
| Dim behind the Episodes and details sheets | ok | fixed: not a Tab stop, no screen-wide ripple | ok | ok | ok | ok | ok | ok |
| Nudge buttons, Next-up (Start over, Play now, Hide), end states (Free up space, Try again, Back, Watch again, Keep watching) | ok | ok (first action takes focus) | ok | ok | ok | ok | ok | ok |
| Key handlers (arrows move focus in the panel) | ok (Esc is the player's, closes the top layer only) | ok | n/a | n/a | n/a | ok | n/a | n/a |

## Player: other files
| Control | Verdict |
| --- | --- |
| CastControls: Cast button, device popover, Try again, device rows, Add a TV, Stop | ok D1 to D6 and D8. left D7: the ticked device (Check icon) and the spinners have no description; the row text carries the name, a reader hears no "connected" (needs a new string) |
| CastControls: Reconnect, Try again, Play here | ok |
| OnlineSubtitlesPanel: Close, Try again, Check again, Pick file, release links | ok |
| OnlineSubtitlesPanel: result rows | fixed: role Button and ring (were plain clickables) |
| PlayerBookmarks: note dialog, Go to time dialog | ok (field focused, Enter saves, a bad time keeps the dialog) |
| PlayerBookmarks: jump row | fixed: role Button and ring |
| PlayerBookmarks: delete (was a cross, read as "close") | fixed: bin icon, same description |
| PlayerLoaderSheet (Try again, lighter copy, Download first, Go back) | ok: Try again takes focus, no trap |
| PlayerLock Unlock | ok: deliberately not focusable (the keyboard must not be a one-press way out), hold Enter is shown |
| PlayerKeyRoute, PlayerScreen key hooks, DevPlayerMain | ok: routed before the page behind (PlayerKeyRouteTest) |

## Chats, search, info (ChatChrome, Chats, ChatInfoPanel, Common)
| Control | Verdict |
| --- | --- |
| Back to chats (Esc), Info | ok |
| Chat search field: Enter submits, Esc clears then closes, Down goes to the grid, clear button | ok (T1 covered) |
| Recent searches panel | fixed: per-item remove (rule 6 had only Clear all); each cross is named "Remove: <search>". left: no "none for private chats" (no per-chat flag reaches the bar; the list is global), no Undo for Clear all on desktop (the phone has one) |
| Chat row (open, S star, H hide, context menu, hover prefetch) | fixed: role Button. rest ok (ring, keys, menu shows the key) |
| Filter chips, Choose chats button | ok |
| ChatInfoPanel (copy username, copy link, open, Try again, Close) | ok |
| Common: state boxes, SlowAnswerLine, EmptyNote, ConfirmDialog | ok (Cancel focused when destructive, confirm for invitations) |
| Common.RecentSearchRow | left: unused (dead code), delete in a clean-up |
| Common search field (BackHandler, key hook) | ok (T1) |

## Crash question, About, History, Downloads
| Control | Verdict |
| --- | --- |
| CrashQuestion "Always send" | fixed: was a clickable row around a Checkbox with no state, now one toggleable checkbox (click and Space) |
| About back arrow, Coffee, Sponsors, Notices | ok |
| About version line (7 taps) | fixed: was a Tab stop with no mark; now pointer only |
| HistorySelection bar (Select all/none, Done, Remove n) | ok (Remove disabled at 0) |
| History TickBox | fixed: toggleable checkbox (state heard). left: 26 dp target, a pointer-first desktop control |
| Downloads: bulk bar, Pause/Resume all, Open folder, Select all, Play random | ok |
| Downloads: Ctrl+A | fixed: picked everything only once a selection existed; now from the start (rule 8). Esc clears (existed) |
| Downloads: rows (VideoRow, pick, More menu, right click, Mac ctrl-click) | ok (ring from keyboardFocused) |
| Downloads: `onFreeUp!!` | fixed: smart cast instead of `!!` |
| Downloads: Cancel (cross) on a running download deletes the part fetched with no question | left: asking "Stop and delete 2.4 GB?" is a product call, see the list below |
| UnlistedRow Delete | ok (confirm dialog exists) |
| GridSelection bar (Select all, Clear, Download selected) | ok (the bar goes at 0 selected) |

## Home
| Control | Verdict |
| --- | --- |
| Carousel (arrow keys, Enter details, Space play, dots, wheel) | ok |
| Carousel round buttons (Details, arrows) | fixed: accent ring on keyboard focus (only Material's faint layer before) |
| Strip arrows | ok: pointer only on purpose (canFocus false), the keyboard goes card to card |
| See all link | ok (underline for keyboard focus, press scale) |
| Row page Back arrow | fixed: keyboard ring and "Esc" in its tooltip, as About's |
| Row card menus (Remove, Open details, Watch later) | ok |
| Slow-Telegram Refresh | ok |

## Dialogs and notices (LanguageAndFeedback, DesktopChecks, DetailPane)
| Control | Verdict |
| --- | --- |
| Language, What's new, Feedback, language keep/change, update prompts | ok (TmAlertDialog; Esc closes) |
| DesktopChecks notice buttons (Play, Free up, Buy a coffee, Star, Not now) | ok |
| DetailPane: Esc closes, scrim click closes, focus held in the sheet | ok |

## Not UI (judged, nothing to do)
Main.kt key hooks, KeyboardNav (arrows, Home, End; Alt and Shift left alone), FocusVisible, IdleCursor
(cursor hides after idle, wakes on move or key, the loop stops while disabled), X11SideScroll,
LinuxNotifications NoticeButton, PlayerExtras.

## Left for the user
- Cancel on a running or paused download deletes the part with no confirmation (Downloads ActiveRow).
  Suggest a confirm only past a threshold (say 100 MB fetched): "Stop and delete {size} already downloaded?".
- Cast: a spoken "connected" for the ticked device (needs a string).
- Desktop chat bar recents: Clear all has no Undo (the phone has one via restoreRecentSearches); private chats still show the global list.
- Delete the unused `RecentSearchRow` in Common.kt.
- The ring is pixel-tested on OverlayButton only; the Skip, Undo, Episodes, speed, Home Details and Back-line users
  share the same modifier but have no render test of their own. The Home row page Back arrow change is compile-checked only.
- Only the Linux JVM ran the tests; the ring and checkbox are Compose, not AWT, so no per-OS difference is expected,
  but Windows and macOS were not run.

## Worker handoff: R1 desktop-ui-1 (branch worktree-agent-a9d6ca6df3064c029, local, not pushed)
Note: private/UX-INTERACTION-PLAN.md is not in this worktree (untracked in the main tree), so the handoff lives here; paste into the plan on merge.
- Done: commits 65ebe33 (player controls), a90013a (checkboxes, version tap, chat row), d65e65f (recents remove, Ctrl+A, `!!`, tests), then the scrim, Home back arrow and this note.
- Checks run: ControlReviewR1Test (8), 13 existing player/browse/downloads render and key test classes, final check line (CatalogTest, app and desktop compile incl. tests): all green.
- Not run: whole desktop suite, Windows/macOS.
- Decisions: white ring in the player (always dark); accent ring on the Home hero; the cancel-download confirm and the Cast "connected" string are left for the user; `local.properties` copied into the worktree only to build (git-ignored).
- Gotcha: `OverlayButton` now shares one InteractionSource for hover, focus and click; any new player control should use `overlayFocusRing` + `OverlayHover`.
