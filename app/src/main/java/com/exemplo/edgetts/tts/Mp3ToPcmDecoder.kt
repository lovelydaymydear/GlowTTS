package com.exemplo.edgetts.tts

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

data class DecodedPcmAudio(
    val pcmBytes: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int
)

class Mp3ToPcmDecoder {

    fun decode(mp3Bytes: ByteArray): DecodedPcmAudio {
        if (mp3Bytes.isEmpty()) {
            return DecodedPcmAudio(ByteArray(0), 24000, 1)
        }

        val mimeType = MediaFormat.MIMETYPE_AUDIO_MPEG
        val codec = MediaCodec.createDecoderByType(mimeType)
        val format = MediaFormat.createAudioFormat(mimeType, 24000, 1)

        val outputStream = ByteArrayOutputStream()
        var sampleRate = 24000
        var channelCount = 1

        try {
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputOffset = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val remaining = mp3Bytes.size - inputOffset
                            if (remaining > 0) {
                                val chunkSize = minOf(remaining, inputBuffer.remaining())
                                inputBuffer.put(mp3Bytes, inputOffset, chunkSize)
                                inputOffset += chunkSize
                                codec.queueInputBuffer(inputBufferIndex, 0, chunkSize, 0L, 0)
                            } else {
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            }
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outputBufferIndex >= 0 -> {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                        val outputBuffer: ByteBuffer? = codec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)
                            outputStream.write(chunk)
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                    }

                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
        }

        return DecodedPcmAudio(
            pcmBytes = outputStream.toByteArray(),
            sampleRateHz = sampleRate,
            channelCount = channelCount
        )
    }
}
