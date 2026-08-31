package io.github.astromg01.monetizei.domain

object RewardRules {
    const val SESSION_DURATION_MS = 30_000L
    const val MAX_SCORE_PER_SECOND = 15
    const val MAX_SESSION_SCORE = (SESSION_DURATION_MS / 1_000L * MAX_SCORE_PER_SECOND).toInt()

    /**
     * v0.1 grants only NON-CASH in-game progression.
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
