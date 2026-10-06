# After the next release: GitHub CLI tasks

Everything outstanding that is done through `gh` (plus the two `wingetcreate` steps that open a GitHub
PR), collected from `docs/PLAN.md`, this folder and the live state on GitHub. Live state read on
2026-10-06 with `gh`; nothing was posted, commented, opened or closed while collecting it.

`<v>` below is the new version (for example `1.23.0`), `v<v>` its tag. The next release cannot go out
before 2026-10-13 (cadence guard: 1.20.0, 1.21.0, 1.22.0 and 1.22.1 all went out on 2026-10-04 and
2026-10-05).

## Live state at a glance (2026-10-06)

| Item | State | Waiting on |
| --- | --- | --- |
| Latest release | v1.22.1, 2026-10-05, run 37357914732 green | |
| Issue #2 Spanish (h15manuel) | open, no label, last comment ours 2026-10-04 | us |
| Issue #3 watched indicator (h15manuel) | open, last comment ours 2026-10-04; the feature shipped in 1.21.0 | us |
| Issue #5 MalayalamSubtitles.org (noushweb) | open, last comment ours 2026-10-04 | MSone (permission email 2026-10-05) |
| PR #4 (h15manuel, "Feature/spanish watched status") | closed by its author 2026-10-02 | nobody |
| Flathub flathub/flathub#10513 | closed by bot 2026-10-05 12:44; reviewer ostfriese4 replied 16:58 | us |
| Awesome list PRs | 7 open, 2 merged, none closed | maintainers |
| Labels | `translation` exists; not yet on issue #2 | |
| Secrets | no `WINGET_CREATE_GITHUB_TOKEN` yet | |
| mediamp source archive | not attached to any release yet (1.22.0 and 1.22.1 have none) | |
| AUR PKGBUILD | still `pkgver=1.22.0`; it was never bumped to 1.22.1 | |

## Do right after the release

### 1. CP12: check the release itself

```sh
gh release list --limit 10                  # cadence guard, before tagging
gh run list --workflow release.yml --limit 1
gh run watch <run-id>
gh release view v<v>
```

Depends on the release: yes.

### 2. CP12: attach the mediamp corresponding source

The release.yml step "Check the mediamp source link still matches" has `TAG=""`, so the next release
links its own copy and prints a warning until the archive is attached.

```sh
scripts/build-mediamp-source.sh
gh release upload v<v> build/mediamp-source/mediamp-corresponding-source-0.5.0.tar.gz
```

Then set `TAG="v<v>"` in that step of `.github/workflows/release.yml`, commit and push, so every later
release links to this one. Depends on the release: yes (it must be the first release that carries the
step). Only with the user's OK.

### 3. CP12: verify the provenance attestation

```sh
mkdir -p /tmp/tmp-verify && cd /tmp/tmp-verify
gh release download v<v> -p 'TMPlayer-<v>-universal.apk'
gh attestation verify TMPlayer-<v>-universal.apk --repo dracu-lah/TMPlayer
```

Also check `site/latest.json` and the changelog commit the run pushed (`git pull`, then
`jq . site/latest.json`), and the Telegram post on t.me/tmplayerapp (U06 is the same check for
1.22.1, still open). Depends on the release: yes.

### 4. CP12: bump the AUR PKGBUILD

Repo commit, not a gh call, but the hash comes from the release:

```sh
gh release download v<v> -p 'TMPlayer-<v>-linux-x64.tar.xz' -D /tmp/tmp-verify
sha256sum /tmp/tmp-verify/TMPlayer-<v>-linux-x64.tar.xz
```

Set `pkgver=<v>`, `pkgrel=1` and the new `sha256sums` in `desktop/packaging/aur/PKGBUILD`, regenerate
`.SRCINFO` if one is kept. From this release on, `package()` installs the notices, which the 1.22.x
tarballs lack. Depends on the release: yes.

### 5. CP13 then U13: winget

CP13 (Claude): bring `docs/distribution/winget/` to the new MSI: schema 1.12.0, `PrivacyUrl`, URL,
`InstallerSha256`, `ProductCode`, and fix `winget.md` (it says `wingetcreate new` takes `--submit`).

```sh
gh release download v<v> -p 'TMPlayer-<v>-windows-x64.msi' -D /tmp/tmp-verify
sha256sum /tmp/tmp-verify/TMPlayer-<v>-windows-x64.msi
msiinfo export /tmp/tmp-verify/TMPlayer-<v>-windows-x64.msi Property | grep ProductCode
```

