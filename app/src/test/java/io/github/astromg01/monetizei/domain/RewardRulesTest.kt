package io.github.astromg01.monetizei.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RewardRulesTest {
    @Test
    fun evaluate_grantsOnlySoftProgressionFromValidSession() {
        val result = RewardRules.evaluate(score = 20, durationMs = 30_000L)

        assertEquals(20, result.score)
        assertEquals(20L, result.softCoinsEarned)
        assertEquals(46L, result.xpEarned)
    }

    @Test
    fun evaluate_clampsInvalidAndImpossibleInput() {
        val negative = RewardRules.evaluate(score = -5, durationMs = -1L)
        assertEquals(0, negative.score)
        assertEquals(0L, negative.durationMs)

        val impossible = RewardRules.evaluate(score = Int.MAX_VALUE, durationMs = Long.MAX_VALUE)
        assertEquals(RewardRules.MAX_SESSION_SCORE, impossible.score)
        assertEquals(RewardRules.SESSION_DURATION_MS, impossible.durationMs)
    }
}
