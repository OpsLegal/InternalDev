package com.opslegal.tda.core.model

import kotlinx.serialization.Serializable

/** Number of task cells per day. The whole method is built around five. */
const val SLOTS_PER_DAY = 5

@Serializable
enum class Priority(val weight: Int) {
    LOW(1),
    NORMAL(2),
    HIGH(3),
    CRITICAL(4),
}

/** What a cell is, shown by its text colour: task (blue), meeting (black), deadline or delivery (red). */
@Serializable
enum class TaskKind { TASK, MEETING, DEADLINE }

/** How heavy a task feels to this user. Heavy work is where procrastination starts. */
@Serializable
enum class Effort { LIGHT, NORMAL, HEAVY }

/**
 * Something that matters to the user (Brand, Money, Family...). Its [weight] (1 to 3) is how
 * much it counts when the planner and the assistant choose between tasks.
 */
@Serializable
data class Value(
    val name: String,
    val weight: Int = 2,
    /** In the user's words: why it matters, what hurts it. */
    val meaning: String = "",
    /** Cells per week the user wants for it, e.g. 2 for family they tend to neglect. Null = no minimum. */
    val minPerWeek: Int? = null,
    /** Ground, Build or Nourish ("ground", "build", "nourish"), when that feature is on; empty otherwise. */
    val bucket: String = "",
)

/** What the assistant knows about the person, built up over time rather than asked up front. */
@Serializable
data class AboutMe(
    /** The starter profile picked (business, lawyer, inhouse), "none" when skipped, null before the choice. */
    val profile: String? = null,
    /** Kinds of work that come easily or that they enjoy, e.g. "repairs, cars". Lighter for them. */
    val easy: List<String> = emptyList(),
    /** Kinds of work they tend to put off, e.g. "long reading". Heavier for them. */
    val hard: List<String> = emptyList(),
    /** A few lines in their own words (work, family, what they put off): the assistant proposes what matters from it. */
    val bio: String = "",
    /** The first-launch welcome (5 cells, the mic, the bell) was read. */
    val welcomed: Boolean = false,
)

/** When people can book a meeting with the user. */
@Serializable
data class MeetingSettings(
    /** ISO day-of-week numbers (1 = Monday). */
    val days: List<Int> = listOf(1, 2, 3, 4, 5),
    /** Time windows, e.g. "09:00-12:00". */
    val windows: List<String> = listOf("09:00-12:00", "14:00-17:00"),
    val durationMinutes: Int = 60,
    val maxPerDay: Int = 2,
    val bufferMinutes: Int = 15,
)

/**
 * A matter or project. Priority and deadline live here, not on single tasks: every task on the
 * table is important. Tasks of the project take its priority and never end after its deadline.
 */
@Serializable
data class Project(
    val name: String,
    val priority: Priority = Priority.NORMAL,
    /** ISO date. */
    val deadline: String? = null,
    val notes: String = "",
    /** Names of the [Value]s this project serves. Its tasks count for them too. */
    val values: List<String> = emptyList(),
    /** Names of projects that can't finish until this one is done: this one takes their importance. */
    val blocks: List<String> = emptyList(),
    /** Why the user wants it, in one sentence. Its steps share it. */
    val intention: String = "",
    /** What it serves: a level 1-3 per attribute (Ground · Build · Nourish feature). Its steps share it. */
    val serve: Map<String, Int> = emptyMap(),
    /** What the assistant should know (dismissed messages worth keeping, "noted" items): "YYYY-MM-DD · what · comment", newest last. */
    val history: List<String> = emptyList(),
    /** The [Area] its steps are planned in ("" = Work). */
    val area: String = "",
)

/**
 * Something the user wants done. A task is made of one or more [Step]s; each step
 * fills exactly one cell of the table (one focused block of a few hours).
 */
