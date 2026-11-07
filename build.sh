#!/bin/bash
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"; export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
BT="$SDK/build-tools/34.0.0"; [ -d "$BT" ] || BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
JAR="$SDK/platforms/android-33/android.jar"; [ -f "$JAR" ] || JAR="$(ls "$SDK"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)"
rm -rf build && mkdir -p build/classes build/dex
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -I $JAR --manifest AndroidManifest.xml -o build/base.apk build/res.zip
javac -source 8 -target 8 -bootclasspath $JAR -d build/classes $(find src -name '*.java')
$BT/d8 --min-api 21 --lib $JAR --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk && (cd build/dex && zip -q ../unsigned.apk classes.dex)
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias diag -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Radio Diag" >/dev/null 2>&1
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --v1-signing-enabled true --v2-signing-enabled true --out RAV4Launcher.apk build/aligned.apk
echo "APK listo: $(pwd)/RAV4Launcher.apk"
