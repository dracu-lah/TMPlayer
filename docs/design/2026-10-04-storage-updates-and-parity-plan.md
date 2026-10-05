# TMPlayer plan: storage that lives where the user says, downloads that are not cache, an update button everywhere, and the Windows taskbar bug

Date: 2026-10-04. Written against commit 60c9c52 (release 1.19.1). This is a plan and nothing
else: nothing in it has been implemented, and nothing should be until the user says so. It is
written so that work can start from this file alone after a context reset.

How it was built: four read only audits of the codebase (storage and downloads, the desktop
window code, the update pipeline and navigation, a feature parity matrix across phone, TV, Linux,
Windows and macOS) and four web research threads (the Win32 fullscreen round trip, TDLib's
directory and download semantics plus how Telegram Desktop, Spotify, Stremio and qBittorrent name
and place cache versus downloads, progress notifications on every target, update check
conventions). Every "X does Y" below was checked against the source or a primary document on
2026-10-04 unless marked *unverified*. Line numbers are at 60c9c52 and will drift.

The user's asks, in their words and in the order given, with where each is answered:

1. Windows: maximized, fullscreen, then unfullscreen leaves the bottom behind the taskbar. Part A.
2. Desktop: a custom storage location, cache included, so the internal drive is not filled. Part B.
3. First launch runs a background update check and shows a yellow "Update <version>" button in the
   side bar. Part C. Mid task the user added: a popup saying a new version is out, with update,
   later and ignore choices, with better phrasing. C4.
4. Cached videos and downloaded videos are separate things, with a button that moves a cached
   video into downloads. B3, B5.
5. Every feature on every target; hide only when necessary or unneeded. Part D.
6. Rephrase cache and download wording so nobody confuses them. B6.
7. Choosing another drive or folder creates a TMPlayer folder with downloads, cache and other
   subfolders. B2.
8. A half cached video that is asked for moves into downloads when it completes, with progress
   notifications on every target. B4, B7.
9. Find features hidden on some targets and add them. Part D.

Part E lists the decisions that are the user's. Part F is the phased delivery and the status
checklist. Part G is the test plan.

Paths are under `app/src/main/java/com/tmplayer/` for Android, `desktop/src/main/kotlin/com/tmplayer/desktop/`
for the desktop and `core/src/commonMain/kotlin/com/tmplayer/` for shared code unless written
in full.

---

## Summary of what changes, in one screen

**Windows fullscreen (Part A).** `NativeFullscreen` never clears the maximized style bit while
the window is fullscreen, so on the way out `SetWindowPlacement(SW_SHOWMAXIMIZED)` finds a window
that already says "maximized" and leaves its monitor sized rectangle alone. The fix is the mpv
and SDL sequence: un-maximize before stripping the frame, remember that it was maximized, and
re-maximize explicitly after the frame comes back. Belt and braces through AWT's
`maximizedBounds`, a `pendingMaximize` flag for Win+Up during fullscreen, and a dev harness
flag that starts maximized so the round trip can be asserted on a Windows CI runner.

**Storage (Part B).** Today a "download" is the same file TDLib holds in its cache, told apart
only by a `dl_` preference record; on the desktop that cache sits under the OS cache directory
(`~/.cache/TMPlayer`), so the user's kept videos live where cleanup tools delete things. The plan
makes the split physical. TDLib's `files_directory` becomes the cache and nothing else, evictable
by rule. A download is a file **moved out** of that cache into TMPlayer's own `downloads` folder
and tracked by TMPlayer's own index with a path. A cached video can be kept with one action
("Save to Downloads"); a video asked for while half cached finishes through the existing queue
and then moves. On the desktop the user picks a storage location; TMPlayer creates
`<location>/TMPlayer/{cache,downloads,updates}` there, restarts TDLib with the new cache
directory (the session stays, it lives in the data directory), and moves the downloads over with
a progress notification. The cache is cleared on a move rather than copied, because TDLib stores
absolute paths and would treat copied files as strangers. One cache cap setting on the desktop
(default 10 GB) replaces the phone's one video rule there; the phone and the TV keep their rule.
Wording is settled: *Cache* and *Downloads*, never "saved" or "on this computer" for both.

**Updates (Part C).** The Android drawer and TV rail already carry an amber "Update" row with
the version as a badge, checked once per launch after sign in. The desktop has only a corner
notice, checked once a day, that disappears for good when dismissed. The plan gives all three the
same thing: a check 10 s after the first frame on every launch including the first, repeated
every 6 h while running, a feed file on the project's own site with the GitHub API as fallback, an
amber side bar item "Update 1.20.0" that stays until the version is installed or skipped, and a
popup shown once per version with *Update now*, *Remind me later* and *Skip this version*. One
feed parser and one version comparison in `:core` instead of two.

**Parity (Part D).** The audit found 70 odd rows. Things that are plainly missing on one target
and cost little are scheduled: the desktop's dead "Download the whole video first" switch, pin,
mute, archive and mark read on desktop chats, multi select download, "Open in another app",
the storage card and confirmations on desktop, PiP on Android TV, the speed menu, Playback
details and Start over on the TV, Copy link on Android, external subtitles on Android, SMTC on
Windows. The hides that stay are listed with the reason each.

---

# Part A: the Windows fullscreen round trip

## A0. What exists today (audit)

Fullscreen is one boolean, `ShellState.fullscreen` (`desktop/.../ui/ShellState.kt:93`), flipped
by the player (`player/PlayerScreen.kt:408-409`, double click at 517, overlay button
`player/PlayerOverlay.kt:313-315`, menu 543; keys F, F11, Alt+Enter, Esc in `player/PlayerKeys.kt:193-248`).
The window side is `Main.kt:85-101`: `NativeFullscreen(window).set(on)`, and when that returns
false (macOS, a Wayland toolkit) Compose's `WindowPlacement.Fullscreen` is used instead.
`WindowMemory.freeze(Hold.Fullscreen, on)` is called first, so nothing is persisted while
fullscreen.

`os/NativeFullscreen.kt`, Windows branch (lines 105-128):

Enter:
1. `GetWindowPlacement` into `saved.placement` (showCmd is `SW_SHOWMAXIMIZED` when maximized).
2. `GetWindowLong(GWL_STYLE)` into `saved.style` (contains `WS_MAXIMIZE` when maximized).
3. `MonitorFromWindow(MONITOR_DEFAULTTONEAREST)` and `GetMonitorInfo`, using `rcMonitor`.
4. `SetWindowLong(GWL_STYLE, style and WS_OVERLAPPEDWINDOW.inv())`.
   `WS_OVERLAPPEDWINDOW` (0x00CF0000) does **not** contain `WS_MAXIMIZE` (0x01000000), so the
   window stays flagged maximized.
5. `SetWindowPos(rcMonitor, SWP_NOOWNERZORDER or SWP_FRAMECHANGED)`.

Leave:
1. `SetWindowLong(GWL_STYLE, saved.style)`.
2. `SetWindowPlacement(saved.placement)` with `showCmd = SW_SHOWMAXIMIZED`.
3. `SetWindowPos(0,0,0,0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED)`.

Nothing in the leave path supplies a rectangle for the maximized case; it relies on Windows
recomputing the maximized rectangle from the `SW_SHOWMAXIMIZED` transition.

## A1. Diagnosis

The transition "normal to maximized" never happens on the way out, because the window never
left the maximized state on the way in. `ShowWindow(SW_SHOWMAXIMIZED)` (which is what
`SetWindowPlacement` performs after writing the rectangles) on a visible window that already has
`WS_MAXIMIZE` set does not recompute the maximized rectangle; it ends as a no size, no move
operation. The window therefore keeps the full monitor rectangle it was given on entry, now with
a caption drawn at the top, so the bottom `rcMonitor.bottom - rcWork.bottom` pixels (48 px on
Windows 11 at 100 percent) sit under the taskbar. Windows, AWT (`Frame.extendedState`) and
Compose (`WindowState.placement`) all still say "maximized", which is why clicking the caption's
maximize button first restores and only the second click fixes the size. The floating case works
because its `rcNormalPosition` carries a real rectangle.

Why this is the mechanism and not something else:

- `WindowMemory` is frozen during fullscreen (`Main.kt:93`) and `observe` ignores non floating
  placement (`os/WindowMemory.kt:236-244`); it cannot write bounds. Ruled out.
- Compose's placement branch is never reached on Windows (`native.set` returns true). Ruled out.
- `MiniPlayerWindow` and `WindowsTitleBar` cannot resize. Ruled out.
- Chromium's `FullscreenHandler` sends `SC_RESTORE` before going fullscreen and `SC_MAXIMIZE`
  after restoring, with the comment that Windows does not hide the taskbar for a maximized
  window; mpv un-maximizes first (`SW_SHOWNORMAL`), remembers `pending_maximize`, and re-enters
  maximized with `SetWindowPlacement` on exit; SDL3 clears `WS_MAXIMIZE` on entry, remembers
  `windowed_mode_was_maximized`, and calls `ShowWindow(SW_MAXIMIZE)` on exit. All three do the
  step TMPlayer skips.

A second, related consequence: with `WS_MAXIMIZE` still set during fullscreen, Explorer's
fullscreen heuristic may not treat the window as fullscreen at all, so the taskbar can stay on
top of the video when the app was maximized before pressing F. Same root cause.

Linux X11 (`_NET_WM_STATE` client messages) and macOS (Compose placement) do not have the
problem: the window manager owns geometry and keeps the maximized hints beside the fullscreen
one. Compose's own Fullscreen to Maximized transition is broken on macOS and Linux
(compose-multiplatform issues 4006 and 4380: the Maximized setter never clears `isFullscreen`),
which is a reason not to move Windows onto Compose's placement either.

## A2. The fix

