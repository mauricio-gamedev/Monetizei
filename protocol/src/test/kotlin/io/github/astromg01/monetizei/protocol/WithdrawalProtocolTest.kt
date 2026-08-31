package io.github.astromg01.monetizei.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WithdrawalProtocolTest {
    @Test
    fun withdrawalEnvelopeRoundTripsAndCanonicalizes() {
        val payload = WithdrawalPayload(
            protocolVersion = WithdrawalProtocol.VERSION,
            installationId = "11111111-1111-1111-1111-111111111111",
            requestId = "22222222-2222-2222-2222-222222222222",
            currency = "BRL",
            requestedAtEpochMs = 123456789L,
            appVersion = "0.6.0"
        )
        val envelope = SignedWithdrawalEnvelope(
            payload = payload,
            keyId = "key-1",
            signatureAlgorithm = SessionProtocol.SIGNATURE_ALGORITHM,
            signatureBase64 = "signature"
        )

        val decoded = ProtocolJson.decodeWithdrawal(ProtocolJson.encodeWithdrawal(envelope))
        assertEquals(envelope, decoded)

        val canonical = CanonicalWithdrawalCodec.bytes(payload).toString(Charsets.UTF_8)
        assertTrue(canonical.contains("installationId=${payload.installationId}"))
        assertTrue(canonical.contains("requestId=${payload.requestId}"))
        assertTrue(canonical.contains("currency=BRL"))
        assertTrue(canonical.endsWith("appVersion=0.6.0"))
    }
}
