package com.opslegal.tda.core.agent

/** One email, as the assistant sees it: who, subject, first lines. */
data class MailItem(
    val id: String,
    val from: String,
    val subject: String,
    val preview: String,
    /** ISO date-time the email arrived. */
    val received: String,
    /** The user is in CC, not in To. */
    val cc: Boolean = false,
)

/**
 * The user's work email (Microsoft 365 or Outlook.com), read only: the app can never send, move or delete email.
 */
interface MailSource {
    /** Emails in the inbox since [sinceIso] (newest first). */
    suspend fun recent(sinceIso: String?, limit: Int): List<MailItem>

    /** Emails anywhere in the mailbox matching words (a name, a file number, "invoice"...). */
    suspend fun search(query: String, limit: Int): List<MailItem>
}
