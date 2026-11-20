# Known end to end failures (HX88 phase B3, 2026-11-20)

Six Chromium tests fail on the HX88 B3 web branch. They fail the same way on the commit the branch
started from, so the branch did not cause them. Fix them in their own task (the nav label contrast,
Recently added's order, the chats gate's button and focus), not in the profiles work.

## How this was checked

- Base: `322da77` (Merge test fixes), its `webapp/` and `core/src/commonMain/resources` exported with
  `git archive` into a separate folder, `node_modules` linked, served by `vite --config e2e/vite.e2e.ts`
  on port 8132.
- Branch: `e049a43` (HX88 B3 web third review, streams), served the same way on port 8131.
- Both runs started together on the same machine (2026-11-20, finished 17:41 UTC):
  `E2E_PORT=<port> playwright test e2e/a11y.spec.ts e2e/home-settings.spec.ts e2e/setup.spec.ts --project=chromium --workers=1`.
- Both: 12 passed, 6 failed, 2 skipped. Every test has the same result on both.

## The six, on each commit

| Test | 322da77 | e049a43 | Why it fails (the same message on both) |
| --- | --- | --- | --- |
| a11y.spec.ts:33 axe on the main screens > Home | fail | fail | color-contrast (serious): `.active > .nav-label` |
| a11y.spec.ts:33 axe on the main screens > Chats | fail | fail | color-contrast (serious): `a[title="Chats (Ctrl+2)"] > .nav-label` |
| a11y.spec.ts:33 axe on the main screens > Search | fail | fail | color-contrast (serious): `a[title="Search (Ctrl+K)"] > .nav-label` |
| home-settings.spec.ts:30 Recently added follows the chats added in Add chats | fail | fail | expected "Test Clips", got "Test Copies · 1.5 GB · 3 versions" |
| setup.spec.ts:14 signing in opens the chats gate | fail | fail | button "Choose at least one chat" not found |
| setup.spec.ts:41 on the TV the gate opens with the remote in the search field | fail | fail | focus on a "button", not the "search" field |

The other twelve tests in these three specs pass on both commits: a11y sign in, Sign out row, a chat,
a video page, History, Settings, the player, reduced motion, sort menu; home-settings 12 and 48;
setup 30. a11y 97 and 132 are skipped on both.

## Not run here

WebKit does not start on this machine (`browserType.launch` fails), so the `webkit` and
`webkit-iphone` projects were not run.
