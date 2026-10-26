# TMPlayer's MSI update. SelfUpdate.stageMsi fills in the values marked with @@, writes this beside the
# install folder and runs it once the app has quit.
#
# Why it is this careful (issue #7): jpackage's per user MSI deletes its whole install folder on
# uninstall (util:RemoveFolderEx on INSTALLDIR), and a major upgrade runs the old version's
# uninstall first (RemoveExistingProducts before CostInitialize), outside any rollback. The install
# folder was %LOCALAPPDATA%\TMPlayer up to 1.26.0, which is also where the app keeps its sign in,
# its settings and its cache, and where the update used to download the MSI and this script to.
# The old version's uninstall deleted the very MSI being installed, so the new version never
# arrived. Later versions install to %LOCALAPPDATA%\TMPlayer App, so the launcher this
# update ends with can be in another folder than the one it started from (Find-Launcher).
#
# So this script, the MSI, the logs and the kept data all live in the folder beside the install
# folder (TMPlayer-update), never inside it; the data is moved out for the install and back after;
# every step is logged; and a failure leaves a marker the app reads on its next start, or opens
# the download page when there is no app left to start.

$ErrorActionPreference = 'Continue'

$AppPid = @@PID@@
$Msi = '@@MSI@@'
$Exe = '@@EXE@@'
$InstallDir = '@@INSTALL_DIR@@'
$Root = '@@ROOT@@'
$Version = '@@VERSION@@'
# What Windows Installer calls it: 1.28.299 for 1.28.2, 1.28.201 for 1.28.2-beta.1 (RELEASING.md).
$ProductVersion = '@@PRODUCT_VERSION@@'
$UpgradeCode = '@@UPGRADE_CODE@@'
$RegistryKey = '@@REGISTRY_KEY@@'
$Marker = Join-Path $Root '@@MARKER@@'
$DownloadPage = 'https://tmplayer.org/download/'
$Kept = Join-Path $Root 'kept'

Set-Location -LiteralPath $Root
try { Start-Transcript -LiteralPath (Join-Path $Root 'update.log') -Force | Out-Null } catch { }

# Write-Host, not Write-Output: the transcript records it, and it never becomes a function's result.
function Say($text) { Write-Host ('{0:yyyy-MM-dd HH:mm:ss} {1}' -f (Get-Date), $text) }

Say "Updating TMPlayer in $InstallDir to $Version from $Msi"

# The app first, then anything else still running from the install folder, which would hold its
# files open. A process that will not quit is ended rather than installed around.
$app = Get-Process -Id $AppPid -ErrorAction SilentlyContinue
if ($app) {
    Say "Waiting for TMPlayer (process $AppPid) to quit"
    if (-not $app.WaitForExit(60000)) {
        Say 'Still running after 60 seconds, so it is ended'
        Stop-Process -Id $AppPid -Force -ErrorAction SilentlyContinue
        [void]$app.WaitForExit(15000)
    }
}
$prefix = $InstallDir.TrimEnd('\') + '\'
Get-Process -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and $_.Path.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) } |
    ForEach-Object {
        Say "Ending $($_.ProcessName) (process $($_.Id)), still running from the install folder"
        Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
    }
Start-Sleep -Seconds 2

# What the MSI installs at the top of the install folder: everything else there is the app's data.
function Test-PackageFile($item) {
    if ($item.PSIsContainer) { return @('app', 'runtime') -contains $item.Name }
    return @('.exe', '.ico') -contains $item.Extension
}

# Moves the data out of the install folder. A move inside one drive is a rename, so a large cache
# costs nothing. False when something could not be moved; Restore-Data then puts back what was moved.
function Save-Data {
    if (-not (Test-Path -LiteralPath $InstallDir)) { return $true }
    New-Item -ItemType Directory -Force -Path $Kept | Out-Null
    foreach ($item in @(Get-ChildItem -LiteralPath $InstallDir -Force)) {
        if (Test-PackageFile $item) { continue }
        $target = Join-Path $Kept $item.Name
        if (Test-Path -LiteralPath $target) {
            Say "$target is there already, so $($item.Name) cannot be kept"
            return $false
        }
        try {
            Move-Item -LiteralPath $item.FullName -Destination $target -ErrorAction Stop
            Say "Kept $($item.Name)"
        } catch {
            Say "Could not move $($item.Name) out of the install folder: $_"
            return $false
        }
    }
    return $true
}

