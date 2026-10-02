package com.opslegal.tda.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/**
 * Why the app works the way it does, behind the "?" of sensitive settings: it reminds the user of the
 * choices made for their security and for ease.
 */
internal object Why {
    const val AI = "Why your own AI account? Your client information never passes through OPS LEGAL TECH: this phone talks " +
        "directly to Anthropic or OpenAI, under your own account and their data terms (they don't train on it by default). " +
        "The key is stored encrypted on this phone. Easy: set it once, change or disconnect anytime."
    const val UPDATES = "Why this way? Everything is read on this phone, with no server of ours. The assistant only proposes: " +
        "nothing changes until you tap Apply. Only the few lines it needs go to your own AI. Easy: it works quietly in the " +
        "background and only interrupts you for what you call urgent."
    const val SOURCES = "Messages come through Beeper. The assistant only reads them; a reply leaves only when you tap Send after reading it. Email and document alerts are the " +
        "notifications your Outlook or Gmail app already shows on this phone (sender, subject, first lines), so there is no " +
        "extra password to give. For the full text of work emails, the optional Microsoft sign-in is read-only and revocable " +
        "anytime from your Microsoft account."
    const val FOCUS = "Why? Staying on one thing is hard when alerts keep coming. Updates wait quietly for the next check, " +
        "and only what you call urgent reaches you right away."
    const val CONFIRM = "Why? The assistant repeats what it understood and waits for your yes, so nothing in your plan changes " +
        "by mistake. You can turn it down to “important changes only” once you trust it."
    const val MEETINGS = "Why? Offered times respect your 5 cells, your deadlines and your hours, not just your calendar. " +
        "The app writes the message but never sends it: you stay in control of what goes out."
}

/** A section title with a light "?" that shows why the app works this way. */
@Composable
internal fun WhyTitle(title: String, why: String, style: TextStyle = MaterialTheme.typography.titleLarge) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = style, modifier = Modifier.weight(1f))
            HelpButton(open, { open = !open })
        }
        if (open) HelpText(why)
    }
}
