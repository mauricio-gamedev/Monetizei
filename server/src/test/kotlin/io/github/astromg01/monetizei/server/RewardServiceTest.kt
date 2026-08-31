package io.github.astromg01.monetizei.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RewardServiceTest {
    @Test
    fun createsPendingRewardsWithinBudgetAndMovesThroughLifecycle() {
        val persistence = MemoryRewardPersistence()
        val service = RewardService(
            persistence = persistence,
            policy = RewardPolicy(
                rewardCentsPerEligibleSession = 2,
                dailyBudgetCents = 10,
                minVerifiedScore = 20,
                maxRewardsPerInstallationPerUtcDay = 2
            )
        )
        val installation = UUID.randomUUID().toString()

        val first = service.evaluateAcceptedGameplay(gameplay(installation, 1, 30, 1_000L))
        val second = service.evaluateAcceptedGameplay(gameplay(installation, 2, 40, 2_000L))
        val third = service.evaluateAcceptedGameplay(gameplay(installation, 3, 50, 3_000L))

        assertEquals(RewardDecisionCode.PENDING_CREATED, first.code)
        assertEquals(RewardDecisionCode.PENDING_CREATED, second.code)
        assertEquals(RewardDecisionCode.INSTALLATION_DAILY_LIMIT, third.code)
        assertEquals(RewardCurrency.BRL, first.currency)
        assertEquals(4L, service.wallet(installation).brl.pendingCents)

        val rewardId = first.rewardId!!
        assertTrue(service.approve(rewardId, 4_000L))
        assertEquals(2L, service.wallet(installation).brl.pendingCents)
        assertEquals(2L, service.wallet(installation).brl.approvedCents)
        assertTrue(service.makeAvailable(rewardId, 5_000L))
        assertEquals(2L, service.wallet(installation).brl.availableCents)
        assertFalse(service.makeAvailable(rewardId, 6_000L))
    }

    @Test
    fun usdPolicyCreatesUsdBalanceWithoutChangingBrlBalance() {
        val service = RewardService(
            policy = RewardPolicy(
                rewardCentsPerEligibleSession = 5,
                dailyBudgetCents = 20,
                minVerifiedScore = 20,
                maxRewardsPerInstallationPerUtcDay = 2,
                currency = RewardCurrency.USD
            )
        )
        val installation = UUID.randomUUID().toString()

        val result = service.evaluateAcceptedGameplay(gameplay(installation, 1, 42, 1_000L))
        val wallet = result.wallet

        assertEquals(RewardDecisionCode.PENDING_CREATED, result.code)
        assertEquals(RewardCurrency.USD, result.currency)
        assertEquals(0L, wallet.brl.totalCents)
        assertEquals(5L, wallet.usd.pendingCents)
        assertEquals(5L, wallet.usd.totalCents)
    }

    @Test
    fun disabledPolicyCreatesNoFinancialLiability() {
        val service = RewardService(policy = RewardPolicy())
        val installation = UUID.randomUUID().toString()
        val result = service.evaluateAcceptedGameplay(gameplay(installation, 1, 99, 1_000L))

        assertEquals(RewardDecisionCode.DISABLED, result.code)
        assertEquals(0L, result.wallet.brl.totalCents)
        assertEquals(0L, result.wallet.usd.totalCents)
        assertTrue(service.snapshot().isEmpty())
    }

    @Test
    fun sameGameplayLedgerCannotCreateRewardTwice() {
        val persistence = MemoryRewardPersistence()
        val service = RewardService(
            persistence,
            RewardPolicy(1, 100, 1, 100)
        )
        val installation = UUID.randomUUID().toString()
        val gameplay = gameplay(installation, 1, 30, 1_000L)

        val first = service.evaluateAcceptedGameplay(gameplay)
        val second = service.evaluateAcceptedGameplay(gameplay)

        assertEquals(RewardDecisionCode.PENDING_CREATED, first.code)
        assertEquals(RewardDecisionCode.ALREADY_EVALUATED, second.code)
        assertEquals(first.rewardId, second.rewardId)
        assertEquals(1, service.snapshot().size)
    }

    private fun gameplay(
        installationId: String,
        sequence: Long,
        score: Long,
        acceptedAt: Long
    ) = GameplayLedgerEntry(
        ledgerId = UUID.randomUUID().toString(),
        installationId = installationId,
        sessionId = UUID.randomUUID().toString(),
        sequence = sequence,
        startedAtEpochMs = acceptedAt - 30_000L,
        finishedAtEpochMs = acceptedAt,
        durationMs = 30_000L,
        verifiedScoreUnits = score,
        appVersion = "0.5.1",
        acceptedAtEpochMs = acceptedAt
    )

    private class MemoryRewardPersistence : RewardPersistence {
        val entries = mutableListOf<RewardLedgerEntry>()

        override fun loadRewardEntries(): List<RewardLedgerEntry> = entries.toList()

        override fun saveRewardEntry(entry: RewardLedgerEntry): Boolean {
            if (entries.any { it.gameplayLedgerId == entry.gameplayLedgerId }) return false
            entries += entry
            return true
        }

        override fun updateRewardState(
            rewardId: String,
            expectedState: RewardState,
            newState: RewardState,
            updatedAtEpochMs: Long
        ): Boolean {
            val index = entries.indexOfFirst { it.rewardId == rewardId && it.state == expectedState }
            if (index < 0) return false
            entries[index] = entries[index].copy(state = newState, updatedAtEpochMs = updatedAtEpochMs)
            return true
        }
    }
}
