package io.github.astromg01.monetizei.server

import io.github.astromg01.monetizei.protocol.InstallationRegistration
import io.github.astromg01.monetizei.protocol.SessionProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class SqliteRewardPersistenceTest {
    @Test
    fun rewardWalletSurvivesDatabaseRestart() {
        val db = Files.createTempDirectory("monetizei-reward-test").resolve("reward.db")
        val installationId = UUID.randomUUID().toString()
        val gameplay = gameplay(installationId)

        SqliteServerPersistence(db).use { persistence ->
            assertTrue(persistence.saveRegistration(registration(installationId)))
            assertTrue(persistence.saveLedgerEntry(gameplay))
            val service = RewardService(
                persistence = persistence,
                policy = RewardPolicy(3, 100, 20, 10)
            )
            val decision = service.evaluateAcceptedGameplay(gameplay)
            assertEquals(RewardDecisionCode.PENDING_CREATED, decision.code)
            assertEquals(3L, decision.wallet.pendingCents)
            assertTrue(service.approve(decision.rewardId!!, 60_000L))
        }

        SqliteServerPersistence(db).use { persistence ->
            val restored = RewardService(
                persistence = persistence,
                policy = RewardPolicy(3, 100, 20, 10)
            )
            val wallet = restored.wallet(installationId)
            assertEquals(0L, wallet.pendingCents)
            assertEquals(3L, wallet.approvedCents)
            assertEquals(0L, wallet.availableCents)
            val rewardId = restored.snapshot().single().rewardId
            assertTrue(restored.makeAvailable(rewardId, 120_000L))
        }

        SqliteServerPersistence(db).use { persistence ->
            val restored = RewardService(persistence = persistence)
            assertEquals(3L, restored.wallet(installationId).availableCents)
        }
    }

    private fun registration(installationId: String) = InstallationRegistration(
        protocolVersion = SessionProtocol.VERSION,
        installationId = installationId,
        keyId = "test-key",
        publicKeyBase64 = "test-key",
        signatureAlgorithm = SessionProtocol.SIGNATURE_ALGORITHM,
        appVersion = "0.5.0",
        createdAtEpochMs = 1_000L
    )

    private fun gameplay(installationId: String) = GameplayLedgerEntry(
        ledgerId = UUID.randomUUID().toString(),
        installationId = installationId,
        sessionId = UUID.randomUUID().toString(),
        sequence = 1L,
        startedAtEpochMs = 10_000L,
        finishedAtEpochMs = 40_000L,
        durationMs = SessionProtocol.SESSION_DURATION_MS,
        verifiedScoreUnits = 35L,
        appVersion = "0.5.0",
        acceptedAtEpochMs = 50_000L
    )
}
