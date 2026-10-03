# Runs TMPlayer.exe --self-test and fails unless it passes. The jpackage launcher is a GUI
# program, so the report is read back from the file the self-test writes rather than from stdout.
param([Parameter(Mandatory = $true)][string]$Exe)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Exe)) { throw "No launcher at $Exe" }
$report = Join-Path $env:RUNNER_TEMP ("selftest-" + [guid]::NewGuid() + ".txt")
$p = Start-Process -FilePath $Exe -ArgumentList "--self-test=$report" -Wait -PassThru -NoNewWindow
if (Test-Path $report) { Get-Content $report } else { Write-Host "(no report written)" }
Write-Host "exit code $($p.ExitCode) from $Exe"
if ($p.ExitCode -ne 0) { exit 1 }
