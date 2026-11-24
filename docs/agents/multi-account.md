# Multi-account profiles (HX88), the core layer

Up to three Telegram accounts on one device, one at a time. This note is the design of the core
layer (phase A); the picker and every platform's screens (phase B) are built on top of the API at
the end.

## The rules

- One live TDLib client at a time. The other profiles sleep: their folders stay on the disk and
  nothing reads them. A switch closes the live client and opens the next profile's own database and
  files folders; nobody signs in again.
- At most 3 profiles ([AccountRules.MAX]).
- The login that is on the device when this version first runs is adopted in place as the first
  profile: its folder is recorded as "the platform's own folders" (`folder = null`), nothing is
  copied or moved, and its settings keep their old key names. Reinstalling or going back to an
  older version still finds that login and its settings where they always were.
- Each other profile gets folders of its own beside the platform's: `<dir>-<folder>` next to each
  of TDLib's database folder, its files (cache) folder and the downloads folder, so
  `filesDir/tdlib` and `filesDir/tdlib-p4k2q9` on Android, `TMPlayer/tdlib` and
  `TMPlayer/tdlib-p4k2q9` on the desktop.
- With one profile nothing changes on screen: no picker, the same folders, the same keys.

## What belongs to a profile and what to the device

Per profile (account data): the chosen chats, the All chats switch and its kept picks (HX86),
favourites, Watch later, named lists, watched marks, resume places and their offsets, the sync
file's and the sync channel's state (HX87), the downloads and the download queue, the watch cache
record, search history (`recent_searches`), chat sorts, filters and topics, new video and episode
alert bookkeeping, the chat and account snapshots, the kids lock's allowed chats (chat ids mean
nothing to another account), whether this profile was signed in (`signed_in`, HX23), Home's and the
chat grids' disk caches, TDLib's own database and cache.

Shared on the device: theme and appearance, language, player, subtitle style and decoder settings,
engine and remedy memories, the speed and track memories by show name, proxies (TDLib keeps those
in each database; the app's own proxy list is shared), the app lock and its PIN, the kids lock
switch, crash report consent, updates, support card bookkeeping, TMDB and image caches, online
subtitle and metadata settings.

[AccountKeys] is the one list: every name `SettingsStore` writes is named there as account or
device, and `AccountKeysTest` fails when a new key is in neither (as `BackupCoverageTest` does for
backups). An unknown name is treated as the device's.

## How the stores split

No store is split into files and no call site changes. The three preference stores are opened
through [AccountScopedDataStore], a DataStore wrapper that shows each profile its own view:

- The adopted profile reads and writes the account keys under their old names, so it is exactly the
  store of the version before.
- Any other profile's account keys are stored as `@<folder>:<name>`. The wrapper hides every
  `@...` key and the adopted profile's account keys from that profile's view, and shows its own
  `@<folder>:` keys under their plain names. A write is mapped back the same way, keeping every
  other profile's keys as they were.
- The device keys are read and written by every profile alike.
- `WatchedStore` and `BookmarkStore` hold nothing but account data, so all their keys are scoped.
- With only the adopted profile and no `@` key in the file the wrapper hands the store through
  untouched.

The view follows [Accounts.activeFolder], so after a switch every flow re-emits with the new
profile's data. `HomeCache` and `ChatGridDisk` read their file through [Accounts.scopedFile]
(`home-cache.json` becomes `home-cache-<folder>.json`) and forget what they hold in memory on a
switch.

## The single live client

`Td` keeps the platform's [Paths] as given (`basePaths`) and hands TDLib [ProfilePaths] for the
active profile, which is the platform's `Paths` itself for the adopted profile. [Td.reopenFor]
points the next client at the new profile and closes the live one the way `restart` does
(Closing, then Closed, then the client loop opens the next one), with one difference: it never
abandons a client that is slow to close. TDLib's own client loop only starts the next client after
the old one reported Closed, so two clients never hold one folder and a 1 GB stick never has two
live clients. A slow close returns `false` after the timeout; the switcher says `ClosingSlowly`
(the picker shows a spinner) and waits for the close to finish.

[AccountSwitcher] serializes every change with a `Mutex`. A switch that waits behind another is
dropped when a newer switch was asked for meanwhile, so the last one asked for wins and the ones in
between never open a client.

## Killed part way

`accounts.json` is written atomically (a temporary file, then a rename) before and after each step:

1. `switchingTo` is set to the profile being opened, then the live client is closed.
2. Once it has said Closed and before the next client exists (Td's client loop runs the switch's
   hook there, under the lock it makes the client with), `active` becomes that profile and
   `switchingTo` is cleared. Only then do the stores' views, `Accounts.stamp` and the caches move.

On the next start [Accounts.open] finds a `switchingTo` left behind and drops it: `active` is
still the last profile that was really open. A profile still being added (`adding`, never signed in) that is not
the only one is removed with its folders: a half made folder never lingers. A file that cannot be
read starts again with the adopted profile alone (the platform's folders), which is the state of
the version before.

## Work that outlives a switch

Nothing of one profile may land in another's file or memory, so nothing resolves "the open
profile" at the moment it writes:

- `Accounts.stamp` (an `AccountStamp`: the folder and a switch counter) is what data is tagged
  with when it is made. Home's snapshot carries the stamp its view model was built under, and
  `PageQueries` the stamp its cache was filled under; `HomeCache.save` and `ChatGridDisk.save`
  drop a stamp that is no longer current, checked under the lock the write holds, and write to
  the file of the stamp, never of the moment.
- `QueryCache.clear()` starts a new epoch, and an answer asked for in an older one is not kept
  (`putIf`), so a fetch on its way during a switch or a sign out lands nowhere.
- `Accounts.profileScope` is cancelled on every switch and carries an `AccountOwner`; a write to
  an account store from a coroutine whose owner is no longer open is dropped. Work without an
  owner writes for the profile open when it asks, as before; phase B moves the profile's own
  background work (sync, alerts, the download runner) onto that scope.
- Writes tagged with the profile they were made for, today: both players' resume saves (the
  stamp taken when the player opened, `owner` on `saveResumePosition` and `clearResumePosition`),
  and every store write of a download's fetch and move jobs on Android and the desktop (launched
  with the `AccountOwner` of the profile that asked). `noteDownload` and `saveDownloadQueue` take
  an `owner` too. Not yet tagged, for phase B to move onto `profileScope` or tag the same way: the
  external player's resume save (`ExternalPlayRouter`), `setDownloadPath` and `forgetDownload`
  from the Downloads screen, watched marks and bookmarks from the players, the sync file
  (`ResumeSync`, `SyncScheduler`), new video, episode and piece alerts, history edits, and every
  screen's own edits (their view models are rebuilt per profile in phase B).
