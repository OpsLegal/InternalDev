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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.opslegal.tda.data.ProviderKind
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

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
