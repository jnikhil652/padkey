package app.buildverse.padkey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Prefs.init(this)
        intent?.getStringExtra("dictionary")?.let { Prefs.dictionary = it; Prefs.save() }
        intent?.getStringExtra("gemini_key")?.let { if (it.isNotBlank()) { Prefs.geminiKey = it.trim(); Prefs.save() } }
        if (!isTv(this) && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        PadClient.start(applicationContext)
        // Phone: the edge back-swipe fires constantly while using the touchpad and used to close the app.
        // Swallow Back so PadKey only leaves via Home (minimize) and is closed from Recents (swipe up).
        if (!isTv(this)) onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFFF7A1A), onPrimary = Color.Black,
                background = Color(0xFF0C0D0F), surface = Color(0xFF1B1D21),
            )) { if (isTv(this)) TvApp() else App() }
        }
    }
}

@Composable
fun App() {
    val view = LocalView.current
    val connected = PadClient.state == LinkState.Connected
    SideEffect { view.keepScreenOn = connected }

    var typing by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp

    var showDisplays by remember { mutableStateOf(false) }
    val mirror = Prefs.screen
    fun toggleScreen() {
        Prefs.screen = !Prefs.screen; Prefs.save()
        PadClient.screen(Prefs.screen, Prefs.display)
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        TopBar(typing, { typing = !typing }, { showConnect = true }, { showSettings = true }, ::toggleScreen, { showDisplays = true })
        PendingBar()
        val keys: @Composable (Modifier) -> Unit = { m -> if (typing) TypePanel(m) else KeyboardPanel(m) }
        if (landscape) {
            Row(Modifier.weight(1f).fillMaxWidth().padding(4.dp)) {
                if (mirror) {
                    ScreenView(Modifier.weight(1.5f).fillMaxSize())
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f).fillMaxSize()) {
                        Touchpad(Modifier.weight(1f).fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        keys(Modifier.weight(1f).fillMaxWidth())
                    }
                } else {
                    Touchpad(Modifier.weight(1f).fillMaxSize())
                    Spacer(Modifier.width(6.dp))
                    keys(Modifier.weight(1.2f).fillMaxSize())
                }
            }
        } else if (mirror) {
            // three equal parts: screen / touchpad / keyboard
            ScreenView(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp))
            Touchpad(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp))
            keys(Modifier.weight(1f).fillMaxWidth())
        } else {
            Touchpad(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp))
            keys(Modifier.weight(1f).fillMaxWidth())
        }
    }
    if (showDisplays) DisplayDialog { showDisplays = false }
    if (showConnect) ConnectDialog { showConnect = false }
    if (showSettings) SettingsDialog { showSettings = false }
    if (PadClient.needPin) PinDialog()
}

@Composable
private fun SmallChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2B2D33))
            .clickable { onClick() }.padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 12.sp, color = Color.White) }
}

/** Text that has not been acknowledged by the Mac yet (offline / send failed), and failed transcriptions. */
@Composable
internal fun PendingBar() {
    val items = Outbox.items
    val failed = Voice.failedAudio
    if (items.isEmpty() && failed == null) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp).clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF3A2A12)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (items.isNotEmpty()) {
            Text("Pending (${items.size}): " + Outbox.joined().replace('\n', ' ').take(160), fontSize = 12.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, color = Color(0xFFFFD9A8))
            if (Outbox.note.isNotEmpty()) Text(Outbox.note, fontSize = 11.sp, color = Color(0xFFB89868))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (PadClient.connected) SmallChip("Send now") { Outbox.sendNow() }
                SmallChip("Copy") { Outbox.copy() }
                SmallChip("Discard") { Outbox.discard() }
            }
        }
        if (failed != null) {
            Text(Voice.status.ifEmpty { "Transcription failed" }, fontSize = 12.sp, maxLines = 2, color = Color(0xFFFFD9A8))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip("Retry transcription") { Voice.retry() }
                SmallChip("Dismiss") { Voice.failedAudio = null }
            }
        }
    }
}

