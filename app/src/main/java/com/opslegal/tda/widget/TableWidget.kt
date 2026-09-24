package com.opslegal.tda.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.opslegal.tda.TdaApp
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Cell
import com.opslegal.tda.core.model.DayRow
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.ui.MainActivity
import java.time.LocalDate

private val Yellow = Color(0xFFFFE14D)
private val Paper = Color(0xFFFFFFFF)
private val Ink = Color(0xFF1F2933)
private val Grid = Color(0xFFD5D9DE)
private val Muted = Color(0xFF8A939C)

private val StepIdKey = ActionParameters.Key<String>("stepId")

/**
 * The home-screen version of the table: today and the next days, five cells per
 * line. Tapping a cell turns it yellow (or back).
 */
class TableWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as TdaApp
        val language = app.settings.settings.value.dayLanguage
        provideContent {
            val board by app.boards.board.collectAsState()
            WidgetTable(board, language)
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

@Composable
private fun WidgetTable(board: Board, language: String) {
    val today = LocalDate.now()
    val rows = Planner.rows(board, today, 14, language)
    Column(
        modifier = GlanceModifier.fillMaxSize().background(Paper).cornerRadius(16.dp).padding(8.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp).clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Today's 5", style = TextStyle(color = ColorProvider(Ink), fontWeight = FontWeight.Bold, fontSize = 13.sp))
            Spacer(GlanceModifier.defaultWeight())
            val todayRow = rows.firstOrNull { it.date == today.toString() }
            if (todayRow != null) {
                Text("${todayRow.completed}/${todayRow.filled}", style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp))
            }
        }
        LazyColumn {
            items(rows, itemId = { it.date.hashCode().toLong() }) { row ->
                DayLine(row, isToday = row.date == today.toString())
            }
        }
    }
}

@Composable
private fun DayLine(row: DayRow, isToday: Boolean) {
    Column {
        Row(modifier = GlanceModifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = GlanceModifier.width(36.dp).height(40.dp).background(if (row.allDone) Yellow else Paper),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    row.label,
                    style = TextStyle(
                        color = ColorProvider(Ink),
                        fontSize = 12.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    ),
                )
            }
            row.cells.forEach { cell ->
                Spacer(GlanceModifier.width(2.dp))
                CellBox(cell, GlanceModifier.defaultWeight())
            }
        }
        Spacer(GlanceModifier.height(2.dp))
    }
}

@Composable
private fun CellBox(cell: Cell?, modifier: GlanceModifier) {
    var m = modifier.height(40.dp).cornerRadius(4.dp)
        .background(if (cell?.done == true) Yellow else if (cell == null) Paper else Color(0xFFF4F5F7))
        .padding(horizontal = 2.dp)
    if (cell != null) m = m.clickable(actionRunCallback<ToggleCellAction>(actionParametersOf(StepIdKey to cell.stepId)))
    Box(modifier = m, contentAlignment = Alignment.Center) {
        if (cell == null) {
            Box(GlanceModifier.fillMaxWidth().height(1.dp).background(Grid)) {}
        } else {
            Text(cell.title, maxLines = 2, style = TextStyle(color = ColorProvider(Ink), fontSize = 10.sp))
        }
    }
}
