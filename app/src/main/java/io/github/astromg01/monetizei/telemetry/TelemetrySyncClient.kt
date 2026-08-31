package io.github.astromg01.monetizei.telemetry

import io.github.astromg01.monetizei.protocol.ProtocolJson
import java.net.HttpURLConnection
import java.net.URL

class TelemetrySyncClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 5_000
) {
    fun sync(outbox: LocalTelemetryOutbox): SyncReport {
        if (baseUrl.isBlank()) return SyncReport(skipped = true, pending = outbox.pendingCount())
        val registration = outbox.registration() ?: return SyncReport(pending = outbox.pendingCount(), error = "missing_registration")

        val registrationResponse = post("/v1/installations", ProtocolJson.encodeRegistration(registration))
        if (registrationResponse.code !in 200..299) {
            return SyncReport(pending = outbox.pendingCount(), error = "registration_http_${registrationResponse.code}")
        }

        var uploaded = 0
        var latestWallet: RemoteRewardWallet? = null
        for (envelope in outbox.pending()) {
            val response = post("/v1/sessions", ProtocolJson.encodeSession(envelope))
            when {
                response.code in 200..299 -> {
                    latestWallet = RewardWalletResponseParser.parse(response.body) ?: latestWallet
                    outbox.remove(envelope.payload.sequence)
                    uploaded += 1
                }
                response.code == 409 && response.body.contains("REPLAY") -> {
                    outbox.remove(envelope.payload.sequence)
                }
                response.code == 429 -> break
                else -> return SyncReport(
                    uploaded = uploaded,
                    pending = outbox.pendingCount(),
                    error = "session_http_${response.code}",
                    wallet = latestWallet
                )
            }
        }

        return SyncReport(uploaded = uploaded, pending = outbox.pendingCount(), wallet = latestWallet)
    }

    private fun post(path: String, body: String): HttpResponse {
        val normalized = baseUrl.trimEnd('/') + path
        val connection = (URL(normalized).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            HttpResponse(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

data class HttpResponse(val code: Int, val body: String)

data class RemoteRewardWallet(
    val pendingCents: Long,
    val approvedCents: Long,
    val availableCents: Long
)

object RewardWalletResponseParser {
    private val pending = Regex("\\\"pendingCents\\\"\\s*:\\s*(\\d+)")
    private val approved = Regex("\\\"approvedCents\\\"\\s*:\\s*(\\d+)")
    private val available = Regex("\\\"availableCents\\\"\\s*:\\s*(\\d+)")

    fun parse(body: String): RemoteRewardWallet? {
        val pendingCents = pending.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val approvedCents = approved.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val availableCents = available.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return RemoteRewardWallet(pendingCents, approvedCents, availableCents)
    }
}

data class SyncReport(
    val uploaded: Int = 0,
    val pending: Int,
    val skipped: Boolean = false,
    val error: String? = null,
    val wallet: RemoteRewardWallet? = null
)
