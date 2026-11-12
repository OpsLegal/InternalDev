package com.opslegal.tda.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opslegal.tda.core.model.Asset
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Upkeep
import com.opslegal.tda.core.plan.Assets
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Paperwork
import com.opslegal.tda.core.plan.Things
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val OK = Color(0xFF2E9E5B)
private val WARN = Color(0xFFE0A100)
private val BAD = Color(0xFFD64545)
private fun statusColor(s: String) = when (s) { "bad" -> BAD; "warn" -> WARN; else -> OK }
private fun worst(list: List<String>) = when { "bad" in list -> "bad"; "warn" in list -> "warn"; list.isEmpty() -> ""; else -> "ok" }
private fun shortDate(d: String) = runCatching { LocalDate.parse(d).let { "${it.month.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }} ${it.dayOfMonth}, ${it.year}" } }.getOrDefault(d)

/** 🏷 My things: a wheel of 8 categories or a shelf of every thing, a search, and each thing's profile card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThingsPage(vm: MainViewModel, board: Board) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tda-ui", android.content.Context.MODE_PRIVATE) }
    var view by remember { mutableStateOf(prefs.getString("thingsView", "wheel") ?: "wheel") }
    var cat by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    val asked by vm.openThing.collectAsState()
    androidx.compose.runtime.LaunchedEffect(asked) { asked?.let { open = it; vm.openThing.value = null } }
    var adding by remember { mutableStateOf<Pair<String, String>?>(null) }
    val today = LocalDate.now()
    val status = remember(board, today) { board.assets.associate { it.id to Things.status(board, it, today) } }
    open?.let { id -> board.assets.firstOrNull { it.id == id }?.let { ThingProfile(vm, board, it) { open = null } } ?: run { open = null } }
    adding?.let { (c, k) -> ThingEditDialog(vm, board, Asset(BoardOps.newId(), "", "", cat = c, sub = k), isNew = true) { saved -> adding = null; if (saved != null) open = saved } }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("🔍 Search your things, kinds, documents", fontSize = 13.sp) },
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("✕") } })
        if (query.isNotBlank()) {
            val found = Things.search(board, query)
            val kinds = Things.kinds(query)
            Text("Things", style = MaterialTheme.typography.labelMedium)
            if (found.isEmpty()) Text("None of your things match.", style = MaterialTheme.typography.bodySmall)
            found.forEach { a -> ThingRow(a, status[a.id].orEmpty()) { open = a.id } }
            if (kinds.isNotEmpty()) {
                Text("Add a kind", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    kinds.take(8).forEach { (c, k) -> TagChip(false, { adding = c.key to k }, label = { Text("＋ ${Things.KIND_ICON[k] ?: c.icon} $k", fontSize = 12.sp) }) }
                }
            }
            return@Column
        }
        // The switch: same two choices, same place; the chosen one is highlighted.
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp)) {
            listOf("wheel" to "◔ Wheel", "shelf" to "▦ Shelf").forEach { (k, l) ->
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (view == k) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { view = k; prefs.edit().putString("thingsView", k).apply() }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(l, fontWeight = if (view == k) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
                }
            }
        }
        if (view == "wheel") {
            ThingsWheel(board, status) { c -> cat = c; view = "shelf"; prefs.edit().putString("thingsView", "shelf").apply() }
            Text("Tap a slice to see its shelf. The bar: green fine, amber due within 30 days, red late.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PickChip(cat == null, "All · ${board.assets.size}") { cat = null }
                Things.CATS.forEach { c ->
                    val n = board.assets.count { Things.cat(it).key == c.key }
                    PickChip(cat == c.key, "${c.icon} ${c.name}" + if (n > 0) " · $n" else "", enabled = true) { cat = if (cat == c.key) null else c.key }
                }
            }
            val shown = board.assets.filter { cat == null || Things.cat(it).key == cat }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { a -> ThingTile(a, status[a.id].orEmpty()) { open = a.id } }
                Column(Modifier.width(100.dp).height(132.dp).clip(RoundedCornerShape(12.dp)).border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .clickable { adding = (cat ?: "ve") to Things.CATS.first { it.key == (cat ?: "ve") }.kinds.first() },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("＋", fontSize = 30.sp)
                    Text("Add", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ThingRow(a: Asset, status: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(Things.icon(a), fontSize = 22.sp)
        Column(Modifier.weight(1f)) {
            Text(a.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${Things.cat(a).name} · ${Things.kind(a)}", style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(statusColor(status)))
    }
}

/** A thing on the shelf: its photo or icon, name, kind, and its status bar. */
@Composable
private fun ThingTile(a: Asset, status: String, onClick: () -> Unit) {
    Column(Modifier.width(100.dp).height(132.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(70.dp), contentAlignment = Alignment.Center) {
            val bmp = rememberPhoto(a.photo, 240)
            if (bmp != null) Image(bmp, null, Modifier.fillMaxWidth().height(70.dp), contentScale = ContentScale.Crop) else Text(Things.icon(a), fontSize = 30.sp)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(statusColor(status)))
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(a.name, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
            Text(Things.kind(a), fontSize = 10.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The main photo (a file kept on the phone), decoded small. */
@Composable
private fun rememberPhoto(uri: String, px: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    return remember(uri, px) {
        if (uri.isBlank()) null else runCatching {
            val u = android.net.Uri.parse(uri)
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var s = 1; while (o.outWidth / (s * 2) >= px) s *= 2
            context.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = s }) }?.asImageBitmap()
        }.getOrNull()
    }
}

/** The wheel: 8 grey slices with icon, name, count and a status bar; the centre shows the total and how many need care. */
@Composable
private fun ThingsWheel(board: Board, status: Map<String, String>, onPick: (String) -> Unit) {
    val measurer = rememberTextMeasurer()
    val grey = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val cats = Things.CATS
    val counts = cats.map { c -> board.assets.filter { Things.cat(it).key == c.key } }
    val care = status.values.count { it != "ok" }
    val sweep = 360f / cats.size
    Canvas(Modifier.fillMaxWidth().heightIn(max = 340.dp).height(320.dp).pointerInput(cats) {
        detectTapGestures { p ->
            val cx = size.width / 2f; val cy = size.height / 2f
            val r = minOf(cx, cy) - 4f
            val d = hypot(p.x - cx, p.y - cy)
            if (d < r * 0.34f || d > r) return@detectTapGestures
            val ang = ((Math.toDegrees(atan2((p.y - cy).toDouble(), (p.x - cx).toDouble())) + 90 + 360) % 360).toFloat()
            onPick(cats[(ang / sweep).toInt().coerceIn(0, cats.lastIndex)].key)
        }
    }) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = minOf(c.x, c.y) - 4f
        val tl = Offset(c.x - r, c.y - r)
        cats.forEachIndexed { i, cat ->
            val start = -90f + i * sweep
            drawArc(grey, start, sweep, useCenter = true, topLeft = tl, size = Size(2 * r, 2 * r))
            drawArc(line, start, sweep, useCenter = true, topLeft = tl, size = Size(2 * r, 2 * r), style = Stroke(3f))
            val st = worst(counts[i].map { status[it.id].orEmpty() })
            if (st.isNotEmpty()) {
                val rr = r - 7f
                drawArc(statusColor(st), start + 4f, sweep - 8f, useCenter = false, topLeft = Offset(c.x - rr, c.y - rr), size = Size(2 * rr, 2 * rr), style = Stroke(8f))
            }
            val mid = Math.toRadians((start + sweep / 2).toDouble())
            val at = Offset(c.x + (r * 0.68f * cos(mid)).toFloat(), c.y + (r * 0.68f * sin(mid)).toFloat())
            val t1 = measurer.measure(cat.icon, TextStyle(fontSize = 20.sp))
            val t2 = measurer.measure(cat.name, TextStyle(fontSize = 10.sp, color = ink, fontWeight = FontWeight.Bold))
            val t3 = measurer.measure("${counts[i].size}", TextStyle(fontSize = 10.sp, color = sub))
            drawText(t1, topLeft = Offset(at.x - t1.size.width / 2f, at.y - t1.size.height - 2f))
            drawText(t2, topLeft = Offset(at.x - t2.size.width / 2f, at.y))
            drawText(t3, topLeft = Offset(at.x - t3.size.width / 2f, at.y + t2.size.height))
        }
        drawCircle(line, r * 0.34f, c)
        val n = measurer.measure("${board.assets.size}", TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = ink))
        val l = measurer.measure(if (care > 0) "$care need care" else "things", TextStyle(fontSize = 11.sp, color = if (care > 0) BAD else sub))
        drawText(n, topLeft = Offset(c.x - n.size.width / 2f, c.y - n.size.height + 4f))
        drawText(l, topLeft = Offset(c.x - l.size.width / 2f, c.y + 4f))
    }
}

