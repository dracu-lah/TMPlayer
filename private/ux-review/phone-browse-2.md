# Review R5: phone-browse-2 (117 controls, 18 files, ui/.../browse)

Date 2026-11-16, branch worktree-agent-ab313160937a2d610. Commits: c1167fe (A to C), a4d6379 (D to H).
Tests: `app/src/test/.../browse/PhoneBrowseReview2Test.kt` (8 tests, Robolectric, 320 and 360 dp phone, fonts 1x and 2x, LTR and RTL).
Also run green: TopicTabsFindTest (TV D-pad), ListsTvFocusTest (TV D-pad), AddChatsRenderTest, PhoneBrowseReviewTest, `:ui:jvmTest` AddChatsReopen / SeriesPlayLabel / DetailBackStack.
Verdict key: ok / fixed in <commit> / left: <why>. Inventory lines are grouped per control; line numbers in the inventory are from an older state of the files.

## Fix list

| Id | Defect | Where | Commit |
| --- | --- | --- | --- |
| A | Telegram's own copies ("Telegram's 720p", "480p") were all described to a screen reader as the upload's quality ("Play the 1080p copy" three times); each now carries its own name | Copies.kt CopyOption, TelegramQualityLine | c1167fe |
| B | List page buttons Rename, Show on Home and Delete sat in one non-wrapping row and ran off a 320 dp phone at a large font; now a wrapping flow | ListPageScaffold | c1167fe |
| C | Add chats: Try again and Send logs side by side (list short, and a failed save) lost room at a large font; now a wrapping flow | AddChatsScreen | c1167fe |
| D | No button role on the detail page's Play pill, round actions (phone and TV), More / Less synopsis, action line, wide-window text action, the floating round button (Close, Back, Trailer), episode lines, guide rows, Play S1E2 and the quality chips: a screen reader said no "button" | DetailHero, MediaDetail, MediaDetailScreen, WideDetail, Series, SeasonsSection | a4d6379 |
| E | Touch targets under 48 dp: quality / list chips (about 30 dp), topic tabs (36 dp), episode range tabs (36 dp), the show page's Play button (40 dp fixed), wide-window text action (40 dp). Chips, topic tabs and range tabs keep their slim look inside a 48 dp press box (`minimumInteractiveComponentSize`, phone only; TV unchanged); Play is a 48 dp floor, not a fixed height, and may take two lines | Series, TopicTabs, WideDetail | a4d6379 |
| F | Detail action line label was one line: "Mark the whole season as watched" lost its end at 2x on a phone; two lines under a finger | MediaDetail DetailActionRow | a4d6379 |
| G | A season the chat holds nothing of was drawn faint and had no press, but was not said to be unavailable; now `disabled()` | SeasonsSection SeasonChip | a4d6379 |
| H | Visibility only: PlayButton, CopyChip, RoundAction, Synopsis, HeroPill (detail), DetailActionRow, WideTextAction went from internal / private to public so the app module can test them | same files | a4d6379 |

## Per screen

D1 behaviour, D2 input modes, D3 motion, D4 look, D5 words, D6 speed, D7 access, D8 common person.

### Add chats picker (AddChatsScreen.kt, 32 controls)

| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Key handlers (rowKey, rail, search, foot) and BackHandler | ok (Back asks before discarding, required mode leaves) | ok (RTL swaps Left and Right; AddChatsRenderTest) | n/a | n/a | n/a | ok (HX76 work) | n/a | ok |
| Search field (L540) | ok (Search key, clear cross, TV has Back to clear) | ok | ok | ok | ok | ok | ok (ClearButton named) | ok |
| Back (L581, L595) | ok | ok | ok (pressScale) | left: QuietButton is a fixed 40 dp high, so under 48 dp and a 2x label can fill it; it lives in onboarding/StepChrome.kt (flows chunk, R6) | ok | ok | left: same | ok |
| Scope chips (L622) | ok | ok (chip row scrolls, focus requester on the chosen one) | ok | fixed E (48 dp press box) | ok | ok | fixed D (button role) | ok |
| Try again, Send logs (L645, L808) | ok | ok | ok | fixed C | ok | ok | ok | ok |
| Band button, Done, Continue without choosing (L685 to L862) | ok (nothing ticked is an answer; required Done waits for a tick) | ok (Right goes to Done, Left to the filters) | ok | ok | ok | ok | ok (disabled Done says what is missing) | ok |
| Rail entries, Search entry (L900 to L919, L1042) | ok | ok (TV only) | ok (plain fill swap) | ok | ok | ok | ok (Role.Tab, selected) | ok |
| Fold row (L1081) | ok | ok | ok | ok | ok | ok | ok (Role.Button) | ok |
| PickRow (L1159 to L1260) | ok (tap ticks, long press and Menu key star it) | ok (rule 6: custom action "Add to favourites" is the twin of the long press) | ok (no animated tick on TV by design, HX76) | ok | ok | ok (colours read in the draw phase) | ok (Role.Checkbox, toggle state) | ok |
| GoneRow Remove (L1379) | ok | ok | ok | left: QuietButton 40 dp (see Back) | ok | ok | ok | ok |
| ChatsGateHost Continue without choosing | ok (waives for this visit only) | ok (focus lands on it when it appears) | ok | left: QuietButton 40 dp | ok | ok | ok | ok |

### Copies and quality (Copies.kt, 11 controls; Series.kt CopyChip)

| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Change / Done summary (CopySummary L192, L691) | ok | ok | ok | ok | ok | ok | ok (Role.Button; left: no expanded state read) | ok |
| Copies sheet and dialog dismissal, Close disc (L246, L301, L308) | ok | ok | ok | ok | ok | ok | ok | ok |
| CopyOption, CopyRow (L450 to L626, L703, L716) | ok (picking an upload or a Telegram copy sets one wish) | ok (radio role, focus fill) | ok (tint fades with Motion.standard) | ok | ok | ok | fixed A (own names), ok role | ok |
| CopyChip (quality picker, lists chips, picker scopes) | ok | ok | left: fill swaps without a fade, as the other pills here do | fixed E | ok | ok | fixed D, E | ok |

### Detail hero and pages (DetailHero, MediaDetail, MediaDetailScreen, WideDetail, FeaturedHero, HeroPill, HomeEmpty, Course; 26 controls)

| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Play pill (DetailHero L197) | ok (spinner while the file is looked up) | ok | ok (pressScale, plain focus swap) | ok | ok | ok | fixed D | ok |
| RoundAction phone and TV (L297, L319) | ok (ghost, disabled, progress ring) | ok | ok (fill fade with Motion.standard) | ok (48 dp) | ok | ok | fixed D | ok |
| Synopsis More / Less (L417) | ok (a stop only while there is more) | ok | ok | ok | ok | ok | fixed D | ok |
| DetailActionRow (MediaDetail L560) | ok | ok | ok | fixed F | ok | ok | fixed D | ok |
| Back, Close, Trailer disc (MediaDetailScreen L190 to L776) | ok | ok | ok | ok | ok | ok | fixed D (role; description kept) | ok |
| Wide window actions (WideDetail L491, L531) | ok | ok | ok | fixed E | ok | ok | fixed D | ok |
| Wide window Back disc (L417) | ok | ok | ok | ok | ok | ok | ok | ok |
| Featured hero dots (FeaturedHero L161 to L200) | ok | ok (focusable only on TV) | ok (width animates, still respected) | ok | ok | ok | left: a dot's press box is about 12 by 22 dp on a phone, where a swipe is the main way and the dots only tell the page; not made a tap target on purpose | ok |
| HeroPill (Home empty cards, HomeEmpty L82, L200, L232) | ok | ok | ok | ok (52 dp, two lines) | ok | ok | ok (Role.Button) | ok |
| Course Play button (Course L65) | ok | ok | ok | fixed E | ok | ok | fixed D | ok |

