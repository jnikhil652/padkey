package app.buildverse.padkey

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// macOS virtual keycodes (kVK_*)
object VK {
    const val RETURN = 36; const val TAB = 48; const val SPACE = 49; const val DELETE = 51; const val ESC = 53
    const val CMD = 55; const val SHIFT = 56; const val OPT = 58; const val CTRL = 59; const val FN = 63
    const val LEFT = 123; const val RIGHT = 124; const val DOWN = 125; const val UP = 126
    const val HOME = 115; const val END = 119; const val PGUP = 116; const val PGDN = 121; const val FWDDEL = 117
    val F = intArrayOf(122, 120, 99, 118, 96, 97, 98, 100, 101, 109, 103, 111)
}

enum class ModLevel { Off, Latched, Locked }

/** Modifier latch state. Tap = one-shot (released after the next key/click), double tap = lock. */
object Mods {
    val state: SnapshotStateMap<Int, ModLevel> = mutableStateMapOf()
    private val latchedAt = HashMap<Int, Long>()

    fun of(code: Int) = state[code] ?: ModLevel.Off

    fun tap(code: Int) {
        if (!PadClient.connected) return
        when (of(code)) {
            ModLevel.Off -> { state[code] = ModLevel.Latched; latchedAt[code] = System.currentTimeMillis(); PadClient.key(code, true) }
            ModLevel.Latched ->
                if (System.currentTimeMillis() - (latchedAt[code] ?: 0) < 450) state[code] = ModLevel.Locked
                else { state[code] = ModLevel.Off; PadClient.key(code, false) }
            ModLevel.Locked -> { state[code] = ModLevel.Off; PadClient.key(code, false) }
        }
    }

    /** Called after a non-modifier key or a click: release one-shot modifiers. */
    fun consume() {
        for ((c, s) in state.toMap()) if (s == ModLevel.Latched) { state[c] = ModLevel.Off; PadClient.key(c, false) }
    }

    fun reset() { state.clear() }
}

internal enum class Kind { Normal, Mod, Layer, Media, Spacer }

internal data class KeyDef(
    val label: String,
    val code: Int = -1,
    val w: Float = 1f,
    val flags: Int = 0,
    val kind: Kind = Kind.Normal,
    val media: String = "",
    val layer: Int = 0,
    val letter: Boolean = false,
    val repeat: Boolean = false,
)

internal fun ch(label: String, code: Int, flags: Int = 0) = KeyDef(label, code, flags = flags)
private fun spacer(w: Float) = KeyDef("", w = w, kind = Kind.Spacer)
private fun letters(s: String, codes: IntArray) = s.mapIndexed { i, c -> KeyDef(c.toString(), codes[i], letter = true) }

private val numCodes = intArrayOf(18, 19, 20, 21, 23, 22, 26, 28, 25, 29)

internal val layer0: List<List<KeyDef>> = listOf(
    "1234567890".mapIndexed { i, c -> ch(c.toString(), numCodes[i]) },
    letters("qwertyuiop", intArrayOf(12, 13, 14, 15, 17, 16, 32, 34, 31, 35)),
    listOf(KeyDef("⇪", 57, 1f)) + letters("asdfghjkl", intArrayOf(0, 1, 2, 3, 5, 4, 38, 40, 37)),
    listOf(KeyDef("⇧", VK.SHIFT, 1.5f, kind = Kind.Mod)) +
        letters("zxcvbnm", intArrayOf(6, 7, 8, 9, 11, 45, 46)) +
        KeyDef("⌫", VK.DELETE, 1.5f),
)

