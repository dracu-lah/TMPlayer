# Runs inside the Windows VM as a job (build-msi.sh puts $Version, $UpgradeFrom, $Tests and
# $Workers in front of this file). The steps of .github/actions/desktop-windows, then an uninstall
# the action does not make: the MSI must leave no product and no launcher behind.
$ErrorActionPreference = 'Stop'
$share = Split-Path -Parent $PSScriptRoot
$work = 'C:\work\TMPlayer'
$env:RUNNER_TEMP = 'C:\work\tmp'
foreach ($v in 'JAVA_HOME', 'ANDROID_HOME') {
  Set-Item "env:$v" ([Environment]::GetEnvironmentVariable($v, 'Machine'))
}
$env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
$upgradeCode = '{6F1D3C0E-2B8A-4B7E-9D2C-7A1E5F4C3B21}'

function Step($what) { Write-Host "== $(Get-Date -Format HH:mm:ss) $what" }
# Windows PowerShell 5.1 turns a native program's stderr into errors, which Stop would make fatal.
function Gradle {
  $ErrorActionPreference = 'Continue'
  & .\gradlew.bat --no-daemon "--max-workers=$Workers" @args 2>&1 | ForEach-Object { "$_" }
  if ($LASTEXITCODE -ne 0) { throw "gradlew $args failed ($LASTEXITCODE)" }
}
function Msi($verb, $target, $log) {
  $p = Start-Process msiexec.exe -ArgumentList $verb, "`"$target`"", '/qn', '/l*v', "`"$log`"" -Wait -PassThru
  if ($p.ExitCode -ne 0) { Get-Content $log -Tail 60; throw "msiexec $verb exited $($p.ExitCode)" }
}
function Related {
  $installer = New-Object -ComObject WindowsInstaller.Installer
  $codes = @()
  foreach ($c in $installer.RelatedProducts($upgradeCode)) { $codes += [string]$c }
  return , $codes
}

try {
  Step "copy the source from the share (build\ and .gradle\ stay for a warm build)"
  robocopy (Join-Path $share 'src') $work /MIR /XD build .gradle .kotlin /NFL /NDL /NP /NJH /R:2 /W:2 | Out-Host
  if ($LASTEXITCODE -ge 8) { throw "robocopy failed ($LASTEXITCODE)" }
  Remove-Item -Recurse -Force $env:RUNNER_TEMP -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Force $env:RUNNER_TEMP, "$work\dist" | Out-Null
  Set-Location $work
  Remove-Item "dist\*.msi", 'desktop\build\compose\binaries\main-release\msi\*.msi' -ErrorAction SilentlyContinue

  # A run that failed half way can leave TMPlayer installed; start from none, like a fresh runner.
  foreach ($code in (Related)) { Step "remove the leftover $code"; Msi '/x' $code "$env:RUNNER_TEMP\leftover.log" }

  if ($Tests) { Step 'desktop tests'; Gradle ':desktop:test' }

  if ($UpgradeFrom) {
    Step "build the older MSI $UpgradeFrom"
    Gradle ':desktop:packageReleaseMsi' "-PdesktopVersion=$UpgradeFrom"
    New-Item -ItemType Directory -Force "$env:RUNNER_TEMP\old" | Out-Null
    Move-Item (Get-ChildItem 'desktop\build\compose\binaries\main-release\msi\*.msi').FullName "$env:RUNNER_TEMP\old\old.msi"
  }

  Step "build the MSI and the app image $Version"
  Gradle ':desktop:packageReleaseMsi' ':desktop:createReleaseDistributable' "-PdesktopVersion=$Version"
  $msis = @(Get-ChildItem 'desktop\build\compose\binaries\main-release\msi\*.msi')
  if ($msis.Count -ne 1) { throw "expected one MSI, found $($msis.FullName -join ', ')" }
  $msi = "$work\dist\TMPlayer-$Version-windows-x64.msi"
  Copy-Item $msis[0].FullName $msi

  Step 'check the bundled C++ runtime'
  $bin = 'desktop\build\compose\binaries\main-release\app\TMPlayer\runtime\bin'
  foreach ($dll in 'msvcp140.dll', 'vcruntime140.dll', 'vcruntime140_1.dll') {
    if (-not (Test-Path (Join-Path $bin $dll))) { throw "$dll is missing from $bin" }
  }

  if ($UpgradeFrom) { Step 'install the older MSI'; Msi '/i' "$env:RUNNER_TEMP\old\old.msi" "$env:RUNNER_TEMP\old-install.log" }

  Step 'install the MSI'
  Msi '/i' $msi "$env:RUNNER_TEMP\install.log"
  $installer = New-Object -ComObject WindowsInstaller.Installer
  $products = Related
  $versions = @($products | ForEach-Object { [string]$installer.ProductInfo($_, 'VersionString') })
  Write-Host "Installed under the upgrade code: $($versions -join ', ')"
  $info = Get-Content -Raw 'desktop\build\generated\buildinfo\com\tmplayer\desktop\BuildInfo.kt'
  $want = [regex]::Match($info, 'INSTALLER_VERSION = "([^"]+)"').Groups[1].Value
  if (-not $want) { throw 'BuildInfo.kt has no INSTALLER_VERSION' }
  if ($products.Count -ne 1 -or $versions[0] -ne $want) { throw "Expected exactly version $want installed, found: $($versions -join ', ')" }
  $exe = Get-ChildItem -Path $env:LOCALAPPDATA, $env:ProgramFiles -Recurse -Filter TMPlayer.exe -Depth 3 -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $exe) { throw 'no TMPlayer.exe after the install' }
  Write-Host "InstallLocation: $($installer.ProductInfo($products[0], 'InstallLocation')); launcher: $($exe.FullName)"

  Step 'self-test the installed app'
  & "$work\.github\actions\desktop-windows\selftest.ps1" -Exe $exe.FullName
  if ($LASTEXITCODE -ne 0) { throw 'the self-test failed' }

  Step 'uninstall'
  Msi '/x' $products[0] "$env:RUNNER_TEMP\uninstall.log"
  $left = Related
  if ($left.Count -ne 0) { throw "still installed after the uninstall: $($left -join ', ')" }
  if (Test-Path $exe.FullName) { throw "$($exe.FullName) is still there after the uninstall" }

  Step 'hand the MSI and the logs back'
  $out = Join-Path $share 'out'
  New-Item -ItemType Directory -Force $out | Out-Null
  Copy-Item $msi, "$env:RUNNER_TEMP\*.log" $out
  Write-Host 'WINDOWS MSI CHECK PASSED'
  exit 0
} catch {
  Write-Host "FAILED: $($_.Exception.Message)"
  Write-Host $_.ScriptStackTrace
  exit 1
}
