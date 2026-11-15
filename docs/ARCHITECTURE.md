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
software decoders from NextLib, while video stays on the hardware decoder unless the device has no
decoder for the picture, refuses it or fails to start it: then the FFmpeg extension takes it by
itself (see "Decoder fallback" below). The extension carries software decoders for old and exotic
formats too (docs/BUILDING.md lists them).

A build made with `-PtmVlc=true` (never F-Droid's) adds libVLC as a second engine (PR2): the
`Engine` interface (`ExoEngine`, `VlcEngine`) behind the same overlay, with `VlcEngine` also a
Media3 `Player` (`VlcPlayer`) so the controls, the track picker and the media session drive it
unchanged. `EngineSelector` (core) picks the engine; VLC reads a stream from `LoopbackStream` on
127.0.0.1 and a download by path. libVLC is loaded by the first video that plays in it, never at
start, and `EngineCrashWatch` marks the time it plays so a native crash is known at the next start
(PR6). See `docs/distribution/libvlc-spike.md`.

### Decoder fallback

The player's renderers are `NextRenderersFactory`'s (Media3's `DefaultRenderersFactory` plus NextLib's
FFmpeg renderers), in the order the device's MediaCodec renderer first and the FFmpeg extension
right behind it (`EXTENSION_RENDERER_MODE_ON`, with Media3's decoder fallback on). Media3 gives each
track to the renderer that reports it best supported, so no setting is needed for the common
cases: a format the device has no decoder for (WMV, RealVideo, WMA, Theora) goes to the extension,
and so does one the device's decoder refuses as beyond its capabilities (the extension reports it
handled). A decoder that claims the format and then fails to start or choke on it goes through
`PlayerActivity.switchDecoderByItself`, once per track type: the failed side's renderers are turned
off (`DecoderSwitch`) and the player is prepared again from the same position, never to software
for a 4K picture on a weak device (`SoftwareDecode.unwatchable`). "Prefer software video" puts the
extension's video renderer first (`EXTENSION_RENDERER_MODE_PREFER`); sound keeps the device first
whatever the setting says. Where neither side can decode the track the player ends in its
unsupported-picture status sheet, with Try the other decoder and Open in another app. The order is
pinned by `RendererOrderTest`; a format reaching the extension on a real device is a device check.
Media3 has MIME types for only some old codecs, so a source that reads the others (C2's
libavformat-backed extractor) names the decoder in the MIME type, `video/x-ffmpeg-<decoder>` or
`audio/x-ffmpeg-<decoder>`, with the container's extra data in the format's initialization data
(`FfmpegLibrary.ffmpegMimeType` documents the layout).

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

## Casting

Two protocols, one picker. Google Cast and DLNA (UPnP AVTransport) both push one video to a TV;
TMPlayer is the control point and serves only the chosen video and its subtitle, never a library.

- **Core `cast/`** is shared by every build: `CastServer` (token path, Range, an `allowed` address
  filter), `CastSource`, `CastFormats` (`ReceiverCaps` and the `CastPlan` routes), `MkvRemuxer`,
  `CastRecovery`, `LanAddress`, `WebVtt`. `cast/dlna/` holds the DLNA client: SSDP discovery,
  device description, SOAP, DIDL, `SinkCaps` (what the TV says it plays), `DlnaRenderer` with
  polling, and `DlnaCast` for one session.
- **LAN guard.** The session token in the URL plus the renderer's own IP; no PIN, as a TV cannot
  type one. DLNA streams carry DLNA headers and close after 30 minutes idle (paused).
- **Phone (`app/.../cast/`).** `CastSupport` is the interface `PlayerCast`, `CastRemoteScreen` and
  `CastPickerSheet` drive. `MergedCastSupport(google, dlna)` joins the lists (ids `gc:` and
  `dlna:`; Cast wins one row per address). `DlnaCastSupport` discovers only while a picker is
  open and holds a multicast lock only then. `DlnaCastService` keeps a DLNA cast alive with the
  screen off (foreground, media session, Wi-Fi and wake locks). The F-Droid build has DLNA alone.
- **Desktop.** `DesktopCast` holds a `Receiver` (Cast or DLNA), `CastFinder` lists both,
  `CastPlaybackEngine` plays on it.
- **Android TV** has no casting and runs no discovery.
- **Not done yet:** subtitles to a DLNA TV, a no-seek TV greying the bar, an MPEG-TS remux
  (only if real TVs refuse fMP4). Fixtures from real renderers are in `scripts/dlna/` and the
  core tests; `scripts/dlna/capture.py` records more from any TV.

## Platforms

What each platform plays with and what it has around playback. "Planned" means designed and not
in a release yet: keep this table in step with the code when it ships.

| | Phone | Android TV and Fire TV | Desktop | Web app | Tizen and webOS |
| --- | --- | --- | --- | --- | --- |
| Player | Media3 ExoPlayer, NextLib for audio the chip cannot decode | the same player, one overlay walked with the remote | libmpv through mediamp | the browser's own player | no app: the web app opens in the TV's browser (beta) |
| Second engine (VLC) | built with `-PtmVlc=true` only, off in released builds and in F-Droid (see `docs/distribution/libvlc-spike.md`), chosen per device, ExoPlayer stays the default; never run on a real device yet | the same as the phone | not applicable, mpv is already the second family | not applicable | not applicable |
| Open in another app | player menu: a downloaded video, or a partial one as far as it has got; a video still downloading streams to the other app through a loopback server (Settings, Playback, Default player) | hold OK on a video, same hand over | open a downloaded video in the computer's default player | not applicable | not applicable |
| Software video fallback | automatic: the device's decoder first, the FFmpeg extension when it is missing, refuses the picture or fails to start (no setting); Settings, Playback, Prefer software video puts the extension first; Try the other decoder in the player menu | the same | not needed, mpv decides | not applicable | not applicable |
| Software decoders in the app | FFmpeg extension: H.264, HEVC, AV1, VP8, VP9, MPEG-1, 2 and 4, DivX 3, VC-1, Motion JPEG, WMV 1 to 3, H.263, Sorenson, Theora, DV, SVQ1, SVQ3, VP6, RealVideo 1 to 4, Cinepak, ProRes, FFV1, Huffyuv, Ut Video, MJPEG-B; audio from DTS and TrueHD to WMA, RealAudio, GSM, ATRAC3, Speex, WavPack, TTA, Monkey's Audio and every PCM and ADPCM kind (a container Media3 cannot read is C2) | the same | mpv's own | not applicable | not applicable |
| Stutter diagnostics | could-not-keep-up card with codec, decoder, dropped frames and whether the video streamed | the same card | none yet | none | none |
| Logs and upload | Send logs shares a scrubbed file; crash reports go to Sentry only when the viewer agrees; nothing is uploaded on its own | saves the file to the Download folder (no share sheet on every TV) | saves the file | none | none |
| Filters | Night mode for sound, Volume boost, audio delay | the same | the same, through mpv's filters | none | none |
| Archives and pieces | stored ZIPs and pieces play; other archives are listed greyed with the reason | the same | the same | the same, read in the browser | none |
| Audio files | a chat's Audio list: queue, shuffle, repeat, background audio | the same, screen off while it plays | the same | none | none |

## Constraints the design answers to

The target device is a 1 GB RAM, 8 GB storage Android TV stick. That is why the app caches one
video at a time, why memory during playback is watched, and why the APK is split per ABI while a
universal APK is published for people who should not have to identify their CPU.

## Where to read more

- The unit tests around the streaming data source are the executable version of the seek rules.
- Version pins and why they exist: [BUILDING.md](BUILDING.md).
