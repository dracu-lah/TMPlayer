# Web audit (2026-11-25)

Every route of the web app walked over the fake Telegram at a phone (390x844), a tablet (820x1180), a
desktop (1280x800) and the web TV layout (1920x1080), in Chromium, with a larger account name and the
overlays that open from a route (the account menu, a chat's Sort menu, a typed search). The walk is
`webapp/e2e/screen-sweep.spec.ts` (commit 99cae40; `pnpm e2e e2e/screen-sweep.spec.ts`; `AUDIT_SHOTS=<dir>` also saves
every route as a PNG). It asserts, per route and size: no sideways scroll of the page, no text hard
clipped by its own box, no control, tab, chip or nav word cut with an ellipsis, a label within 2 px of
the middle of its pill, nothing to press under a fixed bar once the page is at its end (or under a bar at
the top at the top), and a chosen place's glyph really drawn.

Routes: Home, Chats, a chat, chat info, Search (empty and typed), Recently added, Choose chats, a
video's page, Settings and its seven pages, Downloads, Favourites, History (two tabs), Lists, not
found, sign in (signed out), the account menu, the Sort menu, Home and Settings with a long name. 29
routes at 4 sizes, 116 pages walked, plus their end-of-page states.

## Found and fixed

| # | Defect (where) | Picture before | Picture after | Fix | Commit |
| --- | --- | --- | --- | --- | --- |
| 1 | The History place of the bar went blank when chosen: no icon at all (phone, tablet, desktop) | history-icon-phone-390-before.png | history-icon-phone-390-after.png | `Icon.tsx` / `styles.css`: the outline hands over to the filled form only for a glyph that has one | 709de60 |
| 2 | The Chats place, chosen, drew two dashes instead of a filled bubble (phone, desktop, TV bar) | chats-icon-phone-390-before.png, chats-icon-tv-1920-before.png | chats-icon-phone-390-after.png, chats-icon-tv-1920-after.png | `Icon.tsx`: the filled form took the bubble's lines as the body and cut the bubble out of them; the bubble is the body, its lines the cut | 709de60 |
| 3 | Search: a second rectangle inside the pill, the words touching its left edge, while the field has focus (all forms) | search-ring-phone-390-before.png | search-ring-phone-390-after.png | `styles.css`: the focus ring is the pill's, the input inside keeps none | fd4908a |
| 4 | Choose chats: the last rows (their tick and star) could not be scrolled clear of the floating Done bar (phone, TV) | choose-chats-select-bar-phone-390-before.png | choose-chats-select-bar-phone-390-after.png | `styles.css`: a page that carries the select bar keeps its height (98 + 58 + 16 px) at its foot | fd4908a |
| 5 | The account menu cut "Favourites" and "Watch later" to "Favouri..." and "Watch l..." (desktop) | account-menu-labels-desktop-1280-before.png | account-menu-labels-desktop-1280-after.png | `styles.css`, `Nav.tsx`: the card is 264 px (was 232), a tile's name wraps instead of an ellipsis | fd4908a |
| 6 | Settings' choice rows (size, colour, edge, box, position): a ticked word touched its cell's edges | (no picture: 1 px gaps) | n/a | `styles.css`: 6 px each side inside a cell | fd4908a |

The sweep found these by itself except 1 and 2, which the icon check (added after they were found by
eye in the pictures) now guards.

## Looked at and left, with the reason

| What | Why left |
| --- | --- |
| WebKit and the iPhone run of the sweep | WebKit does not start on this machine (the host lacks libraries Playwright needs, `sudo npx playwright install-deps`). The spec has no Chromium-only API and runs in the `webkit` project; left to the nightly run, as `known-failures.md` already says. |
| Recently added's empty line reads "No playable videos in this chat." | Wrong noun for that page, but a new string in 22 catalogs; needs the user's translations. |
| Home's empty card sits at the start of a wide page, not centred | A design choice (Home's cards start at the gutter); not a defect by the invariants. |
| Help's "Copy details" row shows the whole diagnostics blob inline | Intended, it is the text that is copied. |
| Chats page: Archived chip row scrolls under "Select all" in the picker | A scroller by design; the chips are reachable by swipe and keys. |
| `?q=clip` on Search is not read | The page reads its words from the field, not the address; not a layout defect. |