/** The profile card: photo, status, key data, documents, a timeline to zoom, and what to do about each dated item. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThingProfile(vm: MainViewModel, board: Board, a: Asset, onDone: () -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var editing by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf("y") }
    var picked by remember { mutableStateOf<Things.Item?>(null) }
    var task by remember { mutableStateOf("") }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.saveAsset(a.copy(photo = com.opslegal.tda.data.PaperFiles.keepSmall(context, it).toString()), quiet = true) }
    }
    if (editing) { ThingEditDialog(vm, board, a, isNew = false) { editing = false }; return }
    val status = Things.status(board, a, today)
    val cat = Things.cat(a)
    SoftDialog(onDismissRequest = onDone,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { photo.launch("image/*") }, contentAlignment = Alignment.Center) {
                    val bmp = rememberPhoto(a.photo, 300)
                    if (bmp != null) Image(bmp, "Photo", Modifier.size(72.dp), contentScale = ContentScale.Crop) else Text(Things.icon(a), fontSize = 32.sp)
                    if (a.photo.isBlank()) Text("📷", fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(a.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${cat.name} · ${Things.kind(a)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(mapOf("bad" to "● Late", "warn" to "● Due soon", "ok" to "● All good")[status].orEmpty(), fontSize = 12.sp, color = statusColor(status), fontWeight = FontWeight.Bold)
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (a.desc.ifBlank { a.details }).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                // Key data, as boxes.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    cat.fields.mapNotNull { f -> a.fields[f.name]?.takeIf { it.isNotBlank() }?.let { f to it } }.forEach { (f, v) ->
                        DataBox(f.name, when (f.type) { "date" -> shortDate(v); "$" -> v.toDoubleOrNull()?.let { Paperwork.money(it) } ?: v; else -> v })
                    }
                    DataBox("Spent ${today.year}", Paperwork.money(Assets.spent(a, today)))
                    DataBox("Open tasks", "${Assets.tasks(board, a).size}")
                }
                // Documents, with a capture tile.
                Text("📄 Documents · ${a.docs.size}", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.width(84.dp).height(64.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                        .clickable { onDone(); vm.captureFor.value = "asset:${a.id}" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("📷"); Text("Capture", fontSize = 11.sp)
                    }
                    a.docs.reversed().forEach { d ->
                        Column(Modifier.width(84.dp).height(64.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(5.dp)) {
                            Text("📄 ${d.name}", fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 12.sp)
                            Text(d.at.take(10), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                // Timeline.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("🗓 Timeline", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    listOf("y" to "Year", "6" to "6 mo", "3" to "3 mo", "1" to "1 mo").forEach { (k, l) -> PickChip(zoom == k, l) { zoom = k } }
                }
                Timeline(board, a, today, zoom, picked) { picked = if (picked == it) null else it }
                picked?.let { p -> ItemDetail(vm, a, p, today) { picked = null } }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(task, { task = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("＋ A task for it", fontSize = 12.sp) }, textStyle = MaterialTheme.typography.bodySmall)
                    TagChip(false, { vm.assetTask(a.id, task); task = "" }, label = { Text("Add", fontSize = 12.sp) })
                }
            }
        },
        confirmButton = { Button(onClick = { editing = true }) { Text("✏️ Edit") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Close") } },
    )
}

@Composable
private fun DataBox(label: String, value: String) {
    Column(Modifier.widthIn(min = 88.dp, max = 160.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 5.dp)) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Months as cells for Year and 6 mo, weeks for 3 mo and 1 mo; the current cell outlined red; each item a tappable icon. */
