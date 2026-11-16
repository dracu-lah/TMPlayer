# Reelbox on real files (signed in, 2026-10-21)

The signed in follow-up to `REELBOX-WEB-PLAYBACK.md` and `REELBOX-PARITY.md`. Files are described
by format and size only.

## How it was measured

- Headless Chromium 155 (Fedora), driven by Playwright through `launchPersistentContext` on the
  scratch profile, with CDP for network, performance metrics and memory. Read only: no messages
  sent and nothing changed on Telegram. After the run, the 18 watch progress rows and the
  `player-prefs-v1` key that the test created were removed, so the profile is back to its state
  before the run.
- **Limits of this setup.** Headless Chromium here has no hardware video decoding: SwiftShader GL,
  and WebCodecs reports no HEVC even with GPU flags. H.264 goes through Chromium's own WebCodecs
  decoder in software. HEVC goes through movi-player's FFmpeg wasm, which runs single threaded
  because the site is not cross-origin isolated (`crossOriginIsolated` is false), so there are no
  wasm threads. The HEVC rows are therefore the worst case: Firefox, or Chrome on Linux without
  VA-API. On Windows or macOS Chrome with HEVC in hardware, HEVC would go through WebCodecs like
  H.264. Network: one home connection to Telegram's DCs.
- **What the numbers mean.**
  - "First frame" is the time from navigating to `/watch/...` (what a click does) to the first
    presented frame. "Playing" is the time until the clock has moved past 0.3 s.
  - "MB" is the WebSocket bytes received from Telegram over that period (CDP
    `webSocketFrameReceived`).
  - Chunks are `upload.getFile` misses, counted on reelbox's chunk reader. Each chunk is 1 MiB.
  - A seek is `currentTime = 50%` or `95%` of the duration. Its time runs until the clock moves
    0.2 s past the target.
  - Loudness is the RMS dBFS at the Web Audio destination, read through an analyser tap on
    `AudioNode.connect`.
- movi-player reports `version 0.4.1`, `build slim`, renderer `canvas`. Its config: `decoder
  auto`, its own LRU `maxSizeMB 520`, and the reelbox source adapter (`chunkSize 1048576`,
  `cacheChunks 48`, `readAhead 6`, `concurrency 3`, 4 attempts, 400 ms retry delay).

## Summary table

