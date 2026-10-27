#!/usr/bin/env bash
# Builds the two thin "hosted" TV wrappers for the TMPlayer web player:
#   out/TMPlayer-tv.ipk  (LG webOS)
#   out/TMPlayer-tv.wgt  (Samsung Tizen, unsigned: the install tool signs it)
# Usage: ./package.sh [address]      default https://web.tmplayer.org/
# TV_VERSION=x.y.z sets the packages' version (the release job passes the tag's), else the Android default.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
ADDRESS="${1:-https://web.tmplayer.org/}"
OUT="$HERE/out"
EN_JSON="$ROOT/core/src/commonMain/resources/i18n/en.json"

case "$ADDRESS" in
  http://*|https://*) ;;
  *) echo "The address must start with http:// or https://" >&2; exit 1 ;;
esac

# The app's version (x.y.z) from the Android build's default; webOS and Tizen want exactly three numbers.
VERSION="$(sed -n 's/.*findProperty("tmVersionName") as String?) ?: "\([0-9]*\.[0-9]*\.[0-9]*\).*/\1/p' "$ROOT/app/build.gradle.kts" | head -n 1)"
VERSION="${TV_VERSION:-${VERSION:-0.0.1}}"

rm -rf "$OUT/webos" "$OUT/tizen" "$OUT"/*.ipk "$OUT"/*.wgt
mkdir -p "$OUT/webos" "$OUT/tizen" "$OUT/.tools"

# Fill the shell page: words from en.json (HTML escaped), address from the argument.
ADDRESS="$ADDRESS" EN_JSON="$EN_JSON" SHELL_IN="$HERE/shell/index.html" python3 -I - <<'PY' > "$OUT/index.html"
import html, json, os
en = json.load(open(os.environ["EN_JSON"], encoding="utf-8"))
def key(k):
    node = en
    for part in k.split("."):
        node = node[part]
    return node
address = os.environ["ADDRESS"]
title = html.escape(key("web.tv_unreachable_title"))
body = html.escape(key("web.tv_unreachable_body")).replace("{address}", "<b>" + html.escape(address) + "</b>")
retry = html.escape(key("common.try_again"))
address_js = json.dumps(address).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
page = open(os.environ["SHELL_IN"], encoding="utf-8").read()
for a, b in (("{{TITLE}}", title), ("{{BODY}}", body), ("{{RETRY}}", retry), ("{{ADDRESS_JS}}", address_js)):
    page = page.replace(a, b)
print(page, end="")
PY
if grep -q '{{' "$OUT/index.html"; then echo "Unfilled placeholder in index.html" >&2; exit 1; fi

# Address pieces for the Tizen config: origin (scheme://host:port) and host.
ORIGIN="$(printf '%s' "$ADDRESS" | sed -E 's#^([a-z]+://[^/?#]+).*#\1#')"
HOST="$(printf '%s' "$ORIGIN" | sed -E 's#^[a-z]+://##; s#:[0-9]+$##')"

# LG webOS
cp "$OUT/index.html" "$HERE/webos/icon.png" "$HERE/webos/largeIcon.png" "$OUT/webos/"
sed "s#{{VERSION}}#$VERSION#" "$HERE/webos/appinfo.json" > "$OUT/webos/appinfo.json"
(cd "$OUT/.tools" && npx --yes --package @webos-tools/cli ares-package --outdir "$OUT" "$OUT/webos" >/dev/null)
mv "$OUT/org.tmplayer.tv_${VERSION}_all.ipk" "$OUT/TMPlayer-tv.ipk"

# Samsung Tizen (unsigned)
cp "$OUT/index.html" "$HERE/tizen/icon.png" "$OUT/tizen/"
sed "s#{{VERSION}}#$VERSION#; s#{{ORIGIN}}#$ORIGIN#g; s#{{HOST}}#$HOST#" "$HERE/tizen/config.xml" > "$OUT/tizen/config.xml"
rm -f "$OUT/TMPlayer-tv.wgt"
(cd "$OUT/tizen" && zip -q -X -9 "$OUT/TMPlayer-tv.wgt" config.xml index.html icon.png)

rm -f "$OUT/index.html"
echo "Address: $ADDRESS   Version: $VERSION"
ls -l "$OUT/TMPlayer-tv.ipk" "$OUT/TMPlayer-tv.wgt"
