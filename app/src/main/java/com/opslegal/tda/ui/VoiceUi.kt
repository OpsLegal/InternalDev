package com.opslegal.tda.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.voice.VoiceState
import java.time.LocalDate

/**
 * The mic action with the microphone permission handled: asks once, then starts listening.
 * Call it with a day to talk about that day of the table, or null for a general conversation.
 */
@Composable
internal fun rememberMicAction(vm: MainViewModel): (LocalDate?) -> Unit {
    val context = LocalContext.current
    var waitingDay by remember { mutableStateOf<LocalDate?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.micTapped(waitingDay)
    }
    return { day ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.micTapped(day)
        } else {
            waitingDay = day
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/**
 * The voice conversation at the bottom of the table: live subtitles of what is heard, the
 * assistant's answer as text (for anyone who can't hear it well), and changes waiting for a yes.
 * Shows nothing when there is nothing to say.
 */
@Composable
internal fun VoiceDock(vm: MainViewModel, onMic: () -> Unit, modifier: Modifier = Modifier) {
    val voice by vm.voiceState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val reply by vm.lastReply.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()
    val listening = voice is VoiceState.Listening
    val show = listening || busy || voice == VoiceState.Speaking || voice is VoiceState.Error ||
        reply != null || pending.isNotEmpty() || error != null
    if (!show) return

    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                listening || voice is VoiceState.Error ->
                    VoicePanel(
                        voice, board.conversation.endPhrases.firstOrNull(), onFinish = onMic, onStop = vm::stopVoice,
                        languages = board.conversation.languages, onLanguage = vm::switchLanguage,
                    )
                busy -> {
                    Text("Thinking...", style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            reply?.let { text ->
                if (!listening && !busy) {
                    Text(
                        if (voice == VoiceState.Speaking) "Assistant (speaking)" else "Assistant",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    // Subtitles: large enough to read while glancing at the phone.
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (pending.isNotEmpty() && !listening) {
                ConfirmCard(pending.map { it.summary }, enabled = !busy, onYes = { vm.confirm(spoken = true) }, onNo = { vm.reject(spoken = true) })
            }
            if (!listening && !busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (voice == VoiceState.Speaking) TextButton(onClick = vm::stopVoice) { Text("Stop voice") }
                    TextButton(onClick = onMic) { Text("Answer") }
                    TextButton(onClick = { vm.dismissReply(); vm.dismissError(); vm.stopVoice() }) { Text("Close") }
                }
            }
        }
    }
}

@Composable
internal fun ConfirmCard(changes: List<String>, enabled: Boolean, onYes: () -> Unit, onNo: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Waiting for your OK", style = MaterialTheme.typography.labelLarge)
            changes.forEach { Text("• ${it.replaceFirstChar { c -> c.uppercase() }}", style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = onYes, enabled = enabled) { Text("Yes, do it") }
                OutlinedButton(onClick = onNo, enabled = enabled) { Text("No") }
            }
            Text("You can also just say yes or no.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Shows what is being heard and how the turn will end, so the user can take their time. */
@Composable
internal fun VoicePanel(
    state: VoiceState,
    endPhrase: String?,
    onFinish: () -> Unit,
    onStop: () -> Unit,
    languages: List<String> = emptyList(),
    onLanguage: (String) -> Unit = {},
) {
    when (state) {
        is VoiceState.Listening -> Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val french = state.language.startsWith("fr")
                Text(
                    if (french) "Je t'écoute. Prends ton temps." else "I'm listening. Take your time.",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(state.heard.ifBlank { "..." }, style = MaterialTheme.typography.bodyLarge)
                val how = buildString {
                    if (french) {
                        append("Touche le micro quand tu as fini")
                        endPhrase?.let { append(", ou dis « c'est tout »") }
                        state.waitingMs?.let { append(". Sinon je réponds après ${it / 1000.0} s de silence") }
                    } else {
                        append("Tap the mic when you're done")
                        endPhrase?.let { append(", or say \"$it\"") }
                        state.waitingMs?.let { append(". Otherwise I answer after a ${it / 1000.0} s pause") }
                    }
                    append(".")
                }
                Text(how, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Which language is being heard; tap another if the phone guessed wrong.
                    if (languages.size > 1) {
                        languages.forEach { tag ->
                            FilterChip(
                                selected = tag == state.language,
                                onClick = { onLanguage(tag) },
                                label = { Text(tag.substringBefore('-').uppercase()) },
                            )
                        }
                    }
                    TextButton(onClick = onStop) { Text(if (french) "Annuler" else "Cancel") }
                }
            }
        }
        VoiceState.Speaking -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Speaking...", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onFinish) { Text("Interrupt and talk") }
            TextButton(onClick = onStop) { Text("Stop") }
        }
        is VoiceState.Error -> Text(
            state.message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        VoiceState.Idle -> Unit
    }
}

/** A simple microphone glyph, so no extended icon library is needed. */
internal val MicIcon: ImageVector = ImageVector.Builder("Mic", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 14f)
        curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
        lineTo(15f, 5f)
        curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
        curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
        lineTo(9f, 11f)
        curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
        close()
        moveTo(17.3f, 11f)
        curveTo(17.3f, 14f, 14.76f, 16.1f, 12f, 16.1f)
        curveTo(9.24f, 16.1f, 6.7f, 14f, 6.7f, 11f)
        lineTo(5f, 11f)
        curveTo(5f, 14.41f, 7.72f, 17.23f, 11f, 17.72f)
        lineTo(11f, 21f)
        lineTo(13f, 21f)
        lineTo(13f, 17.72f)
        curveTo(16.28f, 17.24f, 19f, 14.42f, 19f, 11f)
        close()
    }
}.build()
