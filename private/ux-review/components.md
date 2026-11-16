# Review: components (R7)

Inventory is stale in places (ConnectionStatus no longer has the Downloads click); reviewed the current code.

| Component | Finding | Verdict |
| --- | --- | --- |
| ChoicePopover rows + dim + card | dim and card were Tab stops; no keyboard ring; fixed 44 dp row cut long words at a big font | fixed in 82c9aa3 (test: ControlReviewR7Test) |
| ChoiceRow (phone / desktop pickers) | no shared focus ring | fixed in 82c9aa3 |
| TvChoiceRow (TV pickers) | radio did not say selected, greyed row not disabled for a reader | fixed in b46fc75 |
| FloatingRoundButton, BarIconBody | no keyboard ring, default colour springs | fixed in e6611df |
| AccentSwatches | grow ignored Reduce motion, scale read in composition | fixed in e6611df |
| FreeUpSpace | two literal tween(140) instead of Motion; result line silent | fixed in e6611df |
| TvMenu | literal tween(140), rows with no role | fixed in b46fc75 |
| StateScaffold slow-answer row | long line pushed Retry off the card | fixed in b46fc75 |
| ConnectionStatus / StartingStatus | pills not read aloud | fixed in b46fc75 |
| CustomAccentPicker | sliders had no names | fixed in b46fc75 |
| AddTvDialog | error line not read aloud | fixed in b46fc75 |
| TrailerPlayer overlay buttons | 40 dp on a phone, no role | fixed in b46fc75 |
| ViewingSummary Count | label clipped at 200% | fixed in b46fc75 |
| Holdable, TvSearchField, Toast, TvConfirm, TmButton, ClearButton, ChooseChatsButton, MiniPlayerBar, ScrollToTop, LoadAhead, FolderCopyDialogs, UpdateCard, TvReach, BackHandler | walked D1 to D8 | ok |

## Left for the user
- The same Tab-stop dim/card pattern (`clickable(indication = null) {}`) remains outside this chunk: CastRemote.kt:151, MetaExtrasUi.kt:576, TouchNav.kt:973/1008, PlayerOverlay.kt:1695 (R8's). Candidate for one shared `scrimDismiss`/`swallowClicks` in ui.
- ChoicePopover writes `origin` (state) from the layout phase (cross-phase write, only when the spot changes): works, would need a rework to remove.
- Popover ChoiceRow keeps Role.Button for a chosen line; a RadioButton role would be more exact but changes tests and reader wording.
- MiniPlayerBar's body and buttons have no ring on a keyboard (ripple only).
