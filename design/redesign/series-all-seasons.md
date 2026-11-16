# Series page: every season and episode the data knows (T9)

## The problem Raju hits

A show has 6 seasons and the chats hold 2. The page lists 2 seasons and 7 episodes, so Raju thinks
the app lost the rest, or that the show is short. Inside a season with 5 of 10 episodes, the gaps are
invisible: episode 3 simply is not there. Nothing says "this exists, you do not have it", and nothing
helps him look for it.

## The new flow

- The season picker lists every season the metadata knows, in order, specials last (only when the
  chats hold one). A dot marks the seasons Raju has. A season he lacks is quiet (lower contrast) with
  one plain line: "Not in your chats yet", or "Airs 12 Mar 2027" for one not aired.
- Inside a season every episode is a row, by number, with its data title. A video he has is the card
  it always was (plays). An episode he lacks is a quiet card: code, title, "Not in your chats yet"
  (or "Airs <date>"). It never has a Play button.
- Tapping a missing card opens a small card: what it is ("S02E04 Harbour at night"), "Not in your
  chats yet", and one button, "Find in other chats", which opens search on `<show> S02E04`. An unaired
  episode says when it airs and offers nothing else.
- "Only show what I have" is one switch on the page's round buttons (all platforms, remembered per
  device). On: the old list, only his videos and seasons. Off (default): the honest full list. It is
  absent when there is nothing missing.
- No video counts as a promise (user rule): the picker says "10 episodes" only from the metadata
  and marks "in your chats" by a dot, not by a number.

## Per platform

| | Phone | TV (remote) | Desktop (keyboard, mouse) | Web |
| --- | --- | --- | --- | --- |
| Season picker | tabs, dot on seasons held | tabs, Left/Right pages through seasons (as today) | dropdown, dot and quiet text for missing | tabs, same dot |
| Missing row | quiet card, 48 dp tall minimum, tap opens the card | focusable like an episode (Down passes through all rows), OK opens the card, Back closes it and focus returns to the row | focusable, Enter and click open the card, Esc closes | button row, Enter and click open the card |
| Play | never on a missing row | never | never | never |
| Find in other chats | closes the page, opens search on the words | same | opens the search palette on the words | opens search on the words |
| Only what I have | round button after Watched | same, a focusable round button | same | same |

## Edge cases

- No metadata, offline, or a film: the page is exactly as it was, no empty skeletons.
- Wrong match (the data's seasons disagree with most of the chats' episodes) or another numbering
  (anime counted straight through): `EpisodeGuide.agrees` is false, the page lists what the chats
  hold and calls nothing missing. One season numbered past the data's list: that season keeps the
  chats' list alone, the others still show their gaps.
- A file holding two episodes ("S01E09E10"): the second is not listed as missing, its file is the
  first's row. Split halves ("E06a", "E06b") stay two rows, one number.
- Specials (season 0): shown only when the chats hold one; the provider's season 0 is mostly
  featurettes and is never offered as missing.
- Huge: 20 seasons and 1,000 episodes. Seasons come from the cached extras (one answer), episode
  numbers from a season's count, names and dates from that one season's listing, asked only for the
  season on screen. No request per row. Rows stay in a lazy list; past 120 episodes the season is cut
  into ranges as today. Counts above 3,000 per season, more than 200 seasons, and negative or absurd
  numbers are dropped.
- Garbage answers (negative counts, duplicate seasons, bad dates): bounded and tested.
- Failing or slow listing: the rows come from the counts; names fill in when the listing arrives.
- A season with no count and no listing (rare): one quiet note with the Find button, no rows.

## What changes in the code

- `EpisodeGuide` (core) is the merge (it already merged the chat's show with the seasons list for the
  detail page): now with specials, caps, `agrees`, count-synthesised episodes, the next episode's date,
  and `rows()` (Have or Gap) for the series page.
- `SeriesPanel` (ui/shared, used by phone, TV and desktop) lists `rows` instead of only the videos.
- The detail page's season section reads the same function, so the two pages agree.
- Replaced, not kept: nothing is parallel. The old per-season "N episodes" line on a tab becomes the
  dot plus the data's count.
