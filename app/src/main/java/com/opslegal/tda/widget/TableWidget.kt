package com.opslegal.tda.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
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
import com.opslegal.tda.ui.MainActivity
import java.time.LocalDate

private val Yellow = Color(0xFFFFE14D)
private val Paper = Color(0xFFFFFFFF)
private val Ink = Color(0xFF1F2933)
private val Muted = Color(0xFF6B7480)
private val Soft = Color(0xFFF1F2F4)
private val Navy = Color(0xFF1F2A44)
private val Red = Color(0xFFB3261E)

private val StepIdKey = ActionParameters.Key<String>("stepId")
private val QuickIdKey = ActionParameters.Key<String>("quickId")
/** What a tap asks the app for (see MainActivity.EXTRA_WIDGET): "talk", "ask", "updates" or "table". */
private val OpenKey = ActionParameters.Key<String>(MainActivity.EXTRA_WIDGET)

private val Compact = DpSize(250.dp, 110.dp)
private val Full = DpSize(250.dp, 300.dp)

/**
 * The home-screen widget, resizable: 4×2 shows today's cells, the ⚡ line, the top notification and Jimmy;
 * 4×6 shows today as a list (tap to tick), the quick things, the important notifications and Jimmy's buttons.
 * Tapping a cell or a quick thing turns it yellow (done) or back; nothing else changes without opening the app.
 */
class TableWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(Compact, Full))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as TdaApp
        provideContent {
            val board by app.boards.board.collectAsState()
            val settings by app.settings.settings.collectAsState()
            if (LocalSize.current.height >= Full.height) FullWidget(board, settings.dayLanguage) else CompactWidget(board, settings.dayLanguage)
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

class ToggleQuickAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[QuickIdKey] ?: return
        val app = context.applicationContext as TdaApp
        app.boards.update { b -> b.quick.firstOrNull { it.id == id }?.let { Quick.tick(b, id, !it.done, LocalDate.now()) } ?: b }
        TableWidget().updateAll(context)
    }
}

private fun open(what: String): Action = actionStartActivity<MainActivity>(actionParametersOf(OpenKey to what))

private fun text(color: Color = Ink, size: Int = 12, bold: Boolean = false, strike: Boolean = false) = TextStyle(
    color = ColorProvider(color), fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    textDecoration = if (strike) TextDecoration.LineThrough else null,
)

private data class Today(val label: String, val cells: List<Cell>, val quick: List<QuickThing>, val news: List<Update>, val tomorrow: List<Cell>)

private fun today(board: Board, language: String): Today {
    val d = LocalDate.now()
    val rows = Planner.rows(board, d, 2, language, today = d)
    val t = rows.firstOrNull()
    return Today(
        label = "${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} ${d.dayOfMonth}",
        cells = t?.cells?.filterNotNull().orEmpty(), quick = t?.quick.orEmpty(),
        news = Updates.inbox(board), tomorrow = rows.getOrNull(1)?.cells?.filterNotNull().orEmpty(),
    )
}

private fun newsTitle(u: Update) = listOf(u.from, u.title.ifBlank { u.summary }).filter { it.isNotBlank() }.joinToString(" · ")

/** Today's date and count, the notifications, and Jimmy (type or talk): the same on both sizes. */
@Composable
private fun Header(t: Today, board: Board) {
    val done = t.cells.count { it.done }
    Row(GlanceModifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${t.label} · $done/${t.cells.size} done", style = text(bold = true, size = 13), maxLines = 1,
            modifier = GlanceModifier.defaultWeight().clickable(open("table")))
        val urgent = t.news.any { it.urgent }
        Box(GlanceModifier.height(28.dp).cornerRadius(14.dp).background(if (urgent) Red else Soft).padding(horizontal = 8.dp).clickable(open("updates")),
            contentAlignment = Alignment.Center) {
            Text("🔔 ${t.news.size}", style = text(if (urgent) Color.White else Ink, 12, bold = true))
        }
        Spacer(GlanceModifier.width(4.dp))
        Box(GlanceModifier.height(28.dp).cornerRadius(14.dp).background(Soft).padding(horizontal = 8.dp).clickable(open("ask")), contentAlignment = Alignment.Center) {
            Text("💬 ${Me.name(board)}", style = text(size = 12, bold = true), maxLines = 1)
        }
        Spacer(GlanceModifier.width(4.dp))
        Box(GlanceModifier.width(28.dp).height(28.dp).cornerRadius(14.dp).background(Navy).clickable(open("talk")), contentAlignment = Alignment.Center) {
            Text("🎤", style = text(Color.White, 13))
        }
    }
}

private fun cellColor(c: Cell) = when {
    c.done -> Yellow
    c.outcome == Outcome.MISSED -> Color(0xFFF6C4BE)
    else -> Soft
}

private fun cellInk(c: Cell) = when {
    c.done -> Ink
    c.outcome != null -> Muted
    c.kind == TaskKind.DEADLINE -> Red
    c.inProject -> Color(0xFF1B6B43)
    else -> Color(0xFF1E4E8C)
}

private fun tick(c: Cell): Action = actionRunCallback<ToggleCellAction>(actionParametersOf(StepIdKey to c.stepId))