internal val layer1: List<List<KeyDef>> = listOf(
    listOf(ch("!", 18, 1), ch("@", 19, 1), ch("#", 20, 1), ch("$", 21, 1), ch("%", 23, 1),
        ch("^", 22, 1), ch("&", 26, 1), ch("*", 28, 1), ch("(", 25, 1), ch(")", 29, 1)),
    listOf(ch("-", 27), ch("_", 27, 1), ch("=", 24), ch("+", 24, 1), ch("[", 33), ch("]", 30),
        ch("{", 33, 1), ch("}", 30, 1), ch("\\", 42), ch("|", 42, 1)),
    listOf(ch(";", 41), ch(":", 41, 1), ch("'", 39), ch("\"", 39, 1), ch("<", 43, 1), ch(">", 47, 1),
        ch(",", 43), ch(".", 47), ch("/", 44), ch("?", 44, 1)),
    listOf(ch("`", 50), ch("~", 50, 1), KeyDef("Tab", VK.TAB, 2f), KeyDef("Return", VK.RETURN, 3f), KeyDef("⌫", VK.DELETE, 3f)),
)

private fun media(label: String, k: String, rep: Boolean = false) = KeyDef(label, kind = Kind.Media, media = k, repeat = rep)

internal val layer2: List<List<KeyDef>> = listOf(
    (0..5).map { ch("F${it + 1}", VK.F[it]) },
    (6..11).map { ch("F${it + 1}", VK.F[it]) },
    listOf(media("Prev", "prev"), media("Play", "play"), media("Next", "next"),
        media("Vol-", "voldown", true), media("Vol+", "volup", true), media("Mute", "mute"),
        media("Bri-", "brightdown", true), media("Bri+", "brightup", true)),
    listOf(KeyDef("Fwd Del", VK.FWDDEL), KeyDef("Home", VK.HOME), KeyDef("End", VK.END),
        KeyDef("PgUp", VK.PGUP), KeyDef("PgDn", VK.PGDN), KeyDef("⌫", VK.DELETE)),
)

internal val modRow: List<KeyDef> = listOf(
    KeyDef("esc", VK.ESC), KeyDef("tab", VK.TAB),
    KeyDef("fn", VK.FN, kind = Kind.Mod), KeyDef("⌃", VK.CTRL, kind = Kind.Mod),
    KeyDef("⌥", VK.OPT, kind = Kind.Mod), KeyDef("⌘", VK.CMD, kind = Kind.Mod),
    KeyDef("◀", VK.LEFT), KeyDef("▼", VK.DOWN), KeyDef("▲", VK.UP), KeyDef("▶", VK.RIGHT),
)

internal class Shortcut(val label: String, val mods: IntArray, val code: Int)

internal val shortcuts = listOf(
    Shortcut("Copy", intArrayOf(VK.CMD), 8), Shortcut("Paste", intArrayOf(VK.CMD), 9),
    Shortcut("Cut", intArrayOf(VK.CMD), 7), Shortcut("Undo", intArrayOf(VK.CMD), 6),
    Shortcut("Redo", intArrayOf(VK.CMD, VK.SHIFT), 6), Shortcut("All", intArrayOf(VK.CMD), 0),
    Shortcut("⌘Tab", intArrayOf(VK.CMD), VK.TAB), Shortcut("Spotlight", intArrayOf(VK.CMD), VK.SPACE),
    Shortcut("Close", intArrayOf(VK.CMD), 13), Shortcut("Quit", intArrayOf(VK.CMD), 12),
    Shortcut("Shot", intArrayOf(VK.CMD, VK.SHIFT), 21), Shortcut("Lock", intArrayOf(VK.CTRL, VK.CMD), 12),
)

private val Accent = Color(0xFFFF7A1A)
private val KeyBg = Color(0xFF2B2D33)
private val KeyBgSpecial = Color(0xFF1F2126)
private val KeyPressed = Color(0xFF5A5E69)

