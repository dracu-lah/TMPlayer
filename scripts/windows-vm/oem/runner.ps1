# The job runner (scheduled task TMPlayerRunner, setup.ps1). It writes a heartbeat to the share
# every few seconds, and runs each jobs\<id>.ps1 the host drops there (build-msi.sh) once, with
# its output in jobs\<id>.log and its exit code in jobs\<id>.exit. A job the VM was restarted in
# the middle of is marked 99, so the host does not wait for it forever.
$ErrorActionPreference = 'Continue'

function Find-Share {
  foreach ($p in '\\host.lan\Data', 'Z:\') { if (Test-Path (Join-Path $p 'jobs')) { return $p } }
  return $null
}

function Beat($share) {
  Set-Content -Path (Join-Path $share 'vm\heartbeat') -Value (Get-Date -Format o) -ErrorAction SilentlyContinue
}

$first = $true
while ($true) {
  $share = Find-Share
  if (-not $share) { Start-Sleep -Seconds 10; continue }
  $jobs = Join-Path $share 'jobs'
  if ($first) {
    New-Item -ItemType Directory -Force (Join-Path $share 'vm') | Out-Null
    $jh = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
    $java = (& "$jh\bin\java.exe" -version 2>&1 | Select-Object -First 1)
    $os = (Get-CimInstance Win32_OperatingSystem)
    Set-Content (Join-Path $share 'vm\versions.txt') @("$($os.Caption) $($os.Version)", "java: $java", "share: $share", "user: $env:USERNAME")
    Get-ChildItem $jobs -Filter *.started -ErrorAction SilentlyContinue | ForEach-Object {
      $exit = $_.FullName -replace '\.started$', '.exit'
      if (-not (Test-Path $exit)) { Set-Content $exit '99' }
    }
    $first = $false
  }
  Beat $share
  $job = Get-ChildItem $jobs -Filter *.ps1 -ErrorAction SilentlyContinue | Sort-Object Name |
    Where-Object { -not (Test-Path ($_.FullName -replace '\.ps1$', '.started')) } | Select-Object -First 1
  if ($job) {
    $base = $job.FullName -replace '\.ps1$', ''
    Set-Content "$base.started" (Get-Date -Format o)
    # The job reads JAVA_HOME, ANDROID_HOME and Path from the registry itself: this process may
    # have started before setup.ps1 set them.
    $p = Start-Process powershell.exe -PassThru -NoNewWindow `
      -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', "& '$($job.FullName)' *> '$base.log'; exit `$LASTEXITCODE"
    $null = $p.Handle   # without it ExitCode can come back empty once the process ends
    while (-not $p.HasExited) { Beat $share; Start-Sleep -Seconds 15 }
    Set-Content "$base.exit" ([string]$p.ExitCode)
  }
  Start-Sleep -Seconds 5
}
