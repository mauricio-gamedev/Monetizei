package io.github.astromg01.monetizei.data

import io.github.astromg01.monetizei.domain.SessionResult
import io.github.astromg01.monetizei.domain.Wallet

interface RewardRepository {
    fun getWallet(): Wallet
    fun creditSession(result: SessionResult): Wallet
}
