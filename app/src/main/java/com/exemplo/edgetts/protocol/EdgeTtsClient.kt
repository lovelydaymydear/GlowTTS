package com.exemplo.edgetts.protocol

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class EdgeVoice(
    val shortName: String,
    val friendlyName: String,
    val locale: String,
    val gender: String
) {
    fun displayLabel(): String {
        val cleanName = friendlyName.substringBefore(" - ").ifBlank { shortName }
        return "$locale • $cleanName ($gender) — $shortName"
    }
}

class EdgeTtsClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val VOICE_LIST_URL =
            "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list?trustedclienttoken=$TRUSTED_CLIENT_TOKEN"
        private const val PRIMARY_WSS_URL =
            "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1"
        private const val FALLBACK_WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

        private const val EDGE_VERSION = "155.0.4283.45"
        private const val CHROMIUM_MAJOR = "155"
        private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
        private const val AUDIO_MARKER = "Content-Type:audio/mpeg"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$EDGE_VERSION"
        private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

        val DEFAULT_FALLBACK_VOICES: List<EdgeVoice> = listOf(
            EdgeVoice("pt-BR-FranciscaNeural", "Francisca", "pt-BR", "Female"),
            EdgeVoice("pt-BR-AntonioNeural", "Antonio", "pt-BR", "Male"),
            EdgeVoice("pt-BR-ThalitaNeural", "Thalita", "pt-BR", "Female"),
            EdgeVoice("pt-PT-RaquelNeural", "Raquel", "pt-PT", "Female"),
            EdgeVoice("pt-PT-DuarteNeural", "Duarte", "pt-PT", "Male"),
            EdgeVoice("pt-PT-FernandaNeural", "Fernanda", "pt-PT", "Female"),
            EdgeVoice("en-US-JennyNeural", "Jenny", "en-US", "Female"),
            EdgeVoice("en-US-GuyNeural", "Guy", "en-US", "Male"),
            EdgeVoice("en-US-AriaNeural", "Aria", "en-US", "Female"),
            EdgeVoice("en-GB-SoniaNeural", "Sonia", "en-GB", "Female"),
            EdgeVoice("en-GB-RyanNeural", "Ryan", "en-GB", "Male"),
            EdgeVoice("es-ES-ElviraNeural", "Elvira", "es-ES", "Female"),
            EdgeVoice("es-MX-DaliaNeural", "Dalia", "es-MX", "Female"),
            EdgeVoice("fr-FR-DeniseNeural", "Denise", "fr-FR", "Female"),
            EdgeVoice("de-DE-KatjaNeural", "Katja", "de-DE", "Female"),
            EdgeVoice("it-IT-ElsaNeural", "Elsa", "it-IT", "Female"),
            EdgeVoice("ja-JP-NanamiNeural", "Nanami", "ja-JP", "Female"),
            EdgeVoice("zh-CN-XiaoxiaoNeural", "Xiaoxiao", "zh-CN", "Female")
        )
    }

    fun fetchAllVoices(): List<EdgeVoice> {
        val request = Request.Builder()
            .url(VOICE_LIST_URL)
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Falha ao buscar lista de vozes (HTTP ${response.code})")
            }
            val body = response.body?.string().orEmpty()
            val array = JSONArray(body)
            val voices = ArrayList<EdgeVoice>(array.length())

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val shortName = obj.optString("ShortName").trim()
                if (shortName.isEmpty()) continue
                val friendlyName = obj.optString("FriendlyName").ifBlank { shortName }.trim()
                val locale = obj.optString("Locale").trim()
                val gender = obj.optString("Gender").trim()
                voices.add(
                    EdgeVoice(
                        shortName = shortName,
                        friendlyName = friendlyName,
                        locale = locale,
                        gender = gender
                    )
                )
            }

            if (voices.isEmpty()) {
                throw IllegalStateException("Lista de vozes retornou vazia.")
            }

            return voices.sortedWith(
                compareBy<EdgeVoice> { !it.locale.startsWith("pt", ignoreCase = true) }
                    .thenBy { it.locale }
                    .thenBy { it.friendlyName }
            )
        }
    }

    fun synthesizeMp3(
        text: String,
        voice: String = "pt-BR-FranciscaNeural",
        ratePercent: Int = 0,
        pitchHz: Int = 0
    ): ByteArray {
        val cleanText = escapeXml(text.trim())
        if (cleanText.isEmpty()) {
            return ByteArray(0)
        }

        val primaryError: Throwable? = try {
            val bytes = synthesizeViaEndpoint(
                baseUrl = PRIMARY_WSS_URL,
                tokenParamName = "Ocp-Apim-Subscription-Key",
                secVersionPrefix = "2-$EDGE_VERSION",
                useV2Token = true,
                text = cleanText,
                voice = voice,
                ratePercent = ratePercent,
                pitchHz = pitchHz
            )
            if (bytes.isNotEmpty()) return bytes
            IllegalStateException("Resposta de áudio vazia no servidor principal.")
        } catch (t: Throwable) {
            t
        }

        return try {
            synthesizeViaEndpoint(
                baseUrl = FALLBACK_WSS_URL,
                tokenParamName = "TrustedClientToken",
                secVersionPrefix = "1-$CHROMIUM_MAJOR.0.0.0",
                useV2Token = false,
                text = cleanText,
                voice = voice,
                ratePercent = ratePercent,
                pitchHz = pitchHz
            )
        } catch (fallbackError: Throwable) {
            throw IllegalStateException(
                primaryError?.message ?: fallbackError.message ?: "Falha ao sintetizar áudio.",
                fallbackError
            )
        }
    }

    private fun synthesizeViaEndpoint(
        baseUrl: String,
        tokenParamName: String,
        secVersionPrefix: String,
        useV2Token: Boolean,
        text: String,
        voice: String,
        ratePercent: Int,
        pitchHz: Int
    ): ByteArray {
        val secMsGec = if (useV2Token) generateSecMsGecV2() else SecMsGec.generate()
        val connectionId = uuidWithoutHyphens()
        val requestId = uuidWithoutHyphens()

        val separator = if (baseUrl.contains("?")) "&" else "?"
        val url = "$baseUrl${separator}$tokenParamName=$TRUSTED_CLIENT_TOKEN" +
            "&Sec-MS-GEC=$secMsGec" +
            "&Sec-MS-GEC-Version=$secVersionPrefix" +
            "&ConnectionId=$connectionId"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .build()

        val audioBuffer = ByteArrayOutputStream()
        val errorRef = AtomicReference<Throwable?>(null)
        val doneLatch = CountDownLatch(1)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildSpeechConfigMessage())
                webSocket.send(
                    buildSsmlMessage(
                        requestId = requestId,
                        voice = voice,
                        text = text,
                        ratePercent = ratePercent,
                        pitchHz = pitchHz
                    )
                )
            }

            override fun onMessage(webSocket: WebSocket, textMsg: String) {
                if (textMsg.contains("Path:turn.end")) {
                    webSocket.close(1000, "Completed")
                    doneLatch.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val raw = bytes.toByteArray()
                val extractedAudio = extractMp3Payload(raw)
                if (extractedAudio.isNotEmpty()) {
                    audioBuffer.write(extractedAudio)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val codeInfo = response?.code?.let { " (HTTP $it)" } ?: ""
                errorRef.set(IllegalStateException("WebSocket$codeInfo: ${t.message}", t))
                doneLatch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                doneLatch.countDown()
            }
        }

        val webSocket = httpClient.newWebSocket(request, listener)
        val finished = doneLatch.await(30, TimeUnit.SECONDS)
        if (!finished) {
            webSocket.cancel()
            throw IllegalStateException("Tempo limite esgotado ao aguardar áudio.")
        }

        errorRef.get()?.let { throw it }
        return audioBuffer.toByteArray()
    }

    private fun generateSecMsGecV2(): String {
        val unixEpochSeconds = System.currentTimeMillis() / 1000L
        val windowsEpochSeconds = unixEpochSeconds + 11644473600L
        val roundedSeconds = windowsEpochSeconds - (windowsEpochSeconds % 300L)
        val ticks = roundedSeconds * 10_000_000L

        val saltBytes = ByteArray(8)
        SecureRandom().nextBytes(saltBytes)
        val saltHex = saltBytes.joinToString("") { byte ->
            String.format(Locale.US, "%02X", byte)
        }

        val payload = String.format(Locale.US, "%d%s%s", ticks, TRUSTED_CLIENT_TOKEN, saltHex)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.US_ASCII))
        val hashHex = digest.joinToString("") { byte ->
            String.format(Locale.US, "%02X", byte)
        }
        return "$saltHex-$hashHex"
    }

    private fun buildSpeechConfigMessage(): String {
        return "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            """{"context":{"synthesis":{"audio":{"metadataOptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"$OUTPUT_FORMAT"}}}}"""
    }

    private fun buildSsmlMessage(
        requestId: String,
        voice: String,
        text: String,
        ratePercent: Int,
        pitchHz: Int
    ): String {
        val rateFormatted = if (ratePercent >= 0) "+${ratePercent}%" else "${ratePercent}%"
        val pitchFormatted = if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"
        val lang = extractLocaleFromVoice(voice)

        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$lang'>" +
            "<voice name='$voice'>" +
            "<prosody rate='$rateFormatted' pitch='$pitchFormatted' volume='+0%'>" +
            text +
            "</prosody></voice></speak>"

        return "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml
    }

    private fun extractMp3Payload(raw: ByteArray): ByteArray {
        if (raw.size < 4) return ByteArray(0)

        val markerBytes = AUDIO_MARKER.toByteArray(StandardCharsets.UTF_8)
        val markerIndex = indexOfSubArray(raw, markerBytes)
        if (markerIndex != -1) {
            val separatorBytes = "\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
            val bodyStart = indexOfSubArray(raw, separatorBytes, startIndex = markerIndex + markerBytes.size)
            if (bodyStart != -1) {
                val payloadStart = bodyStart + separatorBytes.size
                if (payloadStart < raw.size) {
                    return raw.copyOfRange(payloadStart, raw.size)
                }
            }
        }

        val headerLen = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
        val payloadStart = 2 + headerLen
        if (headerLen > 0 && payloadStart < raw.size) {
            val headerStr = String(raw, 2, headerLen, StandardCharsets.UTF_8)
            if (headerStr.contains("Path:audio", ignoreCase = true) || headerStr.contains("audio/mpeg", ignoreCase = true)) {
                return raw.copyOfRange(payloadStart, raw.size)
            }
        }

        return ByteArray(0)
    }

    private fun indexOfSubArray(array: ByteArray, subArray: ByteArray, startIndex: Int = 0): Int {
        if (subArray.isEmpty() || array.size - startIndex < subArray.size) return -1
        for (i in startIndex..(array.size - subArray.size)) {
            var matched = true
            for (j in subArray.indices) {
                if (array[i + j] != subArray[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return -1
    }

    private fun extractLocaleFromVoice(voice: String): String {
        val parts = voice.split("-")
        return if (parts.size >= 2) "${parts[0]}-${parts[1]}" else "pt-BR"
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun uuidWithoutHyphens(): String {
        return UUID.randomUUID().toString().replace("-", "").lowercase(Locale.US)
    }
}
