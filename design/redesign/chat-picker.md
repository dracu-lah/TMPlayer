# Chat picker: Recommended that finds video channels, and no video counts

## Problem Raju hits
- A channel that holds thousands of videos is missing from Recommended. He then scrolls a list of 3,000 chats
  to find it, or never adds it.
- Rows say "About 120 videos" or "1,000+ videos". Telegram's number is a guess (a chat not opened yet, videos
  sent as files, ZIPs), so a wrong number makes him distrust the app.

## Root cause (found by reading, reproduced by `ChatPickerScaleTest`)
1. The band was made once, from the 60 most recently active chats (`ChatPicking.COUNT_AHEAD`), at most 3 s
   after opening. A video channel that is not among the 60 newest was never counted for the band.
2. The band was then frozen. The background count of the whole account (TV only) filled the numbers on the
   rows but never fed the band again.
3. A chat whose count had failed was left out and never asked again for the ranking.
4. Phone, desktop and web counted only the rows scrolled into view, so nothing counted the rest.

## New flow
- Counting is the app's own business and never shown. Rows show the name, the picture, the star and the tick.
- The first 60 chats in Telegram's recency order are counted first, so the band still appears within 3 s.
- The rest of the account is counted in the background (3 at a time, 1 on the TV, stopped when the picker
  closes): channels first, then groups, then people, recency inside each, since a video library is nearly
  always a channel. The band is ranked again as the counts arrive.
- Until the viewer ticks, stars, searches or filters, the band is re-ranked freely. After that, nothing in it
  moves: a chat that qualifies later is added at the end (at most 30 in the band).
- A chat that could not be counted is asked once more at the end of the pass.
- Each row of Recommended says why in one plain line: "Has many videos". In the fallback band (nothing has many
  videos yet) the line is "Has a few videos" and the band note drops its number.
- The foot says "3 chats chosen", with no video total.

| | Phone | TV | Desktop | Web |
| --- | --- | --- | --- | --- |
| Rows | name, star, tick; Recommended rows add the reason line | same, remote focus unchanged (Down, Right to Done, Menu stars) | same, Tab and arrows, Space ticks | same |
| Count again | gone (nothing to retry in the row) | Menu always stars | long press always stars | gone |
| Background count | 3 at a time, state every 2 s | 1 at a time, state every 5 s | as phone | 3 at a time |
| Short list of chats with no videos | none | kept (a chat that is ticked, starred or focused never folds) | none | none |

## Edge cases
- Empty account, offline, nothing counted in 3 s: the skeleton ends, the band appears when a count arrives.
- 3,000 chats: first band within 3 s, the whole pass inside the counter's budget (3 at once, flood waits
  waited out by the call that got them), nothing asked after the picker closes.
- A chat with a few videos is not in Recommended but is in the list and found by search.
- Already added chats keep their own band and are never recommended again.
- The TV fold ("N chats with no videos") is the one thing still derived from a count; it is not a number of
  videos, and a chat that is ticked, starred or focused never hides in it.
