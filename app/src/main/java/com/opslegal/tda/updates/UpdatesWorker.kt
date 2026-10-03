package com.opslegal.tda.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opslegal.tda.TdaApp
import com.opslegal.tda.core.agent.Threads
import com.opslegal.tda.core.agent.UpdateCheck
import com.opslegal.tda.core.model.ThreadMessage
import com.opslegal.tda.core.model.Incoming
import com.opslegal.tda.core.model.UpdateChecks
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Updates
import com.opslegal.tda.data.BeeperMessages
import com.opslegal.tda.ui.MainActivity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * One check: what arrived since the last one goes to the user's own AI with the table; the proposals
 * wait behind the bell. Urgent ones notify at once; the rest only when "protect my focus" is off.
 */
class UpdatesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as TdaApp
        // A failed check (no connection, no credit) keeps what arrived for the next one.
        runCatching { check(app) }
        scheduleNext(applicationContext, app.boards.board.value.checks)
        return Result.success()
    }

    companion object {
        private const val NOW = "updates-now"
        private const val TIMED = "updates-timed"
        private const val CHANNEL = "updates"
        private const val URGENT = "updates-urgent"

        /** Runs a check now (on open, on leave, or "Check now"). Several requests at once make one check. */
        fun checkNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdatesWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }

        /** The next check at one of the user's times; replaced whenever the times change. */
        fun scheduleNext(context: Context, checks: UpdateChecks) {
            val wm = WorkManager.getInstance(context)
            val now = LocalDateTime.now()
            val next = checks.times.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
                .map { t -> now.toLocalDate().atTime(t).let { if (it.isAfter(now)) it else it.plusDays(1) } }
                .minOrNull()
            if (next == null) return run { wm.cancelUniqueWork(TIMED) }
            val request = OneTimeWorkRequestBuilder<UpdatesWorker>()
                .setInitialDelay(Duration.between(now, next).toMinutes().coerceAtLeast(1), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniqueWork(TIMED, ExistingWorkPolicy.REPLACE, request)
        }

        /** Returns how many new updates the check found. */
        suspend fun check(app: TdaApp): Int {
            val board = app.boards.board.value
            val checks = board.checks
            val provider = app.settings.provider()
            val now = LocalDateTime.now().withNano(0)
            if (provider == null || !app.billing.premium.value) return 0
            val email = emailSince(app, checks)
            // With the Microsoft sign-in, emails come in full from Graph: Outlook's notifications would repeat them.
            val chats = beeperSince(app, checks)
            // With Beeper, chats come in full from it: the WhatsApp or SMS notifications would repeat them.
            val beeperOn = checks.messages && app.settings.settings.value.messagesAccess && BeeperMessages(app).permitted
            val items = app.inbox.drain().filter { (email == null || it.source != "outlook") && !(beeperOn && it.source in BEEPER) } + chats + email.orEmpty()
            val found = try {
                UpdateCheck.run(provider, board, items, LocalDate.now(), now.toString())
            } catch (e: Exception) {
                // No connection or no credit: keep what arrived for the next check.
                app.inbox.restore(items.filter { it.chatId.isBlank() && it.source !in BEEPER && it.id !in emailIds(email) })
                throw e
            }
            // Only once the check worked: the chats read now wait for a new message from the person.
            seenChats(app).edit().apply { items.filter { it.chatId.isNotBlank() }.forEach { putString(it.chatId, it.at) } }.apply()
            app.boards.update { b -> Updates.add(b, found).copy(checks = b.checks.copy(lastCheck = now.toString())) }
            val urgent = found.filter { it.urgent }
            val toNotify = if (checks.focus) urgent else found
            if (toNotify.isNotEmpty()) notify(app, toNotify.size, urgent.isNotEmpty(), toNotify.first().summary)
            return found.size
        }

        private val BEEPER = setOf("whatsapp", "sms", "signal", "telegram", "instagram", "messenger", "beeper")

        private fun emailIds(email: List<Incoming>?) = email.orEmpty().map { it.id }.toSet()

        /** Inbox emails since the last check through Microsoft Graph (read only); null when not connected or unreachable. */
        private suspend fun emailSince(app: TdaApp, checks: UpdateChecks): List<Incoming>? {
            if (!checks.email || !app.microsoft.connected) return null
            val since = checks.lastCheck ?: LocalDateTime.now().minusHours(12).withNano(0).toString()
            return runCatching { app.microsoft.recent(since, 25) }.getOrNull()?.map { m ->
                Incoming(BoardOps.newId(), "outlook", m.from, listOf(m.subject, m.preview).filter { it.isNotBlank() }.joinToString(" — "), m.received, mailId = m.id, cc = m.cc)
            }
        }

        /**
         * Conversations waiting for the user, through Beeper: new messages since the last check, and, with the reply
         * assistant on, one-to-one chats left without an answer for up to [UNANSWERED_DAYS] days (read or not). Each
         * comes with what the person wrote since the user's last reply. A chat goes to the AI again only when the
         * person wrote something new. Chats the user answered themselves are cleared from the Replies pile.
         */
        private suspend fun beeperSince(app: TdaApp, checks: UpdateChecks): List<Incoming> {
            if (!checks.messages || !app.settings.settings.value.messagesAccess) return emptyList()
            val beeper = BeeperMessages(app).takeIf { it.permitted } ?: return emptyList()
            val board = app.boards.board.value
            val since = checks.lastCheck ?: LocalDateTime.now().minusHours(12).toString()
            val oldest = LocalDateTime.now().minusDays(UNANSWERED_DAYS).toString()
            val seen = seenChats(app)
            val chats = runCatching { beeper.recentChats(40, unreadOnly = false) }.getOrDefault(emptyList())
                .filter { c -> c.lastActivity > since || (board.replies.on && c.oneToOne && c.lastActivity > oldest) }
            // Answered elsewhere: the user wrote last in a chat that still has a card.
            val waitingChats = Updates.replies(board).map { it.chatId }.filter { it.isNotBlank() }.toSet()
            val answered = mutableSetOf<String>()
            val items = chats.mapNotNull { chat ->
                val messages = runCatching { beeper.messagesOf(chat.id, 20) }.getOrDefault(emptyList())
                val thread = Threads.sinceMyLastReply(messages)
                if (thread == null) { if (chat.id in waitingChats) answered += chat.id; return@mapNotNull null }
                val last = thread.last()
                if (seen.getString(chat.id, "")!! >= last.time) return@mapNotNull null
                Incoming(
                    BoardOps.newId(), chat.network.lowercase().ifBlank { "beeper" }, chat.title, last.text.take(600), last.time,
                    chatId = chat.id, thread = thread.map { ThreadMessage(it.fromMe, it.sender, it.text.take(600), it.time) },
                )
            }.take(20)
            if (answered.isNotEmpty()) app.boards.update { Updates.answeredElsewhere(it, answered) }
            return items
        }

        private const val UNANSWERED_DAYS = 14L

        /** The last message of each chat already read by a check, so a chat is read again only when the person writes. */
        private fun seenChats(app: TdaApp) = app.getSharedPreferences("beeper-seen", Context.MODE_PRIVATE)

        private fun notify(context: Context, count: Int, urgent: Boolean, first: String) {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
            // Urgent ones pop up on the screen; the others arrive quietly in the shade.
            manager.createNotificationChannel(NotificationChannel(URGENT, "Urgent updates", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_UPDATES, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val title = (if (urgent) "Urgent: " else "") + if (count == 1) "1 update to review" else "$count updates to review"
            val notification = NotificationCompat.Builder(context, if (urgent) URGENT else CHANNEL)
                .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(first)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(2, notification)
        }
    }
}
