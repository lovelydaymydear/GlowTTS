package com.exemplo.edgetts.protocol

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class EdgeTtsClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val BASE_WSS_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
        private const val AUDIO_MARKER = "Content-Type:audio/mpeg\r\n"
    }

    fun synthesizeMp3(
        text: String,
        voice: String = "pt-BR-FranciscaNeural",
        ratePercent: Int = 0,
        pitchHz: Int = 0,
        clockOffsetSeconds: Long = 0L
    ): ByteArray {
        val cleanText = escapeXml(text.trim())
        if (cleanText.isEmpty()) {
            return ByteArray(0)
        }

        val secMsGec = SecMsGec.generate(clockOffsetSeconds)
        val secMsGecVersion = SecMsGec.secMsGecVersion()
        val connectionId = uuidWithoutHyphens()
        val requestId = uuidWithoutHyphens()

        val url = "$BASE_WSS_URL?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&Sec-MS-GEC=$secMsGec" +
            "&Sec-MS-GEC-Version=$secMsGecVersion" +
            "&ConnectionId=$connectionId"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .build()

        val audioBuffer = ByteArrayOutputStream()
        val errorRef = AtomicReference<Throwable?>(null)
        val doneLatch = CountDownLatch(1)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val configMessage = buildSpeechConfigMessage()
                webSocket.send(configMessage)

                val ssmlMessage = buildSsmlMessage(
                    requestId = requestId,
                    voice = voice,
                    text = cleanText,
                    ratePercent = ratePercent,
                    pitchHz = pitchHz
                )
                webSocket.send(ssmlMessage)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
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
                errorRef.set(IllegalStateException("Falha na conexão Edge TTS$codeInfo: ${t.message}", t))
                doneLatch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                doneLatch.countDown()
            }
        }

        val webSocket = httpClient.newWebSocket(request, listener)
        val finished = doneLatch.await(60, TimeUnit.SECONDS)
        if (!finished) {
            webSocket.cancel()
            throw IllegalStateException("Tempo limite esgotado ao aguardar áudio do Edge TTS.")
        }

        errorRef.get()?.let { throw it }
        return audioBuffer.toByteArray()
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
        val markerBytes = AUDIO_MARKER.toByteArray(Charsets.UTF_8)
        val markerIndex = indexOfSubArray(raw, markerBytes)
        if (markerIndex == -1) return ByteArray(0)

        val separatorBytes = "\r\n\r\n".toByteArray(Charsets.UTF_8)
        val bodyStart = indexOfSubArray(raw, separatorBytes, startIndex = markerIndex + markerBytes.size)
        if (bodyStart == -1) return ByteArray(0)

        val payloadStart = bodyStart + separatorBytes.size
        if (payloadStart >= raw.size) return ByteArray(0)

        return raw.copyOfRange(payloadStart, raw.size)
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
