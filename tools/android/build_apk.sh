#!/bin/bash
# ==============================================================================
# ReXGlue SDK - Android APK Packaging Script
# Builds ReXGlue Studio APK or standalone recompiled Xbox 360 game APKs
# Usage:
#   ./tools/android/build_apk.sh studio [poco|arm64]
#   ./tools/android/build_apk.sh game <path_to_libmain.so> [game_name] [assets_dir_or_iso_path]
# ==============================================================================
set -e

if [ -f "$HOME/android-tools/env.sh" ]; then
  source "$HOME/android-tools/env.sh"
fi

SDK_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APP_DIR="$SDK_ROOT/tools/android/app"
OUT_DIR="${OUT_DIR:-$SDK_ROOT/out/dist}"
SDL_JAVA="${SDL_JAVA:-$SDK_ROOT/thirdparty/sdl3/android-project/app/src/main/java}"

MODE="${1:-studio}"
TARGET_DEVICE="${2:-poco}"

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "Error: ANDROID_HOME not set!"
  exit 1
fi

BT=$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -mindepth 1 2>/dev/null | sort -V | tail -n 1)
JAR=$(find "$ANDROID_HOME/platforms" -name "android.jar" 2>/dev/null | sort -V | tail -n 1)

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -d "$ANDROID_HOME/ndk" ]; then
    ANDROID_NDK_HOME=$(find "$ANDROID_HOME/ndk" -maxdepth 1 -mindepth 1 2>/dev/null | sort -V | tail -n 1)
  fi
fi

NDK_LIBCXX=$(find "$ANDROID_NDK_HOME" -name "libc++_shared.so" 2>/dev/null | grep "aarch64" | head -n 1)
LLVM_STRIP=$(find "$ANDROID_NDK_HOME" -name "llvm-strip" 2>/dev/null | head -n 1)
[ -z "$LLVM_STRIP" ] && LLVM_STRIP="llvm-strip"

KEY="${KEY:-$HOME/android-tools/debug.keystore}"
mkdir -p "$(dirname "$KEY")" "$OUT_DIR"

if [ "$TARGET_DEVICE" = "poco" ] || [ "${POCO:-0}" = "1" ]; then
  SDK_INSTALL="$SDK_ROOT/out/install/android-poco"
  APK_SUFFIX="POCO-X7-Pro"
else
  SDK_INSTALL="$SDK_ROOT/out/install/android-arm64"
  APK_SUFFIX="Android-arm64"
fi

# Fallback if specific directory doesn't exist yet
if [ ! -d "$SDK_INSTALL" ]; then
  if [ -d "$SDK_ROOT/out/install/android-poco" ]; then
    SDK_INSTALL="$SDK_ROOT/out/install/android-poco"
  else
    SDK_INSTALL="$SDK_ROOT/out/install/android-arm64"
  fi
fi

SDK_LIB="$SDK_INSTALL/lib"
SDK_BIN="$SDK_INSTALL/bin"

STAGE_DIR="$SDK_ROOT/out/apk-stage-$MODE"
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR/classes" "$STAGE_DIR/stage/lib/arm64-v8a" "$STAGE_DIR/compiled_res" "$STAGE_DIR/gen"

echo "=================================================="
echo "ReXGlue APK Builder"
echo "  Mode:          $MODE"
echo "  Target Device: $APK_SUFFIX"
echo "  SDK Libs:      $SDK_LIB"
echo "  Build Tools:   $BT"
echo "=================================================="

# 1. Compile Resources with aapt2
echo "==> [1/6] Compiling Android XML resources..."
if [ -d "$APP_DIR/res" ]; then
  "$BT/aapt2" compile --dir "$APP_DIR/res" -o "$STAGE_DIR/compiled_res.zip"
  RES_PARAM="$STAGE_DIR/compiled_res.zip"
else
  RES_PARAM=""
fi

# 2. Link APK skeleton with aapt2
echo "==> [2/6] Linking Android package with aapt2..."
MANIFEST="$APP_DIR/AndroidManifest.xml"
if [ "$MODE" = "runner" ]; then
  MANIFEST="$APP_DIR/AndroidManifest-game.xml"
fi

"$BT/aapt2" link -o "$STAGE_DIR/base.apk" \
  --manifest "$MANIFEST" \
  -I "$JAR" \
  --java "$STAGE_DIR/gen" \
  $RES_PARAM \
  --auto-add-overlay \
  --min-sdk-version 28 \
  --target-sdk-version 34

# 3. Compile Java with javac
echo "==> [3/6] Compiling Java source files..."
JAVA_SOURCES=$(find "$APP_DIR/java" "$STAGE_DIR/gen" -name '*.java')
if [ -d "$SDL_JAVA" ]; then
  JAVA_SOURCES="$JAVA_SOURCES $(find "$SDL_JAVA" -name '*.java' 2>/dev/null)"
fi

javac --release 17 -nowarn -classpath "$JAR" -d "$STAGE_DIR/classes" \
  $JAVA_SOURCES 2>&1 | grep -v "^Note:" || true

