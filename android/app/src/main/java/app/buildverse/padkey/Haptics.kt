package app.buildverse.padkey

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

object Haptics {
    private var vib: Vibrator? = null

    fun tick(ctx: Context, strong: Boolean = false) {
        if (!Prefs.haptics) return
        val v = vib ?: (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator).also { vib = it } ?: return
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                v.vibrate(VibrationEffect.createPredefined(if (strong) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK))
            } else {
                v.vibrate(VibrationEffect.createOneShot(if (strong) 18 else 9, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) { }
    }
}
