package com.opslegal.tda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.agent.Pricing
import java.time.LocalDate
import java.util.Locale

private fun usd(x: Double) = if (x < 0.01 && x > 0) "< $0.01" else "$" + "%.2f".format(Locale.US, x)
private fun tokens(n: Long) = when { n >= 1_000_000 -> "%.1fM".format(Locale.US, n / 1e6); n >= 1000 -> "${n / 1000}k"; else -> "$n" }

/** This month's AI use: the total, what used it, the budget and the lighter model for automatic checks. */
@Composable
internal fun UsageCard(vm: MainViewModel) {
    val board by vm.board.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val month = LocalDate.now().toString().take(7)
    val ledger = board.usage
    val lines = ledger.month(month)
    val total = ledger.usd(month)
    var budgetOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("This month", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("about ${usd(total)}", fontWeight = FontWeight.Bold)
            }
            Text("${tokens(ledger.tokens(month))} tokens on your own AI account · counted from what your provider reports; the price is an estimate, your provider's bill is the reference.",
                style = MaterialTheme.typography.bodySmall)
            ledger.budget?.let { b ->
                val f = (total / b).coerceIn(0.0, 1.0).toFloat()
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(f).height(6.dp).background(if (ledger.overBudget(month)) MaterialTheme.colorScheme.error else if (ledger.nearBudget(month)) androidx.compose.ui.graphics.Color(0xFFE0A100) else MaterialTheme.colorScheme.primary))
                }
                Text(when {
                    ledger.overBudget(month) -> "Budget of ${usd(b)} reached: automatic checks are paused until next month. What you ask still works."
                    ledger.nearBudget(month) -> "${(f * 100).toInt()}% of your ${usd(b)} budget."
                    else -> "${(f * 100).toInt()}% of your ${usd(b)} monthly budget."
                }, style = MaterialTheme.typography.bodySmall, color = if (ledger.overBudget(month)) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
            }
            if (lines.isEmpty()) Text("Nothing used yet this month.", style = MaterialTheme.typography.bodySmall)
            Pricing.FEATURES.forEach { (k, name) ->
                val l = lines[k] ?: return@forEach
                val share = if (total > 0) (l.usd / total * 100).toInt() else 0
                Row {
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${usd(l.usd)}${if (total > 0) " · $share%" else ""} · ${l.calls}×", style = MaterialTheme.typography.bodySmall)
                }
            }
            // The main model: the user's choice, with what it costs (per million tokens, in / out).
            if (settings.provider == com.opslegal.tda.data.ProviderKind.ANTHROPIC) {
                Text("Main model (chat, ✨ Prepare) · price per million tokens in / out", style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("claude-opus-5-5" to "Opus 5.5 · $4 / $20", "claude-sonnet-5-5" to "Sonnet 5.5 · $2 / $10").forEach { (m, label) ->
                        TagChip(settings.model == m, { vm.updateSettings { it.copy(model = m) } }, label = { Text(label) })
                    }
                }
                if (settings.model !in setOf("claude-opus-5-5", "claude-sonnet-5-5")) Text("Now: ${settings.model}" + (if (settings.model.startsWith("claude-opus-5") && !settings.model.startsWith("claude-opus-5-5")) " · $5 / $25" else ""), style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lighter model for automatic checks")
                    Text(if (settings.provider == com.opslegal.tda.data.ProviderKind.ANTHROPIC) "Checking mail and messages, and reading receipts, use ${Pricing.LIGHT_MODEL} ($0.10 / $0.50), far cheaper. The chat and ✨ Prepare keep the main model."
                        else "Available with a Claude account.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(settings.lighterChecks, { on -> vm.updateSettings { it.copy(lighterChecks = on) } }, enabled = settings.provider == com.opslegal.tda.data.ProviderKind.ANTHROPIC)
            }
            TextButton(onClick = { budgetOpen = true }) { Text(if (ledger.budget == null) "Set a monthly budget" else "Change the budget (${usd(ledger.budget!!)})") }
        }
    }
    if (budgetOpen) {
        var text by remember { mutableStateOf(ledger.budget?.let { "%.0f".format(Locale.US, it) }.orEmpty()) }
        androidx.compose.material3.AlertDialog(onDismissRequest = { budgetOpen = false }, title = { Text("Monthly budget") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("At 80% you get a warning. At 100%, automatic checks pause until next month; what you ask for keeps working.", style = MaterialTheme.typography.bodySmall)
                CompactField(text, { text = it.filter { c -> c.isDigit() || c == '.' } }, "US dollars a month, e.g. 10", Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(onClick = { vm.setBudget(text.toDoubleOrNull()?.takeIf { it > 0 }); budgetOpen = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { vm.setBudget(null); budgetOpen = false }) { Text("No budget") } })
    }
}
