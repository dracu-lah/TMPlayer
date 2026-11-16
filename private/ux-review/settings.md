# Review: settings (203 controls, 16 files), worker R3, 2026-11-16

Verdict key: ok / fixed in <commit> / left: <why>. D1 behaviour, D2 input modes, D3 motion, D4 look, D5 words, D6 speed, D7 access, D8 common person.
Commits (branch worktree-agent-ac7f613f240e2428a): 0ba39c5 DownloadShelf tie-break, 3f189a8 search box, 3769865 support/learnt/TV switch, then the cards commit (see the end).

## Settings list and search (SettingsScreen.kt, SettingsSearchBox.kt)
| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Search box clear (SettingsSearchBox L47) | ok | ok | ok | fixed 3f189a8 (shared ClearButton, was a plain Close icon) | ok | ok | ok (48 dp, label) | ok |
| Back with words in the search box | fixed 3f189a8 (clears first, then leaves; rule 2) | fixed | ok | ok | ok | ok | ok | ok |
| Category rows (Playback ... About) L2218-2344 | ok | ok (D-pad walk tests) | ok | ok | ok | ok | ok | ok |
| Search hit rows L2207 | ok (opens the page on the row) | ok | ok | ok | ok | ok | ok | ok |
| Switch rows (ToggleRow, all switches) | ok | ok | ok (140 ms fill, tick on phone) | ok | ok | ok | fixed 3769865 (television row now says On or Off; the phone's real switch already did) | ok |
| Sync switch (Resume on other devices, L1428) | ok (asks before on; off keeps the file; delete asks) | ok (Down reaches Sync now, test) | ok | ok | ok | ok | ok | ok |
| Clear favourites / Sign out / Restore / Delete sync file / Clear caches | ok (each confirms with a sentence; history clear is Undo on touch, asked on remote) | ok | ok | ok | ok | ok | ok | ok |
| Forget the last chat L1375 | ok (toast says it went) | ok | ok | ok | ok | ok | ok | ok |
| Advanced disclosure (PlaybackSettings L84) | ok | ok | ok | ok | ok | ok | left: no expanded/collapsed state word in en.json, the chevron flips only | ok |
| Reset chip on the television (L3065) | ok | ok (stays focusable greyed on purpose) | ok | ok | ok | ok | left: no Button role or disabled state for a screen reader on the TV chip (needs a test row to hold it) | ok |
| Drawn Switch, StepperRow, RangeRow, TickRow | ok | ok (Left/Right steps, ring) | ok | ok | ok | ok | left: TV stepper row has no value state description (phone arrows have labels) | ok |
| SignOutButton L3518 | ok (asks first) | ok (TV dim until focused, ring) | ok | ok | ok | ok | ok | ok |

## Remembered languages (LearntLanguagesSettings.kt)
| Forget (one) | ok (acts at once, list redraws) | ok | ok | ok | ok | ok | ok | ok |
| Forget all | fixed 3769865 (asks first with a sentence; toast after) | ok | ok | ok | fixed (2 new en.json keys, learnt_languages_forget_all_title and _message; the existing _done string is now the toast) | ok | ok | ok |

## Support, language notice, tour offer, layout nudge, what's new, feedback (Support.kt, LanguageAndFeedback.kt, LayoutSetting.kt)
| Control | verdict |
| --- | --- |
| Support window: Sponsor and Star links | fixed 3769865 (a Row squeezed the second link to a column of letters at 200 percent font; now a FlowRow). Test SettingsLargeFontTest, failed before the fix |
| Support window: Don't show / Maybe later | ok (weighted pair, wraps its words) |
| Language notice: Change / Keep | fixed (FlowRow, whole buttons wrap at 200 percent font) test SettingsLargeFontTest |
| Tour offer: No thanks / Take the tour | fixed (same) test SettingsLargeFontTest |
| Layout nudge, What's new | ok (already FlowRow) |
| Feedback panel | ok (full width buttons, Close on its own line) |
| Coffee button | ok (brand colours kept on purpose) |

## Other files
| File | verdict |
| --- | --- |
| AboutScreen.kt (notices page, Back, TV page scroll) | ok |
| DownloadLocationSettings.kt (location, forget pending folder, confirms) | ok (refusals and moves confirm; forget has a sentence) |
| OnlineMetadataSettings.kt, OnlineSubtitlesSettings.kt (keys, sign in/out, clear cache) | ok (cache clears ask when there is something to clear) |
| PlaybackTestScreen.kt, SendToDeveloper.kt, LogActions.kt | ok (Cancel first focus on destructive/sending panels, Send is explicit) |
| DevicesScreen.kt (ui/shared) | ok (End one and End all ask; Try again; Back) |
| HomeOrderEditor.kt (ui/shared) | ok (Move up/down twins for the drag handle, TV move mode tested). left: the drag handle is 40 dp, the buttons beside it are 48 dp |

## Downloads follow-up
`DownloadShelf.arrange` now breaks equal times by key (0ba39c5), unit test in DownloadShelfTest (same input in two orders gives one order). `DownloadsSelectionKeysTest` is not in this branch (it lives on main, commit 5123a5e): remove its `Thread.sleep(3)` after merging this branch; the order no longer depends on it.

## Left for the user
- Desktop `TrackLanguageSettings.kt` (desktop-ui-3 chunk): Forget all there still acts at once; give it the same confirm (keys exist now).
- Translate the two new keys (learnt_languages_forget_all_title and _message) in the other catalogs.
- Expanded/collapsed word for the Advanced row; Button/disabled semantics on the TV Reset chip; value description on the TV stepper row.
- The sync switch is under Library in this tree (key `settings.resume_sync`), not Account; syncplace.switch is not in this branch, nothing moved.