@Serializable
data class Task(
    val id: String,
    val title: String,
    /**
     * The explanation behind the title: what it is, why, any context. The cell only shows the
     * title (which can stay discreet); the assistant always reads this.
     */
    val description: String = "",
    /** Free-form grouping, e.g. "Personal", "Refinancing", "OpsLegal". */
    val project: String = "",
    val kind: TaskKind = TaskKind.TASK,
    val effort: Effort = Effort.NORMAL,
    /** The user chose the effort themselves: don't change it automatically. */
    val effortByUser: Boolean = false,
    /** How many times a cell of this task was pushed. Repeated pushes mean it feels heavy. */
    val pushes: Int = 0,
    /** Names of the [Value]s this task serves (on top of its project's). */
    val values: List<String> = emptyList(),
    val priority: Priority = Priority.NORMAL,
    /** Hard deadline (ISO date). The last step must be scheduled on or before it. */
    val deadline: String? = null,
    /** For meetings and appointments: the only day this task can happen (ISO date). */
    val fixedDate: String? = null,
    /** Ids of tasks that cannot finish until this one is done. */
    val blocks: List<String> = emptyList(),
    /** Why delaying this hurts (money, other projects...). Shown to the assistant. */
    val impactNote: String = "",
    /** Minimum number of days between two steps of this task. 1 = next day. */
    val minDaysBetweenSteps: Int = 1,
    val steps: List<Step> = emptyList(),
    val createdAt: String = "",
    /** One Errands cell: a trip for the things on the To buy list; done ticks them all. */
    val errands: Boolean = false,
    /** Personal life, not work: it may land on a weekend. Work stays on work days unless a deadline needs more. */
    val personal: Boolean = false,
    /** The [Area] it is planned in ("" = its project's, else Personal or Work from [personal]). */
    val area: String = "",
    /** Why it matters to the user, in one sentence (a project's steps use the project's). */
    val intention: String = "",
    /** What it serves: a level 1-3 per attribute, as many as apply (Ground · Build · Nourish feature). */
    val serve: Map<String, Int> = emptyMap(),
    /** Where it happens, for a physical task (a store, an address, an area, "Home"); empty otherwise. */
    val where: String = "",
) {
    /** Nothing left to do: every step is done, pushed (and replaced) or cancelled. */
    val isDone: Boolean get() = steps.isNotEmpty() && steps.all { it.closed }

    /** Holds a project's ordered steps (two levels: a task is one cell, a project is a list of steps). */
    val isProject: Boolean get() = project.isNotEmpty()

    fun kindOf(step: Step): TaskKind = step.kind ?: kind
    fun effortOf(step: Step): Effort = step.effort ?: effort
    fun fixedDateOf(step: Step): String? = step.fixedDate ?: fixedDate
}

/** How a cell ended without being done. Both show grey in the table. */
@Serializable
enum class Outcome {
    /** Moved to a later day; a new step was created for it. */
    PUSHED,

    /** Will not be done. */
    CANCELLED,

    /** Its project is parked for later: off the table, waiting with the ideas. */
    PARKED,

    /**
     * Not done on its day: the record stays there (red) when the work goes to another day, so the table and the
     * weekly numbers show what really happened. Only the user moves the work on; nothing removes the record.
     */
    MISSED,
}

