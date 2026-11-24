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
off (`DecoderSwitch` through the player's `DecoderGate`: each video and sound renderer is a
`GatedRenderer` whose side, when off, answers that it takes no format, so Media3's mapping moves the
track; the selector's disabled renderers would leave the track mapped to the side turned off, with
no picture or no sound, PB2-01) and the player is prepared again from the same position, never to software
for a 4K picture on a weak device (`SoftwareDecode.unwatchable`). "Prefer software video" puts the
extension's video renderer first (`EXTENSION_RENDERER_MODE_PREFER`); sound keeps the device first
whatever the setting says. Where neither side can decode the track the player ends in its
unsupported-picture status sheet, with Try the other decoder and Open in another app. The order is
pinned by `RendererOrderTest`; a format reaching the extension on a real device is a device check.
Media3 has MIME types for only some old codecs, so a source that reads the others (the
container reader below) names the decoder in the MIME type, `video/x-ffmpeg-<decoder>` or
`audio/x-ffmpeg-<decoder>`, with the container's extra data in the format's initialization data
(`FfmpegLibrary.ffmpegMimeType` documents the layout).

### Remedy ladder

When a video plays wrong, the player changes one thing at a time, by itself, and remembers what
worked. The pieces are pure and tested in `core` (`RemedyLadderTest`, `RemedyMemory` tests, the
two watches' tests); `RemedyRunner` and `DecoderPolicy` in `:app` are the glue.

- **Evidence** (`RemedyEvidence`). Hard evidence lets the ladder hop by itself: a decoder error
  (`DecoderError`), no decoder for the format (`NoDecoder`), sound without a picture
  (`FirstFrameWatch`: 4 s on a hardware decoder, 10 s on software, 3 s more after a seek), a picture
  that stopped while the sound goes on (`FrozenOutputWatch`, 3 s), a failed audio output
  (`AudioFailed`), and the two presses "Report stutter" and "Picture looks wrong" (`UserStutter`,
  `UserReport`). Soft evidence only offers a one-tap chip once per video: a stutter verdict
  (`Stutter`) or software that cannot keep up (`SoftwareTooSlow`, which offers a lighter copy).
  A source that stalls (`Source`) changes nothing, since another decoder would wait just the same.
- **Rungs and remedies** (`Rung`, `Remedy`). One remedy changes one thing, so a result can be
  blamed on it. R1 keeps the chip: reload at the same place, leave the failing decoder's name out
  of the codec list, turn MediaCodec async queueing on, turn tunneled playback off. R2 is the app's
  FFmpeg video decoder. R3 is the other engine, and only as `EngineSelector.afterFailure` allows it.
  A1 is the app's FFmpeg audio decoder (PCM out), A2 passthrough off. R4 asks the person: the status
  sheet, or a lighter Telegram copy, which is only ever offered. Each failure kind has its own
  order (`RemedyLadder.ladderFor`); a decoder error goes to the next decoder, then software, then
  the other engine.
- **Never loops.** At most two automatic hops per video (`MAX_HOPS`), one per level (decoder,
  sound, engine). A hop to a path already played on is refused, the next hop waits 1.5 s
  (`PROBATION_MS`), and after the status sheet only the sheet is left. The viewer's choices win:
  a picked engine, a picked decoder, a set tunneling mode, and Settings "Never switch by itself"
  (then hard evidence offers instead, except a press, which is its own consent). A video with no
  picture after a hop ends in the sheet, never in sound over black. The older one-time switches
  (`switchDecoderByItself`, the engine sheet) ask `RemedyRunner.allowsDecoderSwitch` and
  `allowsEngineSwitch` first, so they share that budget.
- **The path.** `RemedyPath` is where a video plays (engine, software, excluded names, async,
  tunneling, reloaded, audio software, passthrough off); a hop only moves away from the default,
  so it cannot return to a path it left. `DecoderPolicy` turns it into Media3's settings once per
  build of the player (the codec list, the queueing mode, the renderer order, tunneling), and the
  hop re-opens the video at the same position, tracks, speed and subtitles.
- **Class memory** (`RemedyMemory`, `RemedyClassKey`). The class of a video is the chipset, the
  Android version, the picture's kind (codec, bit depth, size and rate bucket), the codec profile,
  the container family and the audio codec, as `sun50iw6p1/28/hevc:10:1080:30/2/mkv/eac3`: never
  a title or a file id. A remedy that played 60 s clean (`WORKED_MS`) becomes the class's start, so
  the next video of that class starts on it with no wait and no stutter. A remedy that failed on
  two different videos is skipped, and tried once more after an app update. One native crash in a
  class starts its next video on software, two on the other engine. It is stored as one text line, bounded: 200 classes, counts capped, entries unseen for 180 days dropped, bad lines
  skipped. `KnownDevices` (the device feed) can name a start remedy for a device while the memory knows
  none, so a device fixed once can be fixed for others.
- **Cost.** `RemedyRunner` owns one sampler tick a second, only while a picture someone can see is
  playing; it stops once the first frame came and the path played 60 s clean, and runs again for 15
  s after a seek. A paused player, background audio or a released player post nothing, which keeps
  a 1 GB TV stick idle.
- **What it records.** Each hop and each press goes into the playback trace and the Send logs
  bundle (`PlaybackTrace`, `StutterReport`, `DecoderFacts`: the decoder names with their hardware
  flags and the output format), by class and short video tag, so a bad device can be told from a bad
  file. The presses are not offered while a cast session runs.
  A real device is the only proof that a remedy fixes a given chip: `private/PLAYBACK-TEST-MATRIX.md`.

### Container reader

A file none of Media3's extractors sniffs (ASF/WMV/WMA, RealMedia, an MPEG-1 system stream, an Ogg
with Theora, NUT, MXF, DV, WTV and the rest of the list in docs/BUILDING.md) goes to
`FfmpegExtractor`, which `FfmpegExtractors` adds after Media3's own, never instead of them:
FFmpeg's libavformat, in the extension's native library (tm4), through `FfmpegDemuxNative`. The
demuxer runs on a thread of its own and reads through two callbacks that Media3's loading thread
answers from its `ExtractorInput`: a read at the input's position, or up to 256 KB ahead of it,
comes from the input; a read anywhere else is `RESULT_SEEK` there, so `TdDataSource` reopens at
that byte and TDLib moves its download (the way Media3's binary-search seekers work). The seek
map is the container's index (ASF's is read once at open from the end of the file, RealMedia's
with its header) or a byte estimate, and the demuxer's own seek goes on from the first reopen.
Nothing is read that the demuxer does not ask for: opening a 47 MB VOB-like file reads about
1.2 MB, a seek to its middle about 130 KB. The playback details say "Container: ASF (Windows
Media), read by FFmpeg" and name both decoders.

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
4. An MP4 with its index at the end plays and seeks correctly; so do an ASF/WMV and a RealMedia
   file (the container reader: index at the end, read at open).
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
- **Finding TVs.** `DlnaDiscovery` asks for `MediaRenderer:1` and `ssdp:all` three times at the
  start and goes on while the picker is open (`live`): NOTIFY alive and byebye on port 1900, a
  slow search every 30 s, rows that expire by their max-age (an old LG that answers only
  `ssdp:all` is found). "Add a TV by its address" (`ManualAddress`: an IPv4 or a description link,
  private or link local only, no names, no IPv6, no redirects; a unicast M-SEARCH, then guessed
  description paths) is kept by `ManualTvStore` (a file in the app's own folder, not in a backup)
  and listed by every run. The picker shows Cast and DLNA counts apart; the dialog is
  `AddTvDialog` in `ui/shared`. Android 17's local network permission: `docs/BUILDING.md`.
- **LAN guard.** The session token in the URL plus the renderer's own IP; no PIN, as a TV cannot
  type one. DLNA streams carry DLNA headers (`contentFeatures.dlna.org` always, asked or not) and
  close after 30 minutes idle (paused). On the phone the filter reads the TV connected now, so a
  cast moved to another TV on the same server is not turned away.
- **What real TVs need (2026-11-23).** `DlnaRenderer` sends Stop before a new SetAVTransportURI
  to a TV that is playing, sends the DIDL again in its plainest form and then none at a 714, 402,
  501, 720 or a fault with no code (`Didl.fallbacks`), asks a Play again after a 701, seeks in
  ABS_TIME where REL_TIME gets a 710 or 402, and polls less often while the TV does not answer. A
  DIDL title carries no `&`, `<` or `>` (Rygel unescapes once too often), declares only the
  namespaces it uses, and replies are read in the charset they name. A cast that fails names its
  step (`DlnaFailure`): no answer, refused type, could not open, busy, refused with a code, never
  came for the video (no request reached the cast server 15 s after Play, whatever state the TV
  claims), stopped before playing, stopped with an error.
- **The real-renderer check.** `scripts/dlna/e2e.sh` runs `DlnaRealRendererTest` (core) against
  gmrender-resurrect and Rygel in containers and the host's own Rygel: every step from discovery
  to Stop, an MKV, and the two load failures, with every SOAP call and cast server request on file.
- **Phone (`app/.../cast/`).** `CastSupport` is the interface `PlayerCast`, `CastRemoteScreen` and
  `CastPickerSheet` drive. `MergedCastSupport(google, dlna)` joins the lists (ids `gc:` and
  `dlna:`; Cast wins one row per address). `DlnaCastSupport` discovers only while a picker is
  open or a screen's 15 s `CastPresenceScan` runs, and holds a multicast lock only then. `DlnaCastService` keeps a DLNA cast alive with the
  screen off (foreground, media session, Wi-Fi and wake locks). The F-Droid build has DLNA alone.
- **Desktop.** `DesktopCast` holds a `Receiver` (Cast or DLNA), `CastFinder` lists both (its
  search counted, like the phone's), `CastPlaybackEngine` plays on it.
- **The button (FQ8).** Core `CastPresence` decides when the Cast button is drawn: only once a TV
  has been found (either kind) or a cast is under way, a TV seen in the last 10 minutes shown at
  once while a search confirms it. The phone's player and detail page and the desktop's player
  bar each search 15 s when they come into sight and then stop, so nothing searches at idle.
- **Transcode, the last resort.** `CastPlan` gets a `Transcode` route only where no Direct, HLS,
  Remux or ConvertAudio way plays the file with its sound: a picture codec, 10 bit or a size
  past 1080p the receiver refuses, MPEG-TS or AVI, or sound nothing else converts (an MP4 with
  DTS). `TranscodePlanner` picks H.264 at most 1080p (the ladder 8, 4.5, 2.5, 1.8, 1 Mb/s, within
  60 % of the link and the encoder's limits), AAC stereo, AC-3 5.1 or the sound passed through,
  fragmented MP4 (MPEG-TS for a DLNA TV that lists TS alone). It is streamed live from the asked
  time, nothing written, one at a time (`TranscodeSlot`, a seek is a new stream), stopped past 15 %
  battery off the charger or a SEVERE thermal state, with a progress card and Stop converting.
  Phone: Media3 Transformer (MediaCodec decoders, GL, the hardware H.264 and AAC encoders, a live
  `FragmentedMp4Muxer` on the request) behind `TranscodeEngine`. Desktop: the user's own `ffmpeg`
  (`TMPLAYER_FFMPEG`, `<app data>/ffmpeg/`, next to the app, `PATH`) as a separate process reading
  a `LoopbackStream`, so nothing GPL is linked or shipped; without one the route is not offered.
- **Android TV** has no casting, runs no discovery and never transcodes.
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
| Software video fallback | automatic: the device's decoder first, the FFmpeg extension when it is missing, refuses the picture or fails to start (no setting); Settings, Playback, Prefer software video puts the extension first; Try the other decoder on the failure sheet, and in the More menu while the video plays (WP-25, `DecoderSwitch.planForPicture`), only when the other side decodes the picture's format: the same position and tracks, recorded as the viewer's pick (`RemedyRunner.viewerPickedDecoder`: it spends the video's decoder level, holds through a re-open, and is the class's start next time: back to the device at once, software only after a clean minute on it and never for a picture too heavy for software here, PB2-09; errors right after it reach the sheet, PB2-02), so neither the ladder nor the one-time switch moves it back by itself; the line warns before software for an HDR picture (shown as SDR) or a large one on a weak device | the same, in the Playback group | mpv decides (`hwdec=auto-safe`, its own fallback per stream); the player menu's Options has Force software decoding, mpv's runtime `hwdec` applied in place and kept as the setting | not applicable | not applicable |
| Software decoders in the app | FFmpeg extension: H.264, HEVC, AV1, VP8, VP9, MPEG-1, 2 and 4, DivX 3, VC-1, Motion JPEG, WMV 1 to 3, H.263, Sorenson, Theora, DV, SVQ1, SVQ3, VP6, RealVideo 1 to 4, Cinepak, ProRes, FFV1, Huffyuv, Ut Video, MJPEG-B; audio from DTS and TrueHD to WMA, RealAudio, GSM, G.723.1, Siren, ATRAC3, Speex, WavPack, TTA, Monkey's Audio and every PCM and ADPCM kind (a container Media3 cannot read is C2) | the same | mpv's own | not applicable | not applicable |
| Stutter diagnostics | could-not-keep-up card with codec, decoder, dropped frames and whether the video streamed; Report stutter in the menu keeps the last minute of the trace for Send logs | the same card, Report stutter in the Help group or a held Info key | Report stutter in the player menu's Options (WP-25, `DesktopStutter`): mpv's codec, size, decoder and dropped frames as one line in the log, and a `StutterReport` through Sentry only with crash reporting on and a real stutter in the numbers | none | none |
| Logs and upload | Send logs shares a scrubbed file; crash reports go to Sentry only when the viewer agrees; nothing is uploaded on its own | saves the file to the Download folder (no share sheet on every TV) | saves the file | none | none |
| Sleep timer, A-B repeat, bookmarks | More: Sleep timer (minutes or the end of the video), A-B repeat, Add bookmark and Bookmarks (a note at the second), on the phone and the TV | the same, with the remote | the same, in the player menu | none | none |
| Skip modes and brightness | skip intro and credits from marks (the viewer's own, `UserSkipMarks`), chapters; a Brightness page in More (steps of 10 percent, Follow the system) and Remember brightness, the window's own level | skip and chapters the same; no brightness (a TV has no window brightness) | skip and chapters through mpv | none | none |
| Stats and key sets | Playback stats overlay and the stats line in Advanced; the remote's key set per row (`PlayerRemoteKeys`), the keyboard's (`PlayerKeyMap`, tablets and Chromebooks) | the remote's set | the keyboard's set (`PlayerKeys`) | the browser's keys (`playerKeys.ts`) | none |
| Filters | Quiet loud scenes (night mode), Even out volume, Volume boost, audio delay | the same | the same, through mpv's filters | Volume boost, Night mode and Even out volume in one Web Audio graph (`boost.ts`: a lift and a compressor, a slow gain that follows the track's level, the boost, a limiter), off unless chosen, not on iPhone and iPad, a canvas engine has the boost only; no audio delay (the browser's `<video>` cannot move its sound) | none |
| Play all and Shuffle | the chat's Sort menu, Play group: Play all starts the first listed video and the chat's order follows a page at a time (`PlayAll`, `ChatOrder`); Shuffle draws in a seeded order from the pages read (`PlayAll.ShufflePool`, at most 2 pages a step, 2,000 videos held); a list's own queue is kept for the process only | the same, from the Sort menu with the remote | the same, in the Sort card | none yet (the web has Play random only) | none |
| Language lists | Settings, Subtitles and audio: the preferred audio and subtitle languages are ordered lists, the first match wins (`LanguageOrder`, stored as codes joined with a comma in the old keys, so one code is a list of one); Media3 and mpv take the whole list | Left and Right move a language, OK removes it after asking | Move up, Move down and Remove on each row | no language setting yet | none |
| Status corner | `GlobalStatusLine` (`ui/shared`): Updating after 2 s, Telegram is slow to answer with Retry after 60 s, a wait Telegram asked for with its seconds, and a sync that needs the viewer (once per kind per day, through `HintRegistry`); connection states win | words only in the pill corner, never focusable | a pill under the top edge | `WorkStatusLine` (WP-98) | none |
| Trailers | `TrailerOpener` (`ui/shared`, provided through `LocalTrailerOpener`) plays a trailer in YouTube's embed (youtube-nocookie.com) in a WebView player (`app/.../ui/trailer/`); iPhone and iPad: `docs/IOS.md` | the same, full screen, driven by the remote | `desktop/.../trailer/`: Chromium by JCEF, downloaded once with consent | `webapp/src/ui/TrailerSheet.tsx`, an iframe | none |
| Archives and pieces | stored ZIPs and pieces play; other archives are listed greyed with the reason | the same | the same | the same, read in the browser | none |
| Audio files | none: videos only, music, audio files and voice messages are never listed (`VideosOnlyTest`); a video's sound plays on with the screen off when that setting is on | none | none | none | none |

## Constraints the design answers to

The target device is a 1 GB RAM, 8 GB storage Android TV stick. That is why the app caches one
video at a time, why memory during playback is watched, and why the APK is split per ABI while a
universal APK is published for people who should not have to identify their CPU.

## Where to read more

- The unit tests around the streaming data source are the executable version of the seek rules.
- Version pins and why they exist: [BUILDING.md](BUILDING.md).
