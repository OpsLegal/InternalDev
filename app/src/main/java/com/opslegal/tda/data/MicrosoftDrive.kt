package com.opslegal.tda.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
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
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * One OneDrive (work or personal), through Microsoft Graph with its own sign-in on this phone (PKCE, no secret, the
 * refresh token encrypted with the Android Keystore). Reads: search and file names (Files.Read). Writes: only inside
 * its own folder, OneDrive › Apps › Docket 5 (Files.ReadWrite.AppFolder): it can never change or delete the user's files.
 */
class MicrosoftDrive(context: Context, val slot: String, val label: String) {
    private val prefs = context.getSharedPreferences("drive_$slot", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var accessToken: String? = null
    private var expiresAt = 0L
    private val accountState = MutableStateFlow(prefs.getString("account", null)?.takeIf { prefs.contains("rt") })

    val account: StateFlow<String?> = accountState.asStateFlow()
    val connected: Boolean get() = accountState.value != null

    data class FileHit(val id: String, val name: String, val path: String, val modified: String, val url: String, val size: Long)

    fun signInIntent(): Intent {
        val verifier = random(48); val state = "drive_$slot." + random(16)
        prefs.edit().putString("verifier", verifier).putString("state", state).apply()
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val url = AUTHORIZE.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", MicrosoftMail.CLIENT_ID).addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", MicrosoftMail.REDIRECT).addQueryParameter("response_mode", "query")
            .addQueryParameter("scope", SCOPES).addQueryParameter("state", state)
            .addQueryParameter("code_challenge", challenge).addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("prompt", "select_account").build()
        return Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** This browser return is for this drive (its own state), not for the email sign-in. */
    fun owns(uri: Uri?): Boolean = uri != null && uri.getQueryParameter("state")?.let { it == prefs.getString("state", null) } == true

    suspend fun finishSignIn(uri: Uri): String {
        lock.withLock {
            val verifier = prefs.getString("verifier", null)
            prefs.edit().remove("state").remove("verifier").apply()
            uri.getQueryParameter("error")?.let { error(if (it == "access_denied") "The sign-in was cancelled." else "Microsoft sign-in failed: ${uri.getQueryParameter("error_description")?.lineSequence()?.firstOrNull() ?: it}") }
            val code = uri.getQueryParameter("code") ?: error("Microsoft didn't send a sign-in code. Try again.")
            token(FormBody.Builder().add("client_id", MicrosoftMail.CLIENT_ID).add("grant_type", "authorization_code").add("code", code)
                .add("redirect_uri", MicrosoftMail.REDIRECT).add("code_verifier", verifier ?: error("This sign-in is out of date. Try again.")).add("scope", SCOPES).build())
        }
        val me = get("$GRAPH/me?\$select=mail,userPrincipalName")
        val address = me.str("mail").ifBlank { me.str("userPrincipalName") }.ifBlank { "OneDrive" }
        prefs.edit().putString("account", address).apply()
        accountState.value = address
        return address
    }

    fun disconnect() { prefs.edit().clear().apply(); accessToken = null; accountState.value = null }

    /** Files whose name or content matches [query] anywhere in this OneDrive (read only). */
    suspend fun search(query: String, limit: Int = 6): List<FileHit> {
        val q = query.replace("'", "''")
        val r = get("$GRAPH/me/drive/root/search(q='${java.net.URLEncoder.encode(q, "UTF-8").replace("+", "%20")}')?\$select=id,name,webUrl,lastModifiedDateTime,parentReference,file,size&\$top=$limit")
        return (r["value"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o["file"] == null) return@mapNotNull null
            val parent = (o["parentReference"] as? JsonObject)?.str("path").orEmpty().substringAfter("root:", "").trim('/')
            FileHit(o.str("id"), o.str("name"), parent, o.str("lastModifiedDateTime").take(10), o.str("webUrl"), o["size"]?.jsonPrimitive?.longOrNull ?: 0L)
        }
    }

    /** The text of a small text file (.txt, .md, .csv): what the brief can quote. Other files are named, not read. */
    suspend fun smallText(hit: FileHit): String? {
        if (hit.size > 200_000 || !hit.name.substringAfterLast('.', "").lowercase().let { it in setOf("txt", "md", "csv") }) return null
        val token = lock.withLock { freshToken() }
        return withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("$GRAPH/me/drive/items/${hit.id}/content").header("Authorization", "Bearer $token").build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string()?.take(3000) else null
            }
        }
    }

    /** A file this app saved (by its web link), to show it or put it in a report. Null when it is not in this drive. */
    suspend fun download(webUrl: String): ByteArray? {
        if (!connected) return null
        val token = lock.withLock { freshToken() }
        val share = "u!" + android.util.Base64.encodeToString(webUrl.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        return withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("$GRAPH/shares/$share/driveItem/content").header("Authorization", "Bearer $token").build()).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes()?.takeIf { it.size <= 8_000_000 } else null
            }
        }
    }

    /** Saves [bytes] at Apps › Docket 5 › [path] (folders are created as needed). Returns the file's web link. */
    suspend fun save(path: String, bytes: ByteArray, mime: String): String {
        require(bytes.size <= 4_000_000) { "The file is larger than 4 MB." }
        val token = lock.withLock { freshToken() }
        val enc = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$GRAPH/me/drive/special/approot:/$enc:/content?@microsoft.graph.conflictBehavior=rename")
                .header("Authorization", "Bearer $token").put(bytes.toRequestBody(mime.toMediaType())).build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.isSuccessful -> runCatching { json.parseToJsonElement(body).jsonObject.str("webUrl") }.getOrDefault("")
                    r.code == 401 || r.code == 403 -> error("OneDrive refused to save. Connect it again in Settings › Connections.")
                    r.code == 507 -> error("OneDrive is full.")
                    else -> error("OneDrive didn't save the file (${r.code}). Try again later.")
                }
            }
        }
    }

    private suspend fun get(url: String): JsonObject {
        val token = lock.withLock { freshToken() }
        return withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.isSuccessful -> json.parseToJsonElement(body).jsonObject
                    r.code == 401 || r.code == 403 -> error("OneDrive refused access. Connect it again in Settings › Connections.")
                    else -> error("OneDrive didn't answer (${r.code}). Try again later.")
                }
            }
        }
    }

    private suspend fun freshToken(): String {
        accessToken?.takeIf { System.currentTimeMillis() < expiresAt - 60_000 }?.let { return it }
        val refresh = prefs.getString("rt", null)?.let { KeystoreCipher.decrypt(it) } ?: error("OneDrive is not connected. Connect it in Settings › Connections.")
        token(FormBody.Builder().add("client_id", MicrosoftMail.CLIENT_ID).add("grant_type", "refresh_token").add("refresh_token", refresh).add("scope", SCOPES).build())
        return accessToken!!
    }

    private suspend fun token(form: FormBody) = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(TOKEN).post(form).build()).execute().use { r ->
            val o = runCatching { json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject }.getOrNull() ?: error("Microsoft sign-in didn't answer. Try again.")
            if (!r.isSuccessful) {
                if (o.str("error") == "invalid_grant") { disconnect(); error("The OneDrive sign-in expired or was revoked. Connect it again in Settings › Connections.") }
                error(if ((o.str("error_description")).contains("AADSTS65001")) "Your organization must approve file access first: ask your Microsoft 365 administrator." else "Microsoft sign-in failed.")
            }
            accessToken = o.str("access_token")
            expiresAt = System.currentTimeMillis() + (o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L) * 1000
            o.str("refresh_token").takeIf { it.isNotEmpty() }?.let { prefs.edit().putString("rt", KeystoreCipher.encrypt(it)).apply() }
        }
    }

    private fun JsonObject.str(key: String): String = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

    companion object {
        /** Read files, write only in the app's own folder. Never Files.ReadWrite (the whole drive). */
        const val SCOPES = "offline_access User.Read Files.Read Files.ReadWrite.AppFolder"
        private const val AUTHORIZE = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
        private const val TOKEN = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
        private const val GRAPH = "https://graph.microsoft.com/v1.0"
        private fun random(bytes: Int): String = b64(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
        private fun b64(data: ByteArray): String = Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
