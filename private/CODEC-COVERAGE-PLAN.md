# Codec coverage: decoders, containers, fallbacks, transcode (user go 2026-11-15)

Ask: "add all encoders, software decoders, fallbacks, everything, so I do not have to add stuff all
the time". Answers (2026-11-15): scope Android plus the web app; formats: old video codecs, more audio
decoders, more containers, exotic video; encoders: full transcode for casting; limits: GPL parts allowed.

Rules for every task: commit locally section-wise (no push, no Co-Authored-By trailer), no em or en
dashes, new user text only in en.json (keys sorted, never run langsync), no `!!`/`.first()` on outside
data, a bad or truncated file must never crash the app (garbage tests), low-end 1 GB TV first (memory
and idle CPU measured), every platform. Gradle: `JAVA_HOME=/home/dracu/.jdks/jdk-21.0.12+8`, wrap as
`flock /tmp/tmplayer-gradle.lock ./gradlew ... --max-workers=2`. The NDK 25.2.9519653, CMake 3.22.1 and
nasm are installed; the extension build is `JOBS=4 scripts/build-media3ext.sh` (about an hour for four
ABIs, so build one ABI first with the script's own ABI switch if it has one).

## Decisions made by Claude
- **Licence:** GPL is allowed, but no FFmpeg decoder needs it, so the decoders stay LGPL v3 (no
  `--enable-gpl`, nothing non-free): the AAR's terms, `THIRD_PARTY_NOTICES.md` and the F-Droid recipe do
  not change. GPL is considered only in C4, for software x264, and only when no hardware encoder exists.
- **No size cap** (answer 2 was GPL allowed, size not capped): report the AAR and APK growth per ABI after
  C1 so the user sees the cost; a decoder that adds more than 1 MB per ABI for a format that is almost
  never seen (ProRes, FFV1) is built but listed in the report for a later cut.
- **Order:** C1 first (highest value, the rest builds on it), C5 with it, then C2, C3, C4 as spikes whose
  result decides the build.

## Tasks

### C1 Android software decoders and fallbacks (tm3 of the FFmpeg extension)
model: sonnet high. depends: none. files: `scripts/build-media3ext.sh`, `scripts/media3ext/*`
(a second patch or an extended tmplayer-decoders.patch, bump `TM_REVISION` to tm3), `app/.../player/`
renderer factory and `DeviceDecode`/`DecodeJudge`, `docs/BUILDING.md`.
done when: (1) an inventory of what nextlib's FFmpeg already decodes (video, audio, subtitles) is in the
plan Progress; (2) every missing LGPL decoder from these groups is enabled and mapped from its Media3
MIME in `FfmpegLibrary` with codec private data where needed: old video (mpeg1video, mpeg2video, wmv1,
wmv2, h263, h263p, flv/sorenson, theora, dvvideo, svq1, svq3, vp6, vp6f, rv10, rv20, rv30, rv40, cinepak),
exotic video (prores, ffv1, huffyuv, ffvhuff, utvideo, mjpegb), more audio (wmav1, wmav2, wmapro,
wmalossless, mp2, mp1, adpcm_* and pcm_* variants, cook, ra_144, ra_288, qcelp, gsm, amrnb, amrwb, sipr,
atrac3, dts/dca variants, truehd, mlp, eac3 if missing, speex, wavpack, tta, ape if LGPL); (3) the
renderer order is hardware decoder first, then the extension, with the software fallback chosen
automatically when the hardware decoder refuses or fails at start (no manual setting needed), and the
existing "unsupported picture" status sheet stays as the last stop; (4) each new decoder has a host test
through `scripts/media3ext/bench` using a clip generated with a host ffmpeg (skip a format the host
ffmpeg cannot encode and say so); (5) the new AAR builds for armeabi-v7a first, then all ABIs; size per
ABI before and after is reported; (6) docs: BUILDING.md decoder list, ARCHITECTURE.md platforms table.

### C2 More containers (ASF/WMV, RealMedia; MPEG-PS already has Media3's PsExtractor, confirm)
model: opus high for the design, sonnet for the build. depends: C1.
files: `app/.../player/` extractor wiring, `scripts/media3ext/` (libavformat if chosen), `core` format
detection.
done when: a spike chooses between (a) a Media3 Extractor backed by FFmpeg's libavformat through JNI with
a custom AVIO reading from the `TdDataSource` window and (b) Kotlin extractors for ASF and RealMedia; the
choice, its size and its seek behaviour on a partially downloaded file are written here; then ASF/WMV,
RealMedia and VOB/PS play and seek on the offset-based window (Seek matrix in ARCHITECTURE.md).

### C3 Web app: libav.js custom build (software fallbacks for the web, webOS and Tizen)
model: opus high design, sonnet build. depends: C1 (same codec list).
files: `webapp/libav/`, `webapp/src/player/engines.ts`, `private/WEBAPP-PLAN.md` session 16.
done when: a libav.js variant with the C1 audio decoders first, containers second, SD software video
third, loaded only when needed (like the AC-3 coders), within a size budget measured on Chromium 81 on the
1 GB stick; the surveyed "needs the app" files play; A/V sync measured; the licence of each module in
`webapp/licenses`.

### C4 Full transcode for casting (Cast and DLNA)
model: opus high. depends: C1. files: `core/.../cast/`, `app/.../cast/`, `desktop/.../cast`.
done when: a spike measures a MediaCodec decode to encode pipeline (H.264 plus AAC, fMP4 or MPEG-TS) on
the phone and writes: speed on a mid phone, battery, thermals, the fallback to software x264 (the only GPL
piece, decided at that point), and whether the desktop does it through its own bundled encoder; then the
`CastPlan` gains a Transcode route used only when remux cannot make the TV play the file, with a progress
card, cancel, a storage check, and the TV never asked to transcode (receiver only).

### C5 Docs and licences
model: sonnet low. depends: C1.
files: `THIRD_PARTY_NOTICES.md`, `docs/BUILDING.md`, `docs/ARCHITECTURE.md`, `docs/RELEASING.md`
(source archive of the tm3 AAR), `docs/distribution/README.md` if the F-Droid recipe changes.

## Progress / handoff
(workers append: task, commit, checks, decisions)

### C1 done (worker, 2026-11-15), branch worktree-agent-ad4735d33e4a59641, 7 local commits 7e9d24b..5f9839a, nothing pushed
**Inventory of nextlib 1.11.1-0.16.0's FFmpeg 9.0.1 (commit 2dc77cd2, ffmpeg/setup.sh), before tm3:**
- Decoders (`ENABLED_DECODERS`): vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3
  dca mlp truehd h264 hevc mpeg2video mpegvideo vp8 vp9 libdav1d (dav1d 1.5.4). tm1/tm2 added mpeg4
  msmpeg4v2 msmpeg4v3 vc1 mjpeg. No subtitle decoders at all (text is Media3's and NextTextRenderer's).
- Demuxers: all 367 of FFmpeg (`--enable-demuxers`), parsers: all 68 (`--enable-parsers`); libavformat is
  built but NOT packaged in the AAR (CMake links only avutil, avcodec, swresample, swscale) and the JNI
  glue never opens a container. Relevant to C2: the demuxers (ASF, RealMedia, VOB) are already compiled.
- Protocols: file http https mmsh mmst pipe rtmp rtmps rtmpt rtmpts rtp tls (Mbed TLS 3.6.7). No
  encoders, muxers, filters, hwaccels. Bitstream filters only those the selected decoders pull in.
- Media3 1.11.1's extractors name only these codecs: video mp4v-es, divx, mp42, mp43, mjpeg, vc1, mpeg,
  mpeg2, 3gpp (H.263), avc, hevc, av1, vp8, vp9; audio mp3, L1, L2, aac, ac3, eac3, dts, truehd, vorbis,
  opus, flac, alac, amr, alaw, mlaw, gsm, raw. The AVI extractor drops every other FourCC (WMV, Cinepak,
  Theora in AVI, DV), the MKV one every other V_/A_ id, so most new decoders are reachable only through
  C2's extractor; they are named `video/x-ffmpeg-<decoder>` / `audio/x-ffmpeg-<decoder>` for it
  (FfmpegLibrary.ffmpegMimeType, layout of the initialization data in its Javadoc).
**Added (LGPL v3, configure checked: "License: LGPL version 3 or later", no --enable-gpl):**
- Old video: mpeg1video wmv1 wmv2 wmv3 (extra: same file as vc1, needed for every WMV9 file) h263 h263p
  flv theora dvvideo svq1 svq3 vp6 vp6f rv10 rv20 rv30 rv40 cinepak. Exotic: prores ffv1 huffyuv ffvhuff
  utvideo mjpegb.
- Audio: wmav1 wmav2 wmapro wmalossless mp1 mp2 cook ra_144 ra_288 qcelp gsm gsm_ms sipr atrac3 atrac3p
  speex wavpack tta ape nellymoser (extra: FLV audio), all 35 PCM and 54 ADPCM decoders that are LGPL.
  dca, truehd, mlp, eac3, amrnb, amrwb were already in.
- **Skipped:** 8 ADPCM (adpcm_circus ima_escape ima_hvqm2 ima_hvqm4 ima_magix ima_pda n64 psxc) and ahx:
  marked LGPL in FFmpeg but built only with `--enable-gpl` (`lgpl_gpl`); the `*_at` decoders (Apple
  AudioToolbox). Every other name in C1 exists in 9.0.1. Names differ from the configure ones in 7 cases
  (real_144, real_288, atrac3plus, g722, g726, g726le, msmpeg4): the mapping uses the decoders' own.
- Mapping (FfmpegLibrary): MPEG audio L1 to mp1 and L2 to mp2 (were mp3), AUDIO_MSGSM to gsm_ms, H.263 to
  h263, every other new decoder by its `x-ffmpeg-` MIME; extradata is the container's, passed as it is.
  JNI glue now also hands FFmpeg sample rate, channels, block_align, bit rate, bits per coded sample
  (audio) and width, height, bits per coded sample and FourCC (video: Ut Video needs it) via
  ffcodecparams.h; only for decoders that were not there before, so tm2 behaviour is unchanged. Extra
  data now has zeroed padding (was av_malloc in the audio glue).
**Fallback (decision: nothing to add, verified):** NextRenderersFactory in EXTENSION_RENDERER_MODE_ON
  lists the device's renderer first and FFmpeg's right behind (Prefer software video = PREFER, ahead);
  Media3 gives a track to the best-supported renderer, so a missing decoder or one that answers "exceeds
  capabilities" goes to FFmpeg by itself, and a decoder that fails at start or mid-way goes through the
  existing PlayerActivity.switchDecoderByItself / DecoderSwitch (once per track type, never to software
  for 4K on a weak device), then the failure sheet (Try the other decoder, Open in another app) as the
  last stop. New test RendererOrderTest pins the order; CrashPlayingNoteTest got the x-ffmpeg names.
**Checks:** `run-codec-tests.sh`: 45 decoders pass bit-exact against a reference ffmpeg 9.0.1 (md5), and
  each survives 8 seeds of damaged extra data and packets with no crash or hang. Untested (no host
  encoder): wmv3 svq3 vp6 vp6f rv30 rv40 mjpegb h263p wmapro wmalossless mp1 cook ra_288 qcelp sipr
  atrac3 atrac3p ape and the rarer ADPCM and PCM kinds. `:app:compileDebugKotlin` against the tm3 AAR
  and the two unit test classes pass. JNI sources syntax-checked with the NDK clang (armv7a) and built
  for four ABIs. No device or emulator touched (device checks left: any of the new formats on a real
  phone and the 1 GB TV, idle CPU, memory).
**Sizes** (Maven AAR 1.11.1-0.16.0 to tm3; no tm2 AAR exists on this machine): whole AAR 14.83 MB to
  17.58 MB (+2.75 MB). Native libraries in the AAR, compressed / raw: arm64-v8a 3.51 to 4.20 MB / 7.88 to
  9.23 MB; armeabi-v7a 3.35 to 4.02 / 7.12 to 8.44; x86 3.58 to 4.26 / 8.97 to 10.47; x86_64 4.34 to 5.04 /
  11.64 to 13.20. About +0.7 MB per ABI in the APK (compressed), +1.3 to 1.6 MB installed, includes tm1
  and tm2's decoders; no single decoder is near the 1 MB line (libavcodec grew 1.3 MB raw for all).
**Left for others:** release.yml, RELEASING.md and THIRD_PARTY_NOTICES.md still name `-tm2` (C5; the
  release job stops by design until the tm3 source archive is linked). C2 builds the extractor on the
  `x-ffmpeg-` naming. Build time: four ABIs about 8 minutes with JOBS=4 (12 threads); `TM_ABIS=armeabi-v7a`
  builds one ABI into a `-partial-` AAR.
- C5 done (2026-11-15): tm2 to tm3 in release.yml (LINKED tm3, TAG empty until the archive is attached), RELEASING.md, THIRD_PARTY_NOTICES.md (decoder families, GPL skips), build-nextlib-source.sh header. ARCHITECTURE/BUILDING already fine.

### C4 design (worker, 2026-11-15, step 1)
**Decisions made by Claude (overrule in the PR):**
- **Licence stays LGPL.** Android: Media3 Transformer (Apache 2.0) on the device's MediaCodec
  decoders and its hardware H.264 and AAC encoders; no x264 in the APK. When the phone has no H.264
  encoder at all the route is not offered (said so on screen); software x264 is not added: no Android
  phone of the API 23 floor ships without a hardware H.264 encoder, so GPL buys nothing. Desktop: an
  `ffmpeg` executable the user has (TMPLAYER_FFMPEG, `<app data>/ffmpeg/`, next to the app, then
  PATH), run as its own process; no ffmpeg on the computer means no Transcode route. Nothing GPL is
  linked or shipped, the app's licence and THIRD_PARTY_NOTICES do not change.
- **When:** only where remux, HLS, ConvertAudio or Direct cannot make the receiver play the file: a
  picture codec it refuses (MPEG-4 Part 2, MPEG-2, VC-1, HEVC or AV1 on an old Chromecast), 10 bit on
  a receiver without it, a picture past 1080p on a 1080p receiver, a container with no rewrap here
  (MPEG-TS, AVI, other), or sound it cannot play that nothing converts (MP4 with DTS, which the
  Matroska remuxer cannot reach). A Maybe picture (HEVC on an unnamed Chromecast) is tried as it is
  first and transcoded only on a receiver error, as before. The TV build never transcodes (no casting
  there, and the ability is null on a TV form factor).
- **Pipeline, Android:** CastSource as a Media3 DataSource (through the RecoveringCastSource, so a
  Telegram stall is ridden out) -> Transformer's ExoPlayer asset loader (MediaExtractor-free, Media3
  extractors: MKV, MP4, TS, AVI) -> MediaCodec decoder -> GL (scale, frame drop, HDR to SDR tone map)
  -> hardware H.264 encoder (High profile where offered, else Main) + AAC LC 192 kb/s stereo, or the
  audio passed through when the receiver plays it and MP4 carries it (Composition.setTransmuxAudio),
  or the picture passed through when only the sound or the wrapper is the problem
  (setTransmuxVideo) -> a live Muxer (Media3's FragmentedMp4Muxer on a channel over the HTTP
  response, no file) -> CastServer `stream<n>.mp4?t=<ms>`. MediaCodec and Transformer stay behind
  `TranscodeEngine` (core), so the driver (wait, progress, cancel, guard) is faked in tests.
