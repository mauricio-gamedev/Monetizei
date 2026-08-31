package io.github.astromg01.monetizei.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ProtocolJson {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun encodeRegistration(value: InstallationRegistration): String =
        json.encodeToString(value)

    fun decodeRegistration(value: String): InstallationRegistration =
        json.decodeFromString(value)

    fun encodeSession(value: SignedSessionEnvelope): String =
        json.encodeToString(value)

    fun decodeSession(value: String): SignedSessionEnvelope =
        json.decodeFromString(value)
}
