# PadKey protocol (v2)

Transport: TCP, control on port **50505**, video on **50506**, `TCP_NODELAY`. Bonjour/mDNS service type `_padkey._tcp`.
v2 adds mandatory pairing and encryption on both ports. v1 clients (no pairing, optional token) are refused with
`{"t":"error","code":"version",...}`.

A connection has two phases:

1. **Handshake**: newline-terminated JSON lines in plaintext (`hello`, or `pair` / `pair_confirm`), at most 4 KB per line.
2. **Session**: after a successful `hello` exchange everything is an encrypted frame (below). No plaintext afterwards.

An unpaired client can only do `hello` (refused) and `pair` (needs the PIN). It cannot send input, ping, or receive video.
A connection that does not finish `hello` and send a first valid frame within 30 s (5 s on the video port) is dropped.

## Pairing (once per phone and Mac)

The Mac menu item **Pair new phone...** shows a 6-digit PIN for 2 minutes (3 wrong confirmations, or one success, close the
window). The user types it on the phone. All values are base64 unless noted; `||` is concatenation.

    P -> M  {"t":"pair","v":2,"id":"<phone id, 32 hex>","name":"Pixel","pub":"<phone ECDH pub>"}
    M -> P  {"t":"pair_pub","pub":"<mac ECDH pub>"}
    P -> M  {"t":"pair_confirm","m":"<MAC>"}
    M -> P  {"t":"pair_ok","mid":"<mac id>","name":"...","m":"<MAC>","iv":"<12 bytes>","ct":"<ciphertext||tag>"}

* Both sides generate an ephemeral **ECDH P-256** key. Public keys are 65-byte uncompressed X9.63 points (`04||X||Y`).
* `shared` = ECDH x-coordinate (32 bytes). `salt = SHA256(phonePub || macPub)`.
* `K = HKDF-SHA256(ikm = shared || PIN (ASCII), salt, info = "padkey v2 pair")`.
  `confirmKey = HKDF(K, salt, "padkey v2 pair confirm")`, `wrapKey = HKDF(K, salt, "padkey v2 pair wrap")`.
* `transcript = "padkey v2 pair" || phonePub || macPub`.
* Phone MAC `m = HMAC-SHA256(confirmKey, "phone" || transcript)`. The Mac verifies it; a wrong PIN fails here (error `wrong_pin`).
* The Mac then generates the **long-term secret** `S` (32 random bytes), stores it with the phone id and answers with its own
  MAC `HMAC-SHA256(confirmKey, "mac" || transcript)` (the phone verifies it, so the Mac proved the PIN too) and `S` encrypted
  with AES-256-GCM under `wrapKey` (random 12-byte `iv`, AAD = the phone id).
* Mac stores `S` in `~/.config/padkey/paired.json` (0600); the phone in its app-private SharedPreferences. The Mac also has a
  random `mid` (stored in the same file) that the phone uses to pick the right secret.

Limits (be honest about them): this is a PIN-bound ECDH, not a full PAKE. A passive eavesdropper learns nothing useful, and an
attacker without the PIN cannot complete pairing with the Mac. An *active* attacker who sits between phone and Mac during the
2-minute window could, however, grind the 10^6 PINs offline against the phone's confirmation MAC. Pair on a network you trust,
and use "Forget all phones" if a pairing was ever done on a hostile network.

## Handshake for a paired phone

    P -> M  {"t":"hello","v":2,"id":"<phone id>","n":"<16 random bytes>"}
    M -> P  {"t":"hello","ok":true,"v":2,"name":"My MacBook Pro","mid":"<mac id>","n":"<16 random bytes>","auth":"<MAC>"}

* Unknown phone id: `{"t":"error","msg":"not paired","code":"not_paired"}` and close. The phone then asks for a PIN.
* `auth = HMAC-SHA256(S, "padkey v2 mac " || label || nc || ns)` with `label = "ctl"` (control) or `"video"`. The phone verifies it
  before sending anything, so a fake Mac cannot talk to the phone.
* Session keys (fresh per connection, so reconnects never reuse a key/nonce pair):
  `okm = HKDF-SHA256(S, salt = nc || ns, info = "padkey v2 keys " || label, 64 bytes)`;
  phone -> Mac key = `okm[0:32]`, Mac -> phone key = `okm[32:64]`.
* The phone proves it holds `S` by sending a first valid frame (control: its first `ping`; video: `{"t":"ready"}`).
  Until then the Mac does not treat the connection as a phone and sends no video.

## Frames (session phase, both ports, both directions)

    | 4 bytes: big-endian N = len(ciphertext)+16 | ciphertext (N-16 bytes) | 16-byte GCM tag |

* AES-256-GCM. Nonce = 4 zero bytes || 8-byte big-endian **per-direction counter** starting at 0 and incremented per frame.
  AAD = the 4 length bytes. A frame that fails authentication, replays, or arrives out of order breaks the counter, so the
  receiver closes the connection.
* Control frames: plaintext is one UTF-8 JSON object (no newline needed), N <= 1,000,000.
* Video frames: plaintext is one Annex-B H.264 access unit, N <= 8,000,016. The Mac only seals frames it actually sends, so
  dropping frames for a slow viewer does not desynchronise the counter.

