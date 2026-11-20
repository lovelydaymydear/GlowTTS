package com.exemplo.edgetts.tts

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

data class DecodedPcmAudio(
    val pcmBytes: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int
)

class Mp3ToPcmDecoder {

    fun decode(mp3Bytes: ByteArray): DecodedPcmAudio {
        if (mp3Bytes.isEmpty()) {
            throw IllegalArgumentException("Array MP3 vazio")
        }

        val tempFile = File.createTempFile("glowtts_audio_", ".mp3")
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(mp3Bytes)
                fos.flush()
            }

            val extractor = MediaExtractor()
            extractor.setDataSource(tempFile.absolutePath)

            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || inputFormat == null) {
                extractor.release()
                throw IllegalStateException("Faixa de áudio não encontrada no MP3")
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"

            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                24000
            }

            var channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val pcmOut = ArrayList<ByteArray>()
            var totalBytes = 0
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000L)
                        if (inputIndex >= 0) {
                            val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputIndex)
                            if (inputBuffer != null) {
                                inputBuffer.clear()
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        0,
                                        0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    val presentationTimeUs = extractor.sampleTime
                                    codec.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        sampleSize,
                                        presentationTimeUs,
                                        0
                                    )
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outputIndex >= 0 -> {
                            val outputBuffer: ByteBuffer? = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val chunk = ByteArray(bufferInfo.size)
                                outputBuffer.get(chunk)
                                pcmOut.add(chunk)
                                totalBytes += chunk.size
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
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
                extractor.release()
            }

            if (totalBytes <= 0) {
                throw IllegalStateException("Decodificação PCM não gerou amostras")
            }

            val mergedPcm = ByteArray(totalBytes)
            var currentPos = 0
            for (part in pcmOut) {
                System.arraycopy(part, 0, mergedPcm, currentPos, part.size)
                currentPos += part.size
            }

            return DecodedPcmAudio(
                pcmBytes = mergedPcm,
                sampleRateHz = sampleRate,
                channelCount = channelCount
            )
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    fun inspectDurationMs(mp3Bytes: ByteArray): Long {
        if (mp3Bytes.isEmpty()) return 0L
        val tempFile = File.createTempFile("glowtts_meta_", ".mp3")
        return try {
            FileOutputStream(tempFile).use { it.write(mp3Bytes) }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempFile.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        } catch (_: Exception) {
            0L
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