### Show page (Series.kt, SeasonsSection.kt, ShowMenu.kt; 33 controls)

| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Season tabs (Series L305, SeasonChip) | ok | ok (TV selects on focus) | ok | ok | ok | ok | fixed G (disabled), ok role | ok |
| Range tabs (Series L1593) | ok | ok | ok | fixed E | ok | ok | ok | ok |
| Quality picker chips (L459) | ok | ok | left (see CopyChip) | fixed E | ok | ok | fixed D | ok |
| Episode line (L515, L517) | ok | ok (okHold: OK plays, hold marks, on a TV) | ok | ok | ok | ok | fixed D (phone long press is exposed as a custom action by combinedClickable) | ok |
| Episode guide row (SeasonsSection L293) | ok | ok | ok | ok | ok | ok | fixed D | ok |
| Key handlers (L946, L963, L1728) | ok (Down from Play, OK keys) | ok | n/a | n/a | n/a | ok | n/a | ok |
| Play, Start over, round actions (L1134 to L1242) | ok | ok | ok | ok | ok | ok | fixed D | ok |
| Back disc (L1531) | ok | ok | ok | ok | ok | ok | ok | ok |
| PlayButton (L1621) | ok | ok | ok | fixed E | ok | ok | fixed D | ok |
| Show menu card, rows (ShowMenu L148, L203) | ok | ok (focus lands on first line) | ok | ok | ok | ok | ok | ok |

### Lists, topics, navigation (ListPageScaffold, ListsUi, TopicTabs, NavGroupHeading; 24 controls)

| Control | D1 | D2 | D3 | D4 | D5 | D6 | D7 | D8 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| List chips, New list (ListPageScaffold L107, L116) | ok | ok (remote starts on the shown list's chip, ListsTvFocusTest) | ok | fixed E | ok | ok | fixed D | ok |
| Rename, Show on Home, Delete (L124 to L126) | ok (Delete asks first) | ok | ok | fixed B | ok | ok | fixed D | ok |
| Name dialog, delete dialog (ListsUi L200 to L231) | ok (Done off for a blank name, name capped) | ok (field takes focus) | ok | ok | ok | ok | ok | ok |
| Topic tabs, All topics (TopicTabs L117, L128, L264) | ok | ok (TopicTabsFindTest on TV) | ok | fixed E | ok | ok | ok (Role.Tab, unread read out) | ok |
| Find a topic, its clear cross (L173 to L222) | ok | ok (TV: OK opens the keyboard, arrows leave) | ok | ok | ok | ok | ok | ok |
| Nav group heading (NavGroupHeading L74) | ok | ok | ok | ok | ok | ok | ok (open / folded state read) | ok |

## Left for the user

- QuietButton (onboarding/StepChrome.kt) is a fixed 40 dp high pill: under the 48 dp rule and a 2x label has no room. It serves Back and Remove in the Add chats picker and the setup's footers. It sits in the flows chunk (R6), so I left it. Suggested: `heightIn(min = 48.dp)` like R5's PlayButton.
- CopyChip, topic tab, season and range fills switch without a fade (the pills' plain swap the user asked for 2026-11-01); decide whether chips should also fade with `Motion.standard(Motion.Fast)`.
- Featured hero dots on a phone are about 12 by 22 dp press boxes; a swipe is the way, so left as is.
- CopySummary (Change / Done) does not say expanded or collapsed to TalkBack.
- The episode line has a long press on a phone (the player's episode list marks watched); the Compose long click is exposed to TalkBack, but I did not check that a visible button twin exists on every screen that passes onLongClick.
- EpisodeLine and GuideRow got their button role without a unit test of their own (private, many parameters); the roles of the other controls are tested.
