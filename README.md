# PadKey

Control a MacBook from an Android phone on the same Wi-Fi: touchpad on top, a custom Mac keyboard below
(Cmd/Opt/Ctrl/Fn, arrows, F-keys, media keys, shortcuts), plus a "Type" mode that uses the normal Android
keyboard (swipe typing / voice typing) and sends the text.

    macos/      Swift menu-bar agent (SwiftPM, builds a .app)
    android/    Kotlin + Jetpack Compose app
    tools/      test_client.py - smoke test for the Mac agent
    PROTOCOL.md newline-delimited JSON over TCP :50505, Bonjour _padkey._tcp

## 1. Mac agent

Build (needs Xcode command line tools / Swift):

    cd macos && ./build.sh            # -> macos/build/PadKey.app
    ./build.sh --install              # also copies to ~/Applications/PadKey.app

Run: `open macos/build/PadKey.app` (or the installed copy). A keyboard icon appears in the menu bar; its menu shows
connection status, the Mac's IP:port (click to copy), and Accessibility status.

**Grant Accessibility (required, once):** on first launch macOS shows a prompt. Otherwise open
System Settings > Privacy & Security > Accessibility, add/enable **PadKey**. Without it, the app runs and the phone
connects, but no input is injected (the menu shows "Accessibility NOT granted"). After granting, if input still does not
work, quit and relaunch PadKey.
The app is ad-hoc signed, so macOS treats every rebuild as a new app: after rebuilding, remove PadKey from the
Accessibility list (minus button) and add it again. To avoid that, sign with a stable certificate:
`SIGN_ID="My Self-Signed Cert" ./build.sh`.

**Start at login:** System Settings > General > Login Items & Extensions > Open at Login > `+` > pick
`~/Applications/PadKey.app` (run `./build.sh --install` first).

**Local network prompt:** macOS may ask to allow PadKey to find/accept devices on the local network - allow it.

Optional token: `mkdir -p ~/.config/padkey && echo mysecret > ~/.config/padkey/token` (read on each connection), and enter the same
token in the phone's Connect dialog. No token file = open to anyone on the LAN.

Test without a phone (agent running): `python3 tools/test_client.py [host] [port] [token]` moves the pointer a few
pixels, presses F13, types a short string, double-clicks, and checks the agent stays alive.

## 2. Android app

Needs JDK 17 and the Android SDK (platform 34, build-tools 34). With Homebrew:

    brew install openjdk@17 gradle && brew install --cask android-commandlinetools
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
    sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

Build and install (`local.properties` with `sdk.dir=...` is already created; recreate it if the SDK path differs):

    cd android && ./gradlew assembleDebug
    # APK: android/app/build/outputs/apk/debug/app-debug.apk
    adb install -r app/build/outputs/apk/debug/app-debug.apk     # phone with USB debugging, or copy the APK to the phone

## 3. Pair and use

1. Mac and phone on the same Wi-Fi (not a guest network with client isolation). PadKey running on the Mac.
2. Open PadKey on the phone. It auto-discovers the Mac via Bonjour; if "Connect (1)" shows, tap it and pick the Mac.
   Or tap **Connect**, type the Mac's IP (from the menu-bar menu) and Connect. The last host is remembered and the app
   reconnects automatically (and re-discovers if the Mac's IP changes).
3. The dot turns green; the screen stays on while connected.

Touchpad: 1-finger move (accelerated; speed in settings), tap = click, double tap = double click, tap then hold/move =
drag, 2-finger tap = right click, 2-finger drag = scroll (momentum, natural scrolling toggle), 3-finger swipe left/right =
Ctrl+Left/Right (switch Spaces), up = Mission Control, down = App Exposé. Optional left/right click strips below the pad
(hold one and drag with another finger).

Keyboard: modifiers (shift, ctrl, opt, cmd, fn) tap = one-shot for the next key/click, double tap = lock (orange). `?123`
= numbers/symbols, `Fn` = F1-F12, media keys, Home/End/PgUp/PgDn/Fwd Del. Shortcut strip on top (Copy, Paste, ..., Spotlight,
Close, Quit, Shot = Cmd+Shift+4, Lock = Ctrl+Cmd+Q). **Type** = text field using the Android keyboard; Send types the text
on the Mac (optional "Return after").

Mission Control / Spaces swipes rely on the default macOS keyboard shortcuts (System Settings > Keyboard > Keyboard Shortcuts
> Mission Control).
