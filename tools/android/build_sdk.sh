#!/bin/bash
# ==============================================================================
# ReXGlue SDK - Android ARM64 / POCO X7 Pro Build Script
# Builds rexglue CLI (PPC->C++ Codegen recompiler) and rexruntime for Android
# ==============================================================================
set -e

if [ -f "$HOME/android-tools/env.sh" ]; then
  source "$HOME/android-tools/env.sh"
fi

SDK_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TARGET_ARCH=${TARGET_ARCH:-"arm64-v8a"}
API_LEVEL=${API_LEVEL:-"30"}
BUILD_TYPE=${BUILD_TYPE:-"Release"}

# Target optimization:
# POCO X7 Pro uses MediaTek Dimensity (ARMv9.2-A / Cortex-A725 & X-series).
# ARMv8.2-A with LSE (atomic instructions), RCPC and DotProd delivers maximum
# performance while remaining 100% compatible with modern Mali-G720 / Mali-G615.
if [ "${POCO:-0}" = "1" ] || [ "${TARGET:-}" = "poco" ]; then
  MARCH=${MARCH:-"armv8.2-a+lse+rcpc+dotprod"}
  BUILD_DIR=${BUILD_DIR:-"$SDK_ROOT/out/build/android-poco"}
  INSTALL_PREFIX=${INSTALL_PREFIX:-"$SDK_ROOT/out/install/android-poco"}
  PACKAGE_NAME="rexglue-sdk-poco-x7-pro"
else
  MARCH=${MARCH:-"armv8.2-a+lse+rcpc+dotprod"}
  BUILD_DIR=${BUILD_DIR:-"$SDK_ROOT/out/build/android-arm64"}
  INSTALL_PREFIX=${INSTALL_PREFIX:-"$SDK_ROOT/out/install/android-arm64"}
  PACKAGE_NAME="rexglue-sdk-android-arm64"
fi

JOBS=${JOBS:-$(nproc 2>/dev/null || echo 4)}
STEP=${1:-all}

echo "=================================================="
echo "ReXGlue SDK Android Build"
echo "  Target:        ${PACKAGE_NAME}"
echo "  Architecture:  ${TARGET_ARCH} (${MARCH})"
echo "  API Level:     ${API_LEVEL}"
echo "  Build Type:    ${BUILD_TYPE}"
echo "  Page Size:     16 KB aligned (Android 15 / MediaTek)"
echo "  Build Dir:     ${BUILD_DIR}"
echo "  Install Prefix:${INSTALL_PREFIX}"
echo "=================================================="

# Check Android NDK
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$NDK_HOME"
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -d "$ANDROID_HOME/ndk" ]; then
    ANDROID_NDK_HOME=$(find "$ANDROID_HOME/ndk" -maxdepth 1 -mindepth 1 2>/dev/null | sort -V | tail -n 1)
  fi
fi

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "Error: ANDROID_NDK_HOME is not set or not found!"
  echo "Please set ANDROID_NDK_HOME to your NDK path."
  exit 1
fi

TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN_FILE" ]; then
  echo "Error: Toolchain file not found at $TOOLCHAIN_FILE"
  exit 1
fi

# Step: Configure
if [ "$STEP" = "configure" ] || [ "$STEP" = "all" ]; then
  echo "==> Configuring CMake..."
  mkdir -p "$BUILD_DIR"
  cmake -S "$SDK_ROOT" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_ABI="$TARGET_ARCH" \
    -DANDROID_PLATFORM="android-$API_LEVEL" \
    -DANDROID_STL="c++_shared" \
    -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
    -DCMAKE_CXX_STANDARD=23 \
    -DCMAKE_C_FLAGS="-march=${MARCH}" \
    -DCMAKE_CXX_FLAGS="-march=${MARCH} -fno-semantic-interposition" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-Bsymbolic-functions -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
    -DCMAKE_INSTALL_PREFIX="$INSTALL_PREFIX" \
    -DREXGLUE_ENABLE_TRACY=OFF \
    -DREXGLUE_BUILD_TESTS=OFF \
    -DSDL_X11=OFF \
    -DSDL_WAYLAND=OFF \
    -DSDL_ALSA=OFF \
    -DSDL_PULSEAUDIO=OFF \
    -DSDL_PIPEWIRE=OFF
