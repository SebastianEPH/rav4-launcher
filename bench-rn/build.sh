#!/bin/bash
# Compila Órbita · React Native (prueba de rendimiento) → ../OrbitaRN.apk
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"; export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
[ -d node_modules ] || npm ci
# medidor común: una sola fuente, la del launcher
mkdir -p android/app/src/main/java/pe/radio/bench
cp ../src/pe/radio/bench/BenchProbe.java android/app/src/main/java/pe/radio/bench/
# firma de la app de prueba (la keystore no va al repo publico)
[ -f android/app/debug.keystore ] || keytool -genkeypair -keystore android/app/debug.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Orbita Bench" >/dev/null 2>&1
(cd android && ./gradlew --no-daemon -q assembleRelease)
cp android/app/build/outputs/apk/release/app-release.apk ../OrbitaRN.apk
echo "APK listo: $(cd .. && pwd)/OrbitaRN.apk"
