# TODO

Left over from the 2026-10-09 run. The full plan is `docs/PLAN.md`.

## Not pushed yet (committed on local branches)

- [ ] **CP40 Episodes button and list** (phone, TV, desktop): branch `worktree-agent-af34a76a1abdf858c`,
  commits c961089, 4ac8650, 83c5262. All committed; the last build was not run. Build, rebase onto main,
  push.
- [ ] **Text that was cut short in German, Russian and Malayalam**: branch
  `worktree-agent-ad03dac91ec236616`, commits b5ec6d4, 7ea985c, plus 3595014 (unfinished, not built).
  Check 3595014, build, rebase, push.

## Translation

- [ ] Translate the new English text from CP40 into the 20 other languages (the agents switched to English
  only near the end). Find the keys that are in `en.json` but missing elsewhere and fill them in.
  Do not run langsync.

## Release

- [ ] Full `./gradlew build` and unit tests on main after the two branches above and the translation land.
- [ ] Cut the release. The user approved it on 2026-10-09 despite the cadence guard (1.24.0 was
  2026-10-06); confirm again if this is picked up on a later day. Release notes go in the annotated tag.
- [ ] After the release: the Telegram post, the site's update feed, the AUR package.

## Not checked on a screen (testing was stopped on 2026-10-09)

- [ ] AppImage on this machine and the MSI in the podman `win11` VM (how-to in the session notes).
- [ ] Phone in portrait and landscape and TV: the Episodes list, Only my folders and its prompt, the
  first sign in card, the new two page tour, the shorter sidebar with chips, the new confirm prompts.
- [ ] Anything with a real Telegram account: unread badge on Chats, real folder counts, the card after a
  real first sign in.
- [ ] TV: focus falls to the rail's Home after the first sign in card closes.
- [ ] Site: the TV chat list screenshots still show the old sidebar.

## On hold

- [ ] F-Droid: needs a decision on the Telegram API key. Nothing about F-Droid in the app, site or README
  until then.