- **Pipeline, desktop:** a LoopbackStream (127.0.0.1, ranges) gives ffmpeg the video as a URL, so it
  seeks in an MP4 whose index is at the end; `-ss <start> -i <url>`, `-map 0:v:0 -map 0:a:<n>?`,
  the first working H.264 encoder from `ffmpeg -encoders` (videotoolbox, nvenc, qsv, amf, mf, then
  libx264, libopenh264), `-f mp4 -movflags frag_keyframe+empty_moov+default_base_moof` (or `-f
  mpegts` for a DLNA TV that lists TS and not MP4) to stdout, copied to the HTTP response; progress
  from `-progress pipe:2`. A hardware encoder that fails before the first byte falls to the next.
- **A/V sync:** one pipeline makes both tracks from one clock (Transformer's and ffmpeg's own), the
  output starts at 0 at the seek point and the sender adds the start back (Published.offsetMs), as
  the rewrap does. No separate audio path to drift.
- **Seeking:** the output has no index, so a seek is a new stream at the new offset (`?t=`), the
  old one taken down (its connection closed, so its transcode stops). Transformer clips from the
  exact time (decodes from the keyframe before); ffmpeg's input `-ss` does the same.
- **Bitrate and size:** at most 1080p (a phone or a CPU does not keep up with 4K, and a 1080p stick
  needs it), never larger than the source, even sides, by the rung ladder 1080p 8 Mb/s, 720p 4.5,
  540p 2.5, 480p 1.8, 360p 1 Mb/s; the rung is the highest within 60 % of the measured link (when
  known) and the encoder's limit. 30 fps cap on a software encoder or a picture past 1080p, else
  60. AAC 192 kb/s stereo; AC-3 640 kb/s 5.1 kept for a receiver that takes Dolby, a multichannel
  source and an encoder that has AC-3 (ffmpeg); Android always AAC.
