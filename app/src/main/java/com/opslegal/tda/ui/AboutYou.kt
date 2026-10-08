package com.opslegal.tda.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opslegal.tda.core.model.Value
import com.opslegal.tda.core.plan.Values

/** First launch: what the app is, in four pictures, then Get started (or Later). */
@Composable
internal fun WelcomeCard(reviewTimes: List<String>, onDone: () -> Unit, onGetStarted: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth().padding(bottom = 6.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Welcome to Docket 5", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            WelcomeLine({ MiniCells() }, "Your day is 5 cells.", "One cell is about 1.5 h of focused work. A full yellow row is a good day.")
            WelcomeLine({ MiniRound(Navy) { Icon(MicIcon, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp)) } },
                "Say it, it's placed.", "Tap the mic and say what you have to do. The assistant finds the cell.")
            WelcomeLine({ MiniBell() }, "Look only when it's red.",
                "Something urgent, or your review time" + (if (reviewTimes.isNotEmpty()) " (${reviewTimes.joinToString(" and ")})" else "") + ". The rest can wait.")
            WelcomeLine({ MiniBars() }, "A balanced week.", "Ground, Build, Nourish: work that moves you forward and time that gives you energy.")
            Cta("Get started", "2 minutes", onGetStarted)
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Later: show me the table") }
        }
    }
}

@Composable
private fun WelcomeLine(pic: @Composable () -> Unit, title: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) { pic() }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MiniCells() = Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    listOf(true, true, false, false, false).forEach { y ->
        androidx.compose.foundation.layout.Box(
            Modifier.size(10.dp, 26.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                .background(if (y) DoneYellow else MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun MiniRound(color: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.Box(Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color), contentAlignment = Alignment.Center) { content() }

@Composable
private fun MiniBell() = androidx.compose.foundation.layout.Box {
    MiniRound(MaterialTheme.colorScheme.surfaceVariant) { Icon(BellIcon, null, modifier = Modifier.size(20.dp)) }
    androidx.compose.foundation.layout.Box(Modifier.align(Alignment.TopEnd).size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.error))
}

@Composable
private fun MiniBars() = Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
    listOf(com.opslegal.tda.core.plan.Gbn.GROUND to 14, com.opslegal.tda.core.plan.Gbn.GROUND to 22, com.opslegal.tda.core.plan.Gbn.BUILD to 30,
        com.opslegal.tda.core.plan.Gbn.BUILD to 18, com.opslegal.tda.core.plan.Gbn.BUILD to 10, com.opslegal.tda.core.plan.Gbn.NOURISH to 26,
        com.opslegal.tda.core.plan.Gbn.NOURISH to 18, com.opslegal.tda.core.plan.Gbn.NOURISH to 30).forEach { (b, h) ->
        androidx.compose.foundation.layout.Box(Modifier.size(5.dp, h.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(bucketColor(b)))
    }
}

/**
 * Tell us a little about you: from a few lines (spoken or typed) the assistant proposes what matters and how much each
 * counts, for the user to change. Ready-made profiles are the shortcut, each shown with its values before choosing.
 */
@Composable
internal fun AboutYouCard(vm: MainViewModel, onVoice: () -> Unit, onSkip: (() -> Unit)?) {
    var typing by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Tell me a little about you", style = MaterialTheme.typography.titleSmall)
            Text(
                "Your work, the people who count, what you tend to put off. I'll suggest what matters to you and how much each counts, " +
                    "so I order your priorities the way you would. You can change everything.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onVoice) { Icon(MicIcon, contentDescription = null); Text("  Tell me") }
                OutlinedButton(onClick = { typing = true }) { Text("Type it") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { profiles = true }) { Text("Or start from a ready-made profile") }
                onSkip?.let { TextButton(onClick = it) { Text("Skip") } }
            }
        }
    }
    if (typing) AboutYouTyping(vm.board.value.about.bio, onSend = { vm.aboutMe(it); typing = false }, onCancel = { typing = false })
    if (profiles) ProfilesDialog(onUse = { vm.chooseProfile(it); profiles = false }, onDone = { profiles = false })
}

@Composable
private fun AboutYouTyping(start: String, onSend: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(start) }
    SoftDialog(
        keepOpen = true,
        onDismissRequest = onCancel,
        title = { Text("About you") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A few lines are enough: e.g. “Lawyer, two kids, I neglect sport, I put off long reading.”", style = MaterialTheme.typography.bodySmall)
                CompactField(text, { text = it }, "In your words", Modifier.fillMaxWidth(), singleLine = false, minLines = 4)
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSend(text.trim()) }) { Text("Suggest what matters") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** The ready-made profiles: tap one to see its values and how much each counts, then use it or go back. */
@Composable
internal fun ProfilesDialog(onUse: (String) -> Unit, onDone: () -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    val shown = open?.let { Values.profiles[it] }
    SoftDialog(
        onDismissRequest = onDone,
        title = { Text(shown?.first ?: "Ready-made profiles") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shown == null) {
                    Text("Tap one to see what it puts first. You can adjust it after.", style = MaterialTheme.typography.bodySmall)
                    Values.profiles.forEach { (id, p) ->
                        OutlinedButton(onClick = { open = id }, modifier = Modifier.fillMaxWidth()) { Text(p.first) }
                    }
                } else {
                    Text("What counts most is first. ●●● counts a lot, ● a little.", style = MaterialTheme.typography.bodySmall)
                    shown.second.sortedByDescending { it.weight }.forEach { ValueLine(it) }
                }
            }
        },
        confirmButton = {
            if (open != null) Button(onClick = { onUse(open!!) }) { Text("Use this profile") }
            else TextButton(onClick = onDone) { Text("Close") }
        },
        dismissButton = { if (open != null) TextButton(onClick = { open = null }) { Text("Back") } },
    )
}

@Composable
private fun ValueLine(v: Value) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(v.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("●".repeat(v.weight.coerceIn(1, 3)) + "○".repeat(3 - v.weight.coerceIn(1, 3)), color = MaterialTheme.colorScheme.primary)
        }
        if (v.meaning.isNotBlank()) Text(v.meaning, style = MaterialTheme.typography.bodySmall)
        v.minPerWeek?.let { Text("At least $it a week", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
