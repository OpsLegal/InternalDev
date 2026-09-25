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

    private var recognizer: SpeechRecognizer? = null
    private var settings = ConversationSettings()
    private var committed = ""
    private var partial = ""
    private var onTurn: ((String) -> Unit)? = null
    private val endTurn = Runnable { finish() }
    private val giveUp = Runnable { if (heard().isBlank()) cancel() }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var afterSpeech: (() -> Unit)? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Starts a turn. [onTurn] receives the full text once the person has finished. */
    fun listen(settings: ConversationSettings, onTurn: (String) -> Unit) {
        stopSpeaking()
        this.settings = settings
        this.onTurn = onTurn
        committed = ""
        partial = ""
        stateFlow.value = VoiceState.Listening("", null)
        main.removeCallbacks(giveUp)
        // Nobody speaks at all: stop listening after a while.
        main.postDelayed(giveUp, 15_000)
        startRecognizer()
    }

    /** Ends the turn now with what was heard (the "I'm done" tap). */
    fun finish() {
        main.removeCallbacks(endTurn)
        main.removeCallbacks(giveUp)
        val text = heard().trim()
        val callback = onTurn
        stopRecognizer()
        stateFlow.value = VoiceState.Idle
        onTurn = null
        if (text.isNotEmpty()) callback?.invoke(text)
    }

    fun cancel() {
        main.removeCallbacks(endTurn)
        main.removeCallbacks(giveUp)
        onTurn = null
        stopRecognizer()
        stateFlow.value = VoiceState.Idle
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
                        val (t, s) = waitingSpeech ?: return@post
                        waitingSpeech = null
                        if (ttsReady) speakNow(t, s) else done()
                    }
                }.apply { setOnUtteranceProgressListener(progress) }
            }
            ttsReady -> speakNow(clean, settings)
            // Engine still starting: it picks this up when ready.
            else -> waitingSpeech = clean to settings
        }
    }

    private var waitingSpeech: Pair<String, ConversationSettings>? = null

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) {
            main.post { done() }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            main.post { done() }
        }
    }

    fun stopSpeaking() {
        afterSpeech = null
        waitingSpeech = null
        tts?.stop()
        if (stateFlow.value == VoiceState.Speaking) stateFlow.value = VoiceState.Idle
    }

    fun release() {
        cancel()
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
    }

    private fun speakNow(text: String, settings: ConversationSettings) {
        val engine = tts ?: return done()
        engine.language = Locale.forLanguageTag(settings.voiceLanguage)
        engine.setSpeechRate(settings.speechRate)
        stateFlow.value = VoiceState.Speaking
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    private fun done() {
        if (stateFlow.value == VoiceState.Speaking) stateFlow.value = VoiceState.Idle
        val next = afterSpeech
        afterSpeech = null
        next?.invoke()
    }

    private fun heard(): String = listOf(committed, partial).filter { it.isNotBlank() }.joinToString(" ")

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

    private fun listening() = onTurn != null

    private fun update() {
        val text = heard()
        // A spoken end phrase hands the turn over at once.
        TurnDetector.stripEndPhrase(text, settings.endPhrases)?.let { rest ->
            committed = rest.ifBlank { text }
            partial = ""
            finish()
            return
        }
        stateFlow.value = VoiceState.Listening(text, (stateFlow.value as? VoiceState.Listening)?.waitingMs)
    }

    private fun scheduleEnd() {
        main.removeCallbacks(endTurn)
        if (heard().isBlank()) return
        val wait = TurnDetector.pauseMillis(heard(), settings)
        stateFlow.value = VoiceState.Listening(heard(), wait)
        main.postDelayed(endTurn, wait)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() {
            // Talking again: the pause timer starts over when they stop.
            main.removeCallbacks(endTurn)
            main.removeCallbacks(giveUp)
            if (listening()) stateFlow.value = VoiceState.Listening(heard(), null)
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (!listening()) return
            partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            update()
        }

        override fun onResults(results: Bundle?) {
            if (!listening()) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) committed = listOf(committed, text).filter { it.isNotBlank() }.joinToString(" ")
            partial = ""
            update()
            if (!listening()) return
            scheduleEnd()
            startRecognizer()
        }

        override fun onError(error: Int) {
            if (!listening()) return
            when (error) {
                // A pause with nothing new: keep listening, the pause timer decides.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (partial.isNotBlank()) {
                        committed = heard()
                        partial = ""
                    }
                    scheduleEnd()
                    startRecognizer()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> main.postDelayed({ if (listening()) startRecognizer() }, 300)
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
}
