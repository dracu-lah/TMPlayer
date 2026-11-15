# Chats, favourites and the words people type (Raju groups A, D, H)

Small, clear fixes (patch scale) with one real change in behaviour: a star mode on the phone's chat
list. The gate answers are for that one; the rest are corrections.

## Problems Raju hit

| # | Where | Problem |
| --- | --- | --- |
| 4.2 | phone Chats | "Jump back in" repeated the list when the list is short, so every chat showed twice |
| 4.3 | phone Chats | the only way to add chats was a bare person-plus icon |
| 4.4 | phone Home | the favourites card said "press and hold any chat", a gesture in a card, with no button |
| 4.6 | phone Search | "Continue watching" showed the file name with dots and a handle |
| 4.8 | phone Search | a typed search took three or four Backs to leave: Back cleared and then raised the keyboard again |
| 11.2 | phone, desktop | Settings search matched titles, not meanings ("bigger", "log out", "delete") |
| 10.3 | web 390 | Sign out was the widest, filled red button on Settings |
| 4.10 | web 390 | two floating bars (chat bar over the main bar) |
| (T1) | Add chats | the search box had no clear control; a cross on the TV took Down from the rows |

## What changed

| | Phone | TV (remote, focus) | Desktop (keyboard) | Web |
| --- | --- | --- | --- | --- |
| Jump back in | only over a list longer than the strip (more than 8 chats) | same rule; with 8 or fewer the remote starts on the first chat row | n/a (no strip) | n/a |
| Choose chats on the Chats bar | pill with the word "Choose chats" beside the glyph at a normal font; glyph alone at a large font (the bar keeps its room) | unchanged (glyph in the top bar) | unchanged | n/a |
| Favourites card on Home | "Star a chat to get a row of its videos here." and a button "Choose favourites" opening the chat list in star mode | unchanged text, never focusable (the remote holds OK on a chat) | n/a (no card) | n/a |
| Star mode | a tap stars or unstars a chat; a line says so with Done; ends on Done, Back or leaving the list | none: a remote has the hold menu | none | none |
| Search Back | Back clears the words without raising the keyboard, then closes: two Backs | unchanged | unchanged | n/a |
| Search "Continue watching" | the name Home's tiles use | n/a | n/a | n/a |
| Add chats search | cross in the box once there are words; Back clears the words, then leaves | no cross (it took the remote's Down); Back from the field clears the words, from a row climbs as before | cross in the box; Esc (Back) clears the words first | n/a |
| Settings search | every row can carry its own words (a catalog key `settings.keywords_<id>`), matched by word start and ranked under the title | no box on the TV | same index (core), so the desktop gets them too | n/a |
| Sign out | n/a | n/a | n/a | an outlined row, red words only, a question first that names what goes with it |

## Feature admission gate (star mode only)

1. Raju test: reached from a button that says "Choose favourites", the first line says what a tap does; Done leaves.
2. Decides for the user: no setting; a mode that ends by itself on leaving the list.
3. Appears when relevant: only from that card, which shows only when no chat is starred.
4. One place: reuses the favourites store and the star on the row; no second list.
5. No silent change: nothing changes for existing users; the hold menu still works.
6. Plain words: "Tap a chat to star it. Tap it again to take the star off."
7. Parity: the TV and desktop have their own ways (hold menu, context menu), noted above.
8. Reversible: tapping again removes the star.

## Edge cases

- Empty chat list: the card's button opens an empty list with the line and Done; nothing breaks.
- Huge account: star mode adds one item to the list; no work per row.
- Rotation or process death: `starMode` is saved with the page state; leaving Chats clears it.
- Back: while star mode is on it ends the mode before anything else.
- A chat starred in star mode shows its star at once (the row's existing star pop).
- Font at 2x: the Chats bar pill drops its word; the star line wraps.
- RTL: the pill and the line mirror with the layout; the person glyph is never mirrored.
- A locale without the new keys reads English until the user's next langsync.
