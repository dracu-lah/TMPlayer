# TMPlayer: Telegram Media Player for Android TV

**One-liner:** A sideloaded Android TV app for the Mi TV Stick that logs into your own Telegram account via QR code, shows your chats as a media-only library, and streams movies with proper seeking, subtitles, and resume. Nothing else.

**Guiding principle:** *Super minimal work, works properly.* Every component below is either an existing library used as-is, or a small adapter (< ~400 lines) copied/adapted from a referenced working implementation. The only genuinely custom code is the TDLib↔ExoPlayer streaming bridge, and even that follows a documented, proven pattern.

---

## 1. Scope

### v1 (what we build)
| # | Feature | How |
|---|---------|-----|
| 1 | QR login (+ 2FA password screen if the account has one) | TDLib native flow + ZXing QR bitmap |
| 2 | Chat list (all chats, recents first) | TDLib `getChats`, large plain rows |
| 3 | Media-only view per chat (videos + video documents), thumbnails, infinite scroll | TDLib `searchChatMessages` with media filters |
| 4 | Streaming playback while downloading, **seek forward and back that actually works while streaming**, embedded subtitles, audio-track switching, resume position | Media3 ExoPlayer + custom `TdDataSource` + `media3-ui-leanback` player UI |
| 5 | Storage auto-management for the 8 GB stick (hard cache cap + auto cleanup) | TDLib `optimizeStorage` |
| 6 | Minimal settings: storage usage, clear cache, logout | 3 buttons, one screen |

### Explicitly NOT in v1 (cut for minimal work)
- Phone-number login (QR only; 2FA password entry stays because Telegram requires it after QR scan when set)
- Sending anything, reading text messages, notifications
- Search, chat folders, multi-account
- Download-to-keep / offline manager (streaming cache handles rewatching recent files)
- External `.srt` auto-attach (v2; embedded subtitles work day 1 via ExoPlayer)
- "Continue watching" home row (v2, but per-file auto-resume IS in v1)
- Play Store compliance (sideload via ADB only)

---

## 2. Target hardware = design floor

Design everything for the **worst** of the two Mi TV Stick variants; it then runs on anything.

| | Mi TV Stick (2020) | Mi TV Stick 4K (2022) |
|---|---|---|
| Android | TV 9 (API 28) | TV 11 (API 30) |
| RAM | **1 GB** | 2 GB |
| Storage | 8 GB (~4-5 GB usable) | 8 GB (~4-5 GB usable) |
| SoC | Amlogic quad A53, 1080p | Amlogic S905Y4, 4K, AV1 |
| ABI | assume **armeabi-v7a** (32-bit) | arm64-v8a |

**Derived hard requirements**
- `minSdk 26`, `targetSdk` latest; ship **both** `armeabi-v7a` + `arm64-v8a` (+ `x86_64` in debug builds for emulator). Use ABI splits → installed APK ≈ 30-45 MB instead of ~90 MB.
- **RAM budget:** ≤ ~180 MB PSS during playback. Consequences: no Hilt/heavy DI, single Activity + one PlayerActivity, small Coil memory cache, ExoPlayer `LoadControl` capped (`targetBufferBytes ≈ 16-24 MB`, back-buffer 0), no `largeHeap`.
- **Storage budget** (see §7): total app footprint target ≤ ~1.6 GB with default settings.
- **Hardware decode only** (SoC handles H.264/H.265; A53 cores cannot software-decode 1080p). Rare unplayable audio codec (some DTS variants) → show a clear error; ffmpeg-audio extension is a v2 option, not v1.
- First user action on real device: confirm which stick variant (Settings → Device Preferences → About). Not a blocker: floor design covers both.

---

## 3. Screens & flow (4 screens total)

```
[Splash/route] → has session? ──yes──► [Chats]──select──► [Media Grid] ──OK──► [Player]
     │ no                                                        ▲                │back (saves position)
     ▼                                                           └────────────────┘
  [QR Login] ──scanned──► (2FA password screen only if account has one) ──► [Chats]
```

Every screen has exactly three states, always implemented: **loading** (big centered spinner + one-line label, e.g. "Loading your chats…"), **content**, **error** (large message + focused "Retry" button). No screen ever shows a blank frame or an un-navigable state.

