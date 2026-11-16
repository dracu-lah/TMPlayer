# Review: flows (R6, 2026-11-16, 228 controls, 21 files)

Legend: ok = judged, nothing to change. fixed = commit on this branch. left = reason given.
Machine checks: `app/src/test/java/com/tmplayer/ui/flows/FlowsReviewTest.kt` (5 tests: sign in error is a live region, a countdown line can opt out, the phone update dialog's Close while downloading, the selection bar at 200% font, a proxy link that fails says why and Add comes back).
Commits: a22fb99 (live errors, PIN), 8f5c8ee (proxy), 631c1e5 (update dialog), b8b4497 (selection bar), bf52e00 (setup frame), 9f59331 (tests).
Inventory drift: the inventory was cut before the one question setup (38d2bdc). `SetupScreen.kt`, `CrashAskCard.kt` and `CrashReportsQuestion.kt` no longer exist; their controls (about 25) are gone with the old setup pages. `SetupChoices.kt` now only serves Settings' "Redo setup choices" (R3's files). `StepFrame` is used by the tour alone.

## MainActivity.kt (21)
| Control | Verdict |
| --- | --- |
| Sign in footer More menu (Proxy settings, Send logs), proxy and logs buttons | ok. 48 dp IconButton named "More options", menu closes on pick. A way out of every failed sign in (Send logs) and the proxy screen is one step away. |
| BackHandlers (chat, settings, about, tour) | ok (D2): each leaves one level; the sign in's first screen has none on purpose (Back leaves the app). |
| onKeyDown / onPreviewKeyEvent (L544, L1545) | ok (global key routing, not a control). |

