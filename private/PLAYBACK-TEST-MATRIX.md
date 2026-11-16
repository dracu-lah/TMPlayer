# Playback test matrix (2026-10-21)

For the device pass after branch `worktree-agent-a09673dc9ad393dd8` (and the agents merged from
it). Platforms: **P** phone (POCO, portrait and landscape), **T** Android TV emulator (the stick
only when the user runs it), **D** desktop (Linux AppImage here, Windows MSI in the win11 VM).
Timings: `adb logcat -s PlayerStart:I StreamWarmup:I TdDataSource:I` on Android, the desktop log
on the desktop (`PlayerStart`, `StreamWarmup`). Write the numbers in the result column.

## 1. Start speed (StreamWarmup, start buffer, resume before prepare)

| # | Case | Steps | Expected | Platform | Result |
| --- | --- | --- | --- | --- | --- |
| 1.1 | Cold start, MKV stream never played | Press Play on a large MKV not on the device | `StreamWarmup: Warmed file N: head 1024 KB, index ...` before the first frame; `PlayerStart: press to first frame` noted; no jump or stall before the picture | P T D | |
| 1.2 | Cold start, MP4 with moov at the end | Same, non fast start MP4 | Warm line names an index of a few MB near the end; first frame without the old wait | P T D | |
| 1.3 | Fast start MP4 | Same, fast start MP4 | Warm line without an index; as fast or faster than main | P T D | |
| 1.4 | Resume a half watched stream | Play to 40 min, back out, play again | Starts at about 39:5x; first TdDataSource request near the resume offset, not byte 0 | P T D | |
| 1.5 | Next episode | Let an episode reach the Next episode card | Warm line for the next file when the card shows; next starts faster than a cold start | P T D | |
| 1.6 | Low storage mode on | Turn it on, play a stream | No StreamWarmup line; plays as before | P T D | |
| 1.7 | Save mobile data, on mobile data | Play a stream | No StreamWarmup line; on Wi-Fi the line appears | P | |
| 1.8 | Downloaded video | Play from Downloads | No warm line, plays from the file | P T D | |
| 1.9 | Video being downloaded on purpose | Start a download, play the same video | No warm line (the download is not moved) | P T D | |
| 1.10 | Start buffer 1 s | Play on a throttled line | Starts sooner than main, no more rebuffers in the first minute | P T | |
| 1.11 | Seek timing | Seek far ahead in a stream | `PlayerStart: seek to ready N ms` logged | P T | |

## 2. Press feedback (PlayPress)

| # | Case | Steps | Expected | Platform | Result |
| --- | --- | --- | --- | --- | --- |
| 2.1 | Video or show page | Press Play | Triangle becomes a spinner at once; "Starting <title>..." pill after a beat if the player is not up | P T | |
| 2.2 | Home, carousel, chats, search, Continue watching | Press each | Pill on a slow start only; gone once the player is up and on return | P T | |
| 2.3 | No room | Fill the disk, press Play | Spinner and pill go when the room prompt shows | P | |
| 2.4 | Offline, not downloaded | Airplane mode, press Play | Spinner and pill go; "connect to play" toast | P | |

## 3. Background and reconnect

| # | Case | Steps | Expected | Platform | Result |
| --- | --- | --- | --- | --- | --- |
| 3.1 | App backgrounded 5 min | Home, wait, reopen | Lists still shown; at most a small "Connecting..." pill; no Retry / Use a proxy banner; lists refresh in place | P | |
| 3.2 | Real block | Block Telegram (dead proxy or firewall), stay in view 25 s | Banner with Retry and Use a proxy after about 20 s in view | P T D | |
| 3.3 | Network loss mid stream | Wi-Fi off during a stream, on after 30 s | Rebuffer chip, then carries on by itself; no stuck spinner, no sheet | P T D | |
| 3.4 | Background audio | Start a video, lock, unlock after 2 min | Sound kept, picture back, no banner | P | |

## 4. Trailers

| # | Case | Steps | Expected | Platform | Result |
| --- | --- | --- | --- | --- | --- |
| 4.1 | TV with YouTube | Video page, Watch trailer | YouTube app opens on it; Back returns to the page | T | |
| 4.2 | TV without YouTube | Disable YouTube, press trailer | In-app: spinner then the trailer; Close focused; Back closes | T | |
| 4.3 | Trailer not allowed embedded | Such a trailer | "This trailer can't play inside the app", QR code, Watch on YouTube, Close | P T | |
| 4.4 | Phone | Press trailer | Plays in the app as before | P | |
| 4.5 | Desktop | Press trailer | Browser | D | |

