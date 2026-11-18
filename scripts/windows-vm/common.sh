# Shared by up.sh, build-msi.sh and down.sh (sourced, not run). See README.md.
# shellcheck shell=bash disable=SC2034 # the variables are used by the scripts that source this

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
vm_home="${TMPLAYER_WINDOWS_VM:-$HOME/.cache/tmplayer/windows-vm}"
storage="$vm_home/storage"   # the VM's disk and the downloaded ISO (about 40 GB once installed)
shared="$vm_home/shared"     # \\host.lan\Data (and Z:) in Windows: the source, jobs, results
oem="$vm_home/oem"           # copied to C:\OEM on the first boot; install.bat runs from there
name=tmplayer-windows
# dockur/windows 6.06 (2026-10-05). Bump on purpose, never "latest".
image="${DOCKUR_IMAGE:-docker.io/dockurr/windows:6.06}"
# Windows Server 2025, what GitHub's windows-latest runs: Microsoft's 180 day evaluation ISO.
win_version="${WIN_VERSION:-2025}"
ram="${RAM_SIZE:-8G}"
cores="${CPU_CORES:-4}"
disk="${DISK_SIZE:-64G}"
viewer_port="${VIEWER_PORT:-8006}"

dry=false
x() { printf '+ %s\n' "$*" | sed -E 's/(PASSWORD=)[^ ]+/\1****/'; $dry || "$@"; }
say() { echo "== windows-vm: $*"; }
die() { echo "windows-vm: $*" >&2; exit 1; }

state() { podman container inspect -f '{{.State.Status}}' "$name" 2>/dev/null || echo none; }

# Seconds since the in-VM runner (oem/runner.ps1) last wrote its heartbeat, or a large number.
heartbeat_age() {
  local f="$shared/vm/heartbeat"
  [ -f "$f" ] || { echo 999999; return; }
  echo $(( $(date +%s) - $(stat -c %Y "$f") ))
}
