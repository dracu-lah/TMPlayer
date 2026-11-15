# Player reliability research: classes of video problems and one automatic remedy ladder (2026-11-16)

Ask (user, 2026-11-16): "make sure these encoder issues and stuttering, playing with audio only, these troublesome
issues are gone, I do not want to waste time figuring out how to fix each video individually."

So this file does not fix videos. It names the CLASSES of failure, says how each is detected with signals the
code can read, and designs ONE ladder that picks the remedy for the class, remembers it for the class (device
chipset + codec + profile + size + container), and never loops. Companion to `private/PLAYER-RELIABILITY-PLAN.md`
(PR0 to PR3, PR2b, L1 to L6). Research only: no source was changed.

Evidence tags used everywhere below:
- [V] read in primary source (code, release notes, docs) during this research, source linked in section 10.
- [A] anecdote: a user or issue report, not a measurement.
- [U] unverified: inference, memory, or something I could not reach. Do not build on it without a spike.

Licence discipline: Media3 is Apache-2.0. VLC's `modules/codec/omxil/mediacodec.c` and `modules/codec/avcodec/video.c`
carry LGPL-2.1-or-later headers [V]; VLC for Android as an app is GPLv2+ and "the VLC engine (LibVLC) for Android
is LGPLv2" per its README [V]. mpv's `player/video.c` and `video/decode/vd_lavc.c` carry LGPL-2.1-or-later headers
[V] while mpv as a whole is GPLv2+ unless built LGPL [U for the build switch]. Everything below is behaviour learned
by reading. No VLC, mpv or FFmpeg code is copied into this repository; implementations are written clean room from
the behaviour descriptions. Nothing closed was needed: the vendor decoders (`OMX.rk.*`, `c2.mtk.*`, Allwinner)
are closed binaries; where their internals matter I say so and stop (no decompiling).

---

## 0. The five classes that matter most (short answer)

1. **Picture never appears, sound plays** (audio only, black screen). Media3 raises no error when a video track is
   unsupported or the decoder starts and never outputs. Today only the static "no decoder at all" case is caught.
2. **Frozen picture, sound continues** (decoder stops returning output, often after a seek: Rockchip, Amlogic,
   MediaTek reports). No exception, no dropped-frame count. Nothing watches the output counters yet.
3. **Wrong picture with no error** (green, blocky, washed HDR, Dolby Vision profile 5 purple): the Allwinner H6 /
   Tanix TX6 class. A decoder that starts and draws garbage throws nothing, so `enableDecoderFallback` never fires.
4. **Stutter on a hardware decoder with few drops and no underruns**: render path, async queueing off below API 31,
   frame release, frame rate / refresh mismatch, CPU contention, slow source reads. Needs the L1 trace to split
   decoder from line.
5. **Software decode too slow, or a profile/level claim mishandled** (AV1 and HEVC Main10 on weak SoCs, DV P5/P7/P8,
   levels stamped too high): partly solved (DecodeJudge, LevelLenient, DolbyVisionBaseLayer), the gap is learning per
   class so the next video of the same kind starts on the right path.

Also in the doc: slow start from an MP4 with the index at the end, audio passthrough failures, interlaced and
variable frame rate, native codec crashes, and engine availability (16 KB pages, 32-bit TV).

---

## 1. What the app already does (so nothing is built twice)

Read from `app/src/main/java/com/tmplayer/player/` and `core/src/pureMain/` in this worktree [V].

| Piece | File | What it already covers | Gap for this research |
| --- | --- | --- | --- |
| Decoder order and fallback | `PlayerActivity.kt` ~4240-4430 | `NextRenderersFactory` (device decoder first, NextLib FFmpeg behind it, PREFER when "Prefer software video"), `.setEnableDecoderFallback(true)`, `LevelLenientVideoRenderer` | Media3 fallback only retries on an INIT exception (see 2.1) |
| One swap per track type | `DecoderSwitch.kt`, `FormatRoute.switchesByItself`, `PlayerActivity.switchDecoderByItself` ~9683 | On `DECODER_INIT_FAILED / QUERY_FAILED / DECODING_FAILED / FORMAT_EXCEEDS_CAPABILITIES / FORMAT_UNSUPPORTED / RESOURCES_RECLAIMED / AUDIO_TRACK_INIT_FAILED`: switch device <-> FFmpeg once per track type, never after the picture started, never to software for a picture too slow | Only reacts to thrown errors. Silent failures (freeze, garbage, black) never reach it |
| Engine selector | `core/.../player/EngineSelector.kt`, `KnownDevices.kt`, `EngineHistory`, `EngineCrashes` | Decision table: user choice, known device (Allwinner H6 -> VLC), history of ExoPlayer failures, 3D, one automatic switch per video, VLC-crash back-off | `afterFailure` only fires on `EngineFailure.Decoder` which is only thrown errors |
| Speed judgement | `core/.../data/DecodeJudge.kt`, `DecodeMemory`, `DeviceDecode.kt`, `HardwareDecoders.kt` | Hardware yes/no from the codec list (profile, level, size, rate), CPU score table, remembered result per picture kind | Remembers only "kept up in software". Not a general remedy memory |
| Watchdog | `core/.../player/PlaybackWatch.kt`, `PlayerActivity.sampleWatch` ~1366 | 12 s window after a 2 s settle: speed < 0.9, > 10% dropped, >= 3 underruns, late frames. Answer once, then offers a lighter or smoother copy | Offers a copy, never changes decoder or engine. Misses "no frames at all" (rendered and dropped both 0) |
| Stutter cause | `StutterVerdict.kt`, `PlaybackTrace.kt` (L1/L2 done) | DECODER_BOUND, NETWORK_BOUND, RENDER_OR_SYNC, THERMAL, MEMORY from read wait, underruns, drops, CPU | Verdict not yet an input to a remedy |
| Static "no picture decoder" | `PlayerActivity.pictureUnplayable` ~1096 | Video group exists, none selected, none supported -> status sheet instead of sound over black | Does not cover a SUPPORTED track whose decoder never outputs |
| Dolby Vision | `FormatRoute.kt`, `DolbyVisionBaseLayer.kt`, `Hdr10PlusMaskingCodecAdapter.kt` | P4/7/8 relabelled as HEVC base layer when the device would not show DV, P9 as AVC, P5 kept as DV (no base layer), MT8696 HDR10+ mask | Good. Needs a P5 end state (see C4) |
| Container help | `CuelessMatroskaSeek.kt`, `demux/FfmpegExtractors`, `StreamWarmup.kt`, `StreamIndex.kt` | MKV without Cues, ASF/RM/old via libavformat, warm-up of the MP4 index near the end | Warm-up exists, its hit rate is not measured |
| Buffer policy | `AheadFirstLoadControl.kt`, `PlayerActivity` buffer constants (15 s min, 50 s max, 1 s start, 4 s rebuffer) | Never stuck at a byte ceiling | `ProgressiveMediaSource` continue-loading interval not set (see C9) |
| Tunneling | `TunnelingPolicy.kt` | Auto/On/Off, one retry without tunneling on an error | Same: only on thrown errors |
| Crash exits | `app/.../data/CrashExits.kt` (ApplicationExitInfo), `EngineCrashes` | Native engine crash counting for VLC | Not keyed to decoder or class |
| Frame grab | `FrameSaver.kt` (PixelCopy) | Reads the video surface on API 24+ | Reusable for a green-frame sentinel |

Not present anywhere (grep, 2026-11-16) [V]: `forceEnableMediaCodecAsynchronousQueueing` or any async toggle,
`ContinueLoadingCheckInterval`, `onVideoCodecError`, a no-first-frame timer, an output-counter stall check, a custom
`MediaCodecSelector` that excludes a failed decoder name, a per-class remedy memory.

---

## 2. How the reference players behave (learned by reading, not copied)

### 2.1 Media3 1.11.1 (what we sit on)

- Fallback is init-only [V `MediaCodecRenderer.maybeInitCodecWithFallback`]: with `enableDecoderFallback` true all
  decoders for the MIME go into a queue and the next is tried only when `initCodec` THROWS. With it false only the
  first is tried. Failures chain via `DecoderInitializationException.fallbackDecoderInitializationException`;
  `diagnosticInfo` carries the `CodecException` diagnostic string; custom codes `NO_SUITABLE_DECODER_ERROR` (-50001)
  and `DECODER_QUERY_ERROR` (-50002). A decoder that initialises and then draws garbage or stalls never reaches this.
