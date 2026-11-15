#!/bin/bash
# Compila Órbita Launcher con Gradle → OrbitaLauncher.apk (+ la ayudante OrbitaVolumen.apk).
# Incluye las interfaces Nativa, Web, Compose, React Native (rn/) y Flutter.
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"; export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
[ -d rn/node_modules ] || (cd rn && npm ci)
ln -sfn rn/node_modules node_modules          # las librerías de RN buscan node_modules en la raíz
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias diag -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Radio Diag" >/dev/null 2>&1
./gradlew --no-daemon -q assembleRelease ${VERSION_CODE:+-PversionCode=$VERSION_CODE} ${VERSION_NAME:+-PversionName=$VERSION_NAME}
cp app/build/outputs/apk/release/app-release.apk OrbitaLauncher.apk
echo "APK listo: $(pwd)/OrbitaLauncher.apk"
[ -d helper ] && ./helper/build.sh "$(pwd)/${KEYSTORE_PATH:-debug.keystore}" >/dev/null && echo "Ayudante lista: $(pwd)/OrbitaVolumen.apk"
