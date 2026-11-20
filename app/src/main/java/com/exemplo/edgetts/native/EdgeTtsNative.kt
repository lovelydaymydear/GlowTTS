package com.exemplo.edgetts.native

import com.exemplo.edgetts.protocol.EdgeVoice
import org.json.JSONArray

object EdgeTtsNative {
    init {
        System.loadLibrary("glowtts_native")
    }

    @JvmStatic
    external fun nativeListVoices(): String

    @JvmStatic
    external fun nativeBeginSynthesis(
        requestId: String,
        text: String,
        voice: String,
        rate: String,
        volume: String,
        pitch: String
    ): String?

    @JvmStatic
    external fun nativeReadPcmChunk(requestId: String, maxBytes: Int): ByteArray?

    @JvmStatic
    external fun nativeGetLastError(requestId: String): String?

    @JvmStatic
    external fun nativeStop(requestId: String)

    fun listVoicesParsed(): List<EdgeVoice> {
        val raw = nativeListVoices()
        if (raw.startsWith("ERROR:")) {
            throw IllegalStateException(raw.removePrefix("ERROR:").trim())
        }
        val array = JSONArray(raw)
        val result = ArrayList<EdgeVoice>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val shortName = obj.optString("ShortName").ifBlank { obj.optString("short_name") }.trim()
            if (shortName.isEmpty()) continue
            val friendlyName = obj.optString("FriendlyName").ifBlank { obj.optString("friendly_name") }.ifBlank { shortName }
            val locale = obj.optString("Locale").ifBlank { obj.optString("locale") }.trim()
            val gender = obj.optString("Gender").ifBlank { obj.optString("gender") }.trim()
            result.add(EdgeVoice(shortName, friendlyName, locale, gender))
        }
        if (result.isEmpty()) {
            throw IllegalStateException("Nenhuma voz retornada pelo motor nativo")
        }
        return result.sortedWith(
            compareBy<EdgeVoice> { !it.locale.startsWith("pt", ignoreCase = true) }
                .thenBy { it.locale }
                .thenBy { it.friendlyName }
        )
    }
}
