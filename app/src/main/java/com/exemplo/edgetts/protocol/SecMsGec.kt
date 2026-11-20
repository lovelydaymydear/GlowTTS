package com.exemplo.edgetts.protocol

import java.security.MessageDigest
import java.util.Locale

object SecMsGec {
    private const val WIN_EPOCH_SECONDS = 11644473600L
    private const val QUANTUM_SECONDS = 300.0
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM_MAJOR_VERSION = "130"

    fun secMsGecVersion(): String {
        return "1-$CHROMIUM_MAJOR_VERSION.0.0.0"
    }

    fun generate(clockOffsetSeconds: Long = 0L): String {
        val unixEpochSeconds = (System.currentTimeMillis() / 1000L) + clockOffsetSeconds
        val windowsEpochSeconds = unixEpochSeconds + WIN_EPOCH_SECONDS
        val roundedSeconds = windowsEpochSeconds - (windowsEpochSeconds % QUANTUM_SECONDS.toLong())
        val ticks = (roundedSeconds * 10_000_000L).toLong()
        val payload = String.format(Locale.US, "%d%s", ticks, TRUSTED_CLIENT_TOKEN)

        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.US_ASCII))
        return digest.joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02X", byte)
        }
    }
}
