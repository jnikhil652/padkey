"""Minimal PadKey protocol v2 client (pairing, session keys, AES-GCM frames) for the test scripts.
Needs: pip install cryptography. See ../PROTOCOL.md."""
import base64, hashlib, hmac, json, os, socket, struct

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

VERSION = 2
b64 = lambda b: base64.b64encode(b).decode()
unb64 = lambda s: base64.b64decode(s)


def hkdf(ikm, salt, info, n=32):
    return HKDF(algorithm=hashes.SHA256(), length=n, salt=salt, info=info.encode()).derive(ikm)


def hmac256(key, msg):
    return hmac.new(key, msg, hashlib.sha256).digest()


def read_line(sock_file):
    line = sock_file.readline()
    if not line:
        raise ConnectionError("closed")
    return json.loads(line)


def send_line(sock, obj):
    sock.sendall((json.dumps(obj) + "\n").encode())


class Channel:
    """Phone side: sends with the p2m key, receives with the m2p key."""

    def __init__(self, secret, nc, ns, label):
        okm = hkdf(secret, nc + ns, "padkey v2 keys " + label, 64)
        self.send_key, self.recv_key = AESGCM(okm[:32]), AESGCM(okm[32:])
        self.sctr = self.rctr = 0

    @staticmethod
    def _nonce(c):
        return b"\0\0\0\0" + struct.pack(">Q", c)

    def seal(self, plain, tamper=False):
        hdr = struct.pack(">I", len(plain) + 16)
        ct = self.send_key.encrypt(self._nonce(self.sctr), plain, hdr)
        self.sctr += 1
        if tamper:
            ct = ct[:-1] + bytes([ct[-1] ^ 1])
        return hdr + ct

    def read_frame(self, sock_file, max_plain=8_000_000):
        hdr = _read_exact(sock_file, 4)
        n = struct.unpack(">I", hdr)[0]
        if n < 16 or n > max_plain + 16:
            raise ValueError("bad frame length %d" % n)
        body = _read_exact(sock_file, n)
        plain = self.recv_key.decrypt(self._nonce(self.rctr), body, hdr)
        self.rctr += 1
        return plain


def _read_exact(f, n):
    b = f.read(n)
    if b is None or len(b) < n:
        raise ConnectionError("closed")
    return b


class Phone:
    """A fake phone: holds an id and per-Mac secrets in a 0600 json file."""

    def __init__(self, path):
        self.path = path
        try:
            d = json.load(open(path))
        except Exception:
            d = {"id": os.urandom(16).hex(), "macs": {}}
        self.id, self.macs = d["id"], d["macs"]

    def save(self):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump({"id": self.id, "macs": self.macs}, f)

    def pair(self, host, port, pin, name="padkey-test"):
        s = socket.create_connection((host, port), timeout=5)
        f = s.makefile("rb")
        priv = ec.generate_private_key(ec.SECP256R1())
        phone_pub = priv.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
        send_line(s, {"t": "pair", "v": VERSION, "id": self.id, "name": name, "pub": b64(phone_pub)})
        r = read_line(f)
        if r["t"] == "error":
            raise RuntimeError(r["msg"])
        mac_pub = unb64(r["pub"])
        shared = priv.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), mac_pub))
        salt = hashlib.sha256(phone_pub + mac_pub).digest()
        k = hkdf(shared + pin.encode(), salt, "padkey v2 pair")
        confirm, wrap = hkdf(k, salt, "padkey v2 pair confirm"), hkdf(k, salt, "padkey v2 pair wrap")
        transcript = b"padkey v2 pair" + phone_pub + mac_pub
        send_line(s, {"t": "pair_confirm", "m": b64(hmac256(confirm, b"phone" + transcript))})
        r = read_line(f)
        if r["t"] == "error":
            raise RuntimeError(r["msg"])
        if not hmac.compare_digest(unb64(r["m"]), hmac256(confirm, b"mac" + transcript)):
            raise RuntimeError("Mac confirmation MAC invalid")
        secret = AESGCM(wrap).decrypt(unb64(r["iv"]), unb64(r["ct"]), self.id.encode())
        self.macs[r["mid"]] = b64(secret)
        self.save()
        s.close()
        return r["mid"]

    def hello(self, host, port, label="ctl", mid=None):
        """Returns (socket, file, Channel, reply). Raises RuntimeError('not paired') etc. on refusal."""
        s = socket.create_connection((host, port), timeout=5)
        f = s.makefile("rb")
        nc = os.urandom(16)
        send_line(s, {"t": "hello", "v": VERSION, "id": self.id, "n": b64(nc)})
        r = read_line(f)
        if r["t"] == "error":
            s.close()
            raise RuntimeError(r["msg"])
        secret = unb64(self.macs[r.get("mid") if mid is None else mid])
        ns = unb64(r["n"])
        want = hmac256(secret, ("padkey v2 mac " + label).encode() + nc + ns)
        if not hmac.compare_digest(unb64(r["auth"]), want):
            raise RuntimeError("Mac auth invalid")
        return s, f, Channel(secret, nc, ns, label), r
