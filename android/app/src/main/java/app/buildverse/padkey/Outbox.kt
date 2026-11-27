package app.buildverse.padkey

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

data class Pending(val id: Long, val text: String, val enter: Boolean)

/**
 * Dictated / typed text on its way to the Mac. An item stays here until the Mac acknowledges it, so a dropped
 * connection never loses text: it is shown in the pending bar, copied to the phone clipboard, and re-sent on reconnect
 * (the Mac ignores an id it already handled).
 */
object Outbox {
    val items = mutableStateListOf<Pending>()
    var note by mutableStateOf("")
    private var ctx: Context? = null

    fun init(c: Context) { ctx = c }

    /** Returns true if it was handed to a live connection (still pending until acked). */
    fun submit(text: String, enter: Boolean): Boolean {
        if (text.isEmpty()) return false
        val p = Pending(Random.nextLong() and 0x1FFFFFFFFFFFFFL, text, enter)
        items.add(p)
        return if (PadClient.connected) { PadClient.sendText(p); true } else {
            copy()
            note = "Not connected: saved and copied to the clipboard. Sends when reconnected."
            false
        }
    }

    fun onConnected() {
        if (items.isEmpty()) return
        note = "Sending saved text..."
        items.toList().forEach { PadClient.sendText(it) }
    }

    fun onDisconnected() {
        if (items.isNotEmpty()) { copy(); note = "Connection lost: text saved and copied. Sends when reconnected." }
    }

    fun ack(id: Long) {
        items.removeAll { it.id == id }
        if (items.isEmpty()) note = ""
    }

    fun sendNow() { if (PadClient.connected) items.toList().forEach { PadClient.sendText(it) } }

    fun discard() { items.clear(); note = "" }

    fun joined() = items.joinToString("\n") { it.text }

    fun copy() = copyText(joined())
    /** Put text on the phone clipboard (e.g. a discarded dictation, so it can still be recovered). */
    fun copyText(t: String) {
        val c = ctx ?: return
        if (t.isBlank()) return
        try {
            (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("PadKey text", t))
        } catch (_: Exception) { }
    }
}
