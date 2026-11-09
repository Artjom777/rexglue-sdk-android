#!/bin/sh
# ==============================================================================
# ReXGlue SDK - On-Device Codegen Script for POCO X7 Pro / Android
# Decompiles Xbox 360 default.xex into portable C++ source code directly on phone!
# ==============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REXGLUE_BIN="${REXGLUE:-$SCRIPT_DIR/../../out/install/android-poco/bin/rexglue}"

if [ ! -f "$REXGLUE_BIN" ]; then
  # Try relative path or search in PATH
  if command -v rexglue >/dev/null 2>&1; then
    REXGLUE_BIN=$(command -v rexglue)
  elif [ -f "$SCRIPT_DIR/rexglue" ]; then
    REXGLUE_BIN="$SCRIPT_DIR/rexglue"
  elif [ -f "$SCRIPT_DIR/../../out/install/android-arm64/bin/rexglue" ]; then
    REXGLUE_BIN="$SCRIPT_DIR/../../out/install/android-arm64/bin/rexglue"
  fi
fi

if [ ! -f "$REXGLUE_BIN" ] && ! command -v "$REXGLUE_BIN" >/dev/null 2>&1; then
  echo "Error: rexglue executable not found!"
  echo "Please build the SDK first using: ./tools/android/build_sdk.sh"
  echo "Or set REXGLUE=/path/to/rexglue"
  exit 1
fi

LIB_DIR="$(dirname "$REXGLUE_BIN")/../lib"
if [ -d "$LIB_DIR" ]; then
  export LD_LIBRARY_PATH="$LIB_DIR:$LD_LIBRARY_PATH"
fi
export LD_LIBRARY_PATH="$(dirname "$REXGLUE_BIN"):$LD_LIBRARY_PATH"

XEX_FILE="$1"
PROJECT_NAME="${2:-recompiled_game}"
OUTPUT_DIR="${3:-$PWD/$PROJECT_NAME}"

if [ -z "$XEX_FILE" ] || [ ! -f "$XEX_FILE" ]; then
  echo "Usage: $0 <path_to_default.xex> [project_name] [output_directory]"
  echo "Example: $0 ~/Downloads/default.xex my_xbox_game"
  exit 1
fi

echo "=================================================="
echo "ReXGlue On-Device Xbox 360 Codegen (POCO X7 Pro)"
echo "  XEX File:     $XEX_FILE"
echo "  Project Name: $PROJECT_NAME"
echo "  Output Dir:   $OUTPUT_DIR"
echo "  ReXGlue:      $REXGLUE_BIN"
echo "=================================================="

mkdir -p "$OUTPUT_DIR"
cd "$OUTPUT_DIR"

MANIFEST_FILE="$OUTPUT_DIR/${PROJECT_NAME}_manifest.toml"

# Step 1: Initialize project manifest if not already present
if [ ! -f "$MANIFEST_FILE" ]; then
  echo "==> Step 1: Generating project manifest..."
  "$REXGLUE_BIN" init "$PROJECT_NAME" "$XEX_FILE" --output "$MANIFEST_FILE" || true
fi

if [ ! -f "$MANIFEST_FILE" ]; then
  # Fallback manifest creation if init subcommand creates default manifest
  if [ -f "$OUTPUT_DIR/rexglue.toml" ]; then
    MANIFEST_FILE="$OUTPUT_DIR/rexglue.toml"
  elif [ -f "$OUTPUT_DIR/manifest.toml" ]; then
    MANIFEST_FILE="$OUTPUT_DIR/manifest.toml"
  fi
fi

# Step 2: Run Recompiler Codegen
echo "==> Step 2: Running ReXGlue PPC -> C++ Codegen..."
"$REXGLUE_BIN" --force --log-level info codegen "$MANIFEST_FILE"

echo "=================================================="
echo "Codegen Completed Successfully!"
echo "Generated C++ sources are in: $OUTPUT_DIR"
echo "You can now build the game APK or library using:"
echo "  ./tools/android/build_game.sh $OUTPUT_DIR"
echo "=================================================="