# 4. Convert classes to DEX with d8
echo "==> [4/6] Creating classes.dex with d8..."
CLASS_FILES=$(find "$STAGE_DIR/classes" -name '*.class')
"$BT/d8" --release --min-api 28 --lib "$JAR" --output "$STAGE_DIR/stage" $CLASS_FILES

# 5. Copy Native Libraries (16KB Page Aligned)
echo "==> [5/6] Bundling native libraries into APK..."
LIB_TARGET="$STAGE_DIR/stage/lib/arm64-v8a"

if [ -f "$NDK_LIBCXX" ]; then
  cp "$NDK_LIBCXX" "$LIB_TARGET/"
fi

if [ -f "$SDK_LIB/librexruntime.so" ]; then
  cp "$SDK_LIB/librexruntime.so" "$LIB_TARGET/"
fi

if [ -f "$SDK_LIB/librexglue_jni.so" ]; then
  cp "$SDK_LIB/librexglue_jni.so" "$LIB_TARGET/"
fi

# Bundle the rexglue CLI executable as librexglue_cli.so for Studio
if [ "$MODE" != "runner" ] && [ -f "$SDK_BIN/rexglue" ]; then
  cp "$SDK_BIN/rexglue" "$LIB_TARGET/librexglue_cli.so"
fi

if [ "$MODE" = "runner" ]; then
  APK_NAME="rexglue-game-runner-${APK_SUFFIX}.apk"
elif [ "$MODE" = "game" ] || [ "$MODE" = "game-iso" ] || [ "$MODE" = "game-standalone" ]; then
  GAME_LIB="$2"
  GAME_NAME="${3:-rexglue-game}"
  GAME_DATA="${4:-}"

  if [ -f "$GAME_LIB" ]; then
    cp "$GAME_LIB" "$LIB_TARGET/libmain.so"
  fi

  # Handle Mode 1 (ISO mount) vs Mode 2 (Standalone assets)
  mkdir -p "$STAGE_DIR/stage/assets"
  if [ -n "$GAME_DATA" ]; then
    if [ -d "$GAME_DATA" ]; then
      echo "==> Bundling standalone game assets from: $GAME_DATA"
      cp -r "$GAME_DATA" "$STAGE_DIR/stage/assets/game"
      cat <<EOF > "$STAGE_DIR/stage/assets/rexglue.cfg"
mode=standalone
project=$GAME_NAME
EOF
      APK_NAME="${GAME_NAME}-standalone-${APK_SUFFIX}.apk"
    elif [ -f "$GAME_DATA" ]; then
      echo "==> Configuring ISO-mounted port for disc image: $GAME_DATA"
      cat <<EOF > "$STAGE_DIR/stage/assets/rexglue.cfg"
mode=iso
iso_path=$GAME_DATA
project=$GAME_NAME
EOF
      APK_NAME="${GAME_NAME}-iso-${APK_SUFFIX}.apk"
    fi
  else
    APK_NAME="${GAME_NAME}-${APK_SUFFIX}.apk"
  fi
else
  APK_NAME="ReXGlue-Studio-${APK_SUFFIX}.apk"
  RUNNER_APK="$OUT_DIR/rexglue-game-runner-${APK_SUFFIX}.apk"
  if [ ! -f "$RUNNER_APK" ]; then
    echo "==> Building embedded game runner APK..."
    "$0" runner "$TARGET_DEVICE"
  fi
  if [ -f "$RUNNER_APK" ]; then
    mkdir -p "$STAGE_DIR/stage/assets"
    cp "$RUNNER_APK" "$STAGE_DIR/stage/assets/game_runner.apk"
    echo "==> Embedded game_runner.apk into Studio assets ($RUNNER_APK)"
  fi
fi

"$LLVM_STRIP" --strip-debug "$LIB_TARGET"/*.so 2>/dev/null || true

# 6. Repackage, 16KB zipalign and apksign
echo "==> [6/6] Packaging, 16KB zipalign, and signing APK..."
cp "$STAGE_DIR/base.apk" "$STAGE_DIR/unaligned.apk"
(cd "$STAGE_DIR/stage" && \
  zip -q -X "$STAGE_DIR/unaligned.apk" classes.dex && \
  zip -q -X -0 -r "$STAGE_DIR/unaligned.apk" lib && \
  ([ ! -d assets ] || zip -q -X -r "$STAGE_DIR/unaligned.apk" assets))

"$BT/zipalign" -f -P 16 4 "$STAGE_DIR/unaligned.apk" "$STAGE_DIR/aligned.apk"

if [ ! -f "$KEY" ]; then
  keytool -genkeypair -keystore "$KEY" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ReXGlue,O=Android,C=US" >/dev/null
fi

FINAL_APK="$OUT_DIR/$APK_NAME"
"$BT/apksigner" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android \
  --out "$FINAL_APK" "$STAGE_DIR/aligned.apk"

echo "=================================================="
echo "Successfully generated APK:"
echo "  $FINAL_APK"
ls -lh "$FINAL_APK"
echo "=================================================="
