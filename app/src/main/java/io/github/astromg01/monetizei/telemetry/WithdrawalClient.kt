package io.github.astromg01.monetizei.telemetry

import io.github.astromg01.monetizei.protocol.ProtocolJson
import io.github.astromg01.monetizei.protocol.SignedWithdrawalEnvelope
import java.net.HttpURLConnection
import java.net.URL

class WithdrawalClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000
) {
    fun request(envelope: SignedWithdrawalEnvelope): WithdrawalResponse {
        if (baseUrl.isBlank()) return WithdrawalResponse("SERVER_NOT_CONFIGURED", 0, null)
        val connection = (URL(baseUrl.trimEnd('/') + "/v1/withdrawals").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val body = ProtocolJson.encodeWithdrawal(envelope)
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            WithdrawalResponseParser.parse(code, responseBody)
        } finally {
            connection.disconnect()
        }
    }
}

data class WithdrawalResponse(
    val result: String,
    val httpCode: Int,
    val wallet: RemoteRewardWallet?,
    val failureCode: String? = null
)

object WithdrawalResponseParser {
    fun parse(httpCode: Int, body: String): WithdrawalResponse {
        val result = extractString(body, "result") ?: "HTTP_$httpCode"
        val failureCode = extractNullableString(body, "failureCode")
        val wallet = RewardWalletResponseParser.parse(body)
        return WithdrawalResponse(result, httpCode, wallet, failureCode)
    }

    private fun extractString(body: String, key: String): String? {
        val marker = "\"$key\""
        val keyIndex = body.indexOf(marker)
        if (keyIndex < 0) return null
        val colon = body.indexOf(':', keyIndex + marker.length)
        if (colon < 0) return null
        var start = colon + 1
        while (start < body.length && body[start].isWhitespace()) start += 1
        if (start >= body.length || body[start] != '"') return null
        start += 1
        val end = body.indexOf('"', start)
        if (end < 0) return null
        return body.substring(start, end)
    }

    private fun extractNullableString(body: String, key: String): String? {
        val marker = "\"$key\""
        val keyIndex = body.indexOf(marker)
        if (keyIndex < 0) return null
        val colon = body.indexOf(':', keyIndex + marker.length)
        if (colon < 0) return null
        var start = colon + 1
        while (start < body.length && body[start].isWhitespace()) start += 1
        if (body.startsWith("null", start)) return null
        if (start >= body.length || body[start] != '"') return null
        start += 1
        val end = body.indexOf('"', start)
        if (end < 0) return null
        return body.substring(start, end)
    }
}
