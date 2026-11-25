#!/usr/bin/env bash
# The checks CI used to run on every push, run here first (docs/agents/CI-LOCAL-PLAN.md).
#
#   scripts/ci-local.sh              what changed against origin/main (what the pre-push hook runs)
#   scripts/ci-local.sh --all        every group, whatever changed, and the full versions (WebKit, debug APK)
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
# The build needs a JDK 21 with a compiler (docs/BUILDING.md); the system's JDK 25 is a runtime only.
if [ ! -x "${JAVA_HOME:-/nonexistent}/bin/javac" ] || ! "$JAVA_HOME/bin/javac" -version 2>&1 | grep -q ' 21\.'; then
  for j in "$HOME"/.jdks/jdk-21* /usr/lib/jvm/java-21*; do
    [ -x "$j/bin/javac" ] && { export JAVA_HOME="$j"; break; }
  done
fi
WORKERS="${CI_LOCAL_WORKERS:-$(( $(nproc) > 8 ? 4 : 2 ))}"
GRADLE=(./gradlew --max-workers="$WORKERS")

want=()
KTESTS=()
mode=changed
FULL=${CI_LOCAL_FULL:-0}   # 1: also WebKit e2e and the debug APK (the pre-push hook leaves both to the nightly run)
for a in "$@"; do
  case "$a" in
    --all) mode=all; FULL=1 ;;
    --full) FULL=1 ;;
    --list) mode=list ;;
    style|kotlin|ios|web|site|linux) want+=("$a"); mode=named ;;
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
  # A here-string, not a pipe: with pipefail, grep -q closing early would kill printf (SIGPIPE) and
  # report a match as no match.
  has() { [ "$files" = ALL ] || grep -qE "$1" <<<"$files"; }
  want+=(style)
  has '^(core|ui|app|desktop|ios|gradle|kotlin-js-store)/|^(build|settings)\.gradle\.kts$|^gradle\.properties$|^gradlew$' && want+=(kotlin)
  # The hook tests the module that changed, not all three (the whole set took 20 minutes); the
  # nightly run and --all cover the rest. A change to the build itself tests everything.
  if [ "$FULL" != 1 ]; then
    KTESTS=()
    if has '^gradle/|^(build|settings)\.gradle\.kts$|^gradle\.properties$|^gradlew$'; then
      KTESTS=(:core:jvmTest :ui:jvmTest :app:testDebugUnitTest)
    else
      has '^core/' && KTESTS+=(:core:jvmTest)
      has '^ui/' && KTESTS+=(:ui:jvmTest)
      # app and desktop tests also exercise the shared ui and core code (CI found the app's focus
      # tests and the desktop's screen invariants red after ui-only pushes, 2026-11-25).
      has '^(app|ui|core)/' && KTESTS+=(:app:testDebugUnitTest)
      has '^(desktop|ui|core)/' && KTESTS+=(:desktop:test)
    fi
  fi
  has '^(ios|iosApp|altstore)/|^(core|ui)/src/|^app/src/main/(java|res)/|^scripts/ios-setup\.sh$|^gradle/|^(build|settings)\.gradle\.kts$|^gradle\.properties$' && want+=(ios)
  has '^(webapp|core)/|^gradle/|^(build|settings)\.gradle\.kts$|^gradle\.properties$' && want+=(web)
  has '^site/|^scripts/site-' && want+=(site)
fi
if [ "$mode" = list ]; then printf '%s\n' "${want[@]}"; exit 0; fi

# A pass already recorded for this exact commit (same groups, no tracked change since) is not run
# again. This is what lets `scripts/ci-local.sh && git push` work: git holds the SSH connection to
# GitHub open while the pre-push hook runs, GitHub drops it after a few idle minutes ("Connection
# to github.com closed by remote host"), so a long test run inside the hook loses the push.
if [ "$mode" != list ] && [ -f "$LOGDIR/$(git rev-parse HEAD).txt" ] \
   && git diff --quiet HEAD -- . ':!design' ':!private' \
   && grep -qxF "groups: ${want[*]}" <(cut -d' ' -f2- "$LOGDIR/$(git rev-parse HEAD).txt"); then
  echo "ci-local: already passed for $(git rev-parse --short HEAD) (${want[*]}), not run again"
  exit 0
fi

passed=(); failed=()
run() { # name, command...
  local name=$1; shift
  echo; echo "=== ci-local: $name"
  local t=$SECONDS
  if "$@"; then passed+=("$name ($((SECONDS - t))s)"); else failed+=("$name"); fi
}

g_style() {
  # private/ is the maintainer's own notes; GitHub's Style job does not flag it, so neither does this.
  # git grep reads every tracked file in one process (no xargs batches, where one batch without a
  # match hides a hit in another); 0 means a dash was found, 1 none, anything else is an error.
  LC_ALL=C.UTF-8 git grep -InIP '[\x{2012}-\x{2015}]' -- . ':!private'
  case $? in
    0) echo "Found an em or en dash."; return 1 ;;
    1) ;;
    *) echo "The dash check could not run."; return 1 ;;
  esac
  bash -n scripts/ios-setup.sh && bash -n iosApp/release/build-ipa.sh
}

g_kotlin() {
  local tests=(:core:jvmTest :ui:jvmTest :app:testDebugUnitTest :desktop:test)
  [ "$mode" = changed ] && [ "$FULL" != 1 ] && tests=("${KTESTS[@]}")
  if [ ${#tests[@]} -eq 0 ]; then echo "ci-local: no module with unit tests changed (iOS only), left to the nightly run"; return 0; fi
  "${GRADLE[@]}" "${tests[@]}" || return 1
  [ "$FULL" = 1 ] && { "${GRADLE[@]}" :app:assembleDebug || return 1; }
  return 0
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
  local projects="--project=chromium"; [ "$FULL" = 1 ] && projects="--project=chromium --project=webkit --project=webkit-iphone"
  # sync.spec.ts rewrites the tracked design shots in design/web-sync; put them back unless they
  # had edits of their own before the run.
  local shots_clean=0; git diff --quiet -- design/web-sync && shots_clean=1
  # Install, type check and the node tests run in the same image as the e2e, with node_modules in a
  # volume so the host's copy is not touched. HOME is set for the browsers (the CI image needs it too).
  local rc=0
  podman run --rm --ipc=host -e HOME=/root -e CI=1 \
    -v "$PWD":/work:z -v tmplayer-webapp-node-modules:/work/webapp/node_modules \
    -v tmplayer-pnpm-store:/root/.local/share/pnpm \
    -w /work "$PLAYWRIGHT_IMAGE" bash -euc "
      npm install -g pnpm@$pm >/dev/null
      pnpm -C webapp install --frozen-lockfile --store-dir /root/.local/share/pnpm/store
      pnpm -C webapp check
      pnpm -C webapp test:tmdb
      pnpm -C webapp test:screens
      pnpm -C webapp exec playwright test $projects
    " || rc=$?
  [ "$shots_clean" = 1 ] && git checkout -- design/web-sync
  return $rc
}

g_site() {
  pnpm -C site install --frozen-lockfile && pnpm -C site check && pnpm -C site build \
    && python3 scripts/site-i18n-check.py && python3 scripts/site-links.py
}

# Opt-in only (scripts/ci-local.sh linux), never in the changed-paths list: CI's Desktop (Linux)
# job, natives from the recipe cache, then the packages and self-tests into dist/ (P3).
g_linux() {
  scripts/linux-local.sh
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
