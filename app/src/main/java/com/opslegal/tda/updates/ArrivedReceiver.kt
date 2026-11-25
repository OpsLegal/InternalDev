package com.opslegal.tda.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opslegal.tda.TdaApp
import com.opslegal.tda.core.model.UpdateStatus
import com.opslegal.tda.core.plan.People

/**
 * ✈️ Landed: the phone's time zone changed. A promise like "Préviens-moi quand tu arrives" comes back as a
 * notification; its tap opens the front door with the line ready. Nothing is sent without the user's tap (Rule 1).
 */
class ArrivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val app = context.applicationContext as? TdaApp ?: return
        val board = runCatching { app.boards.board.value }.getOrNull() ?: return
        val open = board.updates.filter { it.status == UpdateStatus.NEW && it.prog == null }
        val (u, p) = People.promises(open).firstOrNull { it.second.event == "arrive" } ?: return
        UpdatesWorker.alert(context, 7301, "${p.icon} Landed? Tell ${People.firstName(u)}",
            "“${p.line}” is ready: one tap and ${People.firstName(u)} knows.", high = true)
    }
}