- Capability check is a claim, not a test [V `MediaCodecVideoRenderer.supportsFormat`, `MediaCodecInfo.isFormatSupported`]:
  the first decoder is asked `isFormatSupported` (MIME, profile and level, then size and frame rate from
  `VideoCapabilities`, with `PerformancePoint` coverage on API 29+). If it says no, other decoders are tried;
  if none say yes the track is `FORMAT_EXCEEDS_CAPABILITIES`; no decoder at all is `FORMAT_UNSUPPORTED_SUBTYPE`.
  The track selector keeps `exceedRendererCapabilitiesIfNecessary = true` by default [V `DefaultTrackSelector` line 1805],
  so a track that exceeds capabilities can still be selected when nothing else is. Chips that CLAIM a profile and
  cannot do it are the silent-failure source (the Media3 code carries per-device exclusions for exactly this, for
  example Main10 on `sailfish`/`marlin` [V `needsProfileExcludedWorkaround`]).
- An unsupported video track is simply left out and the audio plays [D troubleshooting page, "Why does content fail to
  play, but no error is surfaced"; our own comment at `PlayerActivity.pictureUnplayable`]. `EventLogger` shows
  `supported=NO_UNSUPPORTED_TYPE`.
- No output-stall watchdog exists in `MediaCodecRenderer` [V by reading: only `renderTimeLimitMs` for the render loop
  and `MAX_CODEC_HOTSWAP_TIME_MS = 1000` for a hot-swap]. A codec that accepts input and returns nothing is not an
  error to Media3.
- Late frames: output buffer dropped when more than 30 ms late and not the last buffer; drop-to-keyframe when more
  than 500 ms late; forced render when > 30 ms late and > 100 ms since the last render; decoder input dropped early
  with a 15 ms late threshold (default changed in 1.9.0) [V source + release notes].
- Codec errors: `CodecException.isRecoverable` -> `releaseCodec()` and the exception is flagged recoverable;
  `ERROR_RECLAIMED` -> `ERROR_CODE_DECODING_RESOURCES_RECLAIMED` [V]. Android docs: `isTransient` means retry later,
  `isRecoverable` means stop, configure, start again, `ERROR_RECLAIMED` means the codec is terminal and must be released [D].
- Async MediaCodec: the default is async only on API 31+, forceable from API 23 with
  `DefaultRenderersFactory.forceEnableMediaCodecAsynchronousQueueing()` [V `DefaultMediaCodecAdapterFactory` and docs].
  The docs: "Enabling it can reduce dropped frames and audio underruns", and the troubleshooting page names API 23 to
  30 with DRM or high frame rate [D]. Mixed evidence on TV boxes [A androidx/media #3405: did not help].
- Vendor workarounds are data in Media3: MediaTek `c2.mtk.{avc,hevc,vp9}.decoder` are released instead of flushed when
  rendering to a SurfaceTexture, `OMX.MTK.VIDEO.DECODER.HEVC` on `mcv5a` vertical-resolution rule, Fire OS
  `AFTA/AFTN/...` output-surface workaround, MiTV `API <= 28` boxes [V]. Amlogic, Rockchip and Allwinner are NOT in the
  video renderer or `MediaCodecInfo` workaround lists [V by grep]. They are ours to handle.
- Release-note items relevant here [V 1.8.0 to 1.11.1]: 1.9.0 late drop threshold 15 ms; Dolby Vision codec reuse
  disabled across profiles; 1.9.1 "playbacks of Dolby Vision files fail when attempting to use a fallback AVC or HEVC
  codec" fixed; 1.10.0 Dolby Vision profile 10 and dynamic scheduling; 1.10.1 AV1-based DV artifacts (#3153); 1.11.0
  "codec swallowing all samples if previously flushed before receiving input buffers", frame rate estimator fallback,
  unrecognised profile or level marked `EXCEEDS_CAPABILITIES`, dropped frames counted as skipped while joining,
  `setMaxEarlyUsThreshold`, remove EAC3-JOC fallback on Pixel (#3257); 1.11.1 Matroska Tracks after Clusters (#3377).
  Dolby Vision profile 7 -> HEVC fallback is on `main` (commits 23f3065 and 70e9447, issue #3486 closed 2026-10-30)
  and is referenced by 1.12.0-beta01 [V issue; release not confirmed]. Our `FormatRoute` already does it ahead of Media3.
- 16 KB: no entry in the Media3 release notes [V by search of the notes read].

### 2.2 VLC (libvlc 3 and 4 core, modules LGPL; VLC for Android app GPL)

- MediaCodec is one decoder module among others, priority 800, options `mediacodec` (default true), `mediacodec-dr`
  (direct rendering, default true), `mediacodec-audio` (default FALSE: audio is always decoded in software by
  avcodec), `mediacodec-tunneled-playback` (default false) [V `modules/codec/omxil/mediacodec.c`].
  Consequence: VLC never depends on a device audio decoder or on passthrough hardware for decoding. That is why a
  DTS or EAC3 track "just plays" in VLC where Media3 meets `AudioTrack` init failures.
- Hardware to software switch, exactly once per elementary stream [V]: if MediaCodec aborts BEFORE it has produced an
  output format (`b_aborted && !b_has_format`), `DecodeBlock` takes `goto reload`, creates the variable
  `mediacodec-failed` on the stream, and returns `VLCDEC_RELOAD`; `OpenDecoder` refuses to load again when that
  variable exists ("Fail if this module already failed to decode this ES"), so the core's module search lands on the
  software decoder. The core (`src/input/decoder.c`) resubmits the SAME block once, tagged
  `BLOCK_FLAG_CORE_PRIVATE_RELOADED`, and drops it rather than recurse a second time. So: one hop, never a loop.
- After an output format exists, an abort returns `VLCDEC_ECRITICAL`: the decoder's `error` flag is set, input is
  dropped until a flush, no module swap [V]. VLC is not magic for a mid-stream stall either.
- Interlaced input on a MediaCodec without the interlace quirk: `goto reload` to software [V] (comment: MediaCodec
  "can crash or be in an inconsistent state when decoding interlaced videos" before API 21, vendor dependent).
- The avcodec (software) path has its own pacing [V `modules/codec/avcodec/video.c`]: a frame is "late" when
  `display_date + threshold <= now`, threshold = half the next frame interval or 20 ms; `i_late_frames` counts
  consecutive late frames; after more than 10 late frames and more than 24 packets since the last on-time output it
  enters `FRAMEDROP_AGGRESSIVE_RECOVER`, which discards every non-I block until output is on time again ("more than N
  frames of late video -> dropping frame (computer too slow ?)"). Codecs that forbid dropping (`b_no_frame_drop`) are
  never dropped. Options and defaults [V wiki `Documentation:Modules/avcodec`]: `avcodec-hurry-up` on,
  `avcodec-skiploopfilter` 0, `avcodec-skip-frame` 0, `avcodec-error-resilience` 1, `avcodec-fast` off,
  `avcodec-threads` 0 (auto).
- User-facing hardware acceleration on Android has four modes (Automatic, Disabled, Decoding, Full) [A, 2014 patch on the
  videolan mailing list, may have changed]. A reporter on androidx/media #2090 found VLC "Decoding acceleration" adds
  `:no-mediacodec-dr :no-omxil-dr`: decode on the chip but copy frames out and render with GL instead of handing the
  codec the surface. That fixed jerky H.264 on a Rockchip box that "Full acceleration" also stuttered on [A, the
  reporter's reading]. This is the most useful VLC trick for us: **same hardware decoder, different output path.**
- `HWDecoderUtil` (2013 era, LGPL-2.1+) is a shipped device table: a blacklist keyed on `ro.product.board`
  (MSM8225, hawaii -> NONE) and a per-SoC preference [V, old fork copy]. Same idea as our `KnownDevices`, which confirms
  the approach but not any current table.
- Not verified: current VLC for Android option construction (`VLCOptions.kt` path 404 on the GitHub mirror, GitLab is
  behind a bot wall) and whether VLC 4 changed the reload mechanics.

### 2.3 mpv

- `--hwdec-software-fallback` default 3: fall back after N frames fail in a row, 1 means immediately [V options.rst].
- Start-up fallback keeps the first 32 packets (`hw_probing`, `num_sent_packets < 32`) and replays them into the next
  decoder so nothing is skipped; a failure while copying a frame back from the hardware surface forces the fallback
  at once (`hwdec_fail_count = INT_MAX - 1`) [V `vd_lavc.c`]. `force_fallback` walks the list of hwdec methods tried
  ("Attempting next decoding method after failure of X"), then software. Same idea as a ladder with a visited set.
- `auto` only uses a whitelist of hwdecs "known safe"; `mediacodec` is documented "not safe": it forces RGB conversion
  and may reduce 10-bit to 8; `mediacodec-copy` copies frames back to system RAM [V]. Again: copy-back path as the
  safer sibling of the surface path.
- Frame dropping: default `--framedrop=vo` drops late frames at output and "stops dropping if the effective framerate
  is below 10 FPS"; `decoder` mode "can produce unwatchable choppy output, or even freeze the display completely" [V].
  Lesson for us: do not make decoder-side skipping a remedy; and a drop policy needs a floor so it cannot drop
  everything.
- Video chain failure prints "Could not initialize video chain" [V string in `player/video.c`]. Whether mpv then keeps
  playing audio only is [U, not read]; mpv's own manual says it deselects the failing track in some paths.

### 2.4 FFmpeg

- `-hwaccel` "has no effect if the selected hwaccel is not available or not supported by the chosen decoder" [V ffmpeg
  doc]. libavcodec error concealment and `-err_detect` not read here [U]. FFmpeg is a library, the policy lives in the
  caller: VLC and mpv above are the policies worth copying (as behaviour).

### 2.5 Telegram and TDLib

- `downloadFile(file_id, priority 1-32, offset, limit, synchronous)`: "limit: bytes to download starting from offset
  before the download is automatically canceled, 0 for no limit"; "synchronous: return only after the download has
  succeeded, failed, been canceled, or a new downloadFile request with different offset or limit was sent" [V
  td_api.tl]. The last request replaces the previous one for that file (our ARCHITECTURE doc states one active download
  per file, last request wins [V repo doc], not re-verified in TDLib C++).
- `getFileDownloadedPrefixSize(file_id, offset)` returns the contiguous bytes already present from an offset;
  `localFile.downloaded_prefix_size` "only some prefix of the file starting from download_offset is ready to be read"
  [V td_api.tl]. This is the exact primitive for "is the moov here yet".
- `video.supports_streaming`: "True, if the video is expected to be streamed" [V td_api.tl]. Whether Telegram's server
  sets it from the faststart state of the upload (so false would mean moov at the end) is [U]; use it as a hint, not a rule.
- Telegram raw API `upload.getFile` has a `precise` flag "useful for example to stream videos by keyframes" [D search
  result]; TDLib hides it, [U] whether `offset` alignment matters for speed.

---

## 3. Symptom classes

Each table row is a variant of the class. DETECT lists signals the code can read now or with a small hook.
REMEDY is in order: stop at the first that works. `R0..R4` are rungs defined in section 4. Sources are numbered in
section 10.

### C1. No usable decoder (cannot start at all)

| Variant | Root cause | DETECT (exact signals) | REMEDY in order | VLC / mpv | Src |
| --- | --- | --- | --- | --- | --- |
| Init failed | Vendor decoder refuses the format although it listed it (profile, level, resources, secure flag) | `PlaybackException` `ERROR_CODE_DECODER_INIT_FAILED` (4001) with cause `MediaCodecRenderer.DecoderInitializationException`; read `.codecInfo?.name`, `.diagnosticInfo`, chain of `.fallbackDecoderInitializationException`; `AnalyticsListener.onVideoCodecError` | 1) Already: Media3 tries the next listed decoder (`enableDecoderFallback`). 2) Exclude that decoder NAME for this class (custom `MediaCodecSelector`) and retry R1. 3) R2 FFmpeg software if `DecodeJudge != SOFTWARE_TOO_SLOW`. 4) R3 engine. 5) sheet | VLC: `goto reload` + `mediacodec-failed` -> avcodec. mpv: next hwdec method, then software | 1, 2, 3 |
| No decoder listed | Chip has no decoder for MIME or profile (AV1 on old chip, VC-1, HEVC Main10 on an 8-bit chip) | `ERROR_CODE_DECODING_FORMAT_UNSUPPORTED` / `FORMAT_EXCEEDS_CAPABILITIES`, or track support `NO_UNSUPPORTED_SUBTYPE` / `NO_EXCEEDS_CAPABILITIES`; `MediaCodecUtil.getDecoderInfos(mime,...)` empty | R2 (FFmpeg has: h264 hevc mpeg2 vp8 vp9 av1 plus tm1..tm3 extras) if the verdict says it keeps up, else R3, else lighter copy / external app | Software avcodec always available in VLC | 1 |
| Level too high | File stamps level 5.1 on a 1080p picture, TV chip declares 4.1 | `FORMAT_EXCEEDS_CAPABILITIES` only because of the level in `Format.codecs` | Already solved: `LevelLenientVideoRenderer` relaxes the claim for <= 1080p HEVC/AVC. Keep, add a trace line when it fired | Kodi, Jellyfin ignore the level (code comment) | 4 |
| Resources reclaimed | Another app or codec instance took the hardware decoder (1 instance on many TV chips) | `ERROR_CODE_DECODING_RESOURCES_RECLAIMED`; `CodecException.errorCode == ERROR_RECLAIMED` (1101), `ERROR_INSUFFICIENT_RESOURCE` (1100) | Release everything we own (cast, mini player, thumbnail decoders, second player), wait 300 ms, retry the SAME rung once; then R2 | Not handled specially [U] | 1, 6 |

