#!/bin/bash
# Builds build/PadKey.app. Usage: ./build.sh [--install]
# Signs with the stable self-signed identity "PadKey Self-Signed" (create it once with ./make-signing-cert.sh)
# so Accessibility / Screen Recording grants survive rebuilds. Falls back to ad-hoc (grants reset each build).
# SIGN_ID="Other Cert" ./build.sh   -> use a different identity. VERSION=1.2.3 ./build.sh   -> stamp a version.
set -euo pipefail
cd "$(dirname "$0")"
swift build -c release
APP=build/PadKey.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/PadKey "$APP/Contents/MacOS/PadKey"
cp Info.plist "$APP/Contents/Info.plist"
# Release workflow: VERSION=1.2.3 ./build.sh stamps the version into the bundle.
if [ -n "${VERSION:-}" ]; then
  /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" -c "Set :CFBundleVersion ${BUILD_NUMBER:-$VERSION}" "$APP/Contents/Info.plist"
fi

SIGN_ID="${SIGN_ID:-PadKey Self-Signed}"
KC="$HOME/.padkey-signing/padkey.keychain-db"
[ -f "$KC" ] && security unlock-keychain -p "$(cat "$HOME/.padkey-signing/password.txt")" "$KC" 2>/dev/null || true
signed=0
if security find-identity -p codesigning 2>/dev/null | grep -q "\"$SIGN_ID\""; then
  # Watchdog: a locked/unapproved key makes codesign hang on a GUI dialog.
  codesign --force --deep --sign "$SIGN_ID" "$APP" & CS=$!
  ( sleep 30; kill $CS 2>/dev/null ) & W=$!
  if wait $CS 2>/dev/null; then signed=1; fi
  kill $W 2>/dev/null || true
fi
if [ $signed = 0 ]; then
  echo "WARNING: signing identity \"$SIGN_ID\" unavailable or not usable; using ad-hoc signature." >&2
  echo "         Accessibility and Screen Recording will reset on every rebuild. See README (Signing)." >&2
  codesign --force --deep --sign - "$APP"
fi
echo "Built $(pwd)/$APP ($(codesign -dv "$APP" 2>&1 | grep -E '^Authority|^Signature' | head -1))"
if [ "${1:-}" = "--install" ]; then
  mkdir -p ~/Applications
  pkill -x PadKey 2>/dev/null || true
  rm -rf ~/Applications/PadKey.app
  cp -R "$APP" ~/Applications/PadKey.app
  echo "Installed ~/Applications/PadKey.app"
fi