| # | File (format, size) | Decoder path | First frame / playing | MB to start | Seek 50% | Seek 95% | Result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | MKV H.264 480p, 2 x AAC 2.0, SRT, cover art, 590 MB, 2h33 | WebCodecs video, FFmpeg audio | 13.3 s (resume at 70 s) | 45 | 2.3 s, 6.7 MB | 5.4 s, 9.3 MB | plays |
| 2 | MKV H.264 1080p, E-AC-3 5.1 + AAC 2.0, SRT, 3.2 GB (second DC) | WebCodecs, FFmpeg audio | 9.5 / 9.9 s | 8.7 | 3.1 s, 6.7 MB | 7.5 s, 12 MB | plays, 35 of 52 early frames dropped (CPU) |
| 3 | MP4 H.264 1080p, AAC 5.1, 2.5 GB | WebCodecs | 5.8 / 6.1 s | 16.3 | 3.1 s, 8 MB | 3.9 s, 10.7 MB | plays |
| 4 | MP4 H.264 720p, AAC 2.0, 993 MB | WebCodecs | 4.1 / 4.5 s | 14.2 | 3.1 s, 10.7 MB | 3.5 s, 10.7 MB | plays |
| 5 | MKV H.264 1080p, E-AC-3 5.1, 2.6 GB, after a 2 s hover | WebCodecs | 4.2 / 4.5 s after the click | 4 (+4.7 during hover) | not run | not run | best start seen |
| 6 | MKV H.264 720p, AAC 2.0, SRT, 1.4 GB | WebCodecs | 14.7 / 15.1 s | 51.5 | not run | not run | start does a bisection (below) |
| 7 | MKV H.264 1080p, AAC 2.0, SRT, 2.7 GB, plain click | WebCodecs | 17.1 / 17.5 s | 59.7 | not run | not run | bisection |
| 8 | MKV H.264 480p, 2 x MP3, 3 x SRT, 476 MB | WebCodecs | 32 / 33 s | 46 | not run | not run | bisection (player created only at 11.8 s) |
| 9 | MKV H.264 480p, 2 x AAC 2.0, PGS, 485 MB | WebCodecs | 11.7 / 12 s (resume at 50%) | 43.6 | 2.0 s, 6.7 MB | 2.9 s, 10.7 MB | plays, PGS not drawn |
| 10 | MKV H.264 720p, 2 x AAC 5.1, PGS, 1.2 GB | WebCodecs | 12.5 / 12.9 s (resume at 50%) | 55.6 | 3.6 s, 10.7 MB | 3.3 s, 12 MB | plays, PGS not drawn |
| 11 | MKV HEVC 8 bit 1080p, E-AC-3 2.0 + E-AC-3 5.1, SRT, 2.2 GB | software (FFmpeg wasm) | 22.2 / 22.5 s (resume at 50%) | 61.9 | (was the resume) | 7.2 s, 10.7 MB | plays, 6 of 18 frames dropped |
| 12 | MKV HEVC 10 bit 720p, AAC 2.0 + AAC 5.1, 2 x PGS, 1.0 GB | software | 3.2 / 3.7 s | 8.7 | 6.8 s, 12 MB | 4.4 s, 10.7 MB | plays |
| 13 | MKV HEVC 10 bit 1080p, 2 x E-AC-3 5.1, SRT, 3.8 GB | software | 7.9 / 8.5 s | 8.5 | stuck past 60 s | stuck past 60 s | starts, cannot seek |
| 14 | MP4 HEVC 10 bit 1080p, AAC 5.1, 2.1 GB, moov at the end | software | 18.1 / 18.4 s | 21 | 9.9 s, 10.7 MB | 36.4 s, 12 MB | barely |
| 15 | MKV HEVC 10 bit 1080p HDR10, 2 x AC-3 5.1 + 2 x AAC 2.0, 9 x PGS, 3.1 GB | software | 9.0 / 9.3 s | 10.9 | stuck past 60 s | stuck past 60 s | starts, cannot seek |
| 16 | MKV HEVC 10 bit 2160p, E-AC-3 5.1, SRT, 73 MB sample | software | 49.6 / 49.9 s | 16.9 | stuck past 60 s | 49 s | unwatchable |
| 17 | MKV HEVC 10 bit 1080p, 2 x Opus 2.0, SRT + 3 x ASS, 2.8 GB | software | no frame in 60 s | n/a | n/a | n/a | fails silently: spinner, clock at 2 s |
| 18 | MKV HEVC 10 bit 1080p, E-AC-3 5.1, 18 MB sample | software | 4.4 s (resume) | 1.3 | stuck | stuck | starts, cannot seek |
| 19 | MKV H.264 1080p, AAC 2.0, 3.8 GB, series episode | WebCodecs | 13.5 / 13.9 s | n/a | n/a | n/a | used for the next episode test |

Process memory at the same moments, for rows 2 to 4 and 9 to 16: the renderer was 1.1 to 2.2 GB
and the JS heap 16 to 23 MB. Seeking to 95% never grew the JS heap by more than 3 MB. The renderer
moved by 0.1 to 0.5 GB while software HEVC was decoding and came back after.

## Per file details

### MKV H.264 480p, 2 x AAC 2.0, SRT, 590 MB (row 1)

| What | Measured |
| --- | --- |
| Tracks | h264 750x422 Main, aac 2ch hin, aac 2ch tam, subrip eng, mjpeg cover (shown as a second video track) |
| Paths | Video "Hardware (WebCodecs)", audio "Software (FFmpeg)" (movi `getStats`) |
| Start | Resumed itself at 70 s, the point an earlier play stopped: 13.3 s, 45 MB |
| Seeks | 50%: 2.25 s, 6.7 MB. 95%: 5.4 s, 9.3 MB. 3 s later: 10.7 and 12 MB |
| Memory | JS heap 16 to 17 MB. Renderer 0.99 to 1.12 GB. movi "Memory Used" 47 to 102 MB |
| Loudness | -27.9 and -26.2 dBFS RMS in two windows |
| Subtitles | SRT turned on from the Subtitles menu: first caption 5.5 s later, because the choice re-seeks to the current time. Drawn in HTML over the canvas: Inter 500, `clamp(15px, 3.1cqi, 38px)` (38 px at 1600 px wide), white, an 8 direction black outline of 0.05 em, no box, 140 px above the bottom with the controls showing |

### MKV H.264 1080p, E-AC-3 5.1 + AAC 2.0 (same language), SRT, 3.2 GB (row 2)

