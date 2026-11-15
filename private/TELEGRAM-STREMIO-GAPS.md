# Telegram-Stremio and TeleStremio: what to take (2026-11-16)

User, 2026-11-16: "Telegram-Stremio, check any features and stuff that can be taken from it ... add all of
them whatever necessary." Read-only research; nothing in the source was changed. Beside
[COMPETITORS.md](COMPETITORS.md) (its TeleStremio row and the 2026-11-13 sweep) and
[REUSE-MAP-2026-11-15.md](REUSE-MAP-2026-11-15.md).

## Sources and licence

- Upstream, read 2026-11-16: [README](https://github.com/weebzone/Telegram-Stremio),
  [site docs](https://weebzone.github.io/Telegram-Stremio/), all
  [releases](https://github.com/weebzone/Telegram-Stremio/releases) (server v5.3.3 of 2026-11-13; TeleStremio
  Android and desktop v1.0.0 of 2026-08-01 to v2.0.1 of 2026-11-12), the issue list, and the source of the helpers
  named below. The repo is GPL-3.0, last pushed 2026-11-14.
- **Issues:** all 20 top issues are closed and none has visible reaction counts; the ones that carry a user ask are
  [#5](https://github.com/weebzone/Telegram-Stremio/issues/5) (index by file name, not caption),
  [#6](https://github.com/weebzone/Telegram-Stremio/issues/6) (blacklist part files, but never a "Multi" audio file),
  [#13](https://github.com/weebzone/Telegram-Stremio/issues/13) (show a graceful error when metadata fails),
  [#14](https://github.com/weebzone/Telegram-Stremio/issues/14) (series episodes buffer: the answer is Telegram's
  data centres, forwarding does not move a file) and
  [#158](https://github.com/weebzone/Telegram-Stremio/issues/158) (season packs show in the library but not in
  the episode list).
- **Licence rule.** Both sides are GPL-3.0, but we write our own code: read the behaviour, write a Kotlin test
  from it, never paste. Worth reading, all GPL-3.0 and Python, in `Backend/helper/`:
  [metadata/parse.py](https://github.com/weebzone/Telegram-Stremio/blob/master/Backend/helper/metadata/parse.py)
  (the anchors that stop guessit inventing a season), [split_files.py](https://github.com/weebzone/Telegram-Stremio/blob/master/Backend/helper/split_files.py)
  (combined episode range, split key), [subtitles.py](https://github.com/weebzone/Telegram-Stremio/blob/master/Backend/helper/subtitles.py)
  (language alias table, ignore tokens), [zip_stream.py](https://github.com/weebzone/Telegram-Stremio/blob/master/Backend/helper/zip_stream.py)
  (ZIP64 local header, central directory from the tail), [skip_channel.py](https://github.com/weebzone/Telegram-Stremio/blob/master/Backend/helper/skip_channel.py).
  Not to read for code: `stream_formatter.py` (a port of AIOStreams' template language, which has its own
  licence, not checked). Its libraries are PTN (parse-torrent-title, MIT per its repo, not checked here) and
  GuessIt (LGPL-3.0): TMPlayer uses neither, `MediaName` is its own parser, so no new dependency comes out of this.
- Dates: upstream release dates are from `gh release list` (2026). The release page itself shows no year.

## What was filtered out, and why

| Dropped | Why |
| --- | --- |
| Multi-bot token pools, "multi token clients", extra Mongo databases to scale | Splits traffic over many bots to get past per-account limits. Telegram's API terms and AGENTS.md ("no Telegram limit is bypassed"). Also needs a server. |
| Skip channel (copy unmatched files to a channel, optional delete of the original), Announcement channel, Manual upload session, Add Content | Posts into or deletes from channels on the user's behalf. TMPlayer is read-only and "uploads nothing" (docs/FEATURES.md, "What it is not"). The useful part, telling the user what to fix, is kept as a local note (rows 16, 17). |
| Mass indexing, scan manager, "auto-index every new post", dead-link crawler, duplicate purge | Indexes channels whether or not you read them and deletes messages. TMPlayer indexes nothing by promise; it reads the chats you open, a bounded page at a time (AGENTS.md, Scale). |
| Tokens, daily and monthly data limits, subscriptions with payment screenshots, request page, viewer analytics, IP/ISP/VPN dashboard | Server and multi-user commerce. Tracking viewers is the opposite of the privacy page. |
| Permanent public stream links, Cloudflare Worker streaming, proxy and "direct" links, Tailscale remote access | A server relays Telegram bytes off the device. `docs/agents/telegram-terms.md`: the loopback stays on 127.0.0.1. COMPETITORS already lists signed share links as "TMPlayer must not copy". |
| Stremio and Nuvio manifest, catalog rows, Nuvio collections, per-token addon configure page, AIOStreams-style stream templates, Kitsu catalogs | Stremio protocol only. TMPlayer is its own player with its own Home rows (CP28). |
| Heroku, Koyeb, Render, orkestr, Docker, restart-and-pull-code, password reset tool, web panel themes | Hosting a server. |

## The table

Status words: **done**, **partly**, **planned** (a plan item exists, not shipped), **missing**. Verdicts: **have**
(nothing to add), **take**, **later**, **skip**. Platforms: P phone, T Android TV, D desktop, W web app. Size: S under
a day, M a few days, L a week or more. CP numbers are in [PLAN.md](PLAN.md); paths are from the repo root.
Parser work lives in `core/src/pureMain`, so one change reaches P, T, D and W together; W runs the same code through
`core/src/jsMain/.../WebRules.kt`.

### Parsing: titles, seasons, episodes, quality

| # | Feature | Upstream behaviour | TMPlayer status | Verdict | Plat | Size |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Movie: title, year, quality from the name | [README](https://github.com/weebzone/Telegram-Stremio#filename-and-caption-parsing): title, year, 720p/1080p/2160p; codec and source optional | **done.** `MediaName.parse` (`core/src/pureMain/kotlin/com/tmplayer/data/MediaName.kt:85`), tag vocabulary `:928-985`, golden corpus `NameCorpus.kt` + `NameGoldenCorpusTest`. Conservative on purpose: "a wrong title is worse than none" (`:77-82`) | have | P T D W | - |
| 2 | Series: `S01E04`, `2x04`, `Season 1 Episode 4` | Needs title + SxxEyy + quality | **done.** `PAIRED_EPISODE` `:911-920`, `EPISODE_ONLY`, `looseEpisode` `:192` | have | P T D W | - |
| 3 | Caption versus file name | Caption first, file name when no caption ([#5](https://github.com/weebzone/Telegram-Stremio/issues/5)); users asked to fall back to the name when the caption lacks terms | **done, opposite order.** File name wins when it names an episode; the caption's first non-empty line fills in only when the name has none (`MediaName.kt:103-120`), `tileTitle(fileName, caption)` `:240`. Better than upstream's caption-first, because a caption's first line is often an advert | have | P T D W | - |
| 4 | Guard against reading a number as season or episode | [parse.py](https://github.com/weebzone/Telegram-Stremio/blob/master/Backend/helper/metadata/parse.py): trust guessit's season only with explicit "Season N" or SxxExx; "(2011)" is not season 2011; "One Piece - 1172" is not S11E72 | **done.** `notAnEpisode` `:492-495` (year range, number after tags), `DASHED_EPISODE` needs spaces and 2 to 4 digits, `NameEdgeCasesTest` | have | P T D W | - |
| 5 | Release-group brackets, quality, codec and audio tags stripped | `[Judas]`, `x265`, `DDP5.1`, `Multi`, `ESub` removed before reading | **done.** `GROUP_TAG` `:928`, `TAGS`, language words in the tag list, `MediaFacts.read` | have | P T D W | - |
| 6 | "Multi audio" is not a split part ([#6](https://github.com/weebzone/Telegram-Stremio/issues/6)) | `.part001` skipped, "Multi.English.French.mkv" kept | **done.** `SplitPieces.pieceOf` marks `Name.part001.mkv` as uncertain until the sizes say it was cut (`SplitPieces.kt:34-35`, `cutAlike`) | have | P T D W | - |
| 7 | "Part 01", "CD01", "Disc02" are not joined | Deliberately left as separate files | **done.** `ParsedName.part` and `PART` `:796`: played one after another, never as one byte stream (`SplitPieces.kt:26-27`) | have | P T D W | - |
| 8 | Range of episodes in one file, any separator | [split_files.py](https://github.com/weebzone/Telegram-Stremio/blob/master/Backend/helper/split_files.py): `E01-E13` with separators hyphen, en dash, `~`, `+`, `&`, `,`, `to`; bounded 1 <= start < end <= 99 | **partly.** `EPISODE_RANGE` `:937` takes a hyphen after the word "ep" only; `S01E01-E02` and `S01E01-06` via `PAIRED_EPISODE`; `EXTRA_EPISODE` `:940` takes `-`, `&`, `+` after a first code. No test for `~`, "to", en dash, comma, or `E01 to E04` without the S | **take.** Cheap and the parser is already shaped for it; a miss today files a 12-episode file as episode 1 | P T D W | S |
| 9 | Whole-season file with no episode, "Season N Combined" | Filed as a full season entry; stream label "Season 2 Combined" and "E01-E13"; [#158](https://github.com/weebzone/Telegram-Stremio/issues/158) was the bug where such files hid from episode results | **partly.** The parser reads a season with no episode (`MediaName.kt:453-455`), `GlobalSearch.kt:348-357` folds a pack to its show's card, but `SeriesShelf.kt:9-11` leaves it a loose file ("nowhere in an episode list to put it", test `SeriesShelfTest.kt:234`). So a pack never shows on the show's page | **take.** A user who posts one pack per season sees no episode list, the case #158 reports. Design: a "Season N" row under the season heading that plays the pack, marked watched with the pack | P T D W | M |
| 10 | Several seasons in one name (`S01-S03`) | Refused with an explanation: one season per file | **missing.** `SEASON_ONLY` reads `S01` and ignores the range, so the pack files as season 1 | **take.** Fold into row 8: read the range, treat as a multi-season pack, never as season 1 | P T D W | S |
| 11 | Anime absolute numbers without a dash (`One Piece 1223 720.mkv`) | Strips year, quality and tags, takes the last 3 to 4 digit number; explicit `E`/`EP` wins | **partly.** `Show - 1223` (dash form) is read (`DASHED_EPISODE`, test `MediaNameTest.kt:343`); a bare trailing number is not, on purpose (titles and years) | **later.** Needs a per-chat "anime" switch so a number in a film title is never taken (row 12) | P T D W | S |
| 12 | Per-channel Anime flag | Each channel can be marked Anime; absolute numbering and Kitsu matching follow | **partly.** `MetaQuery.anime` is guessed from a fansub group or dash form (`MetaModels.kt:92-95`); no way to say "this chat is anime" | **later.** Pairs with row 11; a chat setting beside Speed per chat (CP48) | P T D W | S |
| 13 | Absolute to season mapping for long anime (Anime-List, AniBridge, Kitsu, TVDB) | v1.0.3 and v1.0.6: One Piece 1123, Naruto 016 land on the right season and episode | **missing.** AniList is asked first for anime (`OnlineMetadata.kt:286-288`), no episode map | **later.** Needs an offline map shipped in the app or a lookup per title; the map file is large and its licence is unchecked | P T D W | L |
| 14 | IMDb or TMDB link in the caption forces the match | Paste `imdb.com/title/tt...` or a TMDB link; the bot stamps it back so a re-forward matches | **partly.** `{tmdb-123}` in the name or the caption forces it (`MetaModels.kt:66,80-82`); the viewer can also pick the poster (`VideoCorrections.kt`). A bare `tt1234567`, an imdb.com URL or a themoviedb.org URL is not read, and TMDB's find-by-imdb call is not used (`MetaApis.kt`) | **take.** Read only, never write the caption back (that edits the user's message). TMDB `find` with `external_source=imdb_id` | P T D W | S |
| 15 | Metadata in 46 languages with English fallback | v2.0.0 | **done.** Localised metadata (CP23), `language+en` in the cache key (`MetaModels.kt:62`) | have | P T D W | - |
| 16 | Say why a file did not match | `analyze_metadata_failure`: part of a split, "combined" without a season, multi-season name, no quality, no title, or no provider match; each with the fix, shown in the Skip channel and in Stremio | **missing.** TMPlayer has "Wrong title?" with a report (`NameReport.kt`, `TitleReport.kt`) and the archive reasons (`Archives.kt:68-73`), but nothing that says why a tile has no poster or no episode number | **take.** Local, read only: a "Why no poster?" line on the detail page and in the title menu. This is the honest version of the Skip channel | P T D W | M |
| 17 | Skip channel (hold unmatched files, note what to fix) | Copies the message to a skip channel, optional delete of the original | **skip the channel.** Writes to a channel and deletes messages. The local equivalent is row 16, plus the existing Hide chat | skip | - | - |

### Split files, ZIP, archives

| # | Feature | Upstream behaviour | TMPlayer status | Verdict | Plat | Size |
| --- | --- | --- | --- | --- | --- | --- |
| 18 | `.mkv.001` pieces joined and seekable | [v1.0.2](https://github.com/weebzone/Telegram-Stremio/releases/tag/v1.0.2): grouping and seeking | **done.** `SplitPieces` (CP75, shipped 1.27.0), more name shapes than upstream (`.part001`, `.00`, `split -d`); missing piece reported (`SplitPieces.kt:237-247`, strings `grid.piece_missing`). Desktop `JoinedStreamBytes.kt`, web `webapp/src/stream/joined.ts` | have | P T D W | - |
| 19 | Duplicate split pieces ignored | v1.0.2 | **done.** `SplitPieces.duplicates` / `ordered` keep the newest post per number (`:165-179`) | have | P T D W | - |
| 20 | Stored ZIP and split `.zip.001`, with ZIP64 | v1.0.2, [zip_stream.py](https://github.com/weebzone/Telegram-Stremio/blob/master/Backend/helper/zip_stream.py): central directory read from the tail, no background download | **done.** `ZipArchive.kt` (ZIP64 `:171-172`), `ZippedVideos.kt`, PLAN 2026-11-13/14: phone and TV verified (Avengers 3 parts, Colony 18 parts after d702ad4); desktop `cf14670`, web `53cb16d..51572e0` built, not run on a real account | have (verify D, W) | P T D W | - |
| 21 | Compressed ZIP says so | v1.0.2: "clear message that they cannot be streamed" | **done.** `ZipBlocker.Compressed` (`ZipArchive.kt:34-70`, `player.zip_compressed`); CP85 lists RAR, 7z, tar and volumes with a reason (`en.json` `grid.archive_*`: compressed, password, huge, volume) | have | P T D W | - |
| 22 | Multi-disk `.z01 .z02 .zip` | Not supported upstream either ("compressed zip volumes index but do not seek") | **missing**, listed as archive volume (`SplitPieces.kt:398-400`; PLAN 2026-11-14 notes it) | skip. Rare, and both ends of the format would need reading | - | - |
| 23 | Streaming stops when the player closes | v1.0.2 | **done.** AGENTS.md ("backing out stops the download"), `LoopbackStream` | have | P T D | - |

### Subtitles

| # | Feature | Upstream behaviour | TMPlayer status | Verdict | Plat | Size |
| --- | --- | --- | --- | --- | --- | --- |
| 24 | Formats `.srt .vtt .ass .ssa .sub` | [subtitles.py](https://github.com/weebzone/Telegram-Stremio/blob/master/Backend/helper/subtitles.py) | **done.** `ChatSubtitles.EXTENSIONS` (`ChatSubtitles.kt:23`), subtitle zips looked inside (`:26-29`), `SubtitleText` | have | P T D W | - |
| 25 | Match a subtitle to its video by name and language | Title or IMDb id plus a language tag; extra tags forced, sdh, cc, dubbed ignored | **done, richer.** Ranking by album, reply, neighbour, then name, exact versus title match, season-pack zips strict (`ChatSubtitles.kt:138-246`, tests `ChatSubtitlesTest`, `ChatSubtitlesFocusTest`) | have | P T D W | - |
| 26 | Language from a full name or any ISO code | 48 languages, full name, ISO 639-2, ISO 639-1; unknown labelled Unknown | **partly.** `tagsOf` reads two and three letter codes only (`ChatSubtitles.kt:95-130`); "Hindi", "English", "Malayalam" in a name count as generic (`LANGUAGE_NAMES :262`), not as the language, so `Show.S01E02.Hindi.srt` ranks as language unknown. `dub` and `dubbed` are not in `NEUTRAL :260` | **take.** Map names to codes, add `dub`, `dubbed`, `eng-sdh`; keep the two letter output | P T D W | S |
| 27 | Add a subtitle by pasting a message link | Panel form, language auto-detected | **done in another shape.** t.me link opener (CP79), Pick a file (CP31), OpenSubtitles and MSone (CP31, CP33) | have | P T D W | - |

### Dedupe, quality choice, sort, stream titles

| # | Feature | Upstream behaviour | TMPlayer status | Verdict | Plat | Size |
| --- | --- | --- | --- | --- | --- | --- |
| 28 | Dedupe: same file, same quality | "Replace Mode"; v1.0.0 de-duplication; duplicate finder in v1.0.6 that purges messages | **done as a view rule, no deleting.** `SameVideo` (`:1-30`), `VideoCopies`, `EpisodeCopies`, `TitleLedger`, "Not the same video" in `VideoCorrections.kt`. Open: CP107 fold by TMDB id | have (CP107 stays) | P T D W | - |
| 29 | Pick the best copy, sorted by quality | v1.0.2 "best quality first"; per-token sort order and quality filters | **partly.** `EpisodeCopies.closest` picks the copy most like the last watched (language, then tags, then size), `SeriesShelf.pick` (`SeriesShelf.kt:771-775`), `QualityPicker` (`Series.kt:448`), `TelegramQualities` and `DecodeJudge` for what the device can decode. No stored preference: a new show starts from "newest" | **take.** A per-chat (and global default) "Preferred copy": a resolution ceiling or best, a language, remembered, never above what `DecodeJudge` says the device plays | P T D W | M |
| 30 | Stream title from the caption, cut at the extension | v1.0.2 | **done.** `tileTitle`, `ParsedName.query`, `EpisodeCopies.label` ("Tamil, 1080p, HEVC, 1.8 GB") | have | P T D W | - |
| 31 | AIOStreams-style stream templates | `stream_formatter.py` | Stremio only | skip | - | - |
| 32 | Dead link check | Daily check of every indexed message | **partly.** `StaleEntries` removes a Continue or Watch later entry only on a confirmed "gone", never on a network error (`StaleEntries.kt:3-10`) | have; the crawler is skipped (see filter) | - | - |

### Chats, search, scale, scans

| # | Feature | Upstream behaviour | TMPlayer status | Verdict | Plat | Size |
| --- | --- | --- | --- | --- | --- | --- |
| 33 | Add a channel by @username, t.me link, invite link or id | v2.0.0 | **done mostly.** CP79 (links, invites, join by @username) built; CP74 chat info sheet still open | have (CP74 stays) | P T D W | - |
| 34 | Global search across extra channels with a user session | README, tagged GLOBAL | **done.** `GlobalSearch.kt`, `AllChatsSearch.kt` (CP29), only chats the account has joined | have | P T D W | - |
| 35 | 100,000 file channels and huge backups do not run out of memory | v2.0.0 | **done and tested.** `SyntheticAccount` + `HomeScaleTest` (`ui/src/synthetic/...`), `ChatVideoCount` flood-wait budget | have | P T D W | - |
| 36 | Scan with progress notification, resume, auto-index | v1.0.6 | **skip.** TMPlayer does not index. Its nearest part, "a chat's first content within 3 s and the app waits for Telegram before saying empty", is done (FEATURES.md "Catches up on demand") | skip | - | - |
| 37 | "A scan that received nothing is not complete" | v2.0.1 | **done in spirit.** `ChatSyncCompleteTest`, empty states wait for Telegram | have | P T D W | - |
| 38 | Instant sign-in on a big account, chats load in the background | v2.0.1 | **done.** HX8 and the AGENTS.md Scale rule | have | P T D W | - |

### Backup, sync, accounts

| # | Feature | Upstream behaviour | TMPlayer status | Verdict | Plat | Size |
| --- | --- | --- | --- | --- | --- | --- |
| 39 | Backup the library to Saved Messages | v1.0.6: backup to Saved Messages, restore after reinstall | **partly.** CP53 file backup (`Backups`, `BackupService.kt`, settings, favourites, watched, Watch later, resume, bookmarks); CP89 opt-in sync of places, Watched, Watch later, favourites in one `#TMPlayerSync` message (`SyncDocument.kt:31-40`, `ResumeSync.kt`). That sync file leaves out settings, bookmarks, corrections and locks on purpose | **take (bookmarks and viewer corrections only).** Two new lists in the same one message, opt-in, size-capped; settings and locks stay out | P T D W | M |
| 40 | Logging out backs the library up first; a different account starts empty with an offer to restore | v2.0.1 | **missing.** Sign out lists what is lost and offers only "also delete my downloads" (`en.json` `sign_out_message`, `sign_out_loses`) | **take.** A "Save a backup first" row in the dialog (file on every platform; Saved Messages when sync is on); after sign in, offer to restore a backup found | P T D | S |
| 41 | Import another library, import keeps scan progress | v2.0.0, v2.0.1 | Their library format is a server index | skip | - | - |
| 42 | One person, one library per account; several users per server | One Telegram account per app, tokens for others | **planned.** CP84, CP108 (accounts), CP96 (viewers) | have (planned) | P T D | - |
| 43 | Resume across devices | Single user app, no resume sync documented | **done, ahead.** CP89 | have | P T D W | - |

### App behaviour and errors

| # | Feature | Upstream behaviour | TMPlayer status | Verdict | Plat | Size |
| --- | --- | --- | --- | --- | --- | --- |
| 44 | Android TV and Google TV layouts | v1.0.1 | **done**, with D-pad tests and the TV home row | have | T | - |
| 45 | In-app updater with progress, notes, retry | v1.0.1 | **done.** FEATURES.md "Keeps itself current" | have | P T D | - |
| 46 | Friendly inline errors on sign in, back button on every step, QR refresh | v1.0.1 | **partly.** `SignInSteps.kt`, QR first on TV; email code and forgot password open (CP78) | have (CP78 stays) | P T D | - |
| 47 | "Join required", "Plan expired", "Limit reached" cards | Messages in Stremio | Server subscription states, no equivalent | skip | - | - |
| 48 | Playback buffering answer (#14: another data centre, forwarded files stay where they were) | Maintainer's explanation in the issue | **planned.** COMPETITORS item 1 "Say why a video is not starting", CP83 speed note; a data-centre line is not there | later. A line in the stall sheet ("This video is stored in another Telegram data centre") if TDLib exposes it; unverified, so check before building | P T D | S |
| 49 | Themes, fonts, animations, 13 themes | v1.0.0 | **done in TMPlayer's own way.** CP86 Black, subtitle fonts (`SubtitleFonts.kt`), one theme on every platform (CP10b) | skip | - | - |
| 50 | Keyboard command palette, shortcuts | v5.3.3 panel | **done on the desktop.** `Ctrl+F`, `/`, `?` (FEATURES.md) | have | D | - |

## Counts

50 rows: **have 30, take 9, later 4, skip 7.** Takes: rows 8, 9, 10, 14, 16, 26, 29, 39, 40. Later: rows 11, 12, 13, 48.
Skips in the table: rows 17, 22, 31, 36, 41, 47, 49; the filtered list above adds the server and multi-bot features
that never got a row.

## Worker tasks for every "take"

Rules for all of them: commit locally section-wise; no `langsync`; new text only in
`core/src/commonMain/resources/i18n/en.json` (sorted keys; each worker adds its own block, merge in the order
below); no em or en dashes (run the grep in AGENTS.md); every parser change gets lines in
`GarbageInputTest` (core); "video", never "film" or "movie", in any public copy. Tasks are grouped by files so no two
touch the same file at once. `depends:` says which must be merged first.

### T1. Parsing rules: episode ranges, multi-season names, whole-season marker (rows 8, 10, and the parser half of 9)

- model: Sonnet medium. depends: none.
- files: `core/src/pureMain/kotlin/com/tmplayer/data/MediaName.kt`, `core/src/jvmTest/kotlin/com/tmplayer/data/MediaNameTest.kt`,
  `NameEdgeCasesTest.kt`, `NameCorpus.kt`, `GarbageInputTest.kt` (this task owns all edits to it in this batch).
- Do: (a) read a range with any of hyphen, en dash, `~`, `+`, `&`, `,`, "to" between two episode numbers, with or
  without `S` and `E` on the second ("S01E01-E13", "E01 to E04", "Ep 1~4", "S01 E01 & E02"), bounded 1 <= first < last <= 999,
  into `ParsedName.lastEpisode`; a comma list that is not ascending is not a range. (b) `S01-S03`, "Seasons 1-3",
  "S01 to S03": a new `ParsedName.seasonEnd`, `season` stays null so the file never files as season 1. (c) a
  `pack` value on `ParsedName` (true for "Season N Complete", "S02 Combined", a season with no episode and no
  range), so T2 can show it; no behaviour change for anything that has an episode today.
- Done when: every shape above has a test with the name and the expected `season`, `episode`, `lastEpisode`,
  `seasonEnd`, `pack`; the existing 99-case corpus and `NameGoldenCorpusTest` are unchanged; `./gradlew :core:jvmTest`
  green.
- Machine test: garbage lines in `GarbageInputTest` for `"E01-"`, `"E~~"`, `"S01-S"`, `"S99-S1"`, `"E999999999-E1"`,
  `"to to to"`, a 100,000 character run of digits and hyphens, and Arabic-Indic digits; the parser must return
  without an exception and in under 50 ms (the test already feeds every parser truncated, wrong-typed, huge and random input).
- Platforms: P T D W (pure code). Size S.

### T2. Season packs on the show's page (row 9)

- model: Sonnet medium. depends: T1.
- files: `core/src/pureMain/kotlin/com/tmplayer/data/SeriesShelf.kt`, `core/src/jvmTest/kotlin/com/tmplayer/data/SeriesShelfTest.kt`,
  `ui/src/shared/kotlin/com/tmplayer/ui/browse/Series.kt` (and the TV and desktop series pages only through it),
  `core/src/jsMain/kotlin/com/tmplayer/web/WebRules.kt` only if it re-implements the grouping (check first; if it calls
  `SeriesShelf`, leave it).
- Do: a pack ([ParsedName.pack], season known, no episode) of a show that already has a card is listed on that season's
  heading as one "Season N, whole" row that plays the file, on every platform with the remote path and focus the
  episodes rows have; Watched on the pack marks the season (reuse `ShowMarks`); a pack of a show with no other file
  stays a plain tile as today (`SeriesShelfTest.kt:234` keeps passing for that case). A pack that names its range
  ("S01 E01-E13") fills those episodes' `alsoIn` instead, as "S01E01E02" files do (`SeriesEpisode.alsoIn`).
- Done when: a unit test for each of the three cases; a render shot of the series page with a pack on phone, TV and
  desktop (form-factor screenshot tests, `docs/agents/screenshots.md`); a D-pad test that Down from the last
  episode of the season reaches the pack row and `assertIsFocused()` holds.
- Machine test: `SyntheticAccount` with a chat of 20,000 videos in which 300 shows each carry 5 packs: arranging
  the shelf stays inside today's time budget (compare with `HomeScaleTest`); no pack is ever dropped.
- Platforms: P T D W. Size M.

### T3. Caption and name links force a match (row 14)

- model: Sonnet medium. depends: T1 (only to avoid a second edit of `GarbageInputTest`).
- files: `core/src/pureMain/kotlin/com/tmplayer/online/MetaModels.kt`, `core/src/pureMain/kotlin/com/tmplayer/online/MetaApis.kt`
  (TMDB `find` by IMDb id), `core/src/pureMain/kotlin/com/tmplayer/online/OnlineMetadata.kt` (the forced id path),
  new `core/src/jvmTest/kotlin/com/tmplayer/online/CaptionLinkTest.kt`.
- Do: `MetaQuery.of(fileName, caption)` also reads `imdb.com/title/tt0000000`, a bare `tt0000000` word (7 to 9 digits,
  whole word) and `themoviedb.org/movie/603` or `/tv/1399` in the caption or the name, ahead of the title guess; a
  TMDB link also fixes the kind. An IMDb id resolves through `/find/{id}?external_source=imdb_id` once and is cached
  with the other lookups. The caption is only read, never edited. A malformed or conflicting link (two different ids)
  is ignored and the normal guess runs; the viewer's own pick (`VideoCorrections`) still wins.
- Done when: tests with each URL shape, a caption with two ids, a `tt` word inside a longer word, a link in a quote,
  offline (the title guess still runs); `OnlineMetadata` makes no extra network call for a file with no link.
- Machine test: add the new link strings to `GarbageInputTest` (T1 owns that file, so hand the list to it or run this
  after T1 is merged); a fake `HttpTransport` asserts exactly one `find` call per distinct IMDb id.
- Platforms: P T D W. Size S.

### T4. Subtitle language from names (row 26)

- model: Haiku low is enough for the table, Sonnet medium for the tests. depends: none.
- files: `core/src/pureMain/kotlin/com/tmplayer/player/ChatSubtitles.kt`, `core/src/jvmTest/kotlin/com/tmplayer/player/ChatSubtitlesTest.kt`.
  (Does not touch `GarbageInputTest`; its subtitle lines already cover the file names, add any new ones to T1's list.)
- Do: `languageCode` maps full English names and the common native spellings to the two letter code (the 48 upstream
  languages: English, Hindi, Tamil, Telugu, Kannada, Malayalam, Bengali, Marathi, Punjabi, Gujarati, Urdu, Spanish,
  French, German, Italian, Portuguese, Russian, Arabic, Japanese, Korean, Chinese, Thai, Vietnamese, Indonesian, Malay,
  Filipino, Turkish, Dutch, Polish, Swedish, Norwegian, Danish, Finnish, Greek, Hebrew, Persian, Ukrainian, Romanian,
  Hungarian, Czech, Swahili and the rest); `dub`, `dubbed`, `sdh` variants join `NEUTRAL` or `HEARING`; a name with
  two language words keeps the last, as upstream. "Show.S01E02.Hindi.srt" gets language `hi`, so a viewer who prefers
  Hindi subtitles sees it first (`rank(... preferred = ...)`); a title that is a language word ("Hindi Medium.srt")
  keeps its title: a word counts only when it is last, after the episode or year.
- Done when: 25 table tests over names with the language as a name, as a code, as a regional code (`pt-BR`), with
  `forced` and `sdh` after it, and as part of the title; the 10-result cap and `NameMatch` ordering tests pass.
- Machine test: a property test over every `(alias, ext)` pair that the base key equals the key without the tag;
  `ChatSubtitlesFocusTest` still passes (TV focus on the list).
- Platforms: P T D W. Size S.

### T5. Why a title has no match, in words (row 16; also the "graceful error" of #13)

- model: Sonnet medium. depends: T1 (reads `seasonEnd`, `pack`).
- files: new `core/src/pureMain/kotlin/com/tmplayer/data/NameDiagnosis.kt`, new `core/src/jvmTest/kotlin/com/tmplayer/data/NameDiagnosisTest.kt`,
  the detail page and title menu entry in `ui/src/shared/kotlin/com/tmplayer/ui/browse/` (one file, the detail page; find it
  with `grep -n "Wrong title" ui/src/shared`), `en.json` block `name_diagnosis`.
- Do: a pure function `NameDiagnosis.of(item)` returning one of: part of a split, several seasons in one name, "combined"
  with no season, no quality or resolution word, no title left, a title shorter than the matcher's minimum, or "no
  provider found it" (known only after a lookup). Each has a one-line fix in plain words ("Name it 'Show S02 E01-E05'
  or 'Show S02 Complete'"). The detail page shows the line only when the poster is missing, with the existing
  "Wrong title?" and the `{tmdb-123}` hint beside it; a TV remote reaches it with one Down from the actions.
- Done when: one test per reason with a real-shaped name; the line never appears for a matched video; strings are
  in `en.json` only; render shots on phone, TV, desktop; focus test on TV.
- Machine test: run `NameDiagnosis.of` over `NameCorpus` and over the golden corpus: it must never throw and must
  return at most one reason; a garbage line in `GarbageInputTest` (via T1).
- Platforms: P T D W. Size M.

### T6. Preferred copy, kept per chat (row 29)

- model: Sonnet medium (settings and picker), Opus high only if `DecodeJudge` interplay gets deep. depends: T2 (both edit `Series.kt`).
- files: `core/src/pureMain/kotlin/com/tmplayer/data/EpisodeCopies.kt`, `core/src/pureMain/kotlin/com/tmplayer/data/SettingsModels.kt`,
  `SettingsBook.kt`, `ui/src/shared/kotlin/com/tmplayer/ui/browse/Series.kt` (picker row), the Settings page that holds
  Speed per chat (CP48; `grep -n "speed_" core/src`), `core/src/jvmTest/kotlin/com/tmplayer/data/EpisodeCopiesTest.kt` (new) and
  `SeriesShelfTest.kt` only for the pick tests.
- Do: a stored "Preferred copy" per chat and one default: `Best`, `Up to 2160p`, `1080p`, `720p`, `Smallest`, plus an
  optional audio language. `EpisodeCopies.closest` and `SeriesShelf.pick` use it only when nothing was watched yet in the
  show ("most like the last" still wins after that, and a copy partway through still wins over both). Never above
  what `DecodeJudge` says the device plays: it offers the next lower one with the existing "A 1080p copy will play"
  note. Backed up by CP53's file and CP89's sync (as a setting it stays out of sync, per `SyncDocument.kt:38-40`).
- Done when: tests for each preference, a tie by size, a missing resolution (documents state none), a language that no
  copy has (falls back, says nothing); TV: the setting is reachable with the remote and focus returns where it was.
- Machine test: `SyntheticAccount` with 3,000 chats and a show of 400 episodes in 4 sizes each: `pick` for the whole
  show runs once per episode with no allocation per comparison (existing `ScaleCpuTest` budget).
- Platforms: P T D W. Size M.

### T7. Backup before sign out, restore after (row 40) and bookmarks plus corrections in the sync file (row 39)

- model: Sonnet medium. depends: none (its files are alone in this batch).
- files: `core/src/commonMain/kotlin/com/tmplayer/data/BackupService.kt`, `SyncDocument.kt`, `ResumeSync.kt`,
  `core/src/pureMain/kotlin/com/tmplayer/data/ResumeSyncDoc.kt`, `core/src/jvmTest/kotlin/com/tmplayer/data/ResumeSyncDocTest.kt`,
  `SyncDocumentTest.kt`, `BackupTest.kt`, `BackupCoverageTest.kt`, the sign-out dialog (`grep -n "sign_out_message" app ui desktop webapp`;
  one file per platform) and the sync switch row in Settings (`ResumeSyncRowsTest`).
- Do: (a) the sign-out dialog gets a "Save a backup first" row, on by default, that writes the CP53 file through the
  platform's own picker (and does nothing without the user's choice of place); the sign-in screen, after a different
  account signs in on a device that holds a backup from another, offers "Restore a backup". (b) with sync on, the one
  `#TMPlayerSync` message also carries bookmarks and viewer corrections (separate, wrong match, rename, poster pick),
  each list capped like the others, a removal remembered for a month; settings, locks and PINs still never travel.
  The Settings row's explanation lists exactly what the file holds.
- Telegram terms check (`docs/agents/telegram-terms.md`, "nothing is done on the user's behalf without them
  knowing"): the Saved Messages write is the same one CP89 already makes, opt-in, one message edited in place, the
  explanation names it; no new write, nothing posted to a chat or channel. Write that line in the PR.
- Done when: round trip tests across a version bump (older file read, newer file refused as `backup.too_new`),
  a file cut short or with wrong types (the reader trusts nothing, `GarbageInputTest`), two devices with different
  bookmark sets merge to the union, a removal wins over an older add; dialog render shots P T D, and the focus test
  that the dialog's default button on TV is the safe one.
- Machine test: a sync document with 5,000 bookmarks and 2,000 corrections stays under one Telegram message's text
  limit after the existing gzip step, or is split by the cap with the oldest dropped first (assert the cap), and
  encode plus decode of 100,000 mixed entries stays within the CPU budget.
- Platforms: P T D (W has no account switch: only the sync half). Size M.

### T8. Leftovers for the user and one machine check (rows 20 to 23, not a code feature)

Everything for split files and ZIP exists on all four platforms; what is not proven is below. No worker should
re-write it.
- Run on a real account: desktop (`PlayerMedia.kt` `zipWindow`, `JoinedStreamBytes.kt`) and the web app
  (`webapp/src/stream/joined.ts`, `zip.ts`): a 3-piece stored ZIP and an 18-piece one (Colony), seek to the end, and
  the "compressed" card. Not run per PLAN 2026-11-14.
- One worker task, S, files `desktop/src/test/.../ZipWindowBytesTest.kt` and `webapp/src/stream/zip.test.ts` only:
  a synthetic ZIP64 whose single stored entry starts beyond 4 GiB (a fake reader that answers ranges from a formula,
  no 5 GB file) with 18 pieces; assert the entry offset, the size, and that a read at the last byte asks only the last
  piece. Done when both suites pass; they are the guard for the "Colony" failure class.

### Order and conflicts

1. T1, T4, T7, T8 in parallel (disjoint files).
2. Then T2 and T3 (T2 needs T1; T3 only touches `GarbageInputTest` through T1's list).
3. Then T5 (needs T1) and T6 (needs T2; same `Series.kt`).
- `en.json` is touched by T2, T5, T6, T7: each adds a block under its own key prefix; merge in the order T7, T2,
  T5, T6 and rebase mechanically (the file is JSON with sorted keys, so a conflict is only ever a neighbouring line).

## Later, skip, and what the user should decide

- **Later:** rows 11 and 12 (bare absolute numbers behind a per-chat Anime switch), 13 (absolute to season map, needs
  a data licence check and a size budget), 48 (data centre line in the stall sheet, only if TDLib exposes it).
- **Ask the user:** whether a pack row (T2) should also show in Continue watching. Default chosen: yes, as the show.
- **Not taken, said plainly:** nothing that writes to a channel, deletes a message, runs a bot, or serves bytes off the
  device. Telegram's own restrictions stay on (`MediaMapper` still skips restricted messages; no new path reads a
  restricted chat's pack or subtitle).
- Tips from upstream for the plan's own **Edge cases**: a part-file guard that spares "Multi" audio names (done), an
  explanation for every failed match (T5), "do not mark a scan complete when Telegram sent nothing" (done as the
  empty-state wait), and a ZIP of stored entries over 4 GB (T8).
