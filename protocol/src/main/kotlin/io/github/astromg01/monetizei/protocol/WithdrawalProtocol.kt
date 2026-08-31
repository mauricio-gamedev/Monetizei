package io.github.astromg01.monetizei.protocol

import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable

object WithdrawalProtocol {
    const val VERSION = 1
}

@Serializable
data class WithdrawalPayload(
    val protocolVersion: Int,
    val installationId: String,
    val requestId: String,
    val currency: String,
    val requestedAtEpochMs: Long,
    val appVersion: String
)

@Serializable
data class SignedWithdrawalEnvelope(
    val payload: WithdrawalPayload,
    val keyId: String,
    val signatureAlgorithm: String,
    val signatureBase64: String
)

object CanonicalWithdrawalCodec {
    fun bytes(payload: WithdrawalPayload): ByteArray = buildString {
        append("protocolVersion=").append(payload.protocolVersion).append('\n')
        append("installationId=").append(payload.installationId).append('\n')
        append("requestId=").append(payload.requestId).append('\n')
        append("currency=").append(payload.currency).append('\n')
        append("requestedAtEpochMs=").append(payload.requestedAtEpochMs).append('\n')
        append("appVersion=").append(payload.appVersion)
    }.toByteArray(StandardCharsets.UTF_8)
}
