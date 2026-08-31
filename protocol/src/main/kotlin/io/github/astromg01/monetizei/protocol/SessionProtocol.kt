package io.github.astromg01.monetizei.protocol

import java.security.MessageDigest
import java.util.Base64

object SessionProtocol {
    const val VERSION = 1
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    const val SESSION_DURATION_MS = 30_000L
    const val MAX_SESSION_SCORE = 450
}

data class InstallationRegistration(
    val protocolVersion: Int,
    val installationId: String,
    val keyId: String,
    val publicKeyBase64: String,
    val signatureAlgorithm: String,
    val appVersion: String,
    val createdAtEpochMs: Long
)

data class SessionPayload(
    val protocolVersion: Int,
    val installationId: String,
    val sessionId: String,
    val sequence: Long,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val durationMs: Long,
    val score: Int,
    val appVersion: String
)

data class SignedSessionEnvelope(
    val payload: SessionPayload,
    val keyId: String,
    val signatureAlgorithm: String,
    val signatureBase64: String
)

object KeyIds {
    private const val HEX = "0123456789abcdef"

    fun fromEncodedPublicKey(encoded: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    fun fromPublicKeyBase64(publicKeyBase64: String): String =
        fromEncodedPublicKey(Base64.getDecoder().decode(publicKeyBase64))
}
