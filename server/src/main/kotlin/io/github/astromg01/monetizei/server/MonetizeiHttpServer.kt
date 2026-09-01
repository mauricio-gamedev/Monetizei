package io.github.astromg01.monetizei.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.astromg01.monetizei.protocol.ProtocolJson
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

class MonetizeiHttpServer(
    bindAddress: InetSocketAddress = InetSocketAddress("127.0.0.1", 0),
    private val service: SessionIngestService = SessionIngestService(),
    private val rewardService: RewardService? = null,
    private val adminToken: String? = null,
    private val withdrawalService: WithdrawalService? = null
) : AutoCloseable {
    private val server = HttpServer.create(bindAddress, 0).apply {
        executor = Executors.newFixedThreadPool(4)
        createContext("/health") { exchange -> health(exchange) }
        createContext("/v1/installations") { exchange -> register(exchange) }
        createContext("/v1/sessions") { exchange -> submit(exchange) }
        createContext("/v1/wallet") { exchange -> wallet(exchange) }
        createContext("/v1/withdrawals") { exchange -> withdraw(exchange) }
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
            return respond(
                exchange,
                202,
                successfulSessionJson(result.ledgerId.orEmpty(), reward, reward.wallet)
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

    private fun wallet(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            return respond(exchange, 405, "{\"error\":\"method_not_allowed\"}")
        }
        val rewards = rewardService
            ?: return respond(exchange, 503, "{\"error\":\"wallet_unavailable\"}")
        val installationId = queryParameter(exchange, "installationId")
            ?: return respond(exchange, 400, "{\"error\":\"missing_installation_id\"}")
        val validInstallationId = runCatching {
            UUID.fromString(installationId).toString() == installationId
        }.getOrDefault(false)
        if (!validInstallationId) {
            return respond(exchange, 400, "{\"error\":\"invalid_installation_id\"}")
        }
        respond(exchange, 200, "{\"wallet\":${walletJson(rewards.wallet(installationId))}}")
    }

    private fun withdraw(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            return respond(exchange, 405, "{\"error\":\"method_not_allowed\"}")
        }
        val withdrawals = withdrawalService
            ?: return respond(exchange, 503, "{\"result\":\"PROVIDER_DISABLED\"}")
        val envelope = runCatching { ProtocolJson.decodeWithdrawal(readBody(exchange)) }.getOrNull()
            ?: return respond(exchange, 400, "{\"result\":\"INVALID\"}")
        val result = withdrawals.request(envelope, System.currentTimeMillis())
        val status = when (result.code) {
            WithdrawalResultCode.SUBMITTED, WithdrawalResultCode.PROCESSING -> 202
            WithdrawalResultCode.PAID -> 200
            WithdrawalResultCode.NO_AVAILABLE -> 409
            WithdrawalResultCode.PROVIDER_DISABLED -> 503
            WithdrawalResultCode.INVALID -> 400
            WithdrawalResultCode.UNKNOWN_INSTALLATION -> 404
            WithdrawalResultCode.KEY_MISMATCH -> 409
            WithdrawalResultCode.INVALID_SIGNATURE -> 401
            WithdrawalResultCode.STORAGE_FAILURE -> 503
            WithdrawalResultCode.FAILED -> 502
        }
        respond(exchange, status, withdrawalJson(result))
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

    private fun queryParameter(exchange: HttpExchange, name: String): String? {
        val rawQuery = exchange.requestURI.rawQuery ?: return null
        return rawQuery.split('&')
            .asSequence()
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator < 0) return@mapNotNull null
                val key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8)
                if (key != name) return@mapNotNull null
                URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8)
            }
            .firstOrNull()
    }

    private fun successfulSessionJson(
        ledgerId: String,
        reward: RewardDecision,
        wallet: RewardWalletSnapshot
    ): String = "{" +
        "\"accepted\":true," +
        "\"ledgerId\":\"$ledgerId\"," +
        "\"reward\":{" +
            "\"decision\":\"${reward.code}\"," +
            "\"amountCents\":${reward.amountCents}," +
            "\"currency\":\"${reward.currency}\"" +
        "}," +
        "\"wallet\":${walletJson(wallet)}" +
    "}"

    private fun withdrawalJson(result: WithdrawalResult): String = "{" +
        "\"result\":\"${result.code}\"," +
        "\"requestId\":${jsonNullable(result.requestId)}," +
        "\"amountCents\":${result.amountCents}," +
        "\"currency\":\"${result.currency}\"," +
        "\"providerBatchId\":${jsonNullable(result.providerBatchId)}," +
        "\"failureCode\":${jsonNullable(result.failureCode)}," +
        "\"wallet\":${walletJson(result.wallet)}" +
    "}"

    private fun walletJson(wallet: RewardWalletSnapshot): String {
        val brl = wallet.brl
        val usd = wallet.usd
        return "{" +
            "\"pendingCents\":${brl.pendingCents}," +
            "\"approvedCents\":${brl.approvedCents}," +
            "\"availableCents\":${brl.availableCents}," +
            "\"balances\":{" +
                "\"BRL\":${balanceJson(brl)}," +
                "\"USD\":${balanceJson(usd)}" +
            "}" +
        "}"
    }

    private fun balanceJson(balance: CurrencyRewardBalance): String =
        "{\"pendingCents\":${balance.pendingCents}," +
            "\"approvedCents\":${balance.approvedCents}," +
            "\"availableCents\":${balance.availableCents}}"

    private fun jsonNullable(value: String?): String =
        value?.let { "\"${jsonEscape(it)}\"" } ?: "null"

    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

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

    val asaasExplicitlyEnabled = envBoolean("MONETIZEI_ASAAS_PIX_PAYOUTS_ENABLED", false)
    val paypalExplicitlyEnabled = envBoolean("MONETIZEI_PAYPAL_PAYOUTS_ENABLED", false)
    val paypalSandbox = System.getenv("MONETIZEI_PAYPAL_MODE")
        ?.trim()
        ?.lowercase()
        ?.let { it != "live" }
        ?: true

    val payoutGateway: PayoutGateway = when {
        asaasExplicitlyEnabled -> AsaasPixPayoutGateway(
            apiKey = System.getenv("MONETIZEI_ASAAS_API_KEY").orEmpty(),
            pixKey = System.getenv("MONETIZEI_ASAAS_PIX_KEY").orEmpty(),
            pixKeyType = System.getenv("MONETIZEI_ASAAS_PIX_KEY_TYPE").orEmpty()
        )
        paypalExplicitlyEnabled -> PayPalPayoutGateway(
            clientId = System.getenv("MONETIZEI_PAYPAL_CLIENT_ID").orEmpty(),
            clientSecret = System.getenv("MONETIZEI_PAYPAL_CLIENT_SECRET").orEmpty(),
            receiverEmail = System.getenv("MONETIZEI_PAYPAL_RECEIVER_EMAIL").orEmpty(),
            sandbox = paypalSandbox
        )
        else -> DisabledPayoutGateway
    }

    val withdrawalService = WithdrawalService(
        rewardService = rewardService,
        persistence = persistence,
        registrationLookup = { installationId ->
            persistence.loadRegistrations().firstOrNull { it.installationId == installationId }
        },
        gateway = payoutGateway
    )

    val server = MonetizeiHttpServer(
        InetSocketAddress("0.0.0.0", port),
        service,
        rewardService,
        adminToken,
        withdrawalService
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
            "adminApprovalEnabled=${adminToken != null}; payoutsEnabled=${payoutGateway.enabled}; " +
            "payoutProvider=${payoutGateway.providerName}; " +
            "settlementMode=${payoutGateway.settlementMode}"
    )
}

private fun envLong(name: String, default: Long, min: Long, max: Long): Long =
    System.getenv(name)?.toLongOrNull()?.coerceIn(min, max) ?: default

private fun envInt(name: String, default: Int, min: Int, max: Int): Int =
    System.getenv(name)?.toIntOrNull()?.coerceIn(min, max) ?: default

private fun envBoolean(name: String, default: Boolean): Boolean =
    System.getenv(name)?.trim()?.lowercase()?.let { it in setOf("1", "true", "yes", "on") } ?: default

private fun envRewardCurrency(name: String, default: RewardCurrency): RewardCurrency =
    System.getenv(name)
        ?.trim()
        ?.uppercase()
        ?.let { value -> runCatching { RewardCurrency.valueOf(value) }.getOrNull() }
        ?: default
