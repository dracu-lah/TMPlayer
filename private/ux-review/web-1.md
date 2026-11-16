# Review: web-1 (247 controls, 18 files) by R9, 2026-11-16

Method: a machine sweep over 14 screens at phone 390 and desktop 1280 (`webapp/e2e/ux-audit.spec.ts`: every
interactive element has a name, sizes logged, a 320 px reflow check), axe (`a11y.spec.ts`), keyboard walks written as
Playwright tests, and a read of each file's controls. Verdict cells: ok / fixed (batch) / left (why).
Fix batches: A = "Tab brings the player's hidden controls back ..." , B = "moving a Home row ...", C = "sort menu is keyboard whole ...",
D = "the seek bar shows the keyboard ...", E = "reduced motion ...", F = "sheet over the player takes the keyboard ...". Hashes are in the handoff.

D1 behaviour, D2 input modes, D3 motion, D4 look, D5 words, D6 speed, D7 access, D8 common person.

## App.tsx (5)
| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Try again (boot error) | ok | ok | ok | ok | ok | ok | fixed A: the message is `role=alert` | ok |
| Try again (error boundary) | ok | ok | ok | ok | fixed A: was hard-coded English, now `common.try_again` | ok | fixed A: alert | ok |
| Use it here (other tab) | ok | fixed A: takes the keyboard on open (Enter works at once) | ok | ok | ok | ok | ok | ok |
| Later / Reload (update notice) | ok | ok | ok | ok | ok | ok | ok (status role) | ok |

## player/Chrome.tsx (84)
| Control group | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Top row: Back, More | ok | fixed A: Tab into a hidden row shows it, and it stays up while the keyboard is on a control | ok (reduced motion respected in player.css) | ok | ok | ok | ok (names, tooltips only under a pointer) | ok |
| Centre: Previous, Play/Pause, Next | ok | ok | ok | ok | ok | ok | ok (48 px phone, 40/56 desktop with a mouse) | ok |
| Seek bar (slider) | ok | fixed D: the keyboard on the bar grows the thumb with a white ring (the old `outline: none` left no sign); arrows seek 10 s | ok | ok | ok | ok | ok (value text) | ok |
| Time readout (total / remaining) | ok | ok | ok | ok | ok | ok | fixed D: phone press area 44 px tall (was 18) | ok |
| Row: Subtitles, Audio, Speed, Shape, Lock, More, Fullscreen, Mute | ok | ok | ok | ok | ok | ok | left: desktop buttons are 40 px (mouse), as the apps' desktop; phone 48 | ok |
| Chips (copies, episodes, loop, back, skip) | ok | ok | ok | ok | ok | ok | ok (44 px desktop chips) | ok |
| Offer and next-episode card | ok | ok | ok | ok | ok | ok | ok (group label, status count) | ok |
| More options entries (about 30) | ok | fixed D: white focus ring on the keyboard's line | ok | ok | ok | ok | ok (48 px lines) | ok |
| Sheets over the player | ok | fixed F: the controls behind a sheet are `inert` (Tab cannot reach them); the sheet takes the keyboard | ok | ok | ok | ok | ok | ok |

## player/Player.tsx (7), extra-sheets.tsx (13), loader.tsx (1), msone-sheet.tsx (7), sheets.tsx (13)
| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Notice close, failure card buttons | ok | ok | ok | ok | ok | ok | ok | ok |
| Bookmarks, note editor, timing sheet | ok | ok (note field focused on open) | ok | ok | ok | ok | ok | ok |
| Shortcuts sheet | ok | fixed F: close button takes the keyboard on open | ok | ok | ok | ok | ok | ok |
| Panel, PhoneSheet, Menu, Choice, Entry | ok | fixed D: focus ring on lines | ok | ok | ok | ok | ok | ok |
| MSone sheet buttons | ok | ok | ok | ok | ok | ok | ok | ok |
| Loader Back | ok | fixed D: focus ring | ok | ok | ok | ok | ok | ok |