## AuthFields.kt (5), LoginScreen.kt (49)
| Control | D1..D8 |
| --- | --- |
| Pane fields (phone, code, password, email), Done key submits only when allowed | ok all. Focus lands in the field when it opens, on a remote a closed field waits on Back. |
| Error line under every pane (phone, code, password, recovery, reset) | D7 fixed (a22fb99): was plain text, so TalkBack said nothing when a code was refused. One `PaneError` (live region, polite) replaces four copies. |
| Back arrow (phone), Back / Start over / Wrong number buttons | ok. Arrow is 48 dp with tooltip; system Back goes one pane back; the first phone screen leaves the app. |
| Submit buttons (busy label, disabled until valid) | ok |
| Country chip, Choose country, picker (BackHandler, search, clear, rows) | ok. Picker: Back closes, phone opens in the search field, a remote starts on the selected row, empty result says so, ClearButton. |
| Use QR / Use phone, Refresh QR (on white) | ok. Refresh keeps focus on a remote, QR has a description. |
| Show/Hide password toggle (IconButton + button on remote) | ok (named, state-aware description). |
| Forgot password, Reset account (confirm step, Cancel focused first on remote) | ok. Back leaves the confirmation first, then the pane; a reset is never one stray press away. |
| Resend row (countdown, busy, none-left text), Reset email | ok (Raju's changes, not touched). |
| Proxy link on a slow submit | ok |

## ConnectHelp.kt (4), SessionEnded
| Try again, Use a proxy / More help | ok. Machine tests exist (ConnectHelpFocusTest: remote order, 200% font). |

## ProxyScreen.kt (12)
| Control | D1..D8 |
| --- | --- |
| Back, link field, Add proxy, Enter it yourself | ok. Back/Esc closes (BackHandler); a remote lands in the link field. |
| Use, Remove, Stop using proxy | D1 fixed (8f5c8ee): their error answers (and thrown failures) were dropped, so a failing press looked dead. Now a danger line with a live region shows it. |
| Add (link and by hand) | D1 fixed (8f5c8ee): `busy` is reset in `finally`, so an exception can no longer leave the button on "Testing". |
| Test | D1 fixed: a failing ping can no longer escape the scope. |
| Manual kind buttons (MTProto, SOCKS5, HTTP) | left D7: the picked kind is shown only by the primary fill, no selected state for a screen reader. Needs a `selected` flag on the shared SetupButton (one more parameter). |
| Manual form errors | D7 fixed (live region). |

## PinPad.kt (8)
| Control | D1..D8 |
| --- | --- |
| Digit keys, 0, Delete, Cancel, hardware digits and Backspace | ok. 64/72 dp keys, ring, press scale, haptics, Cancel only when cancellable; Back is Cancel. |
| Wrong PIN and wait lines | D7 fixed (a22fb99): live region. The wait is spoken once when it starts (a fixed spoken text per wait), never the countdown every second. |
| Dots | ok (one spoken description of how many are entered). |

## UpdateDialog.kt (16), ReleasePage.kt (1)
| Control | D1..D8 |
| --- | --- |
| TV: Update now / Release page / Later / Skip (focus on Update now, FlowRow wraps), Back closes and download carries on | ok |
| Phone: Update now, Release page, Skip, Later, Close | ok |
| Phone dialog while a download runs | D1 fixed (631c1e5): there was no button and Back and outside taps were swallowed, so a viewer sat in front of a bar with no way out. Now Close and Back hide it (the download runs on the process scope, as on TV). An outside tap is still ignored while it runs. |
| Release page QR, Close | ok (focus on Close) |

## DownloadsScreen.kt (46)
| Control | D1..D8 |
| --- | --- |
| Selection (hold, tick, Select all, Back leaves selecting, top bar close) | ok |
| Bottom selection bar | D4/D7 fixed (b8b4497): Share, Save to folder, Save to gallery and Delete were four weighted buttons in about 330 dp, so every label was cut to "Sa..." even at normal font. Now Share keeps its word, the others are the 48 dp icon buttons the cards already use, each named for a screen reader. `PickingActions` is tested at 200% font. |
| Delete many (confirm dialog), Save to folder dialogs | ok (existing confirm, space check in FolderExport) |
| Card More menu, TvPill actions, sort menu | ok. Card tap plays, hold selects, the menu has Keep / Share / Save / Delete. |
| left D4: `SecondaryAction` is still used by the card rows (kept). |

## Onboarding and tour: StepChrome.kt (9), TourScreen.kt (1), SetupParts.kt, DeviceFitUi.kt (4), LanguageList.kt (1), SetupChoices.kt (12), TipsCard
| Control | D1..D8 |
| --- | --- |
| Skip all, Back (quiet buttons) | D7 fixed (bf52e00): 40 dp tall and fixed, now 48 dp minimum and growing with the font. |
| Step heading | D7 fixed (bf52e00): marked as a heading for a screen reader. |
| Tour Next / Done / Back / Skip all, swipe, Back key = previous page or done | ok. Next has focus on every page, first page's Back ends the tour, the swipe has the Next and Back buttons as twins. |
| Tips card (Show me / Not now) | ok. A remote lands on Show me and is held in the card on purpose, Back is Not now. |
| Device fit hint bar (Apply / Not now) | ok |
| Help translate (LanguageList) | left D1: `runCatching { openUri }` does nothing on a TV with no browser. Needs the update dialog's QR fallback; lives in Settings (R3). |
| SetupChoices (Redo setup choices, switches) | not reviewed here: R3's settings files. |
| Tour badge | left D4: a fixed 28 dp circle, at 200% font the number can touch its edge. Cosmetic. |

## CountryPicker.kt (5), QrScanned.kt (3), MetaExtrasUi.kt (8)
| Country picker, QR refresh and "scanned" panel (live regions), cast enlarge dialog (Back, outside tap and Close, focus on Close) | ok |

## Left for the user
- Manual proxy kind buttons have no selected state for TalkBack (shared `SetupButton` needs a `selected` parameter).
- "Help translate" on a TV with no browser does nothing (add the QR fallback the update dialog uses; Settings file).
- Tour badge at 200% font (cosmetic).
- Dead code to delete when convenient: nothing found beyond the files the one question setup already removed; `SetupChoices.kt` is live only through Settings.
- Not run by hand (no devices): TalkBack announcement of the new live regions, the phone update dialog on a real download.