- A file of one profile's cache (`home-cache-<folder>.json`, `chat-grids-<folder>.json`) is
  deleted with the profile's folders and keys, through the same purge.
- A sign in is recorded as soon as the client is Ready (`adding` cleared), and who it is follows
  when it is read, read again on failure, once per client generation; a newer client cancels the
  read of an older one.
- The Td host waits for the client made after the switch's hook ran, so a client that was about
  to close never counts as the next one; a host that never ran the hook moves nothing.
- A PIN wait that a reboot interrupted runs out from the reboot, never later.
- On a switch: `OfflineDownloads` forgets its rows (file ids are per database), `GoneVideos` and
  `ChatVideoCount` forget what they knew, the chat subtitle finder keys its answers by the switch
  counter, and `Td` clears the chats it had open as each client ends.
- A removed profile's keys are purged from every store with its folders (`AccountBook.sweep`),
  so a profile added later, or the adopted folders opened afresh by Remove all accounts, start
  empty.
- Who signed in is tagged with the profile and the client generation it came from, and dropped
  when either has changed: a slow read never refuses or logs out the wrong profile.
- The code never wipes an existing profile: only the viewer's Sign in again and a new profile
  pass `signInAgain`. A profile's folders are deleted only once another client is live.
- The platform's wipe runs under the switcher's lock: a call to the switcher from inside it is
  refused (logged) rather than waiting for itself.

## Adding, signing out, removing

- `add()` refuses a fourth profile (`Full`), checks free space first (`NoSpace` with what is
  needed and what is free), records the profile as `adding`, creates its folders and switches to
  it: TDLib starts over an empty database, which is the normal sign in.
- When a profile reaches Ready, [AccountSwitcher.attach] records its Telegram user id, name and
  small photo (`accounts/<id>.jpg`). The same Telegram account on a second profile is refused: the
  new session is logged out, its folders go, the previous profile opens again and
  `events` says `SameAccount`.
- `signOutProfile(id, wipe)` opens that profile if it sleeps, lets the caller wipe its data
  (`wipe` runs `SettingsStore.clearAccountData` or `clearEverything` for the last profile, the
  watched and bookmark stores, the backup before sign out and the downloads option, all acting on
  the open profile only), logs out with `Td.logOut()`, then opens another profile and deletes the
  signed out one's folders. Signing out of the last profile keeps its record, empties its identity
  and leaves the client on the normal sign in.
- On Android the sign out question, the backup before it and the sign out itself live in one
  `SignOutHost` at the app shell (`MainActivity`, above Browse and Settings), asked by an event from
  Settings, the TV's account menu and the phone's "You" popover. It owns the prompts, the two ticks and
  the backup job, so a backup started from a menu that closes is not cut off; it calls
  `ProfileFlow.signOutOpen` for the open profile only and names the account in its title. The desktop
  big screen's menu composes the desktop's `SignOutDialog` outside its popover for the same reason.
- `remove(id)` forgets a sleeping profile without reaching Telegram: for a profile whose folder is
  missing or broken, or one still being added.
- `removeAll(wipe)` signs every profile out one by one and leaves one empty profile on the
  platform's own folders.

## The disk cache budget

All profiles share one budget. `Td.cacheCeilingBytes()` is the device's ceiling less what the
sleeping profiles' caches hold (measured once per switch, off the main thread), never under a
quarter of the ceiling, so three profiles never triple the cache.

## A broken profile

[ProfileState.Recoverable] (folder missing, not a folder, cannot be written) is what the picker
shows with "Remove" and "Sign in again"; `switchTo(id)` answers `Recoverable` instead of opening
it, and `switchTo(id, signInAgain = true)` makes the folders afresh and opens the sign in. Nothing
here throws on a bad file or folder.

## Global to per profile, what is left for phase B

Done in core: the TDLib folders, the three preference stores, Home's and the chat grids' disk
caches, the shared cache budget.

Phase B, per platform:

- Call [Accounts.open] before the first store or `Td.start` (done for Android and the desktop;
  iOS and the web player are their own tasks: mtcute sessions and their own storage).