@Composable
internal fun PinDialog() {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { PadClient.cancelPairing() },
        title = { Text("Pair with your Mac") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("On the Mac click the PadKey keyboard icon in the menu bar, choose \"Pair new phone...\" and type the 6-digit PIN it shows (valid 2 minutes).", fontSize = 13.sp)
                Text("Mac: ${PadClient.pairHost}", fontSize = 12.sp, color = Color(0xFF9A9CA3))
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, singleLine = true, label = { Text("PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                if (PadClient.pairError.isNotEmpty()) Text(PadClient.pairError, fontSize = 13.sp, color = Color(0xFFE5484D))
                if (PadClient.pairBusy) Text("Pairing...", fontSize = 12.sp)
            }
        },
        confirmButton = { Button(enabled = pin.length == 6 && !PadClient.pairBusy, onClick = { PadClient.pair(pin) }) { Text("Pair") } },
        dismissButton = { TextButton(onClick = { PadClient.cancelPairing() }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarChip(label: String, active: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else Color(0xFF2B2D33))
            .combinedClickable(onLongClick = onLong, onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 13.sp, maxLines = 1, color = if (active) Color.Black else Color.White) }
}

@Composable
private fun TopBar(
    typing: Boolean, onType: () -> Unit, onConnect: () -> Unit, onSettings: () -> Unit,
    onScreen: () -> Unit, onDisplays: () -> Unit,
) {
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
    Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(if (Voice.status.isNotEmpty() && Voice.state != VoiceState.Idle || Voice.status.startsWith("Error") || Voice.status.startsWith("Add")) Voice.status else text,
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = Color(0xFFD0D2D8))
        Spacer(Modifier.width(4.dp))
        DictateButton()
        Spacer(Modifier.width(4.dp))
        BarChip("☀") { PadClient.wake() }
        Spacer(Modifier.width(4.dp))
        BarChip("🖥", active = Prefs.screen, onLong = onDisplays, onClick = onScreen)
        Spacer(Modifier.width(4.dp))
        BarChip(if (PadClient.found.isNotEmpty() && !PadClient.connected) "Connect·${PadClient.found.size}" else "Connect", onClick = onConnect)
        Spacer(Modifier.width(4.dp))
        BarChip(if (typing) "Keys" else "Type", active = typing, onClick = onType)
        Spacer(Modifier.width(4.dp))
        BarChip("⚙", onClick = onSettings)
    }
}

@Composable
private fun DisplayDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Mirror which display?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val list = PadClient.displays.ifEmpty { listOf(0 to "Main display") }
                list.forEach { (id, name) ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                            .background(if (id == Prefs.display) Color(0x55FF7A1A) else Color(0xFF2B2D33))
                            .clickable {
                                Prefs.display = id; Prefs.screen = true; Prefs.save()
                                PadClient.screen(true, id); onClose()
                            }.padding(12.dp),
                    ) { Text(name, fontSize = 14.sp) }
                }
                Text("Tap the 🖥 button to switch the mirror on or off. Long-press it to pick a display.", fontSize = 12.sp, color = Color(0xFF9A9CA3))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
internal fun TypePanel(modifier: Modifier) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) { } }
    fun send() {
        if (text.isNotEmpty()) {
            Outbox.submit(text, Prefs.returnAfter)   // kept (and shown as pending) until the Mac confirms it
            text = ""
        } else PadClient.tap(VK.RETURN)
    }
    Column(modifier.padding(6.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.weight(1f).fillMaxWidth().focusRequester(focus),
            placeholder = { Text("Type, swipe-type or dictate, then Send") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(Prefs.returnAfter, { Prefs.returnAfter = it; Prefs.save() })
            Text("Return after", fontSize = 13.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { PadClient.tap(VK.DELETE) }) { Text("\u232B") }
            Spacer(Modifier.width(6.dp))
            Button(onClick = { send() }) { Text("Send") }
        }
    }
}