Edge: never retry on a decoder name already excluded; the excluded set is per class, not global.

### C2. Sound without picture

| Variant | Root cause | DETECT | REMEDY | VLC / mpv | Src |
| --- | --- | --- | --- | --- | --- |
| Video track silently unsupported | Track selector drops it (`NO_UNSUPPORTED_TYPE`/`SUBTYPE`) | `Tracks` has a video group (not cover art), none selected, none `isTrackSupported(.., true)`. ALREADY DETECTED (`pictureUnplayable`) | R2 (if FFmpeg has the codec and verdict fits) before showing the sheet; today it goes straight to the sheet | VLC loads software decoder; mpv tries next | 1, 5 |
| Decoder starts, never outputs a frame | Vendor bug (MediaTek HEVC black or frozen no error; MT8696 HDR10+ + DV RPU black screen) | `onRenderedFirstFrame` not fired within T1 after `STATE_READY` with audio playing; `videoDecoderCounters.renderedOutputBufferCount == 0` and `droppedBufferCount == 0` while `isPlaying` | New `FirstFrameWatch` (4 s hardware, 10 s software, +3 s after a seek) -> class-level hop: R1 (other decoder name / async flip / tunneling flip) -> R2 -> R3 | VLC: no format -> reload to software [only when the decoder aborts, not for a silent codec] | 2, 7 |
| Hardware decoder never signals end of stream | Some decoders (AV1, HEVC) never let video reach EOS: audio ends, screen black, playback never completes | Audio renderer ended, video renderer not, `Player` stuck in READY past `duration + 2 s` | End playback at audio end plus 2 s when the position is within 1 s of the duration (a third-party player does exactly this [A, NuvioTV PR]) | n/a | 8 |
| HLS/TS-style non-IDR or missing AUD | Extractor cannot find frame boundaries | Stuck buffering with audio only | Already set: `FLAG_ALLOW_NON_IDR_KEYFRAMES`, `FLAG_DETECT_ACCESS_UNITS` stays off (cost) | n/a | 5 |
| Video track disabled on purpose | Background audio or PiP hiding the picture | `videoHiddenUnseen` (already in code) | Do not count as failure | n/a | code |

### C3. Frozen picture, sound continues (stalled decoder)

