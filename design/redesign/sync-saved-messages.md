# Keep my place on all my devices, and a backup before you sign out

FQ12 (progress and bookmark sync through your own Saved Messages) and COMPETITORS item 11 / TS task
"backup and sync", plus gaps item 40 (sign out saves a backup first). Written before the code, 2026-11-16.

## What exists, so nothing is built twice

- CP53 `Backups` / `BackupService`: the backup file (settings, favourites, watched, Watch later, resume,
  bookmarks, lists, corrections as `fix_` memories). Saved through the system picker. Not in Telegram.
- CP89 `ResumeSync` / `SyncDocuments`: opt-in sync of places, Watched, Watch later, favourites and
  named lists in ONE pinned file `tmplayer-sync.json.gz` in Saved Messages, edited in place, merged by
  newest time with removals remembered 90 days, capped at 1 MB. Its switch sits in Settings, Library,
  Lists, named "Resume on your other devices" and asks in a dialog first. It left bookmarks and
  corrections out on purpose.
- So the sync is not new. This work does four things: puts bookmarks and the viewer's corrections in the
  same file (version 3), makes the question one plain card and one switch under Account, makes the
  write rules explicit and tested, and adds the backup before signing out.

## Admission gate (UX plan, Feature admission gate)

1. Raju test: the question is "Keep my place on all my devices?" with Yes / Not now, asked once. Nobody
   has to know the rest exists. Sign out says one sentence with a tick that is on.
2. Decides for the user: no new setting beyond the one switch. File size, write timing, low end
   handling and merge rules are the app's.
3. Appears when relevant: the card shows only once, at the end of the first Home visit that has rows,
   and never when sync is already on. The sign out tick shows only in the sign out dialog.
   "One in, one out": the old row "Resume on your other devices" in Library (and its enable dialog) goes.
4. One place, one name: the switch moves to Settings, Privacy and lock (the Account page) as "Keep my place on all my devices"; the
   old row and its "Resume on your other devices" wording are removed (keys stay in en.json for the
   locales, listed in DEAD-STRINGS.md). Sync now and "Turn off and delete the sync file" move with it.
5. No silent change: sync stays off until a yes. Users who already turned it on keep it on (same
   stored switch). The file's new lists start travelling at their next sync: stated in the switch's
   one line ("bookmarks and titles you fixed"). The version rises to 3 only when a file holds them.
6. Plain words: no TDLib, cache, MTProto. The privacy line is one sentence, shown under the switch.
7. Parity: phone, TV remote, desktop keyboard. Web: see Platforms.
8. Reversible and safe: switch off keeps the file; "Turn off and delete" removes it (existing).
   Sign out backup never blocks signing out.

## Flows

| Step | Phone | TV (remote) | Desktop (keyboard) | Web app |
| --- | --- | --- | --- | --- |
| Question | Card as the last item of Home, after the rows, shown once. "Yes, keep it" / "Not now" | Same card, last item; Down from the last row lands on "Yes, keep it", Right moves to "Not now"; Back leaves it | Same card at the end of Home; Tab and arrows; Enter | Not built (see below) |
| Yes | Switch on, first sync at once, toast "Your place is now kept on all your devices" | same | same | |
| Not now / leaving Home | Card gone for good (stored as seen). Settings, Privacy and lock (the Account page) holds the switch | same | same | |
| Switch | Settings, Privacy and lock (the Account page), "Keep my place on all my devices", subtitle is the privacy line. On: Sync now (with last result) and "Turn off and delete the sync file" under it | Same rows, D-pad, focus returns to the switch when rows disappear | Same page rows, Space toggles | |
| Off | Switch off, file stays in Saved Messages (delete is the second row) | same | same | |
| Sign out | Dialog: one sentence, tick "Save my settings backup to my Saved Messages first" (on). Confirm: "Saving your backup to Saved Messages", then signs out | Same dialog, tick is a row focusable by remote | Same dialog with the checkbox | |
| Sign out, backup fails | Dialog "The backup was not saved" with the reason, "You are not signed out yet", buttons "Sign out anyway" and Back (Back returns to the first dialog, where Sign out tries again or the tick can be turned off) | same | same | |

## Rules of the sync (all in one file, no server)

- Merge per item by newest time. Items: place per video, mark per video, Watch later line, favourite,
  list line, bookmark (by chat, message and its own id), correction (by chat and message). A removal is
  a tombstone with its time; an entry survives a removal of the same age or older; tombstones are
  kept 90 days and sorted into the file newest first.
- Times: a bookmark's time is its note's last edit (`bt_` key in the bookmark file) or else its own id
  (when it was made). A correction's time is `fixat_<chat>_<message>` in the settings. Removals are
  `xb_` and `xfix_` entries, written by the store itself at the moment of removal, so nothing to
  remember at sync time.
- Caps (oldest dropped and counted, shown as "left out to keep the file small"): 200 places, watched and
  Watch later per their stores, 2,000 bookmarks, 1,000 corrections, 5,000 removals, 1 MB packed. Cut
  order when the file is too big: places, removals, Watch later, marks, corrections, bookmarks, list
  lines. What the viewer wrote themself goes last.
- Reader is tolerant: a wrong type, a missing id, a huge note or 30,000 lines skips the entry or is
  cut; nothing throws (GarbageInputTest `syncedBookmarksAndCorrectionsNeverThrow`).
