package app.buildverse.padkey

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class Mac(val name: String, val ip: String, val port: Int)

enum class LinkState { Idle, Connecting, Connected }

/** Connection to the Mac agent: discovery, auto-reconnect and the message writers. */
object PadClient {
    var state by mutableStateOf(LinkState.Idle)
    var macName by mutableStateOf("")
    var message by mutableStateOf("")
    var latency by mutableStateOf(-1)
    val found = mutableStateListOf<Mac>()

    // Screen mirror state (from `screeninfo` / `cursor`)
    var sessionId by mutableStateOf(0)            // bumps on every (re)connect so the video socket reconnects too
    var screenW by mutableStateOf(0)
    var screenH by mutableStateOf(0)
    var screenOk by mutableStateOf(true)          // false: Mac has no Screen Recording permission / capture failed
    var screenReason by mutableStateOf("ok")      // ok | no_permission | needs_relaunch | failed
    var capsOn by mutableStateOf(false)           // Mac Caps Lock state
    var needPin by mutableStateOf(false)          // the Mac does not know this phone: ask for the pairing PIN
    var pairBusy by mutableStateOf(false)
    var pairError by mutableStateOf("")
    @Volatile var pairHost = ""
    @Volatile var pairPort = 50505
    @Volatile var sessionSecret: ByteArray? = null  // long-term secret of the Mac behind the current session (video port needs it)
    var displays by mutableStateOf(listOf<Pair<Int, String>>())
    var cursorX by mutableStateOf(-1f)
    var cursorY by mutableStateOf(-1f)
    @Volatile var sessionHost = ""

    @Volatile private var socket: Socket? = null
    @Volatile private var autoConnect = true
    @Volatile private var failCount = 0
    @Volatile private var lastRx = 0L
    private val q = LinkedBlockingQueue<String>()
    private val lock = Any()
    private var pdx = 0f
    private var pdy = 0f
    private var psx = 0f
    private var psy = 0f
    private var started = false
    private val combos = Executors.newSingleThreadExecutor()

    val connected get() = state == LinkState.Connected

    fun start(ctx: Context) {
        if (started) return
        started = true
        Outbox.init(ctx.applicationContext)
        startDiscovery(ctx.applicationContext)
        thread(isDaemon = true, name = "padkey-supervisor") {
            while (true) {
                try {
                    if (state == LinkState.Idle && autoConnect) {
                        var h = Prefs.host
                        val f = found.toList()
                        if (h.isBlank()) h = f.firstOrNull()?.ip ?: ""
                        else if (failCount >= 2 && f.isNotEmpty() && f.none { it.ip == h }) {
                            // Saved IP keeps failing and Bonjour sees a Mac elsewhere (DHCP change).
                            h = f.first().ip; Prefs.host = h; Prefs.save(); failCount = 0
                        }
                        if (h.isNotBlank()) session(h, f.firstOrNull { it.ip == h }?.port ?: Prefs.port)
                        else message = "Searching for Mac..."
                    }
                } catch (_: Throwable) { }
                Thread.sleep(1500)
            }
        }
    }

    fun connect(host: String, port: Int) {
        Prefs.host = host; Prefs.port = port; Prefs.save()
        autoConnect = true
        failCount = 0
        message = ""
        needPin = false
        try { socket?.close() } catch (_: Exception) { }
    }

    /** Ask for the PIN of the Mac at [host] (also reachable from the Connect dialog). */
    fun beginPairing(host: String, port: Int) {
        pairHost = host; pairPort = port; pairError = ""; needPin = true
    }

    fun cancelPairing() { needPin = false; pairError = "" }

