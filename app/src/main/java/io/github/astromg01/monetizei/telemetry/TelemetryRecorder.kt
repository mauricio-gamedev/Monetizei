package io.github.astromg01.monetizei.telemetry

import android.content.Context
import io.github.astromg01.monetizei.domain.SessionResult
import io.github.astromg01.monetizei.identity.InstallationIdentityStore
import io.github.astromg01.monetizei.security.AndroidKeystoreSessionSigner

class TelemetryRecorder(
    context: Context,
    appVersion: String
) {
    private val identityStore = InstallationIdentityStore(context)
    private val sequenceStore = TelemetrySequenceStore(context)
    private val outbox = LocalTelemetryOutbox(context)
    private val signer by lazy { AndroidKeystoreSessionSigner() }
    private val factory by lazy {
        SessionEnvelopeFactory(identityStore, sequenceStore, signer, appVersion)
    }

    fun initialize(): Boolean = runCatching {
        outbox.saveRegistration(factory.registration())
        true
    }.getOrDefault(false)

    fun recordSession(
        result: SessionResult,
        startedAtEpochMs: Long,
        finishedAtEpochMs: Long
    ): Boolean = runCatching {
        outbox.saveRegistration(factory.registration())
        outbox.enqueue(
            factory.createSignedSession(
                result = result,
                startedAtEpochMs = startedAtEpochMs,
                finishedAtEpochMs = finishedAtEpochMs
            )
        )
        true
    }.getOrDefault(false)

    fun pendingCount(): Int = outbox.pendingCount()
}
