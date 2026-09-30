package com.opslegal.tda.ui

import android.Manifest
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.opslegal.tda.updates.UpdatesWorker
import java.time.LocalDateTime
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.opslegal.tda.core.agent.AssistantPage

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receiveShare(intent)
        if (savedInstanceState == null) vm.finishMicrosoft(intent?.data)
        if (intent?.getBooleanExtra(EXTRA_UPDATES, false) == true) vm.updatesOpen.value = true
        setContent {
            val appSettings by vm.settings.collectAsState()
            TdaTheme(appSettings.theme) {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val shared by vm.sharedText.collectAsState()
                // Something was shared from another app: go to the assistant with it.
                LaunchedEffect(shared) { if (shared != null) tab = Tab.ASSISTANT.ordinal }
                // A screen asked the assistant something (a cell's Talk, "Ask the assistant", a page's mic).
                val asked by vm.assistantRequests.collectAsState()
                LaunchedEffect(asked) { if (asked > 0) tab = Tab.ASSISTANT.ordinal }
                // A tap on an updates notification: the table, with the updates open.
                LaunchedEffect(openTable) { if (openTable) { tab = Tab.TABLE.ordinal; openTable = false } }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEachIndexed { index, t ->
                                NavigationBarItem(
                                    selected = tab == index,
                                    onClick = { if (t == Tab.ASSISTANT) vm.openAssistantFor(null); tab = index },
                                    icon = { Icon(t.icon, contentDescription = null) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val modifier = Modifier.padding(padding)
                    when (Tab.entries[tab]) {
                        Tab.TABLE -> TableScreen(vm, modifier, header = { UpdatesBell(vm) })
                        Tab.PROGRESS -> ProgressScreen(vm, modifier)
                        Tab.ASSISTANT -> AssistantScreen(vm, modifier, onOpenSettings = { tab = Tab.SETTINGS.ordinal }, onBack = { page ->
                            tab = when (page) {
                                AssistantPage.PROGRESS -> Tab.PROGRESS
                                AssistantPage.PLAYBOOK -> Tab.PLAYBOOK
                                AssistantPage.SETTINGS -> Tab.SETTINGS
                            }.ordinal
                            vm.openAssistantFor(null)
                        })
                        Tab.PLAYBOOK -> RulesScreen(vm, modifier)
                        Tab.SETTINGS -> SettingsScreen(vm, modifier, onEnableDailyReview = ::askNotificationPermission)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShare(intent)
        vm.finishMicrosoft(intent.data)
        if (intent.getBooleanExtra(EXTRA_UPDATES, false)) {
            vm.updatesOpen.value = true
            openTable = true
        }
    }

    private var openTable by mutableStateOf(false)

    override fun onStart() {
        super.onStart()
        // Check on open, at most every 10 minutes.
        val checks = vm.board.value.checks
        val last = checks.lastCheck?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        if (checks.onOpen && (last == null || last.isBefore(LocalDateTime.now().minusMinutes(10)))) UpdatesWorker.checkNow(this)
    }

    override fun onStop() {
        super.onStop()
        // Check on leave: runs in the background, the result waits behind the bell.
        if (!isChangingConfigurations && vm.board.value.checks.onLeave) UpdatesWorker.checkNow(this)
    }

    /** Text shared with "Share → Docket 5" from Outlook, Gmail, WhatsApp, Teams, notes... */
    private fun receiveShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        vm.receiveShared(listOf(subject, text).filter { it.isNotBlank() }.joinToString("\n"))
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_UPDATES = "com.opslegal.tda.UPDATES"
    }

    private enum class Tab(val label: String, val icon: ImageVector) {
        TABLE("Table", Icons.Filled.DateRange),
        PROGRESS("Progress", ProgressIcon),
        ASSISTANT("Assistant", Icons.Filled.Face),
        PLAYBOOK("Playbook", CompassIcon),
        SETTINGS("Settings", Icons.Filled.Settings),
    }
}