| What | Measured |
| --- | --- |
| Start | Chunks 0 to 3 and the last chunk asked for at 1.95 s (the page first fetches the message), 1.4 MB after 4.5 s, 5.4 MB after 8 s: the first file on a second DC pays for the new connection. First frame 9.5 s |
| Frames | 35 of 52 dropped right after the start, then it kept up (software H.264 at 1080p on this machine) |
| Seeks | 50%: 3.1 s; chunks target-1 to target+3 asked for at once, then one about every 0.5 s. 95%: 7.5 s |
| Audio switch | E-AC-3 5.1 to AAC 2.0: sound after 271 ms, no gap found at 50 ms sampling, clock continuous (1829.5 to 1830.2), no extra download (1 to 2 chunks of ordinary read-ahead). Back to E-AC-3: 360 ms |
| Loudness, same windows | E-AC-3 5.1 downmixed: -30.1 and -30.7 dB. AAC 2.0 track: -33.1 and -34.4 dB. The 5.1 downmix is 3 to 4 dB louder, not quieter |

### MP4 H.264, 2.5 GB and 993 MB (rows 3 and 4)

Chunks 0 to 3 and the last chunk come in first, then 4 to 6 as read-ahead, then the playback reads.
On the 2.5 GB file the playback reads start at about 3.7 s, and 16 MB had arrived when the first
frame showed at 5.8 s. The 720p file: first frame 4.1 s, 14 MB. Seeks took 3.1 to 3.9 s and 8 to
11 MB.

### MKV H.264, plain start with a bisection (rows 6, 7, 8, and the resumes in 1, 9, 10, 11)

