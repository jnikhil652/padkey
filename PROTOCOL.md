# PadKey protocol (v1)

Transport: plain TCP, port **50505**, `TCP_NODELAY`. Bonjour/mDNS service type `_padkey._tcp`.
Framing: one JSON object per line (`\n`-terminated, UTF-8). Unknown messages/fields are ignored.
Every message has a type field `t`. Numbers may be floats.

## Handshake

Phone -> Mac (first message):

    {"t":"hello","v":1,"token":"..."}

Mac -> Phone:

    {"t":"hello","ok":true,"name":"My MacBook Pro","v":1}

If the Mac has a token (file `~/.config/padkey/token`, one line) and it does not match, the Mac replies
`{"t":"error","msg":"bad token"}` and closes. With no token file, hello is optional and everything is accepted
(the phone still sends it). Until authenticated, only `hello` and `ping` are handled.

## Keep-alive

Phone sends `{"t":"ping","id":<number>}` about every 2.5 s; Mac answers `{"t":"pong","id":<same>}`.
The phone uses `id` = epoch millis to show latency. The Mac drops a connection silent for 12 s and releases
every held key and button; the phone reconnects after 8 s of silence.

## Input messages (Phone -> Mac)

| Message | Fields | Meaning |
|---|---|---|
| `move` | `dx`, `dy` (points) | Relative pointer move, already accelerated by the phone. Clamped to the union of displays. Becomes a drag event while a button is down. |
| `scroll` | `dx`, `dy` (pixels) | Smooth pixel scroll. Positive `dy` = content moves down (finger-follows direction; the phone inverts for non-natural scrolling). Fractions are accumulated. |
| `btn` | `b` = `left`/`right`/`middle`, `d` = true/false, `n` (click state, default 1) | Raw button down/up (used for drag). |
| `click` | `b`, `n` | One down+up pair with click state `n`. `n` = 2 is the second click of a double click, 3 a triple click. |
| `key` | `c` (macOS virtual keycode, kVK_*), `d` = true/false, `f` (flags, optional) | Key down/up. Mac auto-repeats a held non-modifier key (400 ms, then every 35 ms) until the up arrives. |
| `text` | `s` | Type a unicode string (chunks of <=16 UTF-16 units via CGEventKeyboardSetUnicodeString; `\n` presses Return, `\t` Tab). |
| `media` | `k` | System key press: `volup voldown mute brightup brightdown play next prev fast rewind kbup kbdown`. |
| `release` | - | Release all held keys/buttons. |

### Modifiers

Modifier keycodes (Cmd 55, Shift 56, Option 58, Ctrl 59, Fn 63; right-hand 54/60/61/62) sent as `key` down/up
are tracked by the Mac as held state and applied to every following keyboard and mouse event, so
`key 55 down, key 8 down, key 8 up, key 55 up` = Cmd+C, and holding Cmd while sending `click` gives Cmd+click.

The optional `f` bitmask adds flags to a single event only:
shift = 1, ctrl = 2, alt/option = 4, cmd = 8, fn = 16, caps = 32.
(The phone uses `f=1` for shifted symbols such as `!` = key 18 with `f=1`.)

## Examples

    {"t":"move","dx":12.5,"dy":-3}
    {"t":"scroll","dx":0,"dy":18.2}
    {"t":"click","b":"left","n":1}
    {"t":"btn","b":"left","d":true}
    {"t":"key","c":0,"d":true,"f":0}
    {"t":"text","s":"hello é"}
    {"t":"media","k":"volup"}

## Screen mirror (v1 extension)

Control messages stay on port 50505; the video goes on a **separate TCP port 50506** so input latency is unaffected.

### Control

| Message | Direction | Fields | Meaning |
|---|---|---|---|
| `screen` | phone -> Mac | `on` (bool), `display` (index, optional) | Start/stop mirroring; `display` switches the mirrored display (index into `screeninfo.displays`, main display = 0). Capture only runs while at least one phone has `on` and a video viewer is connected. First use triggers the macOS Screen Recording prompt. |
| `moveto` | phone -> Mac | `x`, `y` in 0..1, optional `f` | Absolute pointer move on the mirrored display (normalized, top-left origin). Follow with `click` for a tap. |
| `screeninfo` | Mac -> phone | `w`,`h` (display size in points), `sel` (selected index), `displays` = `[{"id":0,"name":"..."}]`, `ok` (capture usable), `perm` (Screen Recording granted) | Sent after `screen on` and whenever capture starts/fails. |
| `cursor` | Mac -> phone | `x`,`y` (normalized to the mirrored display; can be outside 0..1 on other displays) | Pointer position, ~15 Hz while mirroring and only when it moved. |

### Video stream (port 50506)

1. Viewer connects and sends one line `{"t":"hello","token":"..."}` + `\n` (token only checked if the Mac has one; must arrive within 5 s).
2. Mac then sends frames: **4-byte big-endian length + one Annex-B H.264 access unit** (start codes `00 00 00 01`).
   Every keyframe carries SPS+PPS in front; a new viewer receives nothing until the next keyframe (a keyframe is forced on connect).
3. Encoding: ScreenCaptureKit -> VideoToolbox H.264 Main, <= 1280 px wide (display aspect, even dimensions), 30 fps max (idle screens send nothing),
   ~4 Mbps, no B-frames, keyframe every 2 s. A viewer that falls behind has frames dropped until its next keyframe.
4. Switching display changes the resolution at the next keyframe (new SPS); decoders must handle a format change.

Test: `python3 tools/test_stream.py [host] [token] [out.h264]` (writes raw Annex-B, playable with `ffplay out.h264`).
