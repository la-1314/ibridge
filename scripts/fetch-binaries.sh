#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$ROOT_DIR/app/src/main/assets/bin/arm64-v8a"
mkdir -p "$DEST_DIR"

if [[ -n "${IBRIDGE_BINARIES_DIR:-}" ]]; then
  echo "[iBridge] Copying native tools from $IBRIDGE_BINARIES_DIR"
  cp -f "$IBRIDGE_BINARIES_DIR"/* "$DEST_DIR"/
  exit 0
fi

if [[ -z "${IBRIDGE_BINARIES_URL:-}" ]]; then
  echo "[iBridge] IBRIDGE_BINARIES_URL is not set; building without bundled native tools."
  echo "[iBridge] Drop usbmuxd, idevice_*, ideviceinstaller and zsign into:"
  echo "[iBridge]   $DEST_DIR"
  exit 0
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "[iBridge] Downloading native tools: $IBRIDGE_BINARIES_URL"
curl -fL --retry 3 --connect-timeout 30 -o "$TMP_DIR/toolchain" "$IBRIDGE_BINARIES_URL"

case "$(file -b "$TMP_DIR/toolchain")" in
  *Zip*)
    unzip -q -o "$TMP_DIR/toolchain" -d "$TMP_DIR/out"
    ;;
  *gzip*)
    mkdir -p "$TMP_DIR/out"
    tar -xzf "$TMP_DIR/toolchain" -C "$TMP_DIR/out"
    ;;
  *)
    echo "[iBridge] Unknown archive format" >&2
    exit 1
    ;;
esac

find "$TMP_DIR/out" -type f \( \
  -name usbmuxd -o \
  -name idevice_id -o \
  -name ideviceinfo -o \
  -name idevicepair -o \
  -name ideviceinstaller -o \
  -name zsign -o \
  -name '*.so*' \) -exec cp -f {} "$DEST_DIR"/ \;

chmod +x "$DEST_DIR"/usbmuxd "$DEST_DIR"/idevice_* "$DEST_DIR"/ideviceinstaller "$DEST_DIR"/zsign 2>/dev/null || true
ls -l "$DEST_DIR"
