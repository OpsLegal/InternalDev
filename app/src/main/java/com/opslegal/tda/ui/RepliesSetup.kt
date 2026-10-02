package com.opslegal.tda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.agent.ReplyWriter

/**
 * "Your assistant for replies": four short steps, and nothing is on until the user says OK at each one.
 * 1 the rule that never changes, 2 what to help with, 3 Microsoft's permission for drafts, 4 a test draft.
 */
@Composable
internal fun RepliesSetup(vm: MainViewModel) {
    val step by vm.repliesWizard.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()
    val msError by vm.microsoftError.collectAsStateWithLifecycle()
    val msBusy by vm.microsoftBusy.collectAsStateWithLifecycle()
    val s = step ?: return
    val r = board.replies
    val go = { n: Int? -> vm.repliesWizard.value = n }
    // Step 4 puts a test draft in Outlook; "Try again" runs it once more.
    var attempt by remember { mutableIntStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    var testProblem by remember { mutableStateOf<String?>(null) }
    var tested by remember { mutableStateOf(false) }
    LaunchedEffect(s, attempt) {
        if (s != 4) return@LaunchedEffect
        testing = true; testProblem = null; tested = false
        testProblem = vm.testDraft()
        tested = testProblem == null
        testing = false
    }

    SoftDialog(
        keepOpen = true,
        onDismissRequest = { go(null) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your assistant for replies", modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    (1..4).forEach { n ->
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (n <= s) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
                    }
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (s) {
                    1 -> {
                        Text("First, the rule that never changes", fontWeight = FontWeight.SemiBold)
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
                            Text("🔒 ${ReplyWriter.RULE_1}")
                        }
                        Text(
                            "Not you, not me, not any setting can change it. It is built in: Docket 5 is not even given the permission to send.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    2 -> {
                        Text("What may I help you answer?", fontWeight = FontWeight.SemiBold)
                        Choice("Emails (Outlook 365)", "I write the reply into your Outlook Drafts. You send it from Outlook.", r.email) { on -> vm.editReplies { it.copy(email = on) } }
                        Choice("Messages (Beeper)", "WhatsApp, SMS… I write the reply and open Beeper. You paste and send.", r.messages) { on -> vm.editReplies { it.copy(messages = on) } }
                        Choice("Meeting requests", "I suggest accept, decline or a better time from your day, and write the answer as a draft. You send it, and accept or decline in Outlook.", r.meetings) { on -> vm.editReplies { it.copy(meetings = on) } }
                    }
                    3 -> {
                        Text("One permission from Microsoft", fontWeight = FontWeight.SemiBold)
                        if (vm.canDraft()) {
                            Text("Outlook drafts are already allowed ✓")
                        } else {
                            Text("To put drafts in your Outlook, Microsoft asks you to allow “Read and write access to your mail”.")
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
                                Text(
                                    "Why “write”? It only means drafts. Docket 5 never asks for “Send mail”, so it can't send, even by mistake. You can remove it anytime in your Microsoft account.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text("On Microsoft's page: choose your work account, check the list, then tap Accept. You come back here by yourself.", style = MaterialTheme.typography.bodySmall)
                            if (msBusy) { Text("Finishing with Microsoft…", style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                            Button(enabled = !msBusy, onClick = { vm.signInForDrafts() }, modifier = Modifier.fillMaxWidth()) { Text("Continue to Microsoft") }
                        }
                        msError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    else -> {
                        Text("Let's check it works", fontWeight = FontWeight.SemiBold)
                        if (testing) { Text("Putting a test draft in your Outlook…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        testProblem?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { attempt++ }) { Text("Try again") }
                        }
                        if (tested) {
                            Text("I've put a test draft in your Outlook Drafts: “Docket 5 test, you can delete me”.")
                            Text("Open Outlook → Drafts. Do you see it?")
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (s) {
                1 -> TextButton(onClick = { go(2) }) { Text("I understand") }
                2 -> TextButton(enabled = r.email || r.messages, onClick = {
                    if (r.email) go(3) else { vm.finishRepliesSetup() }
                }) { Text("OK, continue") }
                3 -> if (vm.canDraft()) TextButton(onClick = { go(4) }) { Text("Continue") }
                else -> TextButton(enabled = tested, onClick = { vm.finishRepliesSetup() }) { Text("Yes, I see it") }
            }
        },
        dismissButton = {
            TextButton(onClick = { go(if (s == 1) null else s - 1) }) { Text(if (s == 1) "Not now" else "Back") }
        },
    )
}

@Composable
private fun Choice(title: String, what: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(what, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
