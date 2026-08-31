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

    private fun post(path: String, body: String): Pair<Int, String> {
        val connection = (URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2_000
            readTimeout = 2_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
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
