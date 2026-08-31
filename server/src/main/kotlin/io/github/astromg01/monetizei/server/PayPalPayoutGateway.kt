package io.github.astromg01.monetizei.server

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64

class PayPalPayoutGateway(
    private val clientId: String,
    private val clientSecret: String,
    private val receiverEmail: String,
    private val sandbox: Boolean = true,
    private val connectTimeoutMs: Int = 7_000,
    private val readTimeoutMs: Int = 10_000
) : PayoutGateway {
    override val providerName: String = "paypal"
    override val enabled: Boolean = clientId.isNotBlank() && clientSecret.isNotBlank() && receiverEmail.isNotBlank()

    private val apiBase: String
        get() = if (sandbox) "https://api-m.sandbox.paypal.com" else "https://api-m.paypal.com"

    override fun submit(requestId: String, currency: RewardCurrency, amountCents: Long): ProviderSubmitResult {
        if (!enabled) return ProviderSubmitResult(false, failureCode = "PROVIDER_DISABLED")
        val token = accessToken() ?: return ProviderSubmitResult(false, failureCode = "PAYPAL_AUTH_FAILED")
        val value = money(amountCents)
        val safeRequestId = jsonEscape(requestId)
        val body = "{" +
            "\"sender_batch_header\":{" +
                "\"sender_batch_id\":\"$safeRequestId\"," +
                "\"recipient_type\":\"EMAIL\"," +
                "\"email_subject\":\"Monetizei payout\"," +
                "\"email_message\":\"Seu saque do Monetizei foi enviado.\"" +
            "}," +
            "\"items\":[{" +
                "\"recipient_type\":\"EMAIL\"," +
                "\"receiver\":\"${jsonEscape(receiverEmail)}\"," +
                "\"sender_item_id\":\"$safeRequestId\"," +
                "\"recipient_wallet\":\"PAYPAL\"," +
                "\"amount\":{\"value\":\"$value\",\"currency\":\"${currency.name}\"}," +
                "\"note\":\"Monetizei withdrawal\"" +
            "}]" +
        "}"

        val response = request(
            method = "POST",
            path = "/v1/payments/payouts",
            bearerToken = token,
            body = body,
            requestId = requestId
        ) ?: return ProviderSubmitResult(false, failureCode = "PAYPAL_NETWORK_ERROR")

        if (response.code == 201) {
            val batchId = extractString(response.body, "payout_batch_id")
            return if (batchId.isNullOrBlank()) {
                ProviderSubmitResult(false, failureCode = "PAYPAL_MALFORMED_RESPONSE")
            } else {
                ProviderSubmitResult(true, providerBatchId = batchId)
            }
        }

        if (response.code == 400 && response.body.contains("DUPLICATE", ignoreCase = true)) {
            val batchId = extractPayoutIdFromLink(response.body)
            if (!batchId.isNullOrBlank()) return ProviderSubmitResult(true, providerBatchId = batchId)
        }

        return ProviderSubmitResult(false, failureCode = "PAYPAL_HTTP_${response.code}")
    }

    override fun status(providerBatchId: String): ProviderStatusResult {
        if (!enabled) return ProviderStatusResult(ProviderPayoutState.FAILED, "PROVIDER_DISABLED")
        val token = accessToken() ?: return ProviderStatusResult(ProviderPayoutState.PENDING, "PAYPAL_AUTH_FAILED")
        val response = request(
            method = "GET",
            path = "/v1/payments/payouts/${providerBatchId.encodePathSegment()}",
            bearerToken = token
        ) ?: return ProviderStatusResult(ProviderPayoutState.PENDING, "PAYPAL_NETWORK_ERROR")

        if (response.code !in 200..299) {
            return if (response.code >= 500) {
                ProviderStatusResult(ProviderPayoutState.PENDING, "PAYPAL_HTTP_${response.code}")
            } else {
                ProviderStatusResult(ProviderPayoutState.FAILED, "PAYPAL_HTTP_${response.code}")
            }
        }

        return when (extractString(response.body, "batch_status")?.uppercase()) {
            "SUCCESS" -> ProviderStatusResult(ProviderPayoutState.SUCCESS)
            "DENIED", "CANCELED", "CANCELLED", "FAILED", "BLOCKED", "RETURNED" ->
                ProviderStatusResult(ProviderPayoutState.FAILED, "PAYPAL_BATCH_FAILED")
            else -> ProviderStatusResult(ProviderPayoutState.PENDING)
        }
    }

    private fun accessToken(): String? {
        val credentials = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray(StandardCharsets.UTF_8))
        val connection = (URL("$apiBase/v1/oauth2/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            useCaches = false
            setRequestProperty("Authorization", "Basic $credentials")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write("grant_type=client_credentials") }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) extractString(body, "access_token") else null
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun request(
        method: String,
        path: String,
        bearerToken: String,
        body: String? = null,
        requestId: String? = null
    ): PayPalHttpResponse? {
        val connection = (URL(apiBase + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            requestId?.let { setRequestProperty("PayPal-Request-Id", it) }
            if (body != null) doOutput = true
        }
        return try {
            if (body != null) {
                connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            PayPalHttpResponse(code, stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty())
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun money(cents: Long): String = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

    private fun extractString(body: String, key: String): String? {
        val marker = "\"$key\""
        val keyIndex = body.indexOf(marker)
        if (keyIndex < 0) return null
        val colon = body.indexOf(':', keyIndex + marker.length)
        if (colon < 0) return null
        val quote = body.indexOf('"', colon + 1)
        if (quote < 0) return null
        var index = quote + 1
        val value = StringBuilder()
        var escaped = false
        while (index < body.length) {
            val char = body[index]
            if (escaped) {
                value.append(char)
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                return value.toString()
            } else {
                value.append(char)
            }
            index += 1
        }
        return null
    }

    private fun extractPayoutIdFromLink(body: String): String? {
        val marker = "/v1/payments/payouts/"
        val start = body.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        var end = valueStart
        while (end < body.length && body[end] !in charArrayOf('"', '?', '\\', ' ')) end += 1
        return body.substring(valueStart, end).takeIf { it.isNotBlank() }
    }

    private fun jsonEscape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

    private fun String.encodePathSegment(): String =
        java.net.URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")
}

private data class PayPalHttpResponse(val code: Int, val body: String)
