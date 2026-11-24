package app.buildverse.padkey

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Live Mac screen: H.264 over TCP (port 50506) -> MediaCodec -> TextureView, with zoom/pan and tap-to-click. */
@Composable
fun ScreenView(modifier: Modifier = Modifier) {
    var hasFrame by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(1f) }
    var view by remember { mutableStateOf<ScreenSurface?>(null) }
    val connected = PadClient.connected

    LaunchedEffect(PadClient.cursorX, PadClient.cursorY, Prefs.follow) {
        if (Prefs.follow) view?.followCursor(PadClient.cursorX, PadClient.cursorY)
    }

    Box(modifier.systemGestureExclusion().clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                ScreenSurface(ctx).also { sv ->
                    sv.onFrame = { hasFrame = it }
                    sv.onZoom = { zoom = it }
                    view = sv
                }
            },
            update = {
                it.setAspect(PadClient.screenW, PadClient.screenH)
                it.setSource(if (connected) PadClient.sessionHost else "", PadClient.sessionId)
            },
            onRelease = { it.shutdown(); view = null },
        )
        if (!hasFrame) {
            Text(
                when {
                    !connected -> "Connect to a Mac to see its screen"
                    PadClient.screenReason == "no_permission" ->
                        "Mac has no Screen Recording permission.\nMac menu bar > PadKey > Screen Recording > enable, then toggle the 🖥 button."
                    PadClient.screenReason == "needs_relaunch" ->
                        "Screen Recording is granted but capture needs a restart.\nMac menu bar > PadKey > \"Relaunch PadKey to enable Screen Recording\"."
                    !PadClient.screenOk -> "Screen capture failed on the Mac.\nToggle the 🖥 button; if it persists relaunch PadKey on the Mac."
                    else -> "Waiting for the Mac screen..."
                },
                color = Color(0xFF9A9CA3), fontSize = 12.sp, modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }
        // Only while zoomed in, and in the bottom corner so the Mac's top-right (menu bar items, window buttons) stays tappable.
        if (zoom > 1.02f) {
            Row(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                MirrorChip(if (Prefs.follow) "◎ Follow" else "○ Follow", Prefs.follow) { Prefs.follow = !Prefs.follow; Prefs.save() }
                Box(Modifier.padding(start = 4.dp)) { MirrorChip("%.1fx  ✕ reset".format(zoom), true) { view?.resetZoom() } }
            }
        }
    }
}

@Composable
private fun MirrorChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (active) Color(0xCCFF7A1A) else Color(0x99000000))
            .clickable { onClick() }.padding(horizontal = 8.dp, vertical = 4.dp),
    ) { Text(label, fontSize = 11.sp, color = if (active) Color.Black else Color.White) }
}

class ScreenSurface(ctx: Context) : TextureView(ctx), TextureView.SurfaceTextureListener {
    var onFrame: (Boolean) -> Unit = {}
    var onZoom: (Float) -> Unit = {}

    private var host = ""
    private var session = -1
    private var decoder: Decoder? = null
    private var surface: Surface? = null

    // video geometry (aspect of the Mac display) and view transform
    private var aspect = 16f / 10f
    private var videoAspect = 0f
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    // ----- source / lifecycle -----

    fun setAspect(w: Int, h: Int) {
        if (w > 0 && h > 0 && videoAspect == 0f) { aspect = w.toFloat() / h; applyMatrix() }
    }

    fun setSource(h: String, sessionId: Int) {
        if (h == host && sessionId == session) return
        host = h; session = sessionId
        restart()
    }

    private fun restart() {
        decoder?.shutdown(); decoder = null
        onFrame(false)
        val s = surface
        if (host.isNotEmpty() && s != null) {
            decoder = Decoder(s, host,
                onSize = { w, h -> main.post { if (h > 0) { videoAspect = w.toFloat() / h; aspect = videoAspect; applyMatrix() } } },
                onFrame = { main.post { onFrame(true) } }).also { it.start() }
        }
    }

    fun shutdown() { decoder?.shutdown(); decoder = null; surface?.release(); surface = null }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(st)
        restart(); applyMatrix()
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { applyMatrix() }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        decoder?.shutdown(); decoder = null
        surface?.release(); surface = null
        onFrame(false)
        return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // ----- geometry -----

    /** Content (video) rect in view pixels: [left, top, width, height]. */
    private fun content(): FloatArray {
        val vw = width.toFloat(); val vh = height.toFloat()
        var cw = vw; var ch = vw / aspect
        if (ch > vh) { ch = vh; cw = vh * aspect }
        cw *= zoom; ch *= zoom
        return floatArrayOf(vw / 2 + panX - cw / 2, vh / 2 + panY - ch / 2, cw, ch)
    }

