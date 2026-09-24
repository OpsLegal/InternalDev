package com.opslegal.tda

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.opslegal.tda.billing.BillingRepository
import com.opslegal.tda.core.agent.TdaAgent
import com.opslegal.tda.core.plan.BoardOps
import com.opslegal.tda.core.plan.Planner
import com.opslegal.tda.data.BoardRepository
import com.opslegal.tda.data.ChatRepository
import com.opslegal.tda.data.SettingsRepository
import com.opslegal.tda.widget.TableWidget
import com.opslegal.tda.work.DailyPlanWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate

class TdaApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var boards: BoardRepository
        private set
    lateinit var chat: ChatRepository
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var billing: BillingRepository
        private set

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        boards = BoardRepository(this)
        chat = ChatRepository(this)
        settings = SettingsRepository(this)
        billing = BillingRepository(this, appScope).also { it.connect() }

        DailyPlanWorker.schedule(this)
        appScope.launch { refreshToday() }

        // Keep the home-screen widget in sync with every change made in the app.
        appScope.launch {
            boards.board.drop(1).debounce(300).collect { TableWidget().updateAll(this@TdaApp) }
        }
    }

    /** Rolls unfinished cells forward and places new steps. Safe to call often. */
    suspend fun refreshToday() {
        val today = LocalDate.now()
        boards.update { Planner.dailyRefresh(BoardOps.archiveOld(it, today), today).board }
    }

    /** The assistant, or null when no AI account is connected. */
    fun agent(): TdaAgent? = settings.provider()?.let { TdaAgent(it, boards) }
}
