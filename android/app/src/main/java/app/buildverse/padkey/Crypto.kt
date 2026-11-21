package app.buildverse.padkey

import android.util.Base64
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** PadKey protocol v2 crypto (see PROTOCOL.md): PIN-bound ECDH pairing, per-connection keys, AES-256-GCM frames. */
object PK {
    const val VERSION = 2
    private val rng = SecureRandom()

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)
    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun hmac(key: ByteArray, msg: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(msg)

    fun eq(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, len: Int = 32): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < len) {
            t = hmac(prk, t + info.toByteArray() + byteArrayOf(i.toByte()))
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n; i++
        }
        return out
    }

    /** Mac's proof that it holds the long-term secret. */
    fun macAuth(secret: ByteArray, label: String, nc: ByteArray, ns: ByteArray) =
        hmac(secret, "padkey v2 mac $label".toByteArray() + nc + ns)

    // ---- pairing ----

    private val SPKI_P256 = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
        0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    )

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** Uncompressed X9.63 point: 04 || X || Y (65 bytes), the same encoding CryptoKit uses. */
    fun encodePub(k: PublicKey): ByteArray {
        val w = (k as ECPublicKey).w
        fun fixed(v: BigInteger): ByteArray {
            val raw = v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - raw.size) + raw
        }
        return byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY)
    }

    fun decodePub(b: ByteArray): PublicKey {
        require(b.size == 65 && b[0] == 4.toByte()) { "bad public key" }
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(SPKI_P256 + b))
    }

    fun ecdh(priv: KeyPair, macPub: ByteArray): ByteArray =
        KeyAgreement.getInstance("ECDH").apply { init(priv.private); doPhase(decodePub(macPub), true) }.generateSecret()

    class PairKeys(val confirmKey: ByteArray, val wrapKey: ByteArray, val transcript: ByteArray) {
        fun phoneConfirm() = hmac(confirmKey, "phone".toByteArray() + transcript)
        fun macConfirm() = hmac(confirmKey, "mac".toByteArray() + transcript)
    }

    /** K = HKDF(ECDH shared secret || PIN, salt = SHA256(phonePub || macPub)). */
    fun pairKeys(shared: ByteArray, pin: String, phonePub: ByteArray, macPub: ByteArray): PairKeys {
        val salt = MessageDigest.getInstance("SHA-256").digest(phonePub + macPub)
        val k = hkdf(shared + pin.toByteArray(), salt, "padkey v2 pair")
        return PairKeys(hkdf(k, salt, "padkey v2 pair confirm"), hkdf(k, salt, "padkey v2 pair wrap"),
            "padkey v2 pair".toByteArray() + phonePub + macPub)
    }

    /** Opens the long-term secret the Mac sent in `pair_ok` (AES-GCM, AAD = phone id). */
    fun unwrap(wrapKey: ByteArray, iv: ByteArray, ctTag: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, iv))
            updateAAD(aad)
        }.doFinal(ctTag)

    // ---- handshake line reader ----

    /** Reads one newline-terminated line byte by byte (no read-ahead, so the stream can switch to frames afterwards). */
    fun readLine(inp: InputStream): String {
        val sb = java.io.ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0) throw IOException("closed")
            if (c == 10) break
            sb.write(c)
            if (sb.size() > 4096) throw IOException("handshake line too long")
        }
        return sb.toString("UTF-8")
    }
}

/** Per-connection AES-256-GCM frames. The phone is the client: it sends with the p2m key, receives with m2p. */
class Channel(secret: ByteArray, nc: ByteArray, ns: ByteArray, label: String) {
    private val sendKey: SecretKeySpec
    private val recvKey: SecretKeySpec
    private var sendCtr = 0L
    private var recvCtr = 0L

    init {
        val okm = PK.hkdf(secret, nc + ns, "padkey v2 keys $label", 64)
        sendKey = SecretKeySpec(okm.copyOfRange(0, 32), "AES")
        recvKey = SecretKeySpec(okm.copyOfRange(32, 64), "AES")
    }

    private fun nonce(c: Long) = ByteBuffer.allocate(12).putInt(0).putLong(c).array()

    /** length | ciphertext | tag, ready to write. */
    fun seal(plain: ByteArray): ByteArray {
        val hdr = ByteBuffer.allocate(4).putInt(plain.size + 16).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, nonce(sendCtr)))
        c.updateAAD(hdr)
        sendCtr++
        return hdr + c.doFinal(plain)
    }

    /** `body` = ciphertext || tag. Throws on forgery / replay / reordering. */
    fun open(hdr: ByteArray, body: ByteArray, len: Int): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, recvKey, GCMParameterSpec(128, nonce(recvCtr)))
        c.updateAAD(hdr)
        val out = c.doFinal(body, 0, len)
        recvCtr++
        return out
    }
}

/** Reads and decrypts one frame (4-byte big-endian length | ciphertext | tag). */
fun Channel.readFrame(inp: java.io.DataInputStream, maxPlain: Int): ByteArray {
    val n = inp.readInt()
    if (n < 16 || n > maxPlain + 16) throw IOException("bad frame length $n")
    val body = ByteArray(n)
    inp.readFully(body)
    return open(ByteBuffer.allocate(4).putInt(n).array(), body, n)
}