- Use `Td.paths?.downloadsDir` (the profile's) instead of `AndroidPaths(context).downloadsDir` and
  `DesktopPaths.downloadsDir` wherever a download is written or listed.
- Rebuild the screens' state after a switch (`Accounts.active` changes): view models, the chat
  repository's memory, Home, the player. The simplest is to key the root of the UI on the active
  profile id.
- Sign out: call `AccountSwitcher.signOutProfile` with the platform's wipe instead of
  `forgetAccount` plus `Td.logOut()`, and the picker after it.
- The picker, the PIN pad, Add account, Remove all accounts, the strings.

## The web player (phase B3)

The web keeps its own book (`webapp/src/ui/accountBook.ts`, `accounts.ts`, `accountFlows.ts`) with
the same rules: three profiles, the adopted one keeps the old storage names, other profiles' keys
are `@<folder>:<key>` in `tmplayer-app`, one mtcute client per page, and a switch reloads the page
into the next profile.

- The PIN unlock lives in the tab only (sessionStorage), bound to the page holding it by a token in
  memory and a Web Lock of that name. A reload of the tab, or a page the tab started itself, takes it
  over; a duplicated tab, a restored session or an address typed into the tab asks the PIN again.
- The unlock carries a time and runs out after a minute unless the shown tab keeps it fresh (every
  20 s). A tab hidden for more than a minute locks at once when it shows again. One hidden for 40 to
  60 s stays open, but its unlock has run out, so its next reload asks the PIN: this fails safe, and
  the PIN pad says so ("asked again after a minute away").
- Locked (the picker at launch or locked again): every page-wide input listener goes through
  `onGlobal` (`ui/globalInput.ts`, enforced by `globalInput.test.ts`), the page is inert and hidden
  from screen readers, no dialog of the profile opens (`showModal` refuses), picture in picture, full
  screen and the Media Session end, the media is unloaded and the player closes after saving its place.
- Stream reads carry the profile (`p=`); a page answers only its own, and an untagged one only while
  there is a single profile. The Service Worker never hands a read to another profile's tab.

## Invariants (every platform, B2 included)

1. One profile with no PIN behaves exactly as the version before: no picker, no wait, no extra
   question, the same folders, keys, notifications and downloads. Every change is tested for it.
2. Work takes its owner (`Accounts.stamp`) when it starts and keeps it: every store write runs as
   `AccountOwner(owner)`, every Telegram call takes its client through `Td.sessionFor(owner)`.
3. Before every write, delete, Telegram call and file removal of a job, the owner is checked
   again (`OwnedSessions.mayAct`, false while a switch is under way too), also after any lookup
   that suspends.
4. Folders come from the owner's own folder name (`AccountDirs.pathsFor(base, owner)`), never
   from `Td.paths` or whatever folder is current: in a switch the next profile's folder is pointed
   at before the open profile changes.
5. Nothing deletes because two snapshots of a profile's data differ: deletes follow events made
   for that profile (`WatchedMarks`), and a switch, a sign out, a sync import, a restore and a
   start delete nothing.
6. A PIN profile's content never shows outside the app while it is hidden: every notification
   goes through `ProfileNotifications`, the launcher through `OutsideWrites`, both policed by tests.
7. A sign out discards what belongs to the session it ends (download rows, results in the
   shade); nothing of an old TDLib database is resumed on a new one: whatever keeps file ids for
   later keeps the database marker with them and is dropped when it differs (`DatabaseMarker`).

## Phase B1: the shared screens and Android

Work that writes for its owner, not for whoever is open when it writes (each takes
`Accounts.stamp` when it starts and runs as `AccountOwner`, so a write after a switch is dropped):

- The sync file (`ResumeSync.sync`, every entry: the scheduler's runs, Sync now, the pull before a
  video, `changeDestination`, `turnOffAndDelete`): its owner is taken before it waits for the
  lock; a run asked for a profile no longer open does nothing; the sync channel's resolve and
  writes and the confirm (`resume_sync_account`) run inside it; a `BeforeSwitch` hook cancels and
  waits for a run (20 s at most, then the switch goes on and the run's late writes are dropped).
  The scheduler's "last wrote at" is written inside the owned run.
- SubMaker's translations: each job's track file is written only while its owner is open; the
  player closing (which a switch does) ends its jobs.
- Save to folder and the move of downloads (below). A moved file's record is written with
  `AccountAddress(owner)`, the one write addressed to a profile on purpose, so a record never
  points at an original that is gone, even when a switch could not wait for that file.
- Folder grants for downloads are the device's: one is kept while any profile's downloads are in
  it (`SettingsStore.downloadPathsOfEveryProfileNow`).

Telegram sessions are bound too: a job that writes to Telegram, or outlives a screen, takes
its client through `Td.sessionFor(owner)`, null while a switch is under way, once another
profile is open, or when the client that came up meanwhile is the next profile's (each
`TdSession` knows the profile it was made for). The sync, the sign out's backup, the sync
channel's page and its "Create it for me", the Devices page's sign outs, downloads and the new
video alerts take theirs there, and check `OwnedSessions.ensure()` again before each Telegram
write. `TdSessionPolicyTest` lists every other wait and why it is safe (a read for the screen on
screen, or the player, which closes before a switch).

App-wide collectors over a profile's flows (audited, HX88): a switch must never read as a
change of that profile's data. "Remove after watching" (`RemoveAfterWatching`, both apps) is
driven by marks as they are made (`WatchedMarks`, raised only by `WatchedStore.markWatched` and
`markAllWatched`, for the profile the mark was written for), never by comparing two watched
lists: a switch, a sign out, the app starting, a restore from a backup and a sync import delete
nothing. A sync import marking videos watched on another device does not delete their downloads
here (the conservative answer, user to confirm): only the viewer finishing or marking a video on
this device does, the first time it is marked (a replay of a video already watched keeps its
download), and only while that profile is open. The players' marks are written as the profile
the player opened under, and a switch waits for them. The others only mirror the open
profile's state and act on no difference: the kids lock and the chats added (`ChatGate.follow`,
`AddedChats.follow`), the viewer's corrections (`VideoCorrections.follow`), the widget's tile, the
TV's channels (cleared on every switch), the notifications (taken down on every switch), and gone
videos (`GoneVideos`, which forgets on a switch). The rest of App.kt's collectors read device
settings (language, low storage, mobile data, pictures, motion, haptics, the downloads folder,
frame rate matching).

