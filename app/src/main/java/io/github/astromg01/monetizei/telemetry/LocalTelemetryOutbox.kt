package io.github.astromg01.monetizei.telemetry

import android.content.Context
import io.github.astromg01.monetizei.protocol.InstallationRegistration
import io.github.astromg01.monetizei.protocol.SignedSessionEnvelope
import org.json.JSONObject

class LocalTelemetryOutbox(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun saveRegistration(registration: InstallationRegistration) {
        check(
            preferences.edit()
                .putString(KEY_REGISTRATION, encodeRegistration(registration))
                .commit()
        ) { "Unable to persist installation registration" }
    }

    @Synchronized
    fun enqueue(envelope: SignedSessionEnvelope) {
        val index = preferences.getStringSet(KEY_PENDING_INDEX, emptySet())
            ?.toMutableSet()
            ?: mutableSetOf()
        val sequence = envelope.payload.sequence.toString()
        val editor = preferences.edit()

        if (sequence !in index && index.size >= MAX_PENDING) {
            val oldest = index.mapNotNull(String::toLongOrNull).minOrNull()?.toString()
            if (oldest != null) {
                index.remove(oldest)
                editor.remove(KEY_SESSION_PREFIX + oldest)
            }
        }

        index.add(sequence)
        check(
            editor
                .putString(KEY_SESSION_PREFIX + sequence, encodeEnvelope(envelope))
                .putStringSet(KEY_PENDING_INDEX, index)
                .commit()
        ) { "Unable to persist signed session" }
    }

    @Synchronized
    fun pendingCount(): Int =
        preferences.getStringSet(KEY_PENDING_INDEX, emptySet())?.size ?: 0

    private fun encodeRegistration(registration: InstallationRegistration): String =
        JSONObject()
            .put("protocolVersion", registration.protocolVersion)
            .put("installationId", registration.installationId)
            .put("keyId", registration.keyId)
            .put("publicKeyBase64", registration.publicKeyBase64)
            .put("signatureAlgorithm", registration.signatureAlgorithm)
            .put("appVersion", registration.appVersion)
            .put("createdAtEpochMs", registration.createdAtEpochMs)
            .toString()

    private fun encodeEnvelope(envelope: SignedSessionEnvelope): String =
        JSONObject()
            .put(
                "payload",
                JSONObject()
                    .put("protocolVersion", envelope.payload.protocolVersion)
                    .put("installationId", envelope.payload.installationId)
                    .put("sessionId", envelope.payload.sessionId)
                    .put("sequence", envelope.payload.sequence)
                    .put("startedAtEpochMs", envelope.payload.startedAtEpochMs)
                    .put("finishedAtEpochMs", envelope.payload.finishedAtEpochMs)
                    .put("durationMs", envelope.payload.durationMs)
                    .put("score", envelope.payload.score)
                    .put("appVersion", envelope.payload.appVersion)
            )
            .put("keyId", envelope.keyId)
            .put("signatureAlgorithm", envelope.signatureAlgorithm)
            .put("signatureBase64", envelope.signatureBase64)
            .toString()

    private companion object {
        const val PREFS_NAME = "monetizei_telemetry_outbox"
        const val KEY_REGISTRATION = "registration"
        const val KEY_PENDING_INDEX = "pending_index"
        const val KEY_SESSION_PREFIX = "session_"
        const val MAX_PENDING = 50
    }
}
