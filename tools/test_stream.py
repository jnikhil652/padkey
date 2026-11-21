#!/usr/bin/env python3
"""Screen-mirror smoke test (protocol v2): python3 tools/test_stream.py [host] [out.h264] [--quality 1]
Needs a prior pairing (tools/test_client.py --pair PIN). Sends `screen on`, reads the encrypted H.264 stream on 50506
for ~4 s, decrypts it, prints stats and optionally writes raw Annex-B (play with: ffplay out.h264)."""
import json, os, socket, struct, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from padkey_proto import Phone

args = [a for a in sys.argv[1:] if not a.startswith("--") and not (sys.argv[sys.argv.index(a) - 1] == "--quality")]
host = args[0] if args else "127.0.0.1"
out = open(args[1], "wb") if len(args) > 1 else None
quality = int(sys.argv[sys.argv.index("--quality") + 1]) if "--quality" in sys.argv else 0
phone = Phone(os.environ.get("PADKEY_TEST_STATE", os.path.expanduser("~/.config/padkey-test/phone.json")))

c, cf, cch, r = phone.hello(host, 50505)
send = lambda **m: c.sendall(cch.seal(json.dumps(m).encode()))
print("hello ->", r.get("name"))
send(t="ping", id=1)
send(t="screen", on=True, display=0, q=quality)
while True:
    m = json.loads(cch.read_frame(cf))
    if m["t"] == "screeninfo":
        print("screeninfo ->", {k: m[k] for k in ("w", "h", "ok", "perm", "reason") if k in m})
        break

v, vf, vch, _ = phone.hello(host, 50506, label="video", mid=r["mid"])
v.sendall(vch.seal(b'{"t":"ready"}'))
frames = keys = nbytes = 0; t0 = time.time(); first = None; size = None
v.settimeout(6)  # max wait for the next frame (an idle screen sends nothing between keyframes)
while time.time() - t0 < 4:
    try:
        au = vch.read_frame(vf)
    except (OSError, ConnectionError):  # timeout or closed
        break
    if au[:4] != b"\x00\x00\x00\x01":
        print("BAD start code"); sys.exit(1)
    if first is None: first = time.time() - t0
    frames += 1; nbytes += len(au)
    if (au[4] & 0x1f) in (7, 5): keys += 1
    if out: out.write(au)
print(f"frames={frames} keyframes={keys} bytes={nbytes} first_frame_after={first} s (quality {quality})")
send(t="screen", on=False)
sys.exit(0 if frames else 2)
