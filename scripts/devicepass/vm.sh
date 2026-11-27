#!/usr/bin/env bash
# The win11 podman VM for the desktop MSI check. Prints every command before it runs.
#
#   scripts/devicepass/vm.sh status
#   scripts/devicepass/vm.sh start                 # podman start win11, wait for the web viewer
#   scripts/devicepass/vm.sh fetch-msi <run-id>    # gh run download tmplayer-desktop-windows into shared/
#   scripts/devicepass/vm.sh copy-msi <file.msi>   # put a local MSI in shared/ as tmplayer-ci.msi
#   scripts/devicepass/vm.sh copy-jars             # keyed local jars into shared/jars/
#   scripts/devicepass/vm.sh queue-install [--clean] [--jars]   # needs runner.ps1 running in the VM
#   scripts/devicepass/vm.sh stop                  # only if this script started it
#
# From memory and the working plan:
# - Container `win11` (dockurr/windows). Web viewer http://127.0.0.1:8006, VNC 127.0.0.1:5900,
#   RDP 3389, SSH 127.0.0.1:2222 (often resets; VNC with vncdotool works, `key 'shift-;'` for a
#   colon, plain `type` drops Shift).
# - Host $HOME/vms/win11/shared is \\host.lan\Data inside the VM.
# - CI's MSI is unsigned and has no Telegram keys; the keyed local jars (core-jvm, ui-jvm, desktop)
#   are swapped in after install, as shared/test.ps1 does.
# - Since 1ac79d3 the MSI installs per user to %LOCALAPPDATA%\TMPlayer App (not ...\TMPlayer),
#   so the older ci.ps1 and test.ps1 in shared/ look in the wrong folder; queue-install writes its
#   own script that looks in both.
# - Stop the emulators before starting the VM (one set at a time).
set -euo pipefail

NAME="${VM_NAME:-win11}"
SHARED="${VM_SHARED:-$HOME/vms/win11/shared}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
STATE="${XDG_RUNTIME_DIR:-/tmp}/tmplayer-devicepass"
mkdir -p "$STATE"

say() { printf '>> %s\n' "$*" >&2; }
run() { printf '$ %s\n' "$*" >&2; "$@"; }
running() { podman ps --format '{{.Names}}' | grep -qx "$NAME"; }

