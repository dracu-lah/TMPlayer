# Desktop audit (2026-11-25)

Every desktop page, panel, dialog and state drawn with made-up data (the real composables, over the real
shell state) at 800x600, 1100x700, 1280x800, 1920x1080 and 2560x1440, again at a 1.3 font scale with a
48 character account name at 800x600 and 1100x700, and in the light theme at 1280x800, with the window's own
buttons on. `ScreenAuditShotsTest` draws them (`TMPLAYER_SHOTS=<dir> ./gradlew :desktop:test --tests
'*ScreenAuditShotsTest*'`; `TMPLAYER_ONLY=<part of a name>` and `TMPLAYER_SIZES=800x600fs,1280x800` narrow it)
and `ScreenInvariantsTest` asserts, over the same screens at 800x600 (font 1.3) and 1280x800: the floating bar
names every place whole in all 22 languages at font 1, 1.3 and 2; no text is hard clipped; no label of 26
characters or fewer is cut with an ellipsis; a button's label is within 2 px of its middle; and the last thing
on Home, History, a show and Favourites scrolls clear of the floating bar.

Screens drawn (57, about 440 pictures): Home (rows, every row off, Telegram slow), Chats, Favourites, a chat's
grid, a video's pane (fresh, protected), a show, History (Continue, Watch later, Watched), Downloads
(filled, empty), the account popover, Settings (the list, Account, Playback, Subtitles, Storage,
Appearance, Library, Privacy, Notifications, Help, Troubleshooting, Setup choices, About, Third-party
notices), the sign out, quit, crash, confirm, rename, shortcuts, report a problem, What's new, language,
support, update and metadata key dialogs, the language notice, the window failure card, the offline and
reconnecting banners, a toast, the PIN pad, sign in (phone, QR), the quick tour, the support step, and the
empty, "small videos hidden", error and loading states. The player and its panels (menu, picture shape,
episodes, transport bar) come from the existing `PlayerMenuPanelShotsTest`, `PlayerChromeShotsTest` and
`EpisodesShotsTest`, run for this audit and looked at. Pictures of the first two passes (before) are in the
scratch folder of the run; the ones that show a defect and its fix are in this folder.

## Found and fixed

| # | Defect | Before | After | Fix | Commit |
| --- | --- | --- | --- | --- | --- |
| 1 | The floating bar cut "Downloads" to "Downlo.." at a larger font (and would cut "Téléchargements", "Загрузки" and every longer word): its cells were a fixed 68 by 50 dp | navbar-names-800x600-fs-before.png | navbar-names-800x600-fs-after.png | `NavBar.kt`: the cells are as wide as the widest name measured at the font in use plus 6 dp each side (68 dp at least) and as tall as the caption's line; the page keeps the bar's measured height at its foot | daef2e5 |
| 2 | Settings and Downloads as a full-window panel (under 1100x700): the window's three buttons and the panel's own close drew side by side, overlapping | settings-panel-controls-800x600-fs-before.png | settings-panel-controls-800x600-fs-after.png | `ModalPage.kt`: a full-window panel counts as a header carrying the buttons, so the window's own set stands down | a920cf1 |
| 3 | A tick box and its words touched (the sign out's two ticks, Do not ask again); a long label ran past the dialog | checkbox-gap-800x600-fs-before.png | checkbox-gap-800x600-fs-after.png | `CheckRow.kt`: 8 dp after the box, 40 dp high, the words take the rest of the row and wrap | a920cf1 |
| 4 | The crash question's Always send box was a copy of that row without the gap | crash-checkbox-800x600-fs-before.png | crash-checkbox-800x600-fs-after.png | `CrashQuestion.kt` uses `CheckRow` | 8ce8d71 |
| 5 | A show's page ended under the floating bar (its cast row could not be scrolled clear of it) | n/a (found by the invariant) | n/a | `SeriesViews.kt`: the foot keeps `LocalBottomChrome` | a920cf1 |
| 6 | Sign in with a QR code: "Get a new code" was cut to "Get a" beside "Sign in with a phone number" | signin-qr-button-800x600-fs-before.png | signin-qr-button-800x600-fs-after.png | `SignIn.kt`: the two buttons are a wrapping row | a7c5b3b |
| 7 | The setup's and the tour's "Skip all" sat under the window's buttons | tour-skip-all-800x600-fs-before.png | tour-skip-all-800x600-fs-after.png | `LocalWindowControlsInset` (ui) set by the shell; `StepChrome.kt` keeps its first line clear of it | a7c5b3b |
| 8 | The language list broke "Español (Latinoamérica)" in the middle of a word over three lines in a narrow window | language-columns-800x600-fs-before.png | language-columns-800x600-fs-after.png | `LanguageAndFeedback.kt`: three columns from a 1000 dp window, two from 640, else one | a7c5b3b |
| 9 | The episodes panel (player, and the phone and TV) cut "Previous: S01E03" to "Previous: S01E.." side by side | episodes-steps-narrow-before.png | episodes-steps-narrow-after.png | `EpisodesPanel.kt` (ui): the two buttons may take two lines | 8ce8d71 |

The invariants and the sweep are in the commit "Desktop audit: ScreenAuditShotsTest and ScreenInvariantsTest".

## Looked at and left, with the reason

| What | Why left |
| --- | --- |
| A tile's facts line ends "Watc..." at a larger font ("53m  ·  1.5 GB  ·  720p  ·  Watched") | A single line with an ellipsis on purpose; its tick on the art already says watched. Over 26 characters, so the ellipsis rule leaves it. |
| Keyboard shortcuts, What's new and the tour's list cut a line at the foot of their scroll area | They are scroll areas; a fade would be a new design piece. |
| The offline and "Back online" pills touch the "TMPlayer" word on Home at 800 wide and a larger font | A word that sits under a centred pill at the narrowest window; the pill is the news. |
| The first row of Settings shows the keyboard ring when the panel opens | Focus is taken by the panel on purpose (Tab stays inside); `FocusVisibility` shows it after a key. |
| Home's hero with a real poster, Add chats, the chat choice after sign in, a chat's info panel | They need Telegram or the TMDB fixtures (`HomeCarouselRenderTest`, `AddChatsRenderTest` draw them, and its "Home is laid out the same after going away and coming back" test passes); not repeated here. |
| The flaky `pages` shot test: one run in four ended with Compose's "LayoutNode not found in RectList" in the scene | It only draws when `TMPLAYER_SHOTS` is set, so no check depends on it; the invariants use the compose test host and were stable. |
| Real window chrome on Windows and macOS, Snap Layouts, the installed AppImage | The audit ran on Linux over `ImageComposeScene`; the installed app was not touched. |
| A larger font than 1.3 beyond the bar | The bar is tested at 2.0; the pages scroll, and are not designed for 2.0 on an 800x600 window. |