- **Storage:** streamed live, nothing written. The Transformer output path is a dummy in
  `cache/cast-transcode/` that the live muxer never opens; the folder is emptied at every start and
  stop, so no partial file survives a crash. So no storage check is needed; the plan's "storage check"
  is met by writing nothing.
- **Guards:** one transcode at a time (TranscodeSlot: a new one stops the old); battery under 15 %
  and not charging, or thermal status SEVERE or worse (API 29+; under 29 battery alone), stops it
  with a message; checked every 5 s. Slower than real time for 20 s says so (the TV will pause to
  catch up) but goes on.
- **UI:** a progress card on the cast remote (phone) and the casting panel (desktop): "Converting
  for the TV", how far it has got and the speed, a bar, and Stop converting (stops the transcode and
  the TV's player, then Try again or Play here). Messages for no encoder, too hot, battery low, the
  TV unreachable (the existing connect text), the receiver hanging up.
- **Cleanup:** cancel, a new seek, Stop casting, the activity going (release), the session dropping
  and the server stopping all close the stream, which ends Transformer (cancel on its looper) or
  kills ffmpeg (destroyForcibly, its loopback closed). Rotation does not recreate the player
  (configChanges), so a transcode survives it.

### C2 step 1 decision (worker, 2026-11-15): route (a), libavformat behind a Media3 Extractor
- **Spike facts:** nextlib's FFmpeg is configured with `--enable-avformat --enable-demuxers` and 12
  network protocols (Mbed TLS for https), libavformat.so is built (2.2 MB raw on armeabi-v7a) but not
  linked or packaged, and the JNI glue has no libavformat call (avcodec only). So (a) needs a small JNI
  file and a tm4 AAR (8 min for four ABIs with JOBS=4).
