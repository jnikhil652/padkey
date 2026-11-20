#!/bin/bash
# Creates the stable self-signed code-signing identity "PadKey Self-Signed" and imports it into a dedicated
# keychain (~/.padkey-signing/padkey.keychain-db), so every rebuild keeps the same designated requirement (Accessibility / Screen Recording survive).
# The .p12 + password live in ~/.padkey-signing/ (never commit). Run once; idempotent.
set -euo pipefail
NAME="PadKey Self-Signed"
OUT="${1:-$HOME/.padkey-signing}"; mkdir -p "$OUT"; chmod 700 "$OUT"; cd "$OUT"
if [ ! -f padkey-signing.p12 ]; then
  PASS=$(openssl rand -hex 16)
  cat > cert.cnf <<CNF
[req]
distinguished_name=dn
x509_extensions=ext
prompt=no
[dn]
CN=$NAME
[ext]
basicConstraints=critical,CA:false
keyUsage=critical,digitalSignature
extendedKeyUsage=critical,codeSigning
CNF
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -keyout key.pem -out cert.pem -config cert.cnf
  openssl pkcs12 -export -legacy -inkey key.pem -in cert.pem -name "$NAME" -out padkey-signing.p12 -passout "pass:$PASS" 2>/dev/null \
    || openssl pkcs12 -export -inkey key.pem -in cert.pem -name "$NAME" -out padkey-signing.p12 -passout "pass:$PASS"
  rm -f key.pem cert.cnf
  echo "$PASS" > password.txt; chmod 600 password.txt padkey-signing.p12
  echo "Created $OUT/padkey-signing.p12"
fi
# Dedicated keychain with a generated password (kept next to the .p12), added to the user search list.
KC="$OUT/padkey.keychain-db"
PASS=$(cat password.txt)
if [ ! -f "$KC" ]; then
  security create-keychain -p "$PASS" "$KC"
  security set-keychain-settings "$KC"            # never auto-lock
  security unlock-keychain -p "$PASS" "$KC"
  security import padkey-signing.p12 -k "$KC" -P "$PASS" -A
  security set-key-partition-list -S apple-tool:,apple: -s -k "$PASS" "$KC" >/dev/null
fi
security unlock-keychain -p "$PASS" "$KC"
if ! security list-keychains -d user | grep -q padkey.keychain; then
  # shellcheck disable=SC2046
  security list-keychains -d user -s $(security list-keychains -d user | tr -d '"') "$KC"
fi
security find-identity -p codesigning | grep "$NAME" || true
# Smoke test with a watchdog: a hang here means macOS is showing a key-access dialog (see README).
T=$(mktemp -d)/t; cp /bin/echo "$T"
codesign --force --sign "$NAME" "$T" >/dev/null 2>&1 & CS=$!
( sleep 20; kill $CS 2>/dev/null ) & W=$!
if wait $CS 2>/dev/null; then echo "OK: codesign works with \"$NAME\""; else echo "WARNING: codesign with \"$NAME\" failed/hung"; fi
kill $W 2>/dev/null || true
