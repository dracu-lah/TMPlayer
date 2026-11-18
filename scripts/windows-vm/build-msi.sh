#!/usr/bin/env bash
# Builds TMPlayer-<v>-windows-x64.msi in the Windows VM and runs CI's install checks on it
# (.github/actions/desktop-windows): the older MSI first, the upgrade to this one, exactly one
# product left, the installed app's --self-test (selftest.ps1), then an uninstall that must leave
# nothing. Run up.sh first. The MSI lands in dist/, the msiexec logs in dist/windows-logs/.
# Pre-checks only: a released MSI still comes from GitHub's runner (README.md).
#
#   scripts/windows-vm/build-msi.sh                    1.0.1-local.0 over 1.0.0, as CI does
#   scripts/windows-vm/build-msi.sh --version 1.2.3-beta.1 --upgrade-from ''
#   scripts/windows-vm/build-msi.sh --no-tests         skip :desktop:test
#   scripts/windows-vm/build-msi.sh --dry-run          print the job and the copy, run nothing
#
# The working tree is copied as it is (tracked and untracked files git does not ignore, no
# local.properties), so the MSI carries no credentials. BUILD_TIMEOUT_MIN (120) caps the wait.
set -euo pipefail
# shellcheck source=scripts/windows-vm/common.sh
. "$(dirname "$0")/common.sh"

version=1.0.1-local.0 upgrade_from=1.0.0 tests=true workers="${VM_GRADLE_WORKERS:-4}"
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) dry=true ;;
    --version) version="${2:?}"; shift ;;
    --upgrade-from) upgrade_from="${2?}"; shift ;;
    --no-tests) tests=false ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) die "unknown argument $1" ;;
  esac
  shift
done
for v in "$version" ${upgrade_from:+"$upgrade_from"}; do
  [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || die "$v is not x.y.z or x.y.z-suffix"
done

if ! $dry; then
  [ "$(state)" = running ] || die "the VM is not running (scripts/windows-vm/up.sh)"
  [ "$(heartbeat_age)" -lt 120 ] || die "the runner in the VM has not answered for $(heartbeat_age) s (up.sh waits for it)"
fi

# --- the source -------------------------------------------------------------------------------
say "copy the working tree to $shared/src"
cd "$repo" || die "no $repo"
if $dry; then
  echo "+ git ls-files -co --exclude-standard | tar -> $shared/src ($(git ls-files -co --exclude-standard | wc -l) files)"
else
  rm -rf "$shared/src.new"; mkdir -p "$shared/src.new"
  git ls-files -z -co --exclude-standard | grep -zv '^local\.properties$' \
    | tar --null -T - --ignore-failed-read -cf - | tar -C "$shared/src.new" -xf -
  rm -rf "$shared/src"; mv "$shared/src.new" "$shared/src"
fi

# --- the job ----------------------------------------------------------------------------------
id="$(date +%Y%m%d-%H%M%S)-msi"
job="$shared/jobs/$id.ps1"
header="\$Version = '$version'; \$UpgradeFrom = '$upgrade_from'; \$Tests = \$$tests; \$Workers = $workers"
if $dry; then
  echo "+ write $job:"; echo "    $header"; echo "    + scripts/windows-vm/build-msi.ps1"
  say "dry run done"; exit 0
fi
rm -rf "$shared/out"
{ printf '%s\r\n' "$header"; cat "$here/build-msi.ps1"; } > "$job.part"
mv "$job.part" "$job"
say "job $id queued; following $shared/jobs/$id.log"

limit=$(( ${BUILD_TIMEOUT_MIN:-120} * 60 )); t=0 shown=0
until [ -f "$shared/jobs/$id.exit" ]; do
  if [ -f "$shared/jobs/$id.log" ]; then
    # Windows PowerShell 5.1 writes the redirected log as UTF-16.
    lines=$(iconv -f UTF-16 -t UTF-8 "$shared/jobs/$id.log" 2>/dev/null | tr -d '\r' || true)
    n=$(printf '%s\n' "$lines" | wc -l)
    [ "$n" -gt "$shown" ] && printf '%s\n' "$lines" | tail -n +"$(( shown + 1 ))" | sed 's/^/   /'
    shown=$n
  fi
  [ "$(heartbeat_age)" -lt 300 ] || die "the runner stopped answering while job $id ran"
  [ "$t" -lt "$limit" ] || die "job $id still running after $(( limit / 60 )) min"
  sleep 10; t=$(( t + 10 ))
done
code=$(tr -dc '0-9' < "$shared/jobs/$id.exit")
iconv -f UTF-16 -t UTF-8 "$shared/jobs/$id.log" 2>/dev/null | tr -d '\r' | tail -n +"$(( shown + 1 ))" | sed 's/^/   /' || true

if [ "$code" = 0 ]; then
  mkdir -p dist/windows-logs
  cp "$shared/out/TMPlayer-$version-windows-x64.msi" dist/
  cp "$shared/out/"*.log dist/windows-logs/ 2>/dev/null || true
  say "passed: dist/TMPlayer-$version-windows-x64.msi"
else
  [ "$code" = 99 ] && echo "windows-vm: the VM restarted during the job" >&2
  die "job $id failed (exit $code); log: $shared/jobs/$id.log"
fi