    /** PIN pairing (ECDH P-256 bound to the PIN, see PROTOCOL.md). On success the long-term secret is stored and the session starts. */
    fun pair(pin: String) {
        if (pairBusy) return
        pairBusy = true; pairError = ""
        val host = pairHost; val port = pairPort
        thread(isDaemon = true, name = "padkey-pair") {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 3000)
                s.soTimeout = 8000
                val inp = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                fun write(o: JSONObject) { out.write((o.toString() + "\n").toByteArray()); out.flush() }
                fun read(): JSONObject {
                    val o = JSONObject(PK.readLine(inp))
                    if (o.optString("t") == "error") throw IllegalStateException(o.optString("msg", "error"))
                    return o
                }
                val kp = PK.newKeyPair()
                val phonePub = PK.encodePub(kp.public)
                write(JSONObject().put("t", "pair").put("v", PK.VERSION).put("id", Prefs.phoneId)
                    .put("name", Build.MODEL ?: "Android").put("pub", PK.b64(phonePub)))
                val macPub = PK.unb64(read().getString("pub"))
                val keys = PK.pairKeys(PK.ecdh(kp, macPub), pin, phonePub, macPub)
                write(JSONObject().put("t", "pair_confirm").put("m", PK.b64(keys.phoneConfirm())))
                val ok = read()
                if (!PK.eq(PK.unb64(ok.getString("m")), keys.macConfirm())) throw IllegalStateException("Mac did not prove the PIN")
                val secret = PK.unwrap(keys.wrapKey, PK.unb64(ok.getString("iv")), PK.unb64(ok.getString("ct")), Prefs.phoneId.toByteArray())
                Prefs.addPairing(Paired(ok.getString("mid"), ok.optString("name", "Mac"), PK.b64(secret)))
                Prefs.host = host; Prefs.port = port; Prefs.save()
                needPin = false; autoConnect = true; failCount = 0; message = ""
            } catch (e: Exception) {
                pairError = when (e) {
                    is java.net.SocketTimeoutException -> "Timed out"
                    is java.io.IOException -> "Could not reach the Mac (${e.message})"
                    else -> e.message ?: "Pairing failed"
                }
            } finally {
                try { s.close() } catch (_: Exception) { }
                pairBusy = false
            }
        }
    }

    fun disconnect() {
        autoConnect = false
        message = "Disconnected"
        try { socket?.close() } catch (_: Exception) { }
    }

    // ---------- session ----------

    private fun session(host: String, port: Int) {
        state = LinkState.Connecting
        message = "Connecting to $host..."
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(host, port), 2500)
        } catch (e: Exception) {
            failCount++
            message = "Can't reach $host:$port"
            state = LinkState.Idle
            try { s.close() } catch (_: Exception) { }
            return
        }
        socket = s
        sessionHost = host
        lastRx = System.currentTimeMillis()
        q.clear()
        synchronized(lock) { pdx = 0f; pdy = 0f; psx = 0f; psy = 0f }
        val out = s.getOutputStream()
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        val sessionAlive = { !s.isClosed }

        // ---- handshake: plaintext hello, then everything is AES-GCM frames ----
        val ch: Channel
        try {
            s.soTimeout = 6000
            val nc = PK.random(16)
            out.write((JSONObject().put("t", "hello").put("v", PK.VERSION).put("id", Prefs.phoneId)
                .put("n", PK.b64(nc)).toString() + "\n").toByteArray())
            out.flush()
            val reply = JSONObject(PK.readLine(inp))
            if (reply.optString("t") == "error") {
                if (reply.optString("code") == "not_paired") {
                    message = "Not paired with this Mac: enter the PIN"
                    autoConnect = false
                    beginPairing(host, port)
                } else {
                    message = "Mac: " + reply.optString("msg")
                    autoConnect = false
                }
                throw java.io.IOException("refused")
            }
            val paired = Prefs.pairings.firstOrNull { it.mid == reply.optString("mid") }
            val secret = paired?.let { PK.unb64(it.secret) }
            val ns = PK.unb64(reply.getString("n"))
            if (secret == null || !PK.eq(PK.unb64(reply.getString("auth")), PK.macAuth(secret, "ctl", nc, ns))) {
                message = "Could not verify this Mac: pair again"
                autoConnect = false
                beginPairing(host, port)
                throw java.io.IOException("unverified")
            }
            ch = Channel(secret, nc, ns, "ctl")
            sessionSecret = secret
            macName = reply.optString("name", "Mac")
            s.soTimeout = 0
        } catch (_: Exception) {
            try { s.close() } catch (_: Exception) { }
            if (state != LinkState.Connected) failCount++
            state = LinkState.Idle
            if (message.startsWith("Connecting")) message = "Connection lost, retrying..."
            return
        }
        Mods.reset()
        state = LinkState.Connected
        message = ""
        failCount = 0
        sessionId++
        q.add("{\"t\":\"ping\",\"id\":${System.currentTimeMillis()}}")   // first frame authenticates us to the Mac

        thread(isDaemon = true, name = "padkey-writer") {
            try {
                while (sessionAlive()) {
                    val m = q.poll(5, TimeUnit.MILLISECONDS)
                    if (m == null) { synchronized(lock) { flushPending() }; continue }
                    out.write(ch.seal(m.toByteArray())); out.flush()
                }
            } catch (_: Exception) { try { s.close() } catch (_: Exception) { } }
        }
        thread(isDaemon = true, name = "padkey-ping") {
            try {
                while (sessionAlive()) {
                    Thread.sleep(2500)
                    if (System.currentTimeMillis() - lastRx > 8000) { s.close(); break }
                    q.add("{\"t\":\"ping\",\"id\":${System.currentTimeMillis()}}")
                }
            } catch (_: Exception) { }
        }
        if (Prefs.screen) screen(true, Prefs.display)
        Outbox.onConnected()
        try {
            while (true) {
                val o = try { JSONObject(String(ch.readFrame(inp, 1_000_000))) } catch (e: org.json.JSONException) { continue }
                lastRx = System.currentTimeMillis()
                when (o.optString("t")) {
                    "screeninfo" -> {
                        screenW = o.optInt("w"); screenH = o.optInt("h")
                        screenOk = o.optBoolean("ok", true)
                        screenReason = o.optString("reason", if (screenOk) "ok" else "no_permission")
                        val arr = o.optJSONArray("displays")
                        if (arr != null) displays = (0 until arr.length()).map { arr.getJSONObject(it).let { d -> d.optInt("id") to d.optString("name") } }
                    }
                    "cursor" -> { cursorX = o.optDouble("x").toFloat(); cursorY = o.optDouble("y").toFloat() }
                    "pong" -> latency = (System.currentTimeMillis() - o.optLong("id")).toInt().coerceAtLeast(0)
                    "caps" -> capsOn = o.optBoolean("on")
                    "ack" -> Outbox.ack(o.optLong("id"))
                    "error" -> { message = "Mac: " + o.optString("msg"); autoConnect = false }
                }
            }
        } catch (_: Exception) { }
        try { s.close() } catch (_: Exception) { }
        state = LinkState.Idle
        macName = ""
        latency = -1
        capsOn = false
        sessionSecret = null
        Mods.reset()
        Outbox.onDisconnected()
        if (autoConnect && message.isEmpty()) message = "Connection lost, retrying..."
    }

    // ---------- sending ----------

    private fun fmt(f: Float) = String.format(Locale.US, "%.2f", f)

    private fun flushPending() {
        if (pdx != 0f || pdy != 0f) {
            q.add("{\"t\":\"move\",\"dx\":${fmt(pdx)},\"dy\":${fmt(pdy)}}"); pdx = 0f; pdy = 0f
        }
        if (psx != 0f || psy != 0f) {
            q.add("{\"t\":\"scroll\",\"dx\":${fmt(psx)},\"dy\":${fmt(psy)}}"); psx = 0f; psy = 0f
        }
    }

    fun move(dx: Float, dy: Float) {
        if (!connected) return
        synchronized(lock) { pdx += dx; pdy += dy }
    }

    fun scroll(dx: Float, dy: Float) {
        if (!connected) return
        synchronized(lock) { psx += dx; psy += dy }
    }

    private fun send(json: String) {
        if (!connected) return
        synchronized(lock) { flushPending(); q.add(json) }
    }

    fun button(b: String, down: Boolean) = send("{\"t\":\"btn\",\"b\":\"$b\",\"d\":$down}")
    fun click(b: String, n: Int = 1) = send("{\"t\":\"click\",\"b\":\"$b\",\"n\":$n}")
    fun key(code: Int, down: Boolean, flags: Int = 0) = send("{\"t\":\"key\",\"c\":$code,\"d\":$down,\"f\":$flags}")
    fun media(k: String) = send("{\"t\":\"media\",\"k\":\"$k\"}")
    /** Text (dictation, Type mode) with an id the Mac acknowledges; see [Outbox]. Paste or keystrokes per setting. */
    fun sendText(p: Pending) {
        val type = if (Prefs.insertPaste) "paste" else "text"
        send("{\"t\":\"$type\",\"s\":${JSONObject.quote(p.text)},\"id\":${p.id},\"enter\":${p.enter}}")
    }

    fun screen(on: Boolean, display: Int) = send("{\"t\":\"screen\",\"on\":$on,\"display\":$display,\"q\":${Prefs.quality}}")
    fun moveTo(x: Float, y: Float) = send("{\"t\":\"moveto\",\"x\":${fmt4(x)},\"y\":${fmt4(y)}}")
    private fun fmt4(f: Float) = String.format(Locale.US, "%.4f", f)

    fun tap(code: Int, flags: Int = 0) { key(code, true, flags); key(code, false, flags) }

    /** Press modifiers, tap the key, release modifiers (with small gaps so macOS sees a real chord). */
    fun combo(mods: IntArray, code: Int) {
        combos.execute {
            try {
                mods.forEach { key(it, true) }
                Thread.sleep(40)
                key(code, true); Thread.sleep(50); key(code, false)
                Thread.sleep(30)
                mods.reversed().forEach { key(it, false) }
            } catch (_: Exception) { }
        }
    }

    // ---------- discovery (NSD / Bonjour) ----------

    private fun startDiscovery(ctx: Context) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            if (resolving) return
            val info = pending.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo?, code: Int) {
                    synchronized(pending) { resolving = false; resolveNext() }
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val h = si.host
                    if (h is Inet4Address) {
                        val mac = Mac(si.serviceName, h.hostAddress ?: "", si.port)
                        runOnMain {
                            found.removeAll { it.name == mac.name }
                            found.add(mac)
                        }
                    }
                    synchronized(pending) { resolving = false; resolveNext() }
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onStartDiscoveryFailed(t: String?, e: Int) {}
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onServiceFound(si: NsdServiceInfo) {
                synchronized(pending) { pending.add(si); resolveNext() }
            }
            override fun onServiceLost(si: NsdServiceInfo) {
                runOnMain { found.removeAll { it.name == si.serviceName } }
            }
        }
        try {
            nsd.discoverServices("_padkey._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) { }
    }

    private fun runOnMain(r: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(r)
    }
}
