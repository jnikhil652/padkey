package app.buildverse.padkey

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** True on Android TV / any device without a touchscreen: PadKey then shows the D-pad driven [TvApp]. */
fun isTv(ctx: Context): Boolean {
    val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}

private val Accent = Color(0xFFFF7A1A)
private val KeyBg = Color(0xFF2B2D33)
private val KeyBgSpecial = Color(0xFF1F2126)

/**
 * A focusable key for the TV remote: D-pad moves the focus ring, OK/Enter presses. Holding OK repeats when [repeat]
 * is set (volume, arrows, mouse movement); [onRepeat] gets the repeat count (1, 2, ...).
 */
@Composable
private fun TvButton(
    label: String, modifier: Modifier = Modifier, active: Boolean = false, special: Boolean = false,
    fontSize: Int = 18, repeat: Boolean = false, onRepeat: (Int) -> Unit = {}, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.padding(3.dp)) {
        Box(
            Modifier.fillMaxSize().clip(shape)
                .background(when { active -> Accent; special -> KeyBgSpecial; else -> KeyBg })
                .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
                .onKeyEvent { e ->
                    val k = e.key
                    val ok = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
                    if (repeat && ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0) {
                        onRepeat(e.nativeKeyEvent.repeatCount); true
                    } else false
                }
                .onFocusChanged { focused = it.isFocused }
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label, fontSize = fontSize.sp, maxLines = 1, overflow = TextOverflow.Clip,
                fontWeight = if (focused || active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) Color.Black else Color.White,
            )
        }
    }
}

@Composable
fun TvApp() {
    val view = LocalView.current
    val connected = PadClient.state == LinkState.Connected
    SideEffect { view.keepScreenOn = connected }

    var typing by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 40.dp, vertical = 24.dp)) {
        TvTopBar(typing, { typing = !typing }, { showConnect = true }, { showSettings = true })
        PendingBar()
        if (typing) {
            TypePanel(Modifier.weight(1f).fillMaxWidth())
        } else {
            Row(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
                TvRemote(Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(16.dp))
                TvKeyboard(Modifier.weight(2.1f).fillMaxHeight())
            }
        }
    }
    if (showConnect) ConnectDialog { showConnect = false }
    if (showSettings) SettingsDialog { showSettings = false }
    if (PadClient.needPin) PinDialog()
}

@Composable
private fun TvTopBar(typing: Boolean, onType: () -> Unit, onConnect: () -> Unit, onSettings: () -> Unit) {
    val color = when (PadClient.state) {
        LinkState.Connected -> Color(0xFF3DDC84)
        LinkState.Connecting -> Color(0xFFFFB300)
        LinkState.Idle -> Color(0xFFE5484D)
    }
    val text = when (PadClient.state) {
        LinkState.Connected -> PadClient.macName + if (PadClient.latency >= 0) "  ${PadClient.latency} ms" else ""
        LinkState.Connecting -> PadClient.message.ifEmpty { "Connecting..." }
        LinkState.Idle -> PadClient.message.ifEmpty { "Not connected" }
    }
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = Color(0xFFD0D2D8))
        val connectLabel = if (PadClient.found.isNotEmpty() && !PadClient.connected) "Connect·${PadClient.found.size}" else "Connect"
        TvButton("Wake", Modifier.width(110.dp).fillMaxHeight(), fontSize = 16) { PadClient.wake() }
        TvButton(connectLabel, Modifier.width(150.dp).fillMaxHeight(), fontSize = 16, onClick = onConnect)
        TvButton(if (typing) "Keys" else "Type text", Modifier.width(150.dp).fillMaxHeight(), active = typing, fontSize = 16, onClick = onType)
        TvButton("Settings", Modifier.width(130.dp).fillMaxHeight(), fontSize = 16, onClick = onSettings)
    }
}

