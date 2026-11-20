package app.buildverse.padkey

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
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
        try { socket?.close() } catch (_: Exception) { }
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
        lastRx = System.currentTimeMillis()
        q.clear()
        synchronized(lock) { pdx = 0f; pdy = 0f; psx = 0f; psy = 0f }
        val out = s.getOutputStream()
        val sessionAlive = { !s.isClosed }
        try {
            val hello = JSONObject().put("t", "hello").put("v", 1).put("token", Prefs.token)
            out.write((hello.toString() + "\n").toByteArray()); out.flush()
        } catch (e: Exception) { try { s.close() } catch (_: Exception) { } }

        thread(isDaemon = true, name = "padkey-writer") {
            try {
                while (sessionAlive()) {
                    val m = q.poll(5, TimeUnit.MILLISECONDS)
                    if (m == null) { synchronized(lock) { flushPending() }; continue }
                    out.write(m.toByteArray()); out.write(10); out.flush()
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
        try {
            val r = s.getInputStream().bufferedReader()
            while (true) {
                val line = r.readLine() ?: break
                lastRx = System.currentTimeMillis()
                val o = try { JSONObject(line) } catch (_: Exception) { continue }
                when (o.optString("t")) {
                    "hello" -> {
                        macName = o.optString("name", "Mac")
                        Mods.reset()
                        state = LinkState.Connected
                        message = ""
                        failCount = 0
                    }
                    "pong" -> latency = (System.currentTimeMillis() - o.optLong("id")).toInt().coerceAtLeast(0)
                    "error" -> { message = "Mac: " + o.optString("msg"); autoConnect = false }
                }
            }
        } catch (_: Exception) { }
        try { s.close() } catch (_: Exception) { }
        if (state != LinkState.Connected) failCount++
        state = LinkState.Idle
        macName = ""
        latency = -1
        Mods.reset()
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
    fun text(s: String) = send("{\"t\":\"text\",\"s\":${JSONObject.quote(s)}}")

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
