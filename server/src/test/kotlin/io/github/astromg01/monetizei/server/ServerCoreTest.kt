package io.github.astromg01.monetizei.server

import io.github.astromg01.monetizei.protocol.CanonicalSessionCodec
import io.github.astromg01.monetizei.protocol.InstallationRegistration
import io.github.astromg01.monetizei.protocol.KeyIds
import io.github.astromg01.monetizei.protocol.SessionPayload
import io.github.astromg01.monetizei.protocol.SessionProtocol
import io.github.astromg01.monetizei.protocol.SignedSessionEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

class ServerCoreTest {
    @Test
    fun acceptsValidSignedSessionAndRejectsReplay() {
        val fixture = CryptoFixture()
        val service = SessionIngestService()
        assertEquals(RegistrationResult.CREATED, service.register(fixture.registration()))

        val envelope = fixture.envelope(sequence = 1L, score = 35)
        val first = service.submit(envelope, receivedAtEpochMs = 50_000L)
        val replay = service.submit(envelope, receivedAtEpochMs = 50_100L)

        assertTrue(first.accepted)
        assertFalse(replay.accepted)
        assertEquals(IngestRejectReason.REPLAY, replay.rejectReason)
        assertEquals(1, service.ledgerSnapshot().size)
    }

    @Test
    fun rejectsTamperedPayloadWithOriginalSignature() {
        val fixture = CryptoFixture()
        val service = SessionIngestService()
        service.register(fixture.registration())

        val original = fixture.envelope(sequence = 1L, score = 35)
        val tampered = original.copy(payload = original.payload.copy(score = 36))
        val result = service.submit(tampered, receivedAtEpochMs = 50_000L)

        assertFalse(result.accepted)
        assertEquals(IngestRejectReason.INVALID_SIGNATURE, result.rejectReason)
        assertTrue(service.ledgerSnapshot().isEmpty())
    }

    @Test
    fun rateLimitPreventsExcessAcceptedSessions() {
        val fixture = CryptoFixture()
        val service = SessionIngestService(
            rateLimiter = SessionRateLimiter(maxAcceptedSessions = 2, windowMs = 60_000L)
        )
        service.register(fixture.registration())

        assertTrue(service.submit(fixture.envelope(1L, 10), 100_000L).accepted)
        assertTrue(service.submit(fixture.envelope(2L, 11), 100_100L).accepted)

        val third = service.submit(fixture.envelope(3L, 12), 100_200L)
        assertFalse(third.accepted)
        assertEquals(IngestRejectReason.RATE_LIMITED, third.rejectReason)
        assertEquals(2, service.ledgerSnapshot().size)
    }

    private class CryptoFixture {
        private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        private val installationId = UUID.randomUUID().toString()
        private val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        private val keyId = KeyIds.fromEncodedPublicKey(keyPair.public.encoded)

        fun registration() = InstallationRegistration(
            protocolVersion = SessionProtocol.VERSION,
            installationId = installationId,
            keyId = keyId,
            publicKeyBase64 = publicKeyBase64,
            signatureAlgorithm = SessionProtocol.SIGNATURE_ALGORITHM,
            appVersion = "0.2.0",
            createdAtEpochMs = 1_000L
        )

        fun envelope(sequence: Long, score: Int): SignedSessionEnvelope {
            val payload = SessionPayload(
                protocolVersion = SessionProtocol.VERSION,
                installationId = installationId,
                sessionId = UUID.randomUUID().toString(),
                sequence = sequence,
                startedAtEpochMs = 10_000L + sequence,
                finishedAtEpochMs = 40_000L + sequence,
                durationMs = SessionProtocol.SESSION_DURATION_MS,
                score = score,
                appVersion = "0.2.0"
            )
            val signatureBase64 = Signature.getInstance(SessionProtocol.SIGNATURE_ALGORITHM).run {
                initSign(keyPair.private)
                update(CanonicalSessionCodec.bytes(payload))
                Base64.getEncoder().encodeToString(sign())
            }
            return SignedSessionEnvelope(
                payload = payload,
                keyId = keyId,
                signatureAlgorithm = SessionProtocol.SIGNATURE_ALGORITHM,
                signatureBase64 = signatureBase64
            )
        }
    }
}