@Composable
internal fun ConnectDialog(onClose: () -> Unit) {
    var host by remember { mutableStateOf(Prefs.host) }
    var port by remember { mutableStateOf(Prefs.port.toString()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Connect to Mac") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Prefs.pairings.isNotEmpty()) {
                    Text("Your Macs (tap to connect):", fontSize = 13.sp)
                    Prefs.pairings.forEach { p ->
                        val live = PadClient.found.firstOrNull { it.name == p.name }
                        val here = PadClient.connected && PadClient.activeMid == p.mid
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(if (here) Color(0xFF123524) else Color(0xFF2B2D33))
                                .clickable { PadClient.connectMac(p); onClose() }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name + if (here) "  ·  connected" else "", fontSize = 14.sp)
                                Text(
                                    when { live != null -> "on this Wi-Fi  ${live.ip}"; p.host.isNotBlank() -> p.host; else -> "no address yet" },
                                    fontSize = 11.sp, color = Color(0xFF9A9CA3),
                                )
                            }
                            TextButton(onClick = { Prefs.forget(p.mid) }) { Text("Forget", fontSize = 12.sp) }
                        }
                    }
                }
                Text(if (PadClient.found.isEmpty()) "Searching for Macs on this Wi-Fi..." else "Found on Wi-Fi (tap to connect):", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                PadClient.found.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Color(0xFF2B2D33))
                            .clickable { PadClient.connect(m.ip, m.port); onClose() }.padding(12.dp),
                    ) { Text("${m.name}  ${m.ip}", fontSize = 14.sp) }
                }
                Text("Or enter IP manually:", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(host, { host = it }, singleLine = true, label = { Text("Mac IP or Tailscale name (e.g. 192.168.1.20)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, singleLine = true, label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Text("To save a Mac on another network (office, Tailscale), enter its address once and Connect: it is remembered under Your Macs.", fontSize = 12.sp, color = Color(0xFF9A9CA3))
                OutlinedButton(onClick = { PadClient.beginPairing(host.trim().ifEmpty { Prefs.host }, port.toIntOrNull() ?: 50505); onClose() },
                    modifier = Modifier.fillMaxWidth()) { Text("Pair with PIN...") }
            }
        },
        confirmButton = {
            Button(onClick = {
                PadClient.connect(host.trim(), port.toIntOrNull() ?: 50505)
                onClose()
            }) { Text("Connect") }
        },
        dismissButton = {
            Row {
                if (PadClient.state != LinkState.Idle) TextButton(onClick = { PadClient.disconnect(); onClose() }) { Text("Disconnect") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}

@Composable
internal fun SettingsDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = { Prefs.save(); onClose() },
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Pointer speed  %.1fx".format(Prefs.sens), fontSize = 13.sp)
                Slider(Prefs.sens, { Prefs.sens = it }, valueRange = 0.3f..3f, onValueChangeFinished = { Prefs.save() })
                Text("Scroll speed  %.1fx".format(Prefs.scrollSpeed), fontSize = 13.sp)
                Slider(Prefs.scrollSpeed, { Prefs.scrollSpeed = it }, valueRange = 0.3f..4f, onValueChangeFinished = { Prefs.save() })
                SwitchRow("Natural scrolling", Prefs.natural) { Prefs.natural = it; Prefs.save() }
                SwitchRow("Touch control on Mac screen mirror (off = view only)", Prefs.mirrorTouch) { Prefs.mirrorTouch = it; Prefs.save() }
                SwitchRow("Left/right click strips", Prefs.strips) { Prefs.strips = it; Prefs.save() }
                SwitchRow("Haptic feedback", Prefs.haptics) { Prefs.haptics = it; Prefs.save() }
                SwitchRow("Press Return after typed text", Prefs.returnAfter) { Prefs.returnAfter = it; Prefs.save() }
                ChoiceRow("Insert text by", listOf("Paste", "Type keystrokes"), if (Prefs.insertPaste) 0 else 1) { Prefs.insertPaste = it == 0; Prefs.save() }
                ChoiceRow("Mirror quality", listOf("Standard", "Sharp"), Prefs.quality) {
                    Prefs.quality = it; Prefs.save()
                    if (Prefs.screen) PadClient.screen(true, Prefs.display)
                }
                SwitchRow("Mic button uses phone mic (off = Mac DScribe)", Prefs.phoneMic) { Prefs.phoneMic = it; Prefs.save() }
                OutlinedTextField(Prefs.geminiKey, { Prefs.geminiKey = it; Prefs.save() }, singleLine = true,
                    label = { Text("Gemini API key") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                OutlinedTextField(Prefs.dictionary, { Prefs.dictionary = it; Prefs.save() },
                    label = { Text("Dictionary (comma-separated terms)") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                if (Prefs.pairings.isNotEmpty()) {
                    TextButton(onClick = { Prefs.forgetPairings() }) { Text("Forget paired Macs (${Prefs.pairings.size})") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { Prefs.save(); onClose() }) { Text("Done") } },
    )
}

@Composable
private fun ChoiceRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 14.sp)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { i, o ->
                Box(
                    Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (i == selected) MaterialTheme.colorScheme.primary else Color(0xFF2B2D33))
                        .clickable { onSelect(i) }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(o, fontSize = 13.sp, color = if (i == selected) Color.Black else Color.White) }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(value, onChange)
    }
}

/** Dictation button. Phone mic mode: record on phone -> Gemini -> pasted/typed on Mac (kept until the Mac confirms).
 *  Mac DScribe mode: holds Right Option (keycode 61) so DScribe on the Mac listens.
 *  Hold = push-to-talk. Quick tap = lock on (hands-free); tap again to stop. Recording auto-stops at 8 minutes. */
@Composable
private fun DictateButton() {
    val ctx = LocalContext.current
    var held by remember { mutableStateOf(false) }      // DScribe mode: Right Option is down
    var locked by remember { mutableStateOf(false) }
    fun begin(): Boolean = if (Prefs.phoneMic) Voice.start() else { PadClient.key(61, true); true }
    fun finish() { if (Prefs.phoneMic) Voice.stop() else PadClient.key(61, false) }
    val busy = Voice.state == VoiceState.Processing
    // Phone mic: the recording itself is the truth (it can stop on its own at the time limit).
    val on = if (Prefs.phoneMic) Voice.state == VoiceState.Recording else held
    val showLocked = locked && on
    Box(
        Modifier.height(32.dp).width(48.dp).clip(CircleShape)
            .background(when { on -> Color(0xFFE5484D); busy -> Color(0xFFFFB300); else -> Color(0xFF2B2D33) })
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    Haptics.tick(ctx, strong = true)
                    val stillOn = if (Prefs.phoneMic) Voice.state == VoiceState.Recording else held
                    if (locked && stillOn) {
                        finish(); locked = false; held = false
                        waitForUpOrCancellation(); return@awaitEachGesture
                    }
                    locked = false
                    if (!begin()) { waitForUpOrCancellation(); return@awaitEachGesture }
                    held = true
                    val t0 = System.currentTimeMillis()
                    waitForUpOrCancellation()
                    if (System.currentTimeMillis() - t0 < 350) { locked = true }
                    else { finish(); held = false; Haptics.tick(ctx) }
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(when { showLocked -> "■ Stop"; busy -> "…"; else -> "🎤" }, fontSize = if (showLocked) 11.sp else 15.sp, color = Color.White) }
}