### 3.1 QR Login
- Huge centered QR (~45% of screen height), title "Scan with your phone", 3-line instruction: *Telegram → Settings → Devices → Link Desktop Device*.
- QR auto-refreshes when TDLib rotates the token (it expires ~every 30 s); tiny "refreshing…" shimmer, never an error for normal rotation.
- On scan: full-screen loader "Confirming…". If account has 2FA: a password screen with one large password field, TV keyboard, a big "Unlock" button, and the password hint shown (TDLib provides it).
- Wrong password → inline error, field refocused. That is the only typing in the entire app, ever.

### 3.2 Chats
- Plain dark list, one column, **large rows (~72-80 dp)**: avatar (or colored initial disc), chat name in large type, right-aligned muted type badge (Channel / Group / Chat).
- Ordered by Telegram's own "Main" chat list order. Paged 30 at a time; loader row at the bottom while fetching more.
- Focused row: subtle scale (1.02-1.05) + high-contrast border; both are tv-material Card defaults, not custom.
- Top-right small "Settings" affordance reachable by D-pad up.

### 3.3 Media Grid
- Grid of poster cards, **3 columns** (big cards ≈ 400 px wide on 1080p, laid-back and oversized on purpose). Card = video thumbnail (TDLib minithumbnail instantly, real thumbnail when fetched), duration badge, one-line filename, size + date caption line.
- Only media messages (filter: `searchMessagesFilterVideo` + video-mime `searchMessagesFilterDocument` pass). Text/photos/stickers never appear.
- Infinite scroll upward through history, 40 per page, bottom loader row. Empty state: "No videos in this chat."
- OK = play (auto-resume if a saved position exists; no dialog, just a 3 s "Resuming, press ⏮ to restart" toast overlay; pressing ⏮/rewind at start restarts from 0). Minimal work, no modal.

### 3.4 Player
- **No custom player UI.** `PlaybackSupportFragment` + `LeanbackPlayerAdapter` from `androidx.media3:media3-ui-leanback`, which is a complete, D-pad-native TV transport UI: play/pause, seek bar, fast-forward/rewind with press-and-hold acceleration, title row. We only configure it.
- Buffering: leanback's spinner + our one-line overlay "Buffering… 2.4 MB/s" (TDLib gives download speed) so a stall never looks frozen.
- D-pad LEFT/RIGHT while controls hidden = ±10 s seek (standard TV behavior); seek bar scrubbing for long jumps.
- Subtitle + audio track buttons in the controls row → Media3's built-in `TrackSelectionDialogBuilder` (existing component, zero custom UI). Subtitle default style: white, black outline, large.
- BACK: save position (chat id + message id + ms) to DataStore, release player, return to grid.
- Screen-on held during playback; position auto-saved every 10 s too (crash-safe resume).

---

## 4. TV UX rules (applied everywhere)

