# Anime episodes: titles, pictures and details for a long run (T11)

## The problem Raju hits

He opens Naruto Shippuden: 500 files named `Naruto Shippuden - 123.mkv`. Every row says "Episode 123" with the
video's own frame. No title, no picture from the show, no overview, and no hint that episodes 124 to 500
exist. Causes, each shown by a failing test first (`AnimeEpisodeCauseTest`):

1. AniList lists the whole run as one entry and gives no episodes, so a show matched there had no list at all.
2. `EpisodeNames.named` only takes a name whose season and number are the file's; the lookup answered for an
   AniList show with no episode, so the player's label stayed a bare code.
3. TMDB lists the run in 21 seasons; its "Absolute order" episode group (the one place that maps 123 to a
   season and an episode) was never read.
4. AniList's `streamingEpisodes` (a title and a picture per episode) were never asked for.

## The new flow

One guide per show, asked once (not once a row), merged from the sources in this order, the first with a real
title giving the title and each picture, overview and date coming from the first source that has one:

1. TMDB episode group of type "Absolute order" (each episode with its place in TMDB's seasons),
2. TMDB's seasons counted in order (two appended calls for 21 seasons, not 21),
3. Kitsu by the MyAnimeList id (no key, 20 episodes a page, asked only while titles are still missing),
4. Jikan (MyAnimeList's titles and dates, 100 a page, asked only while titles are still missing),
5. AniList `streamingEpisodes` (free: it comes with AniList's one facts call).

- The series page lists the run as one season counted straight through (what the files say), every episode
  up to the run's end, so T9's quiet missing rows, the unaired rows and "Find in other chats" work for anime.
  A file numbered per season (`S06E12`) is found through TMDB's own place; a number past its season's count
  is the absolute one (`S01E123`).
- Each episode names its source ("From Kitsu") on the line of facts under its title and on a missing
  episode's card; it appears with the title, in a line the card already holds, so nothing moves.
- A missing picture is never an empty box: the show's own picture, dimmed, with the episode's number over it.
  A missing title reads "Episode 123". The picture and the title fill in when they arrive; the 16:9 box and
  the title line are held from the first frame.
- Search for a missing anime episode asks for `Show 123`, the way its files are named, not `S01E123`.
- Player label, next-up card and the episodes panel read the same guide through `EpisodeNames`.

## Per platform

| | Phone | TV (remote) | Desktop | Web |
| --- | --- | --- | --- | --- |
| Rows with titles, stills, overview | yes | yes, same focusable rows | yes | not in this pass (see below) |
| Missing rows, unaired rows | quiet cards, tap opens the card | focusable, OK opens, Back closes and returns | click or Enter, Esc | the chat's own list, as before |
| Player label, next-up | via `EpisodeNames` | same | same | n/a |

Web: the web app has no AniList, Kitsu or Jikan and asks TMDB for no per-episode list, so an
absolute-numbered anime there stays the chat's own list (its `agrees` check keeps it from calling anything
missing). It needs a worker route for the guide first; web files are another worker's this round.

## Edge cases

- Offline or lookups off: nothing asked; what was kept for a day shows, else the page is as before.
- A source cut short (offline in the middle of Kitsu's 25 pages): its titles are used for now, the guide is not
  kept in memory, each finished source is kept for a day, so the next try resumes.
- Wrong match (TMDB's "Naruto", 220 episodes, for the 500 episode run): a list well short of, or far past, the
  run is dropped with its seasons; nothing from the other show is shown.
- Cour split (AniList lists cour one alone, TMDB the show as two seasons): the guide is TMDB's, so a file of
  cour two numbered on (`13`) or from one again (`S2 - 02`) finds its title.
- Running show (AniList's `episodes` null): the run's end is the next to air minus one; the next episode is
  Upcoming with its date; nothing aired is ever dated in the future.
- 3,000 episodes at most, 200 seasons, strings and picture hosts bounded; garbage is tested for every reader.
- Privacy: AniList, Kitsu and Jikan get an id and episode numbers; TMDB gets the show's title as every TMDB
  lookup does, never a file or chat name.

## Admission gate

Raju test: nothing to learn, it is just there. Decides for the user: no setting. Appears only for an anime
the data knows. Same words as T9's rows. No silent change of stored data (the guide is a new cache entry).
Parity: phone, TV, desktop in one shared page; web gap named above.