@Serializable
data class Step(
    val id: String,
    val title: String,
    val done: Boolean = false,
    /** ISO date of the day this step is scheduled on, null when not yet placed. */
    val date: String? = null,
    /** Column 0..4 in the day row. */
    val slot: Int? = null,
    /** Set when the user pinned the step to a day; the planner will not move it. */
    val pinned: Boolean = false,
    /** Pushed or cancelled (grey). Null while the step is still to do or done. */
    val outcome: Outcome? = null,
    /** The planner will not place this step before this ISO date (used when a cell is pushed). */
    val notBefore: String? = null,
    /** In a project, a step can be a meeting or a deadline of its own; null = the task's. */
    val kind: TaskKind? = null,
    /** Null = the task's effort. */
    val effort: Effort? = null,
    /** The only day this step can happen (a meeting in a project); null = the task's. */
    val fixedDate: String? = null,
    /** What this step is about, for project steps; one-cell tasks use the task's description. */
    val description: String = "",
    /** Values this step serves, on top of its task's and project's. */
    val values: List<String> = emptyList(),
    /** Quick things done during this cell (same place or activity, no preparation): no cell of their own. */
    val riders: List<Rider> = emptyList(),
    /** Work days this step waits for someone else after the step before it (e.g. a client's feedback). */
    val waitDays: Int = 0,
    /** Who or what it waits for, in a few words ("ACT's feedback"). */
    val waitFor: String = "",
    /** The assistant added it (project-management basics that were missing): shown as "added". */
    val added: Boolean = false,
) {
    /** Done, pushed or cancelled: nothing more to do in this cell. */
    val closed: Boolean get() = done || outcome != null
}

/**
 * A rule the assistant must follow. Rules are shown in the app and sent to the AI
 * in [order] (lower = more important). Users can edit, reorder and disable them.
 */
@Serializable
data class AssistantRule(
    val id: String,
    val text: String,
    val order: Int,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
)

@Serializable
data class PlannerSettings(
    /** ISO day-of-week numbers (1 = Monday .. 7 = Sunday) the planner may fill. */
    val workDays: List<Int> = listOf(1, 2, 3, 4, 5),
    /** Days kept free of new work right before a deadline, as a safety margin. */
    val deadlineBufferDays: Int = 1,
    /** How far ahead the planner is allowed to place steps. */
    val horizonDays: Int = 120,
    /** Errands and visits are joined only when their places are at most this far apart. */
    val maxDetourKm: Int = 10,
    /** Days banks, public offices and institutions are open (Monday to Friday in Canada). Calls to them stay on these days. */
    val officeDays: List<Int> = listOf(1, 2, 3, 4, 5),
    /** Public holidays the planner keeps free of work: QC, ON, CA, US, FR, "" = none; "?" = not chosen yet (the phone's region is used). */
    val holidays: String = "?",
    /** The user's own days off (planned vacations, ISO dates): no work is planned on them, and the assistant plans around them. */
    val daysOff: List<String> = emptyList(),
    /** Each area of life and its days (Work Mon–Fri, Buildings Sat–Sun...). Empty = Work on [workDays], Personal every day. */
    val areas: List<Area> = emptyList(),
)

/**
 * An area of life planned on its own days: a freelancer's company A and company B, a Cairo office (Sun–Thu), repairs on
 * weekends. Every project and task belongs to one. [work] = public holidays and days off are free of it.
 */
@Serializable
data class Area(
    val id: String, val name: String, val days: List<Int>, val work: Boolean = true,
    /** Words that point to it (a client, a nickname, file names): "ACT", "Alimentation Couche-Tard". Its name counts too. */
    val words: List<String> = emptyList(),
)

/** Who the assistant is to the user: the name they gave it and its face (one of the drawn faces, or their own photo). */
@Serializable
data class Persona(val name: String = "", val face: Int = 0, val photo: Boolean = false)

/** When the assistant must repeat what it understood and wait for a yes before changing the table. */
@Serializable
enum class ConfirmationPolicy {
    /** Every change, even marking a cell done. */
    ALWAYS,

    /** Only changes that are hard to see or undo: moving, deleting, rescheduling, editing, rules. */
    IMPORTANT,

    /** Act directly. */
    NEVER,
}

