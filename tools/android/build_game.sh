#!/bin/bash
# ==============================================================================
# ReXGlue SDK - Android Game Port Compilation Helper
# Compiles a recompiled game project into Android shared libraries (libmain.so)
# ==============================================================================
set -e

if [ -f "$HOME/android-tools/env.sh" ]; then
  source "$HOME/android-tools/env.sh"
fi

SDK_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
GAME_DIR="${1:-$PWD}"
BUILD_DIR="${BUILD_DIR:-$GAME_DIR/build/android}"
PREFIX="${PREFIX:-$SDK_ROOT/out/install/android-poco}"

if [ ! -d "$PREFIX" ]; then
  PREFIX="$SDK_ROOT/out/install/android-arm64"
fi

MARCH=${MARCH:-"armv8.2-a+lse+rcpc+dotprod"}
JOBS=${JOBS:-$(nproc 2>/dev/null || echo 4)}
STEP=${2:-all}

if [ ! -f "$GAME_DIR/CMakeLists.txt" ]; then
  echo "Usage: $0 <game_project_directory_with_CMakeLists.txt> [configure|build|all]"
  exit 1
fi

echo "=================================================="
echo "Compiling Recompiled Game for Android (POCO X7 Pro)"
echo "  Game Dir:      $GAME_DIR"
echo "  Build Dir:     $BUILD_DIR"
echo "  ReXGlue SDK:   $PREFIX"
echo "  Arch/Flags:    $MARCH (16KB Page Aligned)"
echo "=================================================="

# Check NDK
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$NDK_HOME"
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -d "$ANDROID_HOME/ndk" ]; then
    ANDROID_NDK_HOME=$(find "$ANDROID_HOME/ndk" -maxdepth 1 -mindepth 1 2>/dev/null | sort -V | tail -n 1)
  fi
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  echo "Error: ANDROID_NDK_HOME is not set!"
  exit 1
fi

TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"

if [ "$STEP" = "configure" ] || [ "$STEP" = "all" ]; then
  mkdir -p "$BUILD_DIR"
  cmake -S "$GAME_DIR" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_ABI="arm64-v8a" \
    -DANDROID_PLATFORM="android-30" \
    -DANDROID_STL="c++_shared" \
    -DCMAKE_BUILD_TYPE="Release" \
    -DCMAKE_C_FLAGS="-march=${MARCH}" \
    -DCMAKE_CXX_FLAGS="-march=${MARCH} -fno-semantic-interposition" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-Bsymbolic-functions -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
    -DCMAKE_FIND_ROOT_PATH="$PREFIX" \
    -Drexglue_DIR="$PREFIX/lib/cmake/rexglue" \
    -DSDL3_DIR="$PREFIX/lib/cmake/SDL3" \
    -DCMAKE_FIND_PACKAGE_NO_PACKAGE_REGISTRY=ON \
    ${EXTRA_CMAKE_FLAGS:-}
fi

if [ "$STEP" = "build" ] || [ "$STEP" = "all" ]; then
  cmake --build "$BUILD_DIR" -j"$JOBS"
  echo "==> Built libraries in $BUILD_DIR:"
  find "$BUILD_DIR" -name "*.so" -exec ls -lh {} +
fi
