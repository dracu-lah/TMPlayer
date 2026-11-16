# Review: desktop-ui-2 (R4, 2026-11-16, 251 controls, 19 files)

Legend: ok = judged, nothing to change. fixed = commit on this branch. left = reason given.
Machine checks: `desktop/src/test/kotlin/com/tmplayer/desktop/ui/ControlReviewR4Test.kt` (5 tests: check row role, click and Space; dim and card; poster buttons stay under keyboard focus; key dialog focus and Enter; rename dialog focus).
Commits: c376561 (dims, palette), 39e08c1 (check rows, picker), 9196edc (posters, shows), a70f4e0 (dialogs with a field, tests).
Global note: Material buttons, chips and menu rows get their keyboard mark from `ProvideFocusVisibility` (a state layer shown only while focus is visible), so they are ok for D2 as built; only custom clickables were reviewed for ring and role.
Files edited by R3 (Settings.kt, SettingsPages.kt, SettingsRows.kt, StorageSettings.kt) were read for the inventory only and left to R3.

## LinkDialogs.kt (14)
| Control | D1..D8 |
| --- | --- |
| Close, Try again, Look first, Open in Telegram (first one takes focus) | ok all. Esc closes through DesktopSheet. |
| More videos rows (Surface onClick) | ok (role Button from Surface, hover hand, Material focus layer). left D7: 48 dp art is decorative, the title carries the name. |
| Entry rows (round videos, pinned) | ok |

## ListsPage.kt (2)
| Back handler, Back arrow | ok (tooltip, description, Esc through BackHandler). |

## MediaGrid.kt (57)
| Control | Verdict |
| --- | --- |
| Filter note buttons (Show them, Keep looking, Clear filter, Show filtered again), Try again, Keep looking, Clear history, Select | ok |
| Poster tile (click, double click, Enter/Space/P, I, menu key, right click, Ctrl+Space) | fixed (9196edc): role Button (was an unmarked clickable). D2 fixed: the Play and More buttons vanished when Tab moved focus into them (the tile tracked its own focus only); now `hasFocus`. Test: poster test. |
| Play disc on a hovered or focused poster | fixed (9196edc): a Surface button (role, focus layer) instead of a clickable on a Surface |
| More button (IconButton) | ok (named, tooltip) |
| Tile menu items (play, from start, mark, later, list, download, cancel, remove, open elsewhere, copy link) | ok (shared TmDropdownMenu, free space shown on the download line) |
| Sponsored card (button, Report, reasons dialog) | ok |
| Metadata invite dialog and notice card | ok |
| Archive rows | ok. left D2: no hand cursor (cosmetic). |
| History tick box (26 dp circle, toggleable Checkbox) | ok. left D7: 26 dp target, the poster click ticks too and Ctrl/Shift click work. |
| Poster size step (+ and -) | ok (TipIconButton, disabled at the ends) |
| Continue tile menu, Clear history | ok |
| Audio list buttons (inventory L1690 to L1746: Play all, shuffle, chips) | gone: removed by the videos-only change before this pass |

## ModalPage.kt (1)
| Dim behind the panel | fixed (c376561): was a screen-sized Tab stop; now pointer only (`scrimDismiss`, R1's). Esc closes. |

## NavBar.kt (22)
| Control | Verdict |
| --- | --- |
| Home, Chats, Search, You cells | ok (ring for keyboard only, press scale, roles Tab and Button, count in description, tooltip with shortcut) |
| Profile popover rows and Up/Down/Esc | ok (first row focused, ring-like fill, Esc closes) |
| Dim behind the popover | fixed (c376561): pointer only, not a Tab stop |
| TipIconButton | ok |

## OnlineMetadataSettings.kt (8), OnlineSubtitlesSettings.kt (9)
| Control | Verdict |
| --- | --- |
| TMDB key, OMDb key, SubDL key dialogs | fixed (a70f4e0): the keyboard now starts in the field and Enter saves (was: click first, no Enter). Test: key dialog. |
| Opensubtitles sign in dialog | fixed (a70f4e0): field focused, Enter in the password field signs in, one `signIn()` for button and Enter. left D1: no show-password toggle (needs a design call, the sign in screen has one). |
| Cancel buttons, busy label | ok |

## PosterPickerDialog.kt (8)
| Control | Verdict |
| --- | --- |
| "Also the others" row | fixed (39e08c1): one Checkbox control (`CheckRow`): role, state, Space, click on words; was a clickable row around a second Tab stop |
| Title rows | fixed (39e08c1): role Button. Up/Down, Enter, ring and first-focus kept. |
| Reset, Cancel, Try again | ok |

## RenameDialog.kt (5)
| Field, Save, Use the file name, Cancel | ok; focus code now the shared `FocusWhenShown` (a70f4e0, test) |

## SearchPalette.kt (22)
| Control | Verdict |
| --- | --- |
| Field, arrows, PgUp/PgDn, Enter, Ctrl+Enter, Esc | ok (Esc clears then closes) |
| Clear button | ok (shared ClearButton) |
| Dim and the card on it | fixed (c376561): were two clickables, both Tab stops (the card a stop with no mark that did nothing); now `scrimDismiss` and `swallowClicks` |
| Result rows | fixed (c376561): role Button, no longer Tab stops (the field keeps the keyboard, so Tab went to rows that showed no mark); mouse unchanged |
| Retry, remove recent (32 dp), filter chips, chat-type menu | ok. left D7: remove is 32 dp, row sits in a 44+ dp line and the chip row is the only path. |

## SendLogs.kt (3), SeriesViews.kt (8), SignOut.kt (1)
| Control | Verdict |
| --- | --- |
| Save logs, Report on GitHub | ok (busy guard, folder revealed, failure toast) |
| Show poster | fixed (9196edc): role Button; keeps buttons under child focus |
| Season menu | fixed (9196edc): shared TmDropdownMenu (menu fill and corner), no `.first()` on a show with no seasons |
| Show page Back/Esc | ok |
| Sign out dialog tick boxes (backup, delete downloads) | fixed (39e08c1): `CheckRow` (words tick it, Space, role) |

## Settings.kt, SettingsPages.kt, SettingsRows.kt, StorageSettings.kt (42), Shell.kt (3)
Not edited (R3 owns settings). Read-only notes: Settings.kt L819 (subtitle style dialog row) has the same toggleable-row shape and could use `CheckRow`. Shell.kt BackHandlers: ok.

## SignIn.kt (46)
All panes read: Enter submits every field (Done action), the first field takes focus, buttons disable while busy, wrong/error text shown under the field, Start over / Back everywhere, show-password toggle named, country picker is a dialog with Esc, the destructive account reset needs a second press with Cancel the prominent one. ok D1 to D8. left D8: QR pane "Use phone number" wording is the user's.

## Left for the user
- Show-password toggle on the Opensubtitles sign in dialog (new look, needs a call).
- `CheckRow` could also replace the row in Settings.kt (subtitle style dialog, L819), R3's file.
- Hand cursor on archive rows; 26 dp history tick box and 32 dp remove-recent target (pointer conveniences, keyboard and tile click cover them).
- Manual: Tab through a poster on Linux/Windows/macOS to see Play and More stay; open the search palette and Tab (focus leaves the field to the chips, not into rows).
