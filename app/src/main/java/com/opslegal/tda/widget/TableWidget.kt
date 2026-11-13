package com.opslegal.tda.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.glance.layout.fillMaxHeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.opslegal.tda.TdaApp
import com.opslegal.tda.core.agent.Me
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Cell
import com.opslegal.tda.core.model.Outcome
import com.opslegal.tda.core.model.QuickThing
import com.opslegal.tda.core.model.TaskKind
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.core.plan.Quick
import com.opslegal.tda.core.plan.Updates
import com.opslegal.tda.core.plan.Fill
import com.opslegal.tda.core.agent.CalendarEvent
import com.opslegal.tda.core.plan.CalendarCells
import com.opslegal.tda.core.model.cellsPerDay
import com.opslegal.tda.ui.MainActivity
import java.time.LocalDate

private val Yellow = Color(0xFFFFE14D)
private val Paper = Color(0xFFFFFFFF)
private val Ink = Color(0xFF1F2933)
private val Muted = Color(0xFF6B7480)
private val Variant = Color(0xFFF1F2F4)
private val Outline = Color(0xFFD0D4D9)
private val Navy = Color(0xFF1F2A44)
private val Slate = Color(0xFF46505E)
private val Red = Color(0xFFB3261E)
private val Missed = Color(0xFFF6C4BE)
private val Bar = Color(0xFF2E8B57)

private val StepIdKey = ActionParameters.Key<String>("stepId")
private val SortParam = ActionParameters.Key<String>("sort")
private val SortKey = androidx.datastore.preferences.core.stringPreferencesKey("wsort")

/** 🔴 Urgent · ▦ Type · ● Area: how the widget's notifications are sorted (kept with the widget). */
class SortAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val v = parameters[SortParam] ?: return
        androidx.glance.appwidget.state.updateAppWidgetState(context, glanceId) { it[SortKey] = v }
        TableWidget().update(context, glanceId)
    }
}
/** What a tap asks the app for (see MainActivity.EXTRA_WIDGET): "talk", "ask", "jot", "capture", "updates" or "table". */
private val OpenKey = ActionParameters.Key<String>(MainActivity.EXTRA_WIDGET)

private val Compact = DpSize(250.dp, 110.dp)
private val Mid = DpSize(250.dp, 240.dp)
private val Full = DpSize(250.dp, 380.dp)

/**
 * The home-screen widget, resizable. Both sizes: today's line exactly as in the table (the day, its big cells, free
 * ones white, the ⚡ line) and one row of four equal buttons: ⚡ Quick things, 📥 Fill free cells, ⌨️ Type, 🎤 Talk.
 * Taller, it shows the top notifications first, then the days under them: 4×4 today and tomorrow, 4×6 a week (scrolls).
 * Notifications are bordered in their area's colour. A tap on a cell ticks it (yellow).
 */
class TableWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(Compact, Mid, Full))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as TdaApp
        // The next week's calendar events, read on the phone (none without calendar access).
        val events = runCatching {
            val cal = com.opslegal.tda.data.PhoneCalendar(context)
            if (app.settings.settings.value.calendarAccess && cal.permitted) cal.events(LocalDate.now(), LocalDate.now().plusDays(7)) else emptyList()
        }.getOrDefault(emptyList())
        provideContent {
            val board by app.boards.board.collectAsState()
            val settings by app.settings.settings.collectAsState()
            val h = LocalSize.current.height
            val sort = androidx.glance.currentState<androidx.datastore.preferences.core.Preferences>()[SortKey] ?: "urgent"
            WidgetBody(board, settings.dayLanguage, size = if (h >= Full.height) 2 else if (h >= Mid.height) 1 else 0, events = events, sort = sort)
        }
    }
}

class TableWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TableWidget()
}

class ToggleCellAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val stepId = parameters[StepIdKey] ?: return
        val app = context.applicationContext as TdaApp
        app.boards.update { BoardOps.toggleStep(it, stepId) }
        TableWidget().updateAll(context)
    }
}

