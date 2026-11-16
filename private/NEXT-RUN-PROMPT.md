Run the TMPlayer LATER list hands-free, no devices, no questions. Read first: private/UX-INTERACTION-PLAN.md sections
"FINAL STATE of this run", "WRAP UP NOW, THE REST IS LATER", "DECISIONS (user answers)", "RESUME CHECKPOINTS", and AGENTS.md.

Rules (locked, from ~/.claude/CLAUDE.md): main session only orchestrates and prints one line per finished task; max 3 editing
workers at once, each in its own worktree (isolation: worktree), files that overlap run in different waves; Sonnet medium by
default, Opus high for player and design work; one read-only Sonnet reviewer per big diff, the worker fixes findings once; a check
failing twice marks the task blocked in the plan and the queue goes on; commit locally section by section; NEVER push, tag,
release, deploy, run langsync, touch the phone/TV (no adb, no installs), or change the real chat selection. Decisions not in
the plan: take the recommended default and log it in the plan under "Decisions made by Claude". Merge each branch into main with
`git add -f private && git commit` first, then CatalogTest + compile of app and desktop (JDK 21 path in docs/BUILDING.md, --max-workers=3).
Keep en.json keys sorted and never delete or change a key the locale files hold (private/DEAD-STRINGS.md). No em or en dashes.
Public copy says "video", never film, movie or uploader. Videos only: no audio playlists or music anywhere.

Order of work:
0. Stabilise: run :app:testDebugUnitTest and :desktop:test in full, per module; fix DownloadsSelectionKeysTest (fails only in the
   full desktop run), the unproven WIP commit f9e6b3e (RL10 + player labels: AudioButtonAnswerTest, BackStopsThePlayerTest, full
   player suite), and anything else red. Wave 1 together with item 1.
1. Remove audio and voice messages (ChatAudio.kt, isAudio in PlayerActivity, chat lists, web, desktop); keep a video's background
   sound and the "sound but no picture" fixes; add a test that audio items never show. (after item 0 touches PlayerActivity)
2. FQ8 cast: Chromecast and Google TV sender behind a build flag (not FOSS, F-Droid flavour leaves it out) plus the existing DLNA;
   one cast icon only when a device is found; phone, TV (remote focus), desktop where possible. Opus. A reviewer reads it.
3. RL13 (16 KB page-size gate script) and RL14 (docs) from private/PLAYER-RELIABILITY-PLAN.md.
4. Web follow-ups: anime provider on web, poster-tile badge on web, web sync (not built), restore offer after sign in.
5. Raju leftovers: resend row 2.3, country chip 2.1, "bar returns after 1 s of rest" (private/ux-review/raju-journey.md).
6. Inventory review waves R1 to R9 over private/ux-inventory (run `node scripts/ux-inventory.mjs` first), dimensions D1 to D8 in the
   plan (behaviour, input modes phone/TV/desktop, motion, look, words, speed with the compose-performance skill, access, common
   person). Fix what is clear; write bigger ideas into the plan instead.
7. Second Raju pass (private/UX-PERSONA-JOURNEY.md) on the final build, render tests only, fix what is clear.
Dropped, do not build: live streams, "Play a lighter copy" (FQ9), audio playlists (FQ14). Blocked on the user: Trakt and Simkl keys,
anime-skip client id (build behind the missing key), add-chats-to-devices, device passes.

At the end print once: merged tasks with commits, checks run and not run, Decisions made by Claude, a device checklist for later
(phone, TV, Tanix, Mi TV), the needs-the-user list, Edge cases, Caveats, Tips.