| Variant | Root cause | DETECT | REMEDY | VLC / mpv | Src |
| --- | --- | --- | --- | --- | --- |
| Post-seek stall | Rockchip `OMX.rk.video_decoder.hevc` (RK356x, Android 11): after a flush the decoder emits about 14 buffers that are all decode-only or late, released unrendered, then returns nothing and the input queue fills [A, unconfirmed, closed vendor library] | Output counters flat: over 3 s of advancing audio position, `renderedOutputBufferCount` and `droppedBufferCount` and `skippedOutputBufferCount` all unchanged, state READY, not buffering. Sample after `onPositionDiscontinuity(SEEK)` for 5 s | R1: `seekTo` again to the same position once (some stalls clear), then release and rebuild the codec by `player.prepare()` at the same position (our existing prepare path), then R1 excluding that decoder name, then R2, then R3 | VLC: no recovery after format known (ECRITICAL). mpv: hwdec fail counter only if the API errors | 9, 10 |
| Freeze after 10 to 30 s during play | Amlogic / MediaTek HEVC decoder stall; "Seeking or reloading the stream temporarily fixes it" [A, Jellyfin Android TV #4103, Media3 1.11.1 on Dune HD, Onn 4K Pro, Fire TV Cube] | Same counter rule, no seek involved; also `getVideoDecoderCounters().maxConsecutiveDroppedBufferCount` high | Same ladder. A reload at the same position is the cheap first step, then exclude name | VLC "Decoding acceleration" avoids it [A #2090] | 11 |
| ABR/format switch freeze | Format change with codec reuse | Not our case (progressive files); still guard: discard codec reuse after Dolby Vision profile changes (Media3 1.9.0) | none | n/a | 1 |
| Surface problem | Wrong `Surface` after rotation, PiP, surface swap on devices with `setOutputSurface` workaround | Frozen rule plus `onSurfaceSizeChanged` just before | Re-attach surface, then ladder | n/a | 1 |

Counters note: `DecoderCounters` fields are written on the playback thread; the reader must call
`ensureUpdated()` before reading them [U, from the class documentation as remembered; the current code at
`PlayerActivity.sampleWatch` reads `renderedOutputBufferCount` without it].

### C4. Wrong picture with no error (green, blocky, garbage, wrong colour)

| Variant | Root cause | DETECT | REMEDY | VLC / mpv | Src |
| --- | --- | --- | --- | --- | --- |
| Green or blocky video on Allwinner H6 (Tanix TX6) | Vendor decoder or its output path emits corrupt frames; throws nothing [A, the plan's reporters; Kodi on H6 also needs special render settings, a user fixed stutter by changing the PRIME render method [A LibreELEC]] | NOTHING from MediaCodec. Options: (a) known-device table (`allwinner-h6` already routes to VLC); (b) `GreenFrameSentinel`: PixelCopy 8x8 grid of the video surface every 2 s in the first 20 s and 5 s after a seek, flag when >= 90% of samples are near (0,135,0) (zeroed YUV) for 3 consecutive samples [U feasibility on TV boxes: PixelCopy may return black on overlay planes]; (c) user's "Picture looks wrong" / "Report stutter" press, which immediately takes the next rung and records the class | (a) engine by table. (b)/(c): hop to R1 with "other output path": exclude the decoder name, then R2 software (H6 is quad A53: fits SD and 1080p H.264, not 4K), then R3 VLC with `--no-mediacodec-dr` style copy-out | VLC "Decoding acceleration" (copy-out, no direct render) [A]; mpv `mediacodec-copy` | 6, 12 |
| Dolby Vision profile 5 shown purple and green | P5 uses IPT-PQ-C2 with no HDR10 or SDR base layer | `FormatRoute.dolbyVisionOnly(format)` true AND `Display.HdrCapabilities` lacks DV | Today: kept as DV (a decoder that cannot tone-map it shows wrong colours). Plan: end state "this video needs a Dolby Vision display": offer lighter copy or external app, never loop; if the device HAS a DV decoder and display, play | Kodi, Jellyfin same limitation [A search] | 13 |
| Dolby Vision profile 7 sent to a profile 5 decoder (washed, grey) | Media3 picks the first DV decoder when none supports P7 | Already fixed by `FormatRoute.forDevice` relabelling to HEVC | keep; remove when Media3 1.12 ships the same (#3486) | n/a | 13 |
| HDR10 shown washed on SDR display | No tone mapping on that path | `Format.colorInfo.colorTransfer == ST2084/HLG` and display lacks HDR | Surface-level tone mapping is the platform's; document as a limit, not a remedy | mpv tone maps with its GPU path [U] | 4 |
| Deblocking / reference corruption after a hardware hiccup | Vendor decoder drops a reference after backpressure | Same as C3 plus sentinel | Reload at same position (flush rebuilds references from the next keyframe) | VLC avcodec error resilience 1 | 3 |

This is the class the plan's PR0 flagged as having no throw. The sentinel and the user press are the only signals.
If neither fires, only the known-device table protects the next user. That is why section 6 insists on the report
button feeding the class memory: ONE press by the first affected user must protect everyone on the same chipset on
the next feed refresh.

### C5. Stutter on a hardware decoder (drops without underruns)

| Variant | Root cause | DETECT | REMEDY | VLC / mpv | Src |
| --- | --- | --- | --- | --- | --- |
| Async queueing off below API 31 | Media3's default operates MediaCodec synchronously on API 23 to 30 (TV boxes on Android 9 and 10 such as the TX6 class) | `Build.VERSION.SDK_INT in 23..30` and `StutterVerdict` RENDER_OR_SYNC or DECODER_BOUND on a hardware decoder | R1a: `forceEnableMediaCodecAsynchronousQueueing()` for the class. Real change of the pipeline with no format change | n/a | 1, 14 |
| Frame rate vs refresh mismatch (judder) | 23.976 or 25 on a 60 Hz display, frame-rate matching off or unsupported | `Display.Mode` refresh list vs `Format.frameRate`; `onVideoFrameProcessingOffset` average spread without drops | Already: `FrameRateMatch`; rung R1c: flip match mode (system, app, off) for the class | mpv `video-sync=display-*` [U] | code |
| Late frames from CPU contention | Downloads, thumbnails, Sentry, other apps | trace `cpuPercent`, `onVideoFrameProcessingOffset` mean < -40 ms | U15: pause other downloads; lower thread priority of warm-up jobs | VLC drops non-I blocks only when more than 10 late | 15 |
| Tunneling quirks | Tunneled path bypasses app frame release and has per-chip bugs | Stutter or freeze only when tunneling on | R1b: flip tunneling for the class (`KnownDevices.tunneling` already a field) | VLC tunneled default off | 1 |
| Slow source reads look like decoder drops | TDLib window not ahead; high-bitrate file on slow line | `readWaitMs` share, `reopens`, underruns; `StutterVerdict` NETWORK_BOUND | Not a decoder remedy: C9 | n/a | code |
| Thermal or low memory | Throttled SoC, GC pressure on 1 GB | `PowerManager.getCurrentThermalStatus >= SEVERE`, trim levels | Lower buffer budget; suggest lighter copy; no engine hop | n/a | code |
| Generic "kept up in the first 12 s then not" | Content gets heavier (grain, complex scenes), bitrate peaks | `PlaybackWatch` is one-shot. Extend: a second window after minute 5 and after each seek | Same ladder but only OFFER (soft evidence) | VLC aggressive recover | code |

Only the first three are cheap, reversible and same-engine. They belong BEFORE any engine change.

### C6. Software decode too slow (the 1 GB Mi TV class, AV1, HEVC Main10, 4K)

| Variant | Root cause | DETECT | REMEDY | VLC / mpv | Src |
| --- | --- | --- | --- | --- | --- |
| No silicon for AV1 or 10-bit | CPU is the limit; "no decoder library makes that real time" (`DecodeJudge` docs, measured 1080p AV1 10-bit on 4x A53 at 1.4 GHz: about a third of real time) | `DecodeJudge.judge` = SOFTWARE_TOO_SLOW before play (copy chooser) and `PlaybackWatch` after 12 s | Lighter Telegram copy (CP80), else sheet text; do NOT hop to VLC or mpv for speed (same CPU) | Same limit; VLC avcodec would drop all non-I | 16 |
| High bitrate remux on weak line or CPU | Both | `Format.bitrate` or file size over duration, `DeviceDecode.cpu.score` | Verdict before play; warn, offer lighter copy | n/a | code |
| Hardware decoder claims support but cannot sustain size and rate | Capability claim is optimistic | Stutter verdict DECODER_BOUND on hardware | R1 (other decoder name), R2 only if the judge says it fits | n/a | 1 |

### C7. Audio failures

| Variant | Root cause | DETECT | REMEDY | VLC / mpv | Src |
| --- | --- | --- | --- | --- | --- |
| Passthrough AudioTrack will not open | AC3, EAC3, DTS, TrueHD to a receiver or TV the platform says it supports and does not | `ERROR_CODE_AUDIO_TRACK_INIT_FAILED` (5001) naming a compressed format; `AudioSink.InitializationException` | Already: switch to FFmpeg audio (PCM). Add: remember passthrough OFF for this output device class (HDMI vs Bluetooth id) | VLC decodes audio in software, passthrough optional | 1, 17 |
| No decoder for DTS or TrueHD | Android has no platform DTS decoder; Media3 FFmpeg ext has `dca`, `mlp`, `truehd` | track `NO_UNSUPPORTED_TYPE` | FFmpeg audio (NextLib has them) | same | 5 |
| Audio ends, video continues or the reverse | Wrong durations in container, EOS not reached | see C2 EOS | End at the shorter track plus 2 s | n/a | 8 |
| Underruns | Output buffer too small, CPU contention | `onAudioUnderrun` (already counted) | Bigger AudioTrack buffer for the class (audio sink builder) | n/a | code |
| Bluetooth latency, A/V offset | Output latency not compensated | Trace `audio path` field (L1) | User offset control exists (check `en.json`) | VLC audio desync option | code |
| Audio-only mis-protection | A bad hop leaves us on audio with no picture | After any hop: FirstFrameWatch re-armed. If still no picture, STOP (no second hop), show sheet | n/a | n/a | 1 |

### C8. Slow start and slow seek

| Variant | Root cause | DETECT | REMEDY | VLC / mpv | Src |
| --- | --- | --- | --- | --- | --- |
| MP4 with the index (moov) at the end | `Mp4Extractor` reads `ftyp`, hits `mdat`, and when `mdat` is large it asks for a seek past it (`positionHolder`, `seekRequired`) to find `moov` [V `Mp4Extractor.readAtomPayload`], then reads samples from the start of `mdat` again [U, inferred from the extractor flow and our ARCHITECTURE note "head, tail, head"]. With TDLib's "one download, last request wins" this is head, tail, head: two window restarts | First `TdDataSource` open at offset 0 followed by an open near EOF; time to READY > 4 s; `StreamWarmup` log shows an index range or not | Existing `StreamWarmup` reads the tail index. Add: keep the moov bytes in memory and serve them to the extractor so the second head read is not preceded by a window restart; use `getFileDownloadedPrefixSize` to skip waiting | VLC reads the index the same way (demux seeks to tail on non-faststart) [U not read]; both cannot avoid the tail read | 18, 19 |
| `supports_streaming = false` | Upload was not faststart | `video.supports_streaming == false` | Treat as "index probably at the end": run warm-up before opening, show "Preparing" not a spinner | n/a | 20 |
| MKV without Cues or Cues after Clusters | Muxer wrote them last or not at all | Existing `CuelessMatroskaSeek`; Media3 1.11.1 fixed "Tracks placed after clusters" (#3377) | Already handled | n/a | 1 |
| Long keyframe spacing | Seek lands far from target, decode-and-discard until target | seek-to-READY time > 2 s with few cached bytes | Seek parameters: `CLOSEST_SYNC` while dragging, `EXACT` on release (already in code lines 4565, 7902) | VLC seeks to key frames | 5 |
| Old containers (AVI, ASF, RM, PS) | No Media3 extractor | Already `FfmpegExtractors` | Already | n/a | code |
| Low storage mode: nothing starts (user, 2026-11-16, phone AND TV, not diagnosed) | Shared mode logic, not a decoder | FirstFrameWatch will fire with zero bytes: the ladder must classify it SOURCE, not DECODER, and not hop engines | Out of ladder scope: fix at `startLowStorageIfOn` / `LowStorageStreams.allow` per the plan | n/a | plan |

### C9. Stream starvation (looks like stutter, is the line or Telegram)

| Variant | Root cause | DETECT | REMEDY | VLC / mpv | Src |
| --- | --- | --- | --- | --- | --- |
| High bitrate file, slow download | Bitrate > line | `readWaitMs` share >= 10% plus underruns (verdict NETWORK_BOUND) | Download first, lighter copy, pause other downloads (U15) | VLC network cache | code |
| Loader does not keep the buffer growing | Media3 progressive loader checks `LoadControl` every 1 MB by default | `isLoading` true, buffer flat, CPU saturated (maintainer: "CPU starvation") | Set `ProgressiveMediaSource.Factory.setContinueLoadingCheckIntervalBytes` to 10 to 20 MB on weak CPUs [A maintainer advice, #3405, no measured result] | n/a | 21 |
| FLOOD_WAIT, far datacenter | Telegram | existing `StalledSheet`, `FileDatacenter` | Existing | n/a | code |

### C10. Interlaced, variable frame rate, odd rates

| Variant | Root cause | DETECT | REMEDY | VLC / mpv | Src |
| --- | --- | --- | --- | --- | --- |
| Interlaced (DVB recordings, old DVDs) | Hardware decoders may or may not deinterlace; Media3 has no deinterlacer [U, found no issue stating either way]. A Rockchip report: DVB-T H.264 TS freezes every few seconds on ExoPlayer, fine in VLC "Decoding acceleration" [A #2090] | Field order flag is not in `Format`. Via our FFmpeg demux (`FfmpegExtractor`) the stream's `field_order` is available [U field not exposed yet]. Fallback heuristic: MPEG-2 or H.264 at 720x576, 720x480, 1920x1080 with 25, 29.97 fps in TS or MPG | R1 other decoder name, then R3 VLC (has deinterlace filters) | VLC reloads to software when MediaCodec lacks the interlace quirk [V] | 22, 8 |
| Variable frame rate (phone clips, screen records) | `Format.frameRate` unset or wrong, frame release jitter | `Format.frameRate == NO_VALUE`; `FrameRates.pick` already derives one | Do not run frame-rate matching on VFR; Media3 1.11.0 "Fall back to the frame rate estimator" helps [V] | mpv timestamps based | 1 |
| 3D | Side-by-side or top-bottom needs our pipeline | EngineSelector rule 1 keeps ExoPlayer | Already | n/a | code |

### C11. Native codec crash kills the process

| Variant | Root cause | DETECT | REMEDY | Src |
| --- | --- | --- | --- | --- |
| SIGSEGV in `memcpy` from `SampleDataQueue.readData` (HX72) | Codec input buffer unmapped under the playback thread (codec2 service died or reclaimed) [A, draft `private/HX72-media3-issue-draft.md`, one event] | Next launch: `ApplicationExitInfo` reason `REASON_CRASH_NATIVE` with a "was playing class K" marker written before play | Next start of class K begins at R2 (software) or R1 excluding that decoder name; two crashes => R3; never loop (marker cleared after one clean 60 s play) | 23 |
| Crash loop on a bad file | Video, not engine | Same file crashed twice | Do not auto-play on open for 24 h, sheet with Report | 23 |

`CrashExits.kt` and `EngineCrashes` exist for the VLC case. Extend the key from "engine" to "class".

### C12. Platform and packaging

| Variant | Root cause | DETECT | REMEDY | Src |
| --- | --- | --- | --- | --- |
| 16 KB page size | Native libs aligned to 4 KB fail to load or run in compat mode on 16 KB kernels. Play: apps targeting Android 15+ must support 16 KB on 64-bit; updates blocked from 2027-02-01 [V developer.android.com] | Build gate: `zipalign -c -P 16 -v 4 app.apk` and `llvm-objdump -p lib.so | grep LOAD` shows `align 2**14`; runtime: `UnsatisfiedLinkError`, `adb shell getconf PAGE_SIZE` returns 16384 | Add the `zipalign -c -P 16` check and an ELF align check for NextLib FFmpeg, dav1d, libVLC, libmpv to CI; r28+ NDK builds are aligned by default, r27 needs `-Wl,-z,max-page-size=16384`. RELRO end must be 16 KB aligned or the app SIGSEGVs at runtime [V] | 24 |
| libVLC 16 KB | VideoLAN has a tracker issue on 16 KB support (vlc-android #3242) [U not readable, bot wall] | Spike PR2 | Verify before shipping libVLC on Play | 25 |
| 32-bit TV or wrong ABI | `UnsatisfiedLinkError` | `EngineAvailability` already | Hide engine (exists) | code |

---

## 4. The remedy ladder

### 4.1 Rungs

One rung changes ONE thing, so a result can be attributed.

| Rung | Name | What changes | Cost | Reversible in the same video |
| --- | --- | --- | --- | --- |
| R0 | Default | Device decoder, Media3 choice, our relabels (LevelLenient, Dolby Vision base layer) | none | n/a |
| R1 | Same chip, other path | EXACTLY ONE of: (a) async MediaCodec queueing flipped from the default (API < 31 on, API >= 31 off), (b) tunneling flipped, (c) next listed decoder for the MIME, the failed NAME excluded through a wrapping `MediaCodecSelector`, (d) output-copy path where an engine offers it (VLC `--no-mediacodec-dr` class, mpv `mediacodec-copy`; only inside R3) | a re-prepare at the same position (about 1 s) | no, the visited set forbids it |
| R2 | App software decoder | NextLib FFmpeg video renderer for this track (our `DecoderSwitch.apply`), only if the format is in `FfmpegLibrary.supportsFormat` and `DecodeJudge != SOFTWARE_TOO_SLOW` | CPU, battery | no |
| R3 | Other engine | libVLC (built, off by default, never run on a device), then libmpv when PR2b delivers it; one loaded at a time; position, tracks, speed, subtitles carried | a full engine start (2 to 3 s) | no |
| R4 | Ask the person | Status sheet: lighter Telegram copy, download first, open in another app, Send logs, Try another player (manual) | a decision | yes, manual only |

Audio has its own short ladder (A0 device decoder or passthrough, A1 FFmpeg PCM, A2 passthrough off with downmix, A3 engine
hop which brings VLC's always-software audio). It runs independently so a bad audio path never moves the picture.

### 4.2 Budget and invariants (the "never loops" rules)

- At most TWO automatic hops per video: one decoder-level hop (R1 or R2) and one engine-level hop (R3). This is the
  current behaviour made explicit (`decodersSwitched` once per track type, `EngineSelector.SWITCHES_PER_VIDEO = 1`).
- A visited set of `(engine, rung, decoderName, asyncFlag, tunnelFlag)`: a hop to a visited element is refused.
- No hop in the first 1.5 s after another hop (the new path needs its probation) and none when the evidence is `SOURCE`
  (bytes not arriving): another decoder waits just as long.
- Soft evidence (stutter verdict, late frames) only OFFERS (a one-tap chip with the reason). Hard evidence (a thrown
  decoder error, no first frame, frozen output, green frames) hops by itself. The user can set "Never switch by itself".
- The user's choice always wins (EngineSelector rule 2). "Report stutter" is itself hard evidence for the class.
- Every hop writes one trace line and one `RemedyMemory` entry.
- The ladder is a pure class (`RemedyLadder`) with no Android types, so a table test and a property test cover it.

### 4.3 State machine

```
            +-------------------------------------------------------------+
            |  PLAN (before open)                                         |
            |  in: class key K, KnownDevices, RemedyMemory[K], DecodeJudge |
            |  out: start rung = memory[K].working ?: KnownDevices ?: R0   |
            +------------------------------+------------------------------+
                                           v
   OPENING --(STATE_READY)--> FIRST_FRAME_WAIT --(onRenderedFirstFrame)--> PROBATION --(12 s clean)--> STABLE
      |                           |                                        |                           |
      | error(hard)               | T1 expires (4 s hw / 10 s sw)          | frozen / green / error    | frozen / error
      v                           v                                        v                           v
   CLASSIFY <---------------------+----------------------------------------+---------------------------+
      |   (SOURCE? -> stay, show stall card, no hop)
      |   (budget left and an applicable unvisited rung?)
      |        yes -> HOP(rung): write memory(K, rung, "tried"), re-prepare at same position, tracks, speed -> OPENING
      |        no  -> SHEET (R4), write memory(K, "exhausted")
   STABLE --(seek)--> SEEK_PROBATION (5 s, frozen rule only) --(ok)--> STABLE
   STABLE or PROBATION --(60 s clean play on rung r)--> memory(K, r, "worked"), crash marker cleared
   SOFT evidence (stutter verdict) in PROBATION or STABLE --> OFFER chip (one per video), no hop
```

Class-level learning, the point of the whole design:
- Class key `K = (chipset token, SDK, mime family, profile bucket, bit depth, size bucket (480/720/1080/1440/2160),
  fps bucket (<=30 / >30), container family, audio codec)`. Not a title, not a file id. `DecodeMemory.keyOf` and
  `KnownDevices.chipsetMatches` already do half of this.
- `RemedyMemory[K]` = `{ working: Rung?, failedRungs: set, counts, lastSeenVersion, lastSeenDay }`.
  Next video with the same K starts at `working` directly (no 4 s wait, no stutter). That is how "I do not want to
  figure out each video" becomes true.
- Hysteresis against loops across videos: a rung is demoted only after 2 failures on different videos; a failed rung
  is retried at most once per app version (one probe per K after an update). Counts only go up, like `EngineHistory`.
- Same record, anonymised, goes in the Send logs bundle and in the opted-in `StutterReport`: a weekly list of the top
  K by hop count is the "known-bad list" source, and `KnownDevices` can be refreshed from the feed with it. A K that
  hits 3 distinct installs becomes a feed entry (still untrusted input on the client: garbage test).

### 4.4 Which rung first, per class

| Evidence | First hop | Then | Last |
| --- | --- | --- | --- |
| Thrown init or decoding error | R1c (exclude failed name) | R2 | R3, R4 |
| No decoder for the format | R2 | R3 | R4 (lighter copy) |
| No first frame, audio playing | R1c | R1a or R1b by SDK | R2, R3, R4 |
| Frozen output (counters flat) | reload same position (counts as R1 "re-prepare" once) | R1c | R2, R3, R4 |
| Green or garbage frames (sentinel or user) | R1c | R2 if fits, else R3 copy-out | R4 |
| Stutter verdict, hardware, SDK < 31 | OFFER R1a (auto only on a second video of class K) | R1b | R2 or R3 |
| Stutter verdict, software too slow | R4 lighter copy (no hop) | n/a | n/a |
| NETWORK_BOUND | no hop; C9 remedies | n/a | n/a |
| Audio track init failed | A1 | A2 | A3 |
| Previous run native crash in class K | start at R2 (or R1c) | R3 | R4 |

---

## 5. New detectors (specification)

All are pure counters fed once a second by the sampler that already exists (`sampleWatch`), so they cost nothing
extra on the 1 GB stick. They stop when the verdict is final and are re-armed after a seek or a hop.

1. `FirstFrameWatch` (pure, core): inputs `readyAtMs`, `firstFrameAtMs?`, `audioPlaying`, `buffering`, `sourceWaiting`,
   `software`. Fires `NO_FIRST_FRAME` when READY has held `T1` (4 s hardware, 10 s software, +3 s after a seek)
   with audio playing, not buffering, not source waiting, and the video is not hidden on purpose. Refuses on cover art.
2. `FrozenOutputWatch` (pure, core): per second `positionMs`, `playing`, `rendered`, `dropped`, `skipped` totals.
   Fires `FROZEN` when position advanced >= 3 s with all three totals unchanged, not buffering, not paused, speed > 0.
   Static pictures still render, so the rendered counter keeps rising: no false positive on a still scene [U, verify
   on a real still-frame video]. Rate-limited, never fires twice for the same hop.
3. `GreenFrameSentinel` (app, reuses `FrameSaver` PixelCopy): 8x8 grid, 2 s period for 20 s then 5 s after a seek,
   fires `GREEN_OUTPUT` when >= 90% of samples fall in a tight box around (0,135,0) for 3 samples in a row. A BLACK
   rule is NOT proposed: films open on black. Not on secure surfaces. [U: PixelCopy on TV box overlay planes can return
   black or fail; a spike on the Tanix TX6 itself is mandatory before this ships.]
4. `HardwareOutputWatch` (existing, extended): add a second `PlaybackWatch` window after minute 5 and after each seek, soft
   evidence only.
5. `AudioPathWatch`: `AudioSink` init failures and write failures from `AnalyticsListener.onAudioSinkError`;
   already partly covered by the error switch.
6. `SourceStall` (existing `OpeningStall`, `StalledSheet`): used by `CLASSIFY` to refuse a decoder hop.

---

## 6. What to log (for "Report stutter" and for the class memory)

Existing (L1, [V `TraceSample`]): `atMs, intervalMs, droppedFrames, underruns, bufferAheadMs, bitrateKbps, decoder,
hardware, readWaitMs, reopens, tdSpeedBps, cpuPercent, memoryLevel, thermal, engine`. Add, all scrubbed, no titles,
no chat names, file id hashed:

| Group | Field | Why |
| --- | --- | --- |
| Ladder | class key K, current rung, visited set, hop count, each hop: time, evidence code, from, to, result after 12 s | The only way to see a ladder that misbehaves |
| Decoder | `MediaCodecInfo.name`, `isHardwareAccelerated`, `isSoftwareOnly`, `isVendor` (API 29), `isAlias`, `getMaxSupportedInstances`, supported profile levels for the MIME, `VideoCapabilities.getSupportedPerformancePoints` (API 29), `areSizeAndRateSupported` result for this picture, `getAchievableFrameRates` | Tells a wrong claim from a real limit |
| Codec output | `MediaCodec.getOutputFormat`: `KEY_COLOR_STANDARD/TRANSFER/RANGE`, `KEY_WIDTH/HEIGHT`, `crop-*`, `KEY_FRAME_RATE`, `KEY_PIXEL_FORMAT` if present | Green frames and wrong colours show here |
| Counters | all `DecoderCounters` fields once at the end and at every hop: rendered, dropped, skipped, `maxConsecutiveDroppedBufferCount`, `droppedToKeyframeCount`, `totalVideoFrameProcessingOffsetUs`, `decoderInitCount`, `decoderReleaseCount` | Frozen versus slow |
| Timings | open to READY, READY to first frame, seek to READY, seek to first frame after seek, decoder init duration (`onVideoDecoderInitialized`) | Start class C8 |
| Format | container MIME, codecs string, width x height, fps declared and derived, bitrate declared and measured, bit depth, color info, DV profile, `field_order` when known, audio codec, channels, passthrough on/off | The `ffprobe` PR0 asks for, without a file |
| Source | `supports_streaming`, moov position (head or tail), warm-up hit or miss, `downloaded_prefix_size` at first read, number of window restarts, TDLib priority, other downloads running | C8 and C9 |
| Device | `Build.SOC_MODEL/HARDWARE/BOARD/MODEL/DEVICE`, SDK, vendor API level, 32/64-bit app, `isLowRamDevice`, memory class, trim level, thermal status, battery saver | Class key and quirk table |
| Display | supported modes and refresh rates, HDR types, DV support, current mode, frame rate matching state, surface type (SurfaceView or TextureView) | Judder and HDR |
| Audio | `AudioCapabilities` encodings, sink output type, buffer sizes, underruns, Bluetooth | C7 |
| Crash | last `ApplicationExitInfo` reason and the class marker | C11 |

Rules: ring buffer (existing limits), a "mark" press keeps the last 60 s (L3), and the end-of-session verdict (L2) now
includes the rung history. The Send logs screen shows exactly this and asks first (L5).

---

## 7. Tests to write (machine first)

Pure core tests (JVM, fast):
1. `RemedyLadderTest`: a table of (class, evidence sequence) -> hop sequence; invariants as a property test over random
   event sequences: terminates, never revisits an element, hop count <= 2, no hop on SOURCE, a manual choice is never
   overridden, soft evidence never hops, after exhaustion only the sheet remains.
2. `RemedyMemoryTest`: promotion after one clean 60 s, demotion after two failures on two videos, retry once per version,
   counts capped, stale entries expire, encode and decode round trip.
3. `FirstFrameWatchTest`, `FrozenOutputWatchTest`: counter sequences for normal play, still scene (rendered keeps rising),
   pause, buffering, seek, speed 0.25x and 2x, a gap in samples (app away), a decoder re-created (counters reset).
4. `StutterVerdictTest` extension: rung history in the verdict text; `PlaybackWatch` second window.
5. Garbage tests (`GarbageInputTest`): `RemedyMemory` file, the known-device feed with the new fields, the trace reader
   with the new fields, the Send logs bundle reader. Truncated, wrong-typed, huge, random bytes.

Fake codec failures (instrumented or Robolectric, cheap):
6. A `MediaCodecSelector` fake that returns a decoder whose init throws: expects `DECODER_INIT_FAILED`, then the
   ladder hop to the next name, then R2. Robolectric's `ShadowMediaCodec` can fake a decoder and its output; Media3's
   own tests use it for `MediaCodecAdapter` behaviour [U whether our dependency set already has Robolectric; check].
7. A `MediaCodecAdapter.Factory` wrapper (we already wrap one for HDR10+) that: (a) swallows output (frozen), (b) never
   outputs (no first frame), (c) throws `CodecException`-like in `dequeueOutputBufferIndex` with
   `isRecoverable` true and false, (d) injects `ERROR_RECLAIMED`. Drive the real `ExoPlayer` with a fake
   `MediaSource` of tiny AVC samples. This is the one place the Media3 renderer, not our mock, meets the ladder.
8. D-pad focus test for every new card or chip (`performKeyInput`, `assertIsFocused()`), per the repo rule; preview
   screenshot tests phone, TV, tablet for "Try another player" and the offer chip.

Generated media (host `ffmpeg`, as `scripts/media3ext/bench` already does) for the emulator and the device matrix
(`PLAYBACK-TEST-MATRIX.md` style):
9. HEVC Main10 1080p, HEVC level 5.1 on 1080p, AV1 1080p 10-bit, VP9, H.264 interlaced (`-flags +ildct+ilme`),
   H.264 High 10, VFR (`setpts` jitter), MP4 without faststart, MKV with Cues removed, truncated MP4 and MKV, DTS and
   EAC3 and TrueHD audio, 5.1 AAC, a file with no audio. Dolby Vision samples cannot be made with ffmpeg alone [U:
   needs `dovi_tool` and an encoder; use public test streams or the user's own].
10. A "poisoned decoder" device test: a debug-only switch that makes the device decoder return frozen output for class K
    so the whole loop is exercised on the real TV stick and the idle CPU after the hop is read from `/proc/<pid>/stat`.
11. Scale: the ladder adds no TDLib call; assert that with `SyntheticAccount` Home still loads in the existing budget.

Device checks left for the user (not machine-testable here): the Tanix TX6 itself (C4 sentinel feasibility, VLC path),
a Rockchip RK356x box (C3), an Amlogic box (C3), a 16 KB device or emulator image.

---

## 8. Concrete tasks for workers

Conventions: commit locally section-wise, no Co-Authored-By trailer, no em or en dashes, new text only in `en.json`
with sorted keys, no langsync, `JAVA_HOME=/home/dracu/.jdks/jdk-21.0.12+8`, `--max-workers=3`, one Gradle log file
per run. `PlayerActivity.kt` is 10,858 lines: every task below puts logic in NEW files and leaves only a hook line
in `PlayerActivity`. Header format as in the plan: model, depends, files.

### Group A: pure core (no Android types), parallel safe

- **RL1 RemedyLadder state machine**. model: opus high. depends: none.
  files: new `core/src/pureMain/kotlin/com/tmplayer/player/RemedyLadder.kt`, `RemedyEvidence.kt`, test
  `core/src/jvmTest/kotlin/com/tmplayer/player/RemedyLadderTest.kt`.
  Done when: sections 4.1 to 4.4 are encoded, the property test of 7.1 passes, a decision table covers every row of
  4.4, `EngineSelector.afterFailure` is called from it for the R3 hop (no second engine policy).
- **RL2 RemedyMemory and class key**. model: sonnet high. depends: RL1 types.
  files: new `core/.../data/RemedyMemory.kt`, `RemedyClassKey.kt`, tests, `GarbageInputTest.kt` line, reuse
  `DecodeMemory.keyOf`. Done when: 7.2 and the garbage line pass; the stored form is a bounded string like
  `EngineHistory.encode` (cap entries at 200, keys at 64 chars).
- **RL3 FirstFrameWatch and FrozenOutputWatch**. model: sonnet high. depends: none.
  files: new `core/.../player/FirstFrameWatch.kt`, `FrozenOutputWatch.kt`, tests (7.3).
- **RL4 KnownDevices feed fields**. model: sonnet medium. depends: RL2.
  files: `core/.../data/KnownDevices.kt`, `KnownDevicesTest.kt`, `GarbageInputTest.kt`. Add per entry: `async`, `tunneling`
  (exists), `excludeDecoders` (names), `startRung`, `note`. Bad entries skipped, rest kept, shipped list on garbage.
  Done when: the Allwinner H6 entry carries `excludeDecoders`/`startRung` only after PR0 data says what works.

### Group B: Android wiring (files do not overlap with A)

- **RL5 Decoder policy hook**. model: opus high (the Media3 part is the risky part). depends: RL1.
  files: new `app/.../player/DecoderPolicy.kt` (a wrapping `MediaCodecSelector` that drops excluded names, and an
  async/tunnel flag set), `PlayerActivity.kt` (around lines 4240 to 4430 only: pass the selector and
  `forceEnableMediaCodecAsynchronousQueueing()` from the policy), `ExoEngine.kt`.
  Done when: a unit test with a fake selector shows an excluded name is never offered, `enableDecoderFallback` stays on,
  and the decision is logged at build time of the player.
- **RL6 RemedyRunner (the glue)**. model: opus high. depends: RL1, RL3, RL5.
  files: new `app/.../player/RemedyRunner.kt` (owns the sampler tick, builds evidence from `AnalyticsListener`, calls the
  ladder, performs a hop: save position, tracks, speed, subtitles through the existing resume state, re-prepare);
  `PlayerActivity.kt` hook lines in `sampleWatch`, `onPlayerError`, `onPositionDiscontinuity`, the listener at ~4595.
  Done when: fake-codec tests 7.6 and 7.7 pass for init failure, no first frame, frozen output and recoverable error; one
  automatic hop and one engine hop at most; idle CPU unchanged on the stick (read `/proc/<pid>/stat`).
- **RL7 GreenFrameSentinel spike then build**. model: sonnet high. depends: RL1.
  files: new `app/.../player/GreenFrameSentinel.kt` (reuses `FrameSaver` PixelCopy), spike note in this file.
  Done when: on the Tanix TX6 (user) or the emulator it reports whether PixelCopy returns real pixels on the video
  surface; if not, the task ends with "not feasible" and the user press (RL10) is the only C4 signal.
- **RL8 Audio ladder and passthrough memory**. model: sonnet high. depends: RL1.
  files: new `app/.../player/AudioPolicy.kt`, `PlayerActivity.buildAudioSink` region only, `DecoderSwitch.kt` (reuse).
  Done when: an `AUDIO_TRACK_INIT_FAILED` on class K makes the next video of K start with FFmpeg PCM (memory), tested with
  a fake sink.
- **RL9 MP4 index in memory and warm-up measurement**. model: sonnet high. depends: none.
  files: `core/.../player/StreamWarmup.kt`, `TdByteWindow.kt`, `app/.../player/TdDataSource.kt`, `LoopbackStream.kt`.
  Done when: seek matrix item 4 (index at the end) shows no window restart between the tail read and the first head
  read, with `getFileDownloadedPrefixSize` used to avoid waiting; `ProgressiveMediaSource.setContinueLoadingCheckIntervalBytes`
  set for low-end devices behind a flag, measured on the 1 GB stick before default-on.
- **RL10 "Report stutter" and "Picture looks wrong" presses feed the ladder**. model: sonnet medium.
  depends: RL6. files: player menu, `PlayerMorePanel.kt`, `PlayerActivity.kt` hook, `en.json`.
  Done when: one press records hard evidence for class K (C4 variant), takes the next rung once, and keeps the L3 60 s
  window; D-pad reachable on TV; works while a cast session is not running (not applicable on cast).

### Group C: logging, reports, docs, build

- **RL11 Trace fields and bundle**. model: sonnet medium. depends: RL6.
  files: `core/.../player/PlaybackTrace.kt` (version bump, reader tolerant to old lines), `StutterReport.kt`,
  `LogExport.kt`, `LogActions.kt`, garbage tests. Done when: every field of section 6 appears or is explicitly dropped,
  and the bundle reader survives garbage.
- **RL12 Decoder facts collector**. model: sonnet medium. depends: none.
  files: new `app/.../player/DecoderFacts.kt` (the `MediaCodecInfo` and `getOutputFormat` fields of section 6),
  `DecoderLister.kt` (reuse). Done when: a Send logs bundle on the emulator lists decoder names with hardware flags.
- **RL13 16 KB gate**. model: haiku low. depends: none.
  files: `scripts/` (a `check-16kb.sh` running `zipalign -c -P 16 -v 4` and an ELF align scan), `docs/BUILDING.md`.
  Done when: the script fails on a 4 KB aligned `.so` and passes on the release APK; wired to the release checklist
  in `docs/RELEASING.md`. libVLC and libmpv packages checked before PR2 and PR2b ship.
- **RL14 Docs**. model: haiku low. depends: RL6.
  files: `docs/ARCHITECTURE.md` (the ladder, rungs, class memory), `docs/BUILDING.md`, site FAQ only after the user
  approves wording ("video", never "movie").
- **RL15 Device pass**. model: user. files: `private/PLAYBACK-TEST-MATRIX.md` rows for the ladder: Tanix TX6, an RK356x,
  an Amlogic box, the 1 GB Mi TV, a phone; the seek matrix after RL9.

Order that fits the plan: RL3 and RL1 and RL2 in parallel, then RL5, RL6, then RL8 to RL12, RL13 any time, RL7 as a
spike as soon as the TX6 is reachable. PR0's data collection (device logs) feeds RL4's content, not its code.

---

## 9. Could not verify, or stopped on purpose

- Any vendor decoder internals (`OMX.rk.*`, `c2.mtk.*`, Allwinner Cedrus/VE, Amlogic `awesome`): closed binaries, not
  decompiled. Everything about them is from issue reports [A].
- Whether the Tanix TX6 picture fault is the decoder, the HDMI/render path, or a firmware bug. No data from that device
  yet (PR0). The ladder is designed so the answer is not needed up front.
- VLC for Android's current option set and its device tables: `code.videolan.org` is behind a bot wall and the GitHub
  mirror paths I tried returned 404. What I read: VLC core modules (mediacodec.c, avcodec video.c, decoder.c) via the
  GitHub mirror, the avcodec option wiki, and a 2013 era `HWDecoderUtil` from a fork.
- VLC 4 specifics, and whether libVLC exposes `--no-mediacodec-dr` through the Java binding in the version we built.
- Whether mpv continues audio only after "Could not initialize video chain".
- `DecoderCounters.ensureUpdated()` requirement, and the exact value of `Mp4Extractor.RELOAD_MINIMUM_SEEK_DISTANCE`.
- That `supports_streaming == false` implies moov at the end.
- TDLib's single active download per file (taken from our own ARCHITECTURE doc, not from TDLib source).
- Dolby Vision test files: ffmpeg cannot make them.
- Async queueing helping on API 23 to 30 TV boxes: documented as a general benefit, with a counter-example on a Google TV
  Streamer [A]. Measure on the TX6 class before making it a default.
- Robolectric availability in our test setup for the fake-codec tests.
- The 16 KB state of libVLC and libmpv builds.
- Release status of Media3 1.12 (DV P7 fallback), and exact date semantics of the Play deadline (the doc says
  2027-02-01 for blocked updates; some third-party pages say otherwise, ignored).

---

## 10. Sources (with licences where code was read)

1. Media3 release notes: https://raw.githubusercontent.com/androidx/media/release/RELEASENOTES.md (Apache-2.0)
2. `MediaCodecRenderer.java`, `MediaCodecVideoRenderer.java`, `MediaCodecInfo.java`, `MediaCodecUtil.java`,
   `DefaultMediaCodecAdapterFactory.java`, `Mp4Extractor.java`, `DefaultTrackSelector.java` (Apache-2.0), under
   https://github.com/androidx/media/tree/release/libraries
3. Media3 issue 3486, DV P7 fallback: https://github.com/androidx/media/issues/3486
4. Media3 customization and supported formats: https://developer.android.com/media/media3/exoplayer/customization ,
   https://developer.android.com/media/media3/exoplayer/supported-formats
5. Media3 troubleshooting (no error when unsupported, stuttering, seeking, TS flags, MP4 edit lists):
   https://developer.android.com/media/media3/exoplayer/troubleshooting
6. LibreELEC H6 threads (stutter, render method): https://forum.libreelec.tv/thread/28509-tanix-tx6-libreelec-12-0-0-video-stuttering-problem/ [A]
7. Media3 issue 2765, MediaTek HEVC black or frozen no error: https://github.com/androidx/media/issues/2765 [A]
8. Media3 issue 2090, jerky H.264 on a Rockchip box, VLC "Decoding acceleration" works:
   https://github.com/androidx/media/issues/2090 [A]; EOS workaround seen in another app:
   https://github.com/NuvioMedia/NuvioTV/pull/4495 [A]
9. Rockchip post-seek stall write-up: https://github.com/FongMi/media/pull/3 [A, unmerged, author says not ready]
10. https://github.com/FongMi/TV/pull/774 [A]
11. Jellyfin Android TV freezes: https://github.com/jellyfin/jellyfin-androidtv/issues/4103 [A]
12. Allwinner H6 background: https://forum.armbian.com/topic/17187-understanding-hardware-accelerated-video-decoding/ [A]
13. DV P5 and P7 reports: https://github.com/androidx/media/pull/3160 , https://github.com/androidx/media/issues/3486
14. Async queueing: https://developer.android.com/media/media3/exoplayer/customization#enabling-asynchronous-buffer-queueing
15. Media3 issue 3405, high bitrate progressive on Android TV: https://github.com/androidx/media/issues/3405 [A]
16. Our own `DecodeJudge.kt` measurements (repo).
17. Media3 audio passthrough reports: https://github.com/google/ExoPlayer/issues/10204 [A]
18. `Mp4Extractor` moov handling, see 2.
19. Our `docs/ARCHITECTURE.md` Seek matrix (repo).
20. TDLib `td_api.tl` (Boost Software License 1.0): https://github.com/tdlib/td/blob/master/td/generate/scheme/td_api.tl
21. Same as 15.
22. VLC tracker, MediaCodec MBAFF deinterlacing (title only seen): https://code.videolan.org/videolan/vlc/-/issues/26987 [U, not readable]
23. Our `HX72-media3-issue-draft.md` and `CrashExits.kt` (repo).
24. 16 KB: https://developer.android.com/guide/practices/page-sizes
25. libVLC 16 KB tracker (not readable): https://code.videolan.org/videolan/vlc-android/-/issues/3242
26. VLC core, LGPL-2.1+: `modules/codec/omxil/mediacodec.c`, `modules/codec/avcodec/video.c`, `src/input/decoder.c`
    from https://github.com/videolan/vlc (mirror of code.videolan.org/videolan/vlc)
27. VLC avcodec options: https://wiki.videolan.org/Documentation:Modules/avcodec/
28. VLC for Android README (licences): https://github.com/videolan/vlc-android
29. VLC Android 2014 modes patch: https://mailman.videolan.org/pipermail/android/2014-January/003027.html [A, old]
30. Old `HWDecoderUtil` copy: https://github.com/GeoffreyMetais/vlc-android/blob/master/libvlc/src/org/videolan/libvlc/util/HWDecoderUtil.java (LGPL-2.1+, 2013)
31. mpv `DOCS/man/options.rst`, `player/video.c`, `video/decode/vd_lavc.c` (LGPL-2.1+ headers) from
    https://github.com/mpv-player/mpv
32. FFmpeg `-hwaccel`: https://ffmpeg.org/ffmpeg.html
33. Android `MediaCodec.CodecException`: https://developer.android.com/reference/android/media/MediaCodec.CodecException
