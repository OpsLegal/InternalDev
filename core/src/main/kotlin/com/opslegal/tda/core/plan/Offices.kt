package com.opslegal.tda.core.plan

import com.opslegal.tda.core.model.Step
import com.opslegal.tda.core.model.Task

/**
 * Banks, public offices and institutions are open on office days only (Monday to Friday in Canada; another
 * country may differ: Settings › office days). A call or a visit to one never lands on another day.
 */
object Offices {

    /** Words of banks, notaries, insurers, utilities and public services, in English and French. */
    private val WORDS = Regex(
        "(?i)\\b(bank|banque|caisse|desjardins|credit union|notary|notaire|insurer|insurance|assureur|assurance|" +
            "government|gouvernement|minist(?:ry|ère|ere)|city hall|town hall|hôtel de ville|hotel de ville|mairie|municipalit\\w*|" +
            "revenu québec|revenu quebec|revenue canada|canada revenue|cra|service canada|service ontario|serviceontario|saaq|ramq|" +
            "passport|passeport|courthouse|palais de justice|greffe|tribunal|court office|registry|registre foncier|land registry|" +
            "post office|postes canada|canada post|embassy|ambassade|consulate|consulat|hydro-québec|hydro-quebec|hydro one|" +
            "school board|centre de services scolaire|clinic|clinique|hospital|hôpital|cpa|accountant|comptable|public office|bureau)\\b",
    )

    /** The cell needs an office that is open: its words name one (task, step or description). */
    fun needsOfficeDay(task: Task, step: Step): Boolean =
        WORDS.containsMatchIn(listOf(task.title, step.title, task.description, step.description).joinToString(" "))
}
