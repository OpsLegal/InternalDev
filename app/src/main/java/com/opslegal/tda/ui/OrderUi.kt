package com.opslegal.tda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Order
import com.opslegal.tda.core.plan.Planner
import java.time.LocalDate

/** Settings › My day: what comes first. Seven groups in order, ↑↓ to change it; the planner, Fill and Replan follow it. */
@Composable
internal fun OrderList(vm: MainViewModel, board: Board) {
    val order = Order.order(board)
    Text("What comes first", style = MaterialTheme.typography.labelLarge)
    Text("The order for filling your days, Replan and 📥 Fill. Something new that outranks what is planned takes its place from tomorrow on; the rest steps aside, in order.",
        style = MaterialTheme.typography.bodySmall)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        order.forEachIndexed { i, k ->
            val g = Order.GROUPS.first { it.key == k }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${i + 1}", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("${g.icon} ${g.name}", fontSize = 13.sp)
                    Text(g.about, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.moveOrder(k, -1) }, enabled = i > 0) { Text("↑") }
                TextButton(onClick = { vm.moveOrder(k, 1) }, enabled = i < order.lastIndex) { Text("↓") }
            }
        }
    }
    if (board.settings.order.isNotEmpty()) TextButton(onClick = { vm.resetOrder() }) { Text("Back to the usual order", fontSize = 12.sp) }
}

/**
 * 🚨 An emergency that could not get today: asked once, with what would move shown before the tap. Big: a free cell,
 * else today's lowest cell moves to the next day with room. Small: today's ⚡ line (its lowest quick thing moves to tomorrow).
 */
@Composable
internal fun EmergencyDialog(vm: MainViewModel) {
    val board by vm.board.collectAsStateWithLifecycle()
    val asked by vm.emergencyAsk.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val auto = remember(board) { Order.toAsk(board, today)?.second?.id }
    val id = asked ?: auto ?: return
    val (task, step) = BoardOps.findStep(board, id) ?: return
    val plan = remember(board, id) { Order.today(board, id, today) }
    val due = Order.due(board, task)
    val title = Planner.cellTitle(task, step)
    val what = when {
        plan == null -> "Today is full of emergencies and dated cells: nothing can move."
        plan.small -> "On today's ⚡ line" + if (plan.outTitle.isNotBlank()) "; “${plan.outTitle}” moves to tomorrow." else "."
        plan.free -> "In a free cell today."
        else -> "In “${plan.outTitle}”'s place; it moves to ${plan.to?.let { Order.short(it) }}."
    }
    val planned = step.date?.let { LocalDate.parse(it) }?.takeIf { it.isAfter(today) }
    SoftDialog(
        onDismissRequest = { vm.emergencyKeep(id) },
        title = { Text("🚨 An emergency") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title + (due?.let { " · due ${Order.short(LocalDate.parse(it))}" } ?: ""), fontWeight = FontWeight.SemiBold)
                Text((planned?.let { "Planned ${Order.short(it)} (${inDays(today, it)}). " } ?: "") + "Do it today?", style = MaterialTheme.typography.bodySmall)
                Text(what, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFB3261E).copy(alpha = 0.08f)).padding(10.dp))
            }
        },
        confirmButton = { Button(enabled = plan != null, onClick = { vm.emergencyToday(id) }) { Text("🚨 Today") } },
        dismissButton = { TextButton(onClick = { vm.emergencyKeep(id) }) { Text(planned?.let { "Keep ${Order.short(it)}" } ?: "Not today") } },
    )
}
