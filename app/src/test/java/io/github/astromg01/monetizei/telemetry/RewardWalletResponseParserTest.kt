package io.github.astromg01.monetizei.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RewardWalletResponseParserTest {
    @Test
    fun parsesServerWalletSnapshot() {
        val body = """{"accepted":true,"wallet":{"pendingCents":12,"approvedCents":34,"availableCents":56}}"""
        val wallet = RewardWalletResponseParser.parse(body)!!

        assertEquals(12L, wallet.pendingCents)
        assertEquals(34L, wallet.approvedCents)
        assertEquals(56L, wallet.availableCents)
    }

    @Test
    fun rejectsIncompleteWalletPayload() {
        assertNull(RewardWalletResponseParser.parse("""{"wallet":{"pendingCents":1}}"""))
    }
}
