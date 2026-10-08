package com.opslegal.tda.persona

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.opslegal.tda.core.agent.Me
import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Update
import com.opslegal.tda.ui.MainActivity

/**
 * Notifications come from the assistant as from a person: Android's conversation style, with its name and face,
 * like a text from a real assistant ("Jimmy · Sarah: Please remind the boss…").
 */
object AssistantNotify {
    private const val SHORTCUT = "assistant"

    fun person(context: Context, board: Board): Person = Person.Builder()
        .setName(Me.name(board)).setKey(SHORTCUT).setImportant(true)
        .setIcon(IconCompat.createWithBitmap(Faces.bitmap(context, board.persona, 192)))
        .build()

    /** What the assistant says about an update: a message written to it shows who wrote and what they said. */
    fun line(board: Board, u: Update): String =
        if (Me.forMe(board, u)) "${Me.firstName(u.from)}: “${Me.said(board, u.text).take(160)}”" else u.summary

    /** Turns [builder] into a message from the assistant, with one line per item. */
    fun style(context: Context, board: Board, builder: NotificationCompat.Builder, lines: List<String>): NotificationCompat.Builder {
        val jimmy = person(context, board)
        val user = Person.Builder().setName("You").setKey("me").build()
        // A long-lived conversation shortcut: Android then shows the face and lets the user make it a priority conversation.
        runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(context, ShortcutInfoCompat.Builder(context, SHORTCUT)
                .setLongLived(true).setPerson(jimmy).setShortLabel(Me.name(board)).setIcon(jimmy.icon!!)
                .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(MainActivity.EXTRA_UPDATES, true))
                .build())
        }
        val style = NotificationCompat.MessagingStyle(user)
        val now = System.currentTimeMillis()
        lines.take(5).forEachIndexed { i, l -> style.addMessage(l, now - (lines.size - i) * 1000L, jimmy) }
        return builder.setStyle(style).setShortcutId(SHORTCUT).setLargeIcon(Faces.bitmap(context, board.persona, 192))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
    }
}
