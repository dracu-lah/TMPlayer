# How reelbox-10k.pages.dev plays every format (read 2026-10-21)

Read logged out: the public bundles, the Service Worker, a network capture in headless Chromium
155 (Fedora), and the npm package the site ships, played locally on generated files. No login, no
code copied.

## Short answer

Everything runs in the browser. There is no server of theirs in the path: Cloudflare Pages serves
static files only (`/api/...` and `/functions` answer the SPA's index.html), and the only other
connection is a WebSocket straight to Telegram (`wss://venus.web.telegram.org/apiws`).

- **Telegram:** mtcute (`@mtcute/core` 0.32.x, MIT), MTProto in JavaScript with its crypto in a small
  wasm (`mtcute-simd.wasm`), not TDLib. Video bytes come from raw `upload.getFile` calls.
- **Playback:** the npm package **movi-player 0.4.1** (slim build), unmodified: its `movi.wasm` is
  byte for byte the package's (`cmp` equal, 5,647,290 bytes). movi-player is FFmpeg compiled to wasm
  (libavformat demuxers, libavcodec software decoders, swresample, swscale, dav1d) plus WebCodecs,
  drawing the picture on a canvas and the sound through Web Audio. It is not MSE and not `<video>`.

## Libraries and licences

| Piece | Licence | Notes |
| --- | --- | --- |
| movi-player 0.4.1 (`<movi-player>` element, npm, 2026-10-02) | Apache-2.0 for its own JS | slim build: `element.slim.js` 4.9 MB (about 1.0 MB gzip) + `movi.wasm` 5.6 MB (2.2 MB gzip) |
| FFmpeg inside `movi.wasm` (libavcodec 63.x, libavutil git `a06d9165905c`) | LGPL-2.1+ (no GPL-only parts seen: h264, hevc, ac3, eac3, dca, truehd, mlp, aac, opus, vorbis, flac, mp3, mpeg2video, vp9 decoders; matroska, mov, mpegts, avi, flv, asf demuxers; subrip, ass, webvtt, mov_text, pgssub) | The licence string is stripped and the package says only Apache-2.0, so the LGPL notice and the FFmpeg source offer are missing upstream. We can use it under GPL-3, but must ship FFmpeg's notice and point to the exact FFmpeg source and build script; better, build `movi.wasm` ourselves in CI |
| dav1d (AV1 software decoder, in the wasm) | BSD-2-Clause | |
| mtcute | MIT | |
| Optional deps of movi-player: shaka-player, hls.js, dash.js | Apache-2.0 | only for HLS/DASH, unused for Telegram files |

## How bytes arrive

1. A chunk reader per file: 1 MiB chunks (`upload.getFile` with offset, limit 1 MiB,
   `precise: false`), an LRU of 48 chunks (48 MiB) in memory, read ahead 6 chunks, 3 requests in
   parallel, `FILE_MIGRATE` handled by switching DC, a stale file reference refreshed by fetching
   the message again. CDN redirects are refused. Nothing is written to disk, so memory is bounded
   by the 48 chunk LRU whatever the file's size: a 4 GB file seeks to its end with no growth. This
   is the part stock tdweb cannot do (its MEMFS is dense).
2. **Built-in decoder (default):** the reader is handed to movi-player as a custom `sourceAdapter`
   (read(offset, length), seek, getPosition), wrapped with 4 retries and exponential back off. No
   Service Worker in this path. FFmpeg reads through it with Asyncify, single threaded, no COOP/COEP
   needed.
3. **Browser decoder (a setting, and the fallback when the wasm does not load):** a Service Worker
   `/sw.js` answers `/stream/<key>?size=&mime=` with 206 answers of at most **2 MiB** (suffix
   ranges, 416, a streamed 200 without Range), asking the page over a MessageChannel
   (`{type: 'stream-read', key, offset, length}`), the same design as our 2026-10-19 prototype. A
   second, private reader (8 chunks) feeds a hidden `<video>` for seek bar thumbnails.

## Per format path (verified locally with movi-player 0.4.1, headless Chromium 155)

12 s generated files, played 3 s, then seeked to 9 s:

| File | Default (WebCodecs video) | With software decoding | Notes |
| --- | --- | --- | --- |
| MKV H.264 + AAC | plays, seek ok | | |
| MKV H.264 + E-AC-3 | plays, seek ok | | sound decoded by FFmpeg wasm |
| MKV H.264 + DTS | plays, seek ok | | same |
| MKV H.264 + TrueHD | plays, seek ok | | same |
| MKV HEVC + AC-3 | fails: no hardware HEVC in WebCodecs | **plays, seek ok** | FFmpeg's HEVC decoder in software |
| MPEG-TS H.264 + AAC | fails in WebCodecs (Annex B, no avcC) | **plays** | |
| AVI H.264 + MP3 | fails in WebCodecs | **plays** | |

The failure comes as an `errordisplay` event with `canTrySoftware: true`; reelbox then calls
`enableSoftwareDecoding()`, hands a fresh source adapter and seeks back to where it was. That
automatic switch is what makes "everything plays".

- **Video:** WebCodecs `VideoDecoder` first (hardware where the browser has it), FFmpeg wasm in
  software otherwise. Software 1080p HEVC on a phone will be slow; 4K software will not keep up.
- **Sound:** whatever FFmpeg decodes (AC-3, E-AC-3, DTS, TrueHD, MP3, Vorbis, FLAC, Opus, AAC),
  played through Web Audio (`AudioContext`, `setSinkId`). No WebCodecs AC-3 support is needed.
- **Audio tracks:** every track is demuxed, so switching is a decoder switch, not a re-download.
- **Subtitles:** a pluggable renderer; reelbox turns SRT, ASS/SSA (styling stripped), WebVTT and
  mov_text into plain lines with a delay. PGS and VobSub listed but not drawn.
- **Seeking in large files:** FFmpeg's own seek (Matroska Cues, MP4 sample tables) through the
  source adapter, so a seek reads only the index and the target cluster.
- **Picture in picture:** Document Picture in Picture (Chrome/Edge 116+) with subtitles and
  buttons; other browsers get `captureStream()` into a `<video>` for native PiP.

## What it means for TMPlayer (static site, no server: compatible)

1. Use movi-player 0.4.1, slim build, pinned by version and hash; add a "Web" section to
   THIRD_PARTY_NOTICES with FFmpeg's LGPL-2.1 text, dav1d's BSD notice and the FFmpeg source link;
   better, build `movi.wasm` in CI from movi-player's build script.
2. Feed it from our own chunk reader (`sourceAdapter`). With tdweb this is `readRange`
   (getFileDownloadedPrefixSize, downloadFile with offset and limit, readFilePart), but tdweb's
   dense MEMFS allocates up to the read offset, so a seek into a 4 GB file costs 4 GB of memory.
   mtcute next to TDLib would mean a second login (not acceptable). Fix: our own tdweb build with
   sparse storage (OPFS or a chunked file system). Main open question for the web app.
3. Keep the Service Worker range path as the "browser decoder" for MP4/WebM: lightest on battery,
   native PiP and Remote Playback (Cast), and the fallback when the wasm cannot load.
4. Order: browser decoder when `canPlayType`/`MediaSource.isTypeSupported` says probably for the
   container and both codecs, otherwise movi-player with WebCodecs, then movi-player in software on
   `canTrySoftware`. "Needs the app" only for picture subtitles and 4K HEVC in software
   (`assessPlayback()` gives a smoothness estimate).
5. No MKV to fMP4 repack and no WebCodecs AC-3 conversion needed. The player UI around movi-player
   (keys matching PlayerKeys.kt, strings from `player.*` and `keys.*`) is still to build, using its
   API (`nohotkeys`, `setSubtitleRenderer`, `selectAudioTrack`, `selectSubtitleTrack`,
   `setSubtitleDelay`, `getAudioTracks`, chapters).

## Not verified

- Real Telegram files and big files (no login used). Firefox and Safari not run.
- ASS and PGS rendering not played locally.
- Local test script and files: scratchpad `rb/movitest.mjs` and `rb/gen.sh` of session
  6d3c2f2b-673d-44fb-b220-ccc658e09840.
