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
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

class SqliteServerPersistenceTest {
    @Test
    fun acceptedSessionSurvivesRestartAndStillBlocksReplay() {
        val dbPath = Files.createTempDirectory("monetizei-sqlite-test").resolve("monetizei.db")
        val fixture = CryptoFixture()
        val registration = fixture.registration()
        val envelope = fixture.envelope(sequence = 1L, score = 34)

        SqliteServerPersistence(dbPath).use { persistence ->
            val service = SessionIngestService(persistence = persistence)
            assertEquals(RegistrationResult.CREATED, service.register(registration))
            assertTrue(service.submit(envelope, 50_000L).accepted)
            assertEquals(1, service.ledgerSnapshot().size)
        }

        SqliteServerPersistence(dbPath).use { persistence ->
            val restarted = SessionIngestService(persistence = persistence)
            assertEquals(RegistrationResult.ALREADY_REGISTERED, restarted.register(registration))
            assertEquals(1, restarted.ledgerSnapshot().size)

            val replay = restarted.submit(envelope, 80_000L)
            assertFalse(replay.accepted)
            assertEquals(IngestRejectReason.REPLAY, replay.rejectReason)

            val next = restarted.submit(fixture.envelope(sequence = 2L, score = 35), 80_100L)
            assertTrue(next.accepted)
            assertEquals(2, restarted.ledgerSnapshot().size)
        }

        SqliteServerPersistence(dbPath).use { persistence ->
            assertEquals(1, persistence.loadRegistrations().size)
            assertEquals(2, persistence.loadLedgerEntries().size)
        }
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
            appVersion = "0.4.0",
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
                appVersion = "0.4.0"
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
