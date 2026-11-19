#!/usr/bin/env bash
# Builds Nightfall.apk (Watch Face Format, resources only) with plain SDK tools - no Gradle.
#   ./build.sh            generate art + build + sign  -> out/nightfall.apk
#   ./build.sh install    ...then adb install to the connected watch
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
JAR="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
export JAVA_HOME="${JAVA_HOME:-$(ls -d "$HOME"/.local/share/jdk/jdk-* 2>/dev/null | sort -V | tail -1)}"
export PATH="$JAVA_HOME/bin:$PATH"
KS="${KEYSTORE:-$HOME/.android/debug.keystore}"

python3 gen/gen.py
rm -rf out/build && mkdir -p out/build
"$BT/aapt2" compile --dir res -o out/build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o out/build/unsigned.apk out/build/res.zip
"$BT/zipalign" -f -p 4 out/build/unsigned.apk out/build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out out/nightfall.apk out/build/aligned.apk
"$BT/apksigner" verify out/nightfall.apk
echo "built out/nightfall.apk ($(du -h out/nightfall.apk | cut -f1))"

if [[ "${1:-}" == "install" ]]; then
    adb install -r out/nightfall.apk
    echo "installed - long-press the watch face and pick Nightfall"
fi
