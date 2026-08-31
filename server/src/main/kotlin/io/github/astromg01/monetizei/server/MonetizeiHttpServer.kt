package io.github.astromg01.monetizei.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.astromg01.monetizei.protocol.ProtocolJson
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.concurrent.Executors

class MonetizeiHttpServer(
    bindAddress: InetSocketAddress = InetSocketAddress("127.0.0.1", 0),
    private val service: SessionIngestService = SessionIngestService()
) : AutoCloseable {
    private val server = HttpServer.create(bindAddress, 0).apply {
        executor = Executors.newFixedThreadPool(4)
        createContext("/health") { exchange -> health(exchange) }
        createContext("/v1/installations") { exchange -> register(exchange) }
        createContext("/v1/sessions") { exchange -> submit(exchange) }
    }

    val port: Int get() = server.address.port

    fun start() = server.start()

    override fun close() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun health(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respond(exchange, 405, "{\"error\":\"method_not_allowed\"}")
        respond(exchange, 200, "{\"ok\":true,\"storage\":\"ready\",\"walletVersion\":1}")
    }

    private fun register(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return respond(exchange, 405, "{\"error\":\"method_not_allowed\"}")
        val registration = runCatching { ProtocolJson.decodeRegistration(readBody(exchange)) }.getOrNull()
            ?: return respond(exchange, 400, "{\"result\":\"INVALID\"}")

        when (val result = service.register(registration)) {
            RegistrationResult.CREATED -> respond(exchange, 201, "{\"result\":\"$result\"}")
            RegistrationResult.ALREADY_REGISTERED -> respond(exchange, 200, "{\"result\":\"$result\"}")
            RegistrationResult.KEY_CONFLICT -> respond(exchange, 409, "{\"result\":\"$result\"}")
            RegistrationResult.INVALID -> respond(exchange, 400, "{\"result\":\"$result\"}")
            RegistrationResult.STORAGE_FAILURE -> respond(exchange, 503, "{\"result\":\"$result\"}")
        }
    }

    private fun submit(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return respond(exchange, 405, "{\"error\":\"method_not_allowed\"}")
        val envelope = runCatching { ProtocolJson.decodeSession(readBody(exchange)) }.getOrNull()
            ?: return respond(exchange, 400, "{\"accepted\":false,\"reason\":\"MALFORMED_SESSION\"}")

        val result = service.submit(envelope, System.currentTimeMillis())
        if (result.accepted) {
            val reward = result.rewardDecision ?: RewardDecision(RewardDecisionCode.DISABLED)
            val wallet = reward.wallet
            return respond(
                exchange,
                202,
                "{\"accepted\":true,\"ledgerId\":\"${result.ledgerId}\",\"reward\":{\"decision\":\"${reward.code}\",\"amountCents\":${reward.amountCents}},\"wallet\":{\"pendingCents\":${wallet.pendingCents},\"approvedCents\":${wallet.approvedCents},\"availableCents\":${wallet.availableCents}}}"
            )
        }

        val reason = result.rejectReason ?: IngestRejectReason.MALFORMED_SESSION
        val status = when (reason) {
            IngestRejectReason.UNKNOWN_INSTALLATION -> 404
            IngestRejectReason.KEY_MISMATCH -> 409
            IngestRejectReason.MALFORMED_SESSION -> 400
            IngestRejectReason.INVALID_SIGNATURE -> 401
            IngestRejectReason.REPLAY -> 409
            IngestRejectReason.RATE_LIMITED -> 429
            IngestRejectReason.STORAGE_FAILURE -> 503
        }
        respond(exchange, status, "{\"accepted\":false,\"reason\":\"$reason\"}")
    }

    private fun readBody(exchange: HttpExchange): String =
        exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080
    val dbPath = Paths.get(
        System.getenv("MONETIZEI_DB_PATH")?.takeIf { it.isNotBlank() }
            ?: "./data/monetizei.db"
    )
    val persistence = SqliteServerPersistence(dbPath)
    val rewardPolicy = RewardPolicy(
        rewardCentsPerEligibleSession = envLong("MONETIZEI_REWARD_CENTS_PER_SESSION", 0L, 0L, 10_000L),
        dailyBudgetCents = envLong("MONETIZEI_DAILY_REWARD_BUDGET_CENTS", 0L, 0L, 10_000_000L),
        minVerifiedScore = envLong("MONETIZEI_MIN_REWARD_SCORE", 20L, 0L, 10_000L),
        maxRewardsPerInstallationPerUtcDay = envInt("MONETIZEI_MAX_REWARDS_PER_INSTALLATION_DAY", 10, 0, 10_000)
    )
    val rewardService = RewardService(persistence = persistence, policy = rewardPolicy)
    val service = SessionIngestService(persistence = persistence, rewardService = rewardService)
    val server = MonetizeiHttpServer(InetSocketAddress("0.0.0.0", port), service)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { server.close() }
            runCatching { persistence.close() }
        }
    )
    server.start()
    println(
        "Monetizei backend listening on 0.0.0.0:$port with persistent storage; " +
            "rewardPolicyEnabled=${rewardPolicy.enabled}"
    )
}

private fun envLong(name: String, default: Long, min: Long, max: Long): Long =
    System.getenv(name)?.toLongOrNull()?.coerceIn(min, max) ?: default

private fun envInt(name: String, default: Int, min: Int, max: Int): Int =
    System.getenv(name)?.toIntOrNull()?.coerceIn(min, max) ?: default