/** D-pad cluster + the keys a living-room remote needs. In Mouse mode the arrows move the cursor and OK clicks. */
@Composable
private fun TvRemote(modifier: Modifier) {
    var mouse by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { first.requestFocus() } catch (_: Exception) { } }

    fun arrow(code: Int, dx: Float, dy: Float, n: Int) {
        if (mouse) PadClient.move(dx * (12 + minOf(n, 12) * 7), dy * (12 + minOf(n, 12) * 7)) else PadClient.tap(code)
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().weight(0.8f)) {
            TvButton("Arrows", Modifier.weight(1f).fillMaxHeight(), active = !mouse, fontSize = 15) { mouse = false }
            TvButton("Mouse", Modifier.weight(1f).fillMaxHeight(), active = mouse, fontSize = 15) { mouse = true }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Spacer(Modifier.weight(1f)); 
            TvButton("▲", Modifier.weight(1f).fillMaxHeight(), repeat = true, onRepeat = { arrow(VK.UP, 0f, -1f, it) }) { arrow(VK.UP, 0f, -1f, 0) }
            Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            TvButton("◀", Modifier.weight(1f).fillMaxHeight(), repeat = true, onRepeat = { arrow(VK.LEFT, -1f, 0f, it) }) { arrow(VK.LEFT, -1f, 0f, 0) }
            TvButton(if (mouse) "Click" else "OK", Modifier.weight(1f).fillMaxHeight().focusRequester(first), active = true, fontSize = 17) {
                if (mouse) PadClient.click("left") else PadClient.tap(VK.RETURN)
            }
            TvButton("▶", Modifier.weight(1f).fillMaxHeight(), repeat = true, onRepeat = { arrow(VK.RIGHT, 1f, 0f, it) }) { arrow(VK.RIGHT, 1f, 0f, 0) }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Spacer(Modifier.weight(1f))
            TvButton("▼", Modifier.weight(1f).fillMaxHeight(), repeat = true, onRepeat = { arrow(VK.DOWN, 0f, 1f, it) }) { arrow(VK.DOWN, 0f, 1f, 0) }
            Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            if (mouse) {
                TvButton("Right click", Modifier.weight(1f).fillMaxHeight(), special = true, fontSize = 14) { PadClient.click("right") }
                TvButton("Dbl click", Modifier.weight(1f).fillMaxHeight(), special = true, fontSize = 14) { PadClient.click("left", 2) }
            } else {
                TvButton("Esc", Modifier.weight(1f).fillMaxHeight(), special = true, fontSize = 15) { PadClient.tap(VK.ESC) }
                TvButton("Tab", Modifier.weight(1f).fillMaxHeight(), special = true, fontSize = 15) { PadClient.tap(VK.TAB) }
            }
            TvButton("⌫", Modifier.weight(1f).fillMaxHeight(), special = true, repeat = true, onRepeat = { PadClient.tap(VK.DELETE) }) { PadClient.tap(VK.DELETE) }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            TvButton("⏮", Modifier.weight(1f).fillMaxHeight(), special = true) { PadClient.media("prev") }
            TvButton("⏯", Modifier.weight(1f).fillMaxHeight(), special = true) { PadClient.media("play") }
            TvButton("⏭", Modifier.weight(1f).fillMaxHeight(), special = true) { PadClient.media("next") }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            TvButton("Vol −", Modifier.weight(1f).fillMaxHeight(), special = true, fontSize = 15, repeat = true, onRepeat = { PadClient.media("voldown") }) { PadClient.media("voldown") }
            TvButton("Mute", Modifier.weight(1f).fillMaxHeight(), special = true, fontSize = 15) { PadClient.media("mute") }
            TvButton("Vol +", Modifier.weight(1f).fillMaxHeight(), special = true, fontSize = 15, repeat = true, onRepeat = { PadClient.media("volup") }) { PadClient.media("volup") }
        }
    }
}

@Composable
private fun TvKeyboard(modifier: Modifier) {
    var layer by remember { mutableIntStateOf(0) }
    val shiftOn = Mods.of(VK.SHIFT) != ModLevel.Off

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(46.dp).horizontalScroll(rememberScrollState())) {
            shortcuts.forEach { s ->
                TvButton(s.label, Modifier.width(110.dp).fillMaxHeight(), special = true, fontSize = 14) { PadClient.combo(s.mods, s.code) }
            }
        }
        TvKeyRow(modRow, Modifier.weight(1f), shiftOn)
        val rows = when (layer) { 1 -> layer1; 2 -> layer2; else -> layer0 }
        rows.forEach { TvKeyRow(it, Modifier.weight(1f), shiftOn) }
        val bottom = listOf(
            KeyDef(if (layer == 1) "ABC" else "?123", kind = Kind.Layer, w = 1.4f, layer = if (layer == 1) 0 else 1),
            KeyDef(if (layer == 2) "ABC" else "Fn", kind = Kind.Layer, w = 1.2f, layer = if (layer == 2) 0 else 2),
            ch(",", 43), KeyDef("space", VK.SPACE, 4f), ch(".", 47), KeyDef("return", VK.RETURN, 1.6f),
        )
        TvKeyRow(bottom, Modifier.weight(1f), shiftOn, onLayer = { layer = it })
    }
}

@Composable
private fun TvKeyRow(keys: List<KeyDef>, modifier: Modifier, shiftOn: Boolean, onLayer: (Int) -> Unit = {}) {
    Row(modifier.fillMaxWidth()) {
        keys.forEach { k ->
            if (k.kind == Kind.Spacer) { Spacer(Modifier.weight(k.w)); return@forEach }
            val capsKey = k.code == 57 && k.kind == Kind.Normal
            val modState = when {
                k.kind == Kind.Mod -> Mods.of(k.code)
                capsKey && PadClient.capsOn -> ModLevel.Locked
                else -> ModLevel.Off
            }
            val label = if (k.letter && (shiftOn || PadClient.capsOn)) k.label.uppercase() else k.label
            TvButton(
                label, Modifier.weight(k.w).fillMaxHeight(), active = modState != ModLevel.Off,
                special = !(k.letter || k.label.length == 1) && k.code != VK.SPACE,
                fontSize = if (label.length > 3) 15 else 20,
                repeat = k.kind == Kind.Media && k.repeat || k.code == VK.DELETE || k.code in intArrayOf(VK.LEFT, VK.RIGHT, VK.UP, VK.DOWN),
                onRepeat = { if (k.kind == Kind.Media) PadClient.media(k.media) else PadClient.tap(k.code, k.flags) },
            ) {
                when (k.kind) {
                    Kind.Mod -> Mods.tap(k.code)
                    Kind.Layer -> onLayer(k.layer)
                    Kind.Media -> PadClient.media(k.media)
                    else -> { PadClient.tap(k.code, k.flags); Mods.consume() }
                }
            }
        }
    }
}
