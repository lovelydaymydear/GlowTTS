package com.exemplo.edgetts.tts

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import com.exemplo.edgetts.protocol.EdgeTtsClient
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class EdgeTtsService : TextToSpeechService() {

    companion object {
        private const val PREFS_NAME = "edge_tts_prefs"
        private const val KEY_VOICE = "selected_voice"
        private const val KEY_RATE_PERCENT = "rate_percent"
        private const val KEY_PITCH_HZ = "pitch_hz"
        private const val DEFAULT_VOICE = "pt-BR-FranciscaNeural"
    }

    private val client = EdgeTtsClient()
    private val decoder = Mp3ToPcmDecoder()
    private val stopRequested = AtomicBoolean(false)
    private var currentLanguage = arrayOf("por", "BRA", "")

    override fun onGetLanguage(): Array<String> {
        return currentLanguage
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val normalizedLang = (lang ?: "").lowercase(Locale.US)
        return when {
            normalizedLang == "por" || normalizedLang == "pt" -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang == "eng" || normalizedLang == "en" -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang == "spa" || normalizedLang == "es" -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang == "fra" || normalizedLang == "fr" -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang == "zho" || normalizedLang == "zh" -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.isNotBlank() -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val availability = onIsLanguageAvailable(lang, country, variant)
        if (availability != TextToSpeech.LANG_NOT_SUPPORTED) {
            currentLanguage = arrayOf(
                lang ?: "por",
                country ?: "BRA",
                variant ?: ""
            )
        }
        return availability
    }

    override fun onStop() {
        stopRequested.set(true)
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        stopRequested.set(false)
        val text = request.charSequenceText?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            callback.done()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val configuredVoice = prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        val voice = resolveVoiceForRequest(request, configuredVoice)
        val ratePercent = prefs.getInt(KEY_RATE_PERCENT, mapSpeechRateToPercent(request.speechRate))
        val pitchHz = prefs.getInt(KEY_PITCH_HZ, mapPitchToHz(request.pitch))

        try {
            val mp3Bytes = client.synthesizeMp3(
                text = text,
                voice = voice,
                ratePercent = ratePercent,
                pitchHz = pitchHz
            )

            if (stopRequested.get()) {
                callback.done()
                return
            }

            val decoded = decoder.decode(mp3Bytes)
            val audioEncoding = if (decoded.channelCount == 2) {
                AudioFormat.ENCODING_PCM_16BIT
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            callback.start(decoded.sampleRateHz, audioEncoding, decoded.channelCount)

            val chunkSize = 4096
            var offset = 0
            while (offset < decoded.pcmBytes.size && !stopRequested.get()) {
                val length = minOf(chunkSize, decoded.pcmBytes.size - offset)
                val status = callback.audioAvailable(decoded.pcmBytes, offset, length)
                if (status != TextToSpeech.SUCCESS) {
                    break
                }
                offset += length
            }

            callback.done()
        } catch (_: Exception) {
            try {
                callback.error()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveVoiceForRequest(request: SynthesisRequest, fallbackVoice: String): String {
        val lang = (request.language ?: "").lowercase(Locale.US)
        val country = (request.country ?: "").uppercase(Locale.US)

        return when {
            lang == "por" || lang == "pt" -> {
                if (country == "PRT" || country == "PT") {
                    "pt-PT-RaquelNeural"
                } else if (fallbackVoice.startsWith("pt-")) {
                    fallbackVoice
                } else {
                    "pt-BR-FranciscaNeural"
                }
            }
            lang == "eng" || lang == "en" -> {
                if (country == "GBR" || country == "GB") "en-GB-SoniaNeural" else "en-US-JennyNeural"
            }
            lang == "spa" || lang == "es" -> "es-ES-ElviraNeural"
            lang == "fra" || lang == "fr" -> "fr-FR-DeniseNeural"
            lang == "zho" || lang == "zh" -> "zh-CN-XiaoxiaoNeural"
            else -> fallbackVoice
        }
    }

    private fun mapSpeechRateToPercent(speechRate: Int): Int {
        // Android usa 100 como velocidade normal
        val delta = speechRate - 100
        return delta.coerceIn(-50, 100)
    }

    private fun mapPitchToHz(pitch: Int): Int {
        // Android usa 100 como tom normal
        val delta = pitch - 100
        return delta.coerceIn(-50, 50)
    }
}
