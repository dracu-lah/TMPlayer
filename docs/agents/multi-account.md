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

## Phase B1: the shared screens and Android

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
  all of its own, and a cold start with the open profile hidden takes down the profile notifications an
  earlier process left (each carries `ProfileNotifications.PROFILE_EXTRA`; the device's stay). The neutral copy keeps the tap, auto cancel and the swipe's delete intent.
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
| Long jobs of a profile (Save to folder, moving downloads, an external player's stream, a cast to a TV, transfers, downloads) | each takes its owner (`Accounts.stamp`) when it starts and posts for it; a post whose owner is no longer the open profile is dropped (a foreground one goes up neutral); the first switch, sign out or remove stops the job and takes its foreground notification down (`ProfileJobs.stopOnSwitch`) |
| The dev build's name dump (app/src/dev `NameDumpService`) | a fixed title, nothing of a profile |
| External player stream (`ExternalStreams`) | `Ongoing` |
| Updates, update installed, low storage, the sponsor card | `Device`: unchanged, nothing of a profile in them |
| Player media session and controls (Media3, `PlayerActivity`) | its own private metadata (the app's name) while hidden or while the app lock asks on open; the player closes on start when `lockedNow()` and before a switch |
| Recents thumbnail | while the open profile has a PIN: Android 13 and later `setRecentsScreenshotEnabled(false)`; below 13 `FLAG_SECURE`, which also blocks screenshots, screen recording and some casting of that window (MainActivity and PlayerActivity); off when the open profile has no PIN |
| Trailers, offline downloads | drawn inside `ProfilesGate`, keyed on the open profile: a switch or a lock closes them |
| "Wrong title?" and the toasts (`TopMessageHost`) | where they always were, over everything, but only while the open profile's shell shows (`profileShellShown`); "Wrong title?" keyed on the open profile |
| Share targets, shortcuts | none exist today |
