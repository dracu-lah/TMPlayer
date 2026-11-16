#!/usr/bin/env bash
# Builds the test-only Android TV wrapper without Gradle: aapt2, javac, d8, zipalign, apksigner.
# Usage: ./build.sh [address]   default https://web.tmplayer.org/?tv=1
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO=/home/dracu/projects/personal/TMPlayer
ADDRESS="${1:-https://web.tmplayer.org/?tv=1}"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-35/android.jar"
B="$HERE/build"
rm -rf "$B" && mkdir -p "$B/res/drawable-xhdpi" "$B/gen" "$B/classes" "$B/dex" "$B/src"

# Icons from the LG/Samsung wrapper art: a 96 px icon and the 320x180 launcher banner.
magick "$REPO/webapp/tv/tizen/icon.png" -resize 96x96 "$B/res/drawable-xhdpi/icon.png"
magick -size 320x180 xc:'#0c0e10' \( "$REPO/webapp/tv/tizen/icon.png" -resize 96x96 \) -gravity west -geometry +24+0 -composite \
  -fill '#f2f4f5' -font /usr/share/fonts/google-noto-vf/NotoSans[wght].ttf -weight 700 -pointsize 30 -gravity west -annotate +134-14 'TMPlayer' \
  -fill '#6ea8ff' -font /usr/share/fonts/google-noto-vf/NotoSans[wght].ttf -pointsize 20 -annotate +136+22 'Web (test)' "$B/res/drawable-xhdpi/banner.png"

sed "s#@ADDRESS@#$ADDRESS#" "$HERE/src/java/org/tmplayer/tvweb/MainActivity.java" > "$B/src/MainActivity.java"

"$BT/aapt2" compile --dir "$B/res" -o "$B/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest "$HERE/src/AndroidManifest.xml" -o "$B/base.apk" --java "$B/gen" "$B/res.zip"
"${JAVA_HOME:-/home/dracu/.gradle/jdks/azul_systems__inc_-21-amd64-linux.2}/bin/javac" --release 11 -nowarn -classpath "$JAR" -d "$B/classes" "$B/src/MainActivity.java" $(find "$B/gen" -name '*.java')
"$BT/d8" --min-api 23 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q "$B/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$HERE/TMPlayer-web-androidtv-test.apk" "$B/aligned.apk"
echo "Address: $ADDRESS"
ls -l "$HERE/TMPlayer-web-androidtv-test.apk"
