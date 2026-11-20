#!/bin/bash
# Builds build/PadKey.app (ad-hoc signed). Usage: ./build.sh [--install]
# SIGN_ID="My Cert" ./build.sh   -> sign with a stable identity so Accessibility survives rebuilds.
set -euo pipefail
cd "$(dirname "$0")"
swift build -c release
APP=build/PadKey.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/PadKey "$APP/Contents/MacOS/PadKey"
cp Info.plist "$APP/Contents/Info.plist"
codesign --force --deep --sign "${SIGN_ID:--}" "$APP"
echo "Built $(pwd)/$APP"
if [ "${1:-}" = "--install" ]; then
  mkdir -p ~/Applications
  pkill -x PadKey 2>/dev/null || true
  rm -rf ~/Applications/PadKey.app
  cp -R "$APP" ~/Applications/PadKey.app
  echo "Installed ~/Applications/PadKey.app"
fi