@Composable
private fun Timeline(board: Board, a: Asset, today: LocalDate, zoom: String, picked: Things.Item?, onPick: (Things.Item) -> Unit) {
    val monthly = zoom == "y" || zoom == "6"
    val n = mapOf("y" to 12, "6" to 6, "3" to 13, "1" to 5)[zoom] ?: 12
    val start = if (monthly) today.withDayOfMonth(1).minusMonths(if (zoom == "y") 2 else 1) else today.with(DayOfWeek.MONDAY).minusWeeks(1)
    val cells = (0 until n).map { i -> if (monthly) start.plusMonths(i.toLong()) to start.plusMonths(i + 1L) else start.plusWeeks(i.toLong()) to start.plusWeeks(i + 1L) }
    val items = remember(board, a, start, n) { Things.items(board, a, start, cells.last().second) }
    val minW = mapOf("y" to 26.dp, "6" to 40.dp, "3" to 58.dp, "1" to 58.dp)[zoom] ?: 26.dp
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        cells.forEach { (from, to) ->
            val now = !today.isBefore(from) && today.isBefore(to)
            Column(Modifier.widthIn(min = minW).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (now) Modifier.border(2.dp, BAD, RoundedCornerShape(6.dp)) else Modifier).padding(3.dp).heightIn(min = 70.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (monthly) from.month.name.take(if (zoom == "y") 1 else 3).lowercase().replaceFirstChar { it.uppercase() } else "${from.dayOfMonth}/${from.monthValue}",
                    fontSize = 10.sp, fontWeight = if (now) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
                items.filter { it.date >= from.toString() && it.date < to.toString() }.forEach { it ->
                    val late = it.date < today.toString() && it.kind != "expense"
                    Text(it.icon, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(4.dp))
                        .background(if (picked == it) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else if (late) BAD.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable { onPick(it) }.padding(1.dp))
                }
            }
        }
    }
    Text("🔧 upkeep · 📅 date · 📋 task · ⚡ quick thing · 💲 expense", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What a tapped timeline item is, and what can be done about it now. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemDetail(vm: MainViewModel, a: Asset, p: Things.Item, today: LocalDate, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${p.icon} ${p.title}", fontWeight = FontWeight.Bold)
        Text(shortDate(p.date) + (a.upkeep.firstOrNull { it.id == p.ref && p.kind == "upkeep" }?.let { " · every ${it.every} months · becomes ${if (it.becomes == "task") "📋 a task" else "⚡ a quick thing"}" } ?: ""),
            style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (p.kind == "upkeep") TagChip(false, { vm.makeUpkeepNow(a.id, p.ref); onDone() }, label = { Text("➜ Make it now", fontSize = 12.sp) })
            if ((p.kind == "upkeep" || p.kind == "date") && p.date > today.plusDays(7).toString())
                TagChip(false, { vm.remindBefore(a.id, p.title, LocalDate.parse(p.date)); onDone() }, label = { Text("🔔 Remind me a week before", fontSize = 12.sp) })
            if (p.kind == "task") TagChip(false, { vm.setDone(p.ref, true); onDone() }, label = { Text("✓ Done", fontSize = 12.sp) })
            if (p.kind == "quick") TagChip(false, { vm.tickQuickThing(p.ref); onDone() }, label = { Text("✓ Done", fontSize = 12.sp) })
        }
    }
}