/** How the assistant listens, talks and checks its understanding. Editable in Settings. */
@Serializable
data class ConversationSettings(
    val confirmation: ConfirmationPolicy = ConfirmationPolicy.ALWAYS,
    /** Ask one short question when the request is ambiguous instead of guessing. */
    val askWhenUnsure: Boolean = true,
    /** Main language (BCP 47 tag): used when the phone can't tell which language you speak. */
    val voiceLanguage: String = "en-US",
    /** Other languages you speak. You can start any conversation in any of them. */
    val otherLanguages: List<String> = emptyList(),
    /** Read the assistant's answers aloud when you spoke to it. */
    val speakReplies: Boolean = true,
    val speechRate: Float = 1.0f,
    /** Start listening again after every answer, for a hands-free conversation. */
    val handsFree: Boolean = false,
    /** How long you can pause before the assistant considers you finished. */
    val pauseSeconds: Float = 3f,
    /** Wait twice as long when the sentence sounds unfinished ("...and", "...because"). */
    val waitWhenUnfinished: Boolean = true,
    /** Saying one of these at the end hands the turn over immediately. */
    val endPhrases: List<String> = listOf("go ahead", "that's all", "over to you", "vas-y", "c'est tout", "à toi"),
    val yesWords: List<String> = listOf(
        "yes", "yeah", "yep", "ok", "okay", "sure", "correct", "right", "exactly", "do it", "go ahead", "perfect",
        "no problem", "why not",
        "oui", "ouais", "d'accord", "exact", "parfait", "vas-y", "c'est ça", "c'est bon", "pas de problème", "pourquoi pas",
    ),
    val noWords: List<String> = listOf(
        "no", "nope", "not", "wait", "stop", "cancel", "wrong",
        "non", "pas", "attends", "annule", "faux",
    ),
    /** Words that mean "to buy": what follows them goes on the To buy list, not on the table. */
    val buyWords: List<String> = listOf(
        "buy", "shopping list", "to buy", "groceries", "pick up",
        "acheter", "à acheter", "liste d'achat", "liste d'achats", "liste d'épicerie", "épicerie", "rappel d'acheter", "il faut acheter",
    ),
) {
    /** Main language first, then the others. */
    val languages: List<String> get() = (listOf(voiceLanguage) + otherLanguages).distinct()
}

/** Everything the app persists. Serialized as one JSON document. */
@Serializable
data class Board(
    val tasks: List<Task> = emptyList(),
    val projects: List<Project> = emptyList(),
    val values: List<Value> = emptyList(),
    /** Each preset keeps its own adjusted values, so switching presets never loses them (key = profile id). */
    val valueSets: Map<String, List<Value>> = emptyMap(),
    val about: AboutMe = AboutMe(),
    val meetings: MeetingSettings = MeetingSettings(),
    val rules: List<AssistantRule> = emptyList(),
    val settings: PlannerSettings = PlannerSettings(),
    /** The assistant's name and face ("" = not chosen yet: "Assistant"). */
    val persona: Persona = Persona(),
    val conversation: ConversationSettings = ConversationSettings(),
    /** Notes the assistant keeps about the user's habits (its long-term memory). */
    val memory: List<String> = emptyList(),
    /** Things to buy, for home or work. They never take a cell each; a trip is one Errands cell. */
    val buy: List<BuyItem> = emptyList(),
    /** Changes the assistant proposes from the user's channels, waiting for Apply or Dismiss. */
    val updates: List<Update> = emptyList(),
    /** When and what the assistant checks for updates. */
    val checks: UpdateChecks = UpdateChecks(),
    val replies: ReplySettings = ReplySettings(),
    /** "Not needed" lessons, newest first. */
    /** No longer used: put-away cards don't teach the assistant to skip things (kept so older saves still load). */
    val learned: List<Lesson> = emptyList(),
    /** The user's latest replies as sent or saved: the assistant learns how they write (tone, length, greetings), never what to answer. */
    val replyStyle: List<String> = emptyList(),
    /** The Ground · Build · Nourish trial: categories, attribute levels per task, the strip. Off by default. */
    val gbn: Boolean = false,
    /** How the user corrected the levels of a task: the assistant rates similar tasks the same way. */
    val serveLessons: List<ServeLesson> = emptyList(),
    /** Calendar events the user put in their day or left out, by [com.opslegal.tda.core.plan.CalendarCells.key]. */
    val calendarChoices: Map<String, CalendarChoice> = emptyMap(),
    /** Cells per day (ISO date) that calendar events not reviewed yet will likely take: the planner keeps them free. */
    val reserved: Map<String, Int> = emptyMap(),
    /** On my mind: things written down to be organized into the table (never left as a list). */
    val mind: List<MindItem> = emptyList(),
    /** What moved without being done (pushed by the user, or left undone and rolled over): the weekly review's facts. */
    val log: List<LogEntry> = emptyList(),
    /** The last weekly review: its suggestion, and whether the user tried it. */
    val review: ReviewState = ReviewState(),
    /** What the user does regularly to stay balanced: never checked, counted as done in the week's balance. */
    val routines: List<Routine> = emptyList(),
    /** Routines the user would like to start. */
    val routineWishes: List<RoutineWish> = emptyList(),
    /** Suggested routines the user said no to (lower-case titles). */
    val routineNo: List<String> = emptyList(),
    /** Crowded moments the user said went fine ("day:moment"): not asked again. */
    val routineFine: List<String> = emptyList(),
    /** "I am…" tags (Parent, Lawyer…): each attribute weighs the highest of its tags. */
    val tags: List<String> = emptyList(),
    /** The user's own tags: attribute weights per tag. */
    val customTags: Map<String, Map<String, Int>> = emptyMap(),
    /** Weights the user set by hand on the strip: they win over the tags. */
    val adjust: Map<String, Int> = emptyMap(),
    /** Get started: what the user set up (the AI key is checked directly). */
    val setup: SetupState = SetupState(),
    /** Assistant flags the user answered "Not now" today: flag id to ISO date. */
    val notNow: Map<String, String> = emptyMap(),
    /** Why the user said "Not now", for the weekly review. */
    val notNowWhy: List<LogEntry> = emptyList(),
    /** Projects whose dates are set with someone else: never proposed to end sooner. */
    val keepDates: List<String> = emptyList(),
    /** Habits the user chose to work on in a weekly review: what kept happening, why, the fix, and how it went since. */
    val habitFixes: List<HabitFix> = emptyList(),
    val version: Int = 1,
)

