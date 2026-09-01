package io.github.astromg01.monetizei.server

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneOffset

internal data class AsaasHttpResponse(val code: Int, val body: String)

internal interface AsaasHttpClient {
    fun request(method: String, url: String, headers: Map<String, String>, body: String? = null): AsaasHttpResponse?
}

internal class UrlConnectionAsaasHttpClient(
    private val connectTimeoutMs: Int = 7_000,
    private val readTimeoutMs: Int = 10_000
) : AsaasHttpClient {
    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?
    ): AsaasHttpResponse? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) doOutput = true
        }
        return try {
            if (body != null) {
                connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            AsaasHttpResponse(
                code,
                stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            )
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

enum class AsaasPixKeyType {
    CPF,
    CNPJ,
    EMAIL,
    PHONE,
    EVP
}

class AsaasPixPayoutGateway(
    private val apiKey: String,
    pixKey: String,
    pixKeyType: String,
    private val apiBase: String = PRODUCTION_API_BASE,
    private val httpClient: AsaasHttpClient = UrlConnectionAsaasHttpClient(),
    private val utcDate: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) }
) : PayoutGateway {
    private val keyType = runCatching { AsaasPixKeyType.valueOf(pixKeyType.trim().uppercase()) }.getOrNull()
    private val normalizedPixKey = keyType?.let { normalizePixKey(pixKey, it) }.orEmpty()

    override val providerName: String = "asaas_pix"
    override val settlementMode: PayoutSettlementMode = PayoutSettlementMode.LIVE
    override val retryStrategy: PayoutRetryStrategy = PayoutRetryStrategy.RECONCILE_ONLY
    override val enabled: Boolean =
        apiBase == PRODUCTION_API_BASE &&
            apiKey.trim().startsWith(PRODUCTION_KEY_PREFIX) &&
            keyType != null &&
            validPixKey(normalizedPixKey, keyType)

    override fun submit(requestId: String, currency: RewardCurrency, amountCents: Long): ProviderSubmitResult {
        if (!enabled) return ProviderSubmitResult(false, failureCode = "PROVIDER_DISABLED")
        if (currency != RewardCurrency.BRL) {
            return ProviderSubmitResult(false, failureCode = "ASAAS_UNSUPPORTED_CURRENCY")
        }
        if (amountCents <= 0L) {
            return ProviderSubmitResult(false, failureCode = "ASAAS_INVALID_AMOUNT")
        }

        findExistingTransfer(requestId)?.let { transferId ->
            return ProviderSubmitResult(true, providerBatchId = transferId)
        }

        val body = "{" +
            "\"value\":${money(amountCents)}," +
            "\"operationType\":\"PIX\"," +
            "\"pixAddressKey\":\"${jsonEscape(normalizedPixKey)}\"," +
            "\"pixAddressKeyType\":\"${keyType!!.name}\"," +
            "\"description\":\"Monetizei withdrawal\"," +
            "\"externalReference\":\"${jsonEscape(requestId)}\"" +
        "}"

        val response = request("POST", "/transfers", body)
            ?: return ambiguous(requestId, "ASAAS_AMBIGUOUS_NETWORK")

        if (response.code in 200..299) {
            val transferId = extractString(response.body, "id")
            return if (transferId.isNullOrBlank()) {
                ambiguous(requestId, "ASAAS_AMBIGUOUS_MALFORMED_RESPONSE")
            } else {
                ProviderSubmitResult(true, providerBatchId = transferId)
            }
        }

        if (response.code == 409 || response.code >= 500) {
            return ambiguous(requestId, "ASAAS_AMBIGUOUS_HTTP_${response.code}")
        }

        val errorCode = extractString(response.body, "code")
            ?.takeIf { it.isNotBlank() }
            ?.uppercase()
            ?.replace(Regex("[^A-Z0-9_]+"), "_")
        return ProviderSubmitResult(
            accepted = false,
            failureCode = errorCode?.let { "ASAAS_$it" } ?: "ASAAS_HTTP_${response.code}",
            retryable = false
        )
    }

    override fun reconcile(
        requestId: String,
        currency: RewardCurrency,
        amountCents: Long
    ): ProviderSubmitResult {
        if (!enabled) return ProviderSubmitResult(false, failureCode = "PROVIDER_DISABLED", retryable = true)
        if (currency != RewardCurrency.BRL) {
            return ProviderSubmitResult(false, failureCode = "ASAAS_UNSUPPORTED_CURRENCY")
        }
        val transferId = findExistingTransfer(requestId)
        return if (transferId.isNullOrBlank()) {
            ProviderSubmitResult(false, failureCode = "ASAAS_RECONCILING", retryable = true)
        } else {
            ProviderSubmitResult(true, providerBatchId = transferId)
        }
    }

    override fun status(providerBatchId: String): ProviderStatusResult {
        if (!enabled) return ProviderStatusResult(ProviderPayoutState.PENDING, "PROVIDER_DISABLED")
        val response = request("GET", "/transfers/${providerBatchId.encodePathSegment()}")
            ?: return ProviderStatusResult(ProviderPayoutState.PENDING, "ASAAS_NETWORK_ERROR")

        if (response.code !in 200..299) {
            return ProviderStatusResult(
                ProviderPayoutState.PENDING,
                "ASAAS_STATUS_HTTP_${response.code}"
            )
        }

        return when (extractString(response.body, "status")?.uppercase()) {
            "DONE" -> ProviderStatusResult(ProviderPayoutState.SUCCESS)
            "FAILED", "CANCELLED", "CANCELED" ->
                ProviderStatusResult(ProviderPayoutState.FAILED, "ASAAS_TRANSFER_FAILED")
            else -> ProviderStatusResult(ProviderPayoutState.PENDING)
        }
    }

    private fun ambiguous(requestId: String, failureCode: String): ProviderSubmitResult {
        val transferId = findExistingTransfer(requestId)
        return if (transferId.isNullOrBlank()) {
            ProviderSubmitResult(false, failureCode = failureCode, retryable = true)
        } else {
            ProviderSubmitResult(true, providerBatchId = transferId)
        }
    }

    private fun findExistingTransfer(requestId: String): String? {
        val today = utcDate()
        val days = listOf(today, today.minusDays(1))
        for (day in days) {
            var offset = 0
            repeat(MAX_RECONCILIATION_PAGES) {
                val path = "/transfers?dateCreated%5Bge%5D=$day&dateCreated%5Ble%5D=$day&limit=$PAGE_SIZE&offset=$offset"
                val response = request("GET", path) ?: return@repeat
                if (response.code !in 200..299) return@repeat
                findTransferIdByExternalReference(response.body, requestId)?.let { return it }
                val hasMore = extractBoolean(response.body, "hasMore") ?: false
                if (!hasMore) return@repeat
                offset += PAGE_SIZE
            }
        }
        return null
    }

    private fun request(method: String, path: String, body: String? = null): AsaasHttpResponse? =
        httpClient.request(
            method = method,
            url = apiBase.trimEnd('/') + path,
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "User-Agent" to USER_AGENT,
                "access_token" to apiKey.trim()
            ),
            body = body
        )

    private fun findTransferIdByExternalReference(body: String, requestId: String): String? {
        var cursor = 0
        while (cursor < body.length) {
            val referenceKey = body.indexOf("\"externalReference\"", cursor)
            if (referenceKey < 0) return null
            val reference = extractStringAtKey(body, referenceKey)
            if (reference == requestId) {
                var idCursor = referenceKey - 1
                while (idCursor >= 0) {
                    val idKey = body.lastIndexOf("\"id\"", idCursor)
                    if (idKey < 0) break
                    val id = extractStringAtKey(body, idKey)
                    if (!id.isNullOrBlank()) return id
                    idCursor = idKey - 1
                }
            }
            cursor = referenceKey + 1
        }
        return null
    }

    private fun extractString(body: String, key: String): String? {
        val keyIndex = body.indexOf("\"$key\"")
        return if (keyIndex < 0) null else extractStringAtKey(body, keyIndex)
    }

    private fun extractStringAtKey(body: String, keyIndex: Int): String? {
        val colon = body.indexOf(':', keyIndex)
        if (colon < 0) return null
        var start = colon + 1
        while (start < body.length && body[start].isWhitespace()) start += 1
        if (start >= body.length || body[start] != '"') return null
        start += 1
        val value = StringBuilder()
        var escaped = false
        var index = start
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

    private fun extractBoolean(body: String, key: String): Boolean? {
        val marker = "\"$key\""
        val keyIndex = body.indexOf(marker)
        if (keyIndex < 0) return null
        val colon = body.indexOf(':', keyIndex + marker.length)
        if (colon < 0) return null
        var start = colon + 1
        while (start < body.length && body[start].isWhitespace()) start += 1
        return when {
            body.startsWith("true", start) -> true
            body.startsWith("false", start) -> false
            else -> null
        }
    }

    private fun money(cents: Long): String =
        "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

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
        URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

    private companion object {
        const val PRODUCTION_API_BASE = "https://api.asaas.com/v3"
        const val PRODUCTION_KEY_PREFIX = "\$aact_prod_"
        const val USER_AGENT = "Monetizei/0.7.0"
        const val PAGE_SIZE = 100
        const val MAX_RECONCILIATION_PAGES = 5

        fun normalizePixKey(value: String, type: AsaasPixKeyType): String = when (type) {
            AsaasPixKeyType.CPF, AsaasPixKeyType.CNPJ -> value.filter(Char::isDigit)
            AsaasPixKeyType.PHONE -> value.filter(Char::isDigit).let { digits ->
                if (digits.length == 13 && digits.startsWith("55")) digits.drop(2) else digits
            }
            AsaasPixKeyType.EMAIL -> value.trim().lowercase()
            AsaasPixKeyType.EVP -> value.trim()
        }

        fun validPixKey(value: String, type: AsaasPixKeyType): Boolean = when (type) {
            AsaasPixKeyType.CPF -> value.length == 11 && value.all(Char::isDigit)
            AsaasPixKeyType.CNPJ -> value.length == 14 && value.all(Char::isDigit)
            AsaasPixKeyType.PHONE -> value.length == 11 && value.all(Char::isDigit)
            AsaasPixKeyType.EMAIL -> value.contains('@') && value.length in 3..254
            AsaasPixKeyType.EVP -> value.length in 20..80
        }
    }
}
