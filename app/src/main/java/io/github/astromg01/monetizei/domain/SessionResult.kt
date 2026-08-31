package io.github.astromg01.monetizei.domain

data class SessionResult(
    val score: Int,
    val durationMs: Long,
    val softCoinsEarned: Long,
    val xpEarned: Long
)