    private fun clampPan() {
        val vw = width.toFloat(); val vh = height.toFloat()
        val c = content()
        val maxX = max(0f, (c[2] - vw) / 2); val maxY = max(0f, (c[3] - vh) / 2)
        panX = if (c[2] <= vw) 0f else panX.coerceIn(-maxX, maxX)
        panY = if (c[3] <= vh) 0f else panY.coerceIn(-maxY, maxY)
    }

    private fun applyMatrix() {
        if (width == 0 || height == 0) return
        clampPan()
        val c = content()
        val m = Matrix()
        // The texture is stretched over the whole view; scale it down to the content rect.
        m.setScale(c[2] / width, c[3] / height)
        m.postTranslate(c[0], c[1])
        setTransform(m)
        onZoom(zoom)
    }

    fun resetZoom() { zoom = 1f; panX = 0f; panY = 0f; applyMatrix() }

    fun followCursor(nx: Float, ny: Float) {
        if (zoom <= 1.02f || nx < 0 || ny < 0 || width == 0) return
        val c = content()
        // pan so the cursor lands at the view center
        panX = c[2] * (0.5f - nx.coerceIn(0f, 1f))
        panY = c[3] * (0.5f - ny.coerceIn(0f, 1f))
        applyMatrix()
    }

    private fun zoomAt(newZoom: Float, fx: Float, fy: Float) {
        val old = zoom
        val z = newZoom.coerceIn(1f, 4f)
        if (z == old) return
        // keep the content point under (fx, fy) fixed
        val c = content()
        val nx = (fx - c[0]) / c[2]; val ny = (fy - c[1]) / c[3]
        zoom = z
        val c2 = content()
        panX += (fx - (c2[0] + nx * c2[2])); panY += (fy - (c2[1] + ny * c2[3]))
        applyMatrix()
    }

    // ----- touch -----
    // 1 finger: tap = click, double-tap = double-click, long-press = right-click, drag = mouse drag.
    // 2 fingers: pinch = zoom, drag = pan (when zoomed).

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var downX = 0f; private var downY = 0f
    private var downT = 0L; private var moved = false; private var multi = false
    private var dragging = false; private var longFired = false; private var lastSendT = 0L
    private var lastTapT = 0L; private var lastTapX = 0f; private var lastTapY = 0f; private var lastTapCount = 0
    private var focusX = 0f; private var focusY = 0f
    private val longPress = Runnable {
        if (!moved && !multi && !dragging) {
            val p = norm(downX, downY) ?: return@Runnable
            longFired = true
            PadClient.moveTo(p.first, p.second)
            PadClient.click("right")
            Mods.consume()
            Haptics.tick(context, strong = true)
        }
    }
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomAt(zoom * d.scaleFactor, d.focusX, d.focusY); return true }
    })

    /** View point -> 0..1 on the Mac display, or null outside the video. */
    private fun norm(x: Float, y: Float): Pair<Float, Float>? {
        val c = content()
        val nx = (x - c[0]) / c[2]; val ny = (y - c[1]) / c[3]
        return if (nx in 0f..1f && ny in 0f..1f) Pair(nx, ny) else null
    }

    private fun midpoint(e: MotionEvent, skip: Int = -1): FloatArray {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sx += e.getX(i); sy += e.getY(i); n++ }
        return floatArrayOf(sx / max(n, 1), sy / max(n, 1))
    }

    private fun endDrag(x: Float, y: Float) {
        if (!dragging) return
        norm(x, y)?.let { PadClient.moveTo(it.first, it.second) }
        PadClient.button("left", false)
        dragging = false
        Mods.consume()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!Prefs.mirrorTouch) {   // view-only: ignore every touch (finish a drag that was already in progress)
            main.removeCallbacks(longPress)
            if (dragging) endDrag(e.x, e.y)
            return true
        }
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y
                downT = e.eventTime; moved = false; multi = false; dragging = false; longFired = false
                main.removeCallbacks(longPress)
                main.postDelayed(longPress, 500)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                main.removeCallbacks(longPress)
                if (!multi) { multi = true; endDrag(e.x, e.y) }
                val m = midpoint(e); focusX = m[0]; focusY = m[1]
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val m = midpoint(e, skip = e.actionIndex); focusX = m[0]; focusY = m[1]
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    val m = midpoint(e)
                    if (zoom > 1.02f) { panX += m[0] - focusX; panY += m[1] - focusY; applyMatrix() }
                    focusX = m[0]; focusY = m[1]
                    return true
                }
                if (longFired) return true
                if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                    moved = true
                    main.removeCallbacks(longPress)
                    // grab the point that was touched, then follow the finger
                    norm(downX, downY)?.let {
                        PadClient.moveTo(it.first, it.second)
                        PadClient.button("left", true)
                        dragging = true
                        Haptics.tick(context)
                    }
                }
                if (dragging && e.eventTime - lastSendT >= 8) {
                    lastSendT = e.eventTime
                    norm(e.x, e.y)?.let { PadClient.moveTo(it.first, it.second) }
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                if (dragging) endDrag(e.x, e.y)
                else if (!moved && !multi && !longFired && e.eventTime - downT < 500) tap(e.x, e.y, e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                if (dragging) endDrag(e.x, e.y)
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float, t: Long) {
        val p = norm(x, y) ?: return
        val second = lastTapCount > 0 && t - lastTapT < 300 && abs(x - lastTapX) < slop * 4 && abs(y - lastTapY) < slop * 4
        val count = if (second) min(lastTapCount + 1, 3) else 1
        lastTapT = t; lastTapX = x; lastTapY = y; lastTapCount = count
        PadClient.moveTo(p.first, p.second)
        PadClient.click("left", count)   // 2nd tap = clickState 2: a real double-click on the Mac
        Mods.consume()
        Haptics.tick(context)
    }
}

