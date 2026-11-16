# Sorting and filtering: audit and plan (2026-10-21)

Branch `worktree-agent-a9c9684df52a7ec08` (stopped early, work handed to the Home agent). No em or
en dashes, English strings only, no langsync.

## Size limits on the phone (priority, done on the branch)

- The limits themselves never went away: `SizeFilter` (core) still screens every chat page in
  `MediaListViewModel.keep` / `keepLive` (`ui/.../browse/BrowseViewModel.kt`), and the range is set in
  Settings, Library, Video size limits on phone and TV (`SettingsScreen.kt`, `SizeLimitsDialog`) and
  in the desktop's Settings (`SettingsPages.kt`). Search ignores the limits (1.21.0).
- What was lost is the grid's line "N videos hidden by the size limits" with Show hidden videos:
  - phone and TV: dropped from `MediaGridScreen.kt` in **633972e** (2026-10-06, "settings in category
    pages ..."), together with the old Series view switch;
  - desktop: dropped from `MediaGrid.kt` in **7f9c44d** (2026-10-06, "detail page, episode guide ...");
  - the now unused `HiddenVideosNote` and its test deleted in **8061ac8** (2026-10-09, "cleanup").
  - Since then the count was only said when the limits hid every video (the empty state's
    Show hidden button, still there), so a chat with some files outside the limits silently lost them.
- Restored on the branch: `MediaListViewModel.sizeHidden` / `allSizes` flows and `keepSizeLimits()`;
  `HiddenBySizeNote` over the phone and TV listing (`MediaGridScreen.kt`), and the same line with
  Show hidden videos in the desktop grid header (`MediaGrid.kt`). Existing strings
  (`browse.hidden_by_size`, `common.show_hidden`), no new keys. Stored limits untouched.
- Not restored on purpose: the Series view switch (removed by the user's choice in ce2d210).
- The self destructing count line went the same way; such videos are still left out with no word.

## Audit, list by list

| List | Sort today | Filter today | Missing |
| --- | --- | --- | --- |
| Chat grid (phone, TV, desktop) | Newest, Oldest, Name, Year, Size, Unwatched first; per chat (`vsort_`), backed up | size limits (Settings), search in chat, topics | filter by watched / unwatched / in progress, on this device, shows vs single videos, 1080p and up; sort by length; a visible filtered state with Clear |
| Series page and seasons | episode order | season tabs | hide watched episodes (low value) |
| Search (all chats) | Best match, Newest, Best quality, Largest | chat type, quality, hide small clips, archived; empty state names the hiding filter | nothing urgent |
| Chats list | Recent, A to Z (remembered) | All, Unread, Channels, Groups, People, Archived, Telegram folders | fine |
| Downloads (phone, TV, desktop) | fixed: shows grouped, newest first (`DownloadShelf.arrange`) | none | sort by Name and Largest (freeing space) |
| Continue watching / Watch later / Watched | fixed: most recent first (`WatchedStore`, `WatchLaterStore`) | tabs | sort by name; filter by chat (low) |
| Favourites | chat order | none | fine |

## Planned (not built, for whoever picks this up)

1. `VideoFilter` in core beside `VideoSort` (`core/.../data/VideoSort.kt`): All, Unwatched,
   In progress, Watched, On this device, Shows, Single videos, 1080p and up (quality via
   `GlobalSearch.qualityOf`). An entry passes if any of its copies does; a show is Watched only when
   every episode is. Stored per chat as `vfilter_<chatId>` in `SettingsStore` and added to
   `Backup.MEMORY`. Unit tests next to `VideoSortTest`.
2. `VideoSort.Longest` (by `durationSec`, unknown last).
3. One control: the chat's Sort button opens `GroupedChoicePopover` (added on the branch in
   `ui/.../components/ChoicePopover.kt`, not yet used) with three groups: Sort videos by, Show,
   Size limits (Within the limits / All sizes, wired to `showHidden` / `keepSizeLimits`). TV:
   `VideoSortSheet` with the same three groups. The Sort icon tinted in the accent while a filter is on.
4. Filtered state: a line over the grid "Showing <filter>: X of Y" with Clear; an empty state
   "<filter> hides all Y videos loaded so far" with Clear filter.
5. Downloads sort: Newest, Name, Largest, remembered once (not per chat), applied in
   `DownloadShelf.arrange` (a show by its total size), on `DownloadsScreen.kt` (phone and TV) and
   desktop `Downloads.kt`.

Left out: duration ranges (the size limits and a length sort cover clips), language and subtitle
filters (not known before a file is opened), file format filter (MKV and MP4 both play),
recently watched sort in a chat (History already is that list), year or rating filters (only where
online metadata matched; a Year sort exists).