cmd_status() {
  run podman ps -a --filter "name=^${NAME}\$" --format '{{.Names}} {{.Status}} {{.Ports}}'
  [[ -f "$STATE/vm.started" ]] && say "started by this script" || say "not started by this script"
  if command -v adb >/dev/null && adb devices | grep -q '^emulator-'; then
    say "note: emulators are attached; stop them before the VM pass (emulators.sh stop all)"
  fi
  ls -la "$SHARED"/*.msi 2>/dev/null || say "no MSI in $SHARED yet"
}

cmd_start() {
  if running; then say "$NAME is already running; not marking it as ours"; return; fi
  if command -v adb >/dev/null && adb devices | grep -q '^emulator-'; then
    say "warning: emulators are still running; the notes say kill them before the VM"
  fi
  run podman start "$NAME"
  date +%s > "$STATE/vm.started"
  say "waiting for the web viewer on 127.0.0.1:8006"
  for _ in $(seq 1 90); do
    if curl -fsS -o /dev/null http://127.0.0.1:8006/; then
      say "up: open http://127.0.0.1:8006 (Windows itself can take another minute to reach the desktop)"
      return
    fi
    sleep 2
  done
  say "viewer did not answer in 180 s; check: podman logs --tail 50 $NAME"
}

cmd_fetch_msi() {
  local run_id="$1" tmp
  tmp="$(mktemp -d)"
  run gh run download "$run_id" -R dracu-lah/TMPlayer -n tmplayer-desktop-windows -D "$tmp"
  local msi; msi=$(find "$tmp" -name '*.msi' | head -1)
  [[ -n "$msi" ]] || { echo "no .msi in the artifact ($tmp)" >&2; exit 1; }
  cmd_copy_msi "$msi"
}

cmd_copy_msi() {
  local msi="$1"
  [[ -f "$msi" ]] || { echo "no such file: $msi" >&2; exit 1; }
  if [[ -f "$SHARED/tmplayer-ci.msi" ]]; then
    run cp -p "$SHARED/tmplayer-ci.msi" "$SHARED/tmplayer-ci.previous.msi"
  fi
  run cp "$msi" "$SHARED/tmplayer-ci.msi"
  say "in the VM: \\\\host.lan\\Data\\tmplayer-ci.msi"
}

cmd_copy_jars() {
  mkdir -p "$SHARED/jars"
  for j in core/build/libs/core-jvm.jar ui/build/libs/ui-jvm.jar desktop/build/libs/desktop.jar; do
    [[ -f "$REPO/$j" ]] || { echo "missing $REPO/$j: build the desktop locally with the keys first" >&2; exit 1; }
    run cp "$REPO/$j" "$SHARED/jars/"
  done
  ls -la "$SHARED/jars"
}

cmd_queue_install() {
  local clean=0 jars=0
  for a in "$@"; do case "$a" in --clean) clean=1 ;; --jars) jars=1 ;; *) echo "unknown $a" >&2; exit 2 ;; esac; done
  mkdir -p "$SHARED/q"
  local n; n=$(( $(find "$SHARED/q" -maxdepth 1 -name "*.ps1" | wc -l) + 30 ))
  local f="$SHARED/q/${n}-devicepass-install.ps1"
  say "writing $f (clean uninstall first: $clean, swap in keyed jars: $jars)"
  say "without --clean the MSI upgrades in place, which is the data safety check: sign in, settings and lists must survive"
  cat > "$f" <<PS1
# Written by scripts/devicepass/vm.sh queue-install. Run by runner.ps1 inside the VM.
Get-Process TMPlayer -ErrorAction SilentlyContinue | Stop-Process -Force
\$roots = 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall', 'HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall'
foreach (\$k in (Get-ChildItem \$roots -ErrorAction SilentlyContinue | Get-ItemProperty | Where-Object { \$_.DisplayName -eq 'TMPlayer' })) {
    Write-Host "found \$(\$k.DisplayVersion) at \$(\$k.InstallLocation)"
    if ($clean) { Write-Host 'removing it first'; Start-Process msiexec.exe -ArgumentList '/x', \$k.PSChildName, '/qn' -Wait }
}
Copy-Item '\\\\host.lan\\Data\\tmplayer-ci.msi' "\$env:TEMP\\ci.msi" -Force
\$p = Start-Process msiexec.exe -ArgumentList '/i', "\`"\$env:TEMP\\ci.msi\`"", '/qn', '/l*v', "\$env:TEMP\\ci-install.log" -Wait -PassThru
Write-Host "msiexec exit \$(\$p.ExitCode)"
Copy-Item "\$env:TEMP\\ci-install.log" '\\\\host.lan\\Data\\q\\ci-install.log' -Force -ErrorAction SilentlyContinue
\$exe = Get-ChildItem "\$env:LOCALAPPDATA\\TMPlayer App", "\$env:LOCALAPPDATA\\TMPlayer", "\$env:ProgramFiles\\TMPlayer" -Filter TMPlayer.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not \$exe) { Write-Host 'TMPlayer.exe not found after install'; exit 1 }
Write-Host "installed at \$(\$exe.DirectoryName)"
if ($jars) {
    \$app = Join-Path \$exe.DirectoryName 'app'
    Copy-Item '\\\\host.lan\\Data\\jars\\desktop.jar' (Join-Path \$app 'desktop.jar') -Force
    foreach (\$n in 'core-jvm', 'ui-jvm') {
        \$old = Get-ChildItem \$app -Filter "\$n-*.jar" | Select-Object -First 1
        Copy-Item "\\\\host.lan\\Data\\jars\\\$n.jar" \$old.FullName -Force
        Write-Host "replaced \$(\$old.Name)"
    }
}
Start-Process \$exe.FullName
Write-Host 'started'
PS1
  say "inside the VM, if the runner is not already going:"
  say "  powershell -executionpolicy bypass -file \\\\host.lan\\Data\\runner.ps1"
  say "output lands in ${f%.ps1}.out"
}

cmd_stop() {
  if [[ ! -f "$STATE/vm.started" ]]; then
    say "$NAME was not started by this script; leaving it running (podman stop -t 120 $NAME by hand if you mean it)"
    return
  fi
  run podman stop -t 120 "$NAME"
  rm -f "$STATE/vm.started"
}

case "${1:-}" in
  status) cmd_status ;;
  start) cmd_start ;;
  fetch-msi) cmd_fetch_msi "${2:?CI run id (gh run list -w desktop-check.yml)}" ;;
  copy-msi) cmd_copy_msi "${2:?path to the .msi}" ;;
  copy-jars) cmd_copy_jars ;;
  queue-install) shift; cmd_queue_install "$@" ;;
  stop) cmd_stop ;;
  *) sed -n '2,11p' "$0"; exit 2 ;;
esac