U13 (user, on Windows): `wingetcreate token --store`, then
`wingetcreate submit --prtitle "New package: dracu-lah.TMPlayer version <v>" <folder>`. That opens the
PR in microsoft/winget-pkgs. Then, with gh:

```sh
gh search prs --repo microsoft/winget-pkgs --author dracu-lah --state open
gh pr comment <pr-url> --body "@microsoft-github-policy-service agree"
gh pr view <pr-url> --comments              # watch for Needs-Author-Feedback, answer within 5 days
```

If a "Telegram" trademark question comes up (Policy-Test-2.2), draft reply:

> TMPlayer is an unofficial third party client. The name does not contain "Telegram", the icon is our
> own, and the description says it is not made by or affiliated with Telegram, as Telegram's API terms
> require. The package installs the MSI from our own GitHub release.

Depends on the release: yes (the manifests point at the new MSI). Never replace a release file after
submitting.

### 6. Issue #2: ask for the Spanish review (U22)

Only after the release that ships the es-419 translation (the "Languages" release, CP25; if the next
release carries the 16 languages, this is that release). Label it now (see below), comment then:

```sh
gh issue comment 2 --repo dracu-lah/TMPlayer --body-file /tmp/issue2.md
```

Draft for `/tmp/issue2.md`:

> Hi @h15manuel, TMPlayer <v> is out and it speaks Spanish: Latin American Spanish (es-419), along
> with 15 other languages. Thank you for asking, this issue is where it started.
>
> The translations were machine generated, so there will be mistakes, odd wording and labels that are
> too long for the TV. Since you use the app every day, would you be willing to look over the
> Spanish? TMPlayer follows your device language, or you can pick "Español (Latinoamérica)" in
> Settings.
>
> Anything you notice helps, even one word. You can reply here, open a
> [translation issue](https://github.com/dracu-lah/TMPlayer/issues/new?template=translation.yml)
> with the screen and your suggestion, or edit
> [es-419.json](https://github.com/dracu-lah/TMPlayer/blob/main/core/src/commonMain/resources/i18n/es-419.json)
> directly ([how](https://github.com/dracu-lah/TMPlayer/blob/main/CONTRIBUTING.md#translations)).
> No need to review all of it.

Keep the issue open until the review is in or the author goes quiet. Depends on the release: yes.

### 7. Awesome lists: a short follow-up on the slow ones (optional)

offa/android-foss#785 has the `waiting` label: offa wrote on 2026-10-05 "Since the project is
relatively new, we should give it a little more time though." Do not push; if you comment at all, do
it once, a few weeks after this release:

```sh
gh pr comment 785 --repo offa/android-foss --body "Thanks for the patience. For when you look again: <v> is out (https://github.com/dracu-lah/TMPlayer/releases/tag/v<v>), the project is still actively maintained, and the entry is unchanged. Happy to adjust anything."
```

Depends on the release: only for the link.

## Can do any time

### 8. Issue #2: add the `translation` label

```sh
gh issue edit 2 --repo dracu-lah/TMPlayer --add-label translation
```

### 9. Issue #3: close it, the feature shipped in 1.21.0

The 1.21.0 notes say finished episodes get a tick, any video can be marked watched or unwatched, and
there is a Watched tab: exactly what h15manuel asked for.

```sh
gh issue close 3 --repo dracu-lah/TMPlayer --reason completed --comment "This shipped in 1.21.0: finished episodes get a tick, you can mark any video as watched or unwatched, and a Watched tab lists what you have finished. Thanks for the idea. If something about it does not work the way you hoped, reopen this or open a new issue."
```

The 1.21.0 notes call the list the Watched tab; the current English text calls it "Previously watched". Adjust the wording if needed.

### 10. Issue #5: tell the author where it stands (optional)

No reply from MSone yet (U25). A holding comment is fair, and promises nothing:

```sh
gh issue comment 5 --repo dracu-lah/TMPlayer --body "Quick update: I have written to MalayalamSubtitles.org to ask whether TMPlayer may search and download from their site. Nothing will be built until they agree. I will post here when they answer."
```

Add `--add-label enhancement` with `gh issue edit 5` if you want it labelled.

### 11. Awesome Android TV FOSS Apps: file the issue (U15)

Not filed yet (searched the repo for "TMPlayer": no match). The form's labels cannot be set by an
outsider through gh, so open the form in the browser, or send the same fields as a plain body:

```sh
gh issue create --repo Generator/Awesome-Android-TV-FOSS-Apps --title "[App Request]: TMPlayer" --body-file /tmp/atv.md
```

`/tmp/atv.md`, laid out the way the issue form renders:

```
### Name of App

TMPlayer

### Source Code of the App

https://github.com/dracu-lah/TMPlayer

### App Category

Media Player

### App Website

https://tmplayer.org

### App Description

- **TMPlayer:** Streams the videos in your Telegram chats while they download, with subtitles, audio tracks and episode autoplay. Unofficial Telegram client, needs your own account [[Source](https://github.com/dracu-lah/TMPlayer)] [[Website](https://tmplayer.org)]

### Remote Support

Full

### Launcher Icon

Yes

### Additional Information

_No response_
```

The browser form is the safer route: `gh issue create --repo Generator/Awesome-Android-TV-FOSS-Apps --web`
then pick the feature request template. Does not depend on the release.

### 12. Watch the awesome-list PRs

```sh
gh search prs --author dracu-lah --state open --json url,updatedAt --jq '.[]|"\(.updatedAt) \(.url)"'
gh pr view <url> --comments
```

| PR | Opened | Last activity | State |
| --- | --- | --- | --- |
| ebertti/awesome-telegram#331 | 2026-10-05 | format bot passed | no human review yet |
| kalanakt/awesome-telegram#101 | 2026-10-05 | CodeRabbit: no actionable comments | no human review yet |
| offa/android-foss#785 | 2026-10-05 | offa, 18:09: give it more time; label `waiting` | maintainer, see item 7 |
| krzemienski/awesome-video#139 | 2026-10-05 | Sourcery and ECC bots passed | no human review yet |
| fmhy/edit#6585 | 2026-10-05 | label `docs`, no comments | no human review yet |
| stax76/awesome-mpv#56 | 2026-10-05 | no comments | slow list |
| pcqpcq/open-source-android-apps#496 | 2026-10-05 | no comments, mergeable unknown | no human review yet |

Merged: Heapy/awesome-kotlin#1184, mahozad/awesome-compose-multiplatform#5. None closed.
If asked about spyware on android-foss: Sentry is off by default and starts only when the user turns it
on, and then sends only the stack trace, versions and device model.

## Waiting on others

### 13. Flathub flathub/flathub#10513 (U16)

Closed by the submission bot on 2026-10-05 12:44 (checklist format). We commented twice at 12:58
asking for a reopen. Reviewer ostfriese4 answered at 16:58 that the linked video
`https://tmplayer.org/screenshots/demo-phone.mp4` "is not taken on Linux". It is still closed and the
ball is with us.

Two more things the reviewer has not raised yet: the manifest in the PR unpacks the prebuilt
`v1.22.0` tarball, which Flathub's build from source rule does not accept, and the PR body ticks "I
have not used AI tools or agents to generate or automate this submission pull request". Note also that
`flathub.md` still says "Nothing has ever been submitted", which is out of date.

Recommendation from the plan: let it go (AppImage, tarball and AUR cover Linux). Do not open a new PR.
If you let it go, an optional closing note:

```sh
gh pr comment 10513 --repo flathub/flathub --body "Thanks for looking. I will leave this closed for now and come back with a manifest that builds from source and a recording made on Linux."
```

If you pursue it instead: a real screen recording of the Flatpak running on Linux, and a from-source
manifest (several days of work, see `flathub.md`), then comment on the PR. Not tied to the release,
except that a new manifest would point at the newest tag.

### 14. U25 MSone (issue #5)

Permission email sent 2026-10-05, no answer recorded. Nothing gets built until they agree. If yes,
add CP33 and reply on #5. If no, close #5 with a thank you.

### 15. CP16 and U14: winget auto-update

Needs the first winget PR (item 5) merged. Then:

```sh
gh secret set WINGET_CREATE_GITHUB_TOKEN --repo dracu-lah/TMPlayer   # paste a classic token, public_repo only
gh secret list --repo dracu-lah/TMPlayer
```

Then CP16 adds the job. Not set today.

### 16. CP17 F-Droid (for reference, not gh)

GitLab, through `glab`, not `gh`: fdroiddata MR 51285, pipeline failing in 5 jobs. Claude fixes the
recipe on our fork; commenting on the MR needs the user's OK.

## Set aside

- **U10 SignPath** (reply with references to oss-support@signpath.org): set aside with CP14 on
  2026-10-06. Windows installers stay unsigned.
- **U11 Certum, U12 signing secrets, CP14 Windows signing in release.yml:** set aside, $0 budget.
- **Phase G Google Play (U26 to U29, CP34 to CP36):** dropped, $0 budget.
- **IzzyOnDroid, telegram.org/apps:** set aside in the plan, nothing to do through gh.