/** ⚡ Today's quick things picked from the pool (a few more once all are done). */
class PickQuickAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as TdaApp
        app.boards.update { Fill.quick(it, LocalDate.now()) }
        TableWidget().updateAll(context)
    }
}

/** 📥 Today's free cells filled: undone tasks first, then what notifications propose, then each project's next step. */
class FillCellsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as TdaApp
        app.boards.update { Fill.bigs(it, LocalDate.now()).first }
        TableWidget().updateAll(context)
    }
}

private fun open(what: String): Action = actionStartActivity<MainActivity>(actionParametersOf(OpenKey to what))

private fun text(color: Color = Ink, size: Int = 12, bold: Boolean = false, strike: Boolean = false) = TextStyle(
    color = ColorProvider(color), fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    textDecoration = if (strike) TextDecoration.LineThrough else null,
)

/** A box with a 1 dp border (Glance has no border: an outer box in the border's colour, the inside 1 dp in). */
@Composable
private fun Bordered(modifier: GlanceModifier, border: Color, fill: Color, radius: Int, action: Action?, content: @Composable () -> Unit) {
    Box(modifier.cornerRadius(radius.dp).background(border).padding(1.dp).let { if (action != null) it.clickable(action) else it }) {
        Box(GlanceModifier.fillMaxSize().cornerRadius((radius - 1).dp).background(fill)) { content() }
    }
}

private fun cellFill(c: Cell) = when { c.done -> Yellow; c.outcome == Outcome.MISSED -> Missed; c.outcome != null -> Outline; else -> Variant }

private fun cellInk(c: Cell) = when {
    c.outcome == Outcome.MISSED -> Color(0xFF8C1D18)
    c.outcome != null -> Muted
    c.kind == TaskKind.DEADLINE -> Red
    c.kind == TaskKind.MEETING -> Color(0xFF111111)
    c.inProject -> Color(0xFF1B6B43)
    else -> Color(0xFF1E4E8C)
}

