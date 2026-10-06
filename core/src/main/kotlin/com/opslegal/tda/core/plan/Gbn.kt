package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.ServeLesson
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.model.Value

/**
 * Ground · Build · Nourish (a trial the user turns on in Settings). Every attribute sits in one category: Ground keeps
 * life running, Build creates value that turns into money, Nourish gives energy. Each task says, per attribute, how
 * much it serves it (0-3, as many as apply); the assistant proposes it and learns from the user's corrections.
 */
object Gbn {
    const val GROUND = "ground"
    const val BUILD = "build"
    const val NOURISH = "nourish"
    val buckets = listOf(GROUND, BUILD, NOURISH)
    val names = mapOf(GROUND to "Ground", BUILD to "Build", NOURISH to "Nourish")

    private fun v(name: String, bucket: String, meaning: String, weight: Int = 2, min: Int? = null) = Value(name, weight, meaning, min, bucket)
    private val home = v("Home", GROUND, "House, car, repairs, utilities, errands.")
    private val admin = v("Admin", GROUND, "Paperwork, appointments, forms, obligations.")
    private val career = v("Career", BUILD, "Clients, cases, projects, reputation: the work that moves you forward.")
    private val money = v("Money", BUILD, "Cash coming in: fees, salary, sales, collections.")
    private val invest = v("Invest", BUILD, "What grows in value: savings, property, a business, a brand.")
    private val learning = v("Learning", BUILD, "Training and continuing education for my career.")
    private val relations = v("Relations", NOURISH, "Family, friends, the people who matter.")
    private val health = v("Health", NOURISH, "Body, sleep, sport, care, a clear mind.")
    private val joy = v("Joy", NOURISH, "Pleasure, hobbies, rest.")

    private fun set(vararg pairs: Pair<Value, Int>, mins: Map<String, Int> = emptyMap()) =
        pairs.map { (a, w) -> a.copy(weight = w, minPerWeek = mins[a.name]) }

    /** Profiles are presets over the same attributes: only the weights change (a career profile adds Learning). */
    val profiles: Map<String, Pair<String, List<Value>>> = linkedMapOf(
        "gbn-neutral" to ("Neutral" to set(home to 2, admin to 2, career to 2, money to 2, invest to 2, relations to 2, health to 2, joy to 2)),
        "gbn-lawyer" to ("Lawyer in practice" to set(home to 1, admin to 2, career to 3, money to 3, invest to 1, learning to 2, relations to 2, health to 2, joy to 1,
            mins = mapOf("Relations" to 2, "Health" to 2))),
        "gbn-founder" to ("Founder" to set(home to 1, admin to 1, career to 3, money to 2, invest to 3, relations to 2, health to 2, joy to 1, mins = mapOf("Relations" to 2))),
        "gbn-parent" to ("Parent and pro" to set(home to 2, admin to 2, career to 2, money to 2, invest to 1, relations to 3, health to 2, joy to 2,
            mins = mapOf("Relations" to 4, "Health" to 2))),
        "gbn-employee" to ("Employee" to set(home to 1, admin to 1, career to 3, money to 2, invest to 1, learning to 2, relations to 2, health to 2, joy to 2, mins = mapOf("Relations" to 2))),
        "gbn-work" to ("Work only" to set(home to 1, admin to 2, career to 3, money to 3, invest to 2, relations to 1, health to 1, joy to 1)),
    )

    /** At most this many attributes, so the strip stays readable on a phone. */
    const val MAX_ATTRIBUTES = 9

    /** Turning the trial on: values without a category are kept aside and the Neutral attributes take their place. */
    fun turnOn(board: Board): Board {
        if (board.values.isNotEmpty() && board.values.all { it.bucket in buckets }) return board.copy(gbn = true)
        val keep = if (board.values.isNotEmpty()) board.valueSets + ("before-gbn" to board.values) else board.valueSets
        return board.copy(gbn = true, values = profiles.getValue("gbn-neutral").second, valueSets = keep, about = board.about.copy(profile = "gbn-neutral"))
    }

    /** Turning it off: the values in use before come back (the attributes are kept for next time). */
    fun turnOff(board: Board): Board {
        val before = board.valueSets["before-gbn"] ?: return board.copy(gbn = false)
        return board.copy(gbn = false, values = before, valueSets = board.valueSets - "before-gbn" + ("gbn-last" to board.values), about = board.about.copy(profile = "custom"))
    }

