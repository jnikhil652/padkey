package app.buildverse.padkey

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Settings, backed by SharedPreferences. Fields are Compose state so the UI reacts. */
object Prefs {
    private lateinit var sp: SharedPreferences

    var host by mutableStateOf("")
    var port by mutableStateOf(50505)
    var token by mutableStateOf("")
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
        token = sp.getString("token", "") ?: ""
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
            .putString("host", host).putInt("port", port).putString("token", token)
            .putFloat("sens", sens).putFloat("scrollSpeed", scrollSpeed)
            .putBoolean("natural", natural).putBoolean("strips", strips)
            .putBoolean("haptics", haptics).putBoolean("returnAfter", returnAfter)
            .putString("geminiKey", geminiKey).putString("dictionary", dictionary).putBoolean("phoneMic", phoneMic)
            .putBoolean("screen", screen).putInt("display", display).putBoolean("follow", follow)
            .apply()
    }
}