fi

# Step: Build
if [ "$STEP" = "build" ] || [ "$STEP" = "all" ]; then
  echo "==> Building targets with Ninja ($JOBS jobs)..."
  cmake --build "$BUILD_DIR" -j"$JOBS"
fi

# Step: Install
if [ "$STEP" = "install" ] || [ "$STEP" = "all" ]; then
  echo "==> Installing SDK to $INSTALL_PREFIX..."
  cmake --install "$BUILD_DIR"

  # Also copy libc++_shared.so into the install bin and lib directories
  NDK_LIBCXX=$(find "$ANDROID_NDK_HOME" -name "libc++_shared.so" 2>/dev/null | grep "aarch64" | head -n 1)
  if [ -n "$NDK_LIBCXX" ] && [ -f "$NDK_LIBCXX" ]; then
    mkdir -p "$INSTALL_PREFIX/lib" "$INSTALL_PREFIX/bin"
    cp "$NDK_LIBCXX" "$INSTALL_PREFIX/lib/"
    cp "$NDK_LIBCXX" "$INSTALL_PREFIX/bin/"
  fi

  # Prevent host CMake from accidentally finding Android package
  grep -l -E "android" ~/.cmake/packages/rexglue/* 2>/dev/null | xargs -r -I{} mv {} "$BUILD_DIR/registry-entry" 2>/dev/null || true

  echo "==> Installed binaries in $INSTALL_PREFIX/bin:"
  ls -lh "$INSTALL_PREFIX/bin" || true
  echo "==> Installed libraries in $INSTALL_PREFIX/lib:"
  ls -lh "$INSTALL_PREFIX/lib" || true
fi

# Step: Package
if [ "$STEP" = "package" ] || [ "$STEP" = "all" ]; then
  echo "==> Creating release packages..."
  mkdir -p "$SDK_ROOT/out/dist"
  
  # 1. Full SDK Package (CLI + Runtime libs + Headers + CMake modules)
  (cd "$(dirname "$INSTALL_PREFIX")" && \
    zip -r -9 "$SDK_ROOT/out/dist/${PACKAGE_NAME}.zip" "$(basename "$INSTALL_PREFIX")")
  tar -czf "$SDK_ROOT/out/dist/${PACKAGE_NAME}.tar.gz" -C "$(dirname "$INSTALL_PREFIX")" "$(basename "$INSTALL_PREFIX")"

  # 2. Standalone CLI Codegen Package (Just rexglue executable + libc++ + wrapper script)
  CLI_DIR="$BUILD_DIR/rexglue-cli"
  rm -rf "$CLI_DIR"
  mkdir -p "$CLI_DIR"
  cp "$INSTALL_PREFIX/bin/rexglue" "$CLI_DIR/"
  if [ -f "$INSTALL_PREFIX/bin/libc++_shared.so" ]; then
    cp "$INSTALL_PREFIX/bin/libc++_shared.so" "$CLI_DIR/"
  fi
  if [ -f "$INSTALL_PREFIX/lib/librexruntime.so" ]; then
    cp "$INSTALL_PREFIX/lib/librexruntime.so" "$CLI_DIR/"
  fi

  cat << 'EOF' > "$CLI_DIR/rexglue.sh"
#!/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
export LD_LIBRARY_PATH="$DIR:$LD_LIBRARY_PATH"
chmod +x "$DIR/rexglue" 2>/dev/null
exec "$DIR/rexglue" "$@"
EOF
  chmod +x "$CLI_DIR/rexglue.sh" "$CLI_DIR/rexglue"

  (cd "$BUILD_DIR" && zip -r -9 "$SDK_ROOT/out/dist/${PACKAGE_NAME}-cli-only.zip" "rexglue-cli")
  echo "==> Packages created in $SDK_ROOT/out/dist:"
  ls -lh "$SDK_ROOT/out/dist"
fi

echo "==> ReXGlue SDK Android build finished successfully!"
