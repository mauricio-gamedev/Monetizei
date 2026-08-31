package io.github.astromg01.monetizei.server

import io.github.astromg01.monetizei.protocol.CanonicalSessionCodec
import io.github.astromg01.monetizei.protocol.InstallationRegistration
import io.github.astromg01.monetizei.protocol.KeyIds
import io.github.astromg01.monetizei.protocol.ProtocolJson
import io.github.astromg01.monetizei.protocol.SessionPayload
import io.github.astromg01.monetizei.protocol.SessionProtocol
import io.github.astromg01.monetizei.protocol.SignedSessionEnvelope
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MonetizeiHttpServerTest {
    private lateinit var server: MonetizeiHttpServer

    @Before
    fun setUp() {
        server = MonetizeiHttpServer(InetSocketAddress("127.0.0.1", 0))
        server.start()
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun registrationAndSignedSessionWorkOverRealHttp() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val installationId = UUID.randomUUID().toString()
        val publicBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val keyId = KeyIds.fromEncodedPublicKey(keyPair.public.encoded)
        val registration = InstallationRegistration(1, installationId, keyId, publicBase64, SessionProtocol.SIGNATURE_ALGORITHM, "0.3.0", System.currentTimeMillis())

        assertEquals(201, post("/v1/installations", ProtocolJson.encodeRegistration(registration)).first)

        val payload = SessionPayload(1, installationId, UUID.randomUUID().toString(), 1L, 1_000L, 31_000L, 30_000L, 20, "0.3.0")
        val signer = Signature.getInstance(SessionProtocol.SIGNATURE_ALGORITHM).apply {
            initSign(keyPair.private)
            update(CanonicalSessionCodec.bytes(payload))
        }
        val envelope = SignedSessionEnvelope(payload, keyId, SessionProtocol.SIGNATURE_ALGORITHM, Base64.getEncoder().encodeToString(signer.sign()))

        assertEquals(202, post("/v1/sessions", ProtocolJson.encodeSession(envelope)).first)
        val replay = post("/v1/sessions", ProtocolJson.encodeSession(envelope))
        assertEquals(409, replay.first)
    }

    @Test
    fun adminApprovalRequiresTokenAndApprovesOnlyOnePendingReward() {
        server.close()
        val installationId = "test-installation"
        val rewardService = RewardService(
            initialEntries = listOf(
                RewardLedgerEntry(
                    rewardId = "reward-1",
                    installationId = installationId,
                    gameplayLedgerId = "ledger-1",
                    sessionId = "session-1",
                    amountCents = 1,
                    currency = RewardCurrency.BRL,
                    state = RewardState.PENDING,
                    policyCode = "test",
                    createdAtEpochMs = 1_000L,
                    updatedAtEpochMs = 1_000L
                ),
                RewardLedgerEntry(
                    rewardId = "reward-2",
                    installationId = installationId,
                    gameplayLedgerId = "ledger-2",
                    sessionId = "session-2",
                    amountCents = 1,
                    currency = RewardCurrency.BRL,
                    state = RewardState.PENDING,
                    policyCode = "test",
                    createdAtEpochMs = 2_000L,
                    updatedAtEpochMs = 2_000L
                )
            )
        )
        server = MonetizeiHttpServer(
            bindAddress = InetSocketAddress("127.0.0.1", 0),
            rewardService = rewardService,
            adminToken = "test-admin-token"
        )
        server.start()

        assertEquals(401, post("/v1/admin/rewards/approve-next", "{}").first)
        val approved = post(
            "/v1/admin/rewards/approve-next",
            "{}",
            bearerToken = "test-admin-token"
        )
        assertEquals(200, approved.first)
        assertTrue(approved.second.contains("\"rewardId\":\"reward-1\""))
        assertTrue(approved.second.contains("\"state\":\"APPROVED\""))

        val wallet = rewardService.wallet(installationId).brl
        assertEquals(1L, wallet.pendingCents)
        assertEquals(1L, wallet.approvedCents)
        assertEquals(0L, wallet.availableCents)
    }

    @Test
    fun adminAvailabilityRequiresTokenAndMakesOnlyOneApprovedRewardAvailable() {
        server.close()
        val installationId = "test-installation"
        val rewardService = RewardService(
            initialEntries = listOf(
                RewardLedgerEntry(
                    rewardId = "approved-1",
                    installationId = installationId,
                    gameplayLedgerId = "ledger-a1",
                    sessionId = "session-a1",
                    amountCents = 1,
                    currency = RewardCurrency.BRL,
                    state = RewardState.APPROVED,
                    policyCode = "test",
                    createdAtEpochMs = 1_000L,
                    updatedAtEpochMs = 1_500L
                ),
                RewardLedgerEntry(
                    rewardId = "approved-2",
                    installationId = installationId,
                    gameplayLedgerId = "ledger-a2",
                    sessionId = "session-a2",
                    amountCents = 1,
                    currency = RewardCurrency.BRL,
                    state = RewardState.APPROVED,
                    policyCode = "test",
                    createdAtEpochMs = 2_000L,
                    updatedAtEpochMs = 2_500L
                )
            )
        )
        server = MonetizeiHttpServer(
            bindAddress = InetSocketAddress("127.0.0.1", 0),
            rewardService = rewardService,
            adminToken = "test-admin-token"
        )
        server.start()

        assertEquals(401, post("/v1/admin/rewards/make-next-available", "{}").first)
        val available = post(
            "/v1/admin/rewards/make-next-available",
            "{}",
            bearerToken = "test-admin-token"
        )
        assertEquals(200, available.first)
        assertTrue(available.second.contains("\"rewardId\":\"approved-1\""))
        assertTrue(available.second.contains("\"state\":\"AVAILABLE\""))

        val wallet = rewardService.wallet(installationId).brl
        assertEquals(0L, wallet.pendingCents)
        assertEquals(1L, wallet.approvedCents)
        assertEquals(1L, wallet.availableCents)
    }

    private fun post(path: String, body: String, bearerToken: String? = null): Pair<Int, String> {
        val connection = (URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2_000
            readTimeout = 2_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (bearerToken != null) {
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
        }
        return try {
            connection.outputStream.bufferedWriter().use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            code to stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