- **Chosen: (a).** (b) means writing ASF (multi-payload, compressed payloads, replicated data, index
  object) and RealMedia (slice reassembly of RV frames, the genr/int4/sipr audio deinterleavers of
  Cook, ATRAC3 and SIPR) clean room from specs, 1500+ lines, for two containers only; (a) reuses
  FFmpeg's OSS-Fuzz'd demuxers for all of them in about 600 lines of glue.
- **Seek on a partial file:** the demuxer runs on its own thread; its AVIO read and seek callbacks are
  answered by Media3's loading thread from the current ExtractorInput: a read at the input's
  position (or a short skip ahead, up to 256 KB) is served from it, any other position returns
  RESULT_SEEK, so Media3 reopens TdDataSource there and TDLib moves its download (the same way TS's
  binary search seeks). No byte is read that the demuxer did not ask for; ASF's and RealMedia's index
  is at the end (one tail read at open, like an MP4 with its index at the end); a file with no index
  seeks by binary search (log2 opens), and the SeekMap hands Media3 the index position, or a byte
  estimate, so the first reopen is already near the target.
- **Memory, CPU:** one 64 KB AVIO buffer, probe size capped at 1 MB and 2 s of analysis, one
  parked thread per playing file (blocked on a queue: no CPU while paused), ended in release().