/** One habit being corrected: a task that kept moving, the cause the user named, the fix applied, and the outcome. */
@Serializable
data class HabitFix(
    val id: String,
    val taskId: String,
    val title: String,
    /** The cause the user picked (Habits.causes key) or "own" with their words in [words]. */
    val cause: String,
    val words: String = "",
    /** The fix applied, in plain words ("First 30 minutes, first cell Fri 9"). */
    val fix: String,
    /** ISO date it started. */
    val since: String,
    /** Pushes of the task when the fix started: more since means it slipped again. */
    val pushesAtStart: Int = 0,
    /** "" while open; "done" (the task got done), "again" (it slipped again: try another way), "dropped" (let go). */
    val outcome: String = "",
)

/** A moment of the day for a routine, never a clock time. */
@Serializable
enum class Moment(val icon: String, val label: String, val why: String) {
    EARLY("🌄", "Early morning", "before the day starts: time that is yours"),
    MORNING("🌅", "Morning", "starts the day with energy"),
    MIDDAY("☀️", "Midday", "a break that resets the mind"),
    AFTERNOON("🌤️", "Afternoon", "shakes off the afternoon dip"),
    EVENING("🌙", "Evening", "helps you unwind"),
}

/** Something done regularly (sport, a family dinner): [days] are ISO day numbers (1 = Monday). */
@Serializable
data class Routine(val id: String, val title: String, val days: List<Int>, val moment: Moment, val serve: Map<String, Int> = emptyMap())

@Serializable
data class RoutineWish(val id: String, val title: String, val serve: Map<String, Int> = emptyMap())

/** Get started steps the app can't check by itself. */
@Serializable
data class SetupState(
    val messages: Boolean = false,
    val email: Boolean = false,
    val task: Boolean = false,
    val project: Boolean = false,
    val magic: Boolean = false,
    /** Advanced setup steps already seen or done (week, routines, calendar, types, replies, off). */
    val advanced: List<String> = emptyList(),
)

