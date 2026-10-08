package com.opslegal.tda.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.ui.text.font.FontWeight
import com.opslegal.tda.core.agent.AssistantPage
import com.opslegal.tda.core.model.UpdateChecks
import com.opslegal.tda.updates.UpdatesListener
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opslegal.tda.core.agent.AnthropicProvider
import com.opslegal.tda.core.model.ConfirmationPolicy
import com.opslegal.tda.core.model.ConversationSettings
import com.opslegal.tda.core.model.MeetingSettings
import com.opslegal.tda.core.voice.LanguageGuess
import com.opslegal.tda.data.BeeperMessages
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
    val context = LocalContext.current
    val activity = context as? Activity
    // Read to plan around meetings; write only to add meetings booked through the assistant.
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        vm.updateSettings { it.copy(calendarAccess = granted[Manifest.permission.READ_CALENDAR] == true) }
    }
    val beeperPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.updateSettings { it.copy(messagesAccess = granted) }
    }
    var key by remember { mutableStateOf("") }
    var wizard by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    androidx.activity.compose.BackHandler(section != null) { section = null }
    val msAccountTop by vm.microsoftAccount.collectAsStateWithLifecycle()

    Box(modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (section == null) {
            Cta("Get started", "${setupCount(board, settings, msAccountTop)} of 8 set") { vm.getStarted.value = firstUnset(board, settings, msAccountTop) }
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                placeholder = { Text("Find a setting: email, dark, km…") }, leadingIcon = { Text("🔍") },
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("✕") } },
            )
            if (query.isBlank()) Tiles(SETTING_TILES) { section = it }
            else {
                val found = findFeatures(query)
                if (found.isEmpty()) Text("Nothing found. Ask the assistant with the 🎤.", style = MaterialTheme.typography.bodySmall)
                found.forEach { f -> FeatureCard(f) { section = f.section } }
            }
        } else BackToTiles("Settings") { section = null }
        if (section == "Your AI") {
            // Who the assistant is: its name and face, on notifications, the bell and replies.
            var persona by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(board.persona, 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text(com.opslegal.tda.core.agent.Me.name(board), fontWeight = FontWeight.SemiBold)
                        Text("Your assistant's name and face, on notifications and replies.", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { persona = true }) { Text("Change") }
                }
            }
            if (persona) PersonaDialog(vm, board.persona) { persona = false }
        }
        if (section == "Your AI") WhyTitle("Your AI", Why.AI)
        if (section == "Your AI") Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settings.hasApiKey) {
                    val company = when (settings.provider) { ProviderKind.ANTHROPIC -> "Anthropic"; ProviderKind.OPENAI -> "OpenAI"; else -> "your provider" }
                    Text("Connected ✓ ${settings.provider.label}", fontWeight = FontWeight.SemiBold)
                    Text(
                        (if (settings.keyTail.isNotEmpty()) "Key ending …${settings.keyTail} · " else "") + "stored encrypted on this phone · sent only to $company",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { wizard = true }) { Text("Change") }
                        TextButton(onClick = { vm.setApiKey(null) }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
                    }
                } else {
                    Text("Connect your AI", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Your assistant uses your own Claude or ChatGPT account. Your data goes straight from this phone to them, never through us. About 5 minutes, once.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { wizard = true }) { Text("Connect") }
                }
            }
        }
        if (section == "Your AI") TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced: model, other providers") }
        if (section == "Your AI" && advanced) {
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
                        TagChip(selected = settings.model == m, onClick = { vm.updateSettings { it.copy(model = m) } }, label = { Text(m.removePrefix("claude-")) })
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
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { vm.setApiKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Save key") }
            }
        }

        if (section == "Planner & layout") {
        Text("Planner & layout", style = MaterialTheme.typography.titleLarge)
        WeekGrid(vm, board, settings.dayLanguage == "fr")
        Text("Day letters")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagChip(settings.dayLanguage == "en", { vm.updateSettings { it.copy(dayLanguage = "en") } }, label = { Text("M Tu W Th F") })
            TagChip(settings.dayLanguage == "fr", { vm.updateSettings { it.copy(dayLanguage = "fr") } }, label = { Text("L Ma Me J V") })
        }
        Text("Join errands and visits when they are")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(5, 10).forEach { k ->
                TagChip(board.settings.maxDetourKm == k, { vm.edit { b -> b.copy(settings = b.settings.copy(maxDetourKm = k)) } }, label = { Text("$k km") })
            }
            // Your own distance: any number of km, kept when you tap Apply.
            var km by remember(board.settings.maxDetourKm) { mutableStateOf(board.settings.maxDetourKm.takeIf { it != 5 && it != 10 }?.toString().orEmpty()) }
            val n = km.toIntOrNull()?.takeIf { it > 0 }
            val custom = board.settings.maxDetourKm != 5 && board.settings.maxDetourKm != 10
            OutlinedTextField(
                km, { v -> km = v.filter { it.isDigit() }.take(3) },
                Modifier.width(110.dp), singleLine = true, placeholder = { Text("Other") }, suffix = { Text("km") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            )
            if (n != null && n != board.settings.maxDetourKm) Button(onClick = { vm.edit { b -> b.copy(settings = b.settings.copy(maxDetourKm = n)) } }) { Text("Apply") }
            else if (custom) Text("✓", color = projectBarColor(), fontWeight = FontWeight.Bold)
        }
        Text("Now: up to ${board.settings.maxDetourKm} km apart.", style = MaterialTheme.typography.bodySmall)
        Text("Farther than that, each gets its own trip.", style = MaterialTheme.typography.bodySmall)
        SwitchRow(
            "Ground · Build · Nourish (trial)",
            "Every task shows what it serves (Home, Admin · Career, Money, Invest · Relations, Health, Joy) and its value for you, " +
                "in the cell menu and the task and project forms. The assistant proposes; you correct, and it learns. Off brings back your previous values.",
            checked = board.gbn,
            onChange = { on -> vm.setGbn(on) },
        )
        Text("Public holidays")
        var daysOff by remember { mutableStateOf(false) }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            com.opslegal.tda.core.plan.Holidays.regions.forEach { (k, l) ->
                TagChip(board.settings.holidays == k, { vm.editAndPlan { b -> b.copy(settings = b.settings.copy(holidays = k)) } }, label = { Text(l) })
            }
            // Your own days off (planned vacations), on a 12-month calendar.
            val mine = board.settings.daysOff.count { it >= java.time.LocalDate.now().toString() }
            TagChip(mine > 0, { daysOff = true }, label = { Text("📅 My days off" + if (mine > 0) " ($mine)" else "…") })
        }
        if (daysOff) DaysOffDialog(board.settings.daysOff, board.settings.holidays,
            onSave = { list -> vm.editAndPlan { b -> b.copy(settings = b.settings.copy(daysOff = list)) }; daysOff = false },
            onDismiss = { daysOff = false })
        com.opslegal.tda.core.plan.Holidays.ranges(board.settings.daysOff).filter { !it.second.isBefore(java.time.LocalDate.now()) }.takeIf { it.isNotEmpty() }?.let { r ->
            Text("Days off: " + r.take(6).joinToString(" · ") { (a, z) -> if (a == z) vm.dayName(a) else "${vm.dayName(a)} – ${vm.dayName(z)}" }, style = MaterialTheme.typography.bodySmall)
        }
        Text("No 💼 work is planned on your region's holidays: ${com.opslegal.tda.core.plan.Holidays.describe(board.settings.holidays)} 🏠 Personal areas still may be.",
            style = MaterialTheme.typography.bodySmall)
        val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
        SwitchRow(
            "Dark mode",
            "Dark background, easier on the eyes at night.",
            checked = when (settings.theme) { "dark" -> true; "light" -> false; else -> systemDark },
            onChange = { on -> vm.updateSettings { it.copy(theme = if (on) "dark" else "light") } },
        )
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

        }
        if (section == "What the assistant sees") {
        WhyTitle("What the assistant can see", Why.SOURCES)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("My calendar")
                Text(
                    "Your events (Outlook, Google, Samsung...) show under each day as 📅, so nothing is planned over them, and the " +
                        "assistant adds only the meetings you book through it. For Outlook, turn on Sync calendars in the Outlook app.",
                    style = MaterialTheme.typography.bodySmall,
                )
                // One switch for the calendar: if adding booked meetings isn't allowed yet, finish it here.
                if (settings.calendarAccess &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED
                ) {
                    TextButton(onClick = { calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }) {
                        Text("Finish: allow adding booked meetings")
                    }
                }
            }
            Switch(
                checked = settings.calendarAccess,
                onCheckedChange = { on ->
                    if (!on) {
                        vm.updateSettings { it.copy(calendarAccess = false) }
                    } else if (
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
                    ) {
                        vm.updateSettings { it.copy(calendarAccess = true) }
                    } else {
                        calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                    }
                },
            )
        }
        val beeper = remember { BeeperMessages(context) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("My messages (Beeper)")
                Text(
                    if (beeper.installed) "Read only. WhatsApp, SMS, Messenger, Instagram, Signal... through Beeper. The assistant reads only what a request needs and can never send: only a reply you confirm with Send leaves."
                    else "Install and sign in to Beeper to let the assistant read your WhatsApp, SMS, Instagram... messages (read only).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = settings.messagesAccess,
                enabled = beeper.installed,
                onCheckedChange = { on ->
                    if (!on) {
                        vm.updateSettings { it.copy(messagesAccess = false) }
                    } else if (beeper.permitted) {
                        vm.updateSettings { it.copy(messagesAccess = true) }
                    } else {
                        beeperPermission.launch(BeeperMessages.READ_PERMISSION)
                    }
                },
            )
        }
        val msAccount by vm.microsoftAccount.collectAsStateWithLifecycle()
        val msError by vm.microsoftError.collectAsStateWithLifecycle()
        val msBusy by vm.microsoftBusy.collectAsStateWithLifecycle()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("My work email (Microsoft 365, Outlook.com)")
            Text(
                if (msAccount != null) "Connected: $msAccount. Read only: the assistant can search and read your email, never send, move or delete it."
                else "Read only. Sign in once with Microsoft on this phone: the assistant can then read your email when a request needs it, and Updates check new emails. Forward other addresses (Gmail...) to this mailbox.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (msBusy) {
                Text("Finishing the Microsoft sign-in…", style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (msAccount != null) {
                TextButton(onClick = vm::disconnectMicrosoft) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            } else {
                OutlinedButton(onClick = { vm.signInMicrosoft() }) { Text("Sign in with Microsoft") }
            }
            msError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val replies = board.replies
            Text("My assistant for replies")
            Text(
                if (replies.on) listOfNotNull("emails".takeIf { replies.email }, "messages".takeIf { replies.messages })
                    .joinToString(", ", prefix = "On for ", postfix = ". It prepares; nothing leaves without your tap, and it never accepts or declines for you.")
                else "It prepares answers to direct questions and requests in your emails and messages; you review them. Nothing leaves without your tap.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                if (replies.on) {
                    TextButton(onClick = { vm.repliesWizard.value = 2 }) { Text("Change") }
                    TextButton(onClick = { vm.editReplies { it.copy(on = false) } }) { Text("Turn off", color = MaterialTheme.colorScheme.error) }
                } else {
                    OutlinedButton(onClick = { vm.repliesWizard.value = 1 }) { Text("Set it up") }
                }
            }
        }
        Text(
            "Anything else: in Outlook, Gmail, WhatsApp or Teams, tap Share and choose Docket 5. The assistant reads what you share, nothing else.",
            style = MaterialTheme.typography.bodySmall,
        )

        }
        if (section == "Updates") {
        UpdatesSettings(board.checks, vm::editChecks, notificationsAllowed = UpdatesListener.allowed(context), onAllowNotifications = {
            runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        if (board.replyStyle.isNotEmpty()) {
            Text("How you write", style = MaterialTheme.typography.labelLarge)
            Text(
                "Your last ${board.replyStyle.size} replies, as you sent them, show the assistant your tone and length. They never decide what to answer: the rules do.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = vm::forgetReplyStyle) { Text("Forget them") }
        }
        AlertsSettings()
        }
        if (section == "Appointments") MeetingSettingsSection(board.meetings) { change -> vm.edit { it.copy(meetings = change(it.meetings)) } }
        if (section == "Talking with the assistant") VoiceSettings(board.conversation, vm::editConversation)
        if (section == "Premium") {
        Text("Docket 5 Premium", style = MaterialTheme.typography.titleLarge)
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
        // Room to scroll the last setting above the assistant button.
        Spacer(Modifier.height(72.dp))
    }
    PageAssistantButton(vm, AssistantPage.SETTINGS)
    }
    if (wizard) AiWizard(vm, onDismiss = { wizard = false })
}


/**
 * What lets the checks reach the user outside the app: Android's permission to show alerts, and (on phones that
 * put apps to sleep) leaving Docket 5 free to run its checks on time.
 */
@Composable
private fun AlertsSettings() {
    val context = LocalContext.current
    fun alertsOn() = android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun awake() = (context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    var alerts by remember { mutableStateOf(alertsOn()) }
    var free by remember { mutableStateOf(awake()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        alerts = granted
        // Refused twice, Android won't ask again: open the app's notification settings instead.
        if (!granted) runCatching {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }
    // Back from the phone's settings: show the new state.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { alerts = alertsOn(); free = awake() } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Text("Alerts outside the app", style = MaterialTheme.typography.labelLarge)
    if (alerts) {
        Text("On: what you call urgent (or everything, if \"Protect my focus\" is off) shows as a phone notification, even when Docket 5 is closed.", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Off: checks still run, but their results only wait behind the bell. Allow alerts to be told when something is urgent.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow alerts") }
    }
    if (!free) {
        Text("This phone may put Docket 5 to sleep and delay the checks. Choose \"Unrestricted\" (or \"Don't optimise\") for Docket 5 so they run on time.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + context.packageName)))
            }
        }) { Text("Open battery settings for Docket 5") }
    }
}

/** When the assistant checks the user's channels, what it reads, and what may interrupt. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdatesSettings(
    c: UpdateChecks,
    edit: ((UpdateChecks) -> UpdateChecks) -> Unit,
    notificationsAllowed: Boolean,
    onAllowNotifications: () -> Unit,
) {
    var adding by remember { mutableStateOf("") }
    WhyTitle("Updates", Why.UPDATES)
    Text("When the assistant checks your channels. It only proposes; nothing changes without your tap.", style = MaterialTheme.typography.bodySmall)
    Text("Check", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TagChip(c.onOpen, { edit { it.copy(onOpen = !it.onOpen) } }, label = { Text("When I open the app") })
        TagChip(c.onLeave, { edit { it.copy(onLeave = !it.onLeave) } }, label = { Text("When I leave it") })
    }
    Text("And at", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        c.times.forEach { t -> TagChip(true, { edit { it.copy(times = it.times - t) } }, label = { Text("$t  ×") }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompactField(adding, { adding = it }, "Add a time (e.g. 18:00)", Modifier.weight(1f))
        TextButton(onClick = {
            val t = runCatching { java.time.LocalTime.parse(adding.trim().padStart(5, '0')) }.getOrNull()
            if (t != null) {
                val text = "%02d:%02d".format(t.hour, t.minute)
                edit { it.copy(times = (it.times + text).distinct().sorted()) }
                adding = ""
            }
        }) { Text("Add") }
    }
    WhyTitle("Read", Why.SOURCES, MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TagChip(c.messages, { edit { it.copy(messages = !it.messages) } }, label = { Text("Messages (Beeper)") })
        TagChip(c.email, { edit { it.copy(email = !it.email) } }, label = { Text("Work email (Microsoft)") })
        TagChip(c.notifications, { edit { it.copy(notifications = !it.notifications) } }, label = { Text("Email & document notifications") })
    }
    if (c.notifications && !notificationsAllowed) {
        OutlinedButton(onClick = onAllowNotifications) { Text("Allow reading notifications") }
        Text(
            "Android asks once: find Docket 5 in the list and turn it on. Only email, document and chat apps are read.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else if (c.notifications) {
        Text("Email notifications include every account in your Outlook or Gmail app. Only sender, subject and first lines are read.", style = MaterialTheme.typography.bodySmall)
    }
    Text("Urgent means (these interrupt you right away)", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TagChip(c.urgentToday, { edit { it.copy(urgentToday = !it.urgentToday) } }, label = { Text("Due today or tomorrow") })
        TagChip(c.urgentBlocks, { edit { it.copy(urgentBlocks = !it.urgentBlocks) } }, label = { Text("Blocks a project") })
        TagChip(c.urgentKey, { edit { it.copy(urgentKey = !it.urgentKey) } }, label = { Text("From key contacts") })
    }
    if (c.urgentKey) ListField("Key contacts (names, comma-separated)", c.keyContacts) { list -> edit { it.copy(keyContacts = list) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            WhyTitle("Protect my focus", Why.FOCUS, MaterialTheme.typography.bodyLarge)
            Text("Everything else waits for the next check.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = c.focus, onCheckedChange = { on -> edit { it.copy(focus = on) } })
    }
}

/** When people can book a meeting with you. Set once; the assistant only offers times inside it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeetingSettingsSection(m: MeetingSettings, edit: ((MeetingSettings) -> MeetingSettings) -> Unit) {
    WhyTitle("Appointments", Why.MEETINGS)
    Text(
        "Ask the assistant \"find a slot for Jean next week\": it offers times that fit your day and writes the message for you to send.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Days", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { i, name ->
            val day = i + 1
            TagChip(day in m.days, { edit { it.copy(days = if (day in it.days) it.days - day else (it.days + day).sorted()) } }, label = { Text(name) })
        }
    }
    ListField("Hours (e.g. 09:00-12:00, 14:00-17:00)", m.windows) { list ->
        val valid = list.filter { com.opslegal.tda.core.plan.Slots.parseWindow(it) != null }
        if (valid.isNotEmpty()) edit { it.copy(windows = valid) }
    }
    Text("Usual length", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(30, 45, 60, 90).forEach { d -> TagChip(m.durationMinutes == d, { edit { it.copy(durationMinutes = d) } }, label = { Text("$d min") }) }
    }
    Text("Most meetings a day", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..4).forEach { n -> TagChip(m.maxPerDay == n, { edit { it.copy(maxPerDay = n) } }, label = { Text("$n") }) }
    }
    Text("Break around each meeting", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(0, 15, 30).forEach { b -> TagChip(m.bufferMinutes == b, { edit { it.copy(bufferMinutes = b) } }, label = { Text("$b min") }) }
    }
}

/** How the assistant listens, talks and makes sure it understood. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceSettings(talk: ConversationSettings, edit: (((ConversationSettings) -> ConversationSettings)) -> Unit) {
    WhyTitle("Talking with the assistant", Why.CONFIRM)

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
    Text("Languages I speak", style = MaterialTheme.typography.titleSmall)
    Text(
        "Start any conversation in any of them: the phone detects which one (Android 14+), and the assistant answers in the same language. " +
            "The main language is used when it can't tell.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("Main language")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LanguageGuess.offered.forEach { tag ->
            TagChip(
                talk.voiceLanguage == tag,
                { edit { it.copy(voiceLanguage = tag, otherLanguages = it.otherLanguages - tag) } },
                label = { Text(LanguageGuess.displayName(tag)) },
            )
        }
    }
    Text("I also speak")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LanguageGuess.offered.filter { it != talk.voiceLanguage }.forEach { tag ->
            val on = tag in talk.otherLanguages
            TagChip(
                on,
                { edit { it.copy(otherLanguages = if (on) it.otherLanguages - tag else it.otherLanguages + tag) } },
                label = { Text(LanguageGuess.displayName(tag)) },
            )
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
    ListField("Words that mean “to buy” (they go on the 🛒 list, not the table)", talk.buyWords) { list ->
        if (list.isNotEmpty()) edit { it.copy(buyWords = list) }
    }
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
    var focused by remember { mutableStateOf(false) }
    // Follow outside changes (e.g. "Reset to defaults"), but never while the user is typing.
    LaunchedEffect(values, focused) { if (!focused && parse(text) != values) text = values.joinToString(", ") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(parse(it))
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        minLines = 2,
    )
}

private val SETTING_TILES = listOf("🔑" to "Your AI", "🗓️" to "Planner & layout", "👁" to "What the assistant sees", "🔔" to "Updates",
    "🤝" to "Appointments", "🎤" to "Talking with the assistant", "⭐" to "Premium")

/** One feature of Settings, for the live search: where it lives, what it does, and the words people may type. */
private data class Feature(val section: String, val title: String, val info: String, val help: String, val words: String)

private val FEATURES = listOf(
    Feature("Your AI", "Your AI", "Your own Claude or ChatGPT account. About 5 minutes, once.", Why.AI, "ai key claude chatgpt openai anthropic model api"),
    Feature("Your AI", "Your assistant's name and face", "What you call your assistant, and its face on notifications.", "People can write to it: a message with its name comes straight to the bell.", "name face photo jimmy assistant avatar persona"),
    Feature("Planner & layout", "My week", "Each area of life on its own days: Work, Personal, or one you add (Company B, Buildings…).", "Tap a day to add or remove it. Fri & Sat sets a Sun–Thu work week.", "days weekend work saturday sunday friday area company freelance week"),
    Feature("Planner & layout", "Public holidays", "No work on your region's holidays: Québec, Ontario, Canada, United States, France.", "Choose your region; personal tasks may still go on a holiday.", "holiday christmas vacation férié noël region"),
    Feature("Planner & layout", "Day letters", "M Tu W Th F or L Ma Me J V.", "The letters shown on the table and the routines.", "letters language french day"),
    Feature("Planner & layout", "Join errands and visits", "5 km, 10 km, or your own distance.", "Farther than that, each gets its own trip.", "km distance errands trip ride detour"),
    Feature("Planner & layout", "Ground · Build · Nourish", "What each task serves, and the week's balance.", "The values bar: tags and weights are in Playbook.", "values balance ground build nourish attributes bar"),
    Feature("Planner & layout", "Dark mode", "Dark background, easier on the eyes at night.", "", "dark night theme black"),
    Feature("Planner & layout", "Morning AI review", "Every morning the assistant checks the next days and flags risks.", "", "morning review premium"),
    Feature("What the assistant sees", "My calendar", "Your events show under each day, so nothing is planned over them.", Why.SOURCES, "calendar events outlook google samsung agenda"),
    Feature("What the assistant sees", "My messages (Beeper)", "WhatsApp, SMS, Signal… read only.", "Only a reply you confirm with Send leaves.", "beeper whatsapp sms signal messenger instagram messages"),
    Feature("What the assistant sees", "My work email", "Microsoft 365 or Outlook.com, read only.", "Forward other addresses (Gmail…) to this mailbox.", "email mail outlook microsoft gmail inbox"),
    Feature("What the assistant sees", "My assistant for replies", "Prepares answers for you to review and send.", "It never sends, accepts or declines anything for you.", "reply replies drafts answer respond"),
    Feature("Updates", "When the assistant checks", "On opening, on leaving, and at the times you choose.", "It only proposes; nothing changes without your tap.", "updates check times bell notification urgent focus"),
    Feature("Updates", "Alerts", "Lets the checks reach you outside the app.", "", "alerts notifications battery permission"),
    Feature("Appointments", "Appointments", "Days, hours, length and breaks for meetings booked through the assistant.", "Ask “find a slot for Jean next week”.", "meeting appointment slot rdv booking hours"),
    Feature("Talking with the assistant", "Voice and confirmation", "Languages, pauses, reading answers aloud, confirming before changes.", "", "voice language mic speak confirm buy words french"),
    Feature("Premium", "Docket 5 Premium", "The assistant and the morning review.", "", "premium subscription price pay plan"),
)

private fun findFeatures(q: String): List<Feature> {
    val w = q.trim().lowercase()
    return FEATURES.filter { f -> listOf(f.title, f.info, f.words, f.section).any { it.lowercase().contains(w) } || f.words.split(' ').any { it.startsWith(w) || w.startsWith(it) && it.length >= 3 } }
}

@Composable
private fun FeatureCard(f: Feature, onOpen: () -> Unit) {
    var help by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.section.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(f.title, fontWeight = FontWeight.SemiBold)
                }
                if (f.help.isNotBlank()) HelpButton(help, { help = !help })
            }
            Text(f.info, style = MaterialTheme.typography.bodySmall)
            if (help) HelpText(f.help)
            TextButton(onClick = onOpen) { Text("Open ›") }
        }
    }
}
