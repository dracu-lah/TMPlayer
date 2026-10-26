# How TMPlayer works

[TDLib](https://core.telegram.org/tdlib), Telegram's own client library, handles the account, the
chats and the files, through [tdl-coroutines](https://github.com/g000sha256/tdl-coroutines), which
ships prebuilt natives for every ABI here. TDLib is never compiled in this repository.

The interesting part is a small [Media3](https://developer.android.com/media/media3) `DataSource`
that maps ExoPlayer's reads onto TDLib's offset-based partial downloads. Seek to a new position and
it points TDLib at that byte instead of waiting for the intervening gigabytes. The window
arithmetic lives in one pure class with the awkward cases pinned down by unit tests, because that
is where a streaming player either works or does not.

The player is Media3's ExoPlayer under one overlay of TMPlayer's own (`player_controls.xml`),
shared by the remote and the thumb. A television sees a single bottom row walked with the
D-pad; a phone sees the same layout plus a top bar, a centre transport and gestures. The rest of
the same APK draws a Material 3 layout on a phone, so there is one library, one data layer and two
presentations of it.

Audio the device cannot decode in hardware (DTS, TrueHD, E-AC3 on a cheap stick) falls back to
software decoders from NextLib, while video stays on the hardware decoder.

## Modules

`settings.gradle.kts` includes four modules:

- **`:core`**, Kotlin Multiplatform with three targets: android, jvm and js (the js target only builds the rules the web player in `webapp/` runs). Its `commonMain`
  is read from `src/pureMain/kotlin` and is pure Kotlin, with no JDK and no TDLib: the models and
  their rules, the string catalogs, plural rules, formatting, the sign in flow, `DownloadWindow`.
  A second set, `jvmSharedMain` (from `src/commonMain/kotlin`), is shared by android and jvm only
  and holds what talks to TDLib or needs the JDK, such as `TdByteWindow`.
- **`:ui`**, the shared Compose screens (`src/shared/kotlin`), built for android with Jetpack
  Compose and for jvm with Compose Multiplatform.
- **`:app`**, the Android app for phone and TV: activities, ExoPlayer, `TdDataSource`, services.
- **`:desktop`**, the Compose Desktop app for Windows, Linux and macOS. It plays through libmpv by
  way of [mediamp](https://github.com/open-ani/mediamp); `TdMediaData` is its twin of
  `TdDataSource` over the same `TdByteWindow`.

## Streaming and seeking

A naive player downloads from byte 0, so seeking forward means waiting for the whole file. TDLib
downloads **from any offset** (`downloadFile` with an `offset`) and reports how many bytes are on
disk from there (`updateFile`: `local.downloadOffset` and `downloadedPrefixSize`). Telegram's own
apps stream this way (tdlib/td#1498).

TDLib fills one contiguous window at a time: bytes `[downloadOffset, downloadOffset +
downloadedPrefixSize)` are on disk and valid, everything else is a hole. `DownloadWindow`
(`core/src/pureMain`) is the pure arithmetic over that window: how much can be read at a position,
and whether the download has to be moved there.

- **`TdDataSource`** (`app/`) is the Media3 shell. `open()` parses the file id from
  `tdfile://<fileId>` (with the container type as a query, so Media3 tries the right extractor
  first), asks TDLib for the file's size and, unless the bytes are already on disk, for a download
  starting at the requested position. `read()` blocks Media3's loading thread until the bytes are
  there, then reads them from the partial file. Small reads are served from a read-ahead buffer of
  bytes already on disk, because Matroska's index is read one or two bytes at a time. `close()`
  forgets the window and cancels nothing destructive.
- **A seek** makes ExoPlayer reopen the source at the new byte, which moves TDLib's download there.
  TDLib keeps ranges it already has, so seeking back into a watched part plays from disk, and a
  seek forward starts downloading at the target within about one round trip.
- **An MP4 with its index at the end** is read head, tail, head. Offset-based opens handle the
  tail read with no special case, but it stays in the matrix because it is the classic failure.
- **Rapid seeks:** each open supersedes the previous offset (TDLib keeps one active download per
  file, the last request wins), so no debounce is needed.

`TdDataSource` is written clean room from the TDLib docs and that issue. Projects that wait for the
whole file and play `local.path` are references for TDLib wiring only, never for playback.

### Seek matrix

The definition of done for any change to streaming, run on the 1 GB stick. `DownloadWindowTest`
holds the arithmetic side of it.

1. Seek 10 minutes into an undownloaded part of a big MKV: playback resumes in about 4 s or less.
2. Seek back into a part already watched: resumes in under 1 s, from disk.
3. Ten rapid alternating fast forward and rewind presses: no crash, no stuck buffer, ends playing
   at the final position.
4. An MP4 with its index at the end plays and seeks correctly.
5. Seek to the last 30 s of a file: plays to the end, the player exits cleanly.
6. Network cut during a seek: the buffering overlay shows speed 0 and playback recovers when the
   network returns (TDLib reconnects by itself).

## Constraints the design answers to

The target device is a 1 GB RAM, 8 GB storage Android TV stick. That is why the app caches one
video at a time, why memory during playback is watched, and why the APK is split per ABI while a
universal APK is published for people who should not have to identify their CPU.

## Where to read more

- The unit tests around the streaming data source are the executable version of the seek rules.
- Version pins and why they exist: [BUILDING.md](BUILDING.md).
