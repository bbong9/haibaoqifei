#!/usr/bin/env bash
set -euo pipefail

SEAL_REPO="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SEAL_WORKSPACE="$(cd -- "$SEAL_REPO/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$SEAL_WORKSPACE/.toolchain/jdk-25.0.4.1+1/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$SEAL_WORKSPACE/.toolchain/android-sdk}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$SEAL_WORKSPACE/.toolchain/android-user}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$SEAL_WORKSPACE/.toolchain/gradle}"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$SEAL_REPO"

case "${1:-check}" in
  check)
    unset DIPLAY_AUTH_ASSETS_DIR
    python3 scripts/check_public_tree.py
    ./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
      :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
      :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug --max-workers=4 --stacktrace
    ;;
  package)
    SEAL_LOCAL="$SEAL_WORKSPACE/.local"
    umask 077
    mkdir -p "$SEAL_LOCAL" "$SEAL_REPO/build/seal"
    export DIPLAY_AUTH_ASSETS_DIR="${DIPLAY_AUTH_ASSETS_DIR:-$SEAL_LOCAL/runtime-assets}"
    test -s "$DIPLAY_AUTH_ASSETS_DIR/offline-mfi/identity.pk8"
    test -s "$DIPLAY_AUTH_ASSETS_DIR/offline-mfi/certificate.p7b"
    export ANDROID_KEYSTORE_PATH="$SEAL_LOCAL/seal-signing.jks"
    export ANDROID_KEY_ALIAS=seal-local
    if [[ ! -f "$ANDROID_KEYSTORE_PATH" ]]; then
      if [[ ! -s "$SEAL_LOCAL/signing-password" ]]; then
        openssl rand -hex -out "$SEAL_LOCAL/signing-password" 32
      fi
      keytool -genkeypair -keystore "$ANDROID_KEYSTORE_PATH" -storetype JKS \
        -storepass:file "$SEAL_LOCAL/signing-password" -keypass:file "$SEAL_LOCAL/signing-password" \
        -alias "$ANDROID_KEY_ALIAS" -keyalg RSA -keysize 3072 -validity 10000 \
        -dname "CN=DiPlay Seal Local, OU=Personal Development" -noprompt
    fi
    test -s "$SEAL_LOCAL/signing-password"
    ANDROID_KEYSTORE_PASSWORD="$(<"$SEAL_LOCAL/signing-password")"
    ANDROID_KEY_PASSWORD="$ANDROID_KEYSTORE_PASSWORD"
    export ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD
    ./gradlew :mobile:verifyStandaloneAuthentication :mobile:lintRelease :mobile:assembleRelease --max-workers=4 --stacktrace
    SEAL_APK="$SEAL_REPO/build/seal/DiPlay-Seal-0.2.21-seal.9.apk"
    cp mobile/build/outputs/apk/release/mobile-release.apk "$SEAL_APK"
    "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose "$SEAL_APK"
    shasum -a 256 "$SEAL_APK"
    ;;
  *)
    echo 'Usage: bash scripts/build-seal-local.sh [check|package]' >&2
    exit 2
    ;;
esac
