# Review: desktop-ui-3 (R7)

Note: the inventory is stale for Support.kt (the dialog moved into the shared SupportDialog, SupportCards.kt); reviewed the current code.

| Screen / control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Subtitles page: Forget all (TrackLanguageSettings) | fixed in 1b0c4d4 (asks first, toast when done) | ok | ok | ok | ok (existing keys) | ok | ok | ok |
| Subtitles page: per-series Forget, language menu | ok (one series, low stakes) | ok | ok | ok | ok | ok | ok | ok |
| Support dialogs, CoffeeButton | ok | ok | ok | ok | ok | ok | ok | ok |
| TvTopBar tabs, picture, Choose chats | ok | ok (ring, Down handoff) | ok (Reduce motion respected) | ok | ok | ok (scale read in graphicsLayer) | left: update dot on the picture is not announced | ok |
| UpdatePopup buttons | ok | ok | ok | ok | ok | ok | ok | ok |
| Watched / Watch later header buttons, menus | ok (Clear has Undo) | ok | ok | ok | ok | ok | ok | ok |
| Window buttons (minimise, maximise, close) | ok | fixed in 7ba0eae (Tab ring, button role) | ok | ok | ok | ok | ok | ok |
| WindowRecovery card | ok | ok | ok | ok | ok | ok | ok | ok |

## Left for the user
- TvTopBar: the update dot on the picture has no spoken name (picture label only).
- WindowRecovery: the two buttons sit in a Row; a very narrow window at 200% font may clip Settings (FlowRow would wrap).
