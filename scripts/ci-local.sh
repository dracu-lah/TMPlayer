#!/usr/bin/env bash
# The checks CI used to run on every push, run here first (docs/agents/CI-LOCAL-PLAN.md).
#
#   scripts/ci-local.sh              what changed against origin/main (what the pre-push hook runs)
#   scripts/ci-local.sh --all        every group, whatever changed
#   scripts/ci-local.sh --list       print the groups that would run and stop
#   scripts/ci-local.sh kotlin web   exactly these groups (style kotlin ios web site)
#
# Groups: style (dash check, scripts), kotlin (core/ui/app tests and the debug APK), ios (Kotlin for
# iOS compile and the release scripts' tests, the Linux half of Apple CI), web (web player type
# check, tests and the Playwright e2e in podman, same image as CI), site (Astro build and checks).
# A pass is recorded in private/logs/ci-local/<sha>.txt. SKIP_LOCAL_CI=1 skips it (the hook says so).
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"

if [ "${SKIP_LOCAL_CI:-}" = 1 ]; then echo "ci-local: skipped (SKIP_LOCAL_CI=1)"; exit 0; fi

PW_VERSION=$(grep -oP '"@playwright/test": "[\^~]?\K[0-9.]+' webapp/package.json | head -1)
PLAYWRIGHT_IMAGE="mcr.microsoft.com/playwright:v${PW_VERSION}-noble"
LOGDIR=private/logs/ci-local
mkdir -p "$LOGDIR"
WORKERS="${CI_LOCAL_WORKERS:-$(( $(nproc) > 8 ? 4 : 2 ))}"
GRADLE=(./gradlew --max-workers="$WORKERS")

want=()
mode=changed
for a in "$@"; do
  case "$a" in
    --all) mode=all ;;
    --list) mode=list ;;
    style|kotlin|ios|web|site) want+=("$a"); mode=named ;;
    *) echo "ci-local: unknown argument $a" >&2; exit 2 ;;
  esac
done

if [ "$mode" != named ]; then
  base=$(git merge-base HEAD origin/main 2>/dev/null || true)
  if [ "$mode" = all ] || [ -z "$base" ]; then
    files=ALL
  else
    files=$( { git diff --name-only "$base" HEAD; git diff --name-only; git ls-files -o --exclude-standard; } | sort -u )
  fi
  has() { [ "$files" = ALL ] || printf '%s\n' "$files" | grep -qE "$1"; }
  want+=(style)
  has '^(core|ui|app|desktop|ios|gradle)/|^(build|settings)\.gradle\.kts$|^gradle\.properties$' && want+=(kotlin)
  has '^(ios|iosApp|altstore)/|^(core|ui)/src/|^app/src/main/(java|res)/|^scripts/ios-setup\.sh$|^gradle/' && want+=(ios)
  has '^(webapp|core)/|^gradle/' && want+=(web)
  has '^site/|^scripts/site-' && want+=(site)
fi
if [ "$mode" = list ]; then printf '%s\n' "${want[@]}"; exit 0; fi

passed=(); failed=()
run() { # name, command...
  local name=$1; shift
  echo; echo "=== ci-local: $name"
  local t=$SECONDS
  if "$@"; then passed+=("$name ($((SECONDS - t))s)"); else failed+=("$name"); fi
}

g_style() {
  # private/ is the maintainer's own notes; GitHub's Style job does not flag it, so neither does this.
  if git ls-files -z -- . ':!private' | xargs -0 grep -InIP '[\x{2012}-\x{2015}]'; then echo "Found an em or en dash."; return 1; fi
  bash -n scripts/ios-setup.sh && bash -n iosApp/release/build-ipa.sh
}

g_kotlin() {
  "${GRADLE[@]}" :core:jvmTest :ui:jvmTest :app:testDebugUnitTest :app:assembleDebug
}

g_ios() {
  python3 -I -m unittest discover -s iosApp/release || return 1
  python3 -I iosApp/release/altstore_source.py check --project iosApp/project.yml || return 1
  # jsonschema is only needed for the schema check; skip it when it is not installed here.
  if python3 -c 'import jsonschema' 2>/dev/null; then
    python3 -I iosApp/release/validate_schema.py altstore/source.json altstore/source.schema.json || return 1
  else
    echo "ci-local: python jsonschema missing, the schema check is left to the nightly run"
  fi
  # The iOS copies and the three klibs compiled for a device (nothing links: that needs a Mac).
  "${GRADLE[@]}" -Ptmplayer.ios=true \
    :core:appleSourcesForIos :ui:appleSourcesForIos :ios:androidScreensForIos :ios:androidPlayerForIos \
    :core:compileKotlinIosArm64 :ui:compileKotlinIosArm64 :ios:compileKotlinIosArm64
}

g_web() {
  command -v podman >/dev/null || { echo "podman is needed for the e2e run"; return 1; }
  "${GRADLE[@]}" -q :core:webRules || return 1
  local pm; pm=$(grep -oP '"packageManager": "pnpm@\K[0-9.]+' webapp/package.json)
  # Install, type check and the node tests run in the same image as the e2e, with node_modules in a
  # volume so the host's copy is not touched. HOME is set for the browsers (the CI image needs it too).
  podman run --rm --ipc=host -e HOME=/root -e CI=1 \
    -v "$PWD":/work:z -v tmplayer-webapp-node-modules:/work/webapp/node_modules \
    -v tmplayer-pnpm-store:/root/.local/share/pnpm \
    -w /work "$PLAYWRIGHT_IMAGE" bash -euc "
      npm install -g pnpm@$pm >/dev/null
      pnpm -C webapp install --frozen-lockfile
      pnpm -C webapp check
      pnpm -C webapp test:tmdb
      pnpm -C webapp exec playwright test --project=chromium --project=webkit
    "
}

g_site() {
  pnpm -C site install --frozen-lockfile && pnpm -C site check && pnpm -C site build \
    && python3 scripts/site-i18n-check.py && python3 scripts/site-links.py
}

for g in "${want[@]}"; do run "$g" "g_$g"; done

echo; echo "=== ci-local summary"
for p in "${passed[@]:-}"; do [ -n "$p" ] && echo "  ok      $p"; done
for f in "${failed[@]:-}"; do [ -n "$f" ] && echo "  FAILED  $f"; done
if [ ${#failed[@]} -eq 0 ]; then
  printf '%s groups: %s\n' "$(date -Is)" "${want[*]}" >> "$LOGDIR/$(git rev-parse HEAD).txt"
  exit 0
fi
echo "ci-local: fix the failures, or push anyway with SKIP_LOCAL_CI=1 (set before the push command)"
exit 1
