package io.github.astromg01.monetizei.domain

import io.github.astromg01.monetizei.protocol.SessionProtocol

object RewardRules {
    const val SESSION_DURATION_MS = SessionProtocol.SESSION_DURATION_MS
    const val MAX_SCORE_PER_SECOND = 15
    const val MAX_SESSION_SCORE = SessionProtocol.MAX_SESSION_SCORE

    /**
     * Grants only NON-CASH in-game progression.
     * Real-world rewards must be authorized by the backend after eligibility,
     * fraud and policy checks; ad views/clicks must never mint withdrawable value.
     */
    fun evaluate(score: Int, durationMs: Long): SessionResult {
        val normalizedDuration = durationMs.coerceIn(0L, SESSION_DURATION_MS)
        val validScore = score.coerceIn(0, MAX_SESSION_SCORE)
        val coins = validScore.toLong()
        val xp = (validScore * 2L) + (normalizedDuration / 5_000L)

        return SessionResult(
            score = validScore,
            durationMs = normalizedDuration,
            softCoinsEarned = coins,
            xpEarned = xp
        )
    }
}
