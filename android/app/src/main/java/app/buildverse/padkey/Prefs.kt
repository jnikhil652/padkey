package app.buildverse.padkey

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** A Mac this phone is paired with: `secret` is the 32-byte long-term key (base64), never leaves the phone. */
data class Paired(val mid: String, val name: String, val secret: String, val host: String = "", val port: Int = 50505)

/** Settings, backed by SharedPreferences. Fields are Compose state so the UI reacts. */
object Prefs {
    private lateinit var sp: SharedPreferences

    var host by mutableStateOf("")
    var port by mutableStateOf(50505)
    var phoneId by mutableStateOf("")       // random id this phone presents to every Mac
    var pairings by mutableStateOf(listOf<Paired>())
    var insertPaste by mutableStateOf(true) // dictation / Type mode: true = paste via Mac clipboard, false = type keystrokes
    var quality by mutableStateOf(0)        // mirror quality: 0 Standard, 1 Sharp
    var sens by mutableStateOf(1.0f)
    var scrollSpeed by mutableStateOf(1.2f)
    var natural by mutableStateOf(true)
    var strips by mutableStateOf(true)
    var haptics by mutableStateOf(true)
    var returnAfter by mutableStateOf(false)
    var geminiKey by mutableStateOf("")
    var dictionary by mutableStateOf("")
    var phoneMic by mutableStateOf(true)
    var screen by mutableStateOf(false)   // Mac screen mirror (3-way layout) on/off
    var display by mutableStateOf(0)      // which Mac display to mirror
    var follow by mutableStateOf(false)   // keep the Mac cursor centered while zoomed in

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("padkey", Context.MODE_PRIVATE)
        host = sp.getString("host", "") ?: ""
        port = sp.getInt("port", 50505)
        phoneId = sp.getString("phoneId", "") ?: ""
        if (phoneId.isBlank()) { phoneId = PK.hex(PK.random(16)); sp.edit().putString("phoneId", phoneId).apply() }
        pairings = try {
            val a = JSONArray(sp.getString("pairings", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Paired(o.getString("mid"), o.optString("name"), o.getString("secret"), o.optString("host"), o.optInt("port", 50505)) } }
        } catch (_: Exception) { emptyList() }
        insertPaste = sp.getBoolean("insertPaste", true)
        quality = sp.getInt("quality", 0)
        sens = sp.getFloat("sens", 1.0f)
        scrollSpeed = sp.getFloat("scrollSpeed", 1.2f)
        natural = sp.getBoolean("natural", true)
        strips = sp.getBoolean("strips", true)
        haptics = sp.getBoolean("haptics", true)
        returnAfter = sp.getBoolean("returnAfter", false)
        geminiKey = sp.getString("geminiKey", "") ?: ""
        dictionary = sp.getString("dictionary", "") ?: ""
        phoneMic = sp.getBoolean("phoneMic", true)
        screen = sp.getBoolean("screen", false)
        display = sp.getInt("display", 0)
        follow = sp.getBoolean("follow", false)
    }

    fun save() {
        if (!::sp.isInitialized) return
        sp.edit()
            .putString("host", host).putInt("port", port)
            .putBoolean("insertPaste", insertPaste).putInt("quality", quality)
            .putFloat("sens", sens).putFloat("scrollSpeed", scrollSpeed)
            .putBoolean("natural", natural).putBoolean("strips", strips)
            .putBoolean("haptics", haptics).putBoolean("returnAfter", returnAfter)
            .putString("geminiKey", geminiKey).putString("dictionary", dictionary).putBoolean("phoneMic", phoneMic)
            .putBoolean("screen", screen).putInt("display", display).putBoolean("follow", follow)
            .apply()
    }

    fun addPairing(p: Paired) {
        pairings = pairings.filter { it.mid != p.mid } + p
        savePairings()
    }

    /** Remember where a paired Mac was last reached (IP, hostname or Tailscale name). */
    fun rememberAddress(mid: String, host: String, port: Int) {
        if (pairings.none { it.mid == mid && (it.host != host || it.port != port) }) return
        pairings = pairings.map { if (it.mid == mid) it.copy(host = host, port = port) else it }
        savePairings()
    }

    fun forget(mid: String) { pairings = pairings.filter { it.mid != mid }; savePairings() }

    fun forgetPairings() { pairings = emptyList(); savePairings() }

    private fun savePairings() {
        if (!::sp.isInitialized) return
        val a = JSONArray()
        pairings.forEach { a.put(JSONObject().put("mid", it.mid).put("name", it.name).put("secret", it.secret)) }
        sp.edit().putString("pairings", a.toString()).apply()
    }
}
