package com.opslegal.tda.core.agent

import java.time.LocalDate

/** A meeting or appointment from the user's calendar (read only). */
data class CalendarEvent(
    val title: String,
    /** ISO local date-time, or ISO date for all-day events. */
    val start: String,
    val end: String,
    val allDay: Boolean = false,
    val calendar: String = "",
    val location: String = "",
) {
    fun describe(): String = buildString {
        append(if (allDay) "$start (all day)" else "$start → ${end.substringAfter('T')}")
        append(": ").append(title.ifBlank { "(busy)" })
        if (location.isNotBlank()) append(" @ ").append(location)
        if (calendar.isNotBlank()) append(" [").append(calendar).append("]")
    }
}

/** Where calendar events come from. On Android: the phone's calendars (Outlook, Google, Samsung...). */
fun interface CalendarSource {
    suspend fun events(from: LocalDate, to: LocalDate): List<CalendarEvent>
}