## screens/AddChats.tsx (15), Chats.tsx (5), ChatInfo.tsx (4)
| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Search field | ok | ok | ok | fixed: 2 px focus ring on every text field | ok | ok | ok | ok |
| Scope tabs, Select all, Try again | ok | ok | ok | ok | ok | ok | fixed: 48 px hit area under a finger (tabs were 32 px) | ok |
| Star buttons | ok | ok | ok | ok | ok | ok | fixed: the name already says add or remove, the extra `aria-pressed` made a screen reader say both | ok |
| Tick boxes, Done, confirm dialogs | ok | ok (native dialog) | ok | ok | ok | ok | ok | ok |
| Chat info: username, Share, Copy, Try again | ok | ok | ok | ok | ok | ok | fixed: 48 px hit area for outline buttons | ok |

## screens/Chat.tsx (25)
| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Back, Info, Refresh, Search toggle | ok | ok | ok | ok | ok | ok | ok (48 px under a finger, U19 test) | ok |
| Sort menu | ok | fixed C: opens on its first line, Up/Down/Home/End walk, Escape or a pick gives the keyboard back to its button, Tab leaves | ok | ok | ok | ok | fixed C: `aria-haspopup="menu"` | ok |
| Chat search field, Try again, Keep looking | ok | ok | ok | ok | ok | ok | ok (error is an alert) | ok |
| Star cell | ok | ok | ok | ok | ok ("Favourite") | ok | ok | ok |

## screens/Detail.tsx (21), Hero.tsx (8), Home.tsx (3)
| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Back, Play, round buttons | ok | ok | fixed E: Episodes scroll jumps where motion is reduced | ok | ok | ok | ok (constant label plus `aria-pressed`) | ok |
| Copies list | ok | fixed C: arrows and Home/End walk it | ok | ok | ok | ok | fixed C: valid listbox structure (`li` is presentation) | ok |
| Season tabs | ok | fixed C: arrows walk and select (a TV keeps its remote's walk) | ok | ok | ok | ok | ok | ok |
| Mark dialogs, gap card | ok | ok (native dialog) | ok | ok | ok | ok | ok | ok |
| Hero carousel and dots | ok | ok (Left/Right turn it, Enter opens) | ok (auto-advance off under reduced motion, paused on hover or focus) | ok | ok | ok | ok | ok |
| Home row arrows | ok | ok (mouse affordance, the cards are the keyboard's way) | fixed E: smooth scroll jumps where motion is reduced | ok | ok | ok | ok | ok |

## screens/Library.tsx (10), Lists.tsx (20), Recent.tsx (2), Search.tsx (4)
| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Select, Clear, Remove, select bar | ok | ok (Shift-click ranges, native dialog) | ok | ok | ok | ok | ok (toolbar label, live count) | ok |
| Hover tick | ok | ok (desktop only, shown on keyboard focus) | ok | ok | ok | ok | ok | ok |
| Lists dialogs, name field | ok | ok | ok | fixed: 16 px so iPhone does not zoom on focus (was 15) | ok | ok | ok | ok |
| History tabs, New list, buttons | ok | ok | ok | ok | ok | ok | fixed: 48 px hit area under a finger | ok |
| Search field, clear, results | ok | ok | ok | ok | ok | ok | fixed: the wait, the count and the "none found" line are one live region | ok |

## Left for the user
- WebKit and iPhone e2e projects were not run (no WebKit here): the `:focus-visible`-based keyboard follow-ups (player Tab, bar ring) should be looked at once in Safari.
- Desktop 40 px player buttons stay (mouse targets, as the apps' desktop); a touch laptop gets 48 only in the phone skin.
- TV: nothing changed in the remote's walk; the new arrow handlers and `inert` are skipped on the TV skin. No machine focus test exists for the TV Detail season chips.
- Hero dots are a `tablist` that the section's Left/Right already walks; a screen reader's arrow on a dot turns the page, which is the expected result.
