package app.buildverse.padkey

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** "📎 Photo" chip for the Keys-mode shortcut strip: pick from the photo library or take one with the camera; the Mac
 *  puts it on its clipboard and pastes it (Ctrl+V in terminals such as Claude Code, Cmd+V elsewhere). */
@Composable
internal fun PhotoChip() {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) sendPhoto(ctx, uri) }
    val camFile = remember { File(ctx.cacheDir, "camera/shot.jpg").also { it.parentFile?.mkdirs() } }
    val camUri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", camFile) }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) sendPhoto(ctx, camUri) }

    // Status ("Sending photo...", "Photo pasted on the Mac") shows in the top bar; clear it a few seconds after it settles.
    val status = PadClient.imgStatus
    LaunchedEffect(status) {
        if (status.isNotEmpty() && !status.startsWith("Sending") && !status.startsWith("Preparing")) { delay(3000); PadClient.imgStatus = "" }
    }

    Box {
        Box(
            Modifier.fillMaxHeight().padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1F2126)).border(1.dp, Color(0xFF3A3D45), RoundedCornerShape(16.dp))
                .clickable { Haptics.tick(ctx); menu = true }.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text("📎 Photo", fontSize = 13.sp, color = Color(0xFFE6E6EA)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Photo library") }, onClick = {
                Haptics.tick(ctx); menu = false; pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            DropdownMenuItem(text = { Text("Camera") }, onClick = {
                Haptics.tick(ctx); menu = false; try { cam.launch(camUri) } catch (_: Exception) { PadClient.imgStatus = "No camera app" }
            })
        }
    }
}

/** Decode, scale to <= 2560 px on the long side, JPEG it and send it to the Mac. */
private fun sendPhoto(ctx: Context, uri: Uri) {
    if (!PadClient.connected) { PadClient.imgStatus = "Not connected"; return }
    PadClient.imgStatus = "Preparing photo..."
    Thread {
        try {
            val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    val s = info.size; val long = max(s.width, s.height)
                    if (long > 2560) { val f = 2560f / long; d.setTargetSize((s.width * f).toInt(), (s.height * f).toInt()) }
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                var sample = 1
                while (max(o.outWidth, o.outHeight) / sample > 2560) sample *= 2
                ctx.contentResolver.openInputStream(uri)!!.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }!!
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            PadClient.sendImage(out.toByteArray())
        } catch (_: Exception) {
            PadClient.imgStatus = "Could not read the photo"
        }
    }.start()
}
