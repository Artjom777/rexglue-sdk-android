#!/bin/bash
# ==============================================================================
# ReXGlue SDK - Android Game APK Packaging Script
# Packages recompiled Xbox 360 game libraries into a signed Android APK
# Usage: ./tools/android/build_apk.sh <path_to_libmain.so> [apk_name]
# ==============================================================================
set -e

if [ -f "$HOME/android-tools/env.sh" ]; then
  source "$HOME/android-tools/env.sh"
fi

SDK_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APP_DIR="$SDK_ROOT/tools/android/app"
OUT_DIR="${OUT_DIR:-$SDK_ROOT/out/apk}"
SDK_LIB="${SDK_LIB:-$SDK_ROOT/out/install/android-poco/lib}"
if [ ! -d "$SDK_LIB" ]; then
  SDK_LIB="$SDK_ROOT/out/install/android-arm64/lib"
fi
SDL_JAVA="${SDL_JAVA:-$SDK_ROOT/thirdparty/sdl3/android-project/app/src/main/java}"

LIB_MAIN="$1"
NAME="${2:-rexglue-game}"

if [ -z "$LIB_MAIN" ] || [ ! -f "$LIB_MAIN" ]; then
  echo "Usage: $0 <path_to_libmain.so> [apk_name]"
  exit 1
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "Error: ANDROID_HOME not set!"
  exit 1
fi

BT=$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -mindepth 1 2>/dev/null | sort -V | tail -n 1)
JAR=$(find "$ANDROID_HOME/platforms" -name "android.jar" 2>/dev/null | sort -V | tail -n 1)
NDK_LIBCXX=$(find "$ANDROID_NDK_HOME" -name "libc++_shared.so" 2>/dev/null | grep "aarch64" | head -n 1)
LLVM_STRIP=$(find "$ANDROID_NDK_HOME" -name "llvm-strip" 2>/dev/null | head -n 1)
[ -z "$LLVM_STRIP" ] && LLVM_STRIP="llvm-strip"

KEY="${KEY:-$HOME/android-tools/debug.keystore}"
mkdir -p "$(dirname "$KEY")" "$OUT_DIR"
cd "$OUT_DIR"
rm -rf classes stage && mkdir -p classes stage/lib/arm64-v8a

echo "==> Compiling Java sources with javac..."
javac --release 17 -nowarn -classpath "$JAR" -d classes \
  $(find "$SDL_JAVA" "$APP_DIR/java" -name '*.java' 2>/dev/null) 2>&1 | grep -v "^Note:" || true

echo "==> Converting classes to DEX with d8..."
"$BT/d8" --release --min-api 28 --lib "$JAR" --output stage $(find classes -name '*.class')

echo "==> Linking APK with aapt2..."
"$BT/aapt2" link -o base.apk --manifest "$APP_DIR/AndroidManifest.xml" -I "$JAR" \
  --min-sdk-version 28 --target-sdk-version 34

echo "==> Copying shared libraries (with 16KB alignment)..."
cp "$LIB_MAIN" stage/lib/arm64-v8a/libmain.so
if [ -f "$SDK_LIB/librexruntime.so" ]; then
  cp "$SDK_LIB/librexruntime.so" stage/lib/arm64-v8a/
fi
if [ -n "$NDK_LIBCXX" ] && [ -f "$NDK_LIBCXX" ]; then
  cp "$NDK_LIBCXX" stage/lib/arm64-v8a/
fi

"$LLVM_STRIP" --strip-debug stage/lib/arm64-v8a/*.so 2>/dev/null || true

cp base.apk unaligned.apk
(cd stage && zip -q -X ../unaligned.apk classes.dex && zip -q -X -0 -r ../unaligned.apk lib)

echo "==> 16KB Page Aligning APK with zipalign..."
"$BT/zipalign" -f -P 16 4 unaligned.apk aligned.apk

if [ ! -f "$KEY" ]; then
  echo "==> Generating debug signing keystore..."
  keytool -genkeypair -keystore "$KEY" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

echo "==> Signing APK with apksigner..."
"$BT/apksigner" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android \
  --out "$NAME.apk" aligned.apk

rm -f unaligned.apk aligned.apk base.apk
echo "=================================================="
echo "Successfully built APK: $OUT_DIR/$NAME.apk"
ls -lh "$NAME.apk"
echo "=================================================="