/** A day's line, as in the table: the day's box, its big cells (grey to do, yellow done, white free) and its ⚡ line. */
@Composable
private fun DayLine(board: Board, language: String, d: LocalDate = LocalDate.now(), events: List<CalendarEvent> = emptyList()) {
    val isToday = d == LocalDate.now()
    val row = Planner.rows(board, d, 1, language, today = LocalDate.now()).firstOrNull()
    val n = board.cellsPerDay
    val slots = (row?.cells.orEmpty().take(n) + List(n) { null }).take(n)
    val q = row?.quick.orEmpty()
    Row(GlanceModifier.fillMaxWidth().height(94.dp)) {
        val dayEvents = events.filter { it.start.take(10) == d.toString() }
        val undecided = dayEvents.count { board.calendarChoices[CalendarCells.key(it)] == null }
        Bordered(GlanceModifier.width(40.dp).fillMaxHeight(), if (isToday) Navy else Outline, if (slots.isNotEmpty() && slots.all { it?.done == true }) Yellow else Paper, 6, open("table")) {
            Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                Text(row?.label ?: "", style = text(size = 12, bold = true))
                // 📅 2 dark: events still to decide; 📅✓ yellow: all decided.
                if (dayEvents.isNotEmpty()) Box(GlanceModifier.padding(top = 3.dp).cornerRadius(8.dp).background(if (undecided > 0) Ink else Yellow).padding(horizontal = 4.dp, vertical = 1.dp)) {
                    Text("📅" + if (undecided > 0) "$undecided" else "✓", style = text(if (undecided > 0) Color.White else Ink, 9, bold = true))
                }
            }
        }
        Spacer(GlanceModifier.width(3.dp))
        Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
            Row(GlanceModifier.fillMaxWidth().height(64.dp)) {
                slots.forEachIndexed { i, c ->
                    Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(end = if (i < n - 1) 3.dp else 0.dp)) {
                        if (c == null) Bordered(GlanceModifier.fillMaxSize(), Outline, Paper, 6, open("table")) {}
                        else Bordered(GlanceModifier.fillMaxSize(), Outline, cellFill(c), 6, actionRunCallback<ToggleCellAction>(actionParametersOf(StepIdKey to c.stepId))) {
                            Row(GlanceModifier.fillMaxSize()) {
                                // Every project cell has a thin green bar on its left, as in the table.
                                if (c.inProject && (c.outcome == null || c.outcome == Outcome.MISSED)) Box(GlanceModifier.width(3.dp).fillMaxHeight().background(Bar)) {}
                                Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(3.dp), contentAlignment = Alignment.Center) {
                                    Text((if (c.outcome == Outcome.PUSHED) "↷ " else "") + c.title, maxLines = 4,
                                        style = text(cellInk(c), 10, bold = c.priority >= com.opslegal.tda.core.model.Priority.HIGH && c.outcome == null, strike = c.outcome == Outcome.CANCELLED))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(GlanceModifier.height(3.dp))
            val done = q.count { it.done }
            Bordered(GlanceModifier.fillMaxWidth().height(24.dp), Outline, if (q.isEmpty()) Paper else if (done == q.size) Yellow else Variant, 6, open("table")) {
                Box(GlanceModifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    Text(if (q.isEmpty()) (if (isToday) "⚡ add a quick thing" else "⚡") else "⚡ $done/${q.size}  " + q.filter { !it.done }.joinToString(" · ") { it.title }.ifBlank { "all done" },
                        maxLines = 1, style = text(if (q.isEmpty()) Muted else Ink, 11, bold = q.isNotEmpty()))
                }
            }
        }
    }
}

/** One row of four equal push buttons in the app's navy and slate: ⚡ Quick things, 📥 Fill, ⌨️ Type, 🎤 Talk. */
@Composable
private fun Buttons(board: Board, free: Int) {
    val q = Quick.onDay(board, LocalDate.now(), LocalDate.now())
    @Composable fun androidx.glance.layout.RowScope.Btn(icon: String, label: String, color: Color, action: Action, on: Boolean = true) {
        Box(GlanceModifier.defaultWeight().height(52.dp).cornerRadius(12.dp).background(if (on) color else color.copy(alpha = 0.45f)).clickable(action), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(icon, style = text(Color.White, 15))
                Text(label, maxLines = 1, style = text(Color.White, 11, bold = true))
            }
        }
    }
    Row(GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
        Btn("⚡", if (q.isNotEmpty() && q.all { it.done }) "More" else "Quick", Slate, actionRunCallback<PickQuickAction>())
        Spacer(GlanceModifier.width(6.dp))
        Btn("📥", if (free > 0) "Fill $free" else "Full", Slate, actionRunCallback<FillCellsAction>(), on = free > 0)
        Spacer(GlanceModifier.width(6.dp))
        Btn("✏️", "Jot", Navy, open("jot"))
        Spacer(GlanceModifier.width(5.dp))
        Btn("📷", "Capture", Navy, open("capture"))
        Spacer(GlanceModifier.width(5.dp))
        Btn("🎤", "Talk", Navy, open("talk"))
    }
}

@Composable
private fun WidgetBody(board: Board, language: String, size: Int, events: List<CalendarEvent> = emptyList(), sort: String = "urgent") {
    val d = LocalDate.now()
    val row = Planner.rows(board, d, 1, language, today = d).firstOrNull()
    val cells = row?.cells.orEmpty().take(board.cellsPerDay).filterNotNull()
    val free = Fill.free(board, d)
    val news = Updates.inbox(board, d)
    val urgent = news.any { it.urgent }
    Column(GlanceModifier.fillMaxSize().background(Paper).cornerRadius(16.dp).padding(8.dp)) {
        // The date, how many done and free, and the notifications' count (red when one is urgent).
        Row(GlanceModifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} ${d.dayOfMonth} · ${cells.count { it.done }}/${cells.size} done" +
                if (free > 0) " · $free free" else "", style = text(bold = true, size = 13), maxLines = 1, modifier = GlanceModifier.defaultWeight().clickable(open("table")))
            Bordered(GlanceModifier.height(26.dp), if (urgent) Red else Outline, if (urgent) Red else Paper, 13, open("updates")) {
                Box(GlanceModifier.padding(horizontal = 9.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text("🔔 ${news.size}", style = text(if (urgent) Color.White else Ink, 12, bold = true))
                }
            }
        }
        if (size == 0) { DayLine(board, language, events = events); Spacer(GlanceModifier.defaultWeight()) }
        else {
            // Notifications first (the few that matter, then "＋N more"), the days under them; the buttons stay at the bottom.
            val k = if (size == 2) 3 else 2
            val days = if (size == 2) 7 else 2
            // Sorted: urgent first, or grouped by type or by area, with each group's count in sight.
            val groupOf: (Update) -> String = if (sort == "area") { u -> noteArea(board, u)?.id.orEmpty() } else { u -> noteType(board, u) }
            val groups: List<Pair<String, String>> = if (sort == "area") com.opslegal.tda.core.plan.Areas.all(board.settings).map { it.id to "● ${it.name}" } else TYPES
            val sorted = if (sort == "urgent") news.sortedByDescending { it.urgent }
                else news.sortedWith(compareBy<Update> { u -> groups.indexOfFirst { it.first == groupOf(u) }.let { if (it < 0) 99 else it } }.thenByDescending { it.urgent })
            LazyColumn(GlanceModifier.defaultWeight()) {
                item {
                    Row(GlanceModifier.fillMaxWidth().padding(top = 6.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🔔 Notifications · ${news.size}", style = text(Muted, 11, bold = true), modifier = GlanceModifier.defaultWeight().clickable(open("updates")))
                        listOf("urgent" to "🔴 Urgent", "type" to "▦ Type", "area" to "● Area").forEach { (key, label) ->
                            Box(GlanceModifier.padding(start = 3.dp)) {
                                Box(GlanceModifier.cornerRadius(10.dp).background(if (sort == key) Navy else Variant).padding(horizontal = 6.dp, vertical = 2.dp)
                                    .clickable(actionRunCallback<SortAction>(actionParametersOf(SortParam to key)))) {
                                    Text(label, style = text(if (sort == key) Color.White else Slate, 10, bold = true))
                                }
                            }
                        }
                    }
                }
                if (sort != "urgent" && news.isNotEmpty()) item {
                    Text(groups.map { g -> g to news.count { groupOf(it) == g.first } }.filter { it.second > 0 }.joinToString("   ") { (g, c) -> "${g.second} $c" },
                        maxLines = 2, style = text(Ink, 10), modifier = GlanceModifier.padding(bottom = 3.dp).clickable(open("updates")))
                }
                if (news.isEmpty()) item { Text("Nothing waiting for you.", style = text(Muted, 11)) }
                items(sorted.take(k)) { u -> NoteLine(board, u) }
                if (news.size > k) item { Text("＋${news.size - k} more ›", style = text(Color(0xFF1E4E8C), 11, bold = true), modifier = GlanceModifier.padding(start = 4.dp, bottom = 2.dp).clickable(open("updates"))) }
                item { Text(if (days > 2) "📅 Next days" else "📅 Today and tomorrow", style = text(Muted, 11, bold = true), modifier = GlanceModifier.padding(top = 8.dp, bottom = 3.dp).clickable(open("table"))) }
                items((0 until days).map { d.plusDays(it.toLong()) }) { day ->
                    Column(GlanceModifier.fillMaxWidth().padding(bottom = 3.dp)) {
                        CalStrip(board, events.filter { it.start.take(10) == day.toString() })
                        DayLine(board, language, day, events)
                    }
                }
                if (events.isNotEmpty()) item {
                    Row(GlanceModifier.padding(bottom = 4.dp)) {
                        Chip("✓ in my day", Ink, Color.White, Ink); Spacer(GlanceModifier.width(4.dp))
                        Chip("not decided", Paper, Ink, Ink); Spacer(GlanceModifier.width(4.dp))
                        Chip("left out", Yellow, Ink, Color(0xFFE5C800))
                    }
                }
            }
        }
        Buttons(board, free)
    }
}

private val TYPES = listOf("cal" to "📅 Calendar", "todo" to "✋ To do", "flag" to "🚩 Flagged", "money" to "💲 Money", "news" to "📰 To read", "loop" to "👀 In the loop")
private val NEWS = Regex("(?i)\\b(newsletter|digest|fyi|unsubscribe)\\b")

/** A notification's type, by rules (the same groups as the front door's filters). */
private fun noteType(board: Board, u: Update): String = when {
    u.source == com.opslegal.tda.core.plan.CalendarAlerts.SOURCE -> "cal"
    u.flagged -> "flag"
    com.opslegal.tda.core.plan.Found.isBill(board, u) -> "money"
    NEWS.containsMatchIn("${u.from} ${u.title} ${u.summary}") -> "news"
    u.cc || com.opslegal.tda.core.plan.FrontDoor.inLoop(board, u) -> "loop"
    else -> "todo"
}

private fun noteArea(board: Board, u: Update) = u.project.takeIf { it.isNotBlank() }?.let { com.opslegal.tda.core.plan.Areas.ofProject(board, it) }
    ?: com.opslegal.tda.core.plan.Areas.all(board.settings).firstOrNull { it.id == com.opslegal.tda.core.plan.Areas.guess(board, "${u.title} ${u.summary} ${u.text}") }

/** A small pill: its fill, ink and border. */
@Composable
private fun Chip(label: String, fill: Color, ink: Color, border: Color, action: Action? = null) {
    Bordered(GlanceModifier.height(20.dp), border, fill, 10, action) {
        Box(GlanceModifier.fillMaxHeight().padding(horizontal = 7.dp), contentAlignment = Alignment.Center) { Text(label, maxLines = 1, style = text(ink, 10, bold = true)) }
    }
}

/** A day's calendar events, above its line: ✓ black = in my day, white = not decided yet, yellow = left out. A tap opens the day in the app. */
@Composable
private fun CalStrip(board: Board, events: List<CalendarEvent>) {
    if (events.isEmpty()) return
    Row(GlanceModifier.fillMaxWidth().padding(start = 43.dp, bottom = 2.dp)) {
        events.sortedBy { it.start }.take(3).forEach { e ->
            val c = board.calendarChoices[CalendarCells.key(e)]
            val label = (if (c?.added == true) "✓ " else "") + listOf(CalendarCells.startTime(e), e.title).filter { it.isNotBlank() }.joinToString(" ")
            Box(GlanceModifier.padding(end = 3.dp)) {
                when {
                    c?.added == true -> Chip(label, Ink, Color.White, Ink, open("table"))
                    c != null -> Chip(label, Yellow, Ink, Color(0xFFE5C800), open("table"))
                    else -> Chip(label, Paper, Ink, Ink, open("updates"))
                }
            }
        }
    }
}

/** A notification on one line, bordered and lightly tinted in its area's colour, with the area's name. */
@Composable
private fun NoteLine(board: Board, u: Update) {
    val area = u.project.takeIf { it.isNotBlank() }?.let { com.opslegal.tda.core.plan.Areas.ofProject(board, it) }
        ?: com.opslegal.tda.core.plan.Areas.all(board.settings).firstOrNull { it.id == com.opslegal.tda.core.plan.Areas.guess(board, "${u.title} ${u.summary} ${u.text}") }
    val c = area?.let { com.opslegal.tda.ui.areaColor(board, it.id) } ?: Outline
    Box(GlanceModifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Bordered(GlanceModifier.fillMaxWidth().height(28.dp), c, lerp(Paper, c, 0.10f), 6, open("updates")) {
            Row(GlanceModifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((if (u.urgent) "🔴 " else "") + u.title.ifBlank { listOf(u.from, u.summary).filter { it.isNotBlank() }.joinToString(" · ") },
                    maxLines = 1, style = text(Ink, 11, bold = true), modifier = GlanceModifier.defaultWeight())
                if (area != null) Text("● ${area.name}", maxLines = 1, style = text(c, 10))
            }
        }
    }
}
