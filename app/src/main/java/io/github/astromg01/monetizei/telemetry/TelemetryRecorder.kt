package io.github.astromg01.monetizei.telemetry

import android.content.Context
import io.github.astromg01.monetizei.BuildConfig
import io.github.astromg01.monetizei.domain.SessionResult
import io.github.astromg01.monetizei.identity.InstallationIdentityStore
import io.github.astromg01.monetizei.security.AndroidKeystoreSessionSigner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TelemetryRecorder(
    context: Context,
    appVersion: String,
    baseUrl: String = BuildConfig.MONETIZEI_API_BASE_URL
) {
    private val identityStore = InstallationIdentityStore(context)
    private val sequenceStore = TelemetrySequenceStore(context)
    private val outbox = LocalTelemetryOutbox(context)
    private val signer by lazy { AndroidKeystoreSessionSigner() }
    private val factory by lazy { SessionEnvelopeFactory(identityStore, sequenceStore, signer, appVersion) }
    private val syncClient = TelemetrySyncClient(baseUrl)
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "monetizei-http-sync") }
    private val syncQueued = AtomicBoolean(false)
    private val status = AtomicReference("local_only")

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

    fun pendingCount(): Int = outbox.pendingCount()
    fun syncStatus(): String = status.get()

    private fun scheduleSync() {
        if (!syncQueued.compareAndSet(false, true)) return
        executor.execute {
            try {
                val report = runCatching { syncClient.sync(outbox) }
                    .getOrElse { SyncReport(pending = outbox.pendingCount(), error = it.javaClass.simpleName) }
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
}