On 7 of the 15 MKVs tried, the start does not go straight from the head to the first cluster. Once
the header has been read, the demuxer inside `movi.wasm` (stack: `module.onReadRequest`, then
`We.read`, then reelbox's `Xm.read`) reads at 50%, 25%, 12.5%, 6% of the file and so on, five or
six chunks each time, before it decodes frame 0. Example, the 2.7 GB file in row 7:

```
0, 1, last @58 ms; 2..6 @0.3-0.6 s; 1356..1359 @2.5-4.4 s; 678..681 @4.7-5.7 s; ... first frame 17.1 s
```

That costs 45 to 60 MB and 10 to 30 s before the first frame. Seeks later in the same files go
straight to the target (for example chunks 248 to 252 of 485 for 50%), so the files do have an
index; it is only the first positioning that bisects. The files without it (rows 2, 5, 12, 13, 15)
start after reading 4 to 11 MB. The pattern does not follow the subtitle format, the audio codec
or the release group. I did not pin down which call starts it.

### HEVC in software (rows 11 to 18)

- 720p 10 bit plays: start 3.7 s, seeks 4.4 to 6.8 s.
- 1080p 10 bit starts in 8 to 9 s with half of the early frames dropped. After any seek it never
  shows a frame again: `seeking` stays true for 60 s or more and the spinner stays up.
- 2160p 10 bit took 50 s to show its first frame.
- In all of these, chunk fetches slow from one every 0.3 s to one every 5 to 16 s. The FFmpeg wasm
  decode, mtcute's MTProto crypto and the UI share one main thread, so a decoder that cannot keep
  up also starves the download.
- movi notices: `softwareDecodeReason` is `hardware-refused`. reelbox logs "no hardware decoder,
  switching to software decoding", calls `enableSoftwareDecoding()` and seeks back.
- The UI never tells the user that the file is beyond the device. movi's `canPlaySmoothly` and
  `smoothWarning` exist but showed nothing. The `_errorTitle` "Format Unsupported" ("This video
  codec is not supported by your browser's hardware acceleration.") stays set internally and is
  never shown, because reelbox sets `noerrorscreen`.
- Row 17 (1080p 10 bit, Opus) never drew a frame. The clock showed 0:02 and the spinner turned,
  with no message.

### MP4 with the moov at the end (row 14)

Chunks 0 to 3 and the last chunk at the start, then 2147 to 2149 of 2154: the sample tables sit
about 7 MB from the end. Because the last chunk is fetched at once, the moov was partly in memory
before the demuxer asked for it, and the rest followed in about 3 s. The slow start (player created
only at 7.5 s, first frame 18 s) came from software HEVC, not from the moov position.

### HDR10 HEVC with 4 audio tracks and 9 PGS tracks (row 15)

| What | Measured |
| --- | --- |
| Tracks | hevc 1920x800 yuv420p10le, `isHDR` true. Audio: AC-3 5.1 hin, AC-3 5.1 eng, 2 x AAC 2.0 commentary. Subtitles: 9 x hdmv_pgs_subtitle |
| Audio menu | Shows the track names from the file (for example "Org UHD Hindi BD 5.1 ~ 640Kbps") with "AC-3 5.1" or "AAC stereo" under each |
| Subtitles | The button's label is "Subtitles (none in this video)", although the file has 9 tracks. The menu lists each PGS track as "PGS, not supported yet", then "Search online" (OpenSubtitles) and "Look and timing" |
| Resume | Reopened after leaving at 9025 s: playing again at 9029 s |

### ASS subtitles (row 17)

The menu lists "SRT" and three "ASS" tracks ("Signs", full, "Dubtitle"). Choosing an ASS track
shows its lines in the same plain HTML style as SRT, 5.9 s after the choice. Override tags are
stripped (`\{\\[^}]*\}`), and so are position, font, colour, karaoke and the signs layout.

### Seek bar thumbnails (row 9 file)

- 172 x 95 px pictures from a side reader that has its own 8 chunk cache, so they never evict the
  playing video's chunks.
- At a new spot on the bar: 2.2 to 2.9 s and 1.3 to 2.7 MB per position. Near a spot already shown:
  0.13 to 0.35 s.
- The first hover warms up as soon as the pointer enters the bar (`previews.prepare()`).

### Next episode (row 19, a series)

- At 30 s before the end, a "NEXT EPISODE" card appears with a ring countdown from 30, "Hide" and
  "Play now". The countdown follows the video clock (it held still while playback stalled), not
  the wall clock.
- When the card appears, the next episode's chunks 0 and 1 and its last chunk are fetched (3 MiB).
- At the end, 2.9 s pass until the next player exists and 2.3 s more until it plays: 5.2 s from the
  last frame to the next episode playing.
- The watch page also lists "Next episodes" and "All episodes".
- "Still watching?" after a few in a row exists in the code; it was not reached in this run.

### Resume and the miniplayer

- Progress lives in IndexedDB `app-data/progress` (key `chat:message`, position, duration,
  `finished`, `updatedAt`). It is written while playing.
- Reopening starts at the saved position. The loader says "Resuming from {time}".
- A file over about 95% is marked finished and leaves Continue watching. An unfinished one shows
  "8 min left" on its card.
- Leaving the watch page docks the video into a miniplayer that keeps playing, with expand, back
  10, pause, forward 10 and close.
- The data source stays alive after close. The last 3 sources are kept, and read-ahead on the
  previous file was seen still running 30 s later.

### Memory over a session

movi's "Memory Used" (the wasm heap) only grows:

| Point in the session | wasm heap | Renderer RSS |
| --- | --- | --- |
| First file | 47 MB | 0.8 to 1.0 GB |
| After 6 files | 214 MB | |
| After 12 files | 465 MB | 1.6 to 2.2 GB |
| After about 20 files | 815 MB | 2.3 to 2.7 GB |
| After closing the player and a GC | | 2.15 GB |

The JS heap stayed at 16 to 32 MB the whole time. Within one file, memory is bounded as designed:
the adapter cache never went above 48 chunks.

### QR sign in (from the code, not run)

- mtcute `signInQr` loops on `auth.exportLoginToken`. Each token is shown as
  `tg://login?token=...` with its expiry.
- It sleeps until the expiry or an `updateLoginToken`, then exports a fresh token by itself. On
  `auth.loginTokenMigrateTo` it changes DC and calls `auth.importLoginToken`. A 2FA password is
  asked for when needed.
- The UI draws a bar that drains over 30 s (`Hy=3e4`) with "Refreshes by itself", then "Getting a
  fresh code" at zero.
- On an error it shows the message and a "Get a new QR code" button.
- On a coarse pointer under 768 px wide it opens on the phone number form instead.
- At start, a stored session is checked with `getMe`. On `AUTH_KEY_UNREGISTERED`,
  `SESSION_REVOKED` and the like, it falls back to the QR without an error.

### Telegram calls (from the code)

App code calls only `getMe`, `getMessages`, `iterDialogs`, `searchMessages`, `getPeer`,
`downloadAsBuffer`, raw `upload.getFile` and the sign in calls. Nothing marks messages read or
counts views, so playing a video leaves no trace in the chat.

## Findings: what TMPlayer should match

1. **Warm the head and the tail before the press.** This is the single biggest win measured:
   - With a 2 s hover (chunks 0, 1 and the last), a 2.6 GB MKV showed its first frame 4.2 s after
     the click and needed only 4 MB after it.
   - Comparable files without a warm-up took 6 to 17 s.
   - The next episode card does the same 30 s ahead.
   - This confirms the "warm on focus or press" and "warm the next episode" rows of
     `REELBOX-PARITY.md`. Android TV focus and the desktop hover should fetch the first 2 MiB and
     the last 1 MiB, or for TDLib the head plus the index range.
2. **Seek cost is small and flat.** A seek to 50% or 95% of a 0.5 to 3.9 GB file needs 7 to 12 MB
   and 2 to 4 s to resume (H.264), whatever the file size:
   - Four or five 1 MiB chunks around the target are requested at once, then the read-ahead.
   - Memory does not grow with the seek distance (JS heap flat, 48 chunk cap).
   - TMPlayer's targets: under 3 s and about 10 MB per seek on a 3 GB file, and no growth with
     offset. The web app must not use dense tdweb storage, as noted before.
3. **Audio track switch without a refetch.** Every track is demuxed, so switching takes about
   0.3 s, with no gap and no seek. TMPlayer's Media3 track switch with an ExoPlayer re-buffer
   should be checked against 0.3 s.
4. **5.1 is not quiet.** E-AC-3 5.1 downmixed to stereo came out 3 to 4 dB louder than the same
   file's AAC 2.0 track, and AAC 5.1 matched an AAC 2.0 release of the same scene within 1 dB. If
   TMPlayer's 5.1 downmix is quieter than its stereo, that is a real gap (worth a device check with
   the same pair of tracks).
5. **Resume.** It saves often and reopens at the saved second, with "Resuming from" on the loader.
   It marks a file finished at about 95% and drops it from Continue watching. The miniplayer keeps
   playing when the user leaves the page.
6. **Next episode.** A 30 s card with a countdown that follows the video clock, with "Hide" and
   "Play now". It took 5.2 s from the end to the next episode playing. TMPlayer should aim for
   under 3 s by keeping the warmed source.
7. **Subtitle look.**
   - Captions are HTML over the picture: medium weight, size tied to the player width (3.1% of it,
     15 to 38 px), white with a thin 8 direction outline and no box.
   - They appear a few seconds after being chosen, because the choice re-seeks.
   - TMPlayer's outline-only default is close. Its preview should match this on the web.
8. **Honest track lists.** Unsupported PGS tracks are listed with "PGS, not supported yet" and an
   OpenSubtitles search next to them, rather than being hidden. TMPlayer draws PGS on Android and
   desktop and should keep showing them on the web too, at least labelled the same way.
9. **Nothing done on the user's behalf.** No read receipts, no view counts, only reads. That
   matches TMPlayer's terms checklist.

## Findings: what fails in reelbox too

1. **A bisection before the first frame on about half of the MKVs.** Reads go to 50%, 25%, 12.5%
   and on of the file before frame 0: 45 to 60 MB and 15 to 33 s on files that otherwise seek in
   2 to 4 s. A web TMPlayer built on movi-player would inherit this. Check it with these files
   before adopting movi-player 0.4.1, and look for the call that positions at start time.
2. **Software HEVC 10 bit at 1080p and above is unusable without hardware.**
   - It starts, then never recovers from a seek. 2160p took 50 s for a first frame.
   - One file never drew a frame.
   - Because the wasm build is single threaded (no COOP/COEP), the decoder also starves the
     download, since MTProto runs on the same thread.
   - Neither movi nor reelbox tells the user. The planned web app needs either cross-origin
     isolation for threaded FFmpeg (and mtcute or tdweb in a worker), or an honest "this file needs
     the app" notice, using movi's `canPlaySmoothly` or a decode benchmark. Android and desktop
     TMPlayer, with MediaCodec or mpv in hardware, do not have this problem.
3. **The wasm heap only grows.** It went from 47 MB to 815 MB over about 20 videos in one tab, and
   the renderer from 0.9 to 2.7 GB. Closing the player did not release it. A web TMPlayer should
   recreate the wasm module after N videos or when the heap passes a limit.
4. **No styling for ASS and no PGS.** ASS is shown as plain lines, losing positions and signs. PGS
   is not drawn at all, and a file with only PGS gets a Subtitles button that says "none in this
   video".
5. **Second DC start.** The first file on another DC took about 4 s longer: 1.4 MB in the first
   4.5 s. Warming that DC's connection when the home page lists a chat from it would hide this.
6. **The source keeps fetching after close.** Read-ahead of a closed video was still running 30 s
   later, taking bandwidth from the next one.