/** One thing to buy. [errand] is the Errands task that holds it, once a trip is planned. */
/** A quick thing riding along with a cell: done during it, ticked off on its own. */
@Serializable
data class Rider(val id: String, val title: String, val description: String = "", val where: String = "", val done: Boolean = false)

@Serializable
data class BuyItem(
    val id: String,
    val text: String,
    val work: Boolean = false,
    /** ISO date, when it is needed by a certain day. */
    val needBy: String? = null,
    val done: Boolean = false,
    val errand: String? = null,
)

/** Something that arrived on the phone (a notification, a message), kept only until the next check. */
@Serializable
data class Incoming(
    val id: String,
    /** "outlook", "gmail", "teams", "whatsapp", "onedrive", "sms"... */
    val source: String,
    val from: String,
    val text: String,
    /** ISO date-time. */
    val at: String,
    /** The email's id in Microsoft Graph, so a reply draft can answer it. */
    val mailId: String = "",
    /** The Beeper chat, so the reply can be sent there after the user's tap. */
    val chatId: String = "",
    /** The conversation since the user's last reply (a few messages before it for context), oldest first. */
    val thread: List<ThreadMessage> = emptyList(),
    /** An email where the user is only in CC (not in To). */
    val cc: Boolean = false,
)

/** One message of a conversation, as the reply assistant reads it. */
@Serializable
data class ThreadMessage(val fromMe: Boolean, val sender: String, val text: String, val time: String)

/**
 * One change the assistant can apply for an update. [type]: "add" (a task, or a step of [project]),
 * "done" (step [step] is done), "move" (step [step] to [date]), "deadline" (of [project], to [date]).
 */
@Serializable
data class UpdateAction(
    val type: String,
    val project: String = "",
    val step: String = "",
    val title: String = "",
    val description: String = "",
    val kind: TaskKind? = null,
    val date: String? = null,
    /** For an add: what the assistant already worked out, so the task form opens filled (no thinking twice). */
    val intention: String = "",
    val serve: Map<String, Int> = emptyMap(),
    val effort: com.opslegal.tda.core.model.Effort? = null,
    val where: String = "",
)

@Serializable
enum class UpdateStatus { NEW, APPLIED, DISMISSED }

/** A proposal from one incoming item: what it says, what the assistant suggests, and the changes. */
@Serializable
data class Update(
    val id: String,
    val source: String,
    val from: String,
    val text: String,
    val summary: String,
    val project: String = "",
    val actions: List<UpdateAction> = emptyList(),
    val urgent: Boolean = false,
    val status: UpdateStatus = UpdateStatus.NEW,
    val createdAt: String = "",
    /** Someone waits for an answer from the user (only flagged when the reply assistant is on). */
    val needsReply: Boolean = false,
    /** For a meeting request: what and when, in plain words ("ACME review, Fri Oct 3 10:00–11:00"). */
    val meeting: String = "",
    /** The email's id in Microsoft Graph, to put the reply draft in the same thread. */
    val mailId: String = "",
    /** The reply was saved as a draft, sent after the user's tap, answered elsewhere, or not needed. */
    val replied: Boolean = false,
    val chatId: String = "",
    val thread: List<ThreadMessage> = emptyList(),
    /** The user is only in CC of this email. */
    val cc: Boolean = false,
    /** ISO date the person needs it by, when they said so: an acknowledgment then promises "before" it. */
    val due: String = "",
    /** How the user put it away: "done" (already handled, by anyone) or "not_now" (this one only; teaches nothing). */
    val handledAs: String = "",
    /** A few words for a one-line row ("Lease file update"); empty for older cards. */
    val title: String = "",
    /** When the message arrived (ISO date-time), to show how long it has waited. */
    val at: String = "",
)

