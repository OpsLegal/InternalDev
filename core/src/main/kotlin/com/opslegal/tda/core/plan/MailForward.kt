package com.opslegal.tda.core.plan

/**
 * An email forwarded by hand ("Fwd:", "TR:") comes from the user's own address: the real sender is the
 * "From:" / « De : » line inside. Read it, so ignoring a sender never ignores the user's own forwards.
 */
object MailForward {
    private val FORWARD = Regex("""^\s*(fwd?|tr|wg)\s*:""", RegexOption.IGNORE_CASE)
    private val FROM_LINE = Regex("""(?:^|\n|\s)(?:From|De|Von)\s*:\s*([^\n<]+?)\s*(?:<([^>\s]+)>)?\s*(?:\n|Sent|Date|Envoy|To:|À|$)""", RegexOption.IGNORE_CASE)

    fun isForward(subject: String) = FORWARD.containsMatchIn(subject)

    /** The original sender of a hand-forwarded email, else [from]. */
    fun origin(from: String, subject: String, body: String): String {
        if (!isForward(subject)) return from
        val m = FROM_LINE.find(body) ?: return from
        return m.groupValues[1].trim().trim('"').ifBlank { m.groupValues[2] }.ifBlank { from }
    }
}
