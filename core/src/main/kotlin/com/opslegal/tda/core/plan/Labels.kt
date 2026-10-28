package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task

/**
 * What the automations need to place a task, read from its words (the assistant fills the same when it reads a
 * capture; the user changes any of it with a tap). Who: "office" (a public office or a business, open on office days
 * only), "others" (someone else: weekdays), "alone" (me, online: any day of the area). Size: "quick" (15 minutes or
 * less, no research) or "big".
 */
object Labels {
    private val ONLINE = Regex("(?i)\\b(online|en ligne|fill|remplir|form|formulaire|submit|soumettre|file online|request online|order online|commander)\\b")
    private val OTHERS = Regex("(?i)\\b(call|phone|appeler|appelle|téléphoner|telephoner|rappeler|meet|meeting|rencontrer|ask|demander|discuss|discuter|school|école|ecole|teacher|prof|client|with|avec|visit|visiter|interview)\\b")
    private val ALONE = Regex("(?i)\\b(draft|rédiger|rediger|write|écrire|ecrire|prepare|préparer|preparer|read|lire|review|réviser|reviser|sort|trier|plan)\\b")
    private val QUICK = Regex("(?i)^(call|phone|text|reply|answer|confirm|book|schedule|pay|send|ask|appeler|appelle|rappeler|répondre|repondre|confirmer|prendre (un )?rendez|réserver|reserver|payer|envoyer|demander|texter|email|courriel)\\b")
    private val BIG = Regex("(?i)\\b(draft|prepare|analy\\w*|review|research|compare|sort|organi\\w*|write the|plan the|rédiger|rediger|préparer|preparer|analyser|réviser|reviser|chercher|comparer|trier|organiser|report|rapport|proposal|proposition|contract|contrat)\\b")

    fun whoOf(text: String): String = when {
        ONLINE.containsMatchIn(text) -> "alone"
        Offices.needs(text) && (OTHERS.containsMatchIn(text) || Regex("(?i)\\b(visit|go to|aller|passer|déposer|deposer)\\b").containsMatchIn(text)) -> "office"
        Offices.needs(text) -> "office"
        OTHERS.containsMatchIn(text) -> "others"
        ALONE.containsMatchIn(text) -> "alone"
        else -> ""
    }

    fun sizeOf(text: String): String = when {
        BIG.containsMatchIn(text) -> "big"
        QUICK.containsMatchIn(text.trim()) -> "quick"
        else -> ""
    }

    private val BUY = Regex("(?i)^(buy|acheter|achète|achete|milk|bread|eggs|lait|pain|œufs|oeufs|toner|groceries|épicerie|epicerie)\\b|\\b(to buy|à acheter|a acheter)\\b")

    /** What a line written down is: "buy" (the To buy list), "task" (a cell) or "quick" (the ⚡ pool). Pre-selected, never forced. */
    fun kindOf(text: String): String = when {
        BUY.containsMatchIn(text.trim()) -> "buy"
        sizeOf(text) == "big" -> "task"
        else -> "quick"
    }

    /** "Who" in the cell menu and in Replan: its icon, name and where it is planned. */
    val WHO = linkedMapOf(
        "office" to Triple("🏛", "Office open", "office days only"),
        "others" to Triple("👥", "With someone", "weekdays"),
        "alone" to Triple("🧍", "Me alone", "any day"),
    )

    fun who(task: Task, step: Step? = null): String = task.who.ifBlank {
        whoOf(listOfNotNull(task.title, step?.title, task.description, step?.description).joinToString(" "))
    }

    /** An office or someone else: only on office days (never weekends or holidays). Me alone: any day of the area. */
    fun weekdayOnly(task: Task, step: Step? = null): Boolean = who(task, step) == "office" || who(task, step) == "others"

    fun setWho(board: Board, taskId: String, who: String): Board = BoardOps.updateTask(board, taskId) { it.copy(who = who) }
}
