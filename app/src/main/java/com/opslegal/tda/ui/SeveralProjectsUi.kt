package com.opslegal.tda.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opslegal.tda.core.model.Project
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.QuickProjects
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Several projects at once (the year's rocks, a meeting's list): one per line, typed or said, with an end date in plain
 * words. Each line shows where it ends as it is typed. Create them as they are, or ✨ with steps for each, checked
 * before anything is saved.
 */
@Composable
internal fun SeveralProjectsDialog(vm: MainViewModel, close: () -> Unit) {
    val today = LocalDate.now()
    var text by remember { mutableStateOf("") }
    val items = remember(text) { QuickProjects.parse(text, today) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // After ✨: each project with its proposed steps, ticked to keep.
    val drafts = remember { mutableStateListOf<Triple<QuickProjects.Item, List<com.opslegal.tda.core.plan.Pacing.Filled>, Boolean>>() }
    val scope = rememberCoroutineScope()
    fun save(withSteps: Boolean) {
        val list = if (withSteps) drafts.filter { it.third } else items.map { Triple(it, emptyList(), true) }
        list.forEach { (item, steps, _) ->
            vm.saveProject(Project(item.name, deadline = item.deadline?.toString()), null,
                steps.map { BoardOps.EditedStep(null, it.title, waitDays = it.waitDays, waitFor = it.waitFor, added = it.added) })
        }
        vm.notify("${list.size} project${if (list.size > 1) "s" else ""} created" + if (withSteps) ", with their steps on your table." else ". Open one to add its steps (✨ in the project).")
        close()
    }
    SoftDialog(keepOpen = true, onDismissRequest = close, title = { Text("Several projects") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (drafts.isEmpty()) {
                Text("One per line, with an end date if there is one: “Contacter LIA by Nov 30”, “Site web – fin d'année”.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.Top) {
                    CompactField(text, { text = it }, "Your projects, one per line", Modifier.weight(1f), singleLine = false, minLines = 5)
                    DictateButton(Modifier.padding(start = 6.dp, top = 7.dp)) { said -> text = listOf(text.trim(), said.trim()).filter { it.isNotBlank() }.joinToString("\n") }
                }
                // The consequence at the moment of the choice: what each line becomes.
                items.forEach { i ->
                    Row { Text("📁 ${i.name}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(i.deadline?.let { "ends ${vm.dayName(it)} ${it.year.takeIf { y -> y != today.year } ?: ""}".trim() } ?: "no end date", style = MaterialTheme.typography.bodySmall) }
                }
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); status?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = items.isNotEmpty() && !busy, onClick = { save(false) }) { Text("Create ${items.size}") }
                    Button(enabled = items.isNotEmpty() && !busy, onClick = {
                        busy = true
                        scope.launch {
                            items.forEachIndexed { n, item ->
                                status = "Steps for ${item.name} (${n + 1} of ${items.size})…"
                                val plan = runCatching { vm.draftPlan(item.name, "normal", item.deadline?.toString(), emptyList(), item.name, null, emptyList(), null) }.getOrNull()
                                drafts += Triple(item, plan?.steps.orEmpty(), true)
                            }
                            busy = false
                            if (drafts.all { it.second.isEmpty() }) { drafts.clear(); status = null; vm.notify("The steps could not be made now (is your AI connected?). Create them as they are, and add steps later.") }
                        }
                    }) { Text("✨ With steps") }
                }
            } else {
                Text("Check each one, untick what you don't want, then Save. Nothing is on your table yet.", style = MaterialTheme.typography.bodySmall)
                drafts.forEachIndexed { i, (item, steps, keep) ->
                    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(keep, { drafts[i] = Triple(item, steps, it) })
                            Column(Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.SemiBold)
                                Text(item.deadline?.let { "ends ${vm.dayName(it)}" } ?: "no end date", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (steps.isEmpty()) Text("No steps proposed: it is created empty.", style = MaterialTheme.typography.bodySmall)
                        steps.forEachIndexed { n, st -> Text("${n + 1}. ${st.title}" + if (st.waitDays > 0) " · waits ${st.waitDays} d" else "", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }, confirmButton = { if (drafts.isNotEmpty()) Button(enabled = drafts.any { it.third }, onClick = { save(true) }) { Text("Save ${drafts.count { it.third }}") } else TextButton(onClick = close) { Text("Close") } },
        dismissButton = { if (drafts.isNotEmpty()) TextButton(onClick = { drafts.clear() }) { Text("Back to the list") } })
}
