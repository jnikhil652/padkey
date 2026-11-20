package app.buildverse.padkey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        PadClient.start(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFFF7A1A), onPrimary = Color.Black,
                background = Color(0xFF0C0D0F), surface = Color(0xFF1B1D21),
            )) { App() }
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

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        TopBar(typing, { typing = !typing }, { showConnect = true }, { showSettings = true })
        if (landscape) {
            Row(Modifier.weight(1f).fillMaxWidth().padding(4.dp)) {
                Touchpad(Modifier.weight(1f).fillMaxSize())
                Spacer(Modifier.width(6.dp))
                if (typing) TypePanel(Modifier.weight(1.2f).fillMaxSize()) else KeyboardPanel(Modifier.weight(1.2f).fillMaxSize())
            }
        } else {
            Touchpad(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp))
            if (typing) TypePanel(Modifier.weight(1f).fillMaxWidth()) else KeyboardPanel(Modifier.weight(1f).fillMaxWidth())
        }
    }
    if (showConnect) ConnectDialog { showConnect = false }
    if (showSettings) SettingsDialog { showSettings = false }
}

@Composable
private fun TopBar(typing: Boolean, onType: () -> Unit, onConnect: () -> Unit, onSettings: () -> Unit) {
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
        Spacer(Modifier.width(8.dp))
        Text(if (Voice.status.isNotEmpty() && Voice.state != VoiceState.Idle || Voice.status.startsWith("Error") || Voice.status.startsWith("Add")) Voice.status else text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f), color = Color(0xFFD0D2D8))
        DictateButton()
        TextButton(onClick = onConnect) { Text(if (PadClient.found.isNotEmpty() && !PadClient.connected) "Connect (${PadClient.found.size})" else "Connect") }
        if (typing) Button(onClick = onType) { Text("Keys") } else OutlinedButton(onClick = onType) { Text("Type") }
        TextButton(onClick = onSettings) { Text("⚙", fontSize = 20.sp) }
    }
}

@Composable
private fun TypePanel(modifier: Modifier) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) { } }
    fun send() {
        if (text.isNotEmpty()) {
            PadClient.text(text)
            if (Prefs.returnAfter) PadClient.tap(VK.RETURN)
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
private fun ConnectDialog(onClose: () -> Unit) {
    var host by remember { mutableStateOf(Prefs.host) }
    var port by remember { mutableStateOf(Prefs.port.toString()) }
    var token by remember { mutableStateOf(Prefs.token) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Connect to Mac") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (PadClient.found.isEmpty()) "Searching for Macs on this Wi-Fi..." else "Found on Wi-Fi (tap to connect):", fontSize = 13.sp)
                PadClient.found.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Color(0xFF2B2D33))
                            .clickable { Prefs.token = token; PadClient.connect(m.ip, m.port); onClose() }.padding(12.dp),
                    ) { Text("${m.name}  ${m.ip}", fontSize = 14.sp) }
                }
                Text("Or enter IP manually:", fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(host, { host = it }, singleLine = true, label = { Text("Mac IP (e.g. 192.168.1.20)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, singleLine = true, label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, singleLine = true, label = { Text("Token (only if you set one)") },
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                Prefs.token = token
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
private fun SettingsDialog(onClose: () -> Unit) {
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
                SwitchRow("Left/right click strips", Prefs.strips) { Prefs.strips = it; Prefs.save() }
                SwitchRow("Haptic feedback", Prefs.haptics) { Prefs.haptics = it; Prefs.save() }
                SwitchRow("Press Return after typed text", Prefs.returnAfter) { Prefs.returnAfter = it; Prefs.save() }
                SwitchRow("Mic button uses phone mic (off = Mac DScribe)", Prefs.phoneMic) { Prefs.phoneMic = it; Prefs.save() }
                OutlinedTextField(Prefs.geminiKey, { Prefs.geminiKey = it; Prefs.save() }, singleLine = true,
                    label = { Text("Gemini API key") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                OutlinedTextField(Prefs.dictionary, { Prefs.dictionary = it; Prefs.save() },
                    label = { Text("Dictionary (comma-separated terms)") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { Prefs.save(); onClose() }) { Text("Done") } },
    )
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(value, onChange)
    }
}

/** Dictation button. Phone mic mode: record on phone -> Gemini -> typed on Mac.
 *  Mac DScribe mode: holds Right Option (keycode 61) so DScribe on the Mac listens.
 *  Hold = push-to-talk. Quick tap = lock on (hands-free); tap again to stop. */
@Composable
private fun DictateButton() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    fun begin(): Boolean = if (Prefs.phoneMic) Voice.start() else { PadClient.key(61, true); true }
    fun finish() { if (Prefs.phoneMic) Voice.stop() else PadClient.key(61, false) }
    val busy = Voice.state == VoiceState.Processing
    Box(
        Modifier.padding(horizontal = 4.dp).height(34.dp).width(64.dp).clip(CircleShape)
            .background(when { on -> Color(0xFFE5484D); busy -> Color(0xFFFFB300); else -> Color(0xFF2B2D33) })
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    Haptics.tick(ctx, strong = true)
                    if (locked) {
                        finish(); locked = false; on = false
                        waitForUpOrCancellation(); return@awaitEachGesture
                    }
                    if (!begin()) { waitForUpOrCancellation(); return@awaitEachGesture }
                    on = true
                    val t0 = System.currentTimeMillis()
                    waitForUpOrCancellation()
                    if (System.currentTimeMillis() - t0 < 350) { locked = true }
                    else { finish(); on = false; Haptics.tick(ctx) }
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(when { locked -> "■ Stop"; busy -> "…"; else -> "🎤" }, fontSize = if (locked) 12.sp else 16.sp, color = Color.White) }
}
