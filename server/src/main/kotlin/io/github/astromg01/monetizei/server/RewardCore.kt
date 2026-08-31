package io.github.astromg01.monetizei.server

import java.util.UUID

enum class RewardState {
    PENDING,
    APPROVED,
    AVAILABLE
}

data class RewardLedgerEntry(
    val rewardId: String,
    val installationId: String,
    val gameplayLedgerId: String,
    val sessionId: String,
    val amountCents: Long,
    val state: RewardState,
    val policyCode: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

data class RewardWalletSnapshot(
    val pendingCents: Long = 0,
    val approvedCents: Long = 0,
    val availableCents: Long = 0
) {
    val totalCents: Long get() = pendingCents + approvedCents + availableCents
}

enum class RewardDecisionCode {
    PENDING_CREATED,
    ALREADY_EVALUATED,
    DISABLED,
    NOT_ELIGIBLE,
    INSTALLATION_DAILY_LIMIT,
    DAILY_BUDGET_EXHAUSTED,
    STORAGE_FAILURE
}

data class RewardDecision(
    val code: RewardDecisionCode,
    val rewardId: String? = null,
    val amountCents: Long = 0,
    val wallet: RewardWalletSnapshot = RewardWalletSnapshot()
)

interface RewardPersistence {
    fun loadRewardEntries(): List<RewardLedgerEntry>
    fun saveRewardEntry(entry: RewardLedgerEntry): Boolean
    fun updateRewardState(
        rewardId: String,
        expectedState: RewardState,
        newState: RewardState,
        updatedAtEpochMs: Long
    ): Boolean
}

object NoopRewardPersistence : RewardPersistence {
    override fun loadRewardEntries() = emptyList<RewardLedgerEntry>()
    override fun saveRewardEntry(entry: RewardLedgerEntry) = true
    override fun updateRewardState(
        rewardId: String,
        expectedState: RewardState,
        newState: RewardState,
        updatedAtEpochMs: Long
    ) = true
}

data class RewardPolicy(
    val rewardCentsPerEligibleSession: Long = 0,
    val dailyBudgetCents: Long = 0,
    val minVerifiedScore: Long = 20,
    val maxRewardsPerInstallationPerUtcDay: Int = 10
) {
    init {
        require(rewardCentsPerEligibleSession >= 0)
        require(dailyBudgetCents >= 0)
        require(minVerifiedScore >= 0)
        require(maxRewardsPerInstallationPerUtcDay >= 0)
    }

    val enabled: Boolean
        get() = rewardCentsPerEligibleSession > 0 &&
            dailyBudgetCents > 0 &&
            maxRewardsPerInstallationPerUtcDay > 0
}

class RewardService(
    private val persistence: RewardPersistence = NoopRewardPersistence,
    private val policy: RewardPolicy = RewardPolicy(),
    initialEntries: Iterable<RewardLedgerEntry> = persistence.loadRewardEntries()
) {
    private val entries = initialEntries.toMutableList()

    @Synchronized
    fun evaluateAcceptedGameplay(entry: GameplayLedgerEntry): RewardDecision {
        val existing = entries.firstOrNull { it.gameplayLedgerId == entry.ledgerId }
        if (existing != null) {
            return RewardDecision(
                code = RewardDecisionCode.ALREADY_EVALUATED,
                rewardId = existing.rewardId,
                amountCents = existing.amountCents,
                wallet = wallet(entry.installationId)
            )
        }

        if (!policy.enabled) {
            return decision(RewardDecisionCode.DISABLED, entry.installationId)
        }
        if (entry.verifiedScoreUnits < policy.minVerifiedScore) {
            return decision(RewardDecisionCode.NOT_ELIGIBLE, entry.installationId)
        }

        val day = utcDay(entry.acceptedAtEpochMs)
        val sameDay = entries.filter { utcDay(it.createdAtEpochMs) == day }
        val installationRewardCount = sameDay.count { it.installationId == entry.installationId }
        if (installationRewardCount >= policy.maxRewardsPerInstallationPerUtcDay) {
            return decision(RewardDecisionCode.INSTALLATION_DAILY_LIMIT, entry.installationId)
        }

        val committedToday = sameDay.sumOf { it.amountCents }
        val amount = policy.rewardCentsPerEligibleSession
        if (committedToday + amount > policy.dailyBudgetCents) {
            return decision(RewardDecisionCode.DAILY_BUDGET_EXHAUSTED, entry.installationId)
        }

        val reward = RewardLedgerEntry(
            rewardId = UUID.randomUUID().toString(),
            installationId = entry.installationId,
            gameplayLedgerId = entry.ledgerId,
            sessionId = entry.sessionId,
            amountCents = amount,
            state = RewardState.PENDING,
            policyCode = "verified_gameplay_v1",
            createdAtEpochMs = entry.acceptedAtEpochMs,
            updatedAtEpochMs = entry.acceptedAtEpochMs
        )
        if (!persistence.saveRewardEntry(reward)) {
            return decision(RewardDecisionCode.STORAGE_FAILURE, entry.installationId)
        }
        entries += reward
        return RewardDecision(
            code = RewardDecisionCode.PENDING_CREATED,
            rewardId = reward.rewardId,
            amountCents = reward.amountCents,
            wallet = wallet(entry.installationId)
        )
    }

    @Synchronized
    fun wallet(installationId: String): RewardWalletSnapshot {
        var pending = 0L
        var approved = 0L
        var available = 0L
        entries.asSequence()
            .filter { it.installationId == installationId }
            .forEach { reward ->
                when (reward.state) {
                    RewardState.PENDING -> pending += reward.amountCents
                    RewardState.APPROVED -> approved += reward.amountCents
                    RewardState.AVAILABLE -> available += reward.amountCents
                }
            }
        return RewardWalletSnapshot(pending, approved, available)
    }

    @Synchronized
    fun approve(rewardId: String, nowEpochMs: Long): Boolean =
        transition(rewardId, RewardState.PENDING, RewardState.APPROVED, nowEpochMs)

    @Synchronized
    fun makeAvailable(rewardId: String, nowEpochMs: Long): Boolean =
        transition(rewardId, RewardState.APPROVED, RewardState.AVAILABLE, nowEpochMs)

    @Synchronized
    fun snapshot(): List<RewardLedgerEntry> = entries.toList()

    private fun transition(
        rewardId: String,
        expected: RewardState,
        target: RewardState,
        nowEpochMs: Long
    ): Boolean {
        val index = entries.indexOfFirst { it.rewardId == rewardId && it.state == expected }
        if (index < 0) return false
        if (!persistence.updateRewardState(rewardId, expected, target, nowEpochMs)) return false
        entries[index] = entries[index].copy(state = target, updatedAtEpochMs = nowEpochMs)
        return true
    }

    private fun decision(code: RewardDecisionCode, installationId: String) =
        RewardDecision(code = code, wallet = wallet(installationId))

    private fun utcDay(epochMs: Long): Long = epochMs / DAY_MS

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