/** Add or edit a thing: category and kind, name, a line about it, the key data for its kind, and its upkeep. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThingEditDialog(vm: MainViewModel, board: Board, start: Asset, isNew: Boolean, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var cat by remember { mutableStateOf(Things.cat(start).key) }
    var kind by remember { mutableStateOf(Things.kind(start)) }
    var name by remember { mutableStateOf(start.name) }
    var desc by remember { mutableStateOf(start.desc.ifBlank { start.details }) }
    val fields = remember { mutableStateMapOf<String, String>().apply { putAll(start.fields) } }
    val upkeep = remember { mutableStateListOf<Upkeep>().apply { addAll(start.upkeep) } }
    var editingUp by remember { mutableStateOf<String?>(null) }
    var what by remember { mutableStateOf("") }
    var every by remember { mutableStateOf("6") }
    var next by remember { mutableStateOf(today.plusMonths(1)) }
    var becomes by remember { mutableStateOf("quick") }
    var confirmRemove by remember { mutableStateOf(false) }
    val c = Things.CATS.first { it.key == cat }
    fun pickDate(from: LocalDate, set: (LocalDate) -> Unit) =
        android.app.DatePickerDialog(context, { _, y, m, d -> set(LocalDate.of(y, m + 1, d)) }, from.year, from.monthValue - 1, from.dayOfMonth).show()
    SoftDialog(keepOpen = true, onDismissRequest = { onDone(null) },
        title = { Text(if (isNew) "🏷 A new thing" else "✏️ ${start.name}") },
        text = {
            Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Category", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Things.CATS.forEach { x -> PickChip(cat == x.key, "${x.icon} ${x.name}") { if (cat != x.key) { cat = x.key; kind = x.kinds.first() } } }
                }
                Text("Kind", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    c.kinds.forEach { k -> PickChip(kind == k, "${Things.KIND_ICON[k] ?: c.icon} $k") { kind = k } }
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") }, placeholder = { Text("e.g. Honda CR-V 2019, Unit 3", fontSize = 12.sp) })
                OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), maxLines = 3, label = { Text("About it (a line or two)") })
                Text("Key data", style = MaterialTheme.typography.labelMedium)
                c.fields.forEach { f ->
                    if (f.type == "date") {
                        val v = fields[f.name].orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(f.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TagChip(v.isNotBlank(), { pickDate(runCatching { LocalDate.parse(v) }.getOrDefault(today)) { fields[f.name] = it.toString() } },
                                label = { Text("📅 " + (if (v.isBlank()) "pick" else shortDate(v)), fontSize = 12.sp) })
                            if (v.isNotBlank()) TextButton(onClick = { fields.remove(f.name) }) { Text("✕") }
                        }
                    } else OutlinedTextField(fields[f.name].orEmpty(), { fields[f.name] = if (f.type == "$") it.filter { ch -> ch.isDigit() || ch == '.' } else it },
                        Modifier.fillMaxWidth(), singleLine = true, label = { Text(f.name + if (f.type == "$") " ($)" else "", fontSize = 12.sp) }, textStyle = MaterialTheme.typography.bodySmall)
                }
                Text("🔧 Upkeep · tap one to change it", style = MaterialTheme.typography.labelMedium)
                upkeep.toList().forEach { u ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (editingUp == u.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { editingUp = u.id; what = u.what; every = "${u.every}"; next = runCatching { LocalDate.parse(u.next) }.getOrDefault(today); becomes = u.becomes },
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${if (u.becomes == "task") "📋" else "⚡"} ${u.what} · every ${u.every} mo · next ${shortDate(u.next)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(start = 4.dp))
                        TextButton(onClick = { upkeep.remove(u); if (editingUp == u.id) editingUp = null }) { Text("✕", color = MaterialTheme.colorScheme.error) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(what, { what = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("e.g. Oil change", fontSize = 12.sp) }, textStyle = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(every, { every = it.filter(Char::isDigit).take(2) }, Modifier.width(56.dp), singleLine = true, textStyle = MaterialTheme.typography.bodySmall, label = { Text("mo", fontSize = 10.sp) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TagChip(false, { pickDate(next) { next = it } }, label = { Text("📅 next ${shortDate(next.toString())}", fontSize = 12.sp) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Becomes", style = MaterialTheme.typography.bodySmall)
                    PickChip(becomes == "quick", "⚡ quick thing") { becomes = "quick" }
                    PickChip(becomes == "task", "📋 task in the table") { becomes = "task" }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TagChip(false, {
                        if (what.isNotBlank()) {
                            val u = Upkeep(editingUp ?: BoardOps.newId(), what.trim(), every.toIntOrNull()?.coerceAtLeast(1) ?: 6, next.toString(), becomes = becomes)
                            val i = upkeep.indexOfFirst { it.id == u.id }
                            if (i >= 0) upkeep[i] = u.copy(made = if (upkeep[i].next == u.next) upkeep[i].made else "") else upkeep.add(u)
                            what = ""; editingUp = null
                        }
                    }, label = { Text(if (editingUp != null) "✓ Update it" else "＋ Add", fontSize = 12.sp) })
                    if (editingUp != null) {
                        TagChip(false, { vm.saveAsset(build(start, cat, kind, name, desc, fields, upkeep), quiet = true); vm.makeUpkeepNow(start.id, editingUp!!); onDone(null) },
                            label = { Text("➜ Make it now", fontSize = 12.sp) })
                    }
                }
                Text("A week before its date it becomes what you chose, tied to this thing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!isNew) TextButton(onClick = { if (confirmRemove) { vm.removeAsset(start.id); onDone(null) } else confirmRemove = true }) {
                    Text(if (confirmRemove) "Tap again to remove it (its papers and costs go too)" else "Remove this thing", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { vm.saveAsset(build(start, cat, kind, name, desc, fields, upkeep)); onDone(start.id) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

private fun build(start: Asset, cat: String, kind: String, name: String, desc: String, fields: Map<String, String>, upkeep: List<Upkeep>): Asset {
    val keep = Things.CATS.first { it.key == cat }.fields.map { it.name }.toSet()
    return start.copy(cat = cat, sub = kind, type = "", name = name.trim(), desc = desc.trim(), details = "",
        fields = fields.filter { it.key in keep && it.value.isNotBlank() }, upkeep = upkeep.toList())
}
