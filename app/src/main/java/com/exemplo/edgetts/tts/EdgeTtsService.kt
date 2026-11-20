package com.exemplo.edgetts.tts

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.exemplo.edgetts.protocol.EdgeTtsClient
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class EdgeTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "GlowTtsService"
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
            currentLanguage = arrayOf(
                lang ?: "por",
                country ?: "BRA",
                variant ?: ""
            )
        }
        return availability
    }

    override fun onGetVoices(): List<Voice> {
        return EdgeTtsClient.DEFAULT_FALLBACK_VOICES.map { v ->
            val parts = v.locale.split("-")
            val locale = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale("pt", "BR")
            Voice(
                v.shortName,
                locale,
                Voice.QUALITY_HIGH,
                Voice.LATENCY_NORMAL,
                true,
                emptySet()
            )
        }
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

            if (mp3Bytes.isEmpty()) {
                Log.w(TAG, "Nenhum byte de áudio recebido para voz=$voice")
                callback.error()
                return
            }

            if (stopRequested.get()) {
                callback.done()
                return
            }

            val decoded = decoder.decode(mp3Bytes)
            if (decoded.pcmBytes.isEmpty()) {
                Log.w(TAG, "Falha ao decodificar PCM para voz=$voice")
                callback.error()
                return
            }

            val safeSampleRate = if (decoded.sampleRateHz > 0) decoded.sampleRateHz else 24000
            val safeChannels = if (decoded.channelCount > 0) decoded.channelCount else 1

            callback.start(safeSampleRate, AudioFormat.ENCODING_PCM_16BIT, safeChannels)

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
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao sintetizar texto com voz=$voice: ${e.message}", e)
            try {
                callback.error()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveVoiceForRequest(request: SynthesisRequest, fallbackVoice: String): String {
        if (fallbackVoice.isNotBlank() && fallbackVoice.contains("Neural")) {
            val reqLang = (request.language ?: "").lowercase(Locale.US)
            val voiceLang = fallbackVoice.substringBefore("-").lowercase(Locale.US)
            val matchesPortuguese = voiceLang == "pt" && (reqLang.startsWith("pt") || reqLang.startsWith("por") || reqLang.isBlank())
            val matchesEnglish = voiceLang == "en" && (reqLang.startsWith("en") || reqLang.startsWith("eng"))
            val matchesSpanish = voiceLang == "es" && (reqLang.startsWith("es") || reqLang.startsWith("spa"))
            val matchesFrench = voiceLang == "fr" && (reqLang.startsWith("fr") || reqLang.startsWith("fra"))
            val matchesGerman = voiceLang == "de" && (reqLang.startsWith("de") || reqLang.startsWith("deu"))
            val matchesItalian = voiceLang == "it" && (reqLang.startsWith("it") || reqLang.startsWith("ita"))
            val matchesJapanese = voiceLang == "ja" && (reqLang.startsWith("ja") || reqLang.startsWith("jpn"))
            val matchesChinese = voiceLang == "zh" && (reqLang.startsWith("zh") || reqLang.startsWith("zho"))

            if (reqLang.isBlank() || matchesPortuguese || matchesEnglish || matchesSpanish ||
                matchesFrench || matchesGerman || matchesItalian || matchesJapanese || matchesChinese
            ) {
                return fallbackVoice
            }
        }

        val lang = (request.language ?: "").lowercase(Locale.US)
        val country = (request.country ?: "").uppercase(Locale.US)

        return when {
            lang.startsWith("por") || lang.startsWith("pt") -> {
                if (country == "PRT" || country == "PT") "pt-PT-RaquelNeural" else "pt-BR-FranciscaNeural"
            }
            lang.startsWith("eng") || lang.startsWith("en") -> {
                if (country == "GBR" || country == "GB") "en-GB-SoniaNeural" else "en-US-JennyNeural"
            }
            lang.startsWith("spa") || lang.startsWith("es") -> "es-ES-ElviraNeural"
            lang.startsWith("fra") || lang.startsWith("fr") -> "fr-FR-DeniseNeural"
            lang.startsWith("deu") || lang.startsWith("de") -> "de-DE-KatjaNeural"
            lang.startsWith("ita") || lang.startsWith("it") -> "it-IT-ElsaNeural"
            lang.startsWith("jpn") || lang.startsWith("ja") -> "ja-JP-NanamiNeural"
            lang.startsWith("zho") || lang.startsWith("zh") -> "zh-CN-XiaoxiaoNeural"
            else -> fallbackVoice
        }
    }

    private fun mapSpeechRateToPercent(speechRate: Int): Int {
        val delta = speechRate - 100
        return delta.coerceIn(-50, 100)
    }

    private fun mapPitchToHz(pitch: Int): Int {
        val delta = pitch - 100
        return delta.coerceIn(-50, 50)
    }
}
