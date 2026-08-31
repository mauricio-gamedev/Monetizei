package io.github.astromg01.monetizei.telemetry

import android.content.Context
import io.github.astromg01.monetizei.protocol.InstallationRegistration
import io.github.astromg01.monetizei.protocol.ProtocolJson
import io.github.astromg01.monetizei.protocol.SignedSessionEnvelope

class LocalTelemetryOutbox(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun saveRegistration(registration: InstallationRegistration) {
        check(preferences.edit().putString(KEY_REGISTRATION, ProtocolJson.encodeRegistration(registration)).commit())
    }

    @Synchronized
    fun registration(): InstallationRegistration? =
        preferences.getString(KEY_REGISTRATION, null)?.let { encoded ->
            runCatching { ProtocolJson.decodeRegistration(encoded) }.getOrNull()
        }

    @Synchronized
    fun enqueue(envelope: SignedSessionEnvelope) {
        val index = preferences.getStringSet(KEY_PENDING_INDEX, emptySet())?.toMutableSet() ?: mutableSetOf()
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
            editor.putString(KEY_SESSION_PREFIX + sequence, ProtocolJson.encodeSession(envelope))
                .putStringSet(KEY_PENDING_INDEX, index)
                .commit()
        )
    }

    @Synchronized
    fun pending(limit: Int = 10): List<SignedSessionEnvelope> =
        preferences.getStringSet(KEY_PENDING_INDEX, emptySet()).orEmpty()
            .mapNotNull(String::toLongOrNull)
            .sorted()
            .take(limit.coerceIn(1, MAX_PENDING))
            .mapNotNull { sequence ->
                preferences.getString(KEY_SESSION_PREFIX + sequence, null)?.let { encoded ->
                    runCatching { ProtocolJson.decodeSession(encoded) }.getOrNull()
                }
            }

    @Synchronized
    fun remove(sequence: Long) {
        val index = preferences.getStringSet(KEY_PENDING_INDEX, emptySet())?.toMutableSet() ?: mutableSetOf()
        index.remove(sequence.toString())
        check(
            preferences.edit()
                .remove(KEY_SESSION_PREFIX + sequence)
                .putStringSet(KEY_PENDING_INDEX, index)
                .commit()
        )
    }

    @Synchronized
    fun pendingCount(): Int = preferences.getStringSet(KEY_PENDING_INDEX, emptySet())?.size ?: 0

    private companion object {
        const val PREFS_NAME = "monetizei_telemetry_outbox"
        const val KEY_REGISTRATION = "registration"
        const val KEY_PENDING_INDEX = "pending_index"
        const val KEY_SESSION_PREFIX = "session_"
        const val MAX_PENDING = 50
    }
}
