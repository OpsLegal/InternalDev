package com.opslegal.tda.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.opslegal.tda.core.model.ConversationSettings
import com.opslegal.tda.core.voice.TurnDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed interface VoiceState {
    data object Idle : VoiceState

    /** [heard] is everything understood so far in this turn, [waitingMs] the pause before it ends. */
    data class Listening(val heard: String, val waitingMs: Long?) : VoiceState

    data object Speaking : VoiceState

    data class Error(val message: String) : VoiceState
}

/**
 * Listens like a patient person: Android's recognizer stops at every short pause, so this
 * restarts it and keeps adding to the same turn until you say an end phrase ("go ahead",
 * "c'est tout"), tap the mic, or stay silent for the pause set in Settings.
 * Must be used from the main thread.
 */
class VoiceController(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val stateFlow = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = stateFlow.asStateFlow()

    // --- listening
    private var recognizer: SpeechRecognizer? = null
    private var settings = ConversationSettings()
    private var committed = ""
    private var partial = ""
    private var onTurn: ((String) -> Unit)? = null

    /** True while the silence timer runs; recognizer restarts must not reset it. */
    private var endPending = false
    private val endTurn = Runnable {
        endPending = false
        finish()
    }
    private val giveUp = Runnable { if (listening() && heard().isBlank()) cancel() }
    private val retry = Runnable { if (listening()) startRecognizer() }

    // --- speaking
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var afterSpeech: (() -> Unit)? = null
    private var waitingSpeech: Pair<String, ConversationSettings>? = null
    private var utteranceSeq = 0
    private var currentUtterance: String? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Starts a turn. [onTurn] receives the full text once the person has finished. */
    fun listen(settings: ConversationSettings, onTurn: (String) -> Unit) {
        stopSpeaking()
        if (!isAvailable) {
            stateFlow.value = VoiceState.Error("This phone has no speech recognition. You can type instead.")
            return
        }
        this.settings = settings
        this.onTurn = onTurn
        committed = ""
        partial = ""
        clearTimers()
        stateFlow.value = VoiceState.Listening("", null)
        // Nobody says anything: stop listening after a while.
        main.postDelayed(giveUp, NOTHING_HEARD_MS)
        startRecognizer()
    }

    /** Ends the turn now with what was heard (the "I'm done" tap). */
    fun finish() {
        clearTimers()
        val text = heard().trim()
        val callback = onTurn
        onTurn = null
        stopRecognizer()
        stateFlow.value = VoiceState.Idle
        // Deliver outside the recognizer callback so a new listen() can't re-enter it.
        if (text.isNotEmpty() && callback != null) main.post { callback(text) }
    }

    fun cancel() {
        clearTimers()
        onTurn = null
        stopRecognizer()
        if (stateFlow.value is VoiceState.Listening) stateFlow.value = VoiceState.Idle
    }

    fun speak(text: String, settings: ConversationSettings, then: () -> Unit) {
        val clean = text.replace(Regex("[*_#`>|]"), "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) {
            then()
            return
        }
        afterSpeech = then
        when {
            tts == null -> {
                waitingSpeech = clean to settings
                tts = TextToSpeech(context) { status ->
                    main.post {
                        ttsReady = status == TextToSpeech.SUCCESS
                        if (!ttsReady) {
                            // Try again from scratch next time instead of waiting forever.
                            tts?.shutdown()
                            tts = null
                        }
                        val (t, s) = waitingSpeech ?: return@post
                        waitingSpeech = null
                        if (ttsReady) speakNow(t, s) else done(null)
                    }
                }.apply { setOnUtteranceProgressListener(progress) }
            }
            ttsReady -> speakNow(clean, settings)
            // Engine still starting: it picks this up when ready.
            else -> waitingSpeech = clean to settings
        }
    }

    fun stopSpeaking() {
        afterSpeech = null
        waitingSpeech = null
        currentUtterance = null
        tts?.stop()
        if (stateFlow.value == VoiceState.Speaking) stateFlow.value = VoiceState.Idle
    }

    fun release() {
        stopSpeaking()
        cancel()
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
    }

    private fun speakNow(text: String, settings: ConversationSettings) {
        val engine = tts ?: return done(null)
        engine.language = Locale.forLanguageTag(settings.voiceLanguage)
        engine.setSpeechRate(settings.speechRate)
        val id = "reply-${++utteranceSeq}"
        currentUtterance = id
        stateFlow.value = VoiceState.Speaking
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) done(id)
    }

    /** Speech finished. [id] null means "whatever is current". Stale utterances are ignored. */
    private fun done(id: String?) {
        if (id != null && id != currentUtterance) return
        currentUtterance = null
        if (stateFlow.value == VoiceState.Speaking) stateFlow.value = VoiceState.Idle
        val next = afterSpeech
        afterSpeech = null
        next?.invoke()
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) {
            main.post { done(utteranceId) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            main.post { done(utteranceId) }
        }
    }

    private fun heard(): String = listOf(committed, partial).filter { it.isNotBlank() }.joinToString(" ")

    private fun listening() = onTurn != null

    private fun clearTimers() {
        main.removeCallbacks(endTurn)
        main.removeCallbacks(giveUp)
        main.removeCallbacks(retry)
        endPending = false
    }

    private fun startRecognizer() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.voiceLanguage)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // A hint only; many recognizers ignore it, which is why the turn logic lives here.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        r.startListening(intent)
    }

    private fun stopRecognizer() {
        recognizer?.cancel()
    }

    /** Words arrived: the person is still talking, so no silence timer runs. */
    private fun stillTalking() {
        main.removeCallbacks(endTurn)
        main.removeCallbacks(giveUp)
        endPending = false
    }

    /** Checks for an end phrase; otherwise shows what was heard. Returns false if the turn ended. */
    private fun update(): Boolean {
        val text = heard()
        TurnDetector.stripEndPhrase(text, settings.endPhrases)?.let { rest ->
            committed = rest.ifBlank { text }
            partial = ""
            finish()
            return false
        }
        stateFlow.value = VoiceState.Listening(text, null)
        return true
    }

    /** Starts the silence timer, unless it is already running (recognizer restarts must not reset it). */
    private fun scheduleEnd() {
        if (heard().isBlank()) {
            main.removeCallbacks(giveUp)
            main.postDelayed(giveUp, NOTHING_HEARD_MS)
            return
        }
        if (endPending) return
        val wait = TurnDetector.pauseMillis(heard(), settings)
        stateFlow.value = VoiceState.Listening(heard(), wait)
        endPending = true
        main.postDelayed(endTurn, wait)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() {
            if (listening()) stillTalking()
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (!listening()) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) return
            partial = text
            stillTalking()
            update()
        }

        override fun onResults(results: Bundle?) {
            if (!listening()) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                .orEmpty().ifBlank { partial }
            if (text.isNotBlank()) {
                committed = listOf(committed, text).filter { it.isNotBlank() }.joinToString(" ")
                // New words: the silence count starts now.
                stillTalking()
            }
            partial = ""
            if (!update()) return
            scheduleEnd()
            startRecognizer()
        }

        override fun onError(error: Int) {
            if (!listening()) return
            when (error) {
                // A pause with nothing new: keep listening, the silence timer decides.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (partial.isNotBlank()) {
                        committed = heard()
                        partial = ""
                    }
                    scheduleEnd()
                    startRecognizer()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    main.removeCallbacks(retry)
                    main.postDelayed(retry, 300)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("Microphone permission is needed.")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    fail("Speech recognition needs a connection on this phone. You can type instead.")
                else -> if (heard().isNotBlank()) finish() else fail("I couldn't hear you. Tap the mic to try again.")
            }
        }
    }

    private fun fail(message: String) {
        cancel()
        stateFlow.value = VoiceState.Error(message)
    }

    private companion object {
        const val NOTHING_HEARD_MS = 15_000L
    }
}
