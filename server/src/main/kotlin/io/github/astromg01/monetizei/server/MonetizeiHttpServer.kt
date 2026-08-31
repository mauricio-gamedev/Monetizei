package io.github.astromg01.monetizei.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.astromg01.monetizei.protocol.ProtocolJson
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.Executors

class MonetizeiHttpServer(
    bindAddress: InetSocketAddress = InetSocketAddress("127.0.0.1", 0),
    private val service: SessionIngestService = SessionIngestService(),
    private val rewardService: RewardService? = null,
    private val adminToken: String? = null
) : AutoCloseable {
    private val server = HttpServer.create(bindAddress, 0).apply {
        executor = Executors.newFixedThreadPool(4)
        createContext("/health") { exchange -> health(exchange) }
        createContext("/v1/installations") { exchange -> register(exchange) }
        createContext("/v1/sessions") { exchange -> submit(exchange) }
        createContext("/v1/admin/rewards/approve-next") { exchange -> approveNextReward(exchange) }
        createContext("/v1/admin/rewards/make-next-available") { exchange -> makeNextRewardAvailable(exchange) }
    }

    val port: Int get() = server.address.port

    fun start() = server.start()

    override fun close() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }

    private fun health(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return respond(exchange, 405, "{\"error\":\"method_not_allowed\"}")
        respond(
            exchange,
            200,
            "{\"ok\":true,\"storage\":\"ready\",\"walletVersion\":2,\"currencies\":[\"BRL\",\"USD\"]}"
        )
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
                successfulSessionJson(result.ledgerId.orEmpty(), reward, wallet)
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

    private fun approveNextReward(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            return respond(exchange, 405, "{\"error\":\"method_not_allowed\"}")
        }

        val rewards = rewardService
        val expectedToken = adminToken?.takeIf { it.isNotBlank() }
        if (rewards == null || expectedToken == null) {
            return respond(exchange, 404, "{\"error\":\"admin_disabled\"}")
        }
        if (!authorized(exchange, expectedToken)) {
            return respond(exchange, 401, "{\"error\":\"unauthorized\"}")
        }

        val pending = rewards.snapshot()
            .asSequence()
            .filter { it.state == RewardState.PENDING }
            .minWithOrNull(compareBy<RewardLedgerEntry> { it.createdAtEpochMs }.thenBy { it.rewardId })
            ?: return respond(exchange, 404, "{\"result\":\"NO_PENDING\"}")

        if (!rewards.approve(pending.rewardId, System.currentTimeMillis())) {
            return respond(exchange, 503, "{\"result\":\"TRANSITION_FAILED\"}")
        }

        val approved = rewards.snapshot().first { it.rewardId == pending.rewardId }
        respond(
            exchange,
            200,
            "{\"result\":\"APPROVED\",\"rewardId\":\"${approved.rewardId}\",\"amountCents\":${approved.amountCents},\"currency\":\"${approved.currency}\",\"state\":\"${approved.state}\"}"
        )
    }

    private fun makeNextRewardAvailable(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            return respond(exchange, 405, "{\"error\":\"method_not_allowed\"}")
        }

        val rewards = rewardService
        val expectedToken = adminToken?.takeIf { it.isNotBlank() }
        if (rewards == null || expectedToken == null) {
            return respond(exchange, 404, "{\"error\":\"admin_disabled\"}")
        }
        if (!authorized(exchange, expectedToken)) {
            return respond(exchange, 401, "{\"error\":\"unauthorized\"}")
        }

        val approved = rewards.snapshot()
            .asSequence()
            .filter { it.state == RewardState.APPROVED }
            .minWithOrNull(compareBy<RewardLedgerEntry> { it.createdAtEpochMs }.thenBy { it.rewardId })
            ?: return respond(exchange, 404, "{\"result\":\"NO_APPROVED\"}")

        if (!rewards.makeAvailable(approved.rewardId, System.currentTimeMillis())) {
            return respond(exchange, 503, "{\"result\":\"TRANSITION_FAILED\"}")
        }

        val available = rewards.snapshot().first { it.rewardId == approved.rewardId }
        respond(
            exchange,
            200,
            "{\"result\":\"AVAILABLE\",\"rewardId\":\"${available.rewardId}\",\"amountCents\":${available.amountCents},\"currency\":\"${available.currency}\",\"state\":\"${available.state}\"}"
        )
    }

    private fun authorized(exchange: HttpExchange, expectedToken: String): Boolean {
        val header = exchange.requestHeaders.getFirst("Authorization") ?: return false
        val prefix = "Bearer "
        if (!header.startsWith(prefix)) return false
        val supplied = header.substring(prefix.length)
        return MessageDigest.isEqual(
            supplied.toByteArray(StandardCharsets.UTF_8),
            expectedToken.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun successfulSessionJson(
        ledgerId: String,
        reward: RewardDecision,
        wallet: RewardWalletSnapshot
    ): String {
        val brl = wallet.brl
        val usd = wallet.usd
        return "{" +
            "\"accepted\":true," +
            "\"ledgerId\":\"$ledgerId\"," +
            "\"reward\":{" +
                "\"decision\":\"${reward.code}\"," +
                "\"amountCents\":${reward.amountCents}," +
                "\"currency\":\"${reward.currency}\"" +
            "}," +
            "\"wallet\":{" +
                // v0.5 BRL-only clients keep reading these legacy aliases.
                "\"pendingCents\":${brl.pendingCents}," +
                "\"approvedCents\":${brl.approvedCents}," +
                "\"availableCents\":${brl.availableCents}," +
                "\"balances\":{" +
                    "\"BRL\":${balanceJson(brl)}," +
                    "\"USD\":${balanceJson(usd)}" +
                "}" +
            "}" +
        "}"
    }

    private fun balanceJson(balance: CurrencyRewardBalance): String =
        "{\"pendingCents\":${balance.pendingCents}," +
            "\"approvedCents\":${balance.approvedCents}," +
            "\"availableCents\":${balance.availableCents}}"

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
        maxRewardsPerInstallationPerUtcDay = envInt("MONETIZEI_MAX_REWARDS_PER_INSTALLATION_DAY", 10, 0, 10_000),
        currency = envRewardCurrency("MONETIZEI_REWARD_CURRENCY", RewardCurrency.BRL)
    )
    val rewardService = RewardService(persistence = persistence, policy = rewardPolicy)
    val service = SessionIngestService(persistence = persistence, rewardService = rewardService)
    val adminToken = System.getenv("MONETIZEI_ADMIN_TOKEN")?.trim()?.takeIf { it.isNotBlank() }
    val server = MonetizeiHttpServer(
        InetSocketAddress("0.0.0.0", port),
        service,
        rewardService,
        adminToken
    )

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { server.close() }
            runCatching { persistence.close() }
        }
    )
    server.start()
    println(
        "Monetizei backend listening on 0.0.0.0:$port with persistent storage; " +
            "rewardPolicyEnabled=${rewardPolicy.enabled}; rewardCurrency=${rewardPolicy.currency}; " +
            "adminApprovalEnabled=${adminToken != null}"
    )
}

private fun envLong(name: String, default: Long, min: Long, max: Long): Long =
    System.getenv(name)?.toLongOrNull()?.coerceIn(min, max) ?: default

private fun envInt(name: String, default: Int, min: Int, max: Int): Int =
    System.getenv(name)?.toIntOrNull()?.coerceIn(min, max) ?: default

private fun envRewardCurrency(name: String, default: RewardCurrency): RewardCurrency =
    System.getenv(name)
        ?.trim()
        ?.uppercase()
        ?.let { value -> runCatching { RewardCurrency.valueOf(value) }.getOrNull() }
        ?: default
