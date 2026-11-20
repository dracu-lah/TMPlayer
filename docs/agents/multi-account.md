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

1. `pending` is set to the profile being opened, then the client is reopened.
2. Once it is open, `active` becomes that profile and `pending` is cleared.

On the next start [Accounts.open] finds a `pending` left behind and reopens `active`, the last
profile that was really open. A profile still being added (`adding`, never signed in) that is not
the only one is removed with its folders: a half made folder never lingers. A file that cannot be
read starts again with the adopted profile alone (the platform's folders), which is the state of
the version before.

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
