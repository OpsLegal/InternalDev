package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Task
import com.opslegal.tda.core.model.Value
import java.time.DayOfWeek
import java.time.LocalDate

/** What matters to the user, and how it weighs on planning. */
object Values {

    /** Starter sets, so a new user sees value in one tap and adjusts later. */
    val profiles: Map<String, Pair<String, List<Value>>> = linkedMapOf(
        "business" to ("Business owner" to listOf(
            Value("Brand", 3, "My company's and my own image: missing a date hurts it."),
            Value("Credit", 3, "Bills and invoices paid on time; my credit score and access to credit."),
            Value("Money", 2, "Money coming in, costs going down, avoiding losses."),
            Value("Relationships", 2, "Family and friends. I tend to let them down.", minPerWeek = 2),
            Value("Pleasure", 2, "Regular rewards and fun keep me going.", minPerWeek = 3),
        )),
        "lawyer" to ("Lawyer" to listOf(
            Value("Clients", 3, "Service and responsiveness to my clients."),
            Value("Court deadlines", 3, "Limitation periods, filings and court dates."),
            Value("Billable time", 2, "Hours that are billed and collected."),
            Value("Reputation", 2, "My standing with clients, courts and colleagues."),
            Value("Family", 2, "Time with the people who matter.", minPerWeek = 2),
            Value("Pleasure", 1, "Something I enjoy, so I keep going.", minPerWeek = 2),
        )),
        "inhouse" to ("In-house counsel" to listOf(
            Value("Business risk", 3, "Protecting the company from legal and financial risk."),
            Value("Compliance dates", 3, "Regulatory and contractual dates."),
            Value("Stakeholders", 2, "Internal clients and management relying on me."),
            Value("Team", 2, "My team's growth and workload."),
            Value("Family", 2, "Time with the people who matter.", minPerWeek = 2),
        )),
    )

    /** The values a task serves: its own plus its project's, matched to the user's list. */
    fun of(board: Board, task: Task): List<Value> {
        val names = task.values + (BoardOps.findProject(board, task.project)?.values ?: emptyList())
        return board.values.filter { v -> names.any { it.equals(v.name, ignoreCase = true) } }
    }

    /** Sum of the weights of the values a task serves. Added to its urgency by the planner. */
    fun score(board: Board, task: Task): Int = of(board, task).sumOf { it.weight.coerceIn(1, 3) }

    /** A value with a weekly minimum that the current week doesn't reach yet. */
    data class Gap(val value: Value, val count: Int) {
        val min: Int get() = value.minPerWeek ?: 0
    }

    /**
     * Values below their weekly minimum, counting cells planned or done this week (Monday to
     * Sunday). Grey cells don't count.
     */
    fun gaps(board: Board, today: LocalDate): List<Gap> {
        val monday = today.with(DayOfWeek.MONDAY)
        val sunday = monday.plusDays(6)
        return board.values.filter { (it.minPerWeek ?: 0) > 0 }.map { value ->
            val count = board.tasks.filter { t -> of(board, t).any { it.name == value.name } }.sumOf { t ->
                t.steps.count { s ->
                    s.outcome == null && s.date != null && LocalDate.parse(s.date) in monday..sunday
                }
            }
            Gap(value, count)
        }.filter { it.count < it.min }
    }
}
