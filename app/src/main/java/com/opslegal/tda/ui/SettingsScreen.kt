package com.opslegal.tda.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.agent.AnthropicProvider
import com.opslegal.tda.core.model.ConfirmationPolicy
import com.opslegal.tda.core.model.ConversationSettings
import com.opslegal.tda.data.ProviderKind
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier, onEnableDailyReview: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val board by vm.board.collectAsStateWithLifecycle()
    val premium by vm.premium.collectAsStateWithLifecycle()
    val offers by vm.offers.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    var key by remember { mutableStateOf("") }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Your AI", style = MaterialTheme.typography.titleLarge)
        Text(
            "The assistant runs on your own AI account and uses your tokens. Your key stays encrypted on this phone and is only sent to the provider you pick.",
            style = MaterialTheme.typography.bodySmall,
        )
        ProviderKind.entries.forEach { kind ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = settings.provider == kind,
                    onClick = { vm.updateSettings { it.copy(provider = kind, model = kind.defaultModel) } },
                )
                Text(kind.label)
            }
        }
        OutlinedTextField(
            value = settings.model,
            onValueChange = { m -> vm.updateSettings { it.copy(model = m.trim()) } },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (settings.provider == ProviderKind.ANTHROPIC) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AnthropicProvider.SUGGESTED_MODELS.forEach { m ->
                    FilterChip(selected = settings.model == m, onClick = { vm.updateSettings { it.copy(model = m) } }, label = { Text(m.removePrefix("claude-")) })
                }
            }
        }
        if (settings.provider == ProviderKind.COMPATIBLE) {
            OutlinedTextField(
                value = settings.baseUrl,
                onValueChange = { u -> vm.updateSettings { it.copy(baseUrl = u.trim()) } },
                label = { Text("Base URL, e.g. https://api.mistral.ai/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(if (settings.hasApiKey) "API key saved. Paste a new one to replace it" else "API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.setApiKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Save key") }
            if (settings.hasApiKey) OutlinedButton(onClick = { vm.setApiKey(null) }) { Text("Remove key") }
        }

        HorizontalDivider()
        Text("Planning", style = MaterialTheme.typography.titleLarge)
        Text("Days the planner can fill")
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            DayOfWeek.entries.forEach { day ->
                val on = day.value in board.settings.workDays
                FilterChip(
                    selected = on,
                    onClick = {
                        vm.editAndPlan { b ->
                            val days = if (on) b.settings.workDays - day.value else (b.settings.workDays + day.value).sorted()
                            if (days.isEmpty()) b else b.copy(settings = b.settings.copy(workDays = days))
                        }
                    },
                    label = { Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault())) },
                )
            }
        }
        Text("Day letters")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(settings.dayLanguage == "en", { vm.updateSettings { it.copy(dayLanguage = "en") } }, label = { Text("M Tu W Th F") })
            FilterChip(settings.dayLanguage == "fr", { vm.updateSettings { it.copy(dayLanguage = "fr") } }, label = { Text("L Ma Me J V") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Morning AI review")
                Text("Every morning the assistant checks the next days and flags risks.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = settings.dailyAiReview,
                enabled = premium,
                onCheckedChange = { on ->
                    vm.updateSettings { it.copy(dailyAiReview = on) }
                    if (on) onEnableDailyReview()
                },
            )
        }

        HorizontalDivider()
        VoiceSettings(board.conversation, vm::editConversation)

        HorizontalDivider()
        Text("TDA Premium", style = MaterialTheme.typography.titleLarge)
        if (premium) {
            Text("Premium is active. Thank you!")
        } else {
            Text("The table and the widget are free. Premium unlocks the AI assistant and the morning review.")
            if (offers.isEmpty()) Text("Plans are loading from Google Play...", style = MaterialTheme.typography.bodySmall)
            offers.forEach { offer ->
                Button(onClick = { activity?.let { vm.buy(it, offer) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("${offer.basePlanId.replaceFirstChar { it.uppercase() }}: ${offer.price}")
                }
            }
        }
    }
}

private val voiceLanguages = listOf(
    "en-US" to "English (US)",
    "en-GB" to "English (UK)",
    "fr-FR" to "Français (France)",
    "fr-CA" to "Français (Canada)",
)

/** How the assistant listens, talks and makes sure it understood. */
@Composable
private fun VoiceSettings(talk: ConversationSettings, edit: (((ConversationSettings) -> ConversationSettings)) -> Unit) {
    Text("Talking with the assistant", style = MaterialTheme.typography.titleLarge)

    Text("Before changing my table", style = MaterialTheme.typography.titleSmall)
    Column {
        listOf(
            ConfirmationPolicy.ALWAYS to "Always repeat what you understood and wait for my yes",
            ConfirmationPolicy.IMPORTANT to "Only for moves, deletions, edits and rules",
            ConfirmationPolicy.NEVER to "Act directly",
        ).forEach { (policy, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = talk.confirmation == policy, onClick = { edit { it.copy(confirmation = policy) } })
                Text(label)
            }
        }
    }
    SwitchRow(
        "Ask me when something is unclear",
        "One short question at a time instead of guessing the day, the task or the effort.",
        talk.askWhenUnsure,
    ) { on -> edit { it.copy(askWhenUnsure = on) } }

    Text("Listening", style = MaterialTheme.typography.titleSmall)
    Text("Language")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        voiceLanguages.forEach { (tag, label) ->
            FilterChip(talk.voiceLanguage == tag, { edit { it.copy(voiceLanguage = tag) } }, label = { Text(label.substringBefore(" (") + " " + tag.takeLast(2)) })
        }
    }
    Text("Pause before the assistant answers: ${"%.1f".format(talk.pauseSeconds)} s")
    Slider(
        value = talk.pauseSeconds,
        onValueChange = { v -> edit { it.copy(pauseSeconds = (v * 2).roundToInt() / 2f) } },
        valueRange = 1f..10f,
    )
    SwitchRow(
        "Wait longer when my sentence sounds unfinished",
        "If you stop on \"and\", \"because\", \"et\", \"parce que\"..., the pause is doubled.",
        talk.waitWhenUnfinished,
    ) { on -> edit { it.copy(waitWhenUnfinished = on) } }
    ListField("Phrases that mean \"I'm done\"", talk.endPhrases) { list -> edit { it.copy(endPhrases = list) } }
    SwitchRow(
        "Keep listening after each answer",
        "Hands-free conversation. The assistant always listens after it asks you something.",
        talk.handsFree,
    ) { on -> edit { it.copy(handsFree = on) } }

    Text("Speaking", style = MaterialTheme.typography.titleSmall)
    SwitchRow("Read answers aloud", "When you talked to it.", talk.speakReplies) { on -> edit { it.copy(speakReplies = on) } }
    Text("Voice speed: ${"%.1f".format(talk.speechRate)}x")
    Slider(
        value = talk.speechRate,
        onValueChange = { v -> edit { it.copy(speechRate = (v * 10).roundToInt() / 10f) } },
        valueRange = 0.6f..1.6f,
    )

    Text("Confirming", style = MaterialTheme.typography.titleSmall)
    ListField("Words that mean yes", talk.yesWords) { list -> edit { it.copy(yesWords = list) } }
    ListField("Words that mean no", talk.noWords) { list -> edit { it.copy(noWords = list) } }
    TextButton(onClick = { edit { ConversationSettings() } }) { Text("Reset to defaults") }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A comma-separated list the user can edit freely; saved as they type. */
@Composable
private fun ListField(label: String, values: List<String>, onChange: (List<String>) -> Unit) {
    fun parse(t: String) = t.split(",").map(String::trim).filter(String::isNotEmpty)
    var text by remember { mutableStateOf(values.joinToString(", ")) }
    // Follow outside changes (e.g. "Reset to defaults") without fighting the user's typing.
    LaunchedEffect(values) { if (parse(text) != values) text = values.joinToString(", ") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(parse(it))
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
}
