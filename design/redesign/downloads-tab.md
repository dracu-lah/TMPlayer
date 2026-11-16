# Downloads as a first-class place, and one honest storage page (R2)

User approved R2 on 2026-11-16 ("do all"). Raju findings 7.1 to 7.4, 8.1, 8.2, PL-16; group C (5.1, 5.2, 7.2, 7.3, 9.6)
rides in the same files. T12 removed the mini player, T4 made the streaming row "While streaming: Keep video | Save space".

## Problem Raju hits
- Downloads is two taps deep behind the profile photo; the only hint is a badge on the photo (7.1, PL-16). On the TV
  the way from the player to Downloads was five presses.
- Two lists for one idea: "Downloaded" and "Recently played" (a cache he did not ask for, which counts in "TMPlayer
  uses" and which he cannot find) (7.4).
- The page's loudest button, "Free up space", shows when there is nothing to free (7.3). Cancel is instant and final (7.2).
- Settings, Storage and downloads is seven switches. "Wi-Fi only" and "Save mobile data" are twins; "Pause downloads
  while playing" and "Warn when space runs low" are engine details that should simply always happen (8.1).

## New flow

| | Phone | TV (remote, focus) | Desktop (keyboard) | Web |
| --- | --- | --- | --- | --- |
| Where | Tab bar: Home, Chats, Search, **Downloads**. Badge on its icon = number running (not queued, not paused). | Top bar: face, Search, Home, Chats, **Downloads** (full word, same pill as the tabs), Watch later (icon). | The floating bar: Home, Chats, Search, **Downloads**, then the picture. Ctrl+J (Cmd+J on a Mac) opens it from anywhere, again returns. | The nav's fifth place **Downloads** opens a page with the "Get the app" card (downloads need the app). |
| Profile and Settings | A **gear** at the end of Home's top and of the Downloads page header opens the same card the picture opened: Favourites, History, Watch later, Settings, Update (no Downloads row). | The face stays and keeps Settings. Its menu loses the Downloads row (it is a tab now). | The picture stays; its menu loses the Downloads row. | The You menu is unchanged (no downloads). |
| Page | Running cards, then one list **On this phone**, a header gear. | Two tabs: Downloading, On this device (the third tab, Recently played, is merged). | The same two sections in the panel. | One card. |
| Back | Back from Downloads goes to where the viewer came from (Home when they used the tab). | Back from Downloads: the remote goes to the bar's Downloads item (the opener); another Back leaves. Back from the player: the remote returns to the row that opened it. | Esc closes the panel. | Browser back. |
| Keys | Tap. | Right from Chats reaches Downloads; OK opens; Down goes into the page. | Ctrl+J, Delete, Ctrl+A in the list (GridSelection). | Tab, Enter. |

## The page
1. **Running cards** (current design, kept): title, line "5 percent . 140.2 MB of 2.59 GB . 3.6 MB/s . about 11m 30s left",
   a bar, Pause and Cancel. A paused card says **Download paused** (9.6: the history tile's word `grid.stage_paused`
   stays "Paused" for the card, the tile that overlays a poster says "Download paused" through the new key
   `grid.stage_paused_long`).
2. **One list "On this phone"** (the TV and desktop say "On this device", "On this computer"): downloaded and auto-saved
   videos together, in the page's order chips (Newest, Name, Largest), grouped by show as before. An auto-saved video
   (what playing left behind, the old "Recently played") wears a small **Auto-saved** tag in its detail line and has a
   **Keep** action (turns it into a download, `saveToDownloads`) beside Watch (first in the phone's More menu). It is
   otherwise a row like any other: it plays on a tap, Share, Save to folder and Delete work on it (named, confirmed), and a
   note once over the list says what Auto-saved means. On the desktop an auto-saved row has Watch and Keep; Delete stays in
   Free up space's review there.
3. **Free up space**: only when the review has something to free; then a quiet text button under the storage line.
   Nothing to free: the line "Nothing to free up" and no button.
4. **Cancel gets Undo** on a touch screen (7.2): the card leaves, the download is paused, "Download cancelled. Undo" for 5 s;
   Undo resumes it with the part file kept; after 5 s the part file is deleted (`CancelWindow`, tokens per cancel). If the page
   is left inside the window the download is simply still there, paused: nothing is deleted behind the viewer's back. A
   remote cannot reach a toast, so the TV and the desktop keep Cancel as it is. A failed card ("Dismiss") has nothing to bring back.
5. The empty state's buttons ("Go to Chats", "Download a season") sit above the bar (bottom padding), not under it (7.3).

## Settings, Downloads and data
Rows: **Where to save** (when more than one place), **On mobile data** (one 3 way control: Ask first, Never, Allow),
**While streaming** (T4: Keep video | Save space), **Download the whole video first** (kept: it is a behaviour, not a
twin; left under While streaming), **Remove after watching**. Gone: Low space alert, Pause downloads while playing
(both behaviours stay on), Wi-Fi only, Save mobile data (replaced).

Mobile data, the stored values (`MobileDataPolicy`):
| Stored `wifi_only_downloads` | Stored `save_mobile_data` | New `mobile_data` | Shown |
| --- | --- | --- | --- |
| true | any | none | Never |
| false | true | none | Ask first |
| false | false | none | Ask first |
| any | any | `ask` | Ask first |
| any | any | `never` | Never |
| any | any | `allow` | Allow |
| any | any | garbage | falls back to the two booleans |

Picking writes `mobile_data` and keeps the old keys in step (`wifi_only_downloads` = Never; the others clear it), so a
restore on an older build still behaves. "Save mobile data" (a streaming bound) keeps its stored value and its effect for
the people who set it until they pick an answer: any pick removes the key, so Allow really lifts the bound.
- **Ask first**: a video that has to be streamed over 300 MB on mobile data asks once per session (today's default, in the
  player). A download is the viewer's own press and is not asked about.
- **Never**: nothing that is not already on the phone starts on mobile data: a stream says so with the way out, a download
  card says "Waiting for Wi-Fi" and starts by itself on Wi-Fi.
- **Allow**: never asks (`MeteredPolicy.decide(asks = false)`).
A TV is never on mobile data: the row is not offered there (`MeteredPolicy`), as the two switches were not.

## Edge cases
- **100,000 downloads**: one `LazyColumn`, keys per row, the shelf arrangement stays one pass; only visible rows compose.
  The running count badge is a number from `OfflineDownloads.active`, never a list scan of the shelf.
- **Count badge**: running = downloading now, not queued and not paused; a failed one counts as nothing. 0 shows no badge.
  99+ is shown as 99+. It updates once a second with the ticker, not per byte.
- **A download finishes while the viewer is on another tab**: the badge falls by one; no toast (the old behaviour stays:
  the notification says it); the video is in the list when the tab is opened. Nothing scrolls under the thumb.
- **Mobile data warning**: Ask first warns before a large streamed video starts (the player's existing sheet, with its size).
  Never says why a card waits. A change of the control applies to the next start. The old "Wi-Fi only" line in the player
  became "On mobile data is set to Never ..." and links to the new row.
- **Empty**: the empty state with Go to Chats and Download a season above the bar.
- **Offline**: the page works (it is the place offline videos live); nothing here needs the network.
- **Rotation, process death**: the sort, the page and the list scroll are saved as before; the cancel window ends the
  cancel when the screen goes (the part file is deleted then, not left).
- **Large font (2.0)**: the bar's captions step down (BarCaption autoSize), the Auto-saved tag wraps under the name; render
  tests at 1.0 and 2.0.
- **Auto-saved that is also a download**: listed once, as the download (as before, `measureCached` drops it).
- **Kids lock**: both lists go through `KidsLock.records`, as today.
- **Old stored keys**: `wifi_only_downloads`, `save_mobile_data`, `pause_downloads_while_playing`, the low storage notice
  switch stay in the file and in backups; nothing reads the last two as an off switch any more.

## What it deletes
The Recently played section and its collapsible heading (phone, desktop), the TV's third tab, `downloads.cached*` and
`settings.storage_cached` use, the Wi-Fi only and Save mobile data rows, the Pause downloads while playing row, the Low
space alert row, the profile button on the phone's bar and its ring, `PROFILE_SLOT` and `TouchPageSpec.inProfile`, the Downloads
row of the three account menus. Strings no screen uses any more are listed in `private/DEAD-STRINGS.md` and stay in en.json
until the next sync.

## Checks (machine first)
Core table test for the boolean to 3 way migration; Compose tests for the phone bar (four places, badge, gear opens the
card without a Downloads row); TV D-pad test (Right from Chats reaches Downloads, OK, Back returns to the opener); desktop
Ctrl+J test; render tests of the Downloads page, the settings page and the bar per form factor at 1.0 and 2.0 font; a
test of the cancel window. Leftovers for the user: a real phone with a SIM for the warning dialog, the TV remote on the
couch, TalkBack on the gear.

## Before and after
Before (the phone, Raju's review): `design/ux-review/raju/downloads-active.webp` (the page two taps deep, Free up space as the
loudest button, Recently played as a second list), `downloads-empty-nav-overlap.webp`, `storage-top.webp` and
`storage-bottom.webp` (seven switches), `detail-page.webp`, `detail-download-ring.webp`, `history-pause-label.webp`.

After (render tests, `design/redesign/downloads-tab/`, WebP q88): `bar-home-dark` (four places, the gear at the top end),
`bar-badge-2-dark` (two running), `bar-narrow-2x-dark` (360 dp at twice the font), `gear-card-dark` (the card, no Downloads row),
`cards-phone-dark`, `cards-phone-narrow-2x-dark` (the Auto-saved tag survives a large font), `cards-tv-dark` (Watch and Keep as
named buttons), `mobile-data-phone-ask-dark`, `mobile-data-phone-narrow-2x`, `mobile-data-tv-allow-focused`.
