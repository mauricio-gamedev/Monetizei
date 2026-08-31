package io.github.astromg01.monetizei.telemetry

import io.github.astromg01.monetizei.protocol.ProtocolJson
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TelemetrySyncClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 5_000
) {
    fun sync(outbox: LocalTelemetryOutbox): SyncReport {
        if (baseUrl.isBlank()) return SyncReport(skipped = true, pending = outbox.pendingCount())
        val registration = outbox.registration()
            ?: return SyncReport(pending = outbox.pendingCount(), error = "missing_registration")

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

        val encodedInstallationId = URLEncoder.encode(registration.installationId, Charsets.UTF_8.name())
        val walletResponse = runCatching {
            get("/v1/wallet?installationId=$encodedInstallationId")
        }.getOrNull()
        if (walletResponse?.code in 200..299) {
            latestWallet = RewardWalletResponseParser.parse(walletResponse?.body.orEmpty()) ?: latestWallet
        }

        return SyncReport(uploaded = uploaded, pending = outbox.pendingCount(), wallet = latestWallet)
    }

    private fun post(path: String, body: String): HttpResponse {
        val connection = open(path, "POST").apply { doOutput = true }
        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            response(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun get(path: String): HttpResponse {
        val connection = open(path, "GET")
        return try {
            response(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

    private fun response(connection: HttpURLConnection): HttpResponse {
        val code = connection.responseCode
        val stream = if (code >= 400) connection.errorStream else connection.inputStream
        return HttpResponse(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
    }
}

data class HttpResponse(val code: Int, val body: String)

data class RemoteCurrencyRewardBalance(
    val pendingCents: Long = 0,
    val approvedCents: Long = 0,
    val availableCents: Long = 0
)

data class RemoteRewardWallet(
    val brl: RemoteCurrencyRewardBalance = RemoteCurrencyRewardBalance(),
    val usd: RemoteCurrencyRewardBalance = RemoteCurrencyRewardBalance()
) {
    val pendingCents: Long get() = brl.pendingCents
    val approvedCents: Long get() = brl.approvedCents
    val availableCents: Long get() = brl.availableCents
}

object RewardWalletResponseParser {
    fun parse(body: String): RemoteRewardWallet? {
        val brlObject = extractObject(body, "BRL")
        val usdObject = extractObject(body, "USD")
        val brl = brlObject?.let(::parseBalance)
        val usd = usdObject?.let(::parseBalance)
        if (brl != null && usd != null) {
            return RemoteRewardWallet(brl = brl, usd = usd)
        }

        val legacyBrl = parseBalance(body) ?: return null
        return RemoteRewardWallet(brl = legacyBrl)
    }

    private fun extractObject(body: String, key: String): String? {
        val marker = "\"$key\""
        val keyIndex = body.indexOf(marker)
        if (keyIndex < 0) return null

        val colonIndex = body.indexOf(':', keyIndex + marker.length)
        if (colonIndex < 0) return null

        val openIndex = body.indexOf('{', colonIndex + 1)
        if (openIndex < 0) return null

        var depth = 0
        for (index in openIndex until body.length) {
            when (body[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return body.substring(openIndex + 1, index)
                }
            }
        }
        return null
    }

    private fun parseBalance(body: String): RemoteCurrencyRewardBalance? {
        val pendingCents = extractLong(body, "pendingCents") ?: return null
        val approvedCents = extractLong(body, "approvedCents") ?: return null
        val availableCents = extractLong(body, "availableCents") ?: return null
        return RemoteCurrencyRewardBalance(pendingCents, approvedCents, availableCents)
    }

    private fun extractLong(body: String, key: String): Long? {
        val marker = "\"$key\""
        val keyIndex = body.indexOf(marker)
        if (keyIndex < 0) return null

        val colonIndex = body.indexOf(':', keyIndex + marker.length)
        if (colonIndex < 0) return null

        var start = colonIndex + 1
        while (start < body.length && body[start].isWhitespace()) start += 1
        if (start >= body.length || !body[start].isDigit()) return null

        var end = start
        while (end < body.length && body[end].isDigit()) end += 1
        return body.substring(start, end).toLongOrNull()
    }
}

data class SyncReport(
    val uploaded: Int = 0,
    val pending: Int,
    val skipped: Boolean = false,
    val error: String? = null,
    val wallet: RemoteRewardWallet? = null
)
