package com.opslegal.tda.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.opslegal.tda.core.agent.AnthropicProvider
import com.opslegal.tda.core.agent.LlmProvider
import com.opslegal.tda.core.agent.OpenAiProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ProviderKind(val label: String, val defaultModel: String) {
    ANTHROPIC("Anthropic (Claude)", AnthropicProvider.DEFAULT_MODEL),
    OPENAI("OpenAI", OpenAiProvider.DEFAULT_MODEL),
    COMPATIBLE("Other (OpenAI-compatible)", ""),
}

data class AppSettings(
    val provider: ProviderKind = ProviderKind.ANTHROPIC,
    val model: String = ProviderKind.ANTHROPIC.defaultModel,
    val baseUrl: String = "",
    val hasApiKey: Boolean = false,
    /** "en" or "fr": letters used for the day column. */
    val dayLanguage: String = "en",
    /** Let the assistant review tomorrow's line every evening (uses the user's tokens). */
    val dailyAiReview: Boolean = false,
    /** Let the assistant read the phone's calendars (read only). */
    val calendarAccess: Boolean = false,
    /** Let the assistant read chats and messages through Beeper (read only). */
    val messagesAccess: Boolean = false,
)

/**
 * User preferences. The AI key is encrypted with a key that never leaves the
 * Android Keystore; it is only ever sent to the provider the user chose.
 */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val settings: StateFlow<AppSettings> = state.asStateFlow()

    private fun read(): AppSettings {
        val provider = runCatching { ProviderKind.valueOf(prefs.getString("provider", null)!!) }.getOrDefault(ProviderKind.ANTHROPIC)
        return AppSettings(
            provider = provider,
            model = prefs.getString("model", null) ?: provider.defaultModel,
            baseUrl = prefs.getString("baseUrl", "")!!,
            hasApiKey = prefs.contains("apiKey"),
            dayLanguage = prefs.getString("dayLanguage", "en")!!,
            dailyAiReview = prefs.getBoolean("dailyAiReview", false),
            calendarAccess = prefs.getBoolean("calendarAccess", false),
            messagesAccess = prefs.getBoolean("messagesAccess", false),
        )
    }

    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(state.value)
        prefs.edit()
            .putString("provider", next.provider.name)
            .putString("model", next.model)
            .putString("baseUrl", next.baseUrl)
            .putString("dayLanguage", next.dayLanguage)
            .putBoolean("dailyAiReview", next.dailyAiReview)
            .putBoolean("calendarAccess", next.calendarAccess)
            .putBoolean("messagesAccess", next.messagesAccess)
            .apply()
        state.value = read()
    }

    fun setApiKey(key: String?) {
        if (key.isNullOrBlank()) prefs.edit().remove("apiKey").apply()
        else prefs.edit().putString("apiKey", KeystoreCipher.encrypt(key.trim())).apply()
        state.value = read()
    }

    private fun apiKey(): String? = prefs.getString("apiKey", null)?.let { KeystoreCipher.decrypt(it) }

    /** The provider to talk to, or null until the user connected an AI account. */
    fun provider(): LlmProvider? {
        val key = apiKey() ?: return null
        val s = state.value
        val http = OkHttpTransport.shared
        val model = s.model.ifBlank { s.provider.defaultModel }
        return when (s.provider) {
            ProviderKind.ANTHROPIC -> AnthropicProvider(key, model, http)
            ProviderKind.OPENAI -> OpenAiProvider(key, model, http)
            ProviderKind.COMPATIBLE -> {
                if (s.baseUrl.isBlank() || model.isBlank()) return null
                OpenAiProvider(key, model, http, baseUrl = s.baseUrl, id = "compatible")
            }
        }
    }
}

private object KeystoreCipher {
    private const val ALIAS = "tda_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(plain.toByteArray())
        return b64(cipher.iv) + ":" + b64(data)
    }

    fun decrypt(stored: String): String? = runCatching {
        val (iv, data) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(data))
    }.getOrNull()

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
