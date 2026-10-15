package com.opslegal.tda.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.data.AppSettings
import com.opslegal.tda.data.BeeperMessages
import com.opslegal.tda.updates.UpdatesListener

/** On, off, or on in the app but no longer allowed by the phone (or the account): then it needs a reconnect. */
private enum class Link { ON, OFF, BROKEN }

private class Connection(val icon: String, val name: String, val state: Link, val what: String, val turnOff: () -> Unit, val connect: () -> Unit, val ask: String? = null)

/**
 * Everything the app is connected to, in one card: its state at a glance and one tap to turn it off or reconnect,
 * without redoing the setup.
 */
@Composable
internal fun ConnectionsCard(
    vm: MainViewModel, settings: AppSettings, board: Board, msAccount: String?, msError: String?,
    onConnectAi: () -> Unit, onCalendar: () -> Unit, onBeeper: () -> Unit,
) {
    val context = LocalContext.current
    // Android's own pages (notification access, permissions) change things behind our back: re-read on return.
    var resumed by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val links = remember(settings, board.checks, msAccount, msError, resumed) { connections(context, vm, settings, board, msAccount, msError, onConnectAi, onCalendar, onBeeper) }
    var asking by remember { mutableStateOf<Connection?>(null) }
    asking?.let { c ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { asking = null },
            title = { Text("Turn off ${c.name}?") }, text = { Text(c.ask ?: "") },
            confirmButton = { TextButton(onClick = { c.turnOff(); asking = null }) { Text("Turn off", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Keep it") } })
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Connections · ${links.count { it.state == Link.ON }} of ${links.size} on", fontWeight = FontWeight.SemiBold)
            links.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(when (c.state) {
                        Link.ON -> Color(0xFF2E9E5B); Link.BROKEN -> Color(0xFFE0A100); Link.OFF -> MaterialTheme.colorScheme.outlineVariant
                    }))
                    Column(Modifier.weight(1f)) {
                        Text("${c.icon} ${c.name}")
                        Text(when (c.state) { Link.ON -> c.what; Link.BROKEN -> "Stopped: reconnect to use it again"; Link.OFF -> "Off" },
                            style = MaterialTheme.typography.bodySmall, color = if (c.state == Link.BROKEN) Color(0xFFB07D00) else Color.Unspecified)
                    }
                    when (c.state) {
                        Link.ON -> TextButton(onClick = { if (c.ask != null) asking = c else c.turnOff() }) { Text("Turn off", color = MaterialTheme.colorScheme.error) }
                        Link.BROKEN -> TextButton(onClick = c.connect) { Text("Reconnect") }
                        Link.OFF -> TextButton(onClick = c.connect) { Text("Connect") }
                    }
                }
            }
        }
    }
}

private fun connections(
    context: Context, vm: MainViewModel, s: AppSettings, board: Board, msAccount: String?, msError: String?,
    onConnectAi: () -> Unit, onCalendar: () -> Unit, onBeeper: () -> Unit,
): List<Connection> {
    val listener = UpdatesListener.allowed(context)
    val openListener = { runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }; Unit }
    val beeper = BeeperMessages(context)
    val calendarOk = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun state(on: Boolean, ok: Boolean) = if (!on) Link.OFF else if (ok) Link.ON else Link.BROKEN
    return listOf(
        Connection("🔑", "My AI", if (s.hasApiKey) Link.ON else Link.OFF, s.provider.label + if (s.keyTail.isNotEmpty()) " · key …${s.keyTail}" else "",
            { vm.setApiKey(null) }, onConnectAi, ask = "Your key is erased from this phone. The assistant stops until you connect again (about 5 minutes)."),
        Connection("✉️", "Microsoft email", if (msAccount != null) (if (msError == null) Link.ON else Link.BROKEN) else Link.OFF, msAccount ?: "",
            { vm.disconnectMicrosoft() }, { vm.signInMicrosoft() }, ask = "The assistant stops reading this mailbox. Sign in again anytime from here."),
        Connection("🔔", "Phone notifications", state(board.checks.notifications, listener), "Gmail, Outlook, Teams, WhatsApp… as they arrive",
            { vm.editChecks { it.copy(notifications = false) } }, { vm.editChecks { it.copy(notifications = true) }; if (!listener) openListener() }),
        Connection("💬", "Beeper messages", state(s.messagesAccess, beeper.installed && beeper.permitted), "WhatsApp, Signal, SMS… read only",
            { vm.updateSettings { it.copy(messagesAccess = false) } },
            {
                if (!beeper.installed) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.beeper.android"))) }
                else if (beeper.permitted) vm.updateSettings { it.copy(messagesAccess = true) } else onBeeper()
            }),
        Connection("📅", "Calendar", state(s.calendarAccess, calendarOk), "Meetings shown under each day",
            { vm.updateSettings { it.copy(calendarAccess = false) } }, { if (calendarOk) vm.updateSettings { it.copy(calendarAccess = true) } else onCalendar() }),
        Connection("💳", "Card and bank payments", state(board.checks.payments, listener), "Read on the phone, sorted into expenses",
            { vm.setPayments(false) }, { vm.setPayments(true); if (!listener) openListener() }),
    )
}