- Writes: one run at most every 3 minutes on its own (and a write only when something changed); the
  player's saves settle 20 s first (a burst is one write); leaving the player writes within 2 s but
  never twice in 30 s; the viewer's own "turn on" and "Sync now" go at once. A restart does not reset
  the pause (the last run's time is stored). On a low end device (Android `isLowRamDevice`) nothing
  runs while the app is in the background; it goes on when the app is back. FLOOD_WAIT is waited out
  by the one call that got it (up to 60 s, 3 tries), then the run ends until the next one.
- Privacy: only TMPlayer's own data. No chat names, file names, captions or paths. A title is cut to
  60 characters, a bookmark note to 120. A bookmark note and a title the viewer typed are the viewer's
  own words about their own videos and go only into the viewer's own Saved Messages. Stated in
  one line under the switch (`syncplace.privacy`).
- Telegram terms: only the user's own account and own Saved Messages (the file refuses another
  account's); one message edited in place, never bulk; nothing sent on a stranger's behalf; no
  restriction is bypassed (a video's chat restriction is not touched, the file holds ids only).

## Edge cases

- **Two devices write at once.** Both read the same file, each writes its merge. The second write
  holds the first's changes only if its read saw them: the next sync of either device merges both
  again, nothing lost because no merge ever drops an entry that a removal does not cover (tested:
  "twoDevicesChangingTheSameNote...", twoDevicesWritingAtOnceKeepBothMarks). A second file left by a race is merged in and deleted
  (existing). Same note changed on both: the later change wins, ties the longer note.
- **A device offline for weeks.** Its bookmark or correction comes back older than the others': the
  newer wins. A removal made elsewhere stays 90 days; a device away longer than that may bring a
  removed bookmark back (documented limit, the same as the other lists).
- **A deleted pinned file.** The next sync finds none, builds the file from this device (merged with
  nothing), sends once and pins it. Never sent twice (a send is not repeated for 15 minutes until
  Telegram confirmed the first; existing guard). Status line says "Waiting for Telegram to confirm".
- **A new device's first pull.** Everything in the file comes in (places by looking the message up in
  Telegram, bookmarks and corrections straight); this device's own lists are never replaced, only
  added to. Until the card is answered nothing is read or written.
- **Size cap.** See Rules; the count of entries left out is shown on the Sync now line.
- **Clock skew.** A time more than a day ahead of this device's clock is read as "now" in a merge, so
  one phone with a clock a year ahead cannot make its notes win for ever; a clock behind loses its
  edits to a newer edit elsewhere (tested "aClockRunningFastCannotWinForEver"). Not fixed: two clocks
  a minute apart can order two edits made within that minute the wrong way round.
- **Older builds.** A file holding bookmarks or corrections is version 3. A build that does not know
  it reads "too new" and neither reads nor writes over it, and says so in its status line: nothing lost.
  A file without them stays version 1 or 2.
- **Another account signed in.** The switch turns itself off and says so (existing). Sign out clears
  the switch, the card state and the bookmark times and removals with the account's data.
- **Kids lock / app lock.** The file is collected from the raw stores, so a hidden chat's places are
  still synced (a place hidden here is not a removal). Settings, Privacy and lock (the Account page) is behind its PIN already.
- **Sign out backup, no line.** Fails within the minute with "TMPlayer could not reach Telegram."; the
  dialog offers Sign out anyway. Never blocks, never signs out half way (the send is confirmed before
  the sign out starts, because signing out destroys an upload under way).
- **Sign out backup, large account.** The backup is the same file as Back up (settings, places, marks,
  bookmarks), well under any limit; one message, silent, once, started by the viewer's own press.
  If the first attempt timed out but arrived, trying again sends a second dated copy: harmless.
- **Restore after signing in again.** The backup is a file in Saved Messages the viewer downloads and
  picks under Restore. Offering it automatically after sign in (gaps item 40, second half) is not in
  this pass.
- **Empty and huge.** No bookmarks and no corrections: nothing added to the file. 20,000 lines in a
  file: read at most twice the cap, then cut to the cap.

## Platforms

- Phone: card in Home's list, switch and rows in Settings, Privacy and lock (the Account page), sign out tick in the dialog.
- TV: the same widgets; the card's two buttons are plain focusable buttons (Down from the last row
  reaches them, Back goes up), the switch row and its two action rows follow the remote, focus goes
  back to the switch when the rows vanish. Sign out tick is a focusable row. Machine checks: D-pad test
  for the card and the switch, render per form factor.
- Desktop: the card in Home, the switch in Settings, Privacy and lock (the Account page) with Space and Enter, the checkbox in
  the sign out dialog.
- Web app (webapp): it has no Saved Messages sync yet (its lists live in the page's IndexedDB). Adding
  it needs the browser client to send, find and pin a document and to read the gzip file; that is its own
  task and is left out here, listed in the plan as a follow-up. Its Settings has no sign out backup
  because the web app signs out without a local backup file.

## Checks that run without a person

- `SyncBookmarksCorrectionsTest`: the merge table (conflicts, tombstones, cap, skew, algebra), the file
  (round trip, versions, no names), two devices against the real stores (made, edited, removed, first
  pull, weeks away, deleted file).
- `GarbageInputTest.syncedBookmarksAndCorrectionsNeverThrow`: truncated, wrong types, huge, random.
- `SyncSchedulerTest`: write throttling against a fake sync and a virtual clock, background on low end,
  leaving the player, FLOOD_WAIT waited out and stopping.
- `SignOutBackupTest`: failure shown, never thrown, never longer than the timeout.
- App: `KeepPlaceCardTest` (D-pad: the card's buttons, the switch, the sign out tick; phone and TV),
  render shots per form factor in `design/sync-saved-messages/`.

## Not done here, for the user's OK

- The public privacy page (`site/src/pages/privacy/index.astro`) lists what the file holds and must
  gain bookmarks and corrections before a release; public copy, so not edited.
- The store listing and any site copy about sync. The web app's sync. Offering a restore after sign in.