## 5. Sign in

| # | Case | Steps | Expected | Platform | Result |
| --- | --- | --- | --- | --- | --- |
| 5.1 | QR scanned | Scan, confirm on the phone | White veil, tick, "Scanned" about 1.4 s, then the app or the password | P T D | |
| 5.2 | QR expired | Offline on the QR pane 50 s | Veiled "This code has expired", Get a new code (focused on TV) works once online | P T D | |
| 5.3 | Get a new code | Press it beside Use phone number | Spinner on the button, a new code | P T D | |

## 6. Sound level

| # | Case | Steps | Expected | Platform | Result |
| --- | --- | --- | --- | --- | --- |
| 6.1 | 5.1 film, phone speaker | Dolby 5.1 film then a web video, same volume | Dialogue clear; film about as loud as the web video | P | |
| 6.2 | 7.1 TrueHD or DTS-HD remux, phone | Play one | Dialogue in both speakers (was left only, quiet) | P | |
| 6.3 | Even out volume off | Player menu line | Film back to its quiet level; line says Off; kept | P T | |
| 6.4 | Loud scene | Action scene, Even out volume on | No crackle or clipping, no pumping after | P T | |
| 6.5 | TV to a receiver, passthrough | Dolby to an AVR | Bitstream untouched | T | |
| 6.6 | Desktop 5.1 on stereo | Play a 5.1 film | Dialogue clearer than main (centre at full) | D | |

## 7. Seek pictures

| # | Case | Steps | Expected | Platform | Result |
| --- | --- | --- | --- | --- | --- |
| 7.1 | Streamed MKV | Play 2 min, scrub back over it | Pictures over the bar with the time under them | P T | |
| 7.2 | Scrub past the download | Scrub ahead | Nearest picture within 45 s; else on a phone no card, on a TV the time chip alone | P T | |
| 7.3 | File that would not open at first | MKV with Cues at the end, scrub early, again after a minute | Pictures appear once 8 MB more is down or 15 s pass | P T | |
| 7.4 | Telegram storyboard | Scrub a video that has one | Storyboard tiles where the file has no frames | P T D | |
| 7.5 | RTL | Arabic UI, scrub | Card follows the mirrored scrub point | P | |
| 7.6 | Downloaded file | Scrub anywhere | Pictures everywhere | P T | |

## 8. Failure sheet and loader

| # | Case | Steps | Expected | Platform | Result |
| --- | --- | --- | --- | --- | --- |
| 8.1 | Unplayable codec | A file the device cannot decode | Sheet with Open in another app; Copy details copies codec and error (phone, desktop); never a black screen | P T D | |
| 8.2 | Slow start | Very slow line | "This is taking longer than usual" under the loader after about 3 s with no speed; stall sheet later as before | P T D | |
| 8.3 | Retry | Any retryable failure, Reload | Plays | P T D | |

## 9. Desktop keys

| # | Case | Steps | Expected | Platform | Result |
| --- | --- | --- | --- | --- | --- |
| 9.1 | Subtitle size | Shift+G, Shift+F | Size steps, flash says it, kept; F fullscreen and G skip unchanged; listed under ? | D | |

## 10. Regressions to watch (unchanged code, from the audit)

| # | Case | Steps | Expected | Platform | Result |
| --- | --- | --- | --- | --- | --- |
| 10.1 | Tracks mid play | Switch audio and subtitles, back out, reopen | Same tracks; series remembers | P T D | |
| 10.2 | Speed | Change speed, next episode | Kept by its rules | P T D | |
| 10.3 | PiP, notification, headphones unplug, a call | Each | Pauses or carries on as before | P | |
| 10.4 | Rotation, window resize | Mid play | No restart, position kept | P D | |
| 10.5 | 4 GB+, HDR 10-bit | Play, seek near the end | Plays, seek lands | P T D | |
| 10.6 | Split pieces | A cut video | Plays joined; no warm line | P T D | |
| 10.7 | Casting | Cast a stream | Starts on the TV as before | P D | |
| 10.8 | Leave mid stream | Back out | Player released; stream download replaced or stopped | P T D | |

## 11. Targets from reelbox on real files (REELBOX-REAL-FILES.md)

Measured on the same connection; ours should match or beat them on Android and the desktop.

