#!/usr/bin/env python3
"""Smoke-test the PadKey Mac agent (protocol v2).

  python3 tools/test_client.py [host] [port] --pair 123456   pair this test client with the PIN shown by the Mac
  python3 tools/test_client.py [host] [port]                 input smoke test (needs a prior pairing)
  python3 tools/test_client.py [host] [port] --security      negative tests: unpaired / tampered / replayed are refused
  add --media to also press Play/Pause. Needs: pip install cryptography
The test phone's id + secret live in ~/.config/padkey-test/phone.json (0600)."""
import json, os, socket, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from padkey_proto import Phone, VERSION, b64, read_line, send_line

args = [a for a in sys.argv[1:] if not a.startswith("--")]
host = args[0] if len(args) > 0 else "127.0.0.1"
port = int(args[1]) if len(args) > 1 else 50505
opt = lambda n: sys.argv[sys.argv.index(n) + 1] if n in sys.argv else None
state = os.environ.get("PADKEY_TEST_STATE", os.path.expanduser("~/.config/padkey-test/phone.json"))
phone = Phone(state)


def check(name, ok):
    print(("PASS " if ok else "FAIL ") + name)
    if not ok:
        sys.exit(1)


if opt("--pair"):
    mid = phone.pair(host, port, opt("--pair"))
    print("paired with Mac", mid)
    sys.exit(0)

if "--security" in sys.argv:
    # 1. a client the Mac does not know is refused (control port)
    s = socket.create_connection((host, port), timeout=5)
    send_line(s, {"t": "hello", "v": VERSION, "id": "00" * 16, "n": b64(os.urandom(16))})
    r = read_line(s.makefile("rb"))
    check("unknown phone id refused on control port", r["t"] == "error" and r.get("code") == "not_paired")
    # 2. unpaired client cannot send input (plaintext move is ignored; connection gets no pong)
    s = socket.create_connection((host, port), timeout=5)
    s.sendall(b'{"t":"ping","id":1}\n{"t":"move","dx":50,"dy":50}\n')
    s.settimeout(1.5)
    try:
        d = s.recv(100)
    except socket.timeout:
        d = b""
    check("plaintext ping/move without hello gets no reply", d == b"")
    # 3. video port refuses unknown ids
    v = socket.create_connection((host, 50506), timeout=5)
    send_line(v, {"t": "hello", "v": VERSION, "id": "00" * 16, "n": b64(os.urandom(16))})
    r = read_line(v.makefile("rb"))
    check("unknown phone id refused on video port", r["t"] == "error")
    # 4. pairing without an open PIN window / with a wrong PIN fails
    try:
        Phone("/tmp/padkey-test-throwaway.json").pair(host, port, "000000")
        check("wrong PIN rejected", False)
    except RuntimeError as e:
        check("pairing with a wrong PIN / closed window rejected (%s)" % e, True)
    os.path.exists("/tmp/padkey-test-throwaway.json") and os.remove("/tmp/padkey-test-throwaway.json")
    if not phone.macs:
        print("(skipping tamper/replay tests: not paired yet)")
        sys.exit(0)
    # 5. tampered frame closes the connection
    s, f, ch, r = phone.hello(host, port)
    s.sendall(ch.seal(json.dumps({"t": "ping", "id": 1}).encode(), tamper=True))
    s.settimeout(2)
    check("tampered frame -> connection closed, no pong", s.recv(100) == b"")
    # 6. replayed frame (counter reuse) closes the connection
    s, f, ch, r = phone.hello(host, port)
    frame = ch.seal(json.dumps({"t": "ping", "id": 2}).encode())
    s.sendall(frame)
    pong = ch.read_frame(f)
    check("valid frame answered", json.loads(pong)["t"] == "pong")
    s.sendall(frame)  # same bytes again
    s.settimeout(3)
    closed, got_pong = False, 0
    try:
        while True:  # drain what the Mac still had queued (e.g. caps); the replay must not produce a second pong
            m = json.loads(ch.read_frame(f))
            got_pong += m["t"] == "pong"
    except Exception:
        closed = True
    check("replayed frame -> connection closed, no second pong", closed and got_pong == 0)
    print("OK: security checks passed")
    sys.exit(0)

# ---- functional smoke test ----
s, f, ch, r = phone.hello(host, port)
print("hello ->", r.get("name"), "v", r.get("v"))
send = lambda **m: s.sendall(ch.seal(json.dumps(m).encode()))
recv = lambda: json.loads(ch.read_frame(f))

send(t="ping", id=7)
m = recv()
while m["t"] != "pong":  # a `caps` message may arrive first
    m = recv()
print("ping ->", m)
check("pong id echoed", m["id"] == 7)
for _ in range(20):
    send(t="move", dx=1.5, dy=0); time.sleep(0.008)
for _ in range(20):
    send(t="move", dx=-1.5, dy=0); time.sleep(0.008)
send(t="scroll", dx=0, dy=3.5)
send(t="scroll", dx=0, dy=-3.5)
send(t="key", c=105, d=True); time.sleep(0.6); send(t="key", c=105, d=False)  # F13, held -> autorepeat
send(t="media", k="play") if "--media" in sys.argv else None
send(t="text", s="padkey-test é\U0001F600 " * 3, id=1001)
while True:
    m = recv()
    if m["t"] == "ack":
        break
check("text acked", m["id"] == 1001)
send(t="text", s="dup", id=1001)  # a resend of an id the Mac already handled is acked, not typed again
check("duplicate id re-acked", next(x for x in iter(recv, None) if x["t"] == "ack")["id"] == 1001)
send(t="paste", s="padkey-paste-test", id=1002)
while True:
    m = recv()
    if m["t"] == "ack":
        break
check("paste acked", m["id"] == 1002)
send(t="btn", b="left", d=True); send(t="move", dx=2, dy=2); send(t="btn", b="left", d=False)
send(t="click", b="left", n=2)
send(t="garbage-not-known")
s.sendall(ch.seal(b"not json at all"))
send(t="ping", id=8)
while True:
    m = recv()
    if m["t"] == "pong":
        break
check("agent survived all messages (pong id 8)", m["id"] == 8)
s.close()
print("OK")