- **Garbage:** FFmpeg's demuxers stay native (a crash there is a crash), so: only a fixed list of
  demuxers is compiled (smaller surface), no protocol at all (no network, nested opens refused via
  io_open), stream count, extra data and packet sizes bounded, every JNI value checked, the Kotlin
  side has garbage tests and the host test feeds truncated, flipped and random files through the
  same C++ core.
- **Licence:** LGPL v3 as before (no --enable-gpl), libavformat.so is a shared library.
- **Demuxers (last resort, after every Media3 extractor):** asf, rm, ivr, xwma, dv, nut, mxf, nsv,
  wtv, ape, tta, wv, aiff, au, caf, voc, w64, ogg (Theora), mpegps (MPEG-1 system streams, which
  Media3's PsExtractor does not sniff), smjpeg, vivo. AVI, FLV and Matroska with a codec Media3 drops
  are not reached (Media3's extractor claims the file and plays it without that track): a later
  task.

### C2 done (worker, 2026-11-15), branch worktree-agent-a6d7efabcf4f86615, 2 local commits 49f8ade, 3f5d5f8, nothing pushed
- **Built:** tm4 AAR (4 ABIs, 8 min JOBS=4) at `<worktree>/build/media3ext/nextlib-media3ext-1.11.1-0.16.0-tm4.aar`
  (copy it to the main checkout's build/media3ext after merging; app/build.gradle.kts now names tm4).
  libavformat.so 0.56 to 0.60 MB raw per ABI (2.2 MB with nextlib's 367 demuxers and protocols);
  natives compressed per ABI +0.27 to 0.30 MB (arm64 4.20 to 4.47, v7a 4.02 to 4.31), AAR 17.58 to 18.71 MB.
- **Code:** patch adds `ffdemux.h` (pure C++ core, shared with the host test) and `ffdemux.cpp` (JNI for
  `com.tmplayer.player.demux.FfmpegDemuxNative`, in the app so a Maven-AAR build still compiles);
  app `demux/`: FfmpegExtractor (demux thread, IO hand-over, RESULT_SEEK reopen, index or byte-estimate
  SeekMap), FfmpegExtractors (appended last), FfmpegFormats (Media3 MIME for mpeg2/h264-annexB/hevc-annexB/
  ac3/eac3/dts/truehd/mp1-3, x-ffmpeg-<decoder> otherwise with tm3's init-data layout), ContainerValues
  (bounds on every native value). Playback details: "Container: X, read by FFmpeg", video and audio decoder.
- **Checks:** run-demux-tests.sh 19/19 (asf wmv2+wmav2, asf msmpeg4+wmav1, wma, rm rv20+ra144, rm rv10+ac3,
  MPEG-1 PS, VOB, nut, mxf, wtv, ogg theora+vorbis, dv, aiff, au, caf alac, voc, w64, wv, tta), each 12
  garbage seeds; 200 more seeds on 8 of them, no crash. Big files: 47 MB VOB open reads 1.2 MB, mid seek
  131 KB / 2 reopens; 32 MB WMV open 590 KB, seek 66 KB / 3 reopens; 30 MB RM 64 KB, seek 64 KB / 1 reopen.
  JVM: FfmpegExtractorTest 7, FfmpegFormatsTest 7, ContainerCoverageTest 3 (VOB is PsExtractor's and plays;
  ASF, RM, MPEG-1 PS, Ogg Theora sniffed by no Media3 extractor), CatalogTest, RendererOrderTest,
  CrashPlayingNoteTest, CuelessMatroskaSeekTest pass.
- **Untested:** ivr, xwma, nsv, vivo, smjpeg, ape, RealMedia with RV30/RV40/Cook/SIPR/ATRAC3 (no host
  muxer/encoder); everything on a device (no adb by rule): the Seek matrix on the 1 GB TV with a WMV and
  an RMVB, idle CPU while paused, memory, the playback details sheet, R8 keep rules in a release build.
- **Left / next tasks:** (1) device pass of the above; (2) AVI/FLV/MKV whose codec Media3 drops (WMV3 or
  Cinepak in AVI, VP6 in FLV, RealVideo in MKV) still play without that track: a wrapper that hands such a
  file to FfmpegExtractor when Media3's extractor publishes a track with no decoder (spike); (3) C5:
  release.yml, RELEASING.md, THIRD_PARTY_NOTICES.md and build-nextlib-source.sh still name tm2/tm3 and need tm4;
  (4) new en.json keys (player.details_audio_decoder, details_container_ffmpeg, details_container_value,
  details_video_decoder) need the 21 translations before a release; (5) DVD LPCM in a VOB is Media3's
  PsExtractor's, which skips it (not checked).

### C4 done (worker, 2026-11-15), branch worktree-agent-a39cc79251d364a49, 5 local commits 89959bc..1096a63, nothing pushed
- **Core:** `CastRoute.Transcode(audioTrack, reason, target)` replaces `TranscodeVideo`; `CastFormats.plan(...,
  transcoder, linkKbps, receiverContainers)`, `remuxEnough`; `TranscodePlanner` (ladder, 1080p cap, fps, AAC/AC-3/copy,
  fMP4/TS, tone map), `TranscodeGuard`, `TranscodeSlot` (one at a time, progress, stop reasons),
  `TranscodeEngine` + `EngineTranscoder`, `CastStreams` serves `stream<n>.mp4?t=`; `Ffmpeg` args, encoder list,
  `FfmpegProgress`. Ability carries `passVideo`/`passAudio` (Media3's fMP4 muxer: AAC, Opus; no AC-3).
- **Desktop:** `FfmpegLocator`, `DesktopFfmpeg.detect` (cached 1 min), `FfmpegTranscoder` (loopback input, encoder
  fallback, tone map fallback, kill on cancel/hang up), casting panel card, refusal says "install ffmpeg".
- **Phone:** `AndroidTranscodeAbility` (MediaCodecList, null on TV or without H.264 encoder), `AndroidDeviceHealth`,
  `CastSourceDataSource`, `TransformerEngine` (Transformer + live `FragmentedMp4Muxer` on the request,
  `setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)`, clip start = seek, stereo mix), wired in `PlayerCast`
  (Stop converting, stop texts, a cut stream is not "ended"). New dependency media3-transformer 1.11.1 (Apache 2.0).
- **Checks:** core TranscodePlanTest 22, TranscodeRunTest 12, FfmpegTest 7, CastFormatsTest 25, CatalogTest,
  GarbageInputTest (ffmpeg output) green; desktop FfmpegTranscoderTest 9 + CastRemoteRenderTest (converting card,
  3 forms x 2 themes) green; app AndroidTranscodeTest 6 + existing cast tests green; `:app:compileDebugKotlin` green.
- **Not done / gaps:** TransformerEngine never ran on a device (no adb by rule): live muxer over a socket,
  Transformer cancel, clip accuracy, ChannelMixing matrices for 6/8 channels, decoder fallback are unverified. The
  link is not measured (`linkKbps` null, so 1080p 8 Mb/s unless the encoder caps it); DLNA `receiverContainers`
  not passed from the Sink list yet (TS output only reachable in tests); the desktop has no battery/heat guard.
- **Device checks due:** speed on a mid phone (1080p HEVC 10 bit to H.264, real time or not), battery drain per
  hour and thermal status after 30 min, a real Chromecast (fMP4 from Media3's muxer, seeking by new stream), a
  real DLNA TV (Samsung, LG) taking the stream with no length, desktop with a real ffmpeg (nvenc, qsv, libx264).

### C3 done (worker, 2026-11-15), branch worktree-agent-a9a5b6cdf7cd6c329, 3 local commits 265caa0, 93904e2, c40cb71, nothing pushed
**Step 1, what existed:** session 16 (c86739c) had one libav.js 6.10.9.0 variant `tmplayer` (FFmpeg 9.0, emsdk
6.0.5 in podman, `webapp/libav/build.sh`, sources published by `sources.sh`) for AVI only: MPEG-4 Part 2, MS MPEG-4,
H.263 decoded and re-encoded by WebCodecs, H.264 copied, MP3/MP2/AC-3/AAC/PCM/MS+IMA ADPCM sound; 1.48 MB wasm. movi
(FFmpeg + WebCodecs, canvas) was the last software stop, then "needs the app". Its wasm used bulk memory, sign-ext
and trunc_sat and its JS optional chaining and a module worker, so it could never run on webOS 5/6 or Tizen.
**Decisions made by Claude (overrule in the PR):**
- **Build route:** keep the existing custom libav.js variant system (fragments per codec, no hand-rolled emcc):
  no prebuilt per-codec package covers WMA/Cook/TrueHD etc.; libav.js's fragments save writing any C glue. Two
  variants instead of per-codec files: `tmplayer` (engine) and `tmplayer-sound` (aac mp1 mp2 mp3 opus vorbis flac
  only, for mediabunny's CustomAudioDecoder). Per-codec splitting would mean one avformat+avutil base (~0.6 MB)
  per file, more than it saves.
- **Licence:** LGPL-2.1-or-later, configure says so for both; no --enable-gpl/version3/nonfree. Module list and
  licences in `webapp/licenses/libav-modules.txt`; notices.config.ts and THIRD_PARTY_NOTICES.md updated.
- **TV route:** the classic `.js` build is loaded everywhere (Chromium 79 reads `type: 'module'` it cannot honour,
  so module workers cannot be detected), its JS lowered to chrome68 by `webapp/libav/lower.mjs` (vite's oxc, an
  inline defineProperty helper, a globalThis shim); a `.mvp.wasm` lowered by binaryen 132 is picked by a
  WebAssembly.validate bulk-memory probe (Chromium < 75). The `.mjs` builds stay only for node's tests.
- **SD video on TVs (step 3) is NOT done:** the engine still re-encodes decoded pictures with WebCodecs, which
  no TV engine (68/79/81) has, so on a TV only H.264-copy files (AVI/FLV/MKV H.264 with odd sound) gain; other
  pictures still say "needs the app" there. Next task: a canvas renderer in the libav worker (YUV to WebGL,
  audio clock from Web Audio), capped at 480p, with the existing too-heavy message.
**Sizes (raw / gzip):** engine wasm 3.77 MB / 1.66 MB (was 1.48 / 0.62), its mvp 3.77 / 1.65, classic glue 326 KB /
~57 KB; sound wasm 1.15 MB / 0.50 MB, mvp same. Main entry unchanged (index 19.59 KB gzip both); i18n +22 B,
Player +0.14 KB, libav chunk +2.4 KB, new lazy libavsound chunk 1.3 KB. Budget on the 1 GB stick (WebView 81) not
measured on the device: on headless Chromium 79 the engine starts in 0.6 to 1.0 s and decodes 100 MPEG-2 frames
(320x176) in ~0.1 s after start, 68 (mvp) 1.1 s; sound build starts in 0.2 to 0.3 s. Memory estimate: 24 MiB
initial heap per instance plus compiled code, one engine instance per open file, the sound instance shared and
ended 30 s after its last decoder.
**Step 2 built:** sound (libavsound.ts, MPEG Layer II named mp3 decoded instead of copied, codec-null sound with
H.264 routed to the engine), containers (FLV, ASF/WMV, RealMedia, MPEG-PS by sniff; MP4/3GP/MKV pictures
mediabunny cannot name; MPEG-2 TS and Ogg Theora where mediabunny finds no picture), SD video (30 picture
decoders, any layout to I420 in JS, cap 480p on <=4 cores or <=2 GB, 720p phone, 1080p desktop, else
web.needs_app_too_heavy). Engine fixes: start time offset (TS 1.4 s), no seek on a fresh demuxer, byte seek when no
index (FLV), avcC copy from MP4/MKV/FLV.
**Checks:** `pnpm test:player` 103 pass (libavframes 9 incl. garbage; libavbuild 38: 31 host-ffmpeg clips through the
real engine build, damaged WMV, mvp equals full, 6 sound tracks through mediabunny + the sound build). tsc, `vite
build`, `vite build --mode tv` pass. Chromium e2e 183 (WMV plays, oldformats.spec 10 incl. seeks in VOB/FLV/TS,
TrueHD with sound); posters' Settings test flaked once, passed alone. Old Chromium (snapshots r706915 and r555668
re-downloaded to the session scratchpad, run over CDP with a minimal fonts.conf for 68): classic frontend, worker,
both builds decode on 79 and 68 (mvp). A/V sync not re-measured (session 16's 14 ms method still applies).
**Untested / left:** WebKit e2e (podman) not run; real iPhone; the 1 GB TV (memory, idle CPU, speed, WebView 81);
codecs without a host encoder (wmv3, vc1, rv30/40, cook, atrac3, qcelp, sipr, wmapro, ape, vp6, indeo); the TV
canvas renderer (above); survey() still counts only sniffed containers as `libav`; Matroska A_MS/ACM sound trusts
FFmpeg's mapping; webOS/Tizen real-device check of /libav/ loading from the /tv/ build.

### W14 real-sample pass (worker, 2026-11-15), branch worktree-agent-a5d5741d2a4d53a47, 2 local commits cbfcd9f, e04a1e6, nothing pushed
- 25 of 25 untested targets got a real file from samples.ffmpeg.org and decode bit for bit against the reference ffmpeg 9.0.1 (WMV3, VC-1, SVQ3, VP6, VP6F, RV30, RV40, MJPEG-B, H.263+, WMA Pro, WMA Lossless, MP1, Cook, RA 2.0, QCELP, SIPR, ATRAC3, ATRAC3+, APE; containers IVR, xWMA, NSV, Vivo, SMJPEG demux). Table, URLs and notes: scripts/media3ext/bench/SAMPLES.md; run: run-sample-tests.sh <work> <samples dir>.
- Found: seeks back to the start read nothing on IVR, one RV40 RM, nsv/sample.nsv and all Vivo files (demuxer state, ffmpeg fails the same); fixed in Kotlin (FfmpegExtractor reopens once; Vivo unseekable) with tests. SND0.AT3 is ATRAC3+, not ATRAC3. nsv-deer-210: last 2 frames differ (truncated file).
- NEEDS tm5 (patch/AAR, not done): (1) ENABLED_DECODERS add g723_1 and siren (Vivo audio); (2) ffdemux.h open(): mpeg1video/mpeg2video with extradata_size 0, copy the first sequence header from the first video packet into extradata (mpeg_layer1_audio.mpg decodes 0 frames after any seek).
- Left: device pass; WMA Lossless sample is 6.3 MB (no smaller one found).

### W15 tm4 additions (worker, 2026-11-15), worktree agent-a597479c591ed9887, 2 local commits bd7376e, 4cc7748, nothing pushed
- Folded into tm4 (never released, TM_REVISION stays tm4): decoders g723_1 and siren (LGPL, both match the reference ffmpeg bit for bit on bush.viv and gclinton.viv); ffdemux.h open() copies the MPEG-1/2 sequence header from the first video packets (256 packets or 4 MB at most) into the stream's extra data and goes back to the data start; FfmpegFormats also hands that header to the device's MPEG-2 decoder as codec data. Kotlin mapping is generic (audio/x-ffmpeg-<decoder>), nothing else to map.
- Host: run-codec-tests 45/45, run-demux-tests 19/19 (mpeg1-system, vob included), run-sample-tests 58 passed 0 failed; mpeg_layer1_audio.mpg demux OK, decoded=[23,39] after a seek (was 0). demuxtest now fails an MPEG-PS video stream without extra data. samples.tsv: the two Vivo decoders no longer expected missing.
- AAR /home/dracu/projects/personal/TMPlayer/build/media3ext/nextlib-media3ext-1.11.1-0.16.0-tm4.aar 18,867,340 bytes sha256 6dd2b70371c8a3a48b9278eb8318596e5110c2fd5bd144845e771f97881ef6c7 (old one kept as .aar.before, 18,713,614; +153,726). Source archive build/nextlib-source/...-tm4.tar.gz 25,374,972 bytes sha256 e5cb0f20efb5b3753aa2e0fc7cfb5cc1cf1d268e027ef5a6466ba3e3ea4b6fb5.
- Left: device pass (Vivo sound, an MPEG-1 file seek on a TV); build-media3ext.sh broke at its last 2 lines when I edited it during the run (the AAR itself built), so I copied the AAR and wrote the stamp by hand.
