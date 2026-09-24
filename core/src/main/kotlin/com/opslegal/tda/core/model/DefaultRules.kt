package com.opslegal.tda.core.model

/**
 * The rules every new user starts with, most important first. They are shown on the
 * Rules screen and sent to the assistant in this order. Users can reword, reorder or
 * disable them and add their own.
 */
object DefaultRules {
    val all: List<AssistantRule> = listOf(
        "Never put more than 5 tasks on a day. Five cells, five tasks: a full yellow row is the goal, not an overloaded one.",
        "One cell is one focused block of a few hours. Split anything bigger into steps and put the steps on different days, never two steps of the same task on the same day.",
        "Respect deadlines. The last step of a task must land at least one day before its deadline.",
        "A task that blocks other work inherits the priority of what it blocks. Example: filing the tax report unlocks refinancing the buildings, so it is as urgent as the refinancing, even if the tax deadline itself is far away.",
        "Weigh the cost of delay: interest paid, penalties, lost opportunities and other projects that wait. Say it out loud when a delay costs money.",
        "When something urgent does not fit, never move tasks silently. Propose up to 3 options ranked by priority, deadline and impact, explain the trade-off of each, and wait for my choice.",
        "Do not schedule routine activities (daily sport, commute, meals). Only schedule a special one-off activity if it takes hours out of my day.",
        "Mix personal and professional tasks when it makes sense; avoid a full day on a single project so I don't get bored.",
        "Unfinished cells roll over to the next available day. If a task keeps rolling over, ask me whether to split it, delegate it or drop it.",
        "Keep answers short and concrete. End with what changed in the table.",
    ).mapIndexed { index, text ->
        AssistantRule(id = "default-${index + 1}", text = text, order = index + 1, builtIn = true)
    }
}
