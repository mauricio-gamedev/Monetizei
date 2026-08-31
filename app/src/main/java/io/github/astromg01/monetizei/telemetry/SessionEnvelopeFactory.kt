package io.github.astromg01.monetizei.telemetry

import io.github.astromg01.monetizei.domain.SessionResult
import io.github.astromg01.monetizei.identity.InstallationIdentityStore
import io.github.astromg01.monetizei.protocol.CanonicalSessionCodec
import io.github.astromg01.monetizei.protocol.InstallationRegistration
import io.github.astromg01.monetizei.protocol.SessionPayload
import io.github.astromg01.monetizei.protocol.SessionProtocol
import io.github.astromg01.monetizei.protocol.SignedSessionEnvelope
import io.github.astromg01.monetizei.security.AndroidKeystoreSessionSigner
import java.util.UUID

class SessionEnvelopeFactory(
    private val identityStore: InstallationIdentityStore,
    private val sequenceStore: TelemetrySequenceStore,
    private val signer: AndroidKeystoreSessionSigner,
    private val appVersion: String
) {
    fun registration(nowEpochMs: Long = System.currentTimeMillis()): InstallationRegistration =
        InstallationRegistration(
            protocolVersion = SessionProtocol.VERSION,
            installationId = identityStore.getOrCreate(),
            keyId = signer.keyId(),
            publicKeyBase64 = signer.publicKeyBase64(),
            signatureAlgorithm = SessionProtocol.SIGNATURE_ALGORITHM,
            appVersion = appVersion,
            createdAtEpochMs = nowEpochMs
        )

    fun createSignedSession(
        result: SessionResult,
        startedAtEpochMs: Long,
        finishedAtEpochMs: Long
    ): SignedSessionEnvelope {
        val payload = SessionPayload(
            protocolVersion = SessionProtocol.VERSION,
            installationId = identityStore.getOrCreate(),
            sessionId = UUID.randomUUID().toString(),
            sequence = sequenceStore.next(),
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            durationMs = result.durationMs,
            score = result.score,
            appVersion = appVersion
        )

        return SignedSessionEnvelope(
            payload = payload,
            keyId = signer.keyId(),
            signatureAlgorithm = SessionProtocol.SIGNATURE_ALGORITHM,
            signatureBase64 = signer.signBase64(CanonicalSessionCodec.bytes(payload))
        )
    }
}
