# Review: web-2 (107 controls, 13 files) by R9, 2026-11-16

Method as web-1.md (sweep, axe, keyboard tests, file read). Batches: A = App and error alerts, B = Home order keyboard,
C = menus on arrows, D = focus rings, E = reduced motion. Hashes are in the handoff.

## screens/Settings.tsx (32)
| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Back (two copies) | ok | ok | ok | ok | ok | ok | ok (`common.back`) | ok |
| Switch rows (`role=switch`, `aria-checked`) | ok | ok (Enter/Space) | ok | ok | ok | ok | ok | ok |
| Home order Up/Down | ok | fixed B: after a move the keyboard stays on that row's button (it was lost to the page start); at the end of the list it goes to the other button | ok | ok | ok | ok | ok (name carries the row) | ok |
| Home order drag (desktop mouse) | ok | ok (buttons are the keyboard and touch twin) | ok | ok | ok | ok | ok | ok |
| Choices (radio groups) | ok | ok (native radios, ring on the label) | ok | ok | ok | ok | ok | ok |
| Clear history (with Undo), Sign out dialog | ok | ok (native dialog, Esc, focus returns) | ok | ok | ok | ok | ok | ok |
| Support: coffee link, UPI window, Copy, Close | ok (a refused clipboard leaves the ID to copy by hand) | ok | ok | ok | ok | ok | ok | ok |
| Install, Keep storage, Clear cache | ok | ok | ok | ok | ok | ok | ok | ok |
| Sync switch, status, Sync now (T4) | reviewed, no change | ok | ok | ok | ok | ok | ok (`role=status`, `aria-busy`) | ok |
| TMDB credit link | ok | ok | ok | ok | ok | ok | ok (`aria-label` is the brand name) | ok |

## screens/SignIn.tsx (30)
| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Phone, code, email, password fields | ok | ok (`autocomplete`, `inputmode`, `one-time-code`, Enter submits) | ok | fixed: 2 px focus ring (border plus 1 px line) | ok | ok | ok (error is an alert) | ok |
| Country picker (dialog) | ok | ok (search field focused, Close) | ok | ok | ok | ok | ok | ok |
| QR, Refresh, Use phone | ok | ok | ok | ok | ok | ok | ok (live region) | ok |
| Show password | ok | ok | ok | ok | ok | ok | ok | ok |
| Links (Resend, Use email) | ok | ok | ok | ok | ok | ok | fixed: 48 px hit area under a finger | ok |

## screens/Sponsored.tsx (5), VideoList.tsx (2), Watch.tsx (2)
| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Sponsored open, Report, report dialog | ok | ok (native dialog) | ok | ok | ok | ok | ok (status role; the picture button is a hidden twin) | ok |
| Video list cards | ok | ok | ok | ok | ok | ok | ok | ok |
| Watch error card | ok | ok | ok | ok | ok | ok | fixed A: error is an alert | ok |

## tv/Keypad.tsx (3), tv/index.tsx (1)
| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Digits and Delete | ok | ok (first digit takes the remote on open) | ok | ok | ok | ok | ok | ok |
| TV tab bar | ok | ok | ok | ok | ok | ok | fixed A: the nav label was the English word "Main", now the app name | ok |

## ui/CardMenu.tsx (9), Toast.tsx (2), PageBar.tsx (1), ChooseChatsButton.tsx (1), Nav.tsx (12)
| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Card menu lines | ok | ok (long press, right click and the keyboard's Menu key all open it; native dialog returns focus) | ok | ok | ok | ok | ok | ok |
| Toast and Undo | ok | ok (stays while the keyboard is in it) | ok | ok | ok | ok | ok (`role=status`) | ok |
| Back (page bar) | ok | ok | ok | ok | fixed A: label was the English word "Back", now `common.back` | ok | fixed A | ok |
| Choose chats link | ok | ok | ok | ok | ok | ok | ok | ok |
| Nav cells | ok | ok | ok | ok | fixed A: nav label "Main" to the app name | ok | ok | ok |
| You menu | ok | fixed C: Tab closes it and carries on from its button, Home and End walk | ok | ok | ok | ok | ok | ok |

## ui/PosterPicker.tsx (7)
| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Choice rows, also-others tick, Reset, Cancel, Try again | ok | ok (first row takes the keyboard, Up and Down walk) | ok | ok | ok | ok | ok (live list) | ok |

## Left for the user
- Home order: dragging has no touch version; the Up and Down buttons are the touch and keyboard way (as designed).
- PosterPicker's own walk (Up and Down only) does not have Home and End; not worth a second helper.
- The a11y "Search" test used a pre-T4 address; it now types its words.
