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
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receiveShare(intent)
        setContent {
            TdaTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val shared by vm.sharedText.collectAsState()
                // Something was shared from another app: go to the assistant with it.
                LaunchedEffect(shared) { if (shared != null) tab = Tab.ASSISTANT.ordinal }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEachIndexed { index, t ->
                                NavigationBarItem(
                                    selected = tab == index,
                                    onClick = { tab = index },
                                    icon = { Icon(t.icon, contentDescription = null) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val modifier = Modifier.padding(padding)
                    when (Tab.entries[tab]) {
                        Tab.TABLE -> TableScreen(vm, modifier)
                        Tab.ASSISTANT -> AssistantScreen(vm, modifier, onOpenSettings = { tab = Tab.SETTINGS.ordinal })
                        Tab.RULES -> RulesScreen(vm, modifier)
                        Tab.SETTINGS -> SettingsScreen(vm, modifier, onEnableDailyReview = ::askNotificationPermission)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShare(intent)
    }

    /** Text shared with "Share → TDA 5" from Outlook, Gmail, WhatsApp, Teams, notes... */
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

    private enum class Tab(val label: String, val icon: ImageVector) {
        TABLE("Table", Icons.Filled.DateRange),
        ASSISTANT("Assistant", Icons.Filled.Face),
        RULES("Rules", Icons.AutoMirrored.Filled.List),
        SETTINGS("Settings", Icons.Filled.Settings),
    }
}
