package io.github.astromg01.monetizei.protocol

import java.nio.charset.StandardCharsets

object CanonicalSessionCodec {
    fun encode(payload: SessionPayload): String = buildString {
        field("protocol_version", payload.protocolVersion.toString())
        field("installation_id", payload.installationId)
        field("session_id", payload.sessionId)
        field("sequence", payload.sequence.toString())
        field("started_at_epoch_ms", payload.startedAtEpochMs.toString())
        field("finished_at_epoch_ms", payload.finishedAtEpochMs.toString())
        field("duration_ms", payload.durationMs.toString())
        field("score", payload.score.toString())
        field("app_version", payload.appVersion)
    }

    fun bytes(payload: SessionPayload): ByteArray =
        encode(payload).toByteArray(StandardCharsets.UTF_8)

    private fun StringBuilder.field(name: String, value: String) {
        val byteLength = value.toByteArray(StandardCharsets.UTF_8).size
        append(name)
            .append(':')
            .append(byteLength)
            .append(':')
            .append(value)
            .append('\n')
    }
}