| # | Case | Reelbox | Our target | Platform | Result |
| --- | --- | --- | --- | --- | --- |
| 11.1 | First frame, 2.6 GB MKV, after a 2 s focus or hover warm-up (head plus index) | about 4 s | 4 s or less (`PlayerStart: press to first frame`) | P T D | |
| 11.2 | First frame, same file, no warm-up | 6 to 17 s | under 6 s with the press warm-up | P T D | |
| 11.3 | Seek to 50 % and 95 % | 2 to 4 s, 7 to 12 MB fetched | `seek to ready` 2 to 4 s; no more than about 12 MB downloaded per seek (TDLib file size before and after) | P T D | |
| 11.4 | Audio track switch | about 0.3 s, no gap, no refetch | no audible gap past 0.5 s; no new download range (TdDataSource log quiet) | P T D | |
| 11.5 | Next episode after the last frame | 5.2 s | under 3 s with the warmed source | P T D | |
| 11.6 | Resume | to the saved second, "Resuming from" loader | same, first request at the resume offset | P T D | |
| 11.7 | Finished mark | about 95 % | as ours today | P T D | |
| 11.8 | E-AC-3 5.1 against the same file's AAC 2.0 track | the 5.1 fold 3 to 4 dB louder | after the dialogue fold and Even out volume, the two within about 2 dB on the phone speaker (switch tracks mid play and listen) | P | |
| 11.9 | Read-ahead after close | leaks for 30 s | stops at once: back out of a stream and the file's size in TDLib stops growing within a second | P T D | |
| 11.10 | Picture subtitle tracks (PGS, VobSub) | shown honestly | listed with their kind; never offered as if they will render where they do not | P T D | |

## 12. Code audit per case (2026-10-21)

"Code checked" means the path was read end to end and is right, or was fixed (commit named);
"device only" means it can only be judged on a device and the rows above cover it.

| Case | Status | Notes |
| --- | --- | --- |
| Entry points (Home, carousel, grids, search, Continue watching, next episode, Downloads, deep links) | Code checked | All reach MainActivity.play / openStored or PlayerActivity.openItem; press feedback and the warm-up sit there (d764505, b21473c) |
| Resume saved and restored | Code checked | Periodic and on leave; a beat inside the first 3 min or inside the rewind never overwrites a saved place (SettingsStore.saveResumePosition); now applied before prepare (b21473c) |
| Resume after process death | Code checked | Saved on the playback tick and onPause/onStop; nothing held only in memory |
| Resume from another device (Saved Messages) | Code checked, device only for timing | ResumeSync.pullFor before the place is read, on Android and desktop |
| Seek near start and end, during download | Code checked | DownloadWindow re-aims; opening at bytes already on the disk no longer re-aims (72bc3a9) |
| Partial files, moov at the end | Fixed | Warm-up brings the index, reads it in place (b21473c, 72bc3a9) |
| Split pieces | Code checked | JoinedDataSource; warm-up skips pieces |
| Audio and subtitle switch, remembered | Code checked | rememberTracks per series, resume state per video |
| Speed | Code checked | SpeedMemory scopes |
| Background playback, PiP, notification | Code checked, device only | PlaybackService and media session unchanged |
| Headphones unplug, audio focus, calls | Code checked | handleAudioFocus true, setHandleAudioBecomingNoisy(true) |
| Rotation and window size | Fixed | Config changes handled in place; keyboard and navigation were missing, so pairing a remote or keyboard restarted the video (bffa84a) |
| Backgrounded and resumed mid play | Code checked | Background audio path; reconnect banner no longer counts background time (7ed794d) |
| Network loss and return mid stream | Code checked, device only | Offline waits without spending retries, then reprepares at the place |
| Low storage | Code checked | Low storage mode windows; warm-up and in-place reads stay out of it |
| 4 GB and larger | Code checked | Offsets are Long end to end; reads clamp to Int only per read |
| HDR and 10-bit | Device only | Decoder fallback on; an unsupported picture now says so (05d3c31) |
| Codecs the device cannot decode | Fixed | Media3 dropped an unsupported picture silently (sound over black); now the format sheet with Open in another app (05d3c31) |
| Errors with a useful message and working retry | Code checked | friendlyError, Reload plan; Copy details added (7e4a4d3) |
| Leaks on exit | Fixed | A read still waiting could restart the download after the player closed; the window refuses requests once closed and the cancel runs again after release (a055764) |
| Casting | Code checked, device only | Unchanged path |

