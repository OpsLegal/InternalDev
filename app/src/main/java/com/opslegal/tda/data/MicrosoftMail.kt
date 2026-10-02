package com.opslegal.tda.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.opslegal.tda.core.agent.MailItem
import com.opslegal.tda.core.agent.MailSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Work email through Microsoft Graph: read (Mail.Read), and, only once the user turns on the reply assistant,
 * drafts (Mail.ReadWrite). Mail.Send is never requested, so the app cannot send even by mistake (Rule 1). The sign-in happens in the browser on this phone
 * (OAuth with PKCE, no client secret); only the refresh token is kept, encrypted with the Android Keystore.
 * Nothing goes through a server of ours, and the user can revoke access anytime from their Microsoft account.
 */
class MicrosoftMail(context: Context) : MailSource {
    private val prefs = context.getSharedPreferences("microsoft", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var accessToken: String? = null
    private var expiresAt = 0L

    private val accountState = MutableStateFlow(prefs.getString("account", null)?.takeIf { prefs.contains("rt") })

    /** The signed-in address, or null. */
    val account: StateFlow<String?> = accountState.asStateFlow()

    val connected: Boolean get() = accountState.value != null

    /** The user allowed drafts (Mail.ReadWrite) at their last sign-in. */
    val canDraft: Boolean get() = connected && prefs.getBoolean("drafts", false)

    private val scopes: String get() = if (prefs.getBoolean("drafts", false)) SCOPES_DRAFTS else SCOPES

    /** Opens Microsoft's sign-in page in the browser; it comes back to [finishSignIn]. */
    fun signInIntent(drafts: Boolean = canDraft): Intent {
        val verifier = random(48)
        val state = random(16)
        prefs.edit().putString("verifier", verifier).putString("state", state).putBoolean("wantDrafts", drafts).apply()
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val url = AUTHORIZE.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", REDIRECT)
            .addQueryParameter("response_mode", "query")
            .addQueryParameter("scope", if (drafts) SCOPES_DRAFTS else SCOPES)
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("prompt", "select_account")
            .build()
        return Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun isRedirect(uri: Uri?): Boolean = uri != null && uri.scheme == "com.opslegal.tda" && uri.host == "auth"

    /** Handles the browser's return. Returns the signed-in address, or throws with a plain-words reason. */
    suspend fun finishSignIn(uri: Uri): String {
        lock.withLock { exchangeCode(uri) }
        // Outside the lock: reading the address takes it again to get a fresh token.
        val me = get("https://graph.microsoft.com/v1.0/me?\$select=mail,userPrincipalName")
        val address = me.str("mail").ifBlank { me.str("userPrincipalName") }.ifBlank { "Microsoft account" }
        prefs.edit().putString("account", address).apply()
        accountState.value = address
        return address
    }

    private suspend fun exchangeCode(uri: Uri) {
        val expected = prefs.getString("state", null)
        val verifier = prefs.getString("verifier", null)
        val drafts = prefs.getBoolean("wantDrafts", false)
        prefs.edit().remove("state").remove("verifier").remove("wantDrafts").apply()
        uri.getQueryParameter("error")?.let { error(signInError(it, uri.getQueryParameter("error_description"))) }
        val code = uri.getQueryParameter("code") ?: error("Microsoft didn't send a sign-in code. Try again.")
        if (expected == null || verifier == null || uri.getQueryParameter("state") != expected) error("This sign-in is out of date. Try again.")
        token(
            FormBody.Builder().add("client_id", CLIENT_ID).add("grant_type", "authorization_code").add("code", code)
                .add("redirect_uri", REDIRECT).add("code_verifier", verifier).add("scope", if (drafts) SCOPES_DRAFTS else SCOPES).build(),
        )
        prefs.edit().putBoolean("drafts", drafts).apply()
    }

    fun disconnect() {
        prefs.edit().clear().apply()
        accessToken = null
        accountState.value = null
    }

    override suspend fun recent(sinceIso: String?, limit: Int): List<MailItem> {
        val url = "$GRAPH/me/mailFolders/inbox/messages".toHttpUrl().newBuilder()
            .addQueryParameter("\$select", SELECT)
            .addQueryParameter("\$orderby", "receivedDateTime desc")
            .addQueryParameter("\$top", limit.coerceIn(1, 50).toString())
            .apply { utc(sinceIso)?.let { addQueryParameter("\$filter", "receivedDateTime ge $it") } }
            .build()
        return items(get(url.toString()))
    }

    override suspend fun search(query: String, limit: Int): List<MailItem> {
        val url = "$GRAPH/me/messages".toHttpUrl().newBuilder()
            .addQueryParameter("\$search", "\"${query.replace("\"", " ").trim()}\"")
            .addQueryParameter("\$select", SELECT)
            .addQueryParameter("\$top", limit.coerceIn(1, 50).toString())
            .build()
        return items(get(url.toString()))
    }

    /**
     * Saves [text] as a reply draft in the user's Outlook Drafts: in the thread of [mailId], or of the latest
     * email from [from] when the id is unknown, else as a new draft. It is never sent: the user sends it from Outlook.
     */
    suspend fun saveReplyDraft(mailId: String, from: String, text: String) {
        val id = mailId.ifBlank { runCatching { search(from, 1).firstOrNull()?.id }.getOrNull().orEmpty() }
        if (id.isNotBlank()) {
            post("$GRAPH/me/messages/$id/createReply", buildJsonObject { put("comment", text) })
        } else {
            newDraft("Re: ${from.take(60)}", text)
        }
    }

