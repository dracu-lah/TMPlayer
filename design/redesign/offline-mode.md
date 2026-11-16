# Offline mode that tells the truth (R4, with Raju group B)

Source: `private/ux-review/raju-journey.md` steps 9.1, 9.2, 4.5 (6.4 is gone: the mini player was removed),
group B and R4. Approved by the user ("do all", 2026-11-16).

## The problem Raju hits

- Wi-Fi off. A bar "Offline. Your downloads still play. Downloads" is laid **over the tab bar**: his taps
  on Chats and his picture land on the bar and do nothing (9.1). `offline-banner-over-nav.webp`.
- Every Home card still says Play, the hero rotates, and the "no" arrives after the tap as a toast that
  fades in 3 s (9.2). A video he never downloaded says Resume. `offline-play-tap.webp`.
- The floating bar hides the last row's caption at rest, and at the end of Home he has scrolled so the
  bar is gone (4.5). `home-nav-overlap.webp`.
- Two things say "offline" (a passive pill and the new "Can't reach Telegram" banner of FQ2).

## The new flow

1. **One banner, the Connect banner's look.** `ConnectBannerView` (FQ2) gets a device-offline form: a
   small card "Offline. Your downloads still play." with **Try again** (and **Downloads** on a touch
   screen). It sits **above the tab bar** (inset = `LocalBottomChrome`), never in the bar's place, and the
   page under it is padded by its height, so it covers nothing it needs. It is a card with its own press
   targets: it never swallows a tap meant for the bar. The old passive Offline pill is deleted; the
   Reconnecting and Connecting pills stay (a different state: online, line not up yet).
2. **Home tells the truth.** Offline, Home is rebuilt by one pure rule (`OfflineHomeRows`):
   - "On this phone" is the first row: Continue watching videos that play without a line (fully in the
     watch cache, or downloaded), then downloads not already there. Nothing appears twice: those records
     leave the normal Continue row.
   - Every other card is greyed with the badge "Needs internet" and no Play. It stays focusable and
     clickable.
   - Rows still waiting for Telegram (placeholders that can never fill) are left out.
   - The hero (carousel) is replaced by the first video of "On this phone"; with nothing on the phone the
     carousel is not shown (an empty picture of something that cannot play is worse than none).
3. **A tap on a greyed card opens its page**, never a toast. The page replaces the Play button with the
   line "Connect to play, or download it first" (Download stays: it queues and starts when the line is
   back). A card that plays (On this phone) plays at once; a fully cached video resumes with no lookup.
4. **Debounced.** The offline answer is held for 2 s in both directions (`OfflineSettle`), so a flaky link
   never flashes cards grey and back. Flipping back inside 2 s cancels the change.
5. **Floating bar.** The list's end padding already clears the bar at rest; at the end of a list the bar
   is shown again (a push or fling that hits the end shows it), so Chats/Downloads stay one tap away.

## Platforms

| | Phone | TV | Desktop | Web |
| --- | --- | --- | --- | --- |
| Banner | above the tab bar, Try again + Downloads | no banner over the rails; the top-end Connect banner place, Try again only (remote reaches it from the bar: Up) | top centre card, Try again | existing offline line stays (above the bar) |
| Home | "On this phone" first, rest greyed, hero = first downloaded video | same rows; **first focus lands on a playable card** (the On this phone row is first); greyed cards focusable, OK opens the page with the line | same rows, greyed tiles, hover/Enter opens the page | no downloads: Home shows the **Reload card** ("Can't reach Telegram" + Reload) when no rows are loaded |
| Tap/OK/Enter on greyed | page with the line | page with the line | page with the line | n/a |
| Debounce | 2 s | 2 s | 2 s | the browser's own event |
| Resume cached | at once, no lookup | at once | at once | n/a |

## Edge cases

- **Flaky link**: debounce 2 s each way; unit-tested with virtual time (flips inside the window never show).
- **Nothing on the phone**: no "On this phone" row, no hero; all cards greyed; the banner and a plain
  line are the only news. (Not handled by inventing content.)
- **A video partly downloaded**: not playable offline: greyed. (`Partial` is not `Complete`.)
- **Cached but the stored file id is stale**: the check asks TDLib about the stored id; a miss means
  greyed, the safe side. The press itself never promises more than the check said.
- **Huge accounts**: the cached check covers at most 40 Continue/Watch later records, once per offline
  stretch, off the main thread; downloads come from one index read.
- **Back online while the page is open**: the page's line goes with the next settle (2 s), Play returns.
- **Kids lock / gone videos**: unchanged: the rows are already filtered before this rule.
- **TV**: first focus is always a playable card when one exists (the first row is On this phone); with
  none, the first greyed card takes it. OK on it opens the page (never a dead press).
- **Large font (2.0)**: the banner wraps its words and the buttons drop under them; tested at 1.0 and 2.0.
- **Desktop with no Windows/macOS network facts** (`NetworkFacts.known = false`): never offline by
  guess, so nothing greys; TDLib's own state still drives the Connect banner.

## Deleted by this

- `ConnectionStatus` Offline branch (the passive pill) and its `onOpenDownloads` link.
- The Home cards' `mainConnectToPlay` toast path (the toast stays for non-Home callers that have no page
  to go to: search results, chat grid, where a press on a not-downloaded video says why).
- `OfflineDownloadsHost`'s separate Downloaded set (folded into `OfflineHome`).

## Tests

core: debounce (flips cancel, holds, symmetric), rows rule (order, no duplicates, placeholders dropped,
nothing on phone). app Compose: tab bar tappable while the banner shows, banner at 1.0 and 2.0 font,
offline Home states (On this phone first, greyed cards have the badge and no Play), D-pad on TV lands on a
playable card and OK on a greyed one opens its page, a fully cached video resumes offline. Desktop render
test of the greyed tile. Before/after shots: `design/redesign/offline-mode-before.webp` is
`design/ux-review/raju/offline-banner-over-nav.webp` (private tree); the after shot is a test render.