## Control messages

Every message has a type field `t`. Numbers may be floats. Unknown messages/fields are ignored.

### Keep-alive

Phone sends `{"t":"ping","id":<number>}` about every 2.5 s; Mac answers `{"t":"pong","id":<same>}` (the phone uses epoch millis to
show latency). The Mac drops a connection silent for 12 s and releases every held key and button; the phone reconnects after
8 s of silence. After the first ping the Mac sends `{"t":"caps","on":bool}` (Caps Lock state); it repeats it on every toggle.

### Input (Phone -> Mac)

| Message | Fields | Meaning |
|---|---|---|
| `move` | `dx`, `dy` (points) | Relative pointer move, already accelerated by the phone. Clamped to the union of displays. Becomes a drag event while a button is down. |
| `scroll` | `dx`, `dy` (pixels) | Smooth pixel scroll. Positive `dy` = content moves down. Fractions are accumulated. |
| `btn` | `b` = `left`/`right`/`middle`, `d` = true/false, `n` (click state, default 1) | Raw button down/up (used for drag). |
| `click` | `b`, `n` | One down+up pair with click state `n` (2 = second click of a double click, 3 = triple). |
| `key` | `c` (macOS virtual keycode), `d` = true/false, `f` (flags, optional) | Key down/up. Held non-modifier keys auto-repeat (400 ms, then 35 ms) until the up. **Keycode 57 = Caps Lock: a key-down toggles it** (system state and event flags), key-up is ignored. |
| `text` | `s`, `id`, `enter` | Type a unicode string (chunks of <= 16 UTF-16 units, ~8 ms apart; `\n` presses Return, `\t` Tab); `enter` presses Return afterwards. |
| `paste` | `s`, `id`, `enter` | Insert `s` through the clipboard: save every pasteboard item/type, set the text (marked transient for clipboard managers), post Cmd+V, restore ~400 ms later (skipped if the clipboard changed meanwhile). |
| `media` | `k` | System key press: `volup voldown mute brightup brightdown play next prev fast rewind kbup kbdown`. |
| `release` | - | Release all held keys/buttons. |
| `wake` | - | Wake the display (declares user activity). Shows the lock screen if the Mac is locked; cannot wake a Mac in full system sleep. |

`text` / `paste` carry a random `id` (<= 2^53). The Mac answers `{"t":"ack","id":<id>}` once the text was posted. The phone keeps
unacknowledged text and re-sends it after a reconnect; the Mac remembers the last 64 ids and re-acks a duplicate without typing it again.

### Modifiers

Modifier keycodes (Cmd 55, Shift 56, Option 58, Ctrl 59, Fn 63; right-hand 54/60/61/62) sent as `key` down/up are tracked by the
Mac as held state and applied to every following keyboard and mouse event. The optional `f` bitmask adds flags to a single
event only: shift = 1, ctrl = 2, alt/option = 4, cmd = 8, fn = 16, caps = 32.

## Screen mirror

| Message | Direction | Fields | Meaning |
|---|---|---|---|
| `screen` | phone -> Mac | `on`, `display` (index, optional), `q` (0 Standard, 1 Sharp, optional) | Start/stop mirroring; switch display or quality (the Mac rebuilds the encoder). With several phones the highest `q` wins. |
| `moveto` | phone -> Mac | `x`, `y` in 0..1, optional `f` | Absolute pointer move on the mirrored display (top-left origin). While a button is down it produces drag events. Tap = `moveto` + `click`; drag = `moveto`, `btn` down, `moveto`..., `btn` up. |
| `screeninfo` | Mac -> phone | `w`,`h` (points), `sel`, `displays`, `ok`, `perm`, `reason` | Sent after `screen on` and whenever capture starts/fails. `reason`: `ok`, `no_permission`, `needs_relaunch` (Screen Recording granted but capture only works after relaunching PadKey), `failed`. |
| `cursor` | Mac -> phone | `x`,`y` (normalized, may be outside 0..1) | Pointer position, ~15 Hz while mirroring and only when it moved. |

### Video stream (port 50506)

1. Viewer connects, does the `hello` handshake above with `label = "video"`, sends its first frame (`{"t":"ready"}`).
2. Mac then sends frames: encrypted Annex-B H.264 access units (see Frames). Every keyframe carries SPS+PPS; a keyframe is forced
   when a viewer becomes ready.
3. Encoding: ScreenCaptureKit -> VideoToolbox H.264 Main, display aspect, even dimensions, 30 fps max (idle screens send nothing),
   no B-frames, keyframe every 2 s. **Standard**: <= 1280 px wide, ~4 Mbps. **Sharp**: <= 2560 px wide (never above native pixels),
   ~10 Mbps. A viewer that falls behind has frames dropped until its next keyframe.
4. Switching display/quality changes the resolution at the next keyframe (new SPS); decoders must handle a format change.

## Testing

`pip install cryptography`, then see `tools/test_client.py` (pairing, input, negative security tests) and `tools/test_stream.py`.
