package com.exemplo.edgetts.protocol

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

object SecMsGec {

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WIN_EPOCH_OFFSET_SECONDS = 11644473600L
    private const val TICKS_PER_SECOND = 10_000_000L
    private const val ROUND_INTERVAL_SECONDS = 300L

    fun generateV1(): String {
        val unixEpochSeconds = System.currentTimeMillis() / 1000L
        val windowsEpochSeconds = unixEpochSeconds + WIN_EPOCH_OFFSET_SECONDS
        val roundedSeconds = windowsEpochSeconds - (windowsEpochSeconds % ROUND_INTERVAL_SECONDS)
        val ticks = roundedSeconds * TICKS_PER_SECOND

        val input = String.format(Locale.US, "%d%s", ticks, TRUSTED_CLIENT_TOKEN)
        return sha256Hex(input)
    }

    fun generateV2(): String {
        val unixEpochSeconds = System.currentTimeMillis() / 1000L
        val windowsEpochSeconds = unixEpochSeconds + WIN_EPOCH_OFFSET_SECONDS
        val roundedSeconds = windowsEpochSeconds - (windowsEpochSeconds % ROUND_INTERVAL_SECONDS)
        val ticks = roundedSeconds * TICKS_PER_SECOND

        val saltBytes = ByteArray(8)
        SecureRandom().nextBytes(saltBytes)
        val saltHex = saltBytes.joinToString("") { byte ->
            String.format(Locale.US, "%02X", byte)
        }

        val input = String.format(Locale.US, "%d%s%s", ticks, TRUSTED_CLIENT_TOKEN, saltHex)
        val digestHex = sha256Hex(input)
        return "$saltHex-$digestHex"
    }

    fun generate(): String = generateV1()

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.US_ASCII))
        return digest.joinToString("") { byte ->
            String.format(Locale.US, "%02X", byte)
        }
    }
}
