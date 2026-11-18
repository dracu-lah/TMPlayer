#!/usr/bin/env bash
# Starts the Windows VM (dockur/windows under rootless podman, QEMU with /dev/kvm) and waits until
# Windows is up and the job runner inside it answers (README.md). The first start downloads
# Microsoft's evaluation ISO (about 7.6 GB) and installs Windows unattended, 20 to 40 min, then
# installs JDK 21, .NET 3.5 (WiX 3) and the runner from oem/; later starts boot the installed disk.
#
#   scripts/windows-vm/up.sh             start (or create) the VM and wait for the runner
#   scripts/windows-vm/up.sh --dry-run   check the host and print the podman command, start nothing
#   scripts/windows-vm/up.sh --no-wait   start it and return; watch it at http://127.0.0.1:8006
#   scripts/windows-vm/up.sh --force     skip the free memory check (12 GB available)
#
# WIN_VERSION (2025), RAM_SIZE (8G), CPU_CORES (4), DISK_SIZE (64G), TMPLAYER_WINDOWS_VM
# (~/.cache/tmplayer/windows-vm), DOCKUR_IMAGE: see common.sh.
set -euo pipefail
# shellcheck source=scripts/windows-vm/common.sh
. "$(dirname "$0")/common.sh"

wait=true force=false
for a in "$@"; do
  case "$a" in
    --dry-run) dry=true ;;
    --no-wait) wait=false ;;
    --force) force=true ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) die "unknown argument $a" ;;
  esac
done

# --- the host ---------------------------------------------------------------------------------
problems=()
command -v podman >/dev/null || problems+=("podman is not installed")
[ -r /dev/kvm ] && [ -w /dev/kvm ] || problems+=("/dev/kvm is missing or not read/writable by $(id -un) (KVM, the kvm group)")
[ -e /dev/net/tun ] || problems+=("/dev/net/tun is missing")
avail_gb=$(awk '/^MemAvailable:/ { print int($2 / 1048576) }' /proc/meminfo)
mkdir -p "$vm_home" 2>/dev/null || true
free_gb=$(df -P --block-size=1G "$vm_home" | awk 'NR == 2 { print $4 }')
installed=false
[ -n "$(find "$storage" -maxdepth 1 -name 'data.*' -size +1G 2>/dev/null)" ] && installed=true
need_disk=60; $installed && need_disk=10
if [ "$avail_gb" -lt 12 ] && ! $force; then
  problems+=("$avail_gb GB of memory available, 12 GB needed (the VM takes $ram; --force to try anyway)")
fi
[ "$free_gb" -ge "$need_disk" ] || problems+=("$free_gb GB free under $vm_home, $need_disk GB needed")
say "host: ${avail_gb} GB memory available, ${free_gb} GB free disk, Windows installed: $installed, container: $(state)"
if [ ${#problems[@]} -gt 0 ]; then
  printf 'windows-vm: %s\n' "${problems[@]}" >&2
  $dry || exit 1
  echo "windows-vm: (dry run) a real run stops here" >&2
fi

# --- the files the VM reads -------------------------------------------------------------------
# A local account with a password made here once, kept beside the disk (mode 600). The VM's
# ports listen on 127.0.0.1 only.
creds="$vm_home/credentials"
if [ ! -f "$creds" ] && ! $dry; then
  umask 077
  printf 'user=tmplayer\npassword=%s\n' "$(head -c 18 /dev/urandom | base64 | tr -d '/+=')" > "$creds"
fi
user=tmplayer password=dry-run
[ -f "$creds" ] && { user=$(sed -n 's/^user=//p' "$creds"); password=$(sed -n 's/^password=//p' "$creds"); }

x mkdir -p "$storage" "$shared/jobs" "$shared/vm" "$oem/android-licenses"
x cp "$here/oem/install.bat" "$here/oem/setup.ps1" "$here/oem/runner.ps1" "$oem/"
# The Android SDK licences this machine accepted, so Gradle in the VM may fetch the SDK packages
# the build needs (docs/BUILDING.md), as GitHub's Windows image has them.
sdk=$(sed -n 's/^sdk\.dir=//p' "$repo/local.properties" 2>/dev/null || true)
sdk="${ANDROID_HOME:-${sdk:-$HOME/Android/Sdk}}"
if [ -d "$sdk/licenses" ]; then
  x cp "$sdk/licenses/"* "$oem/android-licenses/"
else
  echo "windows-vm: no $sdk/licenses: Gradle in the VM cannot fetch the Android SDK it configures" >&2
fi
if ! $dry; then
  # shellcheck disable=SC2016 # PowerShell variables, written as they are
  printf '$User = "%s"\r\n$Password = "%s"\r\n' "$user" "$password" > "$oem/config.ps1"
  chmod 600 "$oem/config.ps1"
fi

# --- start ------------------------------------------------------------------------------------
case "$(state)" in
  running) say "already running" ;;
  none)
    # label=disable: SELinux would otherwise deny the bind mounts (as in the other podman scripts).
    x podman run -d --name "$name" \
      --device /dev/kvm --device /dev/net/tun --cap-add NET_ADMIN \
      --security-opt label=disable --stop-timeout 120 \
      -p "127.0.0.1:$viewer_port:8006" -p 127.0.0.1:3389:3389/tcp -p 127.0.0.1:3389:3389/udp \
      -e VERSION="$win_version" -e RAM_SIZE="$ram" -e CPU_CORES="$cores" -e DISK_SIZE="$disk" \
      -e USERNAME="$user" -e PASSWORD="$password" \
      -v "$storage:/storage" -v "$shared:/shared" -v "$oem:/oem" \
      "$image" ;;
  *) x podman start "$name" ;;
esac
say "screen: http://127.0.0.1:$viewer_port (or RDP to 127.0.0.1:3389 as $user, password in $creds)"
$wait || exit 0
$dry && { say "dry run done"; exit 0; }

# --- wait for the runner ----------------------------------------------------------------------
# First boot: ISO download, Windows setup, then setup.ps1 (JDK, .NET 3.5, the runner task).
limit=$(( ${UP_TIMEOUT_MIN:-90} * 60 )); t=0
until [ "$(heartbeat_age)" -lt 120 ]; do
  [ "$(state)" = running ] || { podman logs --tail 40 "$name" >&2; die "the container stopped"; }
  [ "$t" -lt "$limit" ] || die "no runner heartbeat after $(( limit / 60 )) min: look at http://127.0.0.1:$viewer_port and podman logs $name"
  [ $(( t % 300 )) -eq 0 ] && say "waiting for Windows ($(( t / 60 )) min): $(podman logs --tail 1 "$name" 2>&1 | tr -d '\r' | cut -c1-100)"
  sleep 15; t=$(( t + 15 ))
done
say "Windows is up, runner heartbeat $(heartbeat_age) s ago"
[ -f "$shared/vm/versions.txt" ] && sed 's/^/   /' "$shared/vm/versions.txt"