File walkers, trimmers and cleaners (audited, HX88), each over the open profile's own folders:

- The watch cache (`WatchCacheRules`, Android's `WatchCache`): every walk and delete is over the
  folder of the profile the work is for, resolved from that profile's own folder name
  (`WatchCache.rootOf`, never `Td.paths`); a claim, an eviction, a sweep and Clear cache take that
  profile when they start and check `OwnedSessions.mayAct` before each delete.
- TDLib's own trims (`Td.trimStorage`, `clearPicturesAndPreviews`): on the live client, which is
  the open profile's and sees only its own database and files.
- The downloads: written to and listed in the open profile's folder (`profileDownloadsDir`); a
  moved file's delete (`DownloadFinisher`), the old downloads' one-time move (`LegacyDownloads`)
  and Remove after watching's legacy delete check their owner before deleting.
- Moving downloads (`DownloadRelocation`): the open profile's own folder only, as its owner.
- Free up space and the storage figures (`RoomOnDisk`, `StorageSplit`): the open profile's, on
  its screen.
- The picture, TMDB and online subtitle caches: the device's, shared by every profile (one budget),
  nothing of an account in them; the TDLib cache budget is shared too (`Td.cacheCeilingBytes`).

The downloads a switch's wait paused are kept in memory only (`SwitchPause`), for one thing: a
Cancel of that switch (or an Add account, sign out or Remove all accounts that failed or did not
happen) resumes them, while the same profile and the same set of rows are open. A switch that goes
ahead leaves them paused, as a restart does: the profile's queue comes back as paused rows when it
is open next and the viewer resumes them. Nothing resumes by itself across a switch, a sign out,
an ended session or a restart (simplified in round 15: the per-profile set kept on the device,
`PausedForSwitch`, needed more special cases than it was worth; earlier builds' copy is cleared).

The download rows (`OfflineDownloads`) belong to one profile and one database:

- The rows are dropped on every switch and every new database (`Td.renewDatabaseMarker`, called by
  a sign out and an ended session), which also stops the runner's jobs (`databaseChanges`).
- Each drop counts (`rowsGeneration`): a job carries the count it was queued in
  (`OfflineDownloads.Rows`), its late updates are dropped (`ifRows`), and an id queued before the
  drop never starts (`DownloadStamps.startable`).
- The rows' owner (`rowsOwner`) is set by `restore` only, after it read that profile's stored
  queue; a persist writes for that owner, never before it is set, so a download asked early never
  replaces the stored queue, and a late persist never lands in the next profile. `restore` merges
  with rows already there and runs once for each profile and set of rows (`restoredFor`): the
  screen that asks for it composes again with every recreation of the activity, and a second run
  would read the stored queue as it still is, with a video the viewer just cancelled in it (its
  write may be pending).
- A write that straddles a session end is dropped, not tagged with the new marker: the snapshot
  carries the set of rows (`persistSnapshot().generation`), `saveDownloadQueue(rowsGeneration)`
  re-checks it inside the edit after it read the marker, and `Td.renewDatabaseMarker` drops the
  rows first, renews second, and drops them again (so a restore that began in between finds its
  rows gone). `OfflineDownloads.persistQueue` is the one write path (the service and the model
  test run it).
- A start the queue skips (asked under another profile or in rows that are gone) removes the
  row of its id only in the rows it was queued in (`DownloadStamps.discard`,
  `OfflineDownloads.forgetQueuedIn`): the same id in the next database is another video.
- The stored queue carries the TDLib database's marker (`DatabaseMarker`, a random id in the
  profile's database folder, made with the folder by `AccountDirs.make`, renewed on a sign out and
  an ended session, synchronized and read back from the disk). It is taken from the writing
  profile's own folder (`Td.databaseMarkerFor`), never from `Td.paths`, which a switch moves early.
  A queue of another database is dropped; a marker that cannot be read keeps the tag there was and
  restores nothing.
- A queue from before markers (no tag) is taken only by a database that was there before markers,
  noted as such when it got its marker (`DatabaseMarker.fromBeforeMarkers`), once; after a renew or
  a remade folder it is another database's.

`DownloadQueueModelTest` (core) runs thousands of seeded random sequences of these events on the
real rows, stamps, markers and scoped store, checks the invariants after every step and prints the
failing seed with its steps shrunk. Its writes are the service's own (`persistQueue`, with a hook
for a persist that straddles a switch, `PersistAcrossSwitch`, or a session end,
`PersistAcrossRenew`), and a Cancel whose write is pending followed by a second `restore`
(`CancelThenRestore`) must not bring the row back. A second test runs the same write path on real
threads: three writers hammer `persistQueue` while the orchestrator enqueues, cancels, signs out,
ends sessions and switches, with the invariants checked after every step. Small tests in
`DatabaseMarkerTest` pin the rest: a restore runs once for each set of rows, a row already in
memory keeps its place, and two writes never cross (a held write while a newer one is asked).
Two seams (`OfflineDownloads.afterStoredRead`, `Td.betweenDropAndRenew`) let the model land a
switch or a restore at the two places a race can only be made on purpose.

