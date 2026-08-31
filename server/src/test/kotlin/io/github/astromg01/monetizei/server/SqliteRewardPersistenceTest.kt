package io.github.astromg01.monetizei.server

import io.github.astromg01.monetizei.protocol.InstallationRegistration
import io.github.astromg01.monetizei.protocol.SessionProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID

class SqliteRewardPersistenceTest {
    @Test
    fun rewardWalletSurvivesDatabaseRestart() {
        val db = Files.createTempDirectory("monetizei-reward-test").resolve("reward.db")
        val installationId = UUID.randomUUID().toString()
        val gameplay = gameplay(installationId)

        SqliteServerPersistence(db).use { persistence ->
            assertTrue(persistence.saveRegistration(registration(installationId)))
            assertTrue(persistence.saveLedgerEntry(gameplay))
            val service = RewardService(
                persistence = persistence,
                policy = RewardPolicy(3, 100, 20, 10)
            )
            val decision = service.evaluateAcceptedGameplay(gameplay)
            assertEquals(RewardDecisionCode.PENDING_CREATED, decision.code)
            assertEquals(3L, decision.wallet.brl.pendingCents)
            assertEquals(RewardCurrency.BRL, decision.currency)
            assertTrue(service.approve(decision.rewardId!!, 60_000L))
        }

        SqliteServerPersistence(db).use { persistence ->
            val restored = RewardService(
                persistence = persistence,
                policy = RewardPolicy(3, 100, 20, 10)
            )
            val wallet = restored.wallet(installationId)
            assertEquals(0L, wallet.brl.pendingCents)
            assertEquals(3L, wallet.brl.approvedCents)
            assertEquals(0L, wallet.brl.availableCents)
            assertEquals(0L, wallet.usd.totalCents)
            val rewardId = restored.snapshot().single().rewardId
            assertTrue(restored.makeAvailable(rewardId, 120_000L))
        }

        SqliteServerPersistence(db).use { persistence ->
            val restored = RewardService(persistence = persistence)
            assertEquals(3L, restored.wallet(installationId).brl.availableCents)
        }
    }

    @Test
    fun usdRewardSurvivesDatabaseRestart() {
        val db = Files.createTempDirectory("monetizei-usd-reward-test").resolve("reward.db")
        val installationId = UUID.randomUUID().toString()
        val gameplay = gameplay(installationId)

        SqliteServerPersistence(db).use { persistence ->
            assertTrue(persistence.saveRegistration(registration(installationId)))
            assertTrue(persistence.saveLedgerEntry(gameplay))
            val service = RewardService(
                persistence = persistence,
                policy = RewardPolicy(
                    rewardCentsPerEligibleSession = 7,
                    dailyBudgetCents = 100,
                    minVerifiedScore = 20,
                    maxRewardsPerInstallationPerUtcDay = 10,
                    currency = RewardCurrency.USD
                )
            )
            val decision = service.evaluateAcceptedGameplay(gameplay)
            assertEquals(RewardCurrency.USD, decision.currency)
            assertEquals(7L, decision.wallet.usd.pendingCents)
        }

        SqliteServerPersistence(db).use { persistence ->
            val restored = RewardService(persistence = persistence)
            assertEquals(0L, restored.wallet(installationId).brl.totalCents)
            assertEquals(7L, restored.wallet(installationId).usd.pendingCents)
            assertEquals(RewardCurrency.USD, restored.snapshot().single().currency)
        }
    }

    @Test
    fun v05RewardTableMigratesExistingRowsToBrl() {
        val db = Files.createTempDirectory("monetizei-v05-migration-test").resolve("legacy.db")
        val installationId = UUID.randomUUID().toString()
        val gameplayId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val rewardId = UUID.randomUUID().toString()

        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE installations (
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
                    CREATE TABLE gameplay_ledger (
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
                        UNIQUE(installation_id, sequence)
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE reward_ledger (
                        reward_id TEXT PRIMARY KEY,
                        installation_id TEXT NOT NULL,
                        gameplay_ledger_id TEXT NOT NULL UNIQUE,
                        session_id TEXT NOT NULL UNIQUE,
                        amount_cents INTEGER NOT NULL CHECK(amount_cents > 0),
                        state TEXT NOT NULL CHECK(state IN ('PENDING','APPROVED','AVAILABLE')),
                        policy_code TEXT NOT NULL,
                        created_at_epoch_ms INTEGER NOT NULL,
                        updated_at_epoch_ms INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
            connection.prepareStatement(
                "INSERT INTO installations VALUES (?, ?, ?, ?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, installationId)
                statement.setInt(2, SessionProtocol.VERSION)
                statement.setString(3, "legacy-key")
                statement.setString(4, "legacy-key")
                statement.setString(5, SessionProtocol.SIGNATURE_ALGORITHM)
                statement.setString(6, "0.5.0")
                statement.setLong(7, 1_000L)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO gameplay_ledger VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, gameplayId)
                statement.setString(2, installationId)
                statement.setString(3, sessionId)
                statement.setLong(4, 1L)
                statement.setLong(5, 10_000L)
                statement.setLong(6, 40_000L)
                statement.setLong(7, SessionProtocol.SESSION_DURATION_MS)
                statement.setLong(8, 30L)
                statement.setString(9, "0.5.0")
                statement.setLong(10, 50_000L)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO reward_ledger VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, rewardId)
                statement.setString(2, installationId)
                statement.setString(3, gameplayId)
                statement.setString(4, sessionId)
                statement.setLong(5, 9L)
                statement.setString(6, RewardState.PENDING.name)
                statement.setString(7, "verified_gameplay_v1")
                statement.setLong(8, 50_000L)
                statement.setLong(9, 50_000L)
                statement.executeUpdate()
            }
        }

        SqliteServerPersistence(db).use { persistence ->
            val migrated = persistence.loadRewardEntries().single()
            assertEquals(rewardId, migrated.rewardId)
            assertEquals(RewardCurrency.BRL, migrated.currency)
            assertEquals(9L, migrated.amountCents)
        }
    }

    private fun registration(installationId: String) = InstallationRegistration(
        protocolVersion = SessionProtocol.VERSION,
        installationId = installationId,
        keyId = "test-key",
        publicKeyBase64 = "test-key",
        signatureAlgorithm = SessionProtocol.SIGNATURE_ALGORITHM,
        appVersion = "0.5.1",
        createdAtEpochMs = 1_000L
    )

    private fun gameplay(installationId: String) = GameplayLedgerEntry(
        ledgerId = UUID.randomUUID().toString(),
        installationId = installationId,
        sessionId = UUID.randomUUID().toString(),
        sequence = 1L,
        startedAtEpochMs = 10_000L,
        finishedAtEpochMs = 40_000L,
        durationMs = SessionProtocol.SESSION_DURATION_MS,
        verifiedScoreUnits = 35L,
        appVersion = "0.5.1",
        acceptedAtEpochMs = 50_000L
    )
}
