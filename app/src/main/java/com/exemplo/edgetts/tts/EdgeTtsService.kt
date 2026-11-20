package com.exemplo.edgetts.tts

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import com.exemplo.edgetts.native.EdgeTtsNative
import com.exemplo.edgetts.protocol.EdgeTtsClient
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class EdgeTtsService : TextToSpeechService() {

    companion object {
        private const val PREFS_NAME = "edge_tts_prefs"
        private const val KEY_VOICE = "selected_voice"
        private const val KEY_RATE_PERCENT = "rate_percent"
        private const val KEY_PITCH_HZ = "pitch_hz"
        private const val KEY_LAST_DIAG = "last_diag_message"
        private const val DEFAULT_VOICE = "pt-BR-FranciscaNeural"
        private const val SAMPLE_RATE_HZ = 48000
    }

    private val stopRequested = AtomicBoolean(false)
    @Volatile
    private var activeRequestId: String? = null
    private var currentLanguage = arrayOf("por", "BRA", "")

    override fun onGetLanguage(): Array<String> = currentLanguage

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val normalizedLang = (lang ?: "").lowercase(Locale.US)
        return when {
            normalizedLang.startsWith("por") || normalizedLang.startsWith("pt") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("eng") || normalizedLang.startsWith("en") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("spa") || normalizedLang.startsWith("es") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("fra") || normalizedLang.startsWith("fr") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("deu") || normalizedLang.startsWith("de") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("ita") || normalizedLang.startsWith("it") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("jpn") || normalizedLang.startsWith("ja") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("zho") || normalizedLang.startsWith("zh") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.isNotBlank() -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val availability = onIsLanguageAvailable(lang, country, variant)
        if (availability != TextToSpeech.LANG_NOT_SUPPORTED) {
            currentLanguage = arrayOf(lang ?: "por", country ?: "BRA", variant ?: "")
        }
        return availability
    }

    override fun onGetVoices(): List<Voice> {
        return EdgeTtsClient.DEFAULT_FALLBACK_VOICES.map { v ->
            val parts = v.locale.split("-")
            val locale = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale("pt", "BR")
            Voice(v.shortName, locale, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, true, emptySet())
        }
    }

    override fun onStop() {
        stopRequested.set(true)
        activeRequestId?.let { EdgeTtsNative.nativeStop(it) }
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        stopRequested.set(false)
        val text = request.charSequenceText?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            saveDiag("Texto vazio")
            callback.done()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val configuredVoice = prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        val voice = resolveVoiceForRequest(request, configuredVoice)
        val ratePercent = prefs.getInt(KEY_RATE_PERCENT, (request.speechRate - 100).coerceIn(-50, 100))
        val pitchHz = prefs.getInt(KEY_PITCH_HZ, (request.pitch - 100).coerceIn(-50, 50))

        val rateStr = if (ratePercent >= 0) "+${ratePercent}%" else "${ratePercent}%"
        val pitchStr = if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"
        val volumeStr = "+0%"

        val requestId = UUID.randomUUID().toString()
        activeRequestId = requestId
        saveDiag("A iniciar síntese ($voice)...")

        try {
            val beginError = EdgeTtsNative.nativeBeginSynthesis(
                requestId = requestId,
                text = text,
                voice = voice,
                rate = rateStr,
                volume = volumeStr,
                pitch = pitchStr
            )
            if (!beginError.isNullOrBlank()) {
                saveDiag("Erro início: $beginError")
                callback.error()
                return
            }

            callback.start(SAMPLE_RATE_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
            saveDiag("A reproduzir ($SAMPLE_RATE_HZ Hz)...")

            val chunkMax = callback.maxBufferSize.coerceAtLeast(8192)
            var totalBytes = 0

            while (!stopRequested.get()) {
                val pcmChunk = EdgeTtsNative.nativeReadPcmChunk(requestId, chunkMax) ?: break
                if (pcmChunk.isEmpty()) continue
                totalBytes += pcmChunk.size
                val status = callback.audioAvailable(pcmChunk, 0, pcmChunk.size)
                if (status != TextToSpeech.SUCCESS) {
                    break
                }
            }

            val nativeErr = EdgeTtsNative.nativeGetLastError(requestId)
            if (!nativeErr.isNullOrBlank() && totalBytes == 0) {
                saveDiag("Erro: $nativeErr")
                callback.error()
                return
            }

            saveDiag("OK ($totalBytes bytes PCM)")
            callback.done()
        } catch (e: Exception) {
            saveDiag("Erro: ${e.message ?: e.javaClass.simpleName}")
            try {
                callback.error()
            } catch (_: Exception) {
            }
        } finally {
            EdgeTtsNative.nativeStop(requestId)
            if (activeRequestId == requestId) {
                activeRequestId = null
            }
        }
    }

    private fun saveDiag(message: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DIAG, message)
            .apply()
    }

    private fun resolveVoiceForRequest(request: SynthesisRequest, fallbackVoice: String): String {
        if (fallbackVoice.isNotBlank() && fallbackVoice.contains("Neural")) {
            val reqLang = (request.language ?: "").lowercase(Locale.US)
            val voiceLang = fallbackVoice.substringBefore("-").lowercase(Locale.US)
            if (reqLang.isBlank() || reqLang.startsWith(voiceLang) ||
                (voiceLang == "pt" && reqLang.startsWith("por")) ||
                (voiceLang == "en" && reqLang.startsWith("eng")) ||
                (voiceLang == "es" && reqLang.startsWith("spa")) ||
                (voiceLang == "fr" && reqLang.startsWith("fra")) ||
                (voiceLang == "de" && reqLang.startsWith("deu")) ||
                (voiceLang == "it" && reqLang.startsWith("ita")) ||
                (voiceLang == "ja" && reqLang.startsWith("jpn")) ||
                (voiceLang == "zh" && reqLang.startsWith("zho"))
            ) {
                return fallbackVoice
            }
        }

        val lang = (request.language ?: "").lowercase(Locale.US)
        val country = (request.country ?: "").uppercase(Locale.US)
        return when {
            lang.startsWith("por") || lang.startsWith("pt") ->
                if (country == "PRT" || country == "PT") "pt-PT-RaquelNeural" else "pt-BR-FranciscaNeural"
            lang.startsWith("eng") || lang.startsWith("en") ->
                if (country == "GBR" || country == "GB") "en-GB-SoniaNeural" else "en-US-JennyNeural"
            lang.startsWith("spa") || lang.startsWith("es") -> "es-ES-ElviraNeural"
            lang.startsWith("fra") || lang.startsWith("fr") -> "fr-FR-DeniseNeural"
            lang.startsWith("deu") || lang.startsWith("de") -> "de-DE-KatjaNeural"
            lang.startsWith("ita") || lang.startsWith("it") -> "it-IT-ElsaNeural"
            lang.startsWith("jpn") || lang.startsWith("ja") -> "ja-JP-NanamiNeural"
            lang.startsWith("zho") || lang.startsWith("zh") -> "zh-CN-XiaoxiaoNeural"
            else -> fallbackVoice
        }
    }
}