Mutation check (by hand, 2026-11-21, 14 one-line changes of the production code, each must turn
the suite red): generation re-check in the store write, once-per-generation restore, the rows
guard of a skipped start, the write without an owner, `startable` always true, a drop that does
not count, a restore that ignores the marker, a merge that ignores a switch meanwhile, a write
with no lock, renew before the drop, no second drop, `ifRows` ignoring the count and a restore
written without its marker are all caught. The first run missed four of them (the merge across a
switch, the second drop, the restored queue's tag, the lock), which the seams and tests above
now catch. One is an equivalent mutant: swapping the order of `restored + _active.value`
changes nothing, since `restore` already leaves out the ids that are in memory. That makes all 14
named: 13 caught, 1 equivalent. Two more (2026-11-23), both caught by `DatabaseMarkerTest`: the merge
without its own `restoredFor` check (two restores in flight) and `restore` writing outside
`persistQueue`. Not modelled:
the service's own scope, its conflated writer channel and the `NonCancellable` completion paths
(they call the same `persistQueue`), TDLib's answers, and the clock (nothing in this path reads
one, so there is nothing to fake).

The list writes from a menu (`listWrite`) and the chat list's cold start snapshot run on the
background scope as the profile open when they were asked (`AccountOwner`), so one that lands
after a switch is dropped.

Still resolving the open profile when they write, and why that is safe: the players' history
edits, watched marks and bookmarks carry the player's owner (taken when it opened) or run on its
`lifecycleScope`, and the player closes before a switch; every other screen's edit runs on the
screen's own scope, which a switch ends when it rebuilds the shell, right after the flip and before
the next profile's first frame.

The hooks before a switch run together under one deadline of 30 s (`BeforeSwitch.runAll`); the
picker says what it is still waiting for ("Finishing first: a sync, moving downloads") with a
Cancel that stays on the open profile. Work that has not stopped by the deadline is left to end
on its own: its writes carry their owner and are dropped.

Shared (`ui/src/shared/.../ui/lock`, phone, tablet, TV, desktop and iOS alike):

- `WhoIsWatching` draws the row; `ProfileFlow` (one per process, held by the platform) is every
  press, PIN, panel and switch; `ProfilesGate` goes round the platform's shell: the picker at
  launch with two profiles or more, the open profile's PIN when it was not given, then the shell
  keyed on the open profile. With one profile and no PIN the gate draws the shell and nothing else.
- A platform implements `ProfilePlatform` (playing, downloading, `beforeSwitch`, its `wipe`, `say`,
  its app lock) and calls, at start: `Accounts.open`, `Td.start`, then `Accounts.storesRegistered()`
  once `HomeCache.file`, `PageQueries.store` and the stores are made, and `ProfileUnlock.install()`;
  `ProfileUnlock.onBackground()` when its last window leaves the screen and `onForeground()` when
  one is back; `BeforeSwitch.add` from its player (save the place, close). Strings: the `profiles`
  section of en.json.

### The PIN rules

- `ProfileUnlock` holds the one unlocked profile, in memory only: the open one, once its PIN was
  given. A switch, sign out, remove or add locks it, so opening any profile with a PIN asks every
  time; the app lock locking the app locks it; more than 60 s in the background locks it (a
  rotation or a glance at another app does not); a process that died asks again.
- Sign out of a profile with a PIN that is not the open, unlocked one asks its PIN, then the app
  lock's when it has one. Remove asks the app lock's PIN and the profile's own (unless its folder
  is gone). Add account asks the app lock's PIN. Remove all accounts asks the app lock's PIN and
  then every profile's PIN in turn, before anything is wiped: a wrong PIN, a wait or Cancel wipes
  nothing. The app lock's "ask when the app opens" comes before the picker.
- Switch profile from an account menu asks the target's PIN only, never the app lock's. The web has
  no app lock: if it ever gets one, gate `askSignOut` and `addAccount` there (SEC-09).
- A PIN given counts for what it was given for, once (security review 2026-11-24): the profile PIN
  typed at the picker is spent by the switch it opened, whether that switch worked or not, so Try
  again on a failed switch asks it again; the app lock closing or the app leaving the screen forgets
  it. Settings' gate (`SettingsUnlock`) is one visit: leaving Settings for anything but About and Add
  chats, or the app leaving the screen whatever the lock's options, locks it, and every sign out
  (Settings, the TV bar over Downloads, the phone's account button) reads it. Nothing of
  `ProfilesGate` (a notice, a panel) is drawn while "ask when the app opens" is shut or the lock is
  unread, and the app lock closing drops the notice and any panel past a PIN. A sign out acts only on
  the profile its question named, checked after the backup.
- Forgetting a profile's PIN has no way out in the app (only clearing TMPlayer's storage removes
  every profile): the user's call, see the B1 report.

