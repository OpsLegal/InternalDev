package com.opslegal.tda.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opslegal.tda.data.ProviderKind
import kotlinx.coroutines.launch

/** What the user needs to get a key from one AI company, in three short steps. */
private data class KeyGuide(val name: String, val company: String, val site: String, val keysPage: String, val prefix: String, val steps: List<String>)

private val guides = mapOf(
    ProviderKind.ANTHROPIC to KeyGuide(
        "Claude", "Anthropic", "console.anthropic.com", "https://console.anthropic.com/settings/keys", "sk-ant-",
        listOf(
            "Sign in with the email you use for Claude (or create an account).",
            "Billing: add \$10 of credit. It is separate from a Claude subscription and lasts a long time for one person.",
            "API keys: Create key, name it “Docket 5”, then Copy. It is shown only once.",
        ),
    ),
    ProviderKind.OPENAI to KeyGuide(
        "ChatGPT", "OpenAI", "platform.openai.com", "https://platform.openai.com/api-keys", "sk-",
        listOf(
            "Sign in with the email you use for ChatGPT (or create an account).",
            "Billing: add \$10 of credit. It is separate from a ChatGPT Plus subscription.",
            "API keys: Create new secret key, name it “Docket 5”, then Copy. It is shown only once.",
        ),
    ),
)

/**
 * Connect your AI in three steps: choose Claude or ChatGPT, get the key on their site, paste and test.
 * The key is saved only after a real test call succeeds.
 */
@Composable
internal fun AiWizard(vm: MainViewModel, onDismiss: () -> Unit) {
    var step by remember { mutableIntStateOf(1) }
    var kind by remember { mutableStateOf(ProviderKind.ANTHROPIC) }
    var key by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val guide = guides.getValue(kind)

    SoftDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connect your AI", modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    (1..3).forEach { n ->
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (n <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (step) {
                    1 -> {
                        Text("Which AI do you want your assistant to use?", style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            RoundAction(ChatIcon, "Claude by Anthropic", Navy, onClick = { kind = ProviderKind.ANTHROPIC; step = 2 }, label = "Claude")
                            RoundAction(ChatIcon, "ChatGPT by OpenAI", Slate, onClick = { kind = ProviderKind.OPENAI; step = 2 }, label = "ChatGPT")
                        }
                        Text("Claude is recommended. You can change later.", style = MaterialTheme.typography.bodySmall)
                    }
                    2 -> {
                        Text("Get your ${guide.name} key on ${guide.site}", fontWeight = FontWeight.SemiBold)
                        guide.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
                        Button(
                            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(guide.keysPage))) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Open the ${guide.company} page") }
                        Text("Then come back here: the key will be waiting in your clipboard.", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        WhyTitle("Why a key, and is it safe?", Why.AI, MaterialTheme.typography.bodyMedium)
                        CompactField(
                            if (key.isEmpty()) "" else "•".repeat(minOf(key.length, 24)) + key.takeLast(4),
                            {}, "Your ${guide.name} key", Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                                key = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                                ok = false
                                result = if (key.isEmpty()) "The clipboard is empty. Copy the key again on ${guide.site}." else null
                            }) { Text("Paste") }
                            Button(enabled = !testing && key.isNotEmpty(), onClick = {
                                ok = false
                                result = when {
                                    !key.startsWith(guide.prefix) -> "This doesn't look like a ${guide.name} key. It should start with ${guide.prefix}. Copy it again from ${guide.site}."
                                    key.length < 20 -> "The key looks cut off. Copy the whole line again."
                                    else -> null
                                }
                                if (result != null) return@Button
                                testing = true
                                scope.launch {
                                    val problem = vm.testKey(kind, key)
                                    testing = false
                                    ok = problem == null
                                    result = problem ?: "Connected ✓ ${guide.name} answered."
                                }
                            }) { Text("Test") }
                        }
                        if (testing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        result?.let { Text(it, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
                        Text("After saving, the key is never shown again: only its last 4 characters.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                2 -> TextButton(onClick = { step = 3 }) { Text("I copied it") }
                3 -> TextButton(enabled = ok, onClick = { vm.connectAi(kind, key); key = ""; onDismiss() }) { Text("Save") }
                else -> Unit
            }
        },
        dismissButton = {
            if (step == 1) TextButton(onClick = onDismiss) { Text("Cancel") }
            else TextButton(onClick = { step--; result = null; ok = false }) { Text("Back") }
        },
    )
}