/** TCP reader + MediaCodec H.264 decoder rendering to [surface]. Reconnects until shut down. */
private class Decoder(
    val surface: Surface, val host: String,
    val onSize: (Int, Int) -> Unit, val onFrame: () -> Unit,
) : Thread("padkey-video") {
    @Volatile private var stop = false
    @Volatile private var sock: Socket? = null
    @Volatile private var codec: MediaCodec? = null

    init { isDaemon = true }

    fun shutdown() {
        stop = true
        try { sock?.close() } catch (_: Exception) { }
        interrupt()
    }

    override fun run() {
        while (!stop) {
            try { runOnce() } catch (_: Throwable) { }
            releaseCodec()
            if (!stop) try { sleep(1000) } catch (_: InterruptedException) { }
        }
    }

    private fun releaseCodec() {
        val c = codec; codec = null
        try { c?.stop() } catch (_: Exception) { }
        try { c?.release() } catch (_: Exception) { }
    }

    private fun runOnce() {
        val secret = PadClient.sessionSecret ?: throw java.io.IOException("not connected")
        val s = Socket()
        sock = s
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, 50506), 2500)
        s.soTimeout = 6000
        val out = s.getOutputStream()
        val nc = PK.random(16)
        out.write((JSONObject().put("t", "hello").put("v", PK.VERSION).put("id", Prefs.phoneId)
            .put("n", PK.b64(nc)).toString() + "\n").toByteArray()); out.flush()
        val input = DataInputStream(java.io.BufferedInputStream(s.getInputStream(), 1 shl 16))
        val reply = JSONObject(PK.readLine(input))
        if (reply.optString("t") == "error") throw java.io.IOException(reply.optString("msg"))
        val ns = PK.unb64(reply.getString("n"))
        if (!PK.eq(PK.unb64(reply.getString("auth")), PK.macAuth(secret, "video", nc, ns))) throw java.io.IOException("Mac not verified")
        val ch = Channel(secret, nc, ns, "video")
        out.write(ch.seal("{\"t\":\"ready\"}".toByteArray())); out.flush()   // first frame proves we hold the secret
        s.soTimeout = 0
        val c = newCodec()
        codec = c
        val outThread = Thread {
            val info = MediaCodec.BufferInfo()
            try {
                while (!stop && codec === c) {
                    val i = c.dequeueOutputBuffer(info, 20_000)
                    when {
                        i >= 0 -> { c.releaseOutputBuffer(i, true); onFrame() }
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = c.outputFormat
                            var w = f.getInteger(MediaFormat.KEY_WIDTH); var h = f.getInteger(MediaFormat.KEY_HEIGHT)
                            if (f.containsKey("crop-left") && f.containsKey("crop-right")) {
                                w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                                h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                            }
                            onSize(w, h)
                        }
                    }
                }
            } catch (_: Exception) { }
        }.also { it.isDaemon = true; it.start() }

        while (!stop) {
            val au = ch.readFrame(input, 8_000_000)   // decrypts; throws on forgery/replay
            val n = au.size
            val idx = c.dequeueInputBuffer(50_000)
            if (idx < 0) continue // decoder backed up: drop; next keyframe resyncs
            val bb = c.getInputBuffer(idx) ?: continue
            bb.clear(); bb.put(au, 0, n)
            c.queueInputBuffer(idx, 0, n, System.nanoTime() / 1000, 0)
        }
    }

    private fun newCodec(): MediaCodec {
        fun make(withMax: Boolean): MediaCodec {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 800)
            if (withMax) { f.setInteger(MediaFormat.KEY_MAX_WIDTH, 2560); f.setInteger(MediaFormat.KEY_MAX_HEIGHT, 2560) }
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try { c.configure(f, surface, null, 0); c.start() } catch (e: Exception) { c.release(); throw e }
            return c
        }
        return try { make(true) } catch (_: Exception) { make(false) }
    }
}
