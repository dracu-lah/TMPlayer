# Known end to end failures (HX88 phase B3, 2026-11-20)

These Chromium tests fail on the HX88 B3 web branch. Each one also fails, with the same message, on
the commit the branch started from, so the branch did not cause any of them. Fix them in their own
task (the nav label contrast, Recently added's order, the chats gate, the Add chats picker), not in
the profiles work.

## How this was checked

- Base: `322da77` (Merge test fixes). Its `webapp/` and `core/src/commonMain/resources` were
  exported with `git archive` into a separate folder, `node_modules` was linked, and it was served
  by `vite --config e2e/vite.e2e.ts` on port 8132.
- Branch: `058e69b` (HX88 B3 web fourth review, end to end), the last commit before this file,
  served the same way on port 8131. This file changes no code.
- Both runs started together on the same machine (2026-11-20, finished 18:13 UTC):
  `E2E_PORT=<port> playwright test <specs> --project=chromium --workers=1`.

## The six of a11y, home-settings and setup

All three spec files were run in full on both commits. Both gave 12 passed, 6 failed and 2 skipped,
with the same result for every test.

| Test | 322da77 | 058e69b | Why it fails (the same message on both) |
| --- | --- | --- | --- |
| a11y.spec.ts:33 axe on the main screens > Home | fail | fail | color-contrast (serious): `.active > .nav-label` |
| a11y.spec.ts:33 axe on the main screens > Chats | fail | fail | color-contrast (serious): `a[title="Chats (Ctrl+2)"] > .nav-label` |
| a11y.spec.ts:33 axe on the main screens > Search | fail | fail | color-contrast (serious): `a[title="Search (Ctrl+K)"] > .nav-label` |
| home-settings.spec.ts:30 Recently added follows the chats added in Add chats | fail | fail | expected "Test Clips", got "Test Copies · 1.5 GB · 3 versions" |
| setup.spec.ts:14 signing in opens the chats gate | fail | fail | button "Choose at least one chat" not found |
| setup.spec.ts:41 on the TV the gate opens with the remote in the search field | fail | fail | focus on a "button", not the "search" field |

The other twelve tests pass on both commits: in a11y, the sign in, the Sign out row, a chat, a
video page, History, Settings, the player, reduced motion and the sort menu; in home-settings, lines
12 and 48; in setup, line 30. a11y lines 97 and 132 are skipped on both.

## The Add chats picker (chats, choose-chats, tv)

These are the 7 failures of a wider Chromium run on the branch (app, sync, settings-find, pages,
layout, player, shortcuts-player, shell-keys, library, chats, filter, choose-chats, tv and confirm
specs: 188 passed, 7 failed, 3 skipped). Run again with `-g` on both commits side by side, the same 7
fail on both.

| Test | 322da77 | branch |
| --- | --- | --- |
| chats.spec.ts:16 an archived chat that was added is browsed in Chats | fail | fail |
| chats.spec.ts:27 leaving the picker with ticks not saved asks first | fail | fail |
| chats.spec.ts:42 Select all over a thousand chats asks first | fail | fail |
| choose-chats.spec.ts:72 the picker by keyboard: Tab walks the search, the filters, Select all | fail | fail |
| choose-chats.spec.ts:105 the All chats switch is off at first | fail | fail |
| tv.spec.ts:169 (1920 x 1080) Choose chats on 3,000 chats | fail | fail |
| tv.spec.ts:169 (1280 x 720) Choose chats on 3,000 chats | fail | fail |

## Not run here

WebKit does not start on this machine (`browserType.launch` fails), so the `webkit` and
`webkit-iphone` projects were not run.
