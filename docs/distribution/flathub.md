# Flathub

Status: **not advisable right now.** Not blocked by policy, but blocked by effort: Flathub would
need TMPlayer built entirely from source inside flatpak-builder, offline, and the desktop app
currently depends on three prebuilt native stacks that would each have to be rebuilt there.
Nothing has ever been submitted.

## What the repository has today

- **AppStream metainfo:** `desktop/packaging/linux/io.github.dracu_lah.TMPlayer.metainfo.xml`.
  Good shape already: id, `metadata_license` CC0-1.0, `project_license` GPL-3.0-or-later,
  developer id and name, a description that says it is unofficial, launchable, homepage,
  bugtracker and vcs URLs, categories, keywords, `provides/binary`, OARS rating, a release entry
  filled in by `package.sh`. **Missing: `<screenshots>`**, which Flathub requires for every
  graphical app.
- **Desktop file:** `desktop/packaging/linux/io.github.dracu_lah.TMPlayer.desktop`, icon names
  under `desktop/packaging/icons/hicolor`.
- **No Flatpak manifest any more.** There were two, `desktop/packaging/flatpak/io.github.dracu_lah.TMPlayer.yml`
  and a `.flathub.yml` variant (added in 99e6291, built in CI from 1.18.0). They were deleted in
  882ef89 (`release: publish only the universal APK, the MSI, the AppImage and the tarball`,
  2026-10-04), together with the deb, rpm, per-ABI APKs and the Windows zip. The reason given is
  release-set size, not a technical failure: four files per release instead of eleven.
  `INSTALL.md` tells Flatpak users to move to the AppImage.
- The desktop app knows when it runs inside a Flatpak (`SelfUpdate.kt` checks `/.flatpak-info`
  and `FLATPAK_ID`), so its self-updater can stand aside there, which Flathub would want.

The deleted Flathub variant is in git (`git show 882ef89^:desktop/packaging/flatpak/io.github.dracu_lah.TMPlayer.flathub.yml`).
Its finish-args (network, ipc, x11, pulseaudio, dri, `xdg-download:create`, notifications,
screensaver inhibit, MPRIS names) are a sound starting point. Its build, though, unpacks the
released `linux-x64` tarball, and **that is exactly what Flathub does not accept**.

## The rule that blocks it

From Flathub's requirements (docs.flathub.org, "Building from source"):

> All source available submissions must be built entirely from source code. This requirement
> applies to the main application component defined in the manifest, as well as any runtime
> dependencies included in the manifest.

and "There is no network access during the build process", so every Gradle and Maven dependency
must be listed in the manifest as a source.

For TMPlayer's desktop build that means:

| Piece | Today | What Flathub needs |
| --- | --- | --- |
| Kotlin, Compose Desktop, all jars | Gradle downloads them | Offline Gradle build: generate a sources list with flatpak-builder-tools' Gradle generator, use `org.freedesktop.Sdk.Extension.openjdk21` for the JDK, and keep that list current every time a dependency changes |
| TDLib | Prebuilt `.so` inside the `tdl-coroutines` jar | Build TDLib (C++, CMake, OpenSSL, zlib, gperf) from source as a module, and make the JNI loader use it instead of the copy in the jar |
| libmpv and FFmpeg | Prebuilt inside the `mediamp-mpv-runtime-linux-x64` jar | Build mpv and FFmpeg from source (Flathub has shared modules and many apps already do this), and point mediamp at them |
| Skiko (Compose's renderer) | Prebuilt `.so` inside the skiko jar | Either an exception, or build Skia and Skiko from source, which is very heavy |

Flathub may grant exceptions "to well-known vendors on a case-by-case basis", which does not
cover a one person project, and other Compose Desktop apps there have had to argue the Skiko case
individually. Realistic effort: several days for the first working manifest, then a maintenance
cost on every release and every dependency bump. That is why this is not advised until there is
clear demand from Linux users who cannot use the AppImage.

## If you do it anyway

### Application ID

Two valid choices:

- **`io.github.dracu_lah.TMPlayer`**: what the metainfo, desktop file and icons already use, and
  what 1.18.0 to 1.21.0 Flatpak users had. Four components, `io.github.` prefix, the hyphen in the
  user name becomes `_`, which is the documented demangling. Verification is automatic by logging
  in to Flathub with the GitHub account. No renames needed.
- **`org.tmplayer.TMPlayer`**: valid because tmplayer.org is the project's own domain. Verification
  is by placing a token Flathub gives you at `https://tmplayer.org/.well-known/org.flathub.VerifiedApps.txt`
  (currently 404, as expected). This means renaming the metainfo, desktop file and icons, and the
  sandbox data folder differs from the old Flatpak's `~/.var/app/io.github.dracu_lah.TMPlayer`.

Recommendation: keep `io.github.dracu_lah.TMPlayer`. It costs nothing and is already everywhere.
If the brand domain matters more, switch to `org.tmplayer.TMPlayer` before the first submission,
since an ID cannot be changed cheaply afterwards.

### Metainfo screenshots

Add a `<screenshots>` block whose images are direct links pinned to a tag or commit, not a branch,
for example:

```xml
<screenshots>
  <screenshot type="default">
    <image>https://raw.githubusercontent.com/dracu-lah/TMPlayer/v1.22.0/site/screenshots/tv-grid.webp</image>
    <caption>A chat's videos as a poster grid</caption>
  </screenshot>
  <screenshot>
    <image>https://raw.githubusercontent.com/dracu-lah/TMPlayer/v1.22.0/site/screenshots/tv-chats.webp</image>
    <caption>Browsing chats</caption>
  </screenshot>
</screenshots>
```

Two caveats: the existing screenshots are of the TV and phone apps, while Flathub wants the app it
ships, so take desktop window screenshots; and Flathub mirrors the images, so check that a WebP
comes through its preview before relying on it (PNG is the safe choice for this one file set). Validate with
`flatpak run --command=flatpak-builder-lint org.flatpak.Builder appstream <file>`.

### Submission steps

From Flathub's submission docs:

1. Fork [flathub/flathub](https://github.com/flathub/flathub/fork) with *Copy the master branch
   only* unchecked, then `git clone --branch=new-pr git@github.com:<you>/flathub.git`.
   (Or `gh repo fork --clone flathub/flathub && cd flathub && git checkout --track origin/new-pr`.)
2. `git checkout -b tmplayer-submission new-pr`, add the manifest (`<app-id>.yml`) and any
   generated sources files (Gradle sources JSON) at the top level.
3. Build, install and lint locally (commands from Flathub's submission page):
   `flatpak run --command=flathub-build org.flatpak.Builder --install <app-id>.yml`, then
   `flatpak run --command=flatpak-builder-lint org.flatpak.Builder manifest <app-id>.yml` and
   `flatpak run --command=flatpak-builder-lint org.flatpak.Builder repo repo`.
4. Open the PR against the **`new-pr`** base branch (not `master`), titled `Add <app-id>`.
   Comment `bot, build` to trigger a test build. Reviewers will ask about the prebuilt natives.
5. After merge, Flathub creates `flathub/<app-id>`, you get write access, and every release is a PR
   there. `x-checker-data` (as in the old manifest) lets Flathub's bot open those PRs for you.
