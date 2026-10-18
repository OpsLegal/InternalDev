package com.opslegal.tda.core.agent

import com.opslegal.tda.core.model.Board
import com.opslegal.tda.core.model.Canvas
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * "✨ Prepare": the assistant gets a cell ready from the user's own sources only (emails, messages, calendar, the
 * project's notes and documents), in the format that suits that kind of action. Every line names its source; the app
 * drops any line whose source was not read. What is not known stays "[to confirm]". It reads and drafts; it never sends.
 */
object Prepare {
    /** A kind of action and its default format. */
    data class Kind(val key: String, val icon: String, val name: String, val sections: List<String>, val words: List<String>)

    val KINDS = listOf(
        Kind("call", "📞", "Call", listOf("Who and how to reach them", "What I want to get", "Points to cover", "Questions to ask", "To have at hand"),
            listOf("call", "appeler", "rappeler", "téléphoner", "phone", "joindre")),
        Kind("write", "✉️", "Email or letter", listOf("To whom", "What they asked", "Facts to include", "Tone", "Attachments"),
            listOf("email", "courriel", "écrire", "write", "répondre", "reply", "envoyer", "send", "lettre", "letter", "notifier", "notify", "mise en demeure")),
        Kind("meet", "🤝", "Meeting", listOf("Who and when", "Goal", "Agenda", "Decisions needed", "To bring"),
            listOf("meeting", "rencontre", "réunion", "rdv", "rendez-vous", "lunch", "dîner", "visite", "audience", "hearing")),
        Kind("form", "📝", "Form or document", listOf("What it is", "What to fill", "Already known", "Missing", "Deadline and where to send it"),
            listOf("remplir", "fill", "formulaire", "form", "signer", "sign", "déclaration", "rédiger", "draft", "préparer le", "contrat", "contract")),
        Kind("pay", "💳", "Payment", listOf("Who and how much", "Due date", "How to pay", "Proof to keep"),
            listOf("payer", "pay", "facture", "invoice", "virement", "transfer", "rembourser")),
        Kind("review", "🔍", "Review a file", listOf("What it is", "Key points", "Dates and deadlines", "Risks or gaps", "Next step"),
            listOf("réviser", "review", "lire", "read", "vérifier", "check", "analyser", "analyse", "examiner", "intégrer")),
        Kind("errand", "🛒", "Errand", listOf("Where", "What to get", "Opening hours or booking", "To bring"),
            listOf("acheter", "buy", "aller", "go to", "chercher", "pick up", "récupérer", "dépôt", "drop off")),
        Kind("task", "☑️", "Task", listOf("What done looks like", "What I already have", "What is missing", "First step"), emptyList()),
    )

    private fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** The kind of action a cell is, from its words ("Appeler…" → call); a plain task when nothing fits. */
    fun kindOf(title: String): Kind {
        val t = " ${fold(title)} "
        return KINDS.firstOrNull { k -> k.words.any { w -> Regex("(^|[^a-z])${Regex.escape(fold(w))}").containsMatchIn(t) } } ?: KINDS.last()
    }

    /** The format used for this kind: the user's own, else the default. */
    fun canvas(board: Board, kind: Kind): List<String> = board.canvases.firstOrNull { it.kind == kind.key }?.sections?.takeIf { it.isNotEmpty() } ?: kind.sections

    /** Saves the user's format for a kind (sections in order); an empty list goes back to the default. */
    fun setCanvas(board: Board, kind: String, sections: List<String>): Board {
        val clean = sections.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(8)
        val rest = board.canvases.filter { it.kind != kind }
        return board.copy(canvases = if (clean.isEmpty()) rest else rest + Canvas(kind, clean))
    }

    /** One source read for the brief: [id] "S1"…, what it is ("Email from Me Dubé, Oct 12"), its text. */
    data class Source(val id: String, val label: String, val text: String)

    data class Line(val text: String, val sources: List<String>, val toConfirm: Boolean)
    data class Section(val title: String, val lines: List<Line>)
    data class Draft(val to: String, val subject: String, val body: String)
    data class Brief(val kind: Kind, val sections: List<Section>, val missing: List<String>, val draft: Draft?, val sources: List<Source>)

