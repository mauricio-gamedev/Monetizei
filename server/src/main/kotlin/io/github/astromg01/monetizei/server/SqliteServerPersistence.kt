package io.github.astromg01.monetizei.server

import io.github.astromg01.monetizei.protocol.InstallationRegistration
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class SqliteServerPersistence(dbPath: Path) : SessionPersistence, RewardPersistence, PayoutPersistence, AutoCloseable {
    private val connection: Connection

    init {
        val absolute = dbPath.toAbsolutePath().normalize()
        Files.createDirectories(absolute.parent)
        connection = DriverManager.getConnection("jdbc:sqlite:$absolute")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA synchronous = NORMAL")
            statement.execute("PRAGMA busy_timeout = 5000")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS installations (
                    installation_id TEXT PRIMARY KEY,
                    protocol_version INTEGER NOT NULL,
                    key_id TEXT NOT NULL,
                    public_key_base64 TEXT NOT NULL,
                    signature_algorithm TEXT NOT NULL,
                    app_version TEXT NOT NULL,
                    created_at_epoch_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS gameplay_ledger (
                    ledger_id TEXT PRIMARY KEY,
                    installation_id TEXT NOT NULL,
                    session_id TEXT NOT NULL UNIQUE,
                    sequence INTEGER NOT NULL,
                    started_at_epoch_ms INTEGER NOT NULL,
                    finished_at_epoch_ms INTEGER NOT NULL,
                    duration_ms INTEGER NOT NULL,
                    verified_score_units INTEGER NOT NULL,
                    app_version TEXT NOT NULL,
                    accepted_at_epoch_ms INTEGER NOT NULL,
                    UNIQUE(installation_id, sequence),
                    FOREIGN KEY(installation_id) REFERENCES installations(installation_id)
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS reward_ledger (
                    reward_id TEXT PRIMARY KEY,
                    installation_id TEXT NOT NULL,
                    gameplay_ledger_id TEXT NOT NULL UNIQUE,
                    session_id TEXT NOT NULL UNIQUE,
                    amount_cents INTEGER NOT NULL CHECK(amount_cents > 0),
                    currency TEXT NOT NULL DEFAULT 'BRL' CHECK(currency IN ('BRL','USD')),
                    state TEXT NOT NULL CHECK(state IN ('PENDING','APPROVED','AVAILABLE','PAYOUT_PENDING','PAID')),
                    policy_code TEXT NOT NULL,
                    created_at_epoch_ms INTEGER NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL,
                    FOREIGN KEY(installation_id) REFERENCES installations(installation_id),
                    FOREIGN KEY(gameplay_ledger_id) REFERENCES gameplay_ledger(ledger_id)
                )
                """.trimIndent()
            )
        }

        migrateRewardCurrencyColumn()
        migrateRewardStateConstraint()
        createPayoutTablesAndIndexes()
    }

    private fun createPayoutTablesAndIndexes() {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS payout_ledger (
                    request_id TEXT PRIMARY KEY,
                    installation_id TEXT NOT NULL,
                    currency TEXT NOT NULL CHECK(currency IN ('BRL','USD')),
                    amount_cents INTEGER NOT NULL CHECK(amount_cents > 0),
                    state TEXT NOT NULL CHECK(state IN ('REQUESTED','SUBMITTED','PAID','FAILED')),
                    provider TEXT NOT NULL,
                    provider_batch_id TEXT,
                    failure_code TEXT,
                    created_at_epoch_ms INTEGER NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL,
                    FOREIGN KEY(installation_id) REFERENCES installations(installation_id)
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS payout_rewards (
                    request_id TEXT NOT NULL,
                    reward_id TEXT NOT NULL,
                    PRIMARY KEY(request_id, reward_id),
                    FOREIGN KEY(request_id) REFERENCES payout_ledger(request_id),
                    FOREIGN KEY(reward_id) REFERENCES reward_ledger(reward_id)
                )
                """.trimIndent()
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_ledger_installation_accepted ON gameplay_ledger(installation_id, accepted_at_epoch_ms)"
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_reward_installation_state ON reward_ledger(installation_id, state)"
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_reward_created ON reward_ledger(created_at_epoch_ms)"
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_reward_installation_currency_state ON reward_ledger(installation_id, currency, state)"
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_payout_installation_created ON payout_ledger(installation_id, created_at_epoch_ms)"
            )
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_payout_provider_batch ON payout_ledger(provider_batch_id)"
            )
        }
    }

    private fun migrateRewardCurrencyColumn() {
        val columns = mutableSetOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(reward_ledger)").use { rows ->
                while (rows.next()) columns += rows.getString("name")
            }
        }
        if ("currency" !in columns) {
            connection.createStatement().use { statement ->
                statement.execute(
                    "ALTER TABLE reward_ledger ADD COLUMN currency TEXT NOT NULL DEFAULT 'BRL' CHECK(currency IN ('BRL','USD'))"
                )
            }
        }
    }

    private fun migrateRewardStateConstraint() {
        val tableSql = connection.prepareStatement(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'reward_ledger'"
        ).use { statement ->
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString("sql") else "" }
        }
        if (tableSql.contains("PAYOUT_PENDING") && tableSql.contains("PAID")) return

        val oldAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE reward_ledger RENAME TO reward_ledger_legacy")
                statement.execute(
                    """
                    CREATE TABLE reward_ledger (
                        reward_id TEXT PRIMARY KEY,
                        installation_id TEXT NOT NULL,
                        gameplay_ledger_id TEXT NOT NULL UNIQUE,
                        session_id TEXT NOT NULL UNIQUE,
                        amount_cents INTEGER NOT NULL CHECK(amount_cents > 0),
                        currency TEXT NOT NULL DEFAULT 'BRL' CHECK(currency IN ('BRL','USD')),
                        state TEXT NOT NULL CHECK(state IN ('PENDING','APPROVED','AVAILABLE','PAYOUT_PENDING','PAID')),
                        policy_code TEXT NOT NULL,
                        created_at_epoch_ms INTEGER NOT NULL,
                        updated_at_epoch_ms INTEGER NOT NULL,
                        FOREIGN KEY(installation_id) REFERENCES installations(installation_id),
                        FOREIGN KEY(gameplay_ledger_id) REFERENCES gameplay_ledger(ledger_id)
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    INSERT INTO reward_ledger(
                        reward_id, installation_id, gameplay_ledger_id, session_id,
                        amount_cents, currency, state, policy_code, created_at_epoch_ms, updated_at_epoch_ms
                    )
                    SELECT reward_id, installation_id, gameplay_ledger_id, session_id,
                           amount_cents, currency, state, policy_code, created_at_epoch_ms, updated_at_epoch_ms
                    FROM reward_ledger_legacy
                    """.trimIndent()
                )
                statement.execute("DROP TABLE reward_ledger_legacy")
            }
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = oldAutoCommit
        }
    }

    @Synchronized
    override fun loadRegistrations(): List<InstallationRegistration> {
        val result = mutableListOf<InstallationRegistration>()
        connection.prepareStatement(
            """
            SELECT protocol_version, installation_id, key_id, public_key_base64,
                   signature_algorithm, app_version, created_at_epoch_ms
            FROM installations
            ORDER BY created_at_epoch_ms ASC
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    result += InstallationRegistration(
                        protocolVersion = rows.getInt("protocol_version"),
                        installationId = rows.getString("installation_id"),
                        keyId = rows.getString("key_id"),
                        publicKeyBase64 = rows.getString("public_key_base64"),
                        signatureAlgorithm = rows.getString("signature_algorithm"),
                        appVersion = rows.getString("app_version"),
                        createdAtEpochMs = rows.getLong("created_at_epoch_ms")
                    )
                }
            }
        }
        return result
    }

    @Synchronized
    override fun loadLedgerEntries(): List<GameplayLedgerEntry> {
        val result = mutableListOf<GameplayLedgerEntry>()
        connection.prepareStatement(
            """
            SELECT ledger_id, installation_id, session_id, sequence,
                   started_at_epoch_ms, finished_at_epoch_ms, duration_ms,
                   verified_score_units, app_version, accepted_at_epoch_ms
            FROM gameplay_ledger
            ORDER BY accepted_at_epoch_ms ASC, rowid ASC
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    result += GameplayLedgerEntry(
                        ledgerId = rows.getString("ledger_id"),
                        installationId = rows.getString("installation_id"),
                        sessionId = rows.getString("session_id"),
                        sequence = rows.getLong("sequence"),
                        startedAtEpochMs = rows.getLong("started_at_epoch_ms"),
                        finishedAtEpochMs = rows.getLong("finished_at_epoch_ms"),
                        durationMs = rows.getLong("duration_ms"),
                        verifiedScoreUnits = rows.getLong("verified_score_units"),
                        appVersion = rows.getString("app_version"),
                        acceptedAtEpochMs = rows.getLong("accepted_at_epoch_ms")
                    )
                }
            }
        }
        return result
    }

    @Synchronized
    override fun loadRewardEntries(): List<RewardLedgerEntry> {
        val result = mutableListOf<RewardLedgerEntry>()
        connection.prepareStatement(
            """
            SELECT reward_id, installation_id, gameplay_ledger_id, session_id,
                   amount_cents, currency, state, policy_code, created_at_epoch_ms, updated_at_epoch_ms
            FROM reward_ledger
            ORDER BY created_at_epoch_ms ASC, rowid ASC
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    result += RewardLedgerEntry(
                        rewardId = rows.getString("reward_id"),
                        installationId = rows.getString("installation_id"),
                        gameplayLedgerId = rows.getString("gameplay_ledger_id"),
                        sessionId = rows.getString("session_id"),
                        amountCents = rows.getLong("amount_cents"),
                        currency = RewardCurrency.valueOf(rows.getString("currency")),
                        state = RewardState.valueOf(rows.getString("state")),
                        policyCode = rows.getString("policy_code"),
                        createdAtEpochMs = rows.getLong("created_at_epoch_ms"),
                        updatedAtEpochMs = rows.getLong("updated_at_epoch_ms")
                    )
                }
            }
        }
        return result
    }

    @Synchronized
    override fun saveRegistration(registration: InstallationRegistration): Boolean = runCatching {
        connection.prepareStatement(
            """
            INSERT INTO installations(
                installation_id, protocol_version, key_id, public_key_base64,
                signature_algorithm, app_version, created_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, registration.installationId)
            statement.setInt(2, registration.protocolVersion)
            statement.setString(3, registration.keyId)
            statement.setString(4, registration.publicKeyBase64)
            statement.setString(5, registration.signatureAlgorithm)
            statement.setString(6, registration.appVersion)
            statement.setLong(7, registration.createdAtEpochMs)
            statement.executeUpdate() == 1
        }
    }.getOrDefault(false)

    @Synchronized
    override fun saveLedgerEntry(entry: GameplayLedgerEntry): Boolean = runCatching {
        connection.prepareStatement(
            """
            INSERT INTO gameplay_ledger(
                ledger_id, installation_id, session_id, sequence,
                started_at_epoch_ms, finished_at_epoch_ms, duration_ms,
                verified_score_units, app_version, accepted_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, entry.ledgerId)
            statement.setString(2, entry.installationId)
            statement.setString(3, entry.sessionId)
            statement.setLong(4, entry.sequence)
            statement.setLong(5, entry.startedAtEpochMs)
            statement.setLong(6, entry.finishedAtEpochMs)
            statement.setLong(7, entry.durationMs)
            statement.setLong(8, entry.verifiedScoreUnits)
            statement.setString(9, entry.appVersion)
            statement.setLong(10, entry.acceptedAtEpochMs)
            statement.executeUpdate() == 1
        }
    }.getOrDefault(false)

    @Synchronized
    override fun saveRewardEntry(entry: RewardLedgerEntry): Boolean = runCatching {
        connection.prepareStatement(
            """
            INSERT INTO reward_ledger(
                reward_id, installation_id, gameplay_ledger_id, session_id,
                amount_cents, currency, state, policy_code, created_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, entry.rewardId)
            statement.setString(2, entry.installationId)
            statement.setString(3, entry.gameplayLedgerId)
            statement.setString(4, entry.sessionId)
            statement.setLong(5, entry.amountCents)
            statement.setString(6, entry.currency.name)
            statement.setString(7, entry.state.name)
            statement.setString(8, entry.policyCode)
            statement.setLong(9, entry.createdAtEpochMs)
            statement.setLong(10, entry.updatedAtEpochMs)
            statement.executeUpdate() == 1
        }
    }.getOrDefault(false)

    @Synchronized
    override fun updateRewardState(
        rewardId: String,
        expectedState: RewardState,
        newState: RewardState,
        updatedAtEpochMs: Long
    ): Boolean = runCatching {
        connection.prepareStatement(
            """
            UPDATE reward_ledger
            SET state = ?, updated_at_epoch_ms = ?
            WHERE reward_id = ? AND state = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, newState.name)
            statement.setLong(2, updatedAtEpochMs)
            statement.setString(3, rewardId)
            statement.setString(4, expectedState.name)
            statement.executeUpdate() == 1
        }
    }.getOrDefault(false)

    @Synchronized
    override fun loadPayout(requestId: String): PayoutLedgerEntry? = runCatching {
        connection.prepareStatement(
            """
            SELECT request_id, installation_id, currency, amount_cents, state, provider,
                   provider_batch_id, failure_code, created_at_epoch_ms, updated_at_epoch_ms
            FROM payout_ledger
            WHERE request_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, requestId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@runCatching null
                PayoutLedgerEntry(
                    requestId = rows.getString("request_id"),
                    installationId = rows.getString("installation_id"),
                    currency = RewardCurrency.valueOf(rows.getString("currency")),
                    amountCents = rows.getLong("amount_cents"),
                    state = PayoutState.valueOf(rows.getString("state")),
                    provider = rows.getString("provider"),
                    providerBatchId = rows.getString("provider_batch_id"),
                    failureCode = rows.getString("failure_code"),
                    createdAtEpochMs = rows.getLong("created_at_epoch_ms"),
                    updatedAtEpochMs = rows.getLong("updated_at_epoch_ms")
                )
            }
        }
    }.getOrNull()

    @Synchronized
    override fun savePayout(entry: PayoutLedgerEntry): Boolean = runCatching {
        connection.prepareStatement(
            """
            INSERT INTO payout_ledger(
                request_id, installation_id, currency, amount_cents, state, provider,
                provider_batch_id, failure_code, created_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, entry.requestId)
            statement.setString(2, entry.installationId)
            statement.setString(3, entry.currency.name)
            statement.setLong(4, entry.amountCents)
            statement.setString(5, entry.state.name)
            statement.setString(6, entry.provider)
            statement.setString(7, entry.providerBatchId)
            statement.setString(8, entry.failureCode)
            statement.setLong(9, entry.createdAtEpochMs)
            statement.setLong(10, entry.updatedAtEpochMs)
            statement.executeUpdate() == 1
        }
    }.getOrDefault(false)

    @Synchronized
    override fun updatePayout(
        requestId: String,
        expectedState: PayoutState,
        newState: PayoutState,
        providerBatchId: String?,
        failureCode: String?,
        updatedAtEpochMs: Long
    ): Boolean = runCatching {
        connection.prepareStatement(
            """
            UPDATE payout_ledger
            SET state = ?, provider_batch_id = COALESCE(?, provider_batch_id), failure_code = ?, updated_at_epoch_ms = ?
            WHERE request_id = ? AND state = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, newState.name)
            statement.setString(2, providerBatchId)
            statement.setString(3, failureCode)
            statement.setLong(4, updatedAtEpochMs)
            statement.setString(5, requestId)
            statement.setString(6, expectedState.name)
            statement.executeUpdate() == 1
        }
    }.getOrDefault(false)

    @Synchronized
    override fun savePayoutReward(requestId: String, rewardId: String): Boolean = runCatching {
        connection.prepareStatement(
            "INSERT INTO payout_rewards(request_id, reward_id) VALUES (?, ?)"
        ).use { statement ->
            statement.setString(1, requestId)
            statement.setString(2, rewardId)
            statement.executeUpdate() == 1
        }
    }.getOrDefault(false)

    @Synchronized
    override fun loadPayoutRewardIds(requestId: String): List<String> {
        val result = mutableListOf<String>()
        connection.prepareStatement(
            "SELECT reward_id FROM payout_rewards WHERE request_id = ? ORDER BY reward_id ASC"
        ).use { statement ->
            statement.setString(1, requestId)
            statement.executeQuery().use { rows ->
                while (rows.next()) result += rows.getString("reward_id")
            }
        }
        return result
    }

    @Synchronized
    override fun close() {
        if (!connection.isClosed) connection.close()
    }
}
