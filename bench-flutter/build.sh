#!/bin/bash
# Compila Órbita · Flutter (prueba de rendimiento) → ../OrbitaFlutter.apk
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"; export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
# medidor común: una sola fuente, la del launcher
mkdir -p android/app/src/main/java/pe/radio/bench
cp ../src/pe/radio/bench/BenchProbe.java android/app/src/main/java/pe/radio/bench/
flutter build apk --release --target-platform android-arm,android-arm64,android-x64
cp build/app/outputs/flutter-apk/app-release.apk ../OrbitaFlutter.apk
echo "APK listo: $(cd .. && pwd)/OrbitaFlutter.apk"
