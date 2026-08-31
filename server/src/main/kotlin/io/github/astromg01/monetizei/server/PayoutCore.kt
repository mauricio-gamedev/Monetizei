package io.github.astromg01.monetizei.server

import io.github.astromg01.monetizei.protocol.CanonicalWithdrawalCodec
import io.github.astromg01.monetizei.protocol.SessionProtocol
import io.github.astromg01.monetizei.protocol.SignedWithdrawalEnvelope
import io.github.astromg01.monetizei.protocol.WithdrawalProtocol
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID


enum class PayoutState {
    REQUESTED,
    SUBMITTED,
    PAID,
    FAILED
}

data class PayoutLedgerEntry(
    val requestId: String,
    val installationId: String,
    val currency: RewardCurrency,
    val amountCents: Long,
    val state: PayoutState,
    val provider: String,
    val providerBatchId: String? = null,
    val failureCode: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

interface PayoutPersistence {
    fun loadPayout(requestId: String): PayoutLedgerEntry?
    fun savePayout(entry: PayoutLedgerEntry): Boolean
    fun updatePayout(
        requestId: String,
        expectedState: PayoutState,
        newState: PayoutState,
        providerBatchId: String?,
        failureCode: String?,
        updatedAtEpochMs: Long
    ): Boolean
    fun savePayoutReward(requestId: String, rewardId: String): Boolean
    fun loadPayoutRewardIds(requestId: String): List<String>
}

object NoopPayoutPersistence : PayoutPersistence {
    private val entries = mutableMapOf<String, PayoutLedgerEntry>()
    private val rewards = mutableMapOf<String, MutableList<String>>()

    override fun loadPayout(requestId: String): PayoutLedgerEntry? = entries[requestId]

    override fun savePayout(entry: PayoutLedgerEntry): Boolean {
        if (entries.containsKey(entry.requestId)) return false
        entries[entry.requestId] = entry
        return true
    }

    override fun updatePayout(
        requestId: String,
        expectedState: PayoutState,
        newState: PayoutState,
        providerBatchId: String?,
        failureCode: String?,
        updatedAtEpochMs: Long
    ): Boolean {
        val existing = entries[requestId] ?: return false
        if (existing.state != expectedState) return false
        entries[requestId] = existing.copy(
            state = newState,
            providerBatchId = providerBatchId ?: existing.providerBatchId,
            failureCode = failureCode,
            updatedAtEpochMs = updatedAtEpochMs
        )
        return true
    }

    override fun savePayoutReward(requestId: String, rewardId: String): Boolean {
        val list = rewards.getOrPut(requestId) { mutableListOf() }
        if (rewardId in list) return false
        list += rewardId
        return true
    }

    override fun loadPayoutRewardIds(requestId: String): List<String> = rewards[requestId]?.toList().orEmpty()
}

enum class ProviderPayoutState {
    PENDING,
    SUCCESS,
    FAILED
}

data class ProviderSubmitResult(
    val accepted: Boolean,
    val providerBatchId: String? = null,
    val failureCode: String? = null
)

data class ProviderStatusResult(
    val state: ProviderPayoutState,
    val failureCode: String? = null
)

interface PayoutGateway {
    val providerName: String
    val enabled: Boolean
    fun submit(requestId: String, currency: RewardCurrency, amountCents: Long): ProviderSubmitResult
    fun status(providerBatchId: String): ProviderStatusResult
}

object DisabledPayoutGateway : PayoutGateway {
    override val providerName: String = "paypal"
    override val enabled: Boolean = false
    override fun submit(requestId: String, currency: RewardCurrency, amountCents: Long) =
        ProviderSubmitResult(false, failureCode = "PROVIDER_DISABLED")
    override fun status(providerBatchId: String) =
        ProviderStatusResult(ProviderPayoutState.FAILED, "PROVIDER_DISABLED")
}

enum class WithdrawalResultCode {
    SUBMITTED,
    PROCESSING,
    PAID,
    FAILED,
    NO_AVAILABLE,
    PROVIDER_DISABLED,
    INVALID,
    UNKNOWN_INSTALLATION,
    KEY_MISMATCH,
    INVALID_SIGNATURE,
    STORAGE_FAILURE
}

data class WithdrawalResult(
    val code: WithdrawalResultCode,
    val requestId: String? = null,
    val amountCents: Long = 0,
    val currency: RewardCurrency = RewardCurrency.BRL,
    val providerBatchId: String? = null,
    val failureCode: String? = null,
    val wallet: RewardWalletSnapshot = RewardWalletSnapshot()
)

class WithdrawalService(
    private val rewardService: RewardService,
    private val persistence: PayoutPersistence = NoopPayoutPersistence,
    private val registrationLookup: (String) -> io.github.astromg01.monetizei.protocol.InstallationRegistration?,
    private val gateway: PayoutGateway = DisabledPayoutGateway
) {
    @Synchronized
    fun request(envelope: SignedWithdrawalEnvelope, nowEpochMs: Long): WithdrawalResult {
        val payload = envelope.payload
        val currency = runCatching { RewardCurrency.valueOf(payload.currency.uppercase()) }.getOrNull()
            ?: return WithdrawalResult(WithdrawalResultCode.INVALID)
        if (!validPayload(envelope, nowEpochMs)) {
            return WithdrawalResult(WithdrawalResultCode.INVALID, currency = currency)
        }

        val registration = registrationLookup(payload.installationId)
            ?: return result(WithdrawalResultCode.UNKNOWN_INSTALLATION, payload.installationId, currency)
        if (registration.keyId != envelope.keyId) {
            return result(WithdrawalResultCode.KEY_MISMATCH, payload.installationId, currency)
        }
        if (!verify(envelope, registration.publicKeyBase64)) {
            return result(WithdrawalResultCode.INVALID_SIGNATURE, payload.installationId, currency)
        }

        val existing = persistence.loadPayout(payload.requestId)
        if (existing != null) {
            if (existing.installationId != payload.installationId || existing.currency != currency) {
                return result(WithdrawalResultCode.INVALID, payload.installationId, currency)
            }
            return continueExisting(existing, nowEpochMs)
        }

        if (!gateway.enabled) {
            return result(WithdrawalResultCode.PROVIDER_DISABLED, payload.installationId, currency)
        }

        val rewards = rewardService.snapshot()
            .filter {
                it.installationId == payload.installationId &&
                    it.currency == currency &&
                    it.state == RewardState.AVAILABLE
            }
            .sortedWith(compareBy<RewardLedgerEntry> { it.createdAtEpochMs }.thenBy { it.rewardId })
        if (rewards.isEmpty()) {
            return result(WithdrawalResultCode.NO_AVAILABLE, payload.installationId, currency)
        }

        val payout = PayoutLedgerEntry(
            requestId = payload.requestId,
            installationId = payload.installationId,
            currency = currency,
            amountCents = rewards.sumOf { it.amountCents },
            state = PayoutState.REQUESTED,
            provider = gateway.providerName,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs
        )
        if (!persistence.savePayout(payout)) {
            return result(WithdrawalResultCode.STORAGE_FAILURE, payload.installationId, currency)
        }

        val reserved = mutableListOf<RewardLedgerEntry>()
        for (reward in rewards) {
            if (!persistence.savePayoutReward(payload.requestId, reward.rewardId) ||
                !rewardService.reserveForPayout(reward.rewardId, nowEpochMs)
            ) {
                reserved.forEach { rewardService.releasePayout(it.rewardId, nowEpochMs) }
                persistence.updatePayout(
                    payload.requestId,
                    PayoutState.REQUESTED,
                    PayoutState.FAILED,
                    null,
                    "STORAGE_FAILURE",
                    nowEpochMs
                )
                return result(WithdrawalResultCode.STORAGE_FAILURE, payload.installationId, currency)
            }
            reserved += reward
        }

        return submit(payout, nowEpochMs)
    }

    private fun continueExisting(entry: PayoutLedgerEntry, nowEpochMs: Long): WithdrawalResult = when (entry.state) {
        PayoutState.REQUESTED -> submit(entry, nowEpochMs)
        PayoutState.SUBMITTED -> refresh(entry, nowEpochMs)
        PayoutState.PAID -> result(
            WithdrawalResultCode.PAID,
            entry.installationId,
            entry.currency,
            entry.requestId,
            entry.amountCents,
            entry.providerBatchId
        )
        PayoutState.FAILED -> result(
            WithdrawalResultCode.FAILED,
            entry.installationId,
            entry.currency,
            entry.requestId,
            entry.amountCents,
            entry.providerBatchId,
            entry.failureCode
        )
    }

    private fun submit(entry: PayoutLedgerEntry, nowEpochMs: Long): WithdrawalResult {
        val provider = gateway.submit(entry.requestId, entry.currency, entry.amountCents)
        if (!provider.accepted || provider.providerBatchId.isNullOrBlank()) {
            releaseRewards(entry.requestId, nowEpochMs)
            persistence.updatePayout(
                entry.requestId,
                PayoutState.REQUESTED,
                PayoutState.FAILED,
                provider.providerBatchId,
                provider.failureCode ?: "PAYOUT_REJECTED",
                nowEpochMs
            )
            return result(
                WithdrawalResultCode.FAILED,
                entry.installationId,
                entry.currency,
                entry.requestId,
                entry.amountCents,
                provider.providerBatchId,
                provider.failureCode
            )
        }

        if (!persistence.updatePayout(
                entry.requestId,
                PayoutState.REQUESTED,
                PayoutState.SUBMITTED,
                provider.providerBatchId,
                null,
                nowEpochMs
            )
        ) {
            return result(WithdrawalResultCode.STORAGE_FAILURE, entry.installationId, entry.currency)
        }

        return result(
            WithdrawalResultCode.SUBMITTED,
            entry.installationId,
            entry.currency,
            entry.requestId,
            entry.amountCents,
            provider.providerBatchId
        )
    }

    private fun refresh(entry: PayoutLedgerEntry, nowEpochMs: Long): WithdrawalResult {
        val providerBatchId = entry.providerBatchId
            ?: return result(WithdrawalResultCode.STORAGE_FAILURE, entry.installationId, entry.currency)
        return when (val provider = gateway.status(providerBatchId)) {
            is ProviderStatusResult -> when (provider.state) {
                ProviderPayoutState.PENDING -> result(
                    WithdrawalResultCode.PROCESSING,
                    entry.installationId,
                    entry.currency,
                    entry.requestId,
                    entry.amountCents,
                    providerBatchId
                )
                ProviderPayoutState.SUCCESS -> {
                    if (!markRewardsPaid(entry.requestId, nowEpochMs) ||
                        !persistence.updatePayout(
                            entry.requestId,
                            PayoutState.SUBMITTED,
                            PayoutState.PAID,
                            providerBatchId,
                            null,
                            nowEpochMs
                        )
                    ) {
                        result(WithdrawalResultCode.STORAGE_FAILURE, entry.installationId, entry.currency)
                    } else {
                        result(
                            WithdrawalResultCode.PAID,
                            entry.installationId,
                            entry.currency,
                            entry.requestId,
                            entry.amountCents,
                            providerBatchId
                        )
                    }
                }
                ProviderPayoutState.FAILED -> {
                    releaseRewards(entry.requestId, nowEpochMs)
                    persistence.updatePayout(
                        entry.requestId,
                        PayoutState.SUBMITTED,
                        PayoutState.FAILED,
                        providerBatchId,
                        provider.failureCode ?: "PAYOUT_FAILED",
                        nowEpochMs
                    )
                    result(
                        WithdrawalResultCode.FAILED,
                        entry.installationId,
                        entry.currency,
                        entry.requestId,
                        entry.amountCents,
                        providerBatchId,
                        provider.failureCode
                    )
                }
            }
        }
    }

    private fun markRewardsPaid(requestId: String, nowEpochMs: Long): Boolean {
        val ids = persistence.loadPayoutRewardIds(requestId)
        return ids.isNotEmpty() && ids.all { rewardService.markPaid(it, nowEpochMs) }
    }

    private fun releaseRewards(requestId: String, nowEpochMs: Long) {
        persistence.loadPayoutRewardIds(requestId).forEach { rewardService.releasePayout(it, nowEpochMs) }
    }

    private fun result(
        code: WithdrawalResultCode,
        installationId: String,
        currency: RewardCurrency,
        requestId: String? = null,
        amountCents: Long = 0,
        providerBatchId: String? = null,
        failureCode: String? = null
    ) = WithdrawalResult(
        code = code,
        requestId = requestId,
        amountCents = amountCents,
        currency = currency,
        providerBatchId = providerBatchId,
        failureCode = failureCode,
        wallet = rewardService.wallet(installationId)
    )

    private fun validPayload(envelope: SignedWithdrawalEnvelope, nowEpochMs: Long): Boolean = runCatching {
        val payload = envelope.payload
        payload.protocolVersion == WithdrawalProtocol.VERSION &&
            UUID.fromString(payload.installationId).toString() == payload.installationId &&
            UUID.fromString(payload.requestId).toString() == payload.requestId &&
            payload.currency.uppercase() in setOf("BRL", "USD") &&
            payload.appVersion.isNotBlank() && payload.appVersion.length <= 32 &&
            payload.requestedAtEpochMs > 0L &&
            kotlin.math.abs(nowEpochMs - payload.requestedAtEpochMs) <= REQUEST_MAX_AGE_MS &&
            envelope.signatureAlgorithm == SessionProtocol.SIGNATURE_ALGORITHM &&
            envelope.keyId.isNotBlank() &&
            envelope.signatureBase64.isNotBlank()
    }.getOrDefault(false)

    private fun verify(envelope: SignedWithdrawalEnvelope, publicKeyBase64: String): Boolean = runCatching {
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)))
        val signatureBytes = Base64.getDecoder().decode(envelope.signatureBase64)
        Signature.getInstance(SessionProtocol.SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(CanonicalWithdrawalCodec.bytes(envelope.payload))
            verify(signatureBytes)
        }
    }.getOrDefault(false)

    private companion object {
        const val REQUEST_MAX_AGE_MS = 10 * 60 * 1000L
    }
}
