package com.opslegal.tda.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import com.opslegal.tda.core.agent.CalendarEvent
import com.opslegal.tda.core.agent.CalendarSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Reads the calendars synced on the phone: Outlook/Exchange, Google, Samsung... No extra sign-in:
 * the phone already has these accounts. The only write is a meeting the user booked and confirmed.
 */
class PhoneCalendar(private val context: Context) : CalendarSource {

    val permitted: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun events(from: LocalDate, to: LocalDate): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!permitted) return@withContext emptyList()
        val zone = ZoneId.systemDefault()
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
            Instances.CALENDAR_DISPLAY_NAME, Instances.EVENT_LOCATION, Instances.AVAILABILITY,
        )
        val result = mutableListOf<CalendarEvent>()
        context.contentResolver.query(uri, projection, null, null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                // Holidays and "free" entries don't take time out of the day.
                if (c.getInt(6) == Events.AVAILABILITY_FREE) continue
                val allDay = c.getInt(3) == 1
                // All-day events are stored at midnight UTC.
                val eventZone = if (allDay) ZoneOffset.UTC else zone
                val start = Instant.ofEpochMilli(c.getLong(1)).atZone(eventZone).toLocalDateTime()
                val finish = Instant.ofEpochMilli(c.getLong(2)).atZone(eventZone).toLocalDateTime()
                result += CalendarEvent(
                    title = c.getString(0).orEmpty(),
                    start = if (allDay) start.toLocalDate().toString() else start.toString(),
                    end = if (allDay) finish.toLocalDate().toString() else finish.toString(),
                    allDay = allDay,
                    calendar = c.getString(4).orEmpty(),
                    location = c.getString(5).orEmpty(),
                )
                if (result.size >= 200) break
            }
        }
        result
    }

    val canWrite: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun addEvent(title: String, start: LocalDateTime, end: LocalDateTime, description: String): String? =
        withContext(Dispatchers.IO) {
            if (!canWrite) return@withContext null
            // The primary calendar the user can write to, else the first writable one.
            val projection = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.IS_PRIMARY)
            val selection = "${Calendars.CALENDAR_ACCESS_LEVEL} >= ${Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${Calendars.VISIBLE} = 1"
            var target: Pair<Long, String>? = null
            context.contentResolver.query(Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val candidate = c.getLong(0) to c.getString(1).orEmpty()
                    if (target == null || c.getInt(2) == 1) target = candidate
                    if (c.getInt(2) == 1) break
                }
            }
            val (calendarId, name) = target ?: return@withContext null
            val zone = ZoneId.systemDefault()
            val values = ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                put(Events.TITLE, title)
                put(Events.DESCRIPTION, description)
                put(Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
                put(Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
                put(Events.EVENT_TIMEZONE, zone.id)
            }
            runCatching { context.contentResolver.insert(Events.CONTENT_URI, values) }.getOrNull() ?: return@withContext null
            name.ifBlank { "your calendar" }
        }
}
