package app.buildverse.padkey

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private fun accel(dx: Float, dy: Float, dtMs: Long): Pair<Float, Float> {
    val v = hypot(dx, dy) / max(dtMs, 1L).toFloat() // px per ms
    val gain = Prefs.sens * (0.7f + 1.1f * min(v, 6f).pow(1.15f))
    return Pair(dx * gain, dy * gain)
}

@Composable
fun Touchpad(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF16181C))) {
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                var lastTapUp = 0L
                var lastTapPos = Offset.Zero
                var lastTapCount = 0
                var flingJob: Job? = null
                val slop = 5f * density

                awaitEachGesture {
                    flingJob?.cancel(); flingJob = null
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val t0 = first.uptimeMillis
                    val second = lastTapCount > 0 && t0 - lastTapUp < 260 &&
                        (first.position - lastTapPos).getDistance() < 110f * density
                    var maxP = 1
                    var travel = 0f
                    var moved = false
                    var dragging = false
                    var scrolling = false
                    var pinching = false
                    var dist0 = 0f
                    var lastStepDist = 0f
                    var c0 = Offset.Zero
                    var swiped = false
                    var prevCount = 1
                    var accX = 0f
                    var accY = 0f
                    var vx = 0f
                    var vy = 0f
                    var lastMoveT = t0
                    var holdChecked = !second
                    var lastUp = t0

                    while (true) {
                        val ev: PointerEvent? =
                            if (!holdChecked && !moved && !dragging) {
                                val remain = 220 - (SystemClock.uptimeMillis() - t0)
                                if (remain <= 0) null else withTimeoutOrNull(remain) { awaitPointerEvent() }
                            } else awaitPointerEvent()

                        if (ev == null) { // held after a tap: start a drag
                            holdChecked = true
                            if (maxP == 1 && !moved) {
                                dragging = true
                                PadClient.button("left", true)
                                Haptics.tick(ctx, strong = true)
                            }
                            continue
                        }

                        val pressed = ev.changes.filter { it.pressed }
                        val n = pressed.size
                        if (n == 0) { lastUp = ev.changes.maxOf { it.uptimeMillis }; break }
                        if (n > maxP) { maxP = n; holdChecked = true }
                        if (n != prevCount) { // finger count changed: skip a frame so deltas don't jump
                            prevCount = n
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        val live = pressed.filter { it.previousPressed }
                        if (live.isEmpty()) continue
                        val dx = live.map { it.position.x - it.previousPosition.x }.average().toFloat()
                        val dy = live.map { it.position.y - it.previousPosition.y }.average().toFloat()
                        val dt = pressed[0].uptimeMillis - pressed[0].previousUptimeMillis

                        when {
                            dragging || maxP == 1 -> {
                                travel += hypot(dx, dy)
                                if (!moved && travel > slop) {
                                    moved = true; holdChecked = true
                                    if (second) { dragging = true; PadClient.button("left", true); Haptics.tick(ctx, true) }
                                }
                                if (moved || dragging) {
                                    val (ax, ay) = accel(dx, dy, dt)
                                    PadClient.move(ax, ay)
                                }
                            }
                            maxP == 2 -> {
                                travel += hypot(dx, dy)
                                if (pressed.size >= 2) {
                                    // Spreading/closing the fingers = pinch (Cmd+ / Cmd-); moving them together = scroll.
                                    val d = (pressed[0].position - pressed[1].position).getDistance()
                                    val cen = (pressed[0].position + pressed[1].position) / 2f
                                    if (dist0 == 0f) { dist0 = d; lastStepDist = d; c0 = cen }
                                    if (!pinching && !scrolling) {
                                        val dd = abs(d - dist0)
                                        val dc = (cen - c0).getDistance()
                                        if (dd > 18f * density && dd > dc) pinching = true
                                        else if (dc > slop * 1.6f && dc >= dd) scrolling = true
                                    }
                                    if (pinching) {
                                        val ratio = d / max(lastStepDist, 1f)
                                        if (ratio > 1.25f || ratio < 0.8f) {
                                            Haptics.tick(ctx)
                                            PadClient.combo(intArrayOf(VK.CMD), if (ratio > 1f) 24 else 27)   // = / -
                                            lastStepDist = d
                                        }
                                    }
                                }
                                if (scrolling) {
                                    val sgn = if (Prefs.natural) 1f else -1f
                                    val k = Prefs.scrollSpeed * sgn
                                    PadClient.scroll(dx * k, dy * k)
                                    val d = max(dt, 1L).toFloat()
                                    vx = 0.6f * vx + 0.4f * (dx * k / d)
                                    vy = 0.6f * vy + 0.4f * (dy * k / d)
                                    lastMoveT = SystemClock.uptimeMillis()
                                }
                            }
                            else -> if (n == 3 && !swiped) {
                                accX += dx; accY += dy
                                val thr = 60f * density
                                if (abs(accX) > thr && abs(accX) >= abs(accY)) {
                                    swiped = true
                                    Haptics.tick(ctx, true)
                                    PadClient.combo(intArrayOf(VK.CTRL), if (accX > 0) VK.RIGHT else VK.LEFT)
                                } else if (abs(accY) > thr) {
                                    swiped = true
                                    Haptics.tick(ctx, true)
                                    PadClient.combo(intArrayOf(VK.CTRL), if (accY < 0) VK.UP else VK.DOWN)
                                }
                            }
                        }
                        ev.changes.forEach { it.consume() }
                    }

                    val dur = lastUp - t0
                    if (dragging) {
                        PadClient.button("left", false)
                        lastTapCount = 0
                        Mods.consume()
                    } else if (maxP == 1 && !moved && dur < 260) {
                        val c = if (second) min(lastTapCount + 1, 3) else 1
                        PadClient.click("left", c)
                        Haptics.tick(ctx)
                        lastTapUp = lastUp; lastTapPos = first.position; lastTapCount = c
                        Mods.consume()
                    } else if (maxP == 2 && !scrolling && !pinching && dur < 350) {
                        PadClient.click("right", 1)
                        Haptics.tick(ctx)
                        lastTapCount = 0
                        Mods.consume()
                    } else {
                        lastTapCount = 0
                        if (scrolling && maxP == 2 && SystemClock.uptimeMillis() - lastMoveT < 70 &&
                            (abs(vx) > 0.25f || abs(vy) > 0.25f)
                        ) {
                            flingJob = scope.launch {
                                var fx = vx; var fy = vy
                                while (abs(fx) > 0.04f || abs(fy) > 0.04f) {
                                    PadClient.scroll(fx * 16f, fy * 16f)
                                    fx *= 0.94f; fy *= 0.94f
                                    delay(16)
                                }
                            }
                        }
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "1 finger: move/tap/tap-hold drag   2: scroll, pinch = zoom, tap = right-click   3: swipe",
                color = Color(0xFF4A4D55), fontSize = 11.sp, modifier = Modifier.padding(12.dp),
            )
        }
        if (Prefs.strips) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(4.dp)) {
                Strip("Left click", "left", Modifier.weight(1f).fillMaxSize())
                Box(Modifier.padding(horizontal = 2.dp))
                Strip("Right click", "right", Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}

@Composable
private fun Strip(label: String, btn: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val pressed = remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(if (pressed.value) Color(0xFF5A5E69) else Color(0xFF23262B))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    d.consume()
                    pressed.value = true
                    Haptics.tick(ctx)
                    PadClient.button(btn, true)
                    waitForUpOrCancellation()
                    PadClient.button(btn, false)
                    pressed.value = false
                    Mods.consume()
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color(0xFF9A9DA6), fontSize = 12.sp) }
}
