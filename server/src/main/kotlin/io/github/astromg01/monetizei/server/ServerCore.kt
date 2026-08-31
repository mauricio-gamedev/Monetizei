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

interface SessionPersistence {
    fun loadRegistrations(): List<InstallationRegistration>
    fun loadLedgerEntries(): List<GameplayLedgerEntry>
    fun saveRegistration(registration: InstallationRegistration): Boolean
    fun saveLedgerEntry(entry: GameplayLedgerEntry): Boolean
}

object NoopSessionPersistence : SessionPersistence {
    override fun loadRegistrations() = emptyList<InstallationRegistration>()
    override fun loadLedgerEntries() = emptyList<GameplayLedgerEntry>()
    override fun saveRegistration(registration: InstallationRegistration) = true
    override fun saveLedgerEntry(entry: GameplayLedgerEntry) = true
}

class InstallationRegistry(initial: Iterable<InstallationRegistration> = emptyList()) {
    private val registrations = mutableMapOf<String, InstallationRegistration>()

    init {
        initial.forEach { registration ->
            if (validRegistration(registration)) {
                registrations[registration.installationId] = registration
            }
        }
    }

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
    fun rollbackCreated(registration: InstallationRegistration) {
        val current = registrations[registration.installationId]
        if (current?.keyId == registration.keyId) {
            registrations.remove(registration.installationId)
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
    INVALID,
    STORAGE_FAILURE
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

class ReplayGuard(initialEntries: Iterable<GameplayLedgerEntry> = emptyList()) {
    private val highestSequence = mutableMapOf<String, Long>()
    private val acceptedSessionIds = mutableSetOf<String>()

    init {
        initialEntries.forEach { entry ->
            val highest = highestSequence[entry.installationId] ?: 0L
            highestSequence[entry.installationId] = maxOf(highest, entry.sequence)
            acceptedSessionIds.add(entry.sessionId)
        }
    }

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

    fun canAccept(installationId: String, nowEpochMs: Long): Boolean {
        val queue = acceptedAt.getOrPut(installationId) { ArrayDeque() }
        prune(queue, nowEpochMs)
        return queue.size < maxAcceptedSessions
    }

    fun commit(installationId: String, nowEpochMs: Long) {
        val queue = acceptedAt.getOrPut(installationId) { ArrayDeque() }
        prune(queue, nowEpochMs)
        queue.addLast(nowEpochMs)
    }

    private fun prune(queue: ArrayDeque<Long>, nowEpochMs: Long) {
        val cutoff = nowEpochMs - windowMs
        while (queue.isNotEmpty() && queue.first() <= cutoff) {
            queue.removeFirst()
        }
    }
}

data class GameplayLedgerEntry(
    val ledgerId: String,
    val installationId: String,
    val sessionId: String,
    val sequence: Long,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val durationMs: Long,
    val verifiedScoreUnits: Long,
    val appVersion: String,
    val acceptedAtEpochMs: Long
)

class AppendOnlyGameplayLedger(initialEntries: Iterable<GameplayLedgerEntry> = emptyList()) {
    private val entries = initialEntries.toMutableList()

    fun prepare(payload: SessionPayload, acceptedAtEpochMs: Long): GameplayLedgerEntry =
        GameplayLedgerEntry(
            ledgerId = UUID.randomUUID().toString(),
            installationId = payload.installationId,
            sessionId = payload.sessionId,
            sequence = payload.sequence,
            startedAtEpochMs = payload.startedAtEpochMs,
            finishedAtEpochMs = payload.finishedAtEpochMs,
            durationMs = payload.durationMs,
            verifiedScoreUnits = payload.score.toLong(),
            appVersion = payload.appVersion,
            acceptedAtEpochMs = acceptedAtEpochMs
        )

    @Synchronized
    fun commit(entry: GameplayLedgerEntry) {
        entries.add(entry)
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
    RATE_LIMITED,
    STORAGE_FAILURE
}

data class IngestResult(
    val accepted: Boolean,
    val ledgerId: String? = null,
    val rejectReason: IngestRejectReason? = null,
    val rewardDecision: RewardDecision? = null
)

class SessionIngestService(
    private val persistence: SessionPersistence = NoopSessionPersistence,
    private val rewardService: RewardService = RewardService(),
    private val registry: InstallationRegistry = InstallationRegistry(persistence.loadRegistrations()),
    private val verifier: SignatureVerifier = SignatureVerifier(),
    private val replayGuard: ReplayGuard = ReplayGuard(persistence.loadLedgerEntries()),
    private val rateLimiter: SessionRateLimiter = SessionRateLimiter(),
    private val ledger: AppendOnlyGameplayLedger = AppendOnlyGameplayLedger(persistence.loadLedgerEntries())
) {
    @Synchronized
    fun register(registration: InstallationRegistration): RegistrationResult {
        val result = registry.register(registration)
        if (result != RegistrationResult.CREATED) return result

        if (!persistence.saveRegistration(registration)) {
            registry.rollbackCreated(registration)
            return RegistrationResult.STORAGE_FAILURE
        }
        return RegistrationResult.CREATED
    }

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
        if (!rateLimiter.canAccept(payload.installationId, receivedAtEpochMs)) {
            return rejected(IngestRejectReason.RATE_LIMITED)
        }

        val entry = ledger.prepare(payload, receivedAtEpochMs)
        if (!persistence.saveLedgerEntry(entry)) {
            return rejected(IngestRejectReason.STORAGE_FAILURE)
        }

        replayGuard.commit(payload)
        rateLimiter.commit(payload.installationId, receivedAtEpochMs)
        ledger.commit(entry)
        val rewardDecision = rewardService.evaluateAcceptedGameplay(entry)
        return IngestResult(
            accepted = true,
            ledgerId = entry.ledgerId,
            rewardDecision = rewardDecision
        )
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
