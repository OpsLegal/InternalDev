package com.opslegal.tda.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.opslegal.tda.TdaApp
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * A routine's reminder (a prayer, medication…) at its time, on its days. It comes through even when the user is focused:
 * it is one of the few things the front door pushes. Scheduled each morning and whenever a routine is saved.
 */
class RoutineReminder(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val title = inputData.getString("title") ?: return Result.success()
        com.opslegal.tda.updates.UpdatesWorker.alert(applicationContext, 5000 + (title.hashCode() and 0xfff), "⏰ $title", "Your routine's reminder.", true)
        return Result.success()
    }

    companion object {
        /** Today's reminders still ahead, one unique job each (scheduling again changes nothing). */
        fun schedule(app: TdaApp) {
            val now = LocalDateTime.now()
            val today = LocalDate.now()
            app.boards.board.value.routines.filter { it.remind.isNotBlank() && today.dayOfWeek.value in it.days }.forEach { r ->
                val at = runCatching { LocalTime.parse(r.remind) }.getOrNull() ?: return@forEach
                val delay = Duration.between(now, today.atTime(at))
                if (delay.isNegative) return@forEach
                WorkManager.getInstance(app).enqueueUniqueWork("remind-${r.id}-$today-${r.remind}", ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<RoutineReminder>().setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS).setInputData(workDataOf("title" to r.title)).build())
            }
        }
    }
}