@Composable
private fun CompactWidget(board: Board, language: String) {
    val t = today(board, language)
    Column(GlanceModifier.fillMaxSize().background(Paper).cornerRadius(16.dp).padding(8.dp)) {
        Header(t, board)
        // Today's big cells side by side: tap one to turn it yellow (done) or back.
        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
            if (t.cells.isEmpty()) Box(GlanceModifier.fillMaxSize().cornerRadius(6.dp).background(Soft).clickable(open("table")), contentAlignment = Alignment.Center) {
                Text("Nothing planned today. Tap to add.", style = text(Muted, 11))
            }
            t.cells.take(5).forEach { c ->
                Box(GlanceModifier.defaultWeight().fillMaxSize().padding(end = 3.dp)) {
                    Box(GlanceModifier.fillMaxSize().cornerRadius(6.dp).background(cellColor(c)).padding(3.dp).clickable(tick(c)), contentAlignment = Alignment.Center) {
                        Text((if (c.done) "✓ " else "") + c.title, maxLines = 3, style = text(cellInk(c), 10, strike = c.outcome == Outcome.CANCELLED))
                    }
                }
            }
        }
        Spacer(GlanceModifier.height(4.dp))
        val q = t.quick
        Text(
            if (q.isEmpty()) "⚡ No quick things today" else "⚡ ${q.count { it.done }}/${q.size}  " + q.filter { !it.done }.joinToString(" · ") { it.title }.ifBlank { "all done" },
            maxLines = 1, style = text(size = 11), modifier = GlanceModifier.fillMaxWidth().clickable(open("table")),
        )
        t.news.firstOrNull()?.let { u ->
            Text((if (u.urgent) "🔴 " else "🔔 ") + newsTitle(u), maxLines = 1, style = text(if (u.urgent) Red else Ink, 11),
                modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp).clickable(open("updates")))
        }
    }
}

private sealed interface Line {
    data class Title(val text: String, val action: Action) : Line
    data class Big(val cell: Cell) : Line
    data class Quickie(val q: QuickThing) : Line
    data class News(val u: Update) : Line
    data class Note(val text: String, val action: Action) : Line
}

@Composable
private fun FullWidget(board: Board, language: String) {
    val t = today(board, language)
    val lines = buildList<Line> {
        add(Line.Title("Today", open("table")))
        if (t.cells.isEmpty()) add(Line.Note("Nothing planned today. Tap to add.", open("table")))
        t.cells.forEach { add(Line.Big(it)) }
        add(Line.Title("🔔 Important · ${t.news.size}", open("updates")))
        if (t.news.isEmpty()) add(Line.Note("Nothing waiting for you.", open("updates")))
        t.news.take(4).forEach { add(Line.News(it)) }
        if (t.news.size > 4) add(Line.Note("+${t.news.size - 4} more", open("updates")))
        add(Line.Title("⚡ Quick things · ${t.quick.count { it.done }}/${t.quick.size}", open("table")))
        if (t.quick.isEmpty()) add(Line.Note("None picked today.", open("table")))
        t.quick.forEach { add(Line.Quickie(it)) }
        if (t.tomorrow.isNotEmpty()) {
            add(Line.Title("Tomorrow", open("table")))
            add(Line.Note(t.tomorrow.joinToString(" · ") { it.title }, open("table")))
        }
    }
    Column(GlanceModifier.fillMaxSize().background(Paper).cornerRadius(16.dp).padding(8.dp)) {
        Header(t, board)
        LazyColumn(GlanceModifier.defaultWeight()) {
            items(lines) { line ->
                when (line) {
                    is Line.Title -> Text(line.text, style = text(Muted, 11, bold = true), modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp).clickable(line.action))
                    is Line.Big -> Box(GlanceModifier.fillMaxWidth().padding(bottom = 3.dp)) {
                        Row(GlanceModifier.fillMaxWidth().height(38.dp).cornerRadius(8.dp).background(cellColor(line.cell)).padding(horizontal = 8.dp).clickable(tick(line.cell)),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (line.cell.done) "✓" else "○", style = text(cellInk(line.cell), 13, bold = true), modifier = GlanceModifier.width(20.dp))
                            Text(line.cell.title, maxLines = 2, style = text(cellInk(line.cell), 12, bold = !line.cell.done, strike = line.cell.outcome == Outcome.CANCELLED))
                        }
                    }
                    is Line.Quickie -> Row(GlanceModifier.fillMaxWidth().height(28.dp).clickable(actionRunCallback<ToggleQuickAction>(actionParametersOf(QuickIdKey to line.q.id))),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (line.q.done) "☑" else "☐", style = text(size = 14), modifier = GlanceModifier.width(22.dp))
                        Text(line.q.title, maxLines = 1, style = text(if (line.q.done) Muted else Ink, 12, strike = line.q.done))
                    }
                    is Line.News -> Text((if (line.u.urgent) "🔴 " else "• ") + newsTitle(line.u), maxLines = 2,
                        style = text(if (line.u.urgent) Red else Ink, 12, bold = line.u.urgent), modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(open("updates")))
                    is Line.Note -> Text(line.text, maxLines = 2, style = text(Muted, 11), modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(line.action))
                }
            }
        }
        // Jimmy, one tap away: talk (the mic starts at once) or type.
        Row(GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
            Box(GlanceModifier.defaultWeight().height(40.dp).cornerRadius(20.dp).background(Navy).clickable(open("talk")), contentAlignment = Alignment.Center) {
                Text("🎤 Talk to ${Me.name(board)}", style = text(Color.White, 13, bold = true))
            }
            Spacer(GlanceModifier.width(6.dp))
            Box(GlanceModifier.width(96.dp).height(40.dp).cornerRadius(20.dp).background(Soft).clickable(open("ask")), contentAlignment = Alignment.Center) {
                Text("💬 Type", style = text(size = 13, bold = true))
            }
        }
    }
}
