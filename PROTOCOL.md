# PadKey protocol (v1)

Transport: plain TCP, port **50505**, `TCP_NODELAY`. Bonjour/mDNS service type `_padkey._tcp`.
Framing: one JSON object per line (`\n`-terminated, UTF-8). Unknown messages/fields are ignored.
Every message has a type field `t`. Numbers may be floats.

## Handshake

Phone -> Mac (first message):

    {"t":"hello","v":1,"token":"..."}

Mac -> Phone:

    {"t":"hello","ok":true,"name":"Nik's MacBook Pro","v":1}

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
