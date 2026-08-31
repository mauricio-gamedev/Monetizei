package io.github.astromg01.monetizei.server

import io.github.astromg01.monetizei.protocol.InstallationRegistration
import io.github.astromg01.monetizei.protocol.SessionProtocol
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlitePayoutPersistenceTest {
    @Test
    fun payoutReservationAndLedgerSurviveRestart() {
        val db = Files.createTempDirectory("monetizei-payout-test").resolve("payout.db")
        val installationId = UUID.randomUUID().toString()
        val gameplayId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val rewardId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()

        SqliteServerPersistence(db).use { persistence ->
            assertTrue(
                persistence.saveRegistration(
                    InstallationRegistration(
                        protocolVersion = SessionProtocol.VERSION,
                        installationId = installationId,
                        keyId = "test-key",
                        publicKeyBase64 = "test-key",
                        signatureAlgorithm = SessionProtocol.SIGNATURE_ALGORITHM,
                        appVersion = "0.6.0",
                        createdAtEpochMs = 1_000L
                    )
                )
            )
            assertTrue(
                persistence.saveLedgerEntry(
                    GameplayLedgerEntry(
                        ledgerId = gameplayId,
                        installationId = installationId,
                        sessionId = sessionId,
                        sequence = 1L,
                        startedAtEpochMs = 10_000L,
                        finishedAtEpochMs = 40_000L,
                        durationMs = SessionProtocol.SESSION_DURATION_MS,
                        verifiedScoreUnits = 30L,
                        appVersion = "0.6.0",
                        acceptedAtEpochMs = 50_000L
                    )
                )
            )
            assertTrue(
                persistence.saveRewardEntry(
                    RewardLedgerEntry(
                        rewardId = rewardId,
                        installationId = installationId,
                        gameplayLedgerId = gameplayId,
                        sessionId = sessionId,
                        amountCents = 1L,
                        currency = RewardCurrency.BRL,
                        state = RewardState.AVAILABLE,
                        policyCode = "test",
                        createdAtEpochMs = 50_000L,
                        updatedAtEpochMs = 50_000L
                    )
                )
            )
            assertTrue(
                persistence.savePayout(
                    PayoutLedgerEntry(
                        requestId = requestId,
                        installationId = installationId,
                        currency = RewardCurrency.BRL,
                        amountCents = 1L,
                        state = PayoutState.REQUESTED,
                        provider = "paypal",
                        createdAtEpochMs = 60_000L,
                        updatedAtEpochMs = 60_000L
                    )
                )
            )
            assertTrue(persistence.savePayoutReward(requestId, rewardId))
            assertTrue(
                persistence.updateRewardState(
                    rewardId,
                    RewardState.AVAILABLE,
                    RewardState.PAYOUT_PENDING,
                    60_000L
                )
            )
        }

        SqliteServerPersistence(db).use { persistence ->
            assertEquals(RewardState.PAYOUT_PENDING, persistence.loadRewardEntries().single().state)
            assertEquals(listOf(rewardId), persistence.loadPayoutRewardIds(requestId))
            assertEquals(PayoutState.REQUESTED, persistence.loadPayout(requestId)?.state)
            assertTrue(
                persistence.updatePayout(
                    requestId,
                    PayoutState.REQUESTED,
                    PayoutState.SUBMITTED,
                    "paypal-batch-1",
                    null,
                    70_000L
                )
            )
        }

        SqliteServerPersistence(db).use { persistence ->
            val payout = persistence.loadPayout(requestId)!!
            assertEquals(PayoutState.SUBMITTED, payout.state)
            assertEquals("paypal-batch-1", payout.providerBatchId)
        }
    }
}
