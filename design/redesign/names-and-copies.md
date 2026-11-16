# Names and copies: season packs, caption links, subtitle languages, "Why no poster?", best copy (TS)

Source: `private/TELEGRAM-STREMIO-GAPS.md` (T2, T3, T4, T5, T6), the admission gate in
`private/UX-INTERACTION-PLAN.md`. Backup and sync (T7) are not in this note.

## The problems Raju hits

1. A chat holds "Harbour Notes S01E01 ... S01E06" and one file "Harbour Notes Season 2 Complete 1080p".
   The show page lists season 1 only; the pack sits in the chat as a loose tile with the show's name and no
   way to tell it belongs to the show.
2. A caption says "https://www.imdb.com/title/tt0111161/" and the app still guesses the title from the file
   name and picks the wrong poster, or none.
3. "Show.S01E02.Hindi.srt" and "English.forced.srt" are in the chat, but only codes ("hin", "en") were read, so
   a viewer who prefers Hindi saw them in file order.
4. A title has no poster and nothing says why. Raju thinks the app is broken.
5. The same episode is in the chat at 480p, 1080p and 2160p. With no history the app played the newest, which
   can be the 4K file a phone or a weak TV stick should not start with. Nothing said which one the app chose.

## New behaviour

### 1. Season packs on the show page (T2)

- Parser: `ParsedName.pack` is true for a name that names a season (or a span of seasons) and no episode:
  "Season 2 Complete", "S02", "S02 Combined", "S01-S03", "Seasons 1-3". A name with an episode, a range of
  episodes, an air date, or an extra word (Trailer) is never a pack. `seasonFrom` keeps the first season of a
  span (`season` stays null, so the file never files as season one).
- Shelf: a pack whose title is one of an established show (two episodes or more, as today) joins that show as a
  `SeriesPack` (`Series.packs`), never as a loose tile. A pack of a title with no show stays a plain tile, as
  today. Several packs of a season (720p and 1080p) are each a row.
- Page: under its season's episodes (or, for a season the chat holds only as a pack, as that season's only row)
  one quiet card "Whole season  ·  1080p  ·  6.2 GB" with the pack's own name under it, a Play button for the
  first episode of the file (the file itself: it opens at its start), and the usual Watched mark. A span pack sits
  under its first season and reads "Seasons 1 to 3, whole".
- Never in the episode count, the progress line, "up next" or Continue: a pack is a file, not an episode.

