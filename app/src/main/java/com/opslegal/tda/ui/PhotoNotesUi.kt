package com.opslegal.tda.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** One mark on a photo: a line drawn with a finger, or a measure written where tapped. Points are 0..1 of the picture. */
private sealed class Mark(val color: Color) {
    class Line(color: Color, val points: MutableList<Offset>) : Mark(color)
    class Label(color: Color, val at: Offset, val text: String) : Mark(color)
}

private fun loadSmall(c: android.content.Context, uri: String, max: Int = 1600): Bitmap? = runCatching {
    val u = android.net.Uri.parse(uri)
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2
    c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
}.getOrNull()

/**
 * 📷 Mark it up: draw on the photo with a finger (red, yellow, white) and write measures where you tap ("42 in").
 * Undo takes back the last mark. Saved as one picture on the phone; nothing is sent anywhere.
 */
@Composable
internal fun MarkupDialog(uri: String, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val bmp = remember(uri) { loadSmall(context, uri) }
    if (bmp == null) { onDone(null); return }
    val marks = remember { mutableStateListOf<Mark>() }
    var color by remember { mutableStateOf(Color(0xFFE53935)) }
    var mode by remember { mutableStateOf("draw") }
    var pending by remember { mutableStateOf<Offset?>(null) }
    var label by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    Dialog(onDismissRequest = { onDone(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📷 Mark it up", style = MaterialTheme.typography.titleLarge)
                Box(Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(bmp.width.toFloat() / bmp.height).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))) {
                    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    Canvas(Modifier.fillMaxSize()
                        .pointerInput(mode, color) {
                            if (mode == "draw") detectDragGestures(
                                onDragStart = { p -> marks.add(Mark.Line(color, mutableListOf(Offset(p.x / size.width, p.y / size.height)))); tick++ },
                                onDrag = { ch, _ -> (marks.lastOrNull() as? Mark.Line)?.points?.add(Offset(ch.position.x / size.width, ch.position.y / size.height)); tick++ },
                            ) else detectTapGestures { p -> pending = Offset(p.x / size.width, p.y / size.height); label = "" }
                        }) {
                        tick.let { }
                        val w = size.width; val h = size.height
                        marks.forEach { m ->
                            when (m) {
                                is Mark.Line -> if (m.points.size > 1) {
                                    val path = Path().apply { m.points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * w, p.y * h) else lineTo(p.x * w, p.y * h) } }
                                    drawPath(path, m.color, style = Stroke(width = maxOf(4f, w / 160f), cap = StrokeCap.Round, join = StrokeJoin.Round))
                                }
                                is Mark.Label -> drawContext.canvas.nativeCanvas.let { nc -> drawLabel(nc, m, w, h) }
                            }
                        }
                        pending?.let { drawCircle(Color.White, 8f, Offset(it.x * w, it.y * h)); drawCircle(color, 5f, Offset(it.x * w, it.y * h)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Color(0xFFE53935), Color(0xFFFFD600), Color.White).forEach { c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(c).border(if (color == c) 3.dp else 1.dp, if (color == c) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape).clickable { color = c })
                    }
                    Spacer(Modifier.weight(1f))
                    PickChip(mode == "draw", "✏️ Draw") { mode = "draw"; pending = null }
                    PickChip(mode == "text", "🔤 Measure") { mode = "text" }
                    TagChip(false, { if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex); tick++ }, label = { Text("↩", fontSize = 14.sp) })
                }
                if (mode == "text") {
                    if (pending == null) Text("Tap where the measure goes, type it, then Place.", style = MaterialTheme.typography.bodySmall)
                    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(label, { label = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("e.g. 42 in, or Replace", fontSize = 12.sp) })
                        Button(onClick = { if (label.isNotBlank()) marks.add(Mark.Label(color, pending!!, label.trim())); pending = null; tick++ }) { Text("Place") }
                    }
                } else Text("Draw with your finger.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onDone(null) }) { Text("Cancel") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        // The marks drawn onto the picture itself, at its own size.
                        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
                        val cv = android.graphics.Canvas(out)
                        val w = out.width.toFloat(); val h = out.height.toFloat()
                        marks.forEach { m ->
                            when (m) {
                                is Mark.Line -> if (m.points.size > 1) {
                                    val p = android.graphics.Path().apply { m.points.forEachIndexed { i, o -> if (i == 0) moveTo(o.x * w, o.y * h) else lineTo(o.x * w, o.y * h) } }
                                    cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = m.color.toArgb(); style = Paint.Style.STROKE; strokeWidth = maxOf(4f, w / 160f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND })
                                }
                                is Mark.Label -> drawLabel(cv, m, w, h)
                            }
                        }
                        onDone(com.opslegal.tda.data.PaperFiles.saveBitmap(context, out).toString())
                    }) { Text("Use this photo") }
                }
            }
        }
    }
}

/** A measure: big bold letters with a dark (or light) outline so it reads on any photo. */
private fun drawLabel(cv: android.graphics.Canvas, m: Mark.Label, w: Float, h: Float) {
    val fs = maxOf(22f, w / 18f)
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = fs; isFakeBoldText = true; style = Paint.Style.STROKE; strokeWidth = fs / 6f; color = if (m.color == Color.White) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = fs; isFakeBoldText = true; color = m.color.toArgb() }
    cv.drawText(m.text, m.at.x * w, m.at.y * h, outline)
    cv.drawText(m.text, m.at.x * w, m.at.y * h, fill)
}

/** A photo, full size, over everything; a tap closes it. */
@Composable
internal fun PhotoView(uri: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val bmp = remember(uri) { loadSmall(context, uri, 2000) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
        }
    }
}

/** A small thumbnail of a photo on the phone. */
@Composable
internal fun PhotoThumb(uri: String, sizeDp: Int = 56, onClick: () -> Unit) {
    val context = LocalContext.current
    val bmp = remember(uri) { loadSmall(context, uri, 300) }
    Box(Modifier.size(sizeDp.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("📷")
    }
}
