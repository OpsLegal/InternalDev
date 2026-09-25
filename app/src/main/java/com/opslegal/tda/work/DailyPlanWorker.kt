package com.opslegal.tda.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opslegal.tda.TdaApp
import com.opslegal.tda.core.agent.ChatItem
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * The daily "cron": early every morning, unfinished cells roll over and new steps are
 * placed. If the user enabled it, the assistant also reviews the next days and
 * leaves a short note in the chat (and a notification).
 */
class DailyPlanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as TdaApp
        app.refreshToday()

        val settings = app.settings.settings.value
        if (settings.dailyAiReview && app.billing.premium.value) {
            val agent = app.agent() ?: return Result.success()
            val reply = runCatching {
                agent.send(app.chat.items.value, REVIEW_PROMPT, onItem = { app.chat.append(it) })
            }.getOrNull()
            val text = (reply?.lastOrNull() as? ChatItem.Assistant)?.text
            if (!text.isNullOrBlank()) notify(text)
        }
        return Result.success()
    }

    private fun notify(text: String) {
        val context = applicationContext
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Daily plan", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Today's 5")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(1, notification)
    }

    companion object {
        private const val CHANNEL = "daily"
        private const val WORK_NAME = "daily-plan"

        const val REVIEW_PROMPT =
            "Daily review (automatic). Look at the table for today and the next 3 working days. " +
                "Point out anything at risk (deadlines, rows that are overloaded or rolling over, blockers) and suggest at most 3 concrete changes. " +
                "Do not change the table yourself in this review; wait for my answer. Answer in 5 lines or less."

        fun schedule(context: Context) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(LocalTime.of(4, 30))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<DailyPlanWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
