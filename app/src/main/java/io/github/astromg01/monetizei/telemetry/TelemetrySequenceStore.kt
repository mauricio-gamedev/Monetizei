package io.github.astromg01.monetizei.telemetry

import android.content.Context

class TelemetrySequenceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun next(): Long {
        val current = preferences.getLong(KEY_SEQUENCE, 0L)
        check(current < Long.MAX_VALUE) { "Telemetry sequence exhausted" }
        val next = current + 1L
        check(preferences.edit().putLong(KEY_SEQUENCE, next).commit()) {
            "Unable to persist telemetry sequence"
        }
        return next
    }

    private companion object {
        const val PREFS_NAME = "monetizei_telemetry_sequence"
        const val KEY_SEQUENCE = "sequence"
    }
}
