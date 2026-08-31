package io.github.astromg01.monetizei.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CanonicalSessionCodecTest {
    private val payload = SessionPayload(
        protocolVersion = SessionProtocol.VERSION,
        installationId = "11111111-1111-1111-1111-111111111111",
        sessionId = "22222222-2222-2222-2222-222222222222",
        sequence = 7L,
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 31_000L,
        durationMs = SessionProtocol.SESSION_DURATION_MS,
        score = 42,
        appVersion = "0.2.0"
    )

    @Test
    fun canonicalEncodingIsDeterministic() {
        assertEquals(
            CanonicalSessionCodec.encode(payload),
            CanonicalSessionCodec.encode(payload.copy())
        )
    }

    @Test
    fun changingSignedFieldChangesCanonicalBytes() {
        assertNotEquals(
            CanonicalSessionCodec.encode(payload),
            CanonicalSessionCodec.encode(payload.copy(score = 43))
        )
    }
}
