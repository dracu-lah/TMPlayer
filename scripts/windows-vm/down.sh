#!/usr/bin/env bash
# Shuts the Windows VM down (README.md). The installed disk stays, so the next up.sh boots it in a
# minute or two instead of installing again.
#
#   scripts/windows-vm/down.sh             shut Windows down cleanly (up to 2 min) and stop the container
#   scripts/windows-vm/down.sh --remove    also remove the container (the disk in storage/ stays)
#   scripts/windows-vm/down.sh --purge     also delete storage/ and shared/ (about 40 GB): the next
#                                          up.sh downloads and installs Windows again
#   scripts/windows-vm/down.sh --dry-run   print what would happen
set -euo pipefail
# shellcheck source=scripts/windows-vm/common.sh
. "$(dirname "$0")/common.sh"

remove=false purge=false
for a in "$@"; do
  case "$a" in
    --dry-run) dry=true ;;
    --remove) remove=true ;;
    --purge) remove=true; purge=true ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) die "unknown argument $a" ;;
  esac
done

s=$(state)
say "container: $s"
# dockur passes the stop on to Windows as an ACPI shutdown; 120 s is its own advice.
[ "$s" = running ] && x podman stop -t 120 "$name"
[ "$s" != none ] && $remove && x podman rm "$name"
if $purge; then
  du -sh "$storage" 2>/dev/null || true
  x rm -rf "$storage" "$shared"
fi
say "done"
