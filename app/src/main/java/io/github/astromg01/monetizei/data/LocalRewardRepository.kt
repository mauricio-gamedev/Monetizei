package io.github.astromg01.monetizei.data

import android.content.Context
import io.github.astromg01.monetizei.domain.SessionResult
import io.github.astromg01.monetizei.domain.Wallet

/**
 * Development-only persistence for NON-CASH progression.
 * Withdrawable balance must never be created or trusted on the client.
 */
class LocalRewardRepository(context: Context) : RewardRepository {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var wallet = Wallet(
        softCoins = preferences.getLong(KEY_SOFT_COINS, 0L),
        xp = preferences.getLong(KEY_XP, 0L)
    )

    @Synchronized
    override fun getWallet(): Wallet = wallet

    @Synchronized
    override fun creditSession(result: SessionResult): Wallet {
        wallet = wallet.copy(
            softCoins = wallet.softCoins + result.softCoinsEarned,
            xp = wallet.xp + result.xpEarned
        )
        preferences.edit()
            .putLong(KEY_SOFT_COINS, wallet.softCoins)
            .putLong(KEY_XP, wallet.xp)
            .apply()
        return wallet
    }

    private companion object {
        const val PREFS_NAME = "monetizei_progress"
        const val KEY_SOFT_COINS = "soft_coins"
        const val KEY_XP = "xp"
    }
}