/** What the user taught the assistant with "Not needed": it skips similar items. Undoable in Settings. */
/** A correction of what a task serves, kept so the assistant rates similar tasks the same way. */
@Serializable
data class ServeLesson(val title: String, val words: List<String>, val serve: Map<String, Int>)

@Serializable
data class Lesson(val id: String, val from: String, val source: String, val what: String, val example: String)

/** A cell that moved without being done: [what] is "pushed" (by the user) or "slipped" (left undone, rolled over). */
@Serializable
data class LogEntry(
    val date: String, val slot: Int? = null, val what: String, val title: String,
    val heavy: Boolean = false, val personal: Boolean = false,
    /** Why it moved, in the user's words or a one-tap reason; the weekly review talks about it. "reason" entries only carry this. */
    val why: String = "",
)

/** The weekly review's memory: one suggestion a week, tried or not, checked the next week. */
@Serializable
data class ReviewState(
    /** Monday (ISO date) of the week last reviewed. */
    val week: String = "",
    val suggestion: String = "",
    /** The rule added when the user tapped Try it ("" otherwise). */
    val ruleId: String = "",
    /** Cells that moved without being done in the week the suggestion was made, to see if it helped. */
    val movedBefore: Int = 0,
    /** Suggestions the user said no to: never proposed again. */
    val declined: List<String> = emptyList(),
    /** Monday (ISO date) of the week whose sweep of the last month's open messages already ran. */
    val swept: String = "",
)

/** One thing on the user's mind, waiting to be organized. */
@Serializable
data class MindItem(val id: String, val text: String)

/** The user's choice for one calendar event: in the day (black cells [taskIds]) or left out. */
@Serializable
data class CalendarChoice(val added: Boolean, val taskIds: List<String> = emptyList())

/**
 * The reply assistant: it prepares answers, the user always sends them (Rule 1, built in: no setting can
 * let the app send, accept or decline anything on its own).
 */
@Serializable
data class ReplySettings(
    val on: Boolean = false,
    val email: Boolean = true,
    val messages: Boolean = true,
    val meetings: Boolean = true,
)

@Serializable
data class UpdateChecks(
    val onOpen: Boolean = true,
    val onLeave: Boolean = true,
    /** "HH:mm" times of the day. */
    val times: List<String> = listOf("08:30", "12:30", "16:30"),
    val notifications: Boolean = true,
    val messages: Boolean = true,
    /** Work email through the Microsoft sign-in (read only), when connected. */
    val email: Boolean = true,
    val urgentToday: Boolean = true,
    val urgentBlocks: Boolean = true,
    val urgentKey: Boolean = false,
    val keyContacts: List<String> = emptyList(),
    /** Only urgent updates interrupt; the rest wait quietly for the bell. */
    val focus: Boolean = true,
    /** ISO date-time of the last check. */
    val lastCheck: String? = null,
    /** ISO date-time the user last opened the bell: the review is done until the next review time. */
    val lastReview: String? = null,
)

/** One cell of the table, flattened for display. */
data class Cell(
    val taskId: String,
    val stepId: String,
    val title: String,
    val project: String,
    val done: Boolean,
    val priority: Priority,
    val outcome: Outcome? = null,
    val kind: TaskKind = TaskKind.TASK,
    val effort: Effort = Effort.NORMAL,
    /** The cell is a step of a project (green, with a bar). */
    val inProject: Boolean = false,
    /** Quick things still to do during this cell ("+2" on the cell). */
    val extras: Int = 0,
)

/** One line of the table: a day and its five cells (null = free cell). */
data class DayRow(
    val date: String,
    val label: String,
    val cells: List<Cell?>,
) {
    val filled: Int get() = cells.count { it != null }
    val completed: Int get() = cells.count { it?.done == true }

    /** Cells still waiting to be done (not yellow, not grey). */
    val open: Int get() = cells.count { it != null && !it.done && it.outcome == null }

    /** The "all yellow" day: something was done and nothing is left open (grey cells count as settled). */
    val allDone: Boolean get() = completed > 0 && open == 0
}
