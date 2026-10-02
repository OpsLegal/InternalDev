package com.opslegal.tda.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.BuyItem
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.plan.Shopping
import com.opslegal.tda.voice.VoiceState
import java.time.LocalDate

/** The cart in the top bar, left of Today: how many things are left to buy (red when it is worth a trip). */
@Composable
internal fun CartButton(vm: MainViewModel) {
    val board by vm.board.collectAsStateWithLifecycle()
    val left = Shopping.open(board).size
    val due = Shopping.due(board, LocalDate.now())
    Box {
        IconButton(onClick = { vm.buyStatus.value = null; vm.cartOpen.value = true }) { Icon(CartIcon, "To buy, $left left") }
        if (left > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).defaultMinSize(16.dp, 16.dp).clip(CircleShape)
                    .background(if (due) kindColor(TaskKind.DEADLINE) else Navy).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) { Text("$left", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * To buy: one list for home and work. Say it (one focused question) or type it; tick things off in the store;
 * a trip is one Errands cell on the table.
 */
@Composable
internal fun CartSheet(vm: MainViewModel) {
    val open by vm.cartOpen.collectAsStateWithLifecycle()
    if (!open) return
    val board by vm.board.collectAsStateWithLifecycle()
    val status by vm.buyStatus.collectAsStateWithLifecycle()
    val voice by vm.voiceState.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    var work by remember { mutableStateOf(false) }
    val listening = voice is VoiceState.Listening
    val mic = rememberWithMic { vm.listenToBuy() }
    val left = Shopping.open(board)
    val bought = board.buy.filter { it.done }
    val trip = board.tasks.firstOrNull { it.errands && it.steps.any { s -> !s.closed } }
    val close = { vm.cartOpen.value = false }
    fun add() {
        if (text.isBlank()) return
        vm.addToBuy(text.split(Regex("[,;\\n]")), work)
        text = ""
    }

    SoftDialog(
        keepOpen = true,
        onDismissRequest = close,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("To buy", modifier = Modifier.weight(1f))
                if (left.isNotEmpty()) Text("${left.size} left", style = MaterialTheme.typography.bodySmall)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundAction(
                        MicIcon, if (listening) "I'm done" else "Say what you need to buy",
                        if (listening) Recording else Navy, onClick = mic, size = 52.dp,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("What do you need to buy, and is any of it needed by a certain day?", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            status ?: "Tap the mic and say it, e.g. “toner for the office by Monday, milk and bread”.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactField(text, { text = it }, "Or type it…", Modifier.weight(1f))
                    TextButton(onClick = ::add) { Text("Add") }
                }
                Tags(listOf(false to "Home", true to "Work"), work, { work = it }, Modifier.widthIn(max = 220.dp))
                when {
                    trip != null -> Card(Modifier.fillMaxWidth()) {
                        Text(
                            "🛒 Errands planned" + (trip.steps.firstOrNull { !it.closed }?.date?.let { " on " + vm.dayName(LocalDate.parse(it)) } ?: "") +
                                ". Tick things off here in the store; marking the cell done ticks them all.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp),
                        )
                    }
                    left.isNotEmpty() -> Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (Shopping.due(board, LocalDate.now())) "Worth a trip. " else "") + "Put it all in one Errands cell on your table.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { vm.planTrip(); close() }) { Text("Plan a trip") }
                        }
                    }
                }
                Group("Home", left.filter { !it.work }, vm)
                Group("Work", left.filter { it.work }, vm)
                if (board.buy.isEmpty()) {
                    Text("Nothing to buy. Add here, or tell the assistant: “add printer toner”, « rappel d'acheter du lait ».", style = MaterialTheme.typography.bodySmall)
                }
                if (bought.isNotEmpty()) {
                    HorizontalDivider()
                    Group("Bought", bought, vm)
                    TextButton(onClick = vm::clearBought) { Text("Clear bought") }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
    )
}

@Composable
private fun Group(name: String, items: List<BuyItem>, vm: MainViewModel) {
    if (items.isEmpty()) return
    Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    items.forEach { item ->
        Row(Modifier.fillMaxWidth().clickable { vm.setBought(item.id, !item.done) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item.done, onCheckedChange = { vm.setBought(item.id, it) })
            Column(Modifier.weight(1f)) {
                Text(
                    item.text,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                item.needBy?.let { Text("by " + vm.dayName(LocalDate.parse(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            IconButton(onClick = { vm.removeToBuy(item.id) }) { Text("×", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