| | Phone | TV (remote) | Desktop | Web |
| --- | --- | --- | --- | --- |
| Where | last row of the season | same, Down from the last episode reaches it; OK plays | same, Enter plays, Tab order is row order | same, a row button |
| Season with a pack only | the season appears in the picker with its pack row | same | same | same |
| Watched | not marked per pack (the file's own Watched) | same | same | same |

### 2. A link in the caption or the name forces the match (T3)

- `MetaQuery.of` reads `imdb.com/title/tt0111161` and a bare `tt0111161` word (7 to 9 digits, a whole word),
  and `themoviedb.org/movie/603`, `/tv/1399` (a TMDB link also fixes film or show), in the name or the caption,
  ahead of the title guess. The caption is only read.
- An IMDb id resolves once through TMDB `find` (one call per distinct id per run, kept in memory); a file with
  no link makes no extra call. The answer is cached under a key that includes the id.
- Never trusted blindly: two different ids in one text are ignored and the normal guess runs; a line starting
  with `>` (a quote) is skipped; the viewer's own pick ("Choose the right poster") and `{tmdb-123}` win; a find
  that returns nothing, or a failed call, falls back to the title guess. The page shows the data's own title, so
  a wrong link is visible and the usual "Choose the right poster" corrects it. Offline: the title guess runs.
- No new UI. Same on every platform (core); the web asks through the same rules.

### 3. Subtitle language from full names (T4)

- `Hindi.srt`, `English.forced.srt`, `Spanish (Latin America).srt`, `Portuguese (Brazil)`, `Chinese (Simplified)`,
  the native spellings (`Español`, `Deutsch`, `Français`), regional codes (`pt-BR`), 40+ languages by name.
- `dub`, `dubbed` join the neutral words, `sdh` stays hearing. A language counts only at the end of the name
  (after the episode or year), so "Hindi Medium.srt" keeps its title.
- Result: the language chip and the order of "In this chat" follow the viewer's preferred languages.
  No UI change, no setting.

### 4. "Why no poster?" (T5, reduced)

One quiet line on a title page that has no poster, with the one action that fixes it. Never on a matched
video, never while the answer is still coming.

| Reason | Line | Action |
| --- | --- | --- |
| Lookups off | "Posters are off, so nothing is looked up." | Turn on lookups |
| Marked wrong by the viewer | "You marked this match as wrong." | Fix match |
| Name not recognised | the diagnosis in words ("This name has no title in it.", "Several seasons in one name.") | Fix match |
| Nothing found | "No title like this was found online." | Fix match |
| Found, not sure | "Found a title, but nothing here backs it." | Fix match |

- It replaces the round "Show poster and overview" button where it stood (one in, one out); the same action
  is the line's action. "Fix match" opens the existing "Choose the right poster".
- Phone: under the facts line, a text row with the action as a text button. TV: focusable (one Down from
  Play), OK runs the action, focus returns to the row. Desktop: under the title, keyboard Tab reaches it.
  Web: same line under the title.
- Plain words, no provider names in the line.

### 5. Best copy by default (T6, reduced: no new setting)

- When no choice, history or progress decides (the existing order in `VideoCopies.pick`), the default is no
  longer the newest copy but the best one this device plays well: the tallest copy that is not `tooSlow`
  (`DecodeJudge`), capped at 1080p on a phone (4K only when nothing else exists), newest on a tie. The viewer's
  own pick, a download, a started copy and the habit ("usually plays 720p") still come first.
- Episodes: the same default when nothing was watched yet in the show; once something was, "most like the last"
  still wins, and a copy partway through still wins over both.
- Shown: the copy the app would choose carries a quiet "Best for this device" in the copies list (the line under
  its heading), only where the list has two copies or more. Switch: the copies list, one tap, as today.
- Not done on purpose: a per-chat stored preference and a Settings row (gate rule 2: the app decides from facts;
  the copies list is the switch). Logged for the user's OK.

## Edge cases

- Pack: a name with a huge season number, "S99-S1" (not a range), 100,000 characters; a pack with a caption
  naming an episode (the caption's episode wins, as for any file); the same pack posted twice (two rows); a pack
  title spelled differently from the show (joins by the same alternates as an episode does); a show whose
  episodes are all in another chat (packs join per chat listing, as episodes do).
- Link: `tt` inside a longer word, a link in a quote, a 20 digit id, two ids, the id of a film on a show's file
  (the link decides; the page shows the data's title), no key or lookups off (nothing is sent).
- Subtitles: a name that is only a language ("English.srt"), two languages (the last wins), a title that is a
  language word, a language in brackets, upper case, NBSP.
- No poster line: lookups on but offline (the line is not shown until the answer is settled and known as "none"),
  a film with a year and no title match, a large font (wraps, the action drops under it).
- Copies: sizes with no resolution (sorted by size), all copies slow (the least slow stays), a copy on the device
  (stays first), 3,000 chats (the default is one pass over the copies of one video, no network).

## Machine checks

Unit and garbage tests in core (names, shelf, link, subtitles, copies, diagnosis), `SeriesShelf` scale with
packs, a D-pad test of the pack row (TV and desktop through the shared page), form-factor screenshot tests of
the pack row and the poster-less line (phone, TV, desktop), webapp tests for the web rules. Manual device
checks are left for the user.

## As built (decisions made by Claude)

- A pack of a show sits under its first season; a season held only as a pack appears in the picker with that
  one row and no gap rows. Packs never count as episodes. The pack row plays the file (the TV and desktop pages
  open it the way they open an episode).
- A link counts as backing for the match (`MatchBasis.Linked`), so a file named "clip_720p" with a link shows
  its poster. A link is dropped when the file's own year differs by more than one, when a film's link sits on a
  file that names an episode, or when TMDB does not know the id (the title guess runs instead).
- Subtitle names: "Hindi.srt" alone is Hindi; a bracket after something that is no language stays in the name.
- "Why no poster?" replaces the round "Show poster and overview" button on the phone sheet, the phone and TV
  page and the desktop page; the web gets the same line. "Found but not backed" and "nothing found" are one
  line on the web (the worker does not tell them apart). Offline or throttled says nothing.
- Best copy: default only; no per-chat stored preference and no Settings row. The web has no default copy
  picker, so it has no tag.
- Shots in this folder come from the desktop compose tests (the shared panel at a phone width, a desktop width and
  a doubled font). Robolectric phone and TV shots and the real devices are left for the user.
