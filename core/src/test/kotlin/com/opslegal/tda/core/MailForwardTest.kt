package com.opslegal.tda.core

import com.opslegal.tda.core.plan.MailForward
import kotlin.test.Test
import kotlin.test.assertEquals

class MailForwardTest {
    @Test fun handForwardedGmailKeepsTheRealSender() {
        val body = "---------- Forwarded message ---------\nFrom: Hydro-Québec <noreply@hydroquebec.com>\nDate: Wed, Oct 14, 2026\nSubject: Your bill"
        assertEquals("Hydro-Québec", MailForward.origin("Mohamed D", "Fwd: Your bill", body))
    }

    @Test fun frenchOutlookForward() {
        val body = "De : Interac <notify@payments.interac.ca>\nEnvoyé : 14 octobre 2026\nÀ : moi"
        assertEquals("Interac", MailForward.origin("Mohamed D", "TR: Virement Interac", body))
    }

    @Test fun anOrdinaryEmailIsUnchanged() {
        assertEquals("Rachel", MailForward.origin("Rachel", "Lunch?", "From: someone else"))
    }
}
