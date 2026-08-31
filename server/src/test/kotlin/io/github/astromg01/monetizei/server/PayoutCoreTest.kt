package io.github.astromg01.monetizei.server

import io.github.astromg01.monetizei.protocol.CanonicalWithdrawalCodec
import io.github.astromg01.monetizei.protocol.InstallationRegistration
import io.github.astromg01.monetizei.protocol.KeyIds
import io.github.astromg01.monetizei.protocol.SessionProtocol
import io.github.astromg01.monetizei.protocol.SignedWithdrawalEnvelope
import io.github.astromg01.monetizei.protocol.WithdrawalPayload
import io.github.astromg01.monetizei.protocol.WithdrawalProtocol
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayoutCoreTest {
    @Test
    fun sameSignedRequestSubmitsOnceThenConfirmsPaid() {
        val fixture = fixture()
        val gateway = FakeGateway()
        val persistence = FakePayoutPersistence()
        val service = WithdrawalService(
            rewardService = fixture.rewardService,
            persistence = persistence,
            registrationLookup = { fixture.registration },
            gateway = gateway
        )
        val requestId = UUID.randomUUID().toString()
        val envelope = signedEnvelope(fixture, requestId, NOW)

        val submitted = service.request(envelope, NOW)
        assertEquals(WithdrawalResultCode.SUBMITTED, submitted.code)
        assertEquals(1, gateway.submitCount)
        assertEquals(0L, submitted.wallet.brl.availableCents)
        assertEquals(RewardState.PAYOUT_PENDING, fixture.rewardService.snapshot().single().state)

        gateway.statusResult = ProviderStatusResult(ProviderPayoutState.SUCCESS)
        val paid = service.request(envelope, NOW + 1_000L)
        assertEquals(WithdrawalResultCode.PAID, paid.code)
        assertEquals(1, gateway.submitCount)
        assertEquals(RewardState.PAID, fixture.rewardService.snapshot().single().state)
        assertEquals(PayoutState.PAID, persistence.loadPayout(requestId)?.state)
    }

    @Test
    fun sandboxSuccessRestoresAvailableBalanceAndNeverMarksRealRewardPaid() {
        val fixture = fixture()
        val gateway = FakeGateway(PayoutSettlementMode.SANDBOX)
        val persistence = FakePayoutPersistence()
        val service = WithdrawalService(
            rewardService = fixture.rewardService,
            persistence = persistence,
            registrationLookup = { fixture.registration },
            gateway = gateway
        )
        val requestId = UUID.randomUUID().toString()
        val envelope = signedEnvelope(fixture, requestId, NOW)

        val submitted = service.request(envelope, NOW)
        assertEquals(WithdrawalResultCode.SUBMITTED, submitted.code)
        assertEquals(RewardState.PAYOUT_PENDING, fixture.rewardService.snapshot().single().state)
        assertEquals(0L, submitted.wallet.brl.availableCents)

        gateway.statusResult = ProviderStatusResult(ProviderPayoutState.SUCCESS)
        val verified = service.request(envelope, NOW + 1_000L)
        assertEquals(WithdrawalResultCode.FAILED, verified.code)
        assertEquals("SANDBOX_VERIFIED_NO_SETTLEMENT", verified.failureCode)
        assertEquals(RewardState.AVAILABLE, fixture.rewardService.snapshot().single().state)
        assertEquals(1L, verified.wallet.brl.availableCents)
        assertEquals(PayoutState.FAILED, persistence.loadPayout(requestId)?.state)
        assertEquals("SANDBOX_VERIFIED_NO_SETTLEMENT", persistence.loadPayout(requestId)?.failureCode)

        val repeated = service.request(envelope, NOW + 2_000L)
        assertEquals(WithdrawalResultCode.FAILED, repeated.code)
        assertEquals("SANDBOX_VERIFIED_NO_SETTLEMENT", repeated.failureCode)
        assertEquals(1, gateway.submitCount)
        assertEquals(RewardState.AVAILABLE, fixture.rewardService.snapshot().single().state)
    }

    @Test
    fun retryableSubmitKeepsSameRequestReservedAndDoesNotReleaseBalance() {
        val fixture = fixture()
        val gateway = FakeGateway().apply {
            submitResults += ProviderSubmitResult(
                accepted = false,
                failureCode = "PAYPAL_NETWORK_ERROR",
                retryable = true
            )
            submitResults += ProviderSubmitResult(true, providerBatchId = "batch-retry")
        }
        val persistence = FakePayoutPersistence()
        val service = WithdrawalService(
            rewardService = fixture.rewardService,
            persistence = persistence,
            registrationLookup = { fixture.registration },
            gateway = gateway
        )
        val requestId = UUID.randomUUID().toString()
        val envelope = signedEnvelope(fixture, requestId, NOW)

        val ambiguous = service.request(envelope, NOW)
        assertEquals(WithdrawalResultCode.PROCESSING, ambiguous.code)
        assertEquals(RewardState.PAYOUT_PENDING, fixture.rewardService.snapshot().single().state)
        assertEquals(PayoutState.REQUESTED, persistence.loadPayout(requestId)?.state)

        val retried = service.request(envelope, NOW + 1_000L)
        assertEquals(WithdrawalResultCode.SUBMITTED, retried.code)
        assertEquals(2, gateway.submitCount)
        assertEquals("batch-retry", persistence.loadPayout(requestId)?.providerBatchId)
    }

    @Test
    fun disabledProviderLeavesAvailableBalanceUntouched() {
        val fixture = fixture()
        val service = WithdrawalService(
            rewardService = fixture.rewardService,
            persistence = FakePayoutPersistence(),
            registrationLookup = { fixture.registration },
            gateway = DisabledPayoutGateway
        )
        val envelope = signedEnvelope(fixture, UUID.randomUUID().toString(), NOW)

        val result = service.request(envelope, NOW)
        assertEquals(WithdrawalResultCode.PROVIDER_DISABLED, result.code)
        assertEquals(1L, result.wallet.brl.availableCents)
        assertEquals(RewardState.AVAILABLE, fixture.rewardService.snapshot().single().state)
    }

    private fun fixture(): Fixture {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val installationId = UUID.randomUUID().toString()
        val publicBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val registration = InstallationRegistration(
            protocolVersion = SessionProtocol.VERSION,
            installationId = installationId,
            keyId = KeyIds.fromEncodedPublicKey(keyPair.public.encoded),
            publicKeyBase64 = publicBase64,
            signatureAlgorithm = SessionProtocol.SIGNATURE_ALGORITHM,
            appVersion = "0.6.1",
            createdAtEpochMs = NOW - 10_000L
        )
        val reward = RewardLedgerEntry(
            rewardId = UUID.randomUUID().toString(),
            installationId = installationId,
            gameplayLedgerId = UUID.randomUUID().toString(),
            sessionId = UUID.randomUUID().toString(),
            amountCents = 1L,
            currency = RewardCurrency.BRL,
            state = RewardState.AVAILABLE,
            policyCode = "test",
            createdAtEpochMs = NOW - 5_000L,
            updatedAtEpochMs = NOW - 5_000L
        )
        val rewardService = RewardService(
            persistence = NoopRewardPersistence,
            initialEntries = listOf(reward)
        )
        return Fixture(keyPair, registration, rewardService)
    }

    private fun signedEnvelope(fixture: Fixture, requestId: String, requestedAt: Long): SignedWithdrawalEnvelope {
        val payload = WithdrawalPayload(
            protocolVersion = WithdrawalProtocol.VERSION,
            installationId = fixture.registration.installationId,
            requestId = requestId,
            currency = "BRL",
            requestedAtEpochMs = requestedAt,
            appVersion = "0.6.1"
        )
        val signature = Signature.getInstance(SessionProtocol.SIGNATURE_ALGORITHM).apply {
            initSign(fixture.keyPair.private)
            update(CanonicalWithdrawalCodec.bytes(payload))
        }.sign()
        return SignedWithdrawalEnvelope(
            payload = payload,
            keyId = fixture.registration.keyId,
            signatureAlgorithm = SessionProtocol.SIGNATURE_ALGORITHM,
            signatureBase64 = Base64.getEncoder().encodeToString(signature)
        )
    }

    private data class Fixture(
        val keyPair: KeyPair,
        val registration: InstallationRegistration,
        val rewardService: RewardService
    )

    private class FakeGateway(
        override val settlementMode: PayoutSettlementMode = PayoutSettlementMode.LIVE
    ) : PayoutGateway {
        override val providerName = "paypal"
        override val enabled = true
        var submitCount = 0
        var statusResult = ProviderStatusResult(ProviderPayoutState.PENDING)
        val submitResults = ArrayDeque<ProviderSubmitResult>()

        override fun submit(requestId: String, currency: RewardCurrency, amountCents: Long): ProviderSubmitResult {
            submitCount += 1
            return if (submitResults.isEmpty()) {
                ProviderSubmitResult(true, providerBatchId = "batch-1")
            } else {
                submitResults.removeFirst()
            }
        }

        override fun status(providerBatchId: String): ProviderStatusResult = statusResult
    }

    private class FakePayoutPersistence : PayoutPersistence {
        private val entries = mutableMapOf<String, PayoutLedgerEntry>()
        private val rewards = mutableMapOf<String, MutableList<String>>()

        override fun loadPayout(requestId: String): PayoutLedgerEntry? = entries[requestId]

        override fun savePayout(entry: PayoutLedgerEntry): Boolean {
            if (entry.requestId in entries) return false
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
            val values = rewards.getOrPut(requestId) { mutableListOf() }
            if (rewardId in values) return false
            values += rewardId
            return true
        }

        override fun loadPayoutRewardIds(requestId: String): List<String> = rewards[requestId]?.toList().orEmpty()
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