@Composable
fun KeyboardPanel(modifier: Modifier = Modifier) {
    var layer by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val shiftOn = Mods.of(VK.SHIFT) != ModLevel.Off

    Column(modifier.padding(horizontal = 3.dp, vertical = 2.dp)) {
        // Shortcut strip
        Row(
            Modifier.fillMaxWidth().height(34.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            shortcuts.forEach { s ->
                Box(
                    Modifier.fillMaxHeight().padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp))
                        .background(KeyBgSpecial).border(1.dp, Color(0xFF3A3D45), RoundedCornerShape(16.dp))
                        .clickable { Haptics.tick(ctx); PadClient.combo(s.mods, s.code) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(s.label, fontSize = 13.sp, color = Color(0xFFE6E6EA)) }
            }
        }
        // Persistent modifier / arrow row
        KeyRow(modRow, Modifier.weight(1f), shiftOn)
        val rows = when (layer) { 1 -> layer1; 2 -> layer2; else -> layer0 }
        rows.forEach { KeyRow(it, Modifier.weight(1f), shiftOn) }
        // Bottom row
        val bottom = listOf(
            KeyDef(if (layer == 1) "ABC" else "?123", kind = Kind.Layer, w = 1.4f, layer = if (layer == 1) 0 else 1),
            KeyDef(if (layer == 2) "ABC" else "Fn", kind = Kind.Layer, w = 1.2f, layer = if (layer == 2) 0 else 2),
            ch(",", 43), KeyDef("space", VK.SPACE, 4f), ch(".", 47), KeyDef("return", VK.RETURN, 1.6f),
        )
        KeyRow(bottom, Modifier.weight(1f), shiftOn, onLayer = { layer = it })
    }
}

@Composable
private fun KeyRow(keys: List<KeyDef>, modifier: Modifier, shiftOn: Boolean, onLayer: (Int) -> Unit = {}) {
    Row(modifier.fillMaxWidth()) {
        keys.forEach { k ->
            if (k.kind == Kind.Spacer) Spacer(Modifier.weight(k.w))
            else KeyCap(k, Modifier.weight(k.w).fillMaxHeight(), shiftOn, onLayer)
        }
    }
}

@Composable
private fun KeyCap(k: KeyDef, modifier: Modifier, shiftOn: Boolean, onLayer: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val curK = rememberUpdatedState(k)
    val curLayer = rememberUpdatedState(onLayer)
    val capsKey = k.code == 57 && k.kind == Kind.Normal
    val modState = when {
        k.kind == Kind.Mod -> Mods.of(k.code)
        capsKey && PadClient.capsOn -> ModLevel.Locked   // Caps Lock shows as locked while the Mac has it on
        else -> ModLevel.Off
    }

    val bg = when {
        modState == ModLevel.Locked -> Accent
        modState == ModLevel.Latched -> Accent.copy(alpha = 0.55f)
        pressed -> KeyPressed
        k.kind == Kind.Normal && k.code in intArrayOf(VK.SPACE) -> KeyBg
        k.letter || k.label.length == 1 -> KeyBg
        else -> KeyBgSpecial
    }
    val label = if (k.letter && (shiftOn || PadClient.capsOn)) k.label.uppercase() else k.label

    Box(Modifier.then(modifier).padding(2.dp)) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(7.dp)).background(bg)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val d = awaitFirstDown(requireUnconsumed = false)
                        d.consume()
                        val def = curK.value
                        pressed = true
                        Haptics.tick(ctx)
                        var job: kotlinx.coroutines.Job? = null
                        when (def.kind) {
                            Kind.Mod -> Mods.tap(def.code)
                            Kind.Layer -> curLayer.value(def.layer)
                            Kind.Media -> {
                                PadClient.media(def.media)
                                if (def.repeat) job = scope.launch {
                                    delay(380)
                                    while (true) { PadClient.media(def.media); delay(110) }
                                }
                            }
                            else -> PadClient.key(def.code, true, def.flags)
                        }
                        waitForUpOrCancellation()
                        job?.cancel()
                        pressed = false
                        if (def.kind == Kind.Normal) {
                            PadClient.key(def.code, false, def.flags)
                            Mods.consume()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontSize = if (label.length > 3) 12.sp else 17.sp,
                fontWeight = if (modState != ModLevel.Off) FontWeight.Bold else FontWeight.Normal,
                color = if (modState == ModLevel.Locked) Color.Black else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}
