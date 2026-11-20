#!/usr/bin/env bash
# Builds Nightfall with plain SDK tools.
#   ./build.sh            generate art + build + sign the watch face  -> out/nightfall.apk
#   ./build.sh install    ...then install it on the watch
#   ./build.sh drawer     generate art + build Nightfall Drawer (Gradle) and install it on the watch
# Installs only ever go to a watch: other adb devices (phones, TVs) are skipped. Set ADB_SERIAL to pick one.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
JAR="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
export JAVA_HOME="${JAVA_HOME:-$(ls -d "$HOME"/.local/share/jdk/jdk-* 2>/dev/null | sort -V | tail -1)}"
export PATH="$JAVA_HOME/bin:$PATH"
KS="${KEYSTORE:-$HOME/.android/debug.keystore}"

watch_serial() {
    if [[ -n "${ADB_SERIAL:-}" ]]; then echo "$ADB_SERIAL"; return; fi
    local s try
    for try in 1 2 3; do
        # watches drop Wi-Fi debugging when idle: nudge offline wireless devices back before checking
        for s in $(adb devices | awk 'NR > 1 && $2 == "offline" { print $1 }'); do
            adb disconnect "$s" >/dev/null 2>&1; timeout 8 adb connect "$s" >/dev/null 2>&1 || true
        done
        for s in $(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'); do
            if [[ "$(adb -s "$s" shell pm has-feature android.hardware.type.watch 2>/dev/null | tr -d '\r')" == "true" ]]; then
                echo "$s"; return
            fi
        done
        sleep 2
    done
    echo "no watch connected over adb (other devices are ignored) - turn on wireless debugging on the watch" >&2
    return 1
}

python3 gen/gen.py

if [[ "${1:-}" == "drawer" ]]; then
    serial="$(watch_serial)"
    (cd drawer && ./gradlew -q :app:assembleRelease)
    adb -s "$serial" install -r drawer/app/build/outputs/apk/release/app-release.apk
    echo "installed Nightfall Drawer on $serial"
    exit 0
fi

rm -rf out/build && mkdir -p out/build
"$BT/aapt2" compile --dir res -o out/build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o out/build/unsigned.apk out/build/res.zip
"$BT/zipalign" -f -p 4 out/build/unsigned.apk out/build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out out/nightfall.apk out/build/aligned.apk
"$BT/apksigner" verify out/nightfall.apk
echo "built out/nightfall.apk ($(du -h out/nightfall.apk | cut -f1))"

if [[ "${1:-}" == "install" ]]; then
    serial="$(watch_serial)"
    adb -s "$serial" install -r out/nightfall.apk
    echo "installed on $serial - long-press the watch face and pick Nightfall"
fi