### What shows a profile outside the app, and its lock rule

Two readings in `ProfileUnlock`. Inside the app, `lockedNow()`: the open profile has a PIN that
was not given in this process, or the app was away more than 60 s (or the clock went backwards),
counted when read, before any timer ran. Outside the app, `hidden()` and `hiddenNow()`: a PIN
profile is hidden whenever it is locked AND whenever no window of the app is on screen, from the
moment it leaves (a frozen or killed process leaves nothing behind); it shows again once the app
is open and the profile unlocked. `onBackground` also schedules the `lock()` itself, so listeners
hear it while the app is away.

Two choke points, one rule each:

- The launcher (Play next, TV channels) writes through `OutsideWrites.mayWriteNow(stamp)`, asked
  under the publisher's lock: nothing while hidden or for a profile no longer open; removing
  always may.
- Every notification and the cast's framework media session go through `ProfileNotifications`
  (`post`, `foreground`, `cancel`) and `ProfileMedia.setMetadata`, which read `hiddenNow()`: while
  hidden the shade gets a neutral notification (the app's name, the same channel, icon and tap,
  nothing of the profile); on every flip of `hidden()` what is still up is posted again (ongoing
  ones neutral or real, the profile's results taken down). Each post says its kind: `Ongoing`,
  `Result` or `Device` (the device's, never changed). Each is tagged with the profile it was
  posted for: a switch takes down every profile notification of the profile left, a sign out
  all of its own, and a cold start with the open profile hidden takes down every notification an earlier
  process left but the device's (marked `DEVICE_EXTRA`, or on the updates, low storage or news
  channel); an unmarked one is taken for a profile's. The first run after the upgrade clears the
  shade once (a versioned flag), since older versions marked nothing. The neutral copy keeps the tap, auto cancel and the swipe's delete intent.
- `ProfileNotificationsPolicyTest` scans every source set under app/src but the tests (main,
  cast, nocast, vlc, novlc, dev, debug, preview, promo, fdroid) for posts, media sessions and
  their metadata, cast items, widgets and shortcuts; each hit routes through the wrapper or
  carries a `// profile-notifications: <its rule>` marker on its line or the two above.

| Surface | Rule |
| --- | --- |
| Continue watching widget (phone) | the Locked tile while hidden (`ContinueWidget.lockedBy(lock, profileLocked)`), worked out again on every switch |
| TV home channels (Android TV) | `TvChannelsPublisher.publish` refuses under its lock while hidden or for a stale stamp; cleared by `OutsideSurfaces` whenever `hidden()` is true (its first value included) and on every switch; Home forgets its last plan when hidden, so it is written in full again |
| TV Play next row | `WatchNextPublisher.apply` refuses each upsert while hidden or for a stale stamp (the player passes the stamp it opened under); cleared with the channels |
| Download progress and summary | `ProfileNotifications`, `Ongoing`: neutral while hidden, every row drawn again on a flip |
| Download results, transfer results (`AndroidTransferNotifier`), Save to folder's result | `Result`: neutral while hidden, taken down when the profile hides |
| Save to folder (`FolderExport`), moving downloads (`DownloadRelocationService`), transfer progress | `Ongoing` |
| New video, episode and piece alerts | not even checked while hidden (`BackgroundChecks`, checked again before each post); posted as `Result` |
| Playback stopped notice (`PlaybackService`) | `Result` |
| Cast to a TV (`DlnaCastService`, both builds): notification and media session | `Ongoing`, and `ProfileMedia`: the app's name only while hidden |
| Google Cast (regular build, app/src/cast `CastBuild`): the framework's own notification, lock screen and Bluetooth controls, and the TV's screen | with a PIN profile open the item is loaded with the app's name only (`OutsideWrites.castTitles`), from the load on: the framework cannot change a loaded item's titles, so a PIN profile's casts stay neutral even while unlocked (accepted, 2026-11-20; the profile's options say so: "a cast started from it shows only TMPlayer's name"); other profiles' casts name the video |
| Long jobs of a profile (Save to folder, moving downloads, an external player's stream, a cast to a TV, Google Cast, transfers, downloads) | each takes its owner (`Accounts.stamp`) when it starts and runs as that owner (`AccountOwner`), so every store write is the owner's; before a switch, a sign out (Settings' too) or Remove all accounts the `BeforeSwitch` hooks stop Save to folder and the move of downloads between two files and wait for them (`ProfileJobs.beforeSwitch`, 30 s at most: every hook is bounded together); TDLib calls and removals are skipped once the owner is not open (`ProfileJobs.mayWrite`); a moved file's record is written at once, never cut off by the cancel; a post whose owner is no longer open is dropped (a foreground one goes up neutral and is kept neutral, so no flip brings its titles back); the first switch also stops their services and ends a cast (`ProfileJobs.stopOnSwitch`, at once for a service started after the switch) |
| The dev build's name dump (app/src/dev `NameDumpService`) | a fixed title, nothing of a profile |
| External player stream (`ExternalStreams`) | `Ongoing` |
| Updates, update installed, low storage, the sponsor card | `Device`: unchanged, nothing of a profile in them |
| Player media session and controls (Media3, `PlayerActivity`) | its own private metadata (the app's name) while hidden or while the app lock asks on open; the player closes on start when `lockedNow()` and before a switch |
| Recents thumbnail | while the open profile has a PIN: Android 13 and later `setRecentsScreenshotEnabled(false)`; below 13 `FLAG_SECURE`, which also blocks screenshots, screen recording and some casting of that window (MainActivity and PlayerActivity); off when the open profile has no PIN |
| Trailers, offline downloads | drawn inside `ProfilesGate`, keyed on the open profile: a switch or a lock closes them |
| "Wrong title?" and the toasts (`TopMessageHost`) | where they always were, over everything, but only while the open profile's shell shows (`profileShellShown`); "Wrong title?" keyed on the open profile |
| Launcher shortcuts (WP-42) | Continue watching, Search and Downloads hold nothing of a profile; the last chat's shortcut names the chat only while `LauncherShortcuts.hiddenFor` is false (no PIN on the open profile, not hidden, no lock over the app, the lock read, outside writes allowed), followed process wide by `LauncherShortcuts.follow` (the lock, `ProfileUnlock.hidden()` and `ProfileUnlock.activeHasPin()`, so a PIN set now hides it at once) and on every switch; taking it off rewrites every copy, a copy pinned to the home screen too, as "Last chat" with no chat in its intent (`updateShortcuts`, SEC2-03), then removes the dynamic one |
| Share target (text shared to TMPlayer) | capped at 8,000 characters and handed as a link to `LinkRequests`, which opens only behind the lock and the profile gate |
| App lock's fingerprint or face (WP-41) | the app lock's own pad only, never Settings, sign out, profile PINs or the kids lock; Class 3 (strong) biometrics only (`BiometricPrompter.AUTHENTICATORS`, SEC2-07): a Class 2 2D camera face unlock may accept a photo, so a phone with only that sees no row (user decided strong only, 2026-11-24; a person who had it on and now has only a weaker sensor keeps the row off with a line saying the PIN is used, and the pad never offers the sensor); each prompt answers only while it is the open one (SEC2-06) |

## Phase B2: the desktop and iOS

### The desktop (Windows, Linux, macOS)

- `Main.kt`: `Accounts.open` before `Td.start` (as before), then once `HomeCache.file`,
  `PageQueries.store` and the three stores are made, `Accounts.storesRegistered()`,
  `ProfileUnlock.install()` and `ProfileOutside.follow`. The download queue is restored on every
  sign in of a client (the launch, a switch, Add account), not once.
- The window: `WindowPresence` follows its minimise, restore, hide and show and calls
  `ProfileUnlock.onBackground` / `onForeground`. A window that only lost the focus to another app
  is still on screen. Minimised or hidden more than 60 s, the open profile asks its PIN again.
- `DesktopProfiles` holds the one `ProfileFlow` for the process over `DesktopProfilePlatform`
  (the player's `BeforeSwitch` hook from `PlayerHost`, the download runner's pause and moves, the
  wipe `desktopForgetProfile`, the app lock's PIN). `DesktopShell` draws `DesktopProfilesGate` (the shared `ProfilesGate`, the arrow keys moving the focus in the picker and its panels when nothing focused took them) around the
  window's content; `ShellState` is built again for each open profile (full screen carries over),
  so no page, chat, player or search of one profile is drawn for the next. The trailer host,
  "Wrong title?" and the toasts show only while `profileShellShown`. Add to a list lives inside
  the gate (`GatedShell`), so a lock or a switch takes an open picker down; Home's offline
  downloads are read for the open profile's shell and forgotten on a switch
  (`rememberOfflineHome`). Every other host, menu, sheet, palette, drop target and the player is
  drawn by `Browse`, inside the gate; outside it are only the device's (the crash question, the
  recovery card, the window's buttons). While the gate shows, the window's shortcuts leave the
  hidden shell alone (`ShellState.shellShown`): quit, minimise and full screen still work, Esc is
  the gate's.
- Settings, Account: Switch profile and Remove all accounts with two profiles or more, Add account
  while there is room (one profile too: it is how the second is added), all through the flow.
  Sign out goes through `ProfileFlow.signOutOpen` after its backup question.
- Folders, from the owner's folder name (invariant 4): `DesktopPaths.profileDownloadsDir` and
  `downloadsDirFor(folder)` for every download written, listed or offered; the watch cache's sweep
  takes `rootFor = profileFilesDir(DesktopPaths, owner.folder)`; a storage move carries the open
  profile's cache and downloads only (`StorageLayout.forProfile`), runs as its owner, reads the
  records only while the owner is open and checks it before each file, and writes a moved file's
  record with `AccountAddress`. A switch, a sign out or Remove all accounts waits for the file in
  hand (`StorageRelocation.stopForSwitch`, a `BeforeSwitch` hook, 30 s at most) and the move stops
  after it. The other profiles' caches at the old root are deleted with the move (none of their
  clients runs; TDLib fetches again what it misses); their downloads stay where they are and play
  from there. The update staging is the device's.
- Save to folder (`DesktopFolderExport`) and the legacy downloads' move (`DownloadMigration`) take
  their owner when they start, run as it, check `mayAct` before each removal, record and TDLib
  call, and a switch stops them between two files and waits.
- The download runner: each request takes its stamp and rows when queued (`DownloadStamps`), a
  stale one never starts, its fetch takes `Td.sessionFor`, row updates go through `ifRows`, a switch
  or a new database stops every job (`stopForSwitch`, which also forgets the ids asked about
  again), the queue is written with `persistSnapshot()` for the rows' owner, and a finished file's
  record is addressed to its owner. The move into Downloads runs as the owner and rows of the
  fetch that finished it (`moveContextOf`), never looked up again by file id: after a switch has
  cleared the stamps that lookup would name the profile open then.
- The window away: minimised, hidden, or the whole app hidden with macOS's Hide (Cmd+H, an
  `AppHiddenListener` where `java.awt.Desktop` offers it). Losing the focus to another app, or the
  window being on another virtual desktop, is not away (accepted).
- An ended session renews the database marker; the app lock is cleared at a sign in screen only
  while one profile is left.

Outside the window (`ProfileOutside`, tested in `ProfileOutsideTest`):

| Surface | Rule |
| --- | --- |
| Download and move progress (Linux D-Bus, Windows toasts) | `ProfileGuardedNotifier`: each transfer keeps the profile it was begun for; its title only while that profile is the open one and shown, the app's name otherwise; a late update of one taken down with its profile posts nothing |
| Download and move results | real only for the open, shown owner; the app's name and no file to open otherwise (a transfer of A done after a switch to B names nothing of A). A transfer begun, or a result never begun, belongs to the job's own owner (`ProfileOutside.asOwner`). A real one is kept by its owner (`KeyedResults`) and taken off the system's list when that profile hides, is left or signs out: Linux `CloseNotification` by the server's id, Windows `History.Remove` by its tag. Its press asks first: a stale one opens no file or folder (Linux checks before Show in folder; a Windows result opens through a `tmplayer:` link that checks) |
| New video, episode and piece alerts | not even checked while hidden (`DesktopChecks`), posted through `ProfileOutside.profileNotice` with the owner taken when the check began, read again at each post; kept and taken down like a result; a press on a stale one does nothing |
| Low storage, a new version, the support ask | the device's, unchanged |
| Media controls (MPRIS on Linux, SMTC on Windows) | `ProfileOutside.mediaTitle`: the app's name while hidden, the title again once shown |
| On a switch or a sign out | every transfer, result and alert of the profile left comes off the system's list |
| Window title | `WindowTitle.of` (WP-51): the page or the video's name (never a chat's) while the open profile is shown, only "TMPlayer" while a PIN profile is hidden; the video is named only for the profile it was opened under (`PlayRequest.owner`, SEC2-08), as `ProfileOutside.mediaTitle` has it. The title shows in the taskbar, Alt+Tab and screen sharing |
| Not blockable | the system's own pictures of the window (taskbar and dock previews, Alt+Tab, Mission Control, screenshots) show what the window last drew; on Linux without a session bus (`notify-send`) a result cannot be taken down; on a Windows development run (no `tmplayer:` link) a result's press is the file's own URI |
| None on the desktop | tray icon, recent items, presence (Discord or other), macOS notifications and media controls (nothing is posted there) |

Left LOW after the B2 fix round (2026-11-21):

- A storage move cut short and finished at the next launch (`resumePending`) deletes only the open
  profile's old cache: the pending note keeps that one path, not the old root, so the other
  profiles' old caches stay until a later move. Keep the old root in the note to sweep them too.
- A stale Windows result's press on a development run, and a notify-send result on Linux, cannot
  be checked or taken down (see Not blockable).
- Needs Windows: the tagged results leaving the Action Center, the `tmplayer:` press. Needs a Mac:
  the Cmd+H re-lock.

### iOS

The iOS app compiles Android's `App.kt`, `MainActivity.kt`, `ui/**` and `data/**`, so the B1
wiring is its wiring: `Accounts.open` (through `SettingsStore(context)` and `Td.start(context)` on
the app's own files folder), `storesRegistered`, `ProfileUnlock.install`, `ProfileNotifications`,
the gate (`ProfilesRoot`, `AndroidProfiles`), Settings' rows and the sign out through
`signOutProfile`. The scene's phases reach `ProfileUnlock` through the activity's start and stop
(`ios/kit` `ActivityShell.follow` to App's `LockOnLeave`). iOS's own pieces:

- Downloads (`IosDownloads`): the owner's folder (`DesktopPaths.downloadsDirFor`), the queue
  restored on every sign in, its file check written for the profile that read it.
- Now Playing (`NowPlaying`): the app's name, no artist, no artwork while hidden, drawn again on
  each flip.
- The app switcher's picture (`SnapshotCover`): a plain cover over every window of every
  connected scene (an iPad's split view and Stage Manager) as the app resigns active, since the
  switcher's live card shows an inactive app, while the open profile has a PIN; again at the
  background; each call covers every window that has none yet (a second window or scene, an
  external display that came since) and forgets the ones gone (`WindowCovers`, core, tested in
  `WindowCoversTest`); every cover off when it is active again. A system alert or the
  Control Center over the app covers it too while it lasts.
- Notifications go through `ProfileNotifications` as on Android. There are no widgets, Spotlight
  items or Siri suggestions in the iOS app today.
- Needs a Mac or a device: linking the framework, the kit's `SnapshotCover` calls (`ios/kit`
  does not compile on Linux) and the cover on a real app switcher and on an iPad with two windows, Now Playing on the lock screen, the scene phases (60 s re-lock), the picker
  and PIN pad on a phone and an iPad, notifications while hidden.
