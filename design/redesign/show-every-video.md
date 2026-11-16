# R1. Show every video by default

Status: building (2026-11-16). Source: `private/ux-review/raju-journey.md` finding 4.1, redesign R1, approved by the user ("do all").

## The problem Raju hits

A chat "Mark S" holds two videos on screen. A chip says "1 hidden". Tapping it opens "Smallest video shown" with six radios and 400 MB selected as Default. The family clips Raju is looking for are 5 to 300 MB, so the app shows him almost nothing and never says that a setting is the reason. On a desktop the empty state reads "No videos here of 400 MB or more. 12 videos hidden by the size limits."

Root cause: `SizeFilter.DEFAULT_MIN = 400 MB` is a default that suits a chat of films and breaks every other chat. A slider pair in Settings, a six radio sheet and a chip were built around it.

## The new flow

- Default minimum is 0: every video of a chat is listed. Nobody opens Settings to see their videos.
- One switch in a chat's Sort sheet (menu on phone and desktop, panel on TV): **Hide small clips (under 50 MB)**, off by default. It is a group of its own, "Small videos", directly under "Show". It applies to every chat and is remembered (the stored `min_size_bytes` is 50 MB when on).
- When it is on and clips were left out, the grid ends with a row: "3 small videos hidden" and a **Show them** button. One tap shows them for this visit to the chat (the switch reads off while they are shown; the stored choice is not changed, so the next visit hides them again). The number is exact only when the whole chat has been read (the hidden set is the one the app filtered itself, counted once while paging, one pass, no extra query); until then the row says "Some small videos are hidden".
- A chat that is empty only because of the switch says "3 small videos are hidden" with the same button.
- Home keeps its own floor of 50 MB (`SizeFilter.HOME_MIN`) so trailers and samples do not fill Recently added. Recently added's See all page says: "Videos under 50 MB are not on Home. Open a chat to see them."
- Notifications (`NewVideoAlerts`, `EpisodeAlerts`) keep their own 50 MB floor (`SizeFilter.NOTIFY_MIN`), so a clip never pings.
- Search's "Hide small clips" filter uses the same 50 MB, so one name means one thing.

## Stored value migration

| Stored `min_size_bytes` | Meaning now |
| --- | --- |
| absent | 0, every video |
| 400 MB (the old default) | 0, treated as never set |
| 50 MB | the switch is on |
| anything else (a floor chosen on purpose) | kept as a custom floor; the Sort sheet shows one line "Videos under X are hidden. Reset" in the Size group, Reset removes it |
| stored `max_size_bytes` below 8 GB | kept as a custom ceiling; same line and Reset |

No write on read: the stored number is interpreted by `SizeFilter.effectiveMin`, so a backup restore of an old file also behaves.

## Before and after

| | Before | After |
| --- | --- | --- |
| Default | 400 MB minimum, hides ordinary clips | 0, nothing hidden |
| Where to change | chip "1 hidden" (slider icon), six radio sheet, Settings sliders (min and max), reset chip | one switch in the chat's Sort sheet |
| What the chat says | "1 hidden" chip, then "applies to every chat" | grid ends with "N small videos hidden. Show them" |
| Empty chat | "No videos here of 400 MB or more. 12 videos hidden by the size limits. Change the minimum and maximum size in Settings." | "N small videos are hidden." with Show them |
| Home | 400 MB or the user's floor | 50 MB floor (or the user's custom floor), said on See all |
| Notifications | the user's floor | 50 MB or the custom floor, whichever is higher |
| Phone | chip in the bottom bar | switch in the Sort popover, end row in the grid |
| TV | pill in the bar, radio panel | switch in the Sort panel (Down from the order), focus stays on it after toggling; end row is a focusable button |
| Desktop | chip, popover of sizes, Settings steppers | switch in the Sort popover (Tab reaches it), end row button |
| Web | chip, size picker dialog, Settings sliders | switch in the Sort menu, end row, default 0 |

## Platforms

| Platform | Switch | End row | Focus and keys |
| --- | --- | --- | --- |
| Phone | Sort popover, group "Small videos", line with a tick | full width grid item, text and Show them | 48 dp targets, press haptic as other toggles |
| TV | Sort panel (`VideoSortSheet`), Down from the order lines | grid item, button reached with Down from the last row | the panel stays open after the toggle and the remote stays on the line; Back closes |
| Desktop | Sort popover, Tab and Enter, Space | grid item with a button | Tab order follows reading order |
| Web | Sort menu in the chat bar | end row with the same words | Tab reaches both |

## Edge cases

- Empty chat, all clips: the empty state says how many are hidden and offers Show them (not a dead end).
- 100,000 videos: one pass over each page as it is read (`SizeFilter.split`), the count is kept in memory, no second query; the row says "Some" until the chat has been read to its start.
- A search ignores the switch (as it ignored the limits before) and shows no row.
- A file of unknown size (0 bytes) is never hidden.
- The switch toggled while the grid is loading: the grid restarts from the kept pages (cached), the sheet stays open.
- A user who chose a floor on purpose keeps it, silently, with the note and Reset in the Sort sheet.
- Backup and restore: the keys are unchanged, old backups interpret the same way.

## Deleted

Settings min and max sliders and Reset (phone and TV: `SettingsScreen.kt`; desktop: `SettingsPages.kt`; web: `Settings.tsx`), the size radio dialog (`SizeQuickSheet.kt`, the desktop popover, the web picker), the "n hidden" chip and pill, the one-off `hiddenNotice`, the slider helpers of `SizeFilter` (`step`, `snap`, `fraction`, `bytesAt`, `clampMin`, `clampMax`, `QUICK_MINIMUMS`), and the strings that no screen uses any more (listed in `private/DEAD-STRINGS.md`; the translated catalogs keep them until the next cleanup).

## Shots

Before: `private/ux-review` frames `chat-videos`, `chat-min-size-dialog`, `state-empty-hidden` (400 MB wording).

After (desktop render tests, `TMPLAYER_SHOTS` set; phone and TV are covered by focus and layout tests, no device pass as asked), in `design/redesign/show-every-video/`:

- `parity-chat-sort-dark.png`, `parity-chat-sort-light.png`: the Sort menu with the "Small videos" group directly under Show.
- `desktop-small-hidden-exact-dark.png`, `desktop-small-hidden-exact-light.png`: the grid's end row, "12 small videos hidden" and Show them.
- `desktop-small-hidden-some-dark.png`: the same row before the whole chat is read ("Some small videos are hidden").
- `state-empty-hidden.png`: a chat of only small videos.

## Machine checks

`SizeFilterTest` (default, migrated 400 MB, custom, clip switch, hidden set, 100,000 videos in one pass), `BooksMatchStoresTest` (both stores migrate and reset the same), `ChatGridKeptTest` (hidden set exact, 100,000 videos from the kept listing, empty state with Show them), `VideoMenuRandomTest` (group order, switch, custom line), `VideoSortSheetFocusTest` (TV: Down reaches the switch, OK toggles, focus stays, panel stays open), `SmallHiddenRowTest` (TV Down to Show them, exact or "Some", singular, large font on a phone), `SmallHiddenKeyboardTest` (desktop keyboard), `WebRulesTest`, web e2e `app.spec.ts` (default lists every video, switch, end row, Show them, saved 400 MB reads as not set).
