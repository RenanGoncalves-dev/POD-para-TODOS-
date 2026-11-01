#!/usr/bin/env bash
# Gera o APK do app Android -> dist/POD.apk
# Requisitos (1ª vez): Java 17+, Android SDK (~/android-sdk) e Gradle 8.9 (~/gradle-8.9)
set -e
cd "$(dirname "$0")/android"
GRADLE="${GRADLE:-$HOME/gradle-8.9/bin/gradle}"
echo "sdk.dir=${ANDROID_HOME:-$HOME/android-sdk}" > local.properties
"$GRADLE" --no-daemon assembleRelease
mkdir -p ../dist
cp app/build/outputs/apk/release/app-release.apk ../dist/POD.apk
echo "APK pronto: $(cd .. && pwd)/dist/POD.apk"

