# Phone and tablet audit (2026-11-25)

Every phone and tablet screen below was drawn with fake data at font 1.0, 1.3 and 2.0 in a 393x873 dp phone,
a 320x640 dp small phone, 873x393 dp on its side, an 800x1280 and a 1280x800 dp tablet (dark), and the phone
again in light, then read picture by picture and by the invariants in `app/src/test/java/com/tmplayer/ui/audit`
(`PhoneAuditShots` draws and writes the findings, `PhoneInvariantsTest` fails the build on them).
Pictures go to `build/phone-audit/<window>/`; the pairs here are the ones that changed.

## Screens covered (68 drawn by the harness, 204 pictures per window)

Home (rows, empty, loading, no favourites, partial and stalled status, with the featured carousel), Chats (full
and empty), Favourites (full and empty), History Continue, Watch later and Watched (each full and empty), a
chat's videos (loaded and empty), a video's page, Search (empty with recents, typing, results, none, searching,
offline, error, other chats), Downloads (empty and the cards: finished, auto-saved, selected, part loaded,
running), Settings (list and all 12 pages), About, the account card, Who's watching, PIN check and choose, Add
chats, sign in (phone, code, wrong code, QR, password), first run (notifications, support, six tour pages), the
proxy page, and the shared empty, error, loading and empty-with-buttons states. The player's sheets, the cast
picker, toasts and the dialogs are drawn by their own render tests (`PlayerMoreRenderTest`,
`PlayerExtrasRenderTest`, `SeekChromeRenderTest`, `SpeedSheetFontTest`, `TouchAdjustFontTest`,
`CastPickerRenderTest`, `ToastPositionTest`, `CoffeeDialogRenderTest`, `DownloadLocationRenderTest` and others),
run in the same pass.

## Fixed

| Defect | Pictures | Fix | Commit |
| --- | --- | --- | --- |
| Account card: "Favourites" cut as "Favourite" and "s", "12 watch..." at twice the font | account-card-393x873-f2.0 | `LibraryTiles` stands the three tiles as full width rows past 1.5 times the font | 149a819 |
| Who's watching: "Benedi/ct", "Accou/nt 3", "Add acco..." | who-is-watching-393x873-f2.0 | `ProfilePicker` tiles grow with the font | 149a819 |
| A letter on an avatar outgrew its disc at twice the font (112 px in 99) | chat-videos-393x873-f2.0 | letters are laid out at font scale 1 (`Art`, `ChatAvatar`, `DrawnLetter`) | 149a819 |
| Settings row titles cut ("Play the next episode autom...") while the description ran four lines | settings-playback-393x873-f2.0 | `TITLE_LINES` 4 | 6662f1d |
| Segmented answers: "Syste..." with the check cut, the chosen cell taller than the rest, "System" broken | settings-appearance, settings-storage, settings-setupchoices | equal cells, no check past 1.3x, label capped at 1.5x | 6662f1d |
| Page header title cut ("Downloads and da...") | settings-storage-393x873-f2.0 | two lines before a cut in Settings, About, Downloads | 6662f1d |
| Hard edge through a text line above a fixed row (Search results, the tour page, Add chats) | search-results-393x873-f1.0, tour-controls-393x873-f2.0, add-chats-393x873-f1.3 | `bottomFade` (shared), drawn while the list can scroll on | 0063b76 |
| Search chat shortcuts "Wee...", "Harb..." | search-empty-393x873-f2.0 | label wraps to two lines and grows with the font | 0063b76 |
| Search count line "4 videos in 3 chats" split by the button beside it | search-other-chats (invariant) | `FlowRow` | 0063b76 |
| Add chats on its side at 1.3: count and Done stacked, taking a third of 393 dp | add-chats-873x393-f1.3 | stack only under 600 dp wide | 0063b76 |
| "Enter it yourself" cut to "Enter it" with no ellipsis | proxy-393x873-f2.0 | `QuietButton` two lines then an ellipsis | f8d5857 |
| "Step 1 of 6" broken on 320 dp at twice the font | tour (invariant) | the step line wraps by word (`FlowRow`) | f8d5857 |
| A choice's sentence stopped at "never" with no ellipsis | settings-setupchoices-393x873-f1.3 | no line limit | f8d5857 |
| Numbered discs cut their digit | login-qr-393x873-f2.0, tour pages | the disc grows with the digit | f8d5857, 0063b76 |
| Bar count badge grew into a disc over its icon at twice the font | history-continue-393x873-f2.0, design/phone-bar | capped as the captions are | 6ac446c, 20f6e6b |
| Carousel words ran into the app's name (landscape) and under Play (320 dp, 2.0) | home-carousel-873x393, home-carousel-320x640 | overview, then facts give way; hero pill capped at 1.3x | 6ac446c |
| Detail page round captions "Watch/ed", "Downl/oad" at twice the font | detail-video-393x873-f2.0 | caption capped at 1.3x, fit with margin | 6ac446c |
| Chat grid: "Harbou/r" in three columns at twice the font | chat-videos-393x873-f2.0 | two columns from 1.6x | 6ac446c |

Before and after are `<name>-before.png` and `<name>-after.png`, uncompressed.

## Not fixed, and why

- Appearance's sample card ("Harbour", "Season 1,") shows its text cut at 1.3 and 2.0: it is a decorative
  specimen of a tile, not a control; left to the designer.
- Add chats on a phone on its side starts with its heading, All chats and search before the first chat (all of
  it scrolls); the heading could shrink but that changes the page's layout, so it is the owner's call.
- A video's page on its side shows its actions below the first screen (it scrolls).
- On 320 dp at twice the font a single long word ("Troubleshooting", "downloads") is wider than its line and
  breaks; listed in `Known.small` in `PhoneInvariantsTest`.
- The grey digit on a generated cover ("5", "6") is a drawn picture of a number, cut by its tile on purpose.
- A Home row heading just under the window's foot in 873x393 at twice the font reports a short box: it is a lazy
  item measured but not drawn, no cut is visible.
- Three columns of chat tiles at 1.3 still ellipsise a very long second line of a title (two lines, then "...").
- Not drawn here: TV, desktop and web (W25, W28), and a real device pass (focus, TalkBack, system fonts above
  2.0) which is the user's.
