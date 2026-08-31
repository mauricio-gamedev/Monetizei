package io.github.astromg01.monetizei.server

import io.github.astromg01.monetizei.protocol.CanonicalSessionCodec
import io.github.astromg01.monetizei.protocol.InstallationRegistration
import io.github.astromg01.monetizei.protocol.KeyIds
import io.github.astromg01.monetizei.protocol.SessionPayload
import io.github.astromg01.monetizei.protocol.SessionProtocol
import io.github.astromg01.monetizei.protocol.SignedSessionEnvelope
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.ArrayDeque
import java.util.Base64
import java.util.UUID

class InstallationRegistry {
    private val registrations = mutableMapOf<String, InstallationRegistration>()

    @Synchronized
    fun register(registration: InstallationRegistration): RegistrationResult {
        if (!validRegistration(registration)) return RegistrationResult.INVALID

        val existing = registrations[registration.installationId]
        if (existing == null) {
            registrations[registration.installationId] = registration
            return RegistrationResult.CREATED
        }

        return if (
            existing.keyId == registration.keyId &&
            existing.publicKeyBase64 == registration.publicKeyBase64
        ) {
            RegistrationResult.ALREADY_REGISTERED
        } else {
            RegistrationResult.KEY_CONFLICT
        }
    }

    @Synchronized
    fun get(installationId: String): InstallationRegistration? = registrations[installationId]

    private fun validRegistration(registration: InstallationRegistration): Boolean = runCatching {
        registration.protocolVersion == SessionProtocol.VERSION &&
            UUID.fromString(registration.installationId).toString() == registration.installationId &&
            registration.signatureAlgorithm == SessionProtocol.SIGNATURE_ALGORITHM &&
            registration.appVersion.isNotBlank() && registration.appVersion.length <= 32 &&
            registration.createdAtEpochMs > 0L &&
            KeyIds.fromPublicKeyBase64(registration.publicKeyBase64) == registration.keyId
    }.getOrDefault(false)
}

enum class RegistrationResult {
    CREATED,
    ALREADY_REGISTERED,
    KEY_CONFLICT,
    INVALID
}

class SignatureVerifier {
    fun verify(envelope: SignedSessionEnvelope, publicKeyBase64: String): Boolean = runCatching {
        if (envelope.signatureAlgorithm != SessionProtocol.SIGNATURE_ALGORITHM) return false

        val publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val signatureBytes = Base64.getDecoder().decode(envelope.signatureBase64)

        Signature.getInstance(SessionProtocol.SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(CanonicalSessionCodec.bytes(envelope.payload))
            verify(signatureBytes)
        }
    }.getOrDefault(false)
}

class ReplayGuard {
    private val highestSequence = mutableMapOf<String, Long>()
    private val acceptedSessionIds = mutableSetOf<String>()

    fun isReplay(payload: SessionPayload): Boolean {
        val highest = highestSequence[payload.installationId] ?: 0L
        return payload.sequence <= highest || payload.sessionId in acceptedSessionIds
    }

    fun commit(payload: SessionPayload) {
        highestSequence[payload.installationId] = payload.sequence
        acceptedSessionIds.add(payload.sessionId)
    }
}

class SessionRateLimiter(
    private val maxAcceptedSessions: Int = 5,
    private val windowMs: Long = 120_000L
) {
    private val acceptedAt = mutableMapOf<String, ArrayDeque<Long>>()

    fun allow(installationId: String, nowEpochMs: Long): Boolean {
        val queue = acceptedAt.getOrPut(installationId) { ArrayDeque() }
        val cutoff = nowEpochMs - windowMs
        while (queue.isNotEmpty() && queue.first() <= cutoff) {
            queue.removeFirst()
        }
        if (queue.size >= maxAcceptedSessions) return false
        queue.addLast(nowEpochMs)
        return true
    }
}

data class GameplayLedgerEntry(
    val ledgerId: String,
    val installationId: String,
    val sessionId: String,
    val sequence: Long,
    val verifiedScoreUnits: Long,
    val acceptedAtEpochMs: Long
)

class AppendOnlyGameplayLedger {
    private val entries = mutableListOf<GameplayLedgerEntry>()

    @Synchronized
    fun append(payload: SessionPayload, acceptedAtEpochMs: Long): GameplayLedgerEntry {
        val entry = GameplayLedgerEntry(
            ledgerId = UUID.randomUUID().toString(),
            installationId = payload.installationId,
            sessionId = payload.sessionId,
            sequence = payload.sequence,
            verifiedScoreUnits = payload.score.toLong(),
            acceptedAtEpochMs = acceptedAtEpochMs
        )
        entries.add(entry)
        return entry
    }

    @Synchronized
    fun snapshot(): List<GameplayLedgerEntry> = entries.toList()
}

enum class IngestRejectReason {
    UNKNOWN_INSTALLATION,
    KEY_MISMATCH,
    MALFORMED_SESSION,
    INVALID_SIGNATURE,
    REPLAY,
    RATE_LIMITED
}

data class IngestResult(
    val accepted: Boolean,
    val ledgerId: String? = null,
    val rejectReason: IngestRejectReason? = null
)

class SessionIngestService(
    private val registry: InstallationRegistry = InstallationRegistry(),
    private val verifier: SignatureVerifier = SignatureVerifier(),
    private val replayGuard: ReplayGuard = ReplayGuard(),
    private val rateLimiter: SessionRateLimiter = SessionRateLimiter(),
    private val ledger: AppendOnlyGameplayLedger = AppendOnlyGameplayLedger()
) {
    fun register(registration: InstallationRegistration): RegistrationResult =
        registry.register(registration)

    fun ledgerSnapshot(): List<GameplayLedgerEntry> = ledger.snapshot()

    @Synchronized
    fun submit(envelope: SignedSessionEnvelope, receivedAtEpochMs: Long): IngestResult {
        val payload = envelope.payload
        val registration = registry.get(payload.installationId)
            ?: return rejected(IngestRejectReason.UNKNOWN_INSTALLATION)

        if (registration.keyId != envelope.keyId) {
            return rejected(IngestRejectReason.KEY_MISMATCH)
        }
        if (!validPayload(payload)) {
            return rejected(IngestRejectReason.MALFORMED_SESSION)
        }
        if (!verifier.verify(envelope, registration.publicKeyBase64)) {
            return rejected(IngestRejectReason.INVALID_SIGNATURE)
        }
        if (replayGuard.isReplay(payload)) {
            return rejected(IngestRejectReason.REPLAY)
        }
        if (!rateLimiter.allow(payload.installationId, receivedAtEpochMs)) {
            return rejected(IngestRejectReason.RATE_LIMITED)
        }

        replayGuard.commit(payload)
        val entry = ledger.append(payload, receivedAtEpochMs)
        return IngestResult(accepted = true, ledgerId = entry.ledgerId)
    }

    private fun validPayload(payload: SessionPayload): Boolean = runCatching {
        payload.protocolVersion == SessionProtocol.VERSION &&
            UUID.fromString(payload.installationId).toString() == payload.installationId &&
            UUID.fromString(payload.sessionId).toString() == payload.sessionId &&
            payload.sequence > 0L &&
            payload.startedAtEpochMs > 0L &&
            payload.finishedAtEpochMs > 0L &&
            payload.durationMs == SessionProtocol.SESSION_DURATION_MS &&
            payload.score in 0..SessionProtocol.MAX_SESSION_SCORE &&
            payload.appVersion.isNotBlank() && payload.appVersion.length <= 32
    }.getOrDefault(false)

    private fun rejected(reason: IngestRejectReason) =
        IngestResult(accepted = false, rejectReason = reason)
}
