# PadKey

Open-source remote for your Mac, from your Android phone, over Wi-Fi. No physical keyboard or mouse needed.

<!-- TODO: add screenshots (touchpad, keyboard, mirror layout) once taken on an unlocked phone with no personal content -->

## Features

- **Touchpad**: move, tap / double-tap, tap-hold drag, two-finger scroll and right-click, **pinch = Cmd+ / Cmd-** (zoom), three-finger swipes for Spaces and Mission Control
- **Full Mac keyboard**: Cmd/Opt/Ctrl/Fn, **Caps Lock** (shows its locked state), arrows, F-keys, media keys, a shortcut strip, and modifiers that latch or lock
- **Voice dictation**: hold 🎤 and speak (tap to lock hands-free, up to 8 minutes); Gemini cleans up the text, which is pasted on the Mac (or trigger [DScribe](https://github.com/jnikhil652/dscribe) on the Mac). Text is never lost: if the Mac is unreachable it is kept, shown, copied to the phone clipboard and sent when you reconnect
- **Type mode**: use your Android keyboard (swipe/voice typing) and send the text
- **Paste or type**: dictation and Type mode insert through the Mac clipboard (fast, exact; your clipboard is restored) or as keystrokes (Settings > Insert text by)
- **Screen mirror**: tap 🖥 for a live H.264 view of the Mac screen (Standard or Sharp quality). Tap = click, double-tap = double-click, long-press = right-click, drag = mouse drag, pinch = zoom, two-finger drag = pan
- **Android TV**: the same APK installs on Android TV / Google TV and shows a D-pad UI (no touch needed): a **remote** (arrows / OK, Esc, Tab, Backspace, media, volume, mute, Wake, plus a **Mouse** mode where the arrows move the cursor and OK clicks) and the full **keyboard** (modifiers, F-keys, shortcuts), or **Type text** with the TV's own keyboard. No screen mirror or dictation on TV. Sideload with `adb connect <tv-ip>` then `adb install PadKey-vX-android.apk`
- **Paired and encrypted**: a one-time 6-digit PIN pairs the phone; everything afterwards (input and video) is AES-256-GCM encrypted
- Mac menu-bar app: pairing, paired phones, Launch at login, permission status, Screen Recording relaunch helper

Everything stays on your local network: no account, no cloud relay. Dictation is the exception: it calls the Gemini API with your own key.

    macos/      Swift menu-bar agent (SwiftPM, builds a .app)
    android/    Kotlin + Jetpack Compose app
    tools/      test_client.py (pairing, input and security tests), test_stream.py (screen-mirror stream test)
    PROTOCOL.md protocol v2: pairing, encryption, messages (control TCP :50505, video TCP :50506, Bonjour _padkey._tcp)

## Install from Releases

Download from the [Releases](https://github.com/jnikhil652/padkey/releases) page:

- **Mac**: `PadKey-vX-mac.dmg` (or `.zip`), drag `PadKey.app` to `~/Applications` or `/Applications` and open it. If macOS says it is from an unidentified developer, right-click > Open once.
- **Android**: `PadKey-vX-android.apk`. Allow installing from your browser/files app and install it. An APK named `...-debugsigned.apk` was signed with a throwaway key (the release workflow had no keystore): it installs fine, but a later release signed with a different key cannot update it in place (uninstall first, which clears the pairing).

## Build from source

### Mac agent

Needs Xcode command line tools / Swift and macOS 13+:

    cd macos && ./build.sh            # -> macos/build/PadKey.app
    ./build.sh --install              # also copies to ~/Applications/PadKey.app and replaces a running copy

A keyboard icon appears in the menu bar. The menu shows connection status, the Mac's IP:port (click to copy), permission status,
pairing and the Launch at login toggle.

### Android app

Needs JDK 17 and the Android SDK (platform 34, build-tools 34):

    brew install openjdk@17 gradle && brew install --cask android-commandlinetools
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
    cd android && ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk     # or copy the APK to the phone

`local.properties` with `sdk.dir=...` must point at your SDK (it is git-ignored). `./gradlew assembleRelease` also works: without a
keystore it signs with the debug key (see "Releases" for real signing).

## Permissions (Mac)

macOS needs three one-time approvals for PadKey:

1. **Accessibility** (required): System Settings > Privacy & Security > Accessibility > enable **PadKey**. Without it the phone connects but no input is injected (the menu says "Accessibility NOT granted").
2. **Screen Recording** (for the 🖥 mirror): the first time the phone turns the mirror on, macOS asks. Otherwise System Settings > Privacy & Security > Screen Recording. If you grant it while PadKey is running, macOS only applies it after a restart: the menu then shows **Relaunch PadKey to enable Screen Recording** (click it) and the phone shows the same hint.
3. **Local network**: allow PadKey to accept devices on the local network when asked.

### Signing (keeps permissions across rebuilds)

macOS ties those grants to the app's code signature. An ad-hoc signature changes on every build, so the grants reset after each
rebuild. To keep them, run once:

    macos/make-signing-cert.sh

It creates a self-signed code-signing identity **PadKey Self-Signed** in `~/.padkey-signing/` (`padkey-signing.p12`,
`password.txt`, a dedicated `padkey.keychain-db` that is added to your keychain search list). **Never commit that folder.**
`./build.sh` then signs with it automatically and falls back to ad-hoc (with a warning) when it is unusable. Check with
`codesign -dv ~/Applications/PadKey.app` (the signature should not say `adhoc`). macOS may show a keychain dialog the first time
`codesign` uses the key: approve it with "Always Allow" (the keychain password is in `~/.padkey-signing/password.txt`). After the
first stably-signed build, grant Accessibility and Screen Recording once more; later rebuilds keep them.

## Pair and use

1. Mac and phone on the same Wi-Fi (not a guest network with client isolation). PadKey running on the Mac.
2. Open PadKey on the phone. It finds the Mac via Bonjour (or tap **Connect** and type the Mac's IP). A PIN dialog opens
   because the phone is not paired yet.
3. On the Mac: menu-bar icon > **Pair new phone...** shows a 6-digit PIN (valid 2 minutes). Type it on the phone.
4. Done: the dot turns green and the phone reconnects automatically from now on. Pair again only after "Forget all phones" on the
   Mac, "Forget paired Macs" in the phone's Settings, or an app data reset.

The Mac menu lists paired phones and has **Forget all phones**. Several phones and several Macs can be paired.

### Pairing and encryption model

- Pairing: ECDH P-256 key exchange whose key derivation includes the PIN (HKDF over shared secret and PIN) and PIN-keyed confirmation MACs in both directions. It yields a random 32-byte long-term secret per phone/Mac pair, stored in `~/.config/padkey/paired.json` (0600) on the Mac and in app-private storage on the phone. The PIN window closes after 2 minutes, 3 wrong tries or one success.
- Every connection (control and video) derives fresh session keys from the long-term secret and random nonces from both sides, and sends length-prefixed AES-256-GCM frames with per-direction counters (forgery, replay and reordering break the connection).
- Unpaired clients can only attempt `hello`/`pair`: no input, no video, on both ports.
- Limit: this is not a full PAKE. An active attacker on your LAN during the 2-minute pairing window could try to guess the PIN offline. Pair on a trusted network. Details: [PROTOCOL.md](PROTOCOL.md).
- Dictation sends audio to Google's Gemini API with the key you put in Settings (stored on the phone only).

## Using the phone app

Touchpad: 1-finger move (accelerated; speed in settings), tap = click, double tap = double click, tap then hold/move = drag,
2-finger tap = right click, 2-finger drag = scroll (momentum, natural scrolling toggle), 2-finger pinch = Cmd+ / Cmd-,
3-finger swipe left/right = Ctrl+Left/Right (switch Spaces), up = Mission Control, down = App Exposé. Optional left/right click
strips below the pad.

Keyboard: modifiers (shift, ctrl, opt, cmd, fn) tap = one-shot for the next key/click, double tap = lock (orange). `⇪` = Caps Lock
(orange while on). `?123` = numbers/symbols, `Fn` = F1-F12, media keys, Home/End/PgUp/PgDn/Fwd Del. Shortcut strip on top
(Copy, Paste, ..., Spotlight, Close, Quit, Shot, Lock). **Type** = text field using the Android keyboard; Send inserts the text on
the Mac (optional "Return after").

Dictation (🎤): add your Gemini API key in Settings. Hold to talk, or tap to lock on and tap again to stop; a countdown appears
in the last 30 s of the 8-minute limit, and long recordings are transcribed in chunks. Pending text, if any, shows in an orange
bar with Send now / Copy / Discard; a failed transcription can be retried.

Screen mirror (🖥): toggles a live view of the Mac screen (portrait: screen / touchpad / keyboard in thirds; landscape: screen
left). Long-press 🖥 to pick the display. Tap = click, double-tap = double-click, long-press = right-click, one-finger drag =
mouse drag, pinch = zoom (1-4x), two-finger drag = pan when zoomed, the zoom chip resets the zoom, **Follow** keeps the Mac
cursor centered while zoomed. Settings > Mirror quality: Standard (1280 px, ~4 Mbps) or Sharp (up to 2560 px, ~10 Mbps).

Mission Control / Spaces swipes rely on the default macOS keyboard shortcuts (System Settings > Keyboard > Keyboard Shortcuts >
Mission Control).

## Releases (maintainers)

Pushing a tag `vX.Y.Z` runs `.github/workflows/release.yml`: it builds the release APK and the Mac app (`.zip` and `.dmg`) and
attaches them to a GitHub Release. Signing is optional; the workflow signs with your keys when these repository secrets exist and
falls back to a debug-signed APK / ad-hoc Mac app when they do not. Run these yourself (never commit keys):

    # Android release keystore (once; keep the .jks and passwords safe, losing them means users must reinstall)
    keytool -genkeypair -v -keystore ~/padkey-release.jks -alias padkey -keyalg RSA -keysize 2048 -validity 10000

    gh secret set PADKEY_KEYSTORE_BASE64 -R jnikhil652/padkey < <(base64 -i ~/padkey-release.jks)
    gh secret set PADKEY_KEYSTORE_PASSWORD -R jnikhil652/padkey
    gh secret set PADKEY_KEY_ALIAS -R jnikhil652/padkey --body padkey
    gh secret set PADKEY_KEY_PASSWORD -R jnikhil652/padkey

    # Mac signing identity: run macos/make-signing-cert.sh first, then
    gh secret set PADKEY_SIGNING_P12_BASE64 -R jnikhil652/padkey < <(base64 -i ~/.padkey-signing/padkey-signing.p12)
    gh secret set PADKEY_SIGNING_P12_PASSWORD -R jnikhil652/padkey < ~/.padkey-signing/password.txt

(`gh secret set NAME` without `<` or `--body` prompts for the value.) Then release with `git tag v2.0.1 && git push origin v2.0.1`.
To sign a local release APK use the same variables: `PADKEY_KEYSTORE=~/padkey-release.jks PADKEY_KEYSTORE_PASSWORD=... ./gradlew assembleRelease`.

## Tests without a phone

    pip install cryptography
    python3 tools/test_client.py --pair 123456    # pair a test client with the PIN shown by "Pair new phone..."
    python3 tools/test_client.py                  # moves the pointer, presses F13, types and pastes a short string
    python3 tools/test_client.py --security       # unpaired, tampered and replayed traffic must be refused
    python3 tools/test_stream.py                  # reads the encrypted H.264 stream (needs Screen Recording permission)

The test client keeps its own phone id and secret in `~/.config/padkey-test/phone.json`. Forget it from the Mac menu
("Forget all phones") when you are done.

## Troubleshooting

- **Phone connects but nothing moves / types**: Accessibility is missing or stale. After an ad-hoc rebuild the grant no longer matches the new binary: reset it and grant again:

      tccutil reset Accessibility fun.buildverse.padkey

  then relaunch PadKey and enable it in System Settings > Privacy & Security > Accessibility. Same for Screen Recording (`tccutil reset ScreenCapture fun.buildverse.padkey`). Sign with `macos/make-signing-cert.sh` to stop this from recurring.
- **Mirror shows "needs a restart"**: Screen Recording was granted while PadKey was running; use the menu item "Relaunch PadKey to enable Screen Recording".
- **"Not paired" keeps coming back**: the Mac's `~/.config/padkey/paired.json` was deleted or "Forget all phones" was used; pair again. If the phone says it cannot verify the Mac, you are talking to a different Mac than the one you paired: pair again (or check for something on your network impersonating it).
- **Phone cannot find the Mac**: same Wi-Fi, no client isolation, allow the Local Network prompt on the Mac; or enter the IP in Connect. Ports 50505 and 50506 must be reachable.
- **Launch at login fails**: install the app first (`./build.sh --install` or from the DMG) and toggle it again, or add it in System Settings > General > Login Items.
- **Text pasted wrongly / apps ignore paste**: switch Settings > Insert text by > Type keystrokes.

## License

MIT, see [LICENSE](LICENSE).
