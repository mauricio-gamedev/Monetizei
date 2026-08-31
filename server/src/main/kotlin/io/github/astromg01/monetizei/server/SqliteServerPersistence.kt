package io.github.astromg01.monetizei.server

import io.github.astromg01.monetizei.protocol.InstallationRegistration
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class SqliteServerPersistence(dbPath: Path) : SessionPersistence, RewardPersistence, AutoCloseable {
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
                    state TEXT NOT NULL CHECK(state IN ('PENDING','APPROVED','AVAILABLE')),
                    policy_code TEXT NOT NULL,
                    created_at_epoch_ms INTEGER NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL,
                    FOREIGN KEY(installation_id) REFERENCES installations(installation_id),
                    FOREIGN KEY(gameplay_ledger_id) REFERENCES gameplay_ledger(ledger_id)
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
        }

        migrateRewardCurrencyColumn()
        connection.createStatement().use { statement ->
            statement.execute(
                "CREATE INDEX IF NOT EXISTS idx_reward_installation_currency_state ON reward_ledger(installation_id, currency, state)"
            )
        }
    }

    private fun migrateRewardCurrencyColumn() {
        val columns = mutableSetOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(reward_ledger)").use { rows ->
                while (rows.next()) {
                    columns += rows.getString("name")
                }
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
    override fun close() {
        if (!connection.isClosed) connection.close()
    }
}
