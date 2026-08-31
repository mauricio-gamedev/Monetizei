package io.github.astromg01.monetizei.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolJsonTest {
    @Test
    fun registration_roundTrips() {
        val original = InstallationRegistration(1, "123e4567-e89b-12d3-a456-426614174000", "key", "pub", "SHA256withECDSA", "0.3.0", 123L)
        assertEquals(original, ProtocolJson.decodeRegistration(ProtocolJson.encodeRegistration(original)))
    }

    @Test
    fun signedSession_roundTrips() {
        val payload = SessionPayload(1, "123e4567-e89b-12d3-a456-426614174000", "123e4567-e89b-12d3-a456-426614174001", 7L, 100L, 30_100L, 30_000L, 42, "0.3.0")
        val original = SignedSessionEnvelope(payload, "key", "SHA256withECDSA", "sig")
        assertEquals(original, ProtocolJson.decodeSession(ProtocolJson.encodeSession(original)))
    }
}
