# Persona walkthrough: "Raju", a common man who downloads and plays videos from Telegram

Started 2026-11-16 (user: "use an agent as a common man who just downloads and plays videos from
telegram ... check how ux friendly it is. review and fix every minute detail").

## Who Raju is
- 45, a phone and a living-room TV with a remote, a little English, reads the app's language slowly.
- Uses Telegram daily: his family and a few channels send videos. He wants to watch them on the big
  screen and keep a few on the phone for the train. He has never heard of a cache, a codec, a proxy,
  a stream window or a library.
- He does not read dialogs: he taps the biggest, brightest button. He gets lost after two levels.
- Patience: if nothing visibly happens in 2 seconds he taps again; after 10 he thinks it is broken.
- Cheap phone, little free space, mobile data that costs money, sometimes bad network.

## Journey (every step on phone, TV remote and desktop where it exists, web app where it exists)
1. First open: what is this app, what do I do (store page words, first screen, permission asks).
2. Sign in with Telegram: phone number, code, password (2 step), a wrong code, a slow code, a QR on TV.
3. The setup after sign in: does every choice have a safe default and a words-only explanation?
4. Finding my videos: Home, Chats, which chat has the video, folders, archive, search for a name.
5. Opening a video: the details page, what the buttons mean (Play, Download, Keep, Share).
6. Playing: start time, first frame, seeking, pause, resume where I left, subtitles, audio track,
   speed, rotate, mini player, Picture in Picture, going back; on TV: every key of the remote.
7. Downloading: one video, many at once, how much space, progress, pause, cancel, finished, where is it.
8. Space and data: low space warnings, mobile data warning, removing downloads, the streaming mode.
9. Trouble: no network, video gone from Telegram, a video that will not play, a crash, Send logs.
10. Coming back tomorrow: does it remember where I was, my list, my settings; updates; sign out.
11. Settings: can I find "make the text bigger", "stop downloads on mobile data", "delete everything"?

## How to run it (no device is allowed)
- Read the screens in code, and look at real pictures: the Compose render tests
  (`./gradlew :app:testDebugUnitTest --tests '*RenderTest'` writes PNGs to `build/device-test/review/`),
  desktop shots (`TMPLAYER_SHOTS=<dir> ./gradlew :desktop:test`), the web app over its fake Telegram in
  headless Chromium (`docs/ux-audit.md` "How to repeat it"), existing `design/` pictures.
- For each step write: what Raju sees, what he tries, what confuses or fails him, severity
  (blocker, annoying, polish), and the fix. Plain words and common sense beat rules.
- Fix what is small and clear (wording in en.json, order, default, spacing, missing feedback, missing
  animation, a missing empty or error state, a target too small) with a machine test, commit section
  by section. Bigger changes (new screens, flow changes) are written up with a proposal instead.

## Output
`private/ux-review/raju-journey.md`: the table per step (platform, finding, severity, fixed commit or
proposal), the top 10 things that would make Raju quit, and the leftovers for the user to check on devices.

## Progress / handoff
- 2026-11-16: brief written; waits for a free worker slot (T1 to T3 are running).