## 12. Formats against the device (HX2, 2026-10-26)

The judgement ([DecodeJudge]: the codec list for hardware, a software budget by codec, bit depth
and pixel rate scaled by the CPU, the device's own memory of earlier plays) and the watchdog
([PlaybackWatch]: speed against the clock, dropped frames, audio underruns, late frames over the
first 12 s of playing time). Clips: `/tmp/hx2scripts/make-matrix.sh` (20 s, 23.976 fps, ffmpeg),
run through the promo decode bench (`PromoDecodeBenchActivity`, `/tmp/hx2scripts/run-matrix.sh`),
which plays a file from the disk through the real renderers and logs the decoder, the verdict and
the watchdog's answer.

### The real file on the Mi TV (1.28.1, read-only measurements, 60 to 69 s each)

| Copy of "The Paradise" | Decoder | Process CPU | Speed | Audio underruns | UI frames |
| --- | --- | --- | --- | --- | --- |
| 1080p AV1 10-bit WEB-DL (av01.0.08M.10) | FFmpeg dav1d, software | 175% of one core (Playback thread 82%, 4 dav1d workers 22% each) | 0.36 (6.3 s in 17.6 s) | 43 in 69 s | 85% janky |
| 720p H.264 HDRip | OMX.amlogic.avc, hardware | 36% (Playback 12%, TDL-Sender 11%) | 1.00 (86.8 s in 86.8 s) | 0 | idle |

Both copies' E-AC-3 went through OMX.amlogic.audio.decoder.eac3. The stream was not the problem
(45 s buffered ahead on the AV1 copy). Mi TV CPU: 4 Cortex-A53 (CPU part 0xd03) at 1.416 GHz,
32-bit userland; HEVC and H.264 hardware to 1920x1088, no AV1 decoder in media_codecs.xml.

### TV emulator (tmplayer_tv_api36, x86_64, host decoders; cpufreq unreadable, so the CPU counts as 4 x 1.2 GHz)

| Clip | Verdict | Decoder | Watchdog | Speed, dropped of 480 |
| --- | --- | --- | --- | --- |
| a2 AC-3 5.1, a3 E-AC-3 5.1, a4 DTS 5.1, a5 Opus 5.1 (H.264 720p) | hardware | goldfish h264; FFmpeg ac3/eac3/dca, c2 opus | kept up | 0.98 to 1.00, 0 to 1 |
| m1 H.264 1080p AAC, MP4 | hardware | goldfish h264 | kept up | 0.98, 18 |
| m2 HEVC 10-bit 1080p E-AC-3, MP4 | software, too slow | FFmpeg hevc | kept up | 1.00, 0 |
| m3 AV1 10-bit 1080p Opus, MP4 | software, too slow | c2.android.av1-dav1d | kept up | 0.98, 5 |
| v01 H.264 720p, v02 H.264 1080p | hardware | goldfish h264 | kept up | 0.98 to 1.00, 6 to 17 |
| v03 H.264 1080p 20 Mbit/s | hardware | goldfish h264 | cannot keep up | 0.93, 249 |
| v04, v05 HEVC 8-bit 720p, 1080p | hardware | goldfish hevc | kept up | 0.99 to 1.00, 0 |
| v06, v07, v08 HEVC 10-bit 720p, 1080p, 1080p 15 Mbit/s | software, too slow | FFmpeg hevc | kept up | 0.99 to 1.00, 0 |
| v09 to v12 AV1 8 and 10-bit, 720p and 1080p | software, too slow | c2.android.av1-dav1d | kept up | 0.99 to 1.00, 0 to 1 |
| v13, v14 VP9 720p, 1080p | hardware | goldfish vp9 | kept up | 0.99 to 1.00, 0 |

Reading it: hardware kinds stay hardware; the emulator's software decoders run on a fast desktop
CPU behind a cpufreq file that says nothing, so the table's cautious 1.2 GHz guess calls them too
slow and the watchdog then finds they kept up, which the device memory ([DecodeMemory]) records
so the next file of that kind is judged as fitting. v03 shows the other direction: a hardware
decoder that could not keep up, caught by the watchdog. A first emulator run made while Gradle
was building on the same host dropped frames on most 1080p clips; these figures are from a second
run on an idle host.

Still to run (devices were taken back before it): the same matrix on the Mi TV with the fixed
build, normal and with `--ez software true` to set the budget table from the A53's own figures.
