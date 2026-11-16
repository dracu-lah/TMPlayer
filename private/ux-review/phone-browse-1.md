# Review R2: phone-browse-1 (244 controls, 23 files, app/.../ui/browse)

Date 2026-11-16, branch worktree-agent-a27a245869c3b18fa. Commits: b783ee4 (fixes A to H), b704d3c (fix I).
Tests: `app/src/test/.../browse/PhoneBrowseReviewTest.kt` (11 tests, Robolectric, 320 dp phone, fonts 1x, 1.3x, 2x).
Verdict key: ok / fixed in <commit> / left: <why>. Controls are grouped (the inventory lists one line per click, key or back handler).

## Fix list

| Id | Defect | Where | Commit |
| --- | --- | --- | --- |
| A | Selection bars (chat grid, History) lost the "n selected" count at 2x font: words in two buttons pushed it to nothing. From 1.3x the two actions are icons with names and tooltips | MediaGridScreen SelectionBar, BrowseScreen HistorySelectionBar | b783ee4 |
| B | Recent chat tile (phone "Jump back in" strip) fixed at 154 dp cut its name at 1.3x font; the height now grows with the font, equal for every tile | BrowseScreen ChatTile | b783ee4 |
| C | Profile popover rows were 44 dp and one line; now at least 48 dp, label wraps to two lines | TouchNav PopoverRow | b783ee4 |
| D | Filter sheet switch lines: a plain click with a state-less Switch, so TalkBack never said on or off, and a fixed 56 dp cut a wrapped label. Now `toggleable(Role.Switch)` and a 56 dp floor | PhoneSearch SwitchLine | b783ee4 |
| E | "Open in Telegram" in the chat sheet called startActivity unguarded: a phone with no handler crashed on the press. Now `openLink` (runCatching, "Nothing on this phone opens that.") | ChatInfoSheet | b783ee4 |
| F | Chat sheet: Share and Copy link ran off a narrow phone at a large font (now a wrapping flow); the @username line was under 48 dp with no button role | ChatInfoSheet | b783ee4 |
| G | Topic search failure: Retry and Send logs side by side ran off the screen at a large font (now a wrapping flow) | PhoneSearch TopicsFailed | b783ee4 |
| H | Header actions and pills had no button role; a worded HeaderAction named its icon and its text both (read twice). TV focus fades used `tween(140)` and a private constant, not `Motion.standard(Motion.Fast)`, so Reduce motion did not apply | BrowseScreen, MediaGridScreen | b783ee4 |
| I | Search note, "more videos" failure and poster picker message rows squeezed their words into a column beside the Try again / Keep looking button at 2x; the button now wraps under the words | SearchNoteRow, LinkSheets, PosterPickerSheet | b704d3c |

## Per screen

D1 behaviour, D2 input modes, D3 motion, D4 look, D5 words, D6 speed, D7 access, D8 common person.

### Chats page and its chrome (BrowseScreen.kt)

| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Lists / Select / Clear history header actions (L740 to 843, bar icons L990 to 997) | ok | ok (focus ring on HeaderAction) | fixed H | ok | ok | ok | fixed H (role, no double name) | ok |
| Back handlers and TV key handler (L1051 to 1309) | ok (Back clears selection, then bar, then leaves) | ok | n/a | n/a | n/a | ok | n/a | ok |
| Overflow menu (BarOverflow) | ok | ok | ok | ok | ok | ok | ok (named "More options") | ok |
| Continue watching card (L1685) | ok | ok | ok | ok | ok | ok | ok | ok |
| History selection bar (L1808 to 1839) | ok | ok | ok | ok | ok | ok | fixed A | ok |
| HeaderAction, PillButton (L1945, L2346) | ok | ok | fixed H | ok | ok | ok | fixed H | ok |
| Touch filter chip, chip row, TvChip (L2069 to L2305) | ok | ok | ok (tokens, press and slide) | ok | ok | ok | ok (TvChip fixed 48 dp is TV only) | ok |
| Chat tile / recent strip (L2515 to 2589, 2752 to 3004) | ok | ok (holdable: press, hold, focus) | ok | fixed B | ok | ok | fixed B | ok |
| Chat row on a phone (ListItem, 72 dp floor) | ok | ok | ok | ok | ok | ok | ok | ok |

### Chat grid (MediaGridScreen.kt)

| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Back, About-this-chat pill, favourite, search, size hidden, refresh (floating bar) | ok | ok | ok | ok | ok | ok | left: floating Back disc is 44 dp in semantics (user's FloatingChrome.Button token; Compose widens the touch region to 48 dp, so a thumb is fine) | ok |
| Search field (L1540 to 1576) | ok (Search IME, held text, clear keeps focus) | ok | ok | ok | ok | ok | ok | ok |
| Recent searches (L1790 to 1799) | ok (pick, Clear) | ok | n/a | ok | ok | ok | ok (Role.Button) | ok |
| Selection bar, phone (L1835 to 1871) | ok (Download disabled at 0) | ok | ok | ok | ok | ok | fixed A | ok |
| Selection bar, TV pills | ok | ok (ring, grow, tip) | fixed H (Motion token) | ok | ok | ok | ok | ok |
| Sponsored card buttons | ok | ok | fixed H (Motion token) | left: TV focus uses Color.White and Color.Black, not Tone.focusFill (a deliberate high contrast on a separate ad card, user to decide) | ok | ok | ok | ok |
| Media card, cards with long press (L2204 to 2756) | ok | ok (secondaryPress and long press, same menu) | ok | ok | ok | ok | ok | ok |
| Filter empty state buttons (L3014 to 3040) | ok | ok | n/a | ok | ok | ok | ok | ok |

### Phone search (PhoneSearch.kt)

| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Search field, clear, voice (L360 to 403) | ok | ok | ok | ok | ok | ok | ok | ok |
| Chat shortcuts row, chat result rows | ok | ok | ok | ok | ok | ok | ok (rows over 48 dp) | ok |
| Recent searches: pick, fill, remove, Clear all | ok (long press and button twin, rule 6) | ok | ok (animateItem, Reduce motion respected) | ok | ok | ok | ok | ok |
| Result cards, Resume, Show all, Search other chats | ok | ok | ok | ok | ok | ok | ok | ok |
| Retry buttons (L567, L716) | ok | ok | n/a | ok | left: "Retry" here but "Try again" in every other screen (UiState.RETRY and commonRetry exist side by side, a global word decision) | ok | ok | ok |
| Topic failure row | ok | ok | n/a | fixed G | ok | ok | fixed G | ok |
| Filter chips (Filters n, chat type) | ok | ok | ok | left: Material FilterChip with its own shape, not TouchFilterChip (the chat types have no icons; design call) | ok | ok | ok | ok |
| Filter sheet switch lines | ok | ok | ok | ok | ok | ok | fixed D | ok |

### Navigation bar and profile popover (TouchNav.kt)

| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Home, Chats, Search, Profile buttons (64 x 52) | ok | ok | ok (Reduce motion) | ok | ok | ok | ok (caption steps down at large fonts) | ok |
| Popover rows | ok | ok | ok | ok | ok | ok | fixed C | ok |
| Popover scrim and card (L969, L1014) | ok (outside tap, Back closes) | ok | ok | ok | ok | ok | left: scrim has no TalkBack dismiss action (Back and the system scrim gesture close it) | ok |

### Sheets and pages

| Control | Verdict |
| --- | --- |
| ChatInfoSheet (join, Try again, copy username, Share, Copy link) | fixed E, F; rest ok |
| LinkSheets (open in Telegram, look first, more videos, round videos) | fixed I; rest ok (focus on first line, FocusOnOpen) |
| ListPage (tiles, menu, floating Back) | ok; Back closes the dialog, TV lands on the first tile |
| MediaDetailSheet (back, cast button) | ok |
| PosterPickerSheet | fixed I; rest ok |
| VideoSortSheet | ok (existing VideoSortSheetFocusTest) |
| FirstRun (video tip dismiss, metadata notice) | ok (dismiss is a 48 dp IconButton with tooltip; two actions stack under the words on a phone) |
| SeriesScreens (card, menu) | ok |
| AddChatsPage, ArchiveRows | ok (existing AddChatsTvKeysTest, ArchiveRowsFocusTest) |
| AudioRows | left: audio rows are due to go (user decision 2026-11-16, FQ14 cancelled), not reviewed |
| ChipSlider | ok (draws only; Reduce motion handled by the chips) |

### TV (TvTopBar, TvHomeCarousel, TvSearch, HomePane key handlers)

| Control | Verdict |
| --- | --- |
| TvTopBar profile, tabs, quick entries, Choose chats | ok: one focus group, Down goes to the page, return points; existing TvTopBarQuickEntriesTest, ChooseChatsBarTest |
| TvHomeCarousel Play and Details, page turns on Left and Right | ok: existing TvBannerFocusTest |
| TvSearch pills, mic, clear, result rows | ok: existing TvSearch* tests (clear focus, scope, return, render) |
| HomePane D-pad handler (stale repeat, Up and Down paging), See all | ok: existing TileFocusRecomposeTest, ListsTvFocusTest; See all keeps Motion tokens and a 48 dp phone target |
| HomeCarousel (phone hero Play, Details) | ok |

## Left for the user

1. Sponsored button focus colours (white on black) vs Tone.focusFill: design call.
2. "Retry" (commonRetry) vs "Try again" (commonTryAgain) both live app-wide; pick one word (UiState.RETRY, PhoneSearch, TvSearch, desktop palette use Retry).
3. Phone search filter chips use Material's rounded rectangle, not the pill TouchFilterChip every other chip uses.
4. The 44 dp floating Back disc has a 44 dp semantic size (Compose still gives a 48 dp touch region); raise `FloatingChrome.Button` to 48 if TalkBack's touch-target checker should pass, which moves the chat-name pill by 4 dp (shared UI, other chunk).
5. Popover scrim has no accessibility dismiss action.
6. AudioRows left unreviewed on purpose (audio removal pending).
7. Manual: 2x font on a real phone for the three selection and note fixes; no device was touched.

### Worker handoff: R2 phone-browse-1 (to append to private/UX-INTERACTION-PLAN.md at merge; the plan is git-ignored and absent from this worktree)

- Done: fixes A to I above, commits b783ee4 and b704d3c, tests `PhoneBrowseReviewTest` (11) plus existing browse tests.
- Next: the user's list above; wave 2 phone-browse-2 touches other files (no overlap with the ones edited here: BrowseScreen, MediaGridScreen, TouchNav, PhoneSearch, ChatInfoSheet, LinkSheets, PosterPickerSheet, SearchNoteRow).
- Gotchas: `SelectionBar`, `HistorySelectionBar`, `ChatTile`, `PopoverRow`, `SwitchLine`, `TopicsFailed`, `ChatInfoBody`, `HeaderAction`, `PillButton`, `BrowseInsets` went from private to internal for the tests. `rememberLargeFont()` (MediaGridScreen.kt, 1.3x) is the one switch for "actions become icons".
- Decisions: icons-only actions from 1.3x font (not 1.15x as PhoneSearch.Note uses for stacking) because stacking inside the pill bar is not possible; recent tile height grows 48 dp per whole font step; "Retry" vs "Try again" left alone (global word).
