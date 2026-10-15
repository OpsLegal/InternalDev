package com.opslegal.tda.updates

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.opslegal.tda.TdaApp
import com.opslegal.tda.core.model.Incoming
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Payments
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Keeps the notifications of email, document and chat apps (sender, subject, first lines) for the next
 * check. Read on this phone only; nothing is sent anywhere until a check, and then only to the user's AI.
 */
class UpdatesListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = applicationContext as TdaApp
        // A payment (card, wallet, bank app, Interac): kept to sort into expenses, when the user turned it on. Read here, no AI.
        if (app.boards.board.value.checks.payments && (sbn.packageName in Payments.APPS || sbn.packageName in Payments.EMAIL_APPS)) {
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            Payments.read(sbn.packageName, title, body, LocalDateTime.now())?.let { p -> app.appScope.launch { app.boards.update { Payments.add(it, p) } } }
        }
        val source = SOURCES[sbn.packageName] ?: return
        if (!app.boards.board.value.checks.notifications) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val extras = n.extras
        val from = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty().trim()
        if (from.isEmpty() && text.isEmpty()) return
        app.inbox.add(Incoming(BoardOps.newId(), source, from.take(120), text.take(600), LocalDateTime.now().withNano(0).toString()))
    }

    companion object {
        /** Apps whose notifications can matter to the table: email, documents, work chat. */
        val SOURCES = mapOf(
            "com.microsoft.office.outlook" to "outlook",
            "com.google.android.gm" to "gmail",
            "com.samsung.android.email.provider" to "email",
            "com.microsoft.teams" to "teams",
            "com.microsoft.skydrive" to "onedrive",
            "com.google.android.apps.docs" to "drive",
            "com.whatsapp" to "whatsapp",
            "com.whatsapp.w4b" to "whatsapp",
            "com.google.android.apps.messaging" to "sms",
            "com.samsung.android.messaging" to "sms",
        )

        /** True once the user allowed Docket 5 to read notifications (Android's special access page). */
        fun allowed(context: Context): Boolean {
            val me = ComponentName(context, UpdatesListener::class.java).flattenToString()
            return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.split(":")?.contains(me) == true
        }
    }
}
