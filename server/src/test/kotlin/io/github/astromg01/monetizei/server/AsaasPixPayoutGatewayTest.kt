package io.github.astromg01.monetizei.server

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsaasPixPayoutGatewayTest {
    @Test
    fun createsProductionPixTransferWithRequiredHeadersAndExternalReference() {
        val client = RecordingClient { method, url, _, _ ->
            when {
                method == "GET" && url.contains("/transfers?") ->
                    AsaasHttpResponse(200, "{\"hasMore\":false,\"data\":[]}")
                method == "POST" && url.endsWith("/transfers") ->
                    AsaasHttpResponse(200, "{\"id\":\"transfer-1\",\"status\":\"PENDING\"}")
                else -> AsaasHttpResponse(404, "{}")
            }
        }
        val gateway = gateway(client)

        val result = gateway.submit("request-123", RewardCurrency.BRL, 1L)

        assertTrue(gateway.enabled)
        assertTrue(result.accepted)
        assertEquals("transfer-1", result.providerBatchId)
        val post = client.calls.single { it.method == "POST" }
        assertEquals("https://api.asaas.com/v3/transfers", post.url)
        assertEquals("\$aact_prod_test-key", post.headers["access_token"])
        assertEquals("Monetizei/0.7.0", post.headers["User-Agent"])
        assertTrue(post.body.orEmpty().contains("\"value\":0.01"))
        assertTrue(post.body.orEmpty().contains("\"operationType\":\"PIX\""))
        assertTrue(post.body.orEmpty().contains("\"pixAddressKey\":\"12345678901\""))
        assertTrue(post.body.orEmpty().contains("\"pixAddressKeyType\":\"CPF\""))
        assertTrue(post.body.orEmpty().contains("\"externalReference\":\"request-123\""))
    }

    @Test
    fun ambiguousCreateRecoversExistingTransferByExternalReferenceWithoutDuplicatePost() {
        var postSeen = false
        val client = RecordingClient { method, url, _, _ ->
            when {
                method == "POST" -> {
                    postSeen = true
                    null
                }
                method == "GET" && url.contains("/transfers?") && postSeen ->
                    AsaasHttpResponse(
                        200,
                        "{\"hasMore\":false,\"data\":[{\"id\":\"transfer-recovered\",\"externalReference\":\"request-ambiguous\"}]}"
                    )
                method == "GET" && url.contains("/transfers?") ->
                    AsaasHttpResponse(200, "{\"hasMore\":false,\"data\":[]}")
                else -> AsaasHttpResponse(404, "{}")
            }
        }
        val gateway = gateway(client)

        val result = gateway.submit("request-ambiguous", RewardCurrency.BRL, 125L)

        assertTrue(result.accepted)
        assertEquals("transfer-recovered", result.providerBatchId)
        assertEquals(1, client.calls.count { it.method == "POST" })
    }

    @Test
    fun mapsTransferStatusWithoutMarkingPendingAsPaid() {
        val responses = ArrayDeque(
            listOf(
                AsaasHttpResponse(200, "{\"id\":\"tr-1\",\"status\":\"PENDING\"}"),
                AsaasHttpResponse(200, "{\"id\":\"tr-1\",\"status\":\"DONE\"}"),
                AsaasHttpResponse(200, "{\"id\":\"tr-1\",\"status\":\"CANCELLED\"}")
            )
        )
        val client = RecordingClient { method, url, _, _ ->
            if (method == "GET" && url.contains("/transfers/tr-1")) responses.removeFirst() else AsaasHttpResponse(404, "{}")
        }
        val gateway = gateway(client)

        assertEquals(ProviderPayoutState.PENDING, gateway.status("tr-1").state)
        assertEquals(ProviderPayoutState.SUCCESS, gateway.status("tr-1").state)
        assertEquals(ProviderPayoutState.FAILED, gateway.status("tr-1").state)
    }

    @Test
    fun rejectsNonProductionApiKeyAndInvalidPixConfiguration() {
        val client = RecordingClient { _, _, _, _ -> AsaasHttpResponse(200, "{}") }
        val sandboxKey = AsaasPixPayoutGateway(
            apiKey = "\$aact_hmlg_test",
            pixKey = "12345678901",
            pixKeyType = "CPF",
            httpClient = client,
            utcDate = { LocalDate.of(2026, 8, 31) }
        )
        val invalidCpf = AsaasPixPayoutGateway(
            apiKey = "\$aact_prod_test",
            pixKey = "123",
            pixKeyType = "CPF",
            httpClient = client,
            utcDate = { LocalDate.of(2026, 8, 31) }
        )

        assertFalse(sandboxKey.enabled)
        assertFalse(invalidCpf.enabled)
    }

    private fun gateway(client: AsaasHttpClient) = AsaasPixPayoutGateway(
        apiKey = "\$aact_prod_test-key",
        pixKey = "123.456.789-01",
        pixKeyType = "CPF",
        httpClient = client,
        utcDate = { LocalDate.of(2026, 8, 31) }
    )

    private data class Call(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?
    )

    private class RecordingClient(
        private val handler: (String, String, Map<String, String>, String?) -> AsaasHttpResponse?
    ) : AsaasHttpClient {
        val calls = mutableListOf<Call>()

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?
        ): AsaasHttpResponse? {
            calls += Call(method, url, headers, body)
            return handler(method, url, headers, body)
        }
    }
}
