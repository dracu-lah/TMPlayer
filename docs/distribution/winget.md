# winget (Windows Package Manager)

Status: draft manifests ready, nothing submitted. The three files are in [winget/](winget/).

## What gets submitted

A pull request to [microsoft/winget-pkgs](https://github.com/microsoft/winget-pkgs) that adds:

```
manifests/d/dracu-lah/TMPlayer/1.22.0/dracu-lah.TMPlayer.yaml
manifests/d/dracu-lah/TMPlayer/1.22.0/dracu-lah.TMPlayer.installer.yaml
manifests/d/dracu-lah/TMPlayer/1.22.0/dracu-lah.TMPlayer.locale.en-US.yaml
```

- **PackageIdentifier `dracu-lah.TMPlayer`.** winget's convention is `Publisher.Package`, and the
  folder is `manifests/<first letter of the publisher, lower case>/<Publisher>/<Package>/<version>`.
  Hyphens are allowed in the publisher part. No package by this publisher exists yet (checked
  `manifests/d/dracu-lah`, 404). The identifier is permanent once merged, so decide now; an
  alternative is `TMPlayer.TMPlayer`, matching the MSI's Manufacturer, but `dracu-lah.TMPlayer`
  matches the GitHub account that owns the release URLs, which is what reviewers look at.
- **Schema 1.6.0**, as asked. winget-pkgs still accepts it; newer schema versions exist and
  `wingetcreate` writes whatever is current, which is also fine.
- **InstallerType `msi`, Scope `user`.** The MSI is made by jpackage (Compose Desktop's
  `packageReleaseMsi`) from the WiX template in `desktop/packaging/windows/main.wxs`, with
  `perUserInstall = true`, so it installs per user and needs no administrator. It has no
  `ALLUSERS` property, which is what makes it per user. jpackage MSIs take `/quiet` like any WiX
  MSI, so winget's silent install works.
- **ProductCode `{A989562B-541B-3A74-BA84-4D34ED8EF145}`**, read from the 1.22.0 MSI itself
  (downloaded, sha256 matched `site/latest.json`, Property table parsed). jpackage derives a new
  ProductCode for every version, so it changes each release; the **UpgradeCode
  `{6F1D3C0E-2B8A-4B7E-9D2C-7A1E5F4C3B21}`** is the fixed one from `desktop/build.gradle.kts`
  (`upgradeUuid`) and never changes. winget uses the ProductCode to tell which version is
  installed, so it must be right for each version.
- **InstallerSha256** is `5A36263B...ABD78`, the same hash as `site/latest.json`, upper case as
  winget writes it.
- **Publisher in Apps and Features is `TMPlayer`** (the MSI's Manufacturer, from `vendor =
  "TMPlayer"`), while the manifest's `Publisher` is `dracu-lah`. That is allowed; the
  `AppsAndFeaturesEntries` block tells winget how the installed program names itself.

### How to read the ProductCode yourself

Any of these, on the MSI of the version being submitted:

- Linux, msitools: `msiinfo export TMPlayer-<v>-windows-x64.msi Property | grep ProductCode`
- Windows PowerShell:
  ```powershell
  $i = New-Object -ComObject WindowsInstaller.Installer
  $db = $i.GetType().InvokeMember('OpenDatabase','InvokeMethod',$null,$i,@((Resolve-Path .\TMPlayer-<v>-windows-x64.msi).Path,0))
  $v = $db.GetType().InvokeMember('OpenView','InvokeMethod',$null,$db,@("SELECT Value FROM Property WHERE Property='ProductCode'"))
  $v.GetType().InvokeMember('Execute','InvokeMethod',$null,$v,$null)
  $r = $v.GetType().InvokeMember('Fetch','InvokeMethod',$null,$v,$null)
  $r.GetType().InvokeMember('StringData','GetProperty',$null,$r,1)
  ```
- Or let `wingetcreate` do it: it opens the MSI and fills in ProductCode, UpgradeCode, Scope and
  the hash by itself.

## Submitting by hand

1. Fork `microsoft/winget-pkgs`, add the three files from [winget/](winget/) at the path above.
2. Optional local check on Windows: `winget validate --manifest <folder>` and
   `winget install --manifest <folder>` (needs `winget settings --enable LocalManifestFiles` from
   an elevated prompt).
3. Open the pull request. The bots install the MSI in a sandbox, run it through Defender and
   SmartScreen style checks, and label the PR. Being unsigned is not a blocker for winget, but a
   new, unsigned MSI is sometimes held for manual review.

## Submitting with wingetcreate (simpler)

```powershell
winget install Microsoft.WingetCreate
wingetcreate new https://github.com/dracu-lah/TMPlayer/releases/download/v1.22.0/TMPlayer-1.22.0-windows-x64.msi
```

It downloads the MSI, reads it, and asks for the identifier (`dracu-lah.TMPlayer`), the publisher,
name, licence and description; the answers are in the locale file here. Finish with `--submit`
(or answer yes at the end), with a GitHub token that can fork, and it opens the PR itself.

## Later: every release updates winget by itself

Once the first PR is merged, a job in `.github/workflows/release.yml` can follow each release.
It has to run on Windows, after the release is published:

```yaml
  winget:
    needs: release            # whatever the job that publishes the GitHub release is called
    runs-on: windows-latest
    steps:
      - name: Submit to winget
        env:
          WINGET_TOKEN: ${{ secrets.WINGET_TOKEN }}
          VERSION: ${{ github.ref_name }}
        run: |
          $v = $env:VERSION.TrimStart('v')
          Invoke-WebRequest https://aka.ms/wingetcreate/latest -OutFile wingetcreate.exe
          .\wingetcreate.exe update dracu-lah.TMPlayer `
            --version $v `
            --urls "https://github.com/dracu-lah/TMPlayer/releases/download/v$v/TMPlayer-$v-windows-x64.msi" `
            --submit --token $env:WINGET_TOKEN
```

`WINGET_TOKEN` is a classic personal access token with `public_repo` scope (wingetcreate pushes
to your fork of winget-pkgs and opens the PR from it); the default `GITHUB_TOKEN` cannot do that.
`wingetcreate update` re-reads the new MSI, so the new ProductCode and hash are filled in without
anyone looking them up. The community action `vedantmgoyal9/winget-releaser` does the same job
if a ready-made action is preferred.

One thing to know: winget and the app's own updater would both offer updates. That is harmless
(the MSI upgrade replaces the old copy whichever one runs it), but winget only sees a new version
after its PR is merged, usually a day or so behind.
