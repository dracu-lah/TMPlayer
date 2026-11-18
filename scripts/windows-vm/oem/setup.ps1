# First boot only (install.bat): what GitHub's windows-latest image has and the MSI build needs,
# then the job runner. Log: C:\OEM\setup.log. Safe to run again by hand.
$ErrorActionPreference = 'Continue'
. C:\OEM\config.ps1   # $User, $Password, written by up.sh

# Builds and installs in C:\work; Defender scanning every Gradle file doubles the build time.
New-Item -ItemType Directory -Force C:\work | Out-Null
Add-MpPreference -ExclusionPath 'C:\work', "C:\Users\$User\.gradle", 'C:\Android' -ErrorAction SilentlyContinue

# WiX 3, which Compose's packageMsi fetches itself, runs on .NET Framework 3.5.
dism.exe /online /enable-feature /featurename:NetFx3 /all /norestart

# Temurin JDK 21, as actions/setup-java gives the CI job, with JAVA_HOME set machine wide.
$jdk = 'C:\OEM\jdk21.msi'
curl.exe -fsSL --retry 3 -o $jdk 'https://api.adoptium.net/v3/installer/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'
Start-Process msiexec.exe -Wait -ArgumentList '/i', $jdk, '/qn', 'ADDLOCAL=FeatureMain,FeatureEnvironment,FeatureJavaHome', '/l*v', 'C:\OEM\jdk-install.log'

# An Android SDK folder with this machine's accepted licences: the KMP modules configure their
# Android targets, and Gradle fetches the platform and build tools into it on the first build.
New-Item -ItemType Directory -Force C:\Android\sdk\licenses | Out-Null
Copy-Item C:\OEM\android-licenses\* C:\Android\sdk\licenses\ -ErrorAction SilentlyContinue
[Environment]::SetEnvironmentVariable('ANDROID_HOME', 'C:\Android\sdk', 'Machine')

powercfg.exe /change standby-timeout-ac 0
powercfg.exe /change monitor-timeout-ac 0

# The runner starts at boot under the local account, logged on or not, so it reaches the share
# with that account's rights and installs per user as the CI runner's account does.
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument '-NoProfile -ExecutionPolicy Bypass -File C:\OEM\runner.ps1'
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName 'TMPlayerRunner' -Action $action -Trigger $trigger -Settings $settings `
  -User "$env:COMPUTERNAME\$User" -Password $Password -RunLevel Highest -Force
Start-ScheduledTask -TaskName 'TMPlayerRunner'
Write-Host 'setup done'
