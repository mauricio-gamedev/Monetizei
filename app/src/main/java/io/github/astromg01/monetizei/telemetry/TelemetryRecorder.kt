package io.github.astromg01.monetizei.telemetry

import android.content.Context
import io.github.astromg01.monetizei.BuildConfig
import io.github.astromg01.monetizei.domain.SessionResult
import io.github.astromg01.monetizei.identity.InstallationIdentityStore
import io.github.astromg01.monetizei.protocol.CanonicalWithdrawalCodec
import io.github.astromg01.monetizei.protocol.SessionProtocol
import io.github.astromg01.monetizei.protocol.SignedWithdrawalEnvelope
import io.github.astromg01.monetizei.protocol.WithdrawalPayload
import io.github.astromg01.monetizei.protocol.WithdrawalProtocol
import io.github.astromg01.monetizei.security.AndroidKeystoreSessionSigner
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TelemetryRecorder(
    context: Context,
    private val appVersion: String,
    baseUrl: String = BuildConfig.MONETIZEI_API_BASE_URL
) {
    private val identityStore = InstallationIdentityStore(context)
    private val sequenceStore = TelemetrySequenceStore(context)
    private val outbox = LocalTelemetryOutbox(context)
    private val withdrawalPreferences = context.getSharedPreferences("monetizei_withdrawal_v1", Context.MODE_PRIVATE)
    private val signer by lazy { AndroidKeystoreSessionSigner() }
    private val factory by lazy { SessionEnvelopeFactory(identityStore, sequenceStore, signer, appVersion) }
    private val syncClient = TelemetrySyncClient(baseUrl)
    private val withdrawalClient = WithdrawalClient(baseUrl)
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "monetizei-http-sync") }
    private val syncQueued = AtomicBoolean(false)
    private val withdrawalQueued = AtomicBoolean(false)
    private val status = AtomicReference("local_only")
    private val remoteWallet = AtomicReference<RemoteRewardWallet?>(null)
    private val withdrawalState = AtomicReference(
        if (pendingWithdrawalRequestId() != null) "processing" else "idle"
    )

    fun initialize(): Boolean = runCatching {
        outbox.saveRegistration(factory.registration())
        scheduleSync()
        true
    }.getOrDefault(false)

    fun recordSession(result: SessionResult, startedAtEpochMs: Long, finishedAtEpochMs: Long): Boolean = runCatching {
        outbox.saveRegistration(factory.registration())
        outbox.enqueue(factory.createSignedSession(result, startedAtEpochMs, finishedAtEpochMs))
        scheduleSync()
        true
    }.getOrDefault(false)

    fun requestWithdrawal(currency: String = "BRL"): Boolean {
        if (!withdrawalQueued.compareAndSet(false, true)) return false
        withdrawalState.set("requesting")
        executor.execute {
            try {
                val registration = factory.registration()
                outbox.saveRegistration(registration)
                val requestId = pendingWithdrawalRequestId() ?: UUID.randomUUID().toString().also {
                    withdrawalPreferences.edit().putString(KEY_PENDING_REQUEST_ID, it).apply()
                }
                val payload = WithdrawalPayload(
                    protocolVersion = WithdrawalProtocol.VERSION,
                    installationId = registration.installationId,
                    requestId = requestId,
                    currency = currency.uppercase(),
                    requestedAtEpochMs = System.currentTimeMillis(),
                    appVersion = appVersion
                )
                val envelope = SignedWithdrawalEnvelope(
                    payload = payload,
                    keyId = signer.keyId(),
                    signatureAlgorithm = SessionProtocol.SIGNATURE_ALGORITHM,
                    signatureBase64 = signer.signBase64(CanonicalWithdrawalCodec.bytes(payload))
                )
                val response = runCatching { withdrawalClient.request(envelope) }
                    .getOrElse {
                        withdrawalState.set("network_error")
                        return@execute
                    }
                response.wallet?.let { remoteWallet.set(it) }
                when (response.result) {
                    "SUBMITTED", "PROCESSING" -> withdrawalState.set("processing")
                    "PAID" -> {
                        clearPendingWithdrawal()
                        withdrawalState.set("paid")
                    }
                    "NO_AVAILABLE" -> {
                        clearPendingWithdrawal()
                        withdrawalState.set("no_available")
                    }
                    "PROVIDER_DISABLED" -> {
                        clearPendingWithdrawal()
                        withdrawalState.set("provider_disabled")
                    }
                    "FAILED" -> {
                        clearPendingWithdrawal()
                        if (response.failureCode == SANDBOX_VERIFIED_CODE) {
                            withdrawalState.set("sandbox_verified")
                        } else {
                            withdrawalState.set("failed_${response.failureCode ?: response.httpCode}")
                        }
                    }
                    else -> {
                        if (response.httpCode in 400..499) clearPendingWithdrawal()
                        withdrawalState.set("error_${response.result}")
                    }
                }
            } finally {
                withdrawalQueued.set(false)
            }
        }
        return true
    }

    fun pendingCount(): Int = outbox.pendingCount()
    fun syncStatus(): String = status.get()
    fun rewardWallet(): RemoteRewardWallet? = remoteWallet.get()
    fun withdrawalStatus(): String = withdrawalState.get()
    fun hasPendingWithdrawal(): Boolean = pendingWithdrawalRequestId() != null

    private fun pendingWithdrawalRequestId(): String? =
        withdrawalPreferences.getString(KEY_PENDING_REQUEST_ID, null)?.takeIf { it.isNotBlank() }

    private fun clearPendingWithdrawal() {
        withdrawalPreferences.edit().remove(KEY_PENDING_REQUEST_ID).apply()
    }

    private fun scheduleSync() {
        if (!syncQueued.compareAndSet(false, true)) return
        status.set("syncing")
        executor.execute {
            try {
                val report = runCatching { syncClient.sync(outbox) }
                    .getOrElse { SyncReport(pending = outbox.pendingCount(), error = it.javaClass.simpleName) }
                report.wallet?.let { remoteWallet.set(it) }
                status.set(
                    when {
                        report.skipped -> "server_not_configured"
                        report.error != null -> report.error
                        report.pending > 0 -> "pending_${report.pending}"
                        else -> "synced"
                    }
                )
            } finally {
                syncQueued.set(false)
            }
        }
    }

    private companion object {
        const val KEY_PENDING_REQUEST_ID = "pending_request_id"
        const val SANDBOX_VERIFIED_CODE = "SANDBOX_VERIFIED_NO_SETTLEMENT"
    }
}