1. **Overscan-safe:** 48 dp horizontal / 27 dp vertical outer padding on every screen (Android TV guideline).
2. **Large type:** base body 18 sp, titles 28-36 sp; nothing below 14 sp on screen, ever.
3. **Focus is sacred:** every focusable uses tv-material focus scale+border; initial focus explicitly set per screen; focus restored when returning from player to the same grid card; BACK never traps.
4. **No keyboard after login.** No text search, no forms.
5. **Loaders everywhere something is async** (chat page, grid page, thumbnail placeholder, buffering, login states): always spinner + short label, never blank.
6. **Dark, flat, plain:** near-black background (#0E0E12), one accent for focus, no gradients/animations beyond focus scale and loader spin. Laid-back by design.

---

## 5. Architecture

Single Gradle module. ~15 Kotlin files. No DI framework (manual `object` singletons), no Room (DataStore only), coroutines + Flow throughout.

```
┌──────────────── UI (Compose for TV; PlayerActivity hosts Leanback fragment) ─────────────┐
│  LoginScreen        ChatsScreen        MediaGridScreen        PlayerActivity             │
└───────▲──────────────────▲──────────────────▲───────────────────────▲───────────────────┘
        │ StateFlow        │                  │                       │ MediaItem(tg://file/…)
┌───────┴──────────────────┴──────────────────┴───────────┐   ┌───────┴─────────────────────┐
│                  Repositories (thin)                    │   │ Playback                    │
│  AuthRepo (QR/2FA state machine)                        │   │  ExoPlayer (Media3)         │
│  ChatsRepo (chat list paging)                           │   │  TdDataSource ◄─ the only   │
│  MediaRepo (per-chat media paging, thumbnails)          │   │  real custom piece (§6)     │
│  StorageRepo (usage stats, optimizeStorage, caps)       │   └───────▲─────────────────────┘
└───────▲─────────────────────────────────────────────────┘           │ downloadFile(offset)/
        │ typed requests + updates Flow                               │ updateFile events
┌───────┴──────────────────────────────────────────────────────────────┴──────────────────┐
│ TdClient, one wrapper class around prebuilt TDLib: send(), updates: SharedFlow<TdApi.…> │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

DataStore keeps: resume positions (LRU map, last 200), cache-cap setting. Everything else (session, chats, files, thumbnails) lives in TDLib's own database; we deliberately store almost nothing.

---

## 6. Streaming and seeking design

Naive implementations download from byte 0 only, so seeking forward = wait for the whole file. TDLib natively supports downloading **from any offset** (`downloadFile(fileId, priority, offset, limit, synchronous=false)`) and reports how many bytes are available from that offset (`updateFile → local.downloadOffset / downloadedPrefixSize`, plus `getFileDownloadedPrefixSize`). The official Telegram app streams exactly this way (tdlib/td#1498).

### `TdDataSource` (Media3 `DataSource`)
- `open(dataSpec)`: parse fileId from `tg://file/{id}`; call `downloadFile(fileId, priority=32, offset=dataSpec.position, limit=0, sync=false)`; return known file length.
- `read(buffer)`: if bytes at current position not yet local → suspend on `updateFile` events until `downloadedPrefixSize` (relative to our offset) covers them → read from the partial file on disk (`RandomAccessFile`).
- `close()`: nothing destructive (download continues briefly; manager below decides).

### Seek handling
- ExoPlayer seek ⇒ it re-`open()`s the DataSource at the new byte position ⇒ we re-issue `downloadFile` with the new `offset`. TDLib keeps already-downloaded ranges of the same file, so seeking back to a watched part plays **instantly from disk**, and seeking forward starts downloading at the jump target within ~1 round-trip.
- **MP4 with index (moov) at the end:** ExoPlayer's first reads are: head → tail → head. Our offset-based opens handle the tail read naturally. No special casing, but it's in the test matrix because it's the classic failure.
- **Rapid repeated seeks:** each `open()` supersedes the previous download offset (TDLib maintains one active download per file; last request wins). Debounce not needed: leanback FF/RW already coalesces.
- **Definition of done (seek test matrix, run on the real stick):**
  1. Seek +10 min into an un-downloaded region of a big MKV → playback resumes < ~4 s.
  2. Seek back into an already-watched region → resumes < 1 s (from disk).
  3. 10 rapid alternating FF/RW presses → no crash, no stuck buffer, ends playing at final position.
  4. MP4-moov-at-end file plays and seeks correctly.
  5. Seek to last 30 s of file → plays to end, player exits cleanly.
  6. Kill network mid-seek → buffering overlay with speed 0, recovers when network returns (TDLib auto-reconnects).

### What was actually reused (verified 2026-08-07)
**Verified:** tvgram does NOT stream. It calls `DownloadFile(id, 1, 0, 0, synchronous=true)`, waits for the whole file, then plays `local.path`. That is exactly the anti-pattern this section exists to avoid; tvgram remains a reference for TDLib wiring only, never for playback. Consequently `TdDataSource` is written **clean-room** from the TDLib docs (`downloadFile` offset semantics, `updateFile`, `getFileDownloadedPrefixSize`) and the pattern described in tdlib/td#1498. The project is public Apache-2.0, so GPL sources (official Telegram Android, Telegram X) are behavioral references only; no code is copied from them, ever.

---

## 7. Storage plan (the 8 GB reality)

| Item | Budget |
|---|---|
| Installed APK (single ABI via splits) | ~35-45 MB |
| TDLib database (metadata) + thumbnails | ~100-200 MB (thumbnails capped by cleanup below) |
| **Media cache (partial movie files)** | **default cap 1 GB** (settings: 512 MB / 1 GB / 2 GB) |
| Headroom left on a ~4.5 GB-free stick | ≥ 3 GB untouched |

Mechanisms (all TDLib built-ins, with zero custom file bookkeeping):
1. `setTdlibParameters` with files directory inside app-internal storage (auto-removed on uninstall).
2. After every playback session AND on every app start: `optimizeStorage(size = cap, ttl = 7 days, count = 0, immunity for currently-playing file)`. TDLib deletes least-recently-used file parts itself.
3. Settings screen shows `getStorageStatistics` (used / cap) + "Clear cache now" (= `optimizeStorage(size=0)` keeping the DB).
4. Never a full-file pre-download; only streaming windows.

---

## 8. Tech stack: exact bill of materials

| Concern | Choice | Why / fallback |
|---|---|---|
| Language / build | Kotlin 2.x, AGP 8.x, single module, `minSdk 26 / compileSdk+targetSdk latest` | n/a |
| **TDLib** | **DECIDED: `dev.g000sha256:tdl-coroutines:9.0.0`**, verified on Maven Central: Apache-2.0, TDLib 1.8.66, AAR bundles `libtdjsonjava.so` for armeabi-v7a / arm64-v8a / x86 / x86_64, ~1010 typed suspend request methods + ~184 update `Flow`s | Best fit by a distance; x86_64 native included → emulator works. (TGX bundle ruled out because its README forbids use outside Telegram X; tdlight's Android natives unclear.) **We never compile TDLib ourselves.** |
| Browse UI | Jetpack **Compose for TV** (`androidx.tv:tv-material`) | Focus scale/border/cards built in: large-view TV cards with ~zero custom styling |
| **Player UI** | **`androidx.media3:media3-ui-leanback`** (`PlaybackSupportFragment` + `LeanbackPlayerAdapter`), a finished TV player UI | Per requirement "don't build a player": complete transport controls, D-pad seek, press-hold FF/RW. Fallback: Media3 `PlayerView` (also complete, slightly less TV-idiomatic) |
| Playback engine | `androidx.media3:media3-exoplayer` (+`media3-ui` for `TrackSelectionDialogBuilder`) | Streams, seeks; renders SRT/SSA/VTT **and image-based PGS/VobSub** subtitle tracks; switches between multiple audio + subtitle tracks (dual-audio movies handled natively) |
| Codec coverage | `io.github.anilbeesetti:nextlib-media3ext` (prebuilt ffmpeg decoder extension used in production by NextPlayer), added at M3 | Covers audio codecs TV sticks often lack a license for (DTS, TrueHD, some E-AC3) without us compiling ffmpeg; video stays hardware-decoded (H.264/HEVC/VP9 via SoC) |
| QR render | `com.google.zxing:core` (~0.5 MB) | 20 lines: string → Bitmap |
| Images | Coil 3 (small memory cache) | thumbnails |
| Persistence | Jetpack DataStore | resume positions + settings only |
| DI / other | none, only manual singletons; kotlinx-coroutines | minimal-work rule |

### Reference implementations (read before coding the matching milestone)
| Reference | Use for |
|---|---|
| [keygenqt/android-tvgram](https://github.com/keygenqt/android-tvgram) (Apache-2.0) | TDLib-on-TV wiring and playback approach: closest OSS cousin, safe to copy |
| [tdlib/td#1498](https://github.com/tdlib/td/issues/1498) + TDLib docs (`downloadFile`, `updateFile`, `getFileDownloadedPrefixSize`, `optimizeStorage`, `requestQrCodeAuthentication`) | Streaming/seek + storage + QR auth contracts |
| [TGX-Android/tdlib](https://github.com/TGX-Android/tdlib) | Prebuilt TDLib artifact + integration notes |
| [subinps/TelePlay](https://github.com/subinps/TelePlay) android module | Compose-TV D-pad/player UX patterns |
| [saieshshirodkar/Tele](https://github.com/saieshshirodkar/Tele) | Leanback TV Telegram UX reference |
| [google/tv-samples](https://github.com/android/tv-samples) (JetStream) | Compose-for-TV idioms: focus restore, overscan padding, grids |
| Telegram-Android `FileStreamLoadOperation`, Telegram X | Design reference for seek-while-download (pattern only) |
| [indritbashkimi/TelegramExample](https://github.com/indritbashkimi/TelegramExample) | TDLib auth state machine in Kotlin/Compose |

---

## 9. Milestones (each ends runnable on the stick)

**Testing policy (applies to every milestone):** the genuinely custom logic, namely the auth state machine, `TdDataSource` (the §6 seek matrix simulated against a fake TDLib backend), storage-cap policy and resume-position store, gets JVM unit tests with fakes, run via `./gradlew test` before a milestone closes. UI is verified on device/emulator. The on-device seek matrix is M3's hard exit gate. License: **Apache-2.0** (decided).

**M0: Skeleton (small)** Done, 2026-08-07  
git init; Gradle project; Compose-TV deps; leanback launcher manifest (`LEANBACK_LAUNCHER`, `android.software.leanback`, no-touchscreen); dark theme; placeholder screens with the loading/content/error scaffold; ABI splits; builds + installs on stick via wireless ADB.  Exit: App icon opens to a placeholder on the TV.

**M1: TDLib and QR login** Built, awaiting on-device sign-in  
Pick prebuilt artifact (order in §8); `TdClient` wrapper (send + updates Flow); auth state machine: `WaitTdlibParameters → WaitPhoneNumber(→requestQrCodeAuthentication) → WaitOtherDeviceConfirmation(render QR, auto-refresh) → [WaitPassword] → Ready`; session survives app restart; logout works.  Exit: Scan QR with phone → land on an empty Chats screen; relaunch stays logged in.

**M2: Chats and media grid** Built  
Chat list paging; media-filtered message paging; thumbnails (minithumbnail → thumbnail swap); empty/error/loader states; focus restore.  Exit: Browse a movie channel comfortably from the couch.

**M3: Streaming playback and seeking** Built. The §6 matrix is unit-tested against `DownloadWindow`; the on-stick pass is still owed  
`TdDataSource`; PlayerActivity with leanback transport UI; subtitle/audio pickers; buffering overlay with speed; resume save/restore; **pass the full §6 seek matrix on the real stick**.  Exit: Watch a movie end-to-end incl. jumping around; kill app mid-movie, reopen, resume.

**M4: Storage guard, settings, polish** Built  
`optimizeStorage` hooks + cap setting + usage screen; long-session RAM check on 1 GB profile emulator + stick; focus/back-button audit; QR expiry edge cases; error copy pass.  Exit: After three movie nights, cache stays under cap; app never needs manual cleanup.

**M5: Release build** Done. `INSTALL.md` shipped  
R8 shrink, per-ABI release APKs, self-signed keystore, versioning, `INSTALL.md` (enable Developer options + wireless debugging on the stick, `adb connect <tv-ip>`, `adb install-multiple` / correct-ABI apk).  Exit: a fresh APK installs on the stick from a tagged build.

Rough effort share: M0 5% · M1 25% · M2 15% · M3 35% · M4 15% · M5 5%. Emulator: Android TV API 28 x86_64 image with 1 GB RAM profile for the worst-case floor (needs a TDLib artifact with x86_64; selection criterion in §8); real-stick verification for M3/M4 via wireless ADB.

---

## 10. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Prebuilt TDLib artifact unusable/stale | Resolved: `dev.g000sha256:tdl-coroutines:9.0.0` (TDLib 1.8.66, natives for all four ABIs). It carries Kotlin 2.3 metadata, which is why the project compiles with Kotlin 2.3.10. |
| 1 GB RAM pressure (2020 stick) | Budgets in §2; LoadControl capped at 20 MB with no back-buffer; thumbnails decode to RGB_565 behind a 12 MB LRU (no image library at all); 1 GB emulator profile still to be exercised. |
| Codec gaps on Amlogic (some DTS audio) | Solved earlier than planned: NextLib's FFmpeg audio renderers ship in v1 behind `EXTENSION_RENDERER_MODE_ON`, so hardware decodes video and FFmpeg picks up DTS/TrueHD/E-AC3. Unsupported *video* codecs still surface a clear in-player message. |
| Seek edge cases (moov-at-end, rapid seeks) | §6 test matrix is a hard M3 exit gate on the real device. |
| Telegram flood limits on aggressive paging | Modest page sizes (30-40), no prefetch beyond one page, TDLib queues internally. |
| QR token expiry loops / clock drift | Auto re-render on `updateAuthorizationState`; never surface as error. |
| Emulator lacks matching ABI for TDLib | Prefer artifact with x86_64; else debug-on-device only (wireless ADB), which is acceptable. |

## 11. Open questions

**Stick variant** is still unconfirmed on the test device: Settings, Device Preferences, About (MDZ-24-AA is the 2020 1080p, MDZ-27-AA the 4K). The floor design covers both, so this only tunes defaults.

## 12. v2 backlog (deliberately deferred)
External `.srt` auto-attach from same chat · "Continue watching" home row · search · chat-folder tabs · ffmpeg audio decoder extension · phone-number login · photo viewing · multi-account.