    fun prompt(board: Board, cell: String, project: String, notes: String, kind: Kind, sources: List<Source>): String = buildString {
        val sections = canvas(board, kind)
        appendLine("Help the user get ready for this ${kind.name.lowercase()}: \"$cell\"" + (if (project.isNotBlank()) " (project: $project)" else "") + ".")
        if (notes.isNotBlank()) appendLine("Their notes on it: ${notes.take(600)}")
        appendLine("Use ONLY the sources below. Never invent a name, a number, a date, an amount or a fact. Every line must name the source ids it comes from.")
        appendLine("When something is needed but not in the sources, write it in \"missing\" (the user will confirm it), never guess it.")
        appendLine("Fill these sections, in this order, in the user's language, short lines (max 4 per section): ${sections.joinToString(" | ")}.")
        if (kind.key == "write" || kind.key == "call") appendLine("If an email would help, add a short \"draft\" (only facts from the sources; [to confirm] where something is missing). It is saved as a draft, never sent.")
        appendLine("SOURCES:")
        if (sources.isEmpty()) appendLine("(none found)")
        sources.forEach { appendLine("[${it.id}] ${it.label}: ${it.text.take(900)}") }
        append("Reply with only {\"sections\": [{\"title\": string, \"lines\": [{\"text\": string, \"src\": [string]}]}], \"missing\": [string], " +
            "\"draft\": {\"to\": string, \"subject\": string, \"body\": string} | null}.")
    }

    /**
     * Reads the reply and keeps only what the sources back: a line citing no source that was read is dropped (or kept as
     * "to confirm" when it says so); sections keep the format's order.
     */
    fun parse(reply: String, board: Board, kind: Kind, sources: List<Source>): Brief {
        val ids = sources.map { it.id }.toSet()
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        val root = if (start < 0 || end <= start) null else runCatching { Json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject }.getOrNull()
        fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        val confirm = Regex("""(?i)\[(to confirm|à confirmer|a confirmer)]""")
        val got = runCatching { root!!["sections"]!!.jsonArray }.getOrDefault(emptyList()).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val title = o.s("title") ?: return@mapNotNull null
            val lines = runCatching { o["lines"]!!.jsonArray }.getOrDefault(emptyList()).mapNotNull { l ->
                val lo = l as? JsonObject ?: return@mapNotNull null
                val text = lo.s("text") ?: return@mapNotNull null
                val src = runCatching { lo["src"]!!.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrDefault(emptyList()).filter { it in ids }
                when {
                    src.isNotEmpty() -> Line(text, src.distinct(), confirm.containsMatchIn(text))
                    confirm.containsMatchIn(text) -> Line(text, emptyList(), true)
                    else -> null
                }
            }.take(4)
            Section(title, lines)
        }
        val order = canvas(board, kind)
        val sections = order.map { t -> got.firstOrNull { fold(it.title) == fold(t) } ?: Section(t, emptyList()) } +
            got.filter { g -> order.none { fold(it) == fold(g.title) } && g.lines.isNotEmpty() }
        val missing = runCatching { root!!["missing"]!!.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { s -> s.isNotBlank() } } }.getOrDefault(emptyList()).take(8)
        val draft = runCatching { root!!["draft"]!!.jsonObject }.getOrNull()?.let { d ->
            Draft(d.s("to").orEmpty(), d.s("subject").orEmpty(), d.s("body") ?: return@let null)
        }?.takeIf { sources.isNotEmpty() }
        return Brief(kind, sections, missing, draft, sources)
    }

    /** What is kept in the cell's notes: the sections with their lines, then what is missing. */
    fun asNote(b: Brief): String = buildString {
        append("✨ Prepared (${b.kind.name})")
        b.sections.filter { it.lines.isNotEmpty() }.forEach { s -> append("\n${s.title}: "); append(s.lines.joinToString(" · ") { it.text }) }
        if (b.missing.isNotEmpty()) append("\nTo confirm: ${b.missing.joinToString(" · ")}")
    }

    /** The words to look for in the user's mail and messages: names and meaningful words of the cell and its project. */
    fun searchWords(cell: String, project: String): List<String> {
        val stop = setOf("appeler", "rappeler", "envoyer", "faire", "pour", "avec", "dans", "les", "des", "une", "the", "and", "call", "send", "with", "from", "about", "prepare", "préparer", "review", "réviser")
        return (cell + " " + project).split(Regex("[^\\p{L}0-9'-]+")).map { it.trim('\'', '-') }.filter { it.length >= 4 && fold(it) !in stop.map(::fold) }.distinct().take(5)
    }
}