# Puts the kept data back, into a fresh install folder if the old one is gone, so the sign in and
# settings are there for whichever version runs next. Never overwrites: what is already in the
# install folder wins, and the kept copy stays in $Kept.
function Restore-Data {
    if (-not (Test-Path -LiteralPath $Kept)) { return }
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    foreach ($item in @(Get-ChildItem -LiteralPath $Kept -Force)) {
        $target = Join-Path $InstallDir $item.Name
        if (Test-Path -LiteralPath $target) {
            Say "$($item.Name) is in the install folder already; the kept one stays in $Kept"
            continue
        }
        try {
            Move-Item -LiteralPath $item.FullName -Destination $target -ErrorAction Stop
            Say "Put back $($item.Name)"
        } catch {
            Say "Could not put back $($item.Name): $_"
        }
    }
    if (-not @(Get-ChildItem -LiteralPath $Kept -Force)) { Remove-Item -LiteralPath $Kept -Force -ErrorAction SilentlyContinue }
}

# Where the MSI put the launcher: where it was, or in the install folder of 1.26.1 and later.
function Find-Launcher {
    if (Test-Path -LiteralPath $Exe) { return $Exe }
    $name = Split-Path -Leaf $Exe
    foreach ($dir in (Join-Path $env:LOCALAPPDATA 'TMPlayer App'), (Join-Path $env:LOCALAPPDATA 'TMPlayer')) {
        $candidate = Join-Path $dir $name
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    return $null
}

# The version Windows Installer has for this upgrade code, or null.
function Get-InstalledVersion {
    try {
        $installer = New-Object -ComObject WindowsInstaller.Installer
        $get = [Reflection.BindingFlags]::GetProperty
        $codes = $installer.GetType().InvokeMember('RelatedProducts', $get, $null, $installer, @($UpgradeCode))
        $count = $codes.GetType().InvokeMember('Count', $get, $null, $codes, $null)
        for ($i = 0; $i -lt $count; $i++) {
            $code = $codes.GetType().InvokeMember('Item', $get, $null, $codes, @($i))
            return [string]$installer.GetType().InvokeMember('ProductInfo', $get, $null, $installer, @($code, 'VersionString'))
        }
    } catch {
        Say "Could not ask Windows Installer for the installed version: $_"
    }
    return $null
}

# The new version is in place: its launcher is there, and the MSI's own registry key for this
# version exists (or Windows Installer reports the version).
function Test-NewVersion {
    if (-not (Find-Launcher)) { return $false }
    if (Test-Path -LiteralPath $RegistryKey) { return $true }
    $found = Get-InstalledVersion
    Say "Windows Installer reports version '$found'"
    return $found -eq $ProductVersion
}

# One msiexec run with a verbose log of its own. The exit code, or a negative number when
# msiexec could not start or did not finish within 20 minutes.
function Install-Msi($attempt) {
    $log = Join-Path $Root "msiexec-$attempt.log"
    $arguments = '/i "{0}" /passive /norestart /l*v "{1}"' -f $Msi, $log
    Say "msiexec $arguments"
    try {
        $process = Start-Process -FilePath 'msiexec.exe' -ArgumentList $arguments -PassThru -ErrorAction Stop
        # Reading the handle now keeps the exit code readable once the process has gone.
        $null = $process.Handle
        if (-not $process.WaitForExit(20 * 60 * 1000)) {
            Say 'msiexec has not finished after 20 minutes'
            return -1
        }
        Say "msiexec finished with exit code $($process.ExitCode)"
        return $process.ExitCode
    } catch {
        Say "Could not start msiexec: $_"
        return -2
    }
}

# Data an earlier run could not put back goes home first.
Restore-Data

$installed = $false
if (Save-Data) {
    # 0 is done, 3010 and 1641 are done with a restart asked for. Anything else is tried once more:
    # by then the old version is usually gone, so the second run is a plain install.
    foreach ($attempt in 1, 2) {
        $code = Install-Msi $attempt
        $installed = (@(0, 1641, 3010) -contains $code) -and (Test-NewVersion)
        if ($installed) { break }
        Say "TMPlayer $Version is not installed after attempt $attempt"
        if ($attempt -eq 1) { Start-Sleep -Seconds 15 }
    }
} else {
    Say 'The data could not be moved out of the install folder, so nothing was installed'
}
Restore-Data

if ($installed) {
    Say "TMPlayer $Version is installed"
    Remove-Item -LiteralPath $Marker -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $Msi -Force -ErrorAction SilentlyContinue
} else {
    Say "The update failed; leaving $Marker for the app"
    Set-Content -LiteralPath $Marker -Value $Version -Encoding ASCII
}

$launcher = Find-Launcher
if ($launcher) {
    Say "Starting $launcher"
    Start-Process -FilePath $launcher -WorkingDirectory (Split-Path -Parent $launcher)
} else {
    Say "TMPlayer is not installed; opening $DownloadPage"
    Start-Process $DownloadPage
}

try { Stop-Transcript | Out-Null } catch { }
