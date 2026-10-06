$ProgressPreference = 'SilentlyContinue'
Get-Process TMPlayer -ErrorAction SilentlyContinue | Stop-Process -Force
$d = "$env:LOCALAPPDATA\TMPlayer"
$jre = "$env:TEMP\tmplayer-jre"
if (-not (Test-Path "$jre\bin\java.exe")) {
  Write-Host "Downloading Java 21 (about 45 MB)..."
  Invoke-WebRequest "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse" -OutFile "$env:TEMP\tmplayer-jre.zip"
  Remove-Item -Recurse -Force "$env:TEMP\tmplayer-jre-unzip" -ErrorAction SilentlyContinue
  Expand-Archive "$env:TEMP\tmplayer-jre.zip" "$env:TEMP\tmplayer-jre-unzip" -Force
  Move-Item (Get-ChildItem "$env:TEMP\tmplayer-jre-unzip" -Directory | Select-Object -First 1).FullName $jre
}
$log = "$([Environment]::GetFolderPath('Desktop'))\tmplayer-log.txt"
Write-Host "TMPlayer is starting. Do what fails, then close its window. The log goes to $log"
& "$jre\bin\java.exe" -Djpackage.app-version=1.22.1 "-Dcompose.application.resources.dir=$d\app\resources" -Dcompose.application.configure.swing.globals=true -Dsun.java2d.uiScale.enabled=true "-Dskiko.library.path=$d\app" -cp "$d\app\*" com.tmplayer.desktop.MainKt 2>&1 | ForEach-Object { "$_" } | Tee-Object -FilePath $log
Write-Host "Done. Send $log"
