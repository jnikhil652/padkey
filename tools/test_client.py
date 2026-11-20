#!/usr/bin/env python3
"""Smoke-test the PadKey Mac agent: python3 tools/test_client.py [host] [port] [token]"""
import json, socket, sys, time

host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
port = int(sys.argv[2]) if len(sys.argv) > 2 else 50505
token = sys.argv[3] if len(sys.argv) > 3 else ""

s = socket.create_connection((host, port), timeout=3)
s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
f = s.makefile("rwb", buffering=0)

def send(**m):
    f.write((json.dumps(m) + "\n").encode())

def recv():
    return json.loads(f.readline())

send(t="hello", token=token, v=1)
print("hello ->", recv())
send(t="ping", id=7)
print("ping ->", recv())
for _ in range(20):
    send(t="move", dx=1.5, dy=0); time.sleep(0.008)
for _ in range(20):
    send(t="move", dx=-1.5, dy=0); time.sleep(0.008)
send(t="scroll", dx=0, dy=3.5)
send(t="scroll", dx=0, dy=-3.5)
send(t="key", c=105, d=True); time.sleep(0.6); send(t="key", c=105, d=False)  # F13, held -> autorepeat
send(t="media", k="play") if "--media" in sys.argv else None
send(t="text", s="padkey-test é\U0001F600")
send(t="btn", b="left", d=True); send(t="move", dx=2, dy=2); send(t="btn", b="left", d=False)
send(t="click", b="left", n=2)
send(t="garbage-not-json")
f.write(b"not json at all\n")
send(t="ping", id=8)
print("ping2 ->", recv())
s.close()
print("OK: agent survived all messages")