    /** What a task serves: its own levels, else its project's, else a lesson from a similar task. Empty = not rated yet. */
    fun levelsOf(board: Board, task: Task): Map<String, Int> {
        if (task.serve.isNotEmpty()) return task.serve
        BoardOps.findProject(board, task.project)?.serve?.takeIf { it.isNotEmpty() }?.let { return it }
        return lessonFor(board, task.title)?.serve.orEmpty()
    }

    /** For one task: the share of what it serves in each category, in percent (0 when none). */
    fun share(board: Board, levels: Map<String, Int>): Map<String, Int> {
        val totals = buckets.associateWith { b -> levels.entries.sumOf { (k, l) -> if (board.values.any { it.name == k && it.bucket == b }) l else 0 } }
        val sum = totals.values.sum().takeIf { it > 0 } ?: return buckets.associateWith { 0 }
        return totals.mapValues { it.value * 100 / sum }
    }

    /** For the user's profile: how much each category counts (sum of weights), in percent. */
    fun profileShare(board: Board): Map<String, Int> {
        val totals = buckets.associateWith { b -> board.values.filter { it.bucket == b }.sumOf { it.weight } }
        val sum = totals.values.sum().takeIf { it > 0 } ?: return buckets.associateWith { 0 }
        return totals.mapValues { it.value * 100 / sum }
    }

    /** Value for the user, 0-3: what the task serves times how much each attribute counts for them. */
    fun valueFor(board: Board, levels: Map<String, Int>): Int {
        val score = levels.entries.sumOf { (k, l) -> l * (board.values.firstOrNull { it.name == k }?.weight ?: 0) }
        return when { score >= 10 -> 3; score >= 5 -> 2; score > 0 -> 1; else -> 0 }
    }

    private val STOP = setOf("with", "from", "about", "pour", "avec", "dans", "chez", "this", "that", "into", "over", "after", "before", "their", "your", "mine")
    private fun words(title: String) = title.lowercase().split(Regex("[^\\p{L}0-9]+")).filter { it.length > 3 && it !in STOP }.take(3)

    /** The user corrected what a task serves: kept (newest first) so similar tasks are rated the same way. */
    fun learn(board: Board, title: String, levels: Map<String, Int>): Board {
        val lesson = ServeLesson(title.trim(), words(title), levels.filterValues { it > 0 })
        return board.copy(serveLessons = (listOf(lesson) + board.serveLessons.filter { it.title != lesson.title }).take(30))
    }

    fun lessonFor(board: Board, text: String): ServeLesson? {
        val t = text.lowercase()
        return board.serveLessons.firstOrNull { l -> l.words.isNotEmpty() && l.words.any { it in t } }
    }

    /** Reads {"Attribute": level} from the AI, keeping only the user's attributes and levels 1-3. */
    fun parseLevels(board: Board, raw: Map<String, String>): Map<String, Int> = raw.mapNotNull { (k, v) ->
        val name = board.values.firstOrNull { it.name.equals(k.trim(), ignoreCase = true) }?.name ?: return@mapNotNull null
        val level = v.trim().toIntOrNull()?.coerceIn(0, 3) ?: return@mapNotNull null
        if (level == 0) null else name to level
    }.toMap()

    /** What the AI needs to rate a task: the attributes by category, and the user's own corrections. */
    fun prompt(board: Board): String = buildString {
        appendLine("WHAT IT SERVES: every task serves one or more of the user's attributes, each at a level 1-3 (3 = clearly, 1 = a little); as many as apply.")
        appendLine("Ground keeps life running; Build creates value that turns into money (now or later); Nourish gives energy.")
        buckets.forEach { b ->
            val list = board.values.filter { it.bucket == b }
            if (list.isNotEmpty()) appendLine("- ${names[b]}: " + list.joinToString("; ") { "${it.name} (${it.meaning})" })
        }
        if (board.serveLessons.isNotEmpty()) {
            appendLine("How the user rated tasks themselves (rate similar tasks the same way):")
            board.serveLessons.take(12).forEach { l -> appendLine("- ${l.title}: " + l.serve.entries.joinToString { "${it.key} ${it.value}" }) }
        }
    }
}
