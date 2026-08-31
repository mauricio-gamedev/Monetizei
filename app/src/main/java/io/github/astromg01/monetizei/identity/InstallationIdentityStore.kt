package io.github.astromg01.monetizei.identity

import android.content.Context
import java.util.UUID

class InstallationIdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreate(): String {
        preferences.getString(KEY_INSTALLATION_ID, null)?.let { return it }

        val installationId = UUID.randomUUID().toString()
        check(preferences.edit().putString(KEY_INSTALLATION_ID, installationId).commit()) {
            "Unable to persist installation identity"
        }
        return installationId
    }

    private companion object {
        const val PREFS_NAME = "monetizei_identity"
        const val KEY_INSTALLATION_ID = "installation_id"
    }
}
