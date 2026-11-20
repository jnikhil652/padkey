#!/usr/bin/env python3
"""Screen-mirror smoke test: python3 tools/test_stream.py [host] [token] [out.h264]
Sends `screen on`, reads the H.264 stream on 50506 for ~4 s, prints stats, optionally writes the raw Annex-B file
(play with: ffplay out.h264)."""
import json, socket, struct, sys, time

host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
token = sys.argv[2] if len(sys.argv) > 2 else ""
out = open(sys.argv[3], "wb") if len(sys.argv) > 3 else None

c = socket.create_connection((host, 50505), timeout=3)
cf = c.makefile("rwb", buffering=0)
send = lambda **m: cf.write((json.dumps(m) + "\n").encode())
send(t="hello", token=token, v=1); print("hello ->", cf.readline().decode().strip())
send(t="screen", on=True, display=0)
print("screeninfo ->", cf.readline().decode().strip())

v = socket.create_connection((host, 50506), timeout=5)
v.sendall((json.dumps({"t": "hello", "token": token}) + "\n").encode())
buf = b""; frames = keys = nbytes = 0; t0 = time.time(); first = None
v.settimeout(1)
while time.time() - t0 < 4:
    try:
        d = v.recv(65536)
    except socket.timeout:
        continue
    if not d: break
    buf += d
    while len(buf) >= 4:
        n = struct.unpack(">I", buf[:4])[0]
        if len(buf) < 4 + n: break
        au = buf[4:4 + n]; buf = buf[4 + n:]
        if au[:4] != b"\x00\x00\x00\x01": print("BAD start code"); sys.exit(1)
        if first is None: first = time.time() - t0
        frames += 1; nbytes += n
        if (au[4] & 0x1f) in (7, 5): keys += 1
        if out: out.write(au)
print(f"frames={frames} keyframes={keys} bytes={nbytes} first_frame_after={first} s")
send(t="screen", on=False)
sys.exit(0 if frames else 2)