All in `os/NativeFullscreen.kt`, Windows branch, plus one small addition to the dev harness.

**Saved state** becomes `WindowsSaved(style: Int, exStyle: Int, placement: WINDOWPLACEMENT,
monitor: HMONITOR, work: RECT, wasMaximized: Boolean)` plus a `pendingMaximize: Boolean` field
on the class. `wasMaximized = style and WS_MAXIMIZE != 0` (JNA 5.16 `User32` has no `IsZoomed`;
the style bit is equivalent, or bind `IsZoomed` in a small private `Library` interface the way
`WindowsTitleBar.Dwm` does).

**Enter**, replacing lines 108-118:

1. Return if `windowsSaved != null`.
2. Read style, ex style, placement, monitor and its `MONITORINFO`, and `wasMaximized`.
3. If `wasMaximized`: copy the placement, set `showCmd = SW_SHOWNORMAL`, `SetWindowPlacement`.
   The window is now a normal window at `rcNormalPosition`; AWT receives `WM_SIZE(SIZE_RESTORED)`
   and Compose reads `Floating`. `WindowMemory` is already frozen, so this intermediate state is
   not persisted.
4. `SetWindowLong(GWL_STYLE, style and (WS_CAPTION or WS_THICKFRAME or WS_MAXIMIZEBOX).inv())`.
   Dropping `WS_MAXIMIZEBOX` blocks Win+Up and the title bar shake from re-maximizing the
   borderless window. `SetWindowLong(GWL_EXSTYLE, exStyle and (WS_EX_DLGMODALFRAME or
   WS_EX_WINDOWEDGE or WS_EX_CLIENTEDGE or WS_EX_STATICEDGE).inv())` (Chromium's set).
5. `SetWindowPos(hwnd, null, rcMonitor..., SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)`
   with the monitor from step 2, so a window on the second monitor goes fullscreen there.
6. Optional polish: `DwmSetWindowAttribute(DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_DONOTROUND)`
   so Windows 11 does not round the fullscreen window's corners; undo on leave. Skip
   `ITaskbarList2::MarkFullscreenWindow` (COM through JNA is more plumbing than it is worth; a
   monitor sized client area is what the heuristic looks for).

**Leave**, replacing lines 119-128:

1. `SetWindowLong(GWL_STYLE, saved.style or WS_VISIBLE)`, `SetWindowLong(GWL_EXSTYLE, saved.exStyle)`.
2. Decide the target: `showCmd = if (saved.wasMaximized || pendingMaximize) SW_SHOWMAXIMIZED else saved.placement.showCmd`.
3. If the current monitor (`MonitorFromWindow`) or its `rcWork` differs from the saved one,
   clamp `rcNormalPosition` into the new work area (Chromium's `AdjustToFit`). Note
   `WINDOWPLACEMENT` is in workspace coordinates, `GetWindowRect` in screen coordinates; never
   mix the two for one rectangle.
4. `SetWindowPlacement(hwnd, placement)` with that `showCmd`. For the maximized case this is the
   step that makes Windows recompute the maximized rectangle from the restored caption and
   thick frame styles and the current work area; it fires `WM_SIZE(SIZE_MAXIMIZED)` so AWT's
   zoomed flag and Compose's `Maximized` come back with no Java call. If the maximize animation
   looks wrong, use mpv's two step: `SetWindowPlacement` with the target `showCmd` first, then a
   second one with the saved `rcNormalPosition`.
5. `SetWindowPos(hwnd, null, 0,0,0,0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED)`.
6. Clear `windowsSaved` and `pendingMaximize`; undo the DWM attribute if set.

**Maximize requested while fullscreen.** Add a `java.awt.event.WindowStateListener` in
`NativeFullscreen` (installed on construction, Windows only). While `windowsSaved != null`, a
state change to `MAXIMIZED_BOTH` means something re-maximized the borderless window (Compose
writing `placement = Maximized`, a stray `WM_SIZE`): set `pendingMaximize = true` and reissue the
`SetWindowPos` to `rcMonitor` from step 5 of Enter. A change to `NORMAL` clears the flag. This is
mpv's `pending_maximize` and SDL's `windowed_mode_was_maximized`.

**Belt and braces at the AWT level.** A maximized frame without `WS_THICKFRAME` covers the
taskbar (JDK-4737788, open since 1.4; compose-multiplatform 1724). While the frame is borderless,
set `window.maximizedBounds` to the work area (`GraphicsConfiguration.bounds` minus
`Toolkit.getScreenInsets(gc)`), and set it back to `null` when the frame is decorated again. AWT
routes this into `WM_GETMINMAXINFO`, which is GLFW's fix for the same thing.

**Constants not in JNA's `WinUser`:** `SC_RESTORE = 0xF120` (not needed if `SetWindowPlacement`
is used for the un-maximize), `DWMWA_WINDOW_CORNER_PREFERENCE = 33`, `DWMWCP_DONOTROUND = 1`.
Present already: `GWL_STYLE`, `GWL_EXSTYLE`, `WS_MAXIMIZE`, `WS_CAPTION`, `WS_THICKFRAME`,
`WS_MAXIMIZEBOX`, `WS_VISIBLE`, `SW_SHOWNORMAL`, `SW_SHOWMAXIMIZED`, `MONITOR_DEFAULTTONEAREST`,
`WINDOWPLACEMENT`, `MONITORINFO` with `rcMonitor` and `rcWork`.

`Main.kt:90-101` does not change. `DevPlayerMain.kt:71-79` gains `--maximized` (sets
`state.placement = WindowPlacement.Maximized` before the first frame) so `--fullscreen-after`
exercises the maximized round trip, and its two prints grow to `dev: back bounds=<rect>
work=<rect> placement=<Compose placement> extendedState=<n>`.

## A3. How to know it works from a Linux desk

- Unit level: none possible; `NativeFullscreen.windows` is all native calls. Keep the struct
  sizes honest (JNA's `WINDOWPLACEMENT` sets `length` in its constructor).
- Wine: can smoke test that the JNA calls do not crash, but has no taskbar, no DWM borders and
  no fullscreen heuristic, so it cannot show the bug or its absence.
- GitHub Actions `windows-latest`: the hosted runner has an interactive desktop (1024x768,
  Windows Server). Add a job step after the desktop build that runs
  `:desktop:runPlayerDev --args="--file <small clip> --maximized --fullscreen-after 1500"`
  and asserts from the printed line that, after the round trip, `extendedState` has
  `MAXIMIZED_BOTH`, Compose says `Maximized`, and the window's bottom edge equals
  `rcWork.bottom` within 1 px. Run the floating variant too (ten toggles, no 8 px drift). A
  desktop screenshot artifact is possible with a marketplace action. Windows 11 taskbar styling,
  auto hide and a second monitor are not covered there.
- Manual checklist for whoever has Windows 11 (INSTALL.md gains it under a "Known gaps"
  heading until someone ticks it): maximized, F, F (expect maximized with the taskbar visible);
  floating, F, F (same rectangle); taskbar on top and on the left; auto hide taskbar pops over
  the restored window; second monitor at a different DPI; Win+Up during fullscreen; minimize
  from the taskbar during fullscreen and restore.

---

# Part B: storage, cache and downloads

## B0. What exists today (audit)

**Where TDLib's files live.** `Td.start(paths, ...)` (`data/Td.kt:113-119`) captures `paths`
once per process; every replacement client in `clientLoop` (121-178) reuses it;
`sendParameters` (299-325) passes `databaseDirectory = paths.databaseDir`, `filesDirectory =
paths.filesDir`, `useFileDatabase = true`. Only `logOut`/`restartSignIn` ever close a client.

| | database (session) | files (TDLib cache) | settings |
|---|---|---|---|
| Android (`app/.../data/AndroidPlatform.kt:30-35`) | `filesDir/tdlib` | `filesDir/tdlib-files` | `filesDir/datastore/tmplayer.preferences_pb` |
| Linux (`DesktopPlatform.kt:14-33`, appdirs) | `~/.local/share/TMPlayer/tdlib` | `~/.cache/TMPlayer/tdlib-files` | `~/.config/TMPlayer/` |
| Windows | `%LOCALAPPDATA%\TMPlayer\tdlib` | `%LOCALAPPDATA%\TMPlayer\Cache\tdlib-files` | `%LOCALAPPDATA%\TMPlayer` |
| macOS | `~/Library/Application Support/TMPlayer/tdlib` | `~/Library/Caches/TMPlayer/tdlib-files` | Application Support |

Nothing is configurable. `tdlib-files` is hard coded in four Android places
(`AndroidPlatform.kt:33`, `data/WatchCache.kt:175`, `res/xml/update_paths.xml:14`, and
`DiskSpace` measuring `filesDir`). `DesktopPrefs` has no path key.

**What a download is.** On both platforms `downloadFile(fileId, priority 32, offset 0, limit 0,
synchronous = true)` (`data/DownloadService.kt:493-500`, `DesktopDownloads.kt:174-180`) fills the
same TDLib file the player streams, and on completion `SettingsStore.noteDownload` writes a
`dl_<chatId>_<messageId>` record (`SettingsStore.kt:738-750`). No copy, no move, no path of
TMPlayer's own. A cached video is a `wc_<chatId>_<messageId>` record (112-115) written by
`WatchCache.claim` / `DesktopWatchCache.claim`. The disk cannot tell them apart; only the
records can. `dl_` history is capped at 200 (`SettingsStore.kt:758-769`): the 201st download
silently loses its record, becomes a stray, and the sweep deletes it after 10 minutes.

**The cache rule.** One cached video at a time on phone and TV (`CacheShelf.plan`,
`data/CacheShelf.kt:91-130`, "anything else in it goes whatever the sizes say"), 400 MB headroom,
`RoomOnDisk.decide` before play on Android (`PlayerActivity.kt:1491-1529`, `MainActivity.kt:405-470`).
The desktop copies the one video rule (`DesktopWatchCache.kt:64-104`) but has **no** room check
before play, never calls `Td.trimStorage()`, has no `StorageSplit` and shows only one used total
in Settings (`ui/Settings.kt:138-141`). The byte ceiling (`CacheShelf.ceiling`, 1 to 4 GB) applies
to non video files only.

**Promotion does not exist.** A fully cached video cannot become a download anywhere: the
Android grid filters it out before the runner (`ui/browse/MediaGridScreen.kt:385-387`, toast
"That video is on this device already."), its tile menu shows a dead "Downloaded" entry for a
merely cached file (1930-1935), the desktop menu hides "Download" when `item.onDevice`
(`ui/MediaGrid.kt:475-481`) and the player says "Already on this computer"
(`player/PlayerMedia.kt:173`). The runner itself would promote (`DownloadService.kt:407-415`,
`DesktopDownloads.kt:135-138`) if asked.

**Other findings to fix on the way:** desktop "Remove download" on a cached only video deletes
the cache file and uses the grid's stale `item.fileId` (`MediaGrid.kt:487-519`); desktop
Downloads lists records without checking the disk (`ui/Downloads.kt:94-120`); desktop runner has
no network hold, no re-sourcing of stale file ids, and lets `free == 0` through
(`DesktopDownloads.kt:145`); `DownloadsScreen.share` uses the stored id without `currentFileId`
(`DownloadsScreen.kt:294`); desktop "Download the whole video first" is stored and never read;
`Td.clearEverythingCached()` has no caller; two copies of the cache rules with small
differences; `OfflineDownloads.REFUSED` is Android wording in core (`OfflineDownloads.kt:328`).

**Wording today** for the same idea: "watch cache", "Cached from playback", "Clear cache",
"Cached videos", "playback cache and previews", "Playing a video keeps a copy here"; finished
downloads are "Completed" on Android and "On this computer" on the desktop; the tile badge is
"Saved" on Android and "On this computer" on the desktop, and both mean "any complete file,
cached or downloaded".

**Players do not care where files are.** Both read whatever absolute `local.path` TDLib
reports (`player/TdByteWindow.kt:197-227` reopens the channel when the path changes;
`PlayerMedia.kt:102-108` hands a complete file to mpv as a plain URI).

## B1. What TDLib allows (research, primary sources)

- `files_directory` and `database_directory` are independent paths; TDLib `mkpath`s and
  `realpath`s each on start, so they may be on different drives and need not pre exist. Keeping
  `database_directory` and changing `files_directory` keeps the login. There is no runtime call
  to change it; the client must be closed and recreated (tdlib/td issue 380, open since 2018).
- TDLib stores absolute local paths in the file database and does not rescan. A file that is
  missing is noticed lazily, on the next `downloadFile`, which then re-downloads it. No
  `deleteFile` is needed first (levlam, issue 1361).
- `FileNode::can_delete()` is "path begins with the files directory", so files left in an old
  directory are invisible to `deleteFile`, `optimizeStorage` and `getStorageStatistics`. Files
  copied by hand into a new directory are equally invisible: TDLib does not know them and TMPlayer's
  stray sweep would delete them after 10 minutes. Therefore **moving the cache tree is useless**;
  the cache is cleared on a location change.
- There is no "keep this file" flag in TDLib's cache. The GC (`FileGcWorker`) is immune to
  stickers, profile photos, thumbnails, wallpapers, files younger than `immunity_delay`, and
  nothing else; the downloads list is not consulted. levlam's recommendation for persistent
  copies: "copy them after they are downloaded" (issues 379 and 1250); creating files outside the
  cache is refused by design; symlinking subfolders is forbidden and has lost data.
- Subfolders are an implementation detail (`videos`, `documents`, `animations`, `video_notes`,
  `temp`, `thumbnails`, `photos`, ...): partials live in `temp/` and are renamed into the typed
  folder on completion.
- `addFileToDownloads(file_id, chat_id, message_id, priority)` is TDLib's own persistent download
  list (survives restarts with the message database, `updateFileDownloads` totals). TMPlayer's
  queue already persists itself; TDLib's list is noted as an option, not adopted.
- `cleanFileName` (synchronous) and `getSuggestedFileName(file_id, directory)` exist for safe
  file names.
- Telegram Desktop writes a kept download straight into `~/Downloads/Telegram Desktop` (Qt's
  standard Downloads location, honouring Known Folder redirection and XDG user dirs), separate
  from its cache, with settings "Download path", "Telegram folder in system «Downloads»",
  "Custom folder, cleared only manually", "Ask download path for each file", and a storage box
  with "Total size limit" and "Media cache limit". On load it drops any download whose file is
  missing or has a different size. Stremio asks for a "Caching Drive" and creates its own
  `stremio-cache` folder on it. Spotify calls the whole thing "Offline storage location". The
  vocabulary the plan adopts: **Storage location** for the root, **Cache** for TDLib's tree,
  **Downloads** for kept files.

## B2. The new storage layout

### B2.1 Model

```
<root>/TMPlayer/
  .tmplayer            marker: "TMPlayer storage, created <date>, do not put your own files here"
  cache/               TDLib files_directory. Evictable. Nothing in here is ever "kept".
  downloads/           TMPlayer's own. Files moved out of cache/ on completion. Never evicted.
  updates/             Self update staging (today DesktopPaths.cacheDir/updates)
```

The TDLib **database** directory does not move: it is small, it holds the session, and moving
it buys nothing. Settings and `desktop.properties` do not move either.

### B2.2 Defaults when no location has been chosen

Desktop:

| | cache | downloads |
|---|---|---|
| Linux | `$XDG_CACHE_HOME/TMPlayer/cache` (rename of today's `tdlib-files`) | `<xdg DOWNLOAD dir>/TMPlayer` |
| Windows | `%LOCALAPPDATA%\TMPlayer\Cache\cache` | `<FOLDERID_Downloads>\TMPlayer` |
| macOS | `~/Library/Caches/TMPlayer/cache` | `~/Downloads/TMPlayer` |

The Downloads folder is resolved properly: Windows through
`Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Downloads)` (jna-platform, already a
dependency), not `user.home\Downloads`, because the folder can be redirected; Linux by parsing
`$XDG_CONFIG_HOME/user-dirs.dirs` for `XDG_DOWNLOAD_DIR` (shell style, `$HOME/...` or absolute)
with `$HOME/Downloads` as fallback; macOS `~/Downloads`. A new `DesktopPaths.downloadsDir` and a
`UserDirs` helper in `desktop/os/` own this. The Flatpak manifests already grant
`--filesystem=xdg-download`; it needs `:create` so the `TMPlayer` subfolder can be made.

Android: `filesDir/cache` (TDLib; rename of `tdlib-files`) and `filesDir/downloads`. Internal
storage, as today. The FileProvider paths (`res/xml/update_paths.xml`) gain
`<files-path name="downloads" path="downloads/"/>` so Share and Open in another app keep
working. The Android "Storage location" choice is limited to the volumes
`context.getExternalFilesDirs(null)` reports (internal, and an SD card or USB drive when present:
`Android/data/<pkg>/files/TMPlayer/{cache,downloads}` on that volume). TDLib can write there, no
SAF needed, and TV sticks with a USB drive are the real beneficiaries. Arbitrary SAF tree URIs are
out: TDLib needs a plain path. Phase 4.

### B2.3 Choosing a location on the desktop

Settings, group **Storage**, first row: *Storage location* with the current root (or "Default
folders") and a **Change** button. The picker is FileKit (`io.github.vinceglb:filekit-dialogs-compose`,
0.16.0 latest documented): native `IFileOpenDialog` on Windows, `NSOpenPanel` on macOS, the
`org.freedesktop.portal.FileChooser` portal on Linux with `directory = true` (so it works
inside the Flatpak sandbox, and the user's choice implicitly grants access), falling back to
`JFileChooser(DIRECTORIES_ONLY)`. `java.awt.FileDialog` cannot pick folders on Windows and does
not use the portal (JDK-8284516), so it is not an option. FileKit needs `FileKit.init("TMPlayer")`
once at startup. Decision E3 covers the alternative of hand rolling the three pickers.

Validation, in order, each with its own refusal text:

1. The path exists and is a directory TMPlayer can create a subfolder in (try to create
   `TMPlayer/.tmplayer`). Refusal: "TMPlayer cannot write there."
2. It is not inside the current TMPlayer folder and the current one is not inside it.
3. `Files.getFileStore(root).usableSpace` is at least the size of the current downloads plus
   1 GB. Refusal: "That drive has X free. The downloads alone need Y."
4. If `TMPlayer/.tmplayer` already exists there (a previous install, or the same folder chosen
   again): offer "Use what is already there" (adopt its `downloads` into the index by scanning,
   see B3.3) rather than refusing.
5. A network path (`\\server\...`, or a FileStore type of cifs, nfs, smbfs, fuse.sshfs): allowed
   with a warning "Network drives are slow to stream from and may disconnect." (Spotify refuses
   mapped drives; TMPlayer warns.)

Then the confirm dialog:

> **Move TMPlayer's files to E:\?**
> Downloads (12 videos, 38 GB) move to E:\TMPlayer\downloads. The cache (3.1 GB) is cleared and
> starts again at E:\TMPlayer\cache. Nothing is removed from Telegram. Playback and downloads
> pause while this happens.
> [Move] [Cancel]

The move itself (`StorageRelocation` in `desktop/`, with the pure planning parts in `:core` so
they are unit testable):

1. Pause the queue (`OfflineDownloads.pauseAll`), stop any player (`ShellState.closePlayer`),
   freeze the watch cache sweep.
2. `Td.clearEverythingCached()` (today unused, `Td.kt:580-595`) so TDLib's database agrees the
   cache is empty, then `Td.restart(newPaths)` (B2.4). Delete the old `cache/` tree afterwards
   (`temp/` included; TDLib leaves partials there).
3. Create `<root>/TMPlayer/{cache,downloads,updates}` and the marker.
4. Move every file in the downloads index (B3) to the new `downloads/`: same `FileStore`
   means `Files.move(ATOMIC_MOVE)` per file, instant; a different store means copy to
   `<name>.part` with a chunked `FileChannel.transferTo` loop (64 MiB chunks, so progress is
   reported and the historical 2 GiB boundary bug never applies), `force(true)`, size check,
   `Files.move(part, final, ATOMIC_MOVE)`, then delete the source. The index is updated per file
   as it lands, so a crash mid way leaves a consistent index (some entries moved, some not) and
   the operation can be resumed. Progress goes through the notifier (B7): "Moving downloads,
   14 GB of 38 GB".
5. Write the new root to `DesktopPrefs` (`storage_root`), and `updates` dir follows.
6. Remove the empty old `downloads` folder if TMPlayer created it; never remove anything else.
7. Unfreeze, `resumeAll`, toast "Storage moved to E:\TMPlayer".

Failure mid move: keep both roots in prefs (`storage_root_pending`), finish on next launch
before TDLib starts, and tell the user what is left. A Windows file locked by another program
("The process cannot access the file") is reported per file and retried on next launch, never
treated as fatal.

A **Reset to default folders** button reverses it with the same machinery.

### B2.4 `Td.restart(paths)`

`Td.kt` today: `paths` is a `lateinit val` set once (113-117). It becomes a `@Volatile var`.
New `suspend fun Td.restart(newPaths: Paths)`: sets `paths = newPaths`, sends a new reducer action
`AuthAction.Close` whose handling calls `td.close()` and completes `closedSignal` the way
`RecreateClient` does (`Td.kt:203`), so `clientLoop` builds a fresh client that reads the new
`paths` in `sendParameters`. The auth state passes through `WaitTdlibParameters` to `Ready`
again (the session is in the unchanged database directory); `MainActivity` and the desktop shell
already tolerate a `Connecting` state. `AuthReducerTest` gains the `Close` case. Callers must
pause players and the queue first (B2.3 step 1); `OfflineDownloads.restore` is not re-run.

## B3. Downloads become files TMPlayer owns

### B3.1 The index

`dl_<chatId>_<messageId>` records keep their key and grow a **path** field (`ResumeRecord` gains
`localPath: String?`; the unit separator encoding at `ResumeRecord.kt:51-65` appends it as a
seventh field, old records decode with `null`). A `dl_` record with a path is a download in
TMPlayer's folder. A `dl_` record without a path is a legacy record pointing at TDLib's cache
(B3.4 migrates it). The 200 cap (`MAX_HISTORY`) is **removed for downloads**: the index is the
truth about files on disk and must not forget any. It stays for the `meta_`/`resume_` history.

Validation on load (what Telegram Desktop does): a record whose path is missing or whose file
size differs from the recorded size is shown as "File missing" with a Remove action, never
silently dropped, so the user learns a cleanup tool or a disconnected drive took it.

File naming inside `downloads/`: `<cleanFileName(title or fileName)>` with the original
extension, truncated to 200 bytes of UTF-8 (255 is the limit on NTFS, exFAT and ext4; the margin
leaves room for the suffix), Windows reserved names (`CON`, `NUL`, `COM1`...) and trailing dots
or spaces removed, a ` (2)` suffix on collision. Flat, one folder; a per chat subfolder is
Decision E4. TDLib's `getSuggestedFileName(fileId, directory)` is used when the title is empty.

### B3.2 Finishing a download: the move step

`DownloadService.fetch` (Android, 530-571) and `DesktopDownloadRunner.fetch` (desktop, 208-213)
gain a final stage after TDLib reports complete, `Stage.Moving`, added to
`OfflineDownloads.Stage` (`OfflineDownloads.kt:27-55`):

1. Resolve `Td.localFilePath(fileId)` (complete files only).
2. Wait while any player has the file open (`WatchCache.isPlaying` / `ActiveStreams.openIds()`);
   on Windows a move of an open file fails, and on all platforms a player streaming the file would
   lose it. If a player is on it, mark the row "Finishes when playback stops" and move on
   `stoppedPlaying`.
3. Move into `downloads/` (same store rename, else copy with progress, as in B2.3 step 4). Internal
   storage on Android is one filesystem, so there it is always a rename.
4. `noteDownload(item, chatTitle, path)`, `forgetCachedVideo(chatId, messageId)` if a `wc_`
   record existed, then **tell TDLib** with `Td.deleteFile(fileId)`: the file is gone from the
   cache path anyway, and this keeps TDLib's database and storage statistics honest instead of
   waiting for the lazy check. TDLib will re-download if anything ever asks it to stream that
   file id again, which nothing will (B3.5).
5. Completion notification (B7).

Shared code: a `DownloadFiles` object in `:core` (`moveIntoDownloads(src: File, downloadsDir,
name, onProgress)`, `safeName(...)`, `sameStore(a, b)`), unit tested with temp directories
across two `FileStore`s where the CI runner has them (tmpfs and the disk on Linux).

### B3.3 Adopting a folder

When a chosen root already holds `TMPlayer/downloads` (B2.3 validation step 4), or when the user
asks "Scan the downloads folder" from Settings, every file there that is not in the index is
offered as a row "Not in TMPlayer's list" with "Keep" (adds an index entry with chatId 0; it
plays, cannot be re-sourced from Telegram) or "Delete". This also covers files the user drops
into the folder by hand. Not in the first phase; see F.

### B3.4 Migrating existing downloads

First launch after the upgrade, after sign in, once (`SettingsStore` key `downloads_migrated`):
every legacy `dl_` record (no path) whose TDLib file is complete is moved into `downloads/` by
the B3.2 machinery, with one aggregate notification "Moving 12 downloads into TMPlayer's
Downloads folder" and a toast at the end naming the folder. Records whose file is partial stay
legacy and are shown as "Part downloaded" with Resume (the queue resumes them, then moves). On the
desktop the default downloads folder is the OS Downloads folder (B2.2), so this migration is a
cross store copy for anyone whose home and cache are on different volumes; it reports progress
and survives a restart like B2.3.

### B3.5 Playing a download

Everything that resolves "is this video local" consults the index **before** TDLib:

- `MediaItem.onDevice` (set in `MediaMapper`) becomes three valued: `Downloaded` (index has a
  path and the file is there), `Cached` (TDLib has a complete local file), `Remote`.
- Android: `MainActivity.play()` and `PlayerActivity.onCreate` (440-456) check the index first and
  hand Media3 a `file://` URI (`ProgressiveMediaSource` over `FileDataSource`), skipping
  `RoomOnDisk`, `WatchCache.claim` and `TdDataSource` entirely. Resume positions are keyed by
  chat and message, unchanged. `ExternalPlayer` and `ShareMedia` take the index path.
- Desktop: `TelegramPlayerMedia.open()` (`PlayerMedia.kt:95-121`) returns `UriMediaData(path)`
  from the index before touching TDLib. Downloads page "Play" does the same.
- Next episode and autoplay go through `playEpisode`, which goes through the same resolver.

### B3.6 Deleting a download

Deletes the file at the index path and the record. `Td.deleteFile` is not involved (TDLib no
longer has it). Android `DownloadsScreen` keeps its confirmations and multi select; the desktop
Downloads page gains a confirmation and multi select (Part D).

## B4. Downloading a video that is already cached, wholly or in part

The queue (`OfflineDownloads` plus the two runners) already handles a partially cached file: it
resumes TDLib's download and completes it. What changes is who is allowed to ask:

- The Android grid stops filtering cached files out of `batch.fits` (`MediaGridScreen.kt:385-387`,
  411): a file that TDLib has complete is sent to the runner, which skips straight to the B3.2
  move. `CacheShelf.planBatch` keeps treating "already here" as free (no disk needed), since a
  same store move costs nothing; across stores it costs the file's size, which `DiskInfo` of the
  downloads volume (new `Paths.downloadsDisk()`) answers.
- The desktop menu stops hiding "Download" when `item.onDevice` (`MediaGrid.kt:475-481`) and
  the player's `download()` stops answering "Already on this computer" (`PlayerMedia.kt:173`).
- A video the viewer is **watching** can be kept from the player menu on every target: Android
  phone overflow, TV row (Part D adds the TV overflow), desktop menu. The request enters the
  queue; the row says "Finishes when playback stops" if the move has to wait (B3.2 step 2); the
  move happens on exit from the player.

The user's words were "if it's half cached maybe move it to downloads after completion": this
is exactly the queue path, now reachable from everywhere a cached video is shown.

## B5. One action: Save to Downloads

Wherever a **cached** video appears, the primary action is *Save to Downloads*:

| Surface | Today | After |
|---|---|---|
| Android tile menu (`MediaGridScreen.kt:1930-1947`) | dead "Downloaded" when cached | "Save to Downloads" when cached, "Download" when remote, "Downloading…" when queued, "In Downloads" plus "Remove from Downloads" when indexed |
| Android selection bar | "Download selected" | same label; cached ones move, remote ones queue |
| Desktop right click menu (`MediaGrid.kt:371-400`) | "Download" hidden when on device | same four states |
| Desktop player menu, Android phone overflow, TV row overflow | "Download" / "Already on this computer" | "Save to Downloads" |
| Settings, Storage card | "Clear cache" with a count | a **Cached videos** list, each row title, size, "Save to Downloads", "Delete"; then "Clear cache" |
| Downloads screen (both) | downloads only | third section **Cached from playback** with the same rows and the one line "Played recently. The next video you play replaces it." |

The tile badge becomes two words, not one: **Downloaded** (index) and **Cached** (TDLib only),
replacing "Saved" (`MediaGridScreen.kt:1703`) and "On this computer" (`MediaGrid.kt:312`).

## B6. Wording, settled

Glossary used in every string, comment and KDoc from now on:

- **Cache**: what TDLib holds so a video can stream and play again without downloading. Lives in
  `cache/`. TMPlayer may delete it at any time by rule. User facing adjective: "cached".
- **Downloads**: videos the user chose to keep. Live in `downloads/`. Deleted only by the user.
  User facing: "downloaded", "in Downloads".
- **Storage location**: the folder that holds both.
- Never "saved", "on this device", "on this computer", "offline", "kept" as nouns for either.

Settings, Storage group, Android and desktop alike (desktop gains the card it lacks):

```
Storage
  [card] 38 GB in Downloads  ·  3.1 GB cached  ·  0.4 GB pictures and previews
         120 GB free of 500 GB on E:          (free/total of the storage root's volume)
  Storage location          E:\TMPlayer                       [Change]  [Reset]   (desktop; Android: Internal / SD card chooser)
  Cache limit               10 GB  (desktop only, B8)          [stepper 2 .. 100 GB]
  Cached videos             2 videos, 3.1 GB                   > list with Save to Downloads / Delete
  Clear cache               "Deletes every cached video. Downloads are not touched."
  Clear pictures and previews
  Clear everything except downloads
```

Downloads screen, both platforms: title **Downloads**; sections **Downloading** (was "Ongoing" /
"In progress"), **Downloaded** (was "Completed" / "On this computer"), **Cached from playback**.
Empty states: "Nothing downloading. Choose Download on a video and it queues here." /
"Nothing downloaded yet. Downloads are kept in <folder> until you delete them." /
"Nothing cached. Playing a video keeps it here until the next one."

Dialogs keep their current shape and change nouns only ("Delete this download?", "Clear the
cache?"). The sign out copy on both platforms already says downloads go with it; with downloads
outside TDLib that is now a **choice**: the sign out dialog gains a checkbox "Also delete my
downloads (38 GB)", default off (Decision E5).

`OfflineDownloads.REFUSED` moves its Android wording into `AndroidDownloadRunner`.

## B7. Progress and completion notifications on every target

One interface in `:core`, `platform/TransferNotifier.kt`:

```kotlin
interface TransferNotifier {
    fun begin(id: Long, kind: Kind, title: String)                 // Kind: Download, MoveToDownloads, Relocate, Migrate
    fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?)
    fun complete(id: Long, title: String, body: String, open: OpenTarget?)   // OpenTarget: Downloads screen, a file, a folder
    fun fail(id: Long, title: String, reason: String, retryable: Boolean)
    fun cancel(id: Long)
    val capabilities: Set<Capability>                               // ProgressBar, InPlaceUpdate, Actions, DockProgress
}
```

`:core` owns the policy, so each implementation is thin: progress is coalesced to one emission
per second per id and only when the integer percentage or the formatted size changed (Android
drops updates above 5 per second per package; Windows `Update` costs a process round trip; D-Bus
politeness); the aggregate dock or taskbar value is the byte weighted mean of active transfers
(Chrome's rule); `complete` is always a **new** notification, never an update of the progress
one, so it pops even if the user dismissed the progress (Microsoft's guidance; on Linux
`replaces_id = 0` for the completion).

Implementations:

- **Android** (`app`): the existing `DownloadService` notifications (channel `downloads`, summary
  4200, per video 4201 plus id) are the implementation; they gain the `Moving` stage text
  ("Moving into Downloads"), the `Migrate`/`Relocate` kinds (one aggregate notification), and a
  completion that opens the Downloads screen (already `OPEN_DOWNLOADS`). Android 15 caps
  `dataSync` foreground services at 6 h per day; the plan notes the user initiated data transfer
  job API (34+) as the migration path and does **not** take it now (Decision E6). On **TV** the
  shade is not shown to third party apps, so the same service posts the mandatory notification
  while the UI layer shows the rail badge count (exists) and an in app toast on completion
  ("Downloaded: <title>", `rememberToast`), and the Downloads screen rows carry the progress.
- **Desktop Linux** (`desktop/os/LinuxNotifications.kt`, modelled on `LinuxKeepAwake`): dbus-java
  5.2.2 is already there. `org.freedesktop.Notifications.Notify` with `app_name = "TMPlayer"`,
  `desktop-entry = io.github.dracu_lah.TMPlayer`, `category = transfer` / `transfer.complete` /
  `transfer.error`, `urgency` low for progress, hint `value` (int32 percent; dunst and mako draw a
  bar, GNOME and KDE ignore it and show the body text "42 %, 1.2 GB of 2.9 GB" which is updated in
  place through `replaces_id`), `GetCapabilities` to decide whether to add an "Open folder"
  action and listen for `ActionInvoked`. Fallback ladder: no session bus, then `notify-send -r -p
  -h int:value:N` if on PATH, then in app toast. Plus the Unity LauncherEntry `Update` signal
  (`com.canonical.Unity.LauncherEntry`, `progress` and `progress-visible`) exported from
  `/com/canonical/unity/launcherentry/1`, which KDE's task manager and GNOME's Dash to Dock draw as
  a bar on the dock icon; nothing reaches sway, which has no dock. Both Flatpak manifests add
  `--talk-name=org.freedesktop.Notifications`; the portal `org.freedesktop.portal.Notification` has
  no progress key and is the in sandbox fallback only.
- **Desktop Windows** (`desktop/os/WindowsNotifications.kt`): taskbar progress through
  `java.awt.Taskbar.getTaskbar().setWindowProgressValue(window, pct)` and
  `setWindowProgressState(window, OFF)` at the end; this is in the JDK, needs no JNA, and works
  from the portable zip. Toasts through WinRT `ToastNotificationManager` driven from one hidden
  long lived `powershell.exe -NoProfile -NonInteractive -Command -` process fed over stdin: the
  `<progress>` element bound to `NotificationData`, `ToastNotifier.Update` with an increasing
  `SequenceNumber`, and a fresh toast for completion. Toasts from an unpackaged app need an
  AppUserModelID; jpackage's MSI shortcut does not carry one and the portable zip has no
  shortcut, so at startup TMPlayer writes
  `HKCU\Software\Classes\AppUserModelId\TMPlayer.Desktop` (`DisplayName`, `IconUri`) with
  `Advapi32Util` (jna-platform) and always creates the notifier with that id. This is what the
  Windows Community Toolkit does. If it fails on a real Windows 10 or 11 box, the fallbacks in
  order are the Nucleus library (`dev.nucleusframework:nucleus.notification-windows:2.5.18`) and
  then the Compose `Tray` balloon (which needs a tray icon; TMPlayer has none today, see Part D).
  Taskbar progress alone is the floor and is always on.
- **Desktop macOS** (not shipped): `Taskbar.setProgressValue` on the dock icon and
  `osascript -e 'display notification'` for completion.

In app, on every target, the Downloads side bar or rail item carries the in flight count badge
(Android has it; desktop `Sidebar`/`Rail` in `ui/Shell.kt:212-255` gain it from
`OfflineDownloads.active`), and a completion toast is always shown when the window has focus, so
the "done" signal is never lost where the OS gives nothing.

## B8. Cache rule on the desktop: a cap, not one video

The desktop copied the phone's one video rule for want of a design (plan 2026-10-03 had asked
for a 10 GB cap). With downloads outside the cache, the cache's only job is "play again without
re-downloading", and a computer has disk. The desktop gets **Cache limit** (default 10 GB,
range 2 to 100 GB, `DesktopPrefs.cache_limit_bytes`) and `DesktopWatchCache` evicts least
recently played first until under the cap, sparing the playing file and the queue, after each
claim and at the housekeeping sweep. `Td.trimStorage()` runs on the desktop too (today never),
with `optimizeStorage(size = cap, ttl = 30 d, immunityDelay = 10 min, fileTypes = videos and
documents)` as the second line of defence, since TDLib's own GC is otherwise never invoked. The
phone and TV keep the one video rule (user decision of 2026-08-12) and `RoomOnDisk`. Decision E1
asks whether the phone should also get a cap.

A pre play room check lands on the desktop at the same time (`PlayerMedia.open()` calls a
desktop `RoomOnDisk` over `CacheShelf.plan` with the cap in place of "one video"), with the
same "Not enough space" sheet the phone has.

## B9. Housekeeping found by the audit, folded in

- `WatchCache.strays` takes `Paths.filesDir` instead of the hard coded `tdlib-files`; the four
  Android hard codings collapse to one (`AndroidPaths`).
- `DownloadsScreen.share` and `MediaGrid.removeDownload` resolve `Td.currentFileId` first, or
  stop needing TDLib at all once the index has a path.
- The desktop runner gets `resourceAndRequeue` (stale ids after restart), the `free == 0`
  check, and a `Connectivity` implementation (`NetworkInterface` up/down plus a TDLib
  connection state probe) so `Offline` is produced instead of a timeout failure.
- The two watch cache rule sets become one `WatchCacheRules` in `:core` over a `TdFiles` seam
  (the desktop already has `TdFiles`), with Android and desktop providing the file walker and the
  "playing" set. `DesktopWatchCacheTest` becomes the shared test.
- Settings "Clear cache" subtitle counts and names from the same list.
- `INSTALL.md:126-128` ("caps that at 1 GB ... Change the cap") and `docs/FEATURES.md:45-49`
  ("A setting turns that into a question first") describe things that do not exist; they are
  rewritten to describe B5 to B8.

---

# Part C: the update button and popup

## C0. What exists today (audit)

Android: `Updates` in `:core` (`data/Updates.kt`) fetches `/repos/dracu-lah/TMPlayer/releases/latest`
with `org.json`, picks an APK by ABI, compares versions numerically (suffixes dropped), and
exposes `UpdateState`. `MainActivity.kt:246-252` runs one quiet check per process **after**
`AuthState.Ready`, again on reconnect (316-323). The drawer (`ui/browse/TouchNav.kt:213-228`) and
the TV rail (`ui/browse/BrowseScreen.kt:1018-1027`) already show an **amber "Update" item with
the version as its badge** whenever the state is `Available`; the TV row fills amber on focus
(`RailItem accent = Caution`), the phone tints only the icon. Tapping opens `UpdateDialog`
(download, then Android's installer; TV included, through the per app "install unknown apps"
grant). `dismiss()` keeps `Available`, so the item stays until installed. No persistence, no
throttle, no popup. The metered policy is not applied to the APK download.

Desktop: `DesktopUpdates` is a second implementation (regex parse, full semver compare, OS
package filter), run from `ui/Shell.kt:135` after sign in, at most once per 24 h
(`DesktopPrefs.last_update_check`), behind "Tell me when a new version is out". The only surface
is the corner `UpdateNotice` card, hidden while playing, and the Settings "Updates" group.
`dismiss()` writes `dismissed_release` and **nulls `available`**, so a dismissed version never
shows again on quiet checks. No side bar item, no amber. `SelfUpdate` installs MSI, portable
zip, AppImage, deb and rpm with SHA-256 verification; Flatpak, tarball, macOS and dev runs open
the release page.

Neither checks before sign in. The release workflow publishes the asset set listed at
`release.yml:277-288` with `SHA256SUMS-<v>.txt` and `make_latest: true`.

## C1. One feed, one parser, one comparison

**Feed.** The release job writes `site/latest.json` and the site deploys it, so clients fetch
`https://<site>/latest.json` (CDN, no quota) and fall back to the GitHub API only when the site
is unreachable. Reason: the API's unauthenticated limit is 60 requests per hour **per IP**, shared
by every tool behind the same NAT, and an unauthenticated conditional request that gets a 304
still counts (verified live during research), so ETags do not help an app without a token.
electron-updater, Sparkle, Tauri, Velopack and NewPipe all moved to a static manifest for this
reason. Schema (version 1):

```json
{
  "schema": 1,
  "version": "1.20.0",
  "versionCode": 12000,
  "published": "2026-10-11T08:00:00Z",
  "releaseUrl": "https://github.com/dracu-lah/TMPlayer/releases/tag/v1.20.0",
  "notes": "Two or three plain sentences for the popup",
  "assets": {
    "android-universal":    { "url": "...", "sha256": "...", "size": 0 },
    "android-arm64-v8a":    { "url": "...", "sha256": "...", "size": 0 },
    "android-armeabi-v7a":  { "url": "...", "sha256": "...", "size": 0 },
    "windows-x64-msi":      { "url": "...", "sha256": "...", "size": 0 },
    "windows-x64-portable": { "url": "...", "sha256": "...", "size": 0 },
    "linux-x64-appimage":   { "url": "...", "sha256": "...", "size": 0 },
    "linux-x64-deb":        { "url": "...", "sha256": "...", "size": 0 },
    "linux-x64-rpm":        { "url": "...", "sha256": "...", "size": 0 },
    "linux-x64-flatpak":    { "url": "...", "sha256": "...", "size": 0 },
    "linux-x64-tarball":    { "url": "...", "sha256": "...", "size": 0 }
  }
}
```

`sha256` lets `SelfUpdate` verify without fetching `SHA256SUMS`; `notes` feeds the popup. The
release job computes it from the asset list it already checks (`release.yml:277-288`) and the
checksum file (335). Served with `Cache-Control: public, max-age=300`.

**Parser and comparison.** `:core` `data/Updates.kt` becomes the single `UpdateFeed`: parse the
JSON above (and the GitHub shape as fallback), `compare` as `DesktopUpdates.compare` does today
(semver with pre release ordering, which core's `isNewer` lacks), asset selection per target
(`android-*` by ABI with universal first; desktop by `SelfUpdate.InstallKind`). `DesktopUpdates`
shrinks to the desktop's install kind and prefs. `UpdatesTest` and `DesktopUpdatesTest` merge.

**Shared state** `UpdateState` grows `Available(release, seenAt)` plus two persisted settings in
`SettingsStore` (shared by both platforms): `update_last_check` (epoch ms),
`update_skipped_version`, `update_snoozed_until`. `DesktopPrefs` loses `last_update_check` and
`dismissed_release` (migrated once).

## C2. When the check runs

Same on every target, in a `UpdateScheduler` in `:core` driven by the app's lifecycle:

1. **On every launch, including the first**, 10 s after the first frame is shown (so a cold
   start is never slowed; VS Code and Firefox use 30 s, Telegram checks immediately when due;
   10 s is enough to be off the start path), **regardless of sign in**: the feed is a few KB and
   a fresh install from GitHub may already be behind. The user asked for this explicitly, so
   Sparkle's "skip the first launch" rule is not followed.
2. Not more than once per **6 h** by `update_last_check` (Telegram uses 8 to 16 h, Firefox 6 h,
   Sparkle 24 h). A failed check does not write the timestamp, so it is retried next launch.
3. While running, a timer repeats the check every 6 h (today a TV left on for days never
   rechecks).
4. The Settings "Check for updates" button ignores the throttle and the skip list.
5. Never blocks startup; failures are logged, not shown, unless the check was manual.
6. A check is fine on metered networks; the APK **download** honours `wifi_only_downloads` and
   the metered warning, which today it does not.

Android `MainActivity.kt:250-252` moves from "after Ready" to the scheduler; the reconnect check
stays. Desktop `Shell.kt:135` moves out of `Browse` into `Main.kt` so it runs on the sign in screen
too. The desktop toggle "Tell me when a new version is out" stays and gains an Android twin in
Settings, Version (Decision E7 on default).

## C3. The side bar item, on all three

**Behaviour.** When `UpdateState.Available` and the version is not skipped, an item appears at
the bottom cluster of the navigation (above Downloads, where Android already puts it) on the
phone drawer, the TV rail, the desktop side bar and the desktop narrow rail. It stays until the
installed version is at least that version or the user chooses *Skip this version*. "Remind me
later" from the popup does not remove it. Tapping it opens the popup (C4) in its current state
(available, downloading, ready to restart, failed).

**Label.** One string, "Update 1.20.0", with the version in the badge slot on Android (today's
layout), and the same `badge` slot on the desktop `NavigationDrawerItem`. The narrow desktop
rail truncates labels at the first space (`Shell.kt:251`), so there the item shows the icon with
a `BadgedBox` carrying the version and the label "Update".

**Colour.** The sanctioned yellow is `Caution` (amber `0xFFF5A524`, `ui/.../theme/Palette.kt:29-30`,
whose comment already says "A newer version being out is the whole use"), `Tone.caution` in
light themes (`0xFF8A5300`, because amber on white fails contrast at under 2:1), and
`Tone.readableOn` for text on a filled amber surface. Amber `F5A524` on the dark surface clears
WCAG AA at over 9:1. The desktop item uses `NavigationDrawerItemDefaults.colors(unselectedIconColor
= Tone.caution, unselectedTextColor = Tone.caution, unselectedBadgeColor = Tone.caution)` and the
TV's filled amber focus state is kept. The phone drawer today tints only the icon; it changes to
icon, label and badge in amber, matching the other two.

**Desktop plumbing.** `DesktopExtras.updates.available` is replaced by the shared
`UpdateState`; `Sidebar` and `Rail` (`Shell.kt:212-255`) add the item after the `Destination`
loop (not as a `Destination`, which would need a page in the exhaustive `when` at 152-158). The
corner `UpdateNotice` is **removed**; its download progress and "Restart now" state move into the
popup. `BrowseRenderTest` renders the side bar with the item.

## C4. The popup

Shown once per version, on the first launch where the item appears, 2 s after the item (so the
user sees where it lives), never while a video is playing (deferred to player close), never on
the sign in screen (deferred to the shell), never twice for the same version unless the user taps
the item. Material 3 `AlertDialog` on phone and desktop, the existing TV panel style
(`ui/update/UpdateDialog.kt:114-230`) on TV with D-pad focus on *Update now*.

Phrasing, by state and install kind (the version numbers are examples):

> **TMPlayer 1.20.0 is out**
> You have 1.19.1. <notes from the feed, two or three sentences>
> *Then one line by kind:*
> Android: "The update is 48 MB from GitHub. Android asks you to confirm before it installs."
> TV, installs blocked: "This TV blocks installs from TMPlayer. Allow them in Settings, Apps,
> Security and restrictions, then come back here." with a button **Open that setting**.
> MSI, portable, AppImage, deb, rpm: "The update downloads from GitHub, is checked, and
> TMPlayer restarts when it is done." (deb and rpm add "Your system will ask for your password.")
> Flatpak: "Flatpak updates come through your software centre or `flatpak update`."
> Tarball, macOS, dev run: "Download it from the release page and replace the old files."
>
> [Update now]   [Remind me later]   [Skip this version]

Button semantics:

- **Update now**: Android `downloadAndInstall` (honouring Wi-Fi only; on mobile data the button
  reads "Update now (48 MB on mobile data)"); desktop `SelfUpdate.update(release)`, the dialog
  shows "Downloading 48 MB", "Checking the download", "Installing", then "Ready. TMPlayer restarts
  to finish." with **Restart now** and **Later** (Later keeps the side bar item reading "Restart to
  update"). Flatpak and tarball turn the button into **Open release page**.
- **Remind me later**: closes the popup, writes `update_snoozed_until = now + 24 h`; the popup
  returns on the first launch after that; the side bar item stays throughout.
- **Skip this version**: writes `update_skipped_version`, removes the side bar item and popup
  for this version only; a newer release shows again; Settings "Check for updates" always
  re-offers and says "1.20.0 is out (you skipped it)".

Failure text replaces the body and keeps the three buttons, with *Update now* reading **Try
again**: "Could not reach GitHub. Try again in a moment." / "The download was damaged and was not
installed." / "Android could not open the installer. Install it by hand from the release page."
Rate limiting wording from core (`Updates.kt:221-227`) is kept for the API fallback.

The Settings "Version" rows (Android `SettingsScreen.kt:746-771`, desktop `Settings.kt:180-190`)
keep "Check for updates" and show the amber "Update to 1.20.0" row when available; both open the
same popup.

## C5. Android install path

Keep `ACTION_VIEW` with the FileProvider for now; it works on phone and TV with the per app
grant. Note for later: `ACTION_INSTALL_PACKAGE` is deprecated since API 29 and `PackageInstaller`
sessions (`createSession`, `openWrite`, `commit`, `STATUS_PENDING_USER_ACTION`) are the forward
path, and on a TV without a browser the "release page" link needs a QR code pane (the QR code
renderer exists for sign in). Phase 3.

---

# Part D: feature parity

## D0. How the audit was read

The matrix (phone, TV, Linux, Windows, macOS) had three kinds of gaps: *missing* (not written
for that target), *hidden* (an explicit gate), *dead* (drawn and does nothing). Each was judged
"necessary" (the platform cannot do it), "unneeded" (makes no sense there) or "no" (could be
offered). Only "no" rows are scheduled. Gating primitives: `FormFactor.isTv` on Android,
`isTouch()` in shared UI (true on phone **and** desktop), `OsInfo.isLinux/isWindows/isMac` on the
desktop. There is no shipped macOS build; macOS cells describe what the code would do.

## D1. Dead or missing on the desktop, to add

| Item | Today | Plan |
|---|---|---|
| "Download the whole video first" | toggle stored, never read (`ui/Settings.kt:98-100`) | `TelegramPlayerMedia.open()` reads `downloadBeforePlayingNow()`; when on, a synchronous `downloadFile` with the player's "Downloading the whole video" state and cancel, as `PlayerActivity.fetchWholeFilm` does |
| Pin, mute, archive, mark read on chats | phone and TV hold menu only; `ChatListViewModel` already has the four calls (`BrowseViewModel.kt:344-362`) | right click menu and keyboard (P, M, A, R) on `ui/Chats.kt` rows |
| Multi select download, Select all | Android only | Ctrl+click and Shift+click selection in `MediaGrid`, a selection bar with "Download selected" over `CacheShelf.planBatch` |
| Open in another app | `OpenExternal.open(file)` exists, never wired to a video | tile menu and player menu for downloaded and cached complete files |
| Sponsored Report, open media variant | Android only; `reportSponsored` is shared | the desktop `SponsoredCard` gains Report with the options list |
| Storage card: free of total, split bar, legend | one used total | B6's card, over a desktop `StorageSplit` and `DesktopPaths.disk()` |
| Confirmations before Clear cache, Clear Continue watching, Clear favourites | immediate with a toast | the `TvConfirm` style dialog on desktop (`ui/Common.kt`) |
| Clear everything except downloads; size limit Reset; Forget last chat | missing | rows added to `ui/Settings.kt` |
| Offline and reconnecting banner, "Back online" refresh, offline play check | no `Connectivity` on desktop | B9's desktop `Connectivity`; `ConnectionStatus` composable is shared already |
| Not enough space before play | missing | B8 |
| Prune unplayable history on launch | Android `pruneBrokenHistory` only | call it from `Shell` after sign in |
| Multi select, Delete all, confirmation on Downloads page | missing | with B6's Downloads screen rework |
| Downloads count badge on side bar | missing | B7 |
| Intro and walkthrough | missing | not scheduled; the desktop sign in screen carries the two sentences the intro would (Decision E8) |
| Privacy and Lawful use links, the "talks only to Telegram and GitHub" footer | missing | rows in Settings, Help group, through `OpenExternal.browse` |
| System media session on Windows (SMTC) and macOS (Now Playing) | `NoMediaSession` (`os/MediaSession.kt:58-63`) | SMTC through WinRT `SystemMediaTransportControls` from the same PowerShell host B7 introduces is **not** viable (it needs a window handle in process); the realistic route is a small JNA COM binding of `ISystemMediaTransportControlsInterop`. Scheduled as Phase 4 with a spike first; macOS stays unshipped |
| Copy link | desktop only | Android tile menu and player overflow: clipboard plus toast |
| Tray icon | none | not scheduled: no tray on sway or stock GNOME, and nothing in TMPlayer needs one (Decision E9) |
| Update check toggle | desktop only | Android Settings gains it (C2) |

## D2. Hidden on Android TV, to add

| Item | Gate | Plan |
|---|---|---|
| Overflow menu in the player (Playback details, Start over, Speed menu, Save to Downloads, Open in another app) | `PlayerControls.kt:130` `if (!isTv) setUpPhone(root)`; the TV row has no "more" | a **More** button at the end of the TV row opening a `TvMenu` with those five entries; the long press OK shortcut to Open in another app stays |
| Remaining time toggle | `PlayerControls.kt:184-202` | OK on the time readout toggles it, like the phone's tap |
| Picture in picture | `PlayerActivity.kt:1990, 2011` `if (isTv) return` | Android TV 8+ supports PiP; the gate drops to `hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)` and the More menu gets the entry. Auto enter on Home stays phone only (Decision E10) |
| Remote key help | the "?" sheet is desktop only | a **Remote** entry in More listing the key table from `INSTALL.md:93-102` |
| Downmix to stereo | forced off on TV (`AudioDownmix.folds(isTv)`) | a Playback setting on TV and phone, default as today per form factor |
| Hide the controls after | phone only Player section | the one gesture setting that applies to TV and desktop; shown on all three (desktop hard codes 3 s, `PlayerScreen.kt:634`) |
| Country picker on phone login | TV types the dial code | fine as is: a TV keyboard makes the list slower than four digits. Stays hidden |

## D3. Missing on the Android phone, to add

Copy Telegram link (tile menu, overflow); load an external subtitle file through the SAF
document picker (`ACTION_OPEN_DOCUMENT`, `.srt .ass .ssa .vtt .sub`), fed to Media3 as a
`SubtitleConfiguration` the way `SubtitleDrop` feeds mpv; "Play from start" on any tile, not
only in Continue; the update check toggle (C2).

## D4. Hides that stay, with the reason

Voice search on desktop (no portable speech API); Wi-Fi only and metered policy on desktop and
TV (no metered signal on the JVM, TVs are wired or on home Wi-Fi); gestures, haptics, orientation,
lock, auto PiP on desktop and TV (no touch, no sensors); mouse wheel, volume slider, fullscreen,
mini player, always on top, software decoding on Android (window and mouse concepts, or Media3
decides decoding); dynamic wallpaper colour on TV and desktop (Android 12 phone API); share sheet
on desktop (Show in folder is the equivalent); background download service on desktop (the
process is the service); frame rate matching outside TV (mpv has display sync); frame step on
Android (Media3 has no API); country picker on TV (above); Exit on double Back on desktop.

## D5. Documentation claims to correct alongside

`INSTALL.md:126-128` (cap wording), `docs/FEATURES.md:45-49` and `IntroScreen.kt:156-160` ("It
always asks you first": it does not, and B5 to B8 make the actual rule describable), `INSTALL.md:116`
(modes are Fit, Crop, Stretch), `INSTALL.md:84-86` (TV also offers phone number login),
`INSTALL.md:429-430` (sway variable is set automatically now), `site/download/index.html:141` (desktop
self updates since 1.19.0). Also `INSTALL.md:257-274`'s update table gains the popup and side bar
item.

---

# Part E: decisions that are the user's

Each has a recommendation; the plan proceeds on the recommendation unless told otherwise.

- **E1. Phone cache rule.** Keep one video on phone and TV (recommended, the 2026-08-12 decision
  stands), or give the phone a cap like the desktop. The desktop gets the cap either way (B8).
- **E2. Cache on a location change.** Cleared, not copied (recommended: TDLib would not know
  copied files and the sweep would delete them). The dialog says so and names the size.
- **E3. Folder picker library.** FileKit (recommended: native dialogs on all three OSes and the
  portal under Flatpak for free) versus hand rolled JNA `IFileOpenDialog`, `NSOpenPanel` and a
  portal client over the dbus-java already present (no new dependency, roughly 400 lines, a
  Mac to test on that we do not have).
- **E4. Downloads folder layout.** Flat (recommended: Telegram Desktop's choice, simplest to
  scan and to show in a file manager) or `downloads/<chat title>/<file>`.
- **E5. Sign out and downloads.** Downloads survive sign out by default, with a checkbox to
  delete them (recommended: they are the user's files now) or are deleted as today.
- **E6. Android 15 six hour foreground service budget.** Leave `dataSync` as is (recommended for
  this release; a 6 h cap only bites on very slow links) or move the service onto user initiated
  data transfer jobs on API 34+.
- **E7. Update check default on Android.** On (recommended; today it is always on with no
  switch) with the new toggle defaulting to on.
- **E8. Desktop onboarding.** Two sentences on the sign in screen (recommended) or a port of
  `IntroScreen` and `OverviewScreen`.
- **E9. Tray icon.** Not added (recommended). A tray would enable "close to tray, keep
  downloading" later; nothing asked for it.
- **E10. PiP on TV.** Button and menu entry only (recommended) or also auto enter on Home.
- **E11. Default desktop downloads folder.** `<OS Downloads>/TMPlayer` (recommended, Telegram
  Desktop's precedent, visible to the user) or `<data dir>/TMPlayer/downloads` (hidden, but one
  layout for default and custom).
- **E12. Feed hosting.** `latest.json` on the Vercel site with the API as fallback (recommended)
  or the GitHub API alone with backoff (simpler, shared 60 per hour per IP).

---

# Part F: delivery and status

Status on 2026-10-04 (release 1.20.0): everything below is implemented except the unticked
items. The Android storage volume chooser was left out because FileProvider cannot serve files on
removable volumes, so Share and Open in another app would break there. The TV QR code was not
started. SMTC landed after 1.20.0 (see its line below), untried on real Windows. Nothing was run
on Windows hardware or on the phone and TV for this release. The Windows CI step covers the
fullscreen round trip.

Phases are ordered so each ships on its own. Effort is for one developer with the devices the
memory lists (a Linux desk, the POCO phone, the TV stick; no Windows machine).

## Phase 1: Windows fullscreen and the update button (small, ships first)

- [x] A2: `NativeFullscreen` Windows branch rewritten (un-maximize on entry, `wasMaximized`,
      `pendingMaximize`, ex styles, `maximizedBounds`, `SetWindowPlacement` on exit)
- [x] A2: `DevPlayerMain --maximized`, richer `dev: back` print
- [x] A3: CI step on `windows-latest` asserting the round trip; INSTALL.md manual checklist
- [x] C1: `latest.json` written by `release.yml`, deployed with the site; `UpdateFeed` in `:core`
      replaces both parsers; one `compare`; `DesktopUpdates` shrinks; tests merged
- [x] C2: `UpdateScheduler`: launch plus 10 s, 6 h throttle in `SettingsStore`, repeat timer,
      pre sign in on both platforms; Android toggle
- [x] C3: desktop side bar and rail item in amber; phone drawer item amber on label and badge;
      `UpdateNotice` removed; `BrowseRenderTest` updated
- [x] C4: the popup on phone, TV and desktop with the three buttons and the per kind lines;
      snooze and skip persisted; download progress and Restart now inside it
- [x] C2: APK download honours Wi-Fi only and the metered warning
- [x] Docs: INSTALL.md update table, site download page line

## Phase 2: downloads leave the cache (the core of Part B)

- [x] B3.1: `ResumeRecord.localPath`, `dl_` cap removed, missing file rows
- [x] B3.2: `Stage.Moving`, `DownloadFiles` in `:core` (move, safe name, same store test,
      progress), both runners move on completion and call `deleteFile`
- [x] B2.2: `Paths.downloadsDir` (Android `filesDir/downloads`, desktop OS Downloads folder
      through Known Folder, XDG user dirs, `~/Downloads`); `update_paths.xml` entry; Flatpak
      `xdg-download:create`; `tdlib-files` renamed `cache` with a one time rename on upgrade
- [x] B3.4: one time migration of legacy `dl_` records with an aggregate notification
- [x] B3.5: index first resolution in `MainActivity.play`, `PlayerActivity`, `PlayerMedia`,
      `ExternalPlayer`, `ShareMedia`, Downloads pages; `onDevice` three valued
- [x] B3.6: deletion through the index; desktop `removeDownload` fixed
- [x] B4: cached files reach the runner from the grid, the desktop menu and the player; "Finishes
      when playback stops"
- [x] B5: Save to Downloads everywhere in the table; badges "Downloaded" and "Cached"
- [x] B6: wording pass over Settings, Downloads screens, dialogs, badges, menus; sign out
      checkbox (E5)
- [x] B7: `TransferNotifier` in `:core`, Android implementation over the existing service,
      Linux D-Bus plus LauncherEntry, Windows taskbar progress plus PowerShell toast with
      AUMID registration, in app toast and badge fallback; Flatpak `--talk-name`
- [x] B9: strays over `Paths.filesDir`; `currentFileId` fixes; desktop runner resourcing,
      `free == 0`, `Connectivity`; shared `WatchCacheRules`; `REFUSED` wording moved
- [x] Tests: `DownloadFilesTest` (same and cross store, name sanitising, collision), migration
      test over a fake `TdFiles`, `ResumeRecord` round trip with and without path, runner stage
      tests, `UpdateFeedTest`, a render test of the Downloads page's three sections

## Phase 3: the storage location and the desktop cache cap

- [x] B2.3: FileKit dependency (E3), Storage location row, validation, confirm dialog
- [x] B2.4: `Td.restart(paths)`, `AuthAction.Close`, `AuthReducerTest`
- [x] B2.3: `StorageRelocation` (clear cache, restart TDLib, create folders, move downloads with
      progress and a resumable pending state, Reset to default)
- [x] B8: Cache limit setting, LRU eviction under the cap, `Td.trimStorage` on desktop, pre play
      room check with the "Not enough space" sheet
- [x] B3.3: adopt an existing `TMPlayer/downloads`; Scan the downloads folder
- [x] D1: desktop Storage card, confirmations, Clear everything except downloads, Reset, Forget
      last chat, Privacy and Lawful use rows, offline banner, prune history, Downloads page multi
      select and Delete all, "Download the whole video first" wired
- [x] C5: TV release page as a QR code; note on `PackageInstaller`
      (update popup's Release page button: QR pane on TV, browser on phone; `PackageInstaller` stays a note)
- [x] Docs: INSTALL.md storage section (where files live per OS, how to move them, what the
      cache is), FEATURES.md, site features page

## Phase 4: parity remainder

- [x] D1: pin, mute, archive, mark read on desktop chats; multi select download; Open in
      another app; sponsored Report
- [x] D2: TV More menu (Playback details, Start over, Speed, Save to Downloads, Open in another
      app, Remote keys), remaining time toggle, PiP button (E10), downmix setting, controls
      timeout on TV and desktop
- [x] D3: Copy link, external subtitles, Play from start on the phone
- [ ] B2.2: Android storage volume chooser (internal, SD card, USB) with the same relocation flow
- [x] D1: SMTC spike (JNA COM `ISystemMediaTransportControlsInterop`), then SMTC. Done as
      `os/Smtc.kt`: `GetForWindow` on the app's HWND, IsEnabled, Play, Pause, Stop, Next and
      Previous, PlaybackStatus, a hand written ButtonPressed handler, DisplayUpdater with
      Type=Video and the title; seeking from the flyout is not wired. Any failure falls back to
      `NoMediaSession` and logs once. GUIDs and vtable slots checked against the SDK IDL (Wine's
      copy), the handler IID against the WinRT derivation in a unit test. **Not run on real
      Windows**: it compiles and its pure parts are tested on Linux, and CI's Windows job runs
      `SmtcWindowsSmokeTest` on a hidden window and reports "live" or "fell back" as a notice;
      the flyout and the media keys still want a check by hand on Windows 10 and 11
- [x] D5: documentation corrections

## Not scheduled, with the reason

Tray icon (E9); `tg://` deep links (nothing in this request; the single instance socket is ready
for them); macOS build (no Mac, per the standing decision); hard links instead of moves (exFAT and
FAT drives have none, and Android's app private storage behaviour with `link()` is *unverified*);
TDLib's own `addFileToDownloads` list (TMPlayer's queue already persists; adopting it would mean
two sources of truth).

---

# Part G: how each part is verified

**A (Windows fullscreen).** CI assertion on `windows-latest` as in A3; the floating round trip
ten times with no drift; the manual Windows 11 checklist in INSTALL.md until someone with the
hardware ticks it. Linux: `--fullscreen-after` on sway, X11 GNOME under XWayland, and KDE, as
before, to prove nothing regressed.

**B (storage).** Unit: `DownloadFilesTest` (temp dirs on two stores: on the Linux CI runner
`/dev/shm` and the workspace), `ResumeRecordTest`, `WatchCacheRulesTest`, `StorageRelocationTest`
over fakes for TDLib, the filesystem and the notifier (plan, resume after crash, refusals).
Device: on the phone, download a video, confirm `filesDir/downloads/<name>.mkv` appears and the
TDLib `videos/` folder no longer has it, play it in airplane mode, share it, delete it; cache a
video by playing, Save to Downloads from the tile, from the player and from Settings; play a
second video and confirm the first download survives and the cached one goes. On the TV stick
the same through the rail, plus a USB drive volume in Phase 4. Desktop on this machine: default
folders, then Change to a second partition (this machine has `/` and `/home` on separate
filesystems, which exercises the cross store copy), watch the D-Bus notification in mako with the
progress bar, Reset to default, kill the app mid move and relaunch. Windows toasts and the
AUMID registration leave no trace CI can read back on a Server runner, so they stay a manual item
with the Nucleus fallback documented; the taskbar progress is asserted through
`Taskbar.isSupported(PROGRESS_VALUE_WINDOW)` in the same CI step as A3.

**C (updates).** `UpdateFeedTest` over the JSON above and the GitHub fallback; scheduler tests
with a fake clock (first launch runs, second within 6 h does not, manual ignores throttle and
skip, snooze expiry); render tests of the side bar item and the popup on desktop; on the phone and
TV, install 1.19.1 over the new build's predecessor and watch the item and the popup appear once,
snooze, relaunch, skip, Check for updates re-offers. The feed file is checked in CI by fetching it
after the site deploy and comparing `version` to the tag.

**D (parity).** Each added entry gets the test its neighbours have: `BrowseKeyboardTest` for the
desktop chat actions, `PlayerKeysTest` and a TV `TvMenu` screenshot for the More menu, the
existing render tests for Settings rows.

Before every commit, as CLAUDE.md requires: the dash grep, WebP only for screenshots.