    /** A new draft with no recipient yet (the setup's test draft, or a reply whose email can't be found). */
    suspend fun newDraft(subject: String, text: String) {
        post("$GRAPH/me/messages", buildJsonObject {
            put("subject", subject)
            put("body", buildJsonObject { put("contentType", "Text"); put("content", text) })
        })
    }

    private suspend fun post(url: String, body: JsonObject) {
        if (!canDraft) error("Outlook drafts are not allowed yet. Set up “Your assistant for replies” in Settings.")
        val token = lock.withLock { freshToken() }
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(request).execute().use { r ->
                when {
                    r.isSuccessful -> Unit
                    r.code == 401 || r.code == 403 -> error("Microsoft didn't allow Docket 5 to write the draft. Set up “Your assistant for replies” again in Settings.")
                    r.code == 404 -> error("That email is no longer in your mailbox.")
                    else -> error("Outlook didn't save the draft (${r.code}). Try again later.")
                }
            }
        }
    }

    private fun items(root: JsonObject): List<MailItem> = (root["value"] as? JsonArray).orEmpty().mapNotNull { el ->
        val m = el as? JsonObject ?: return@mapNotNull null
        val from = (m["from"] as? JsonObject)?.get("emailAddress") as? JsonObject
        MailItem(
            id = m.str("id"),
            from = from?.str("name")?.ifBlank { null } ?: from?.str("address").orEmpty(),
            subject = m.str("subject"),
            preview = m.str("bodyPreview").take(600),
            received = m.str("receivedDateTime"),
        )
    }

    private suspend fun get(url: String): JsonObject {
        val token = lock.withLock { freshToken() }
        return withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.isSuccessful -> json.parseToJsonElement(body).jsonObject
                    r.code == 401 || r.code == 403 -> error("Microsoft refused access to the mailbox. Sign in again in Settings.")
                    else -> error("Microsoft email didn't answer (${r.code}). Try again later.")
                }
            }
        }
    }

    /** An access token, refreshed with the saved refresh token when it is about to expire. Call under [lock]. */
    private suspend fun freshToken(): String {
        accessToken?.takeIf { System.currentTimeMillis() < expiresAt - 60_000 }?.let { return it }
        val refresh = prefs.getString("rt", null)?.let { KeystoreCipher.decrypt(it) }
            ?: error("Work email is not connected. Sign in to Microsoft in Settings.")
        token(FormBody.Builder().add("client_id", CLIENT_ID).add("grant_type", "refresh_token").add("refresh_token", refresh).add("scope", scopes).build())
        return accessToken!!
    }

    private suspend fun token(form: FormBody) = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(TOKEN).post(form).build()).execute().use { r ->
            val o = runCatching { json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject }.getOrNull()
                ?: error("Microsoft sign-in didn't answer. Check the connection and try again.")
            if (!r.isSuccessful) {
                if (o.str("error") == "invalid_grant") { disconnect(); error("The Microsoft sign-in expired or was revoked. Sign in again in Settings.") }
                error(signInError(o.str("error"), o.str("error_description")))
            }
            accessToken = o.str("access_token")
            expiresAt = System.currentTimeMillis() + (o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L) * 1000
            // Microsoft sends a new refresh token each time: keep the latest, encrypted.
            o.str("refresh_token").takeIf { it.isNotEmpty() }?.let { prefs.edit().putString("rt", KeystoreCipher.encrypt(it)).apply() }
        }
    }

    private fun signInError(code: String, description: String?): String = when {
        code == "access_denied" -> "The sign-in was cancelled."
        description.orEmpty().contains("AADSTS50194") ->
            "The Docket 5 registration in Microsoft Entra only accepts one organization. In Entra, set its supported account types to any organization and personal accounts."
        description.orEmpty().contains("AADSTS65001") || description.orEmpty().contains("consent", ignoreCase = true) ->
            "Your organization must approve Docket 5 first: ask your Microsoft 365 administrator to allow it (read email and write drafts, never send)."
        else -> "Microsoft sign-in failed" + (description?.lineSequence()?.firstOrNull()?.let { ": $it" } ?: ".")
    }

    private fun JsonObject.str(key: String): String = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

    companion object {
        /** The Docket 5 app registration in Microsoft Entra (public: a mobile app has no secret). */
        const val CLIENT_ID = "b31336e5-2986-4f7f-b16d-90b6841b8180"
        const val REDIRECT = "com.opslegal.tda://auth"
        const val SCOPES = "offline_access User.Read Mail.Read"

        /** With the reply assistant: drafts too. Never Mail.Send. */
        const val SCOPES_DRAFTS = "offline_access User.Read Mail.ReadWrite"
        private const val AUTHORIZE = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
        private const val TOKEN = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
        private const val GRAPH = "https://graph.microsoft.com/v1.0"
        private const val SELECT = "id,from,subject,bodyPreview,receivedDateTime"

        private fun random(bytes: Int): String = b64(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

        private fun b64(data: ByteArray): String = Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        /** A local ISO date-time as Graph's UTC form (2026-09-30T12:00:00Z). */
        private fun utc(local: String?): String? = local?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            ?.atZone(ZoneId.systemDefault())?.withZoneSameInstant(ZoneOffset.UTC)
            ?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))
    }
}
