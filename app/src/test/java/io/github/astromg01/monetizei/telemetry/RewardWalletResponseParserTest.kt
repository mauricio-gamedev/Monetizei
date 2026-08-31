package io.github.astromg01.monetizei.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RewardWalletResponseParserTest {
    @Test
    fun parsesMultiCurrencyServerWalletSnapshot() {
        val body = """
            {"accepted":true,"wallet":{"pendingCents":12,"approvedCents":34,"availableCents":56,"balances":{"BRL":{"pendingCents":12,"approvedCents":34,"availableCents":56},"USD":{"pendingCents":7,"approvedCents":8,"availableCents":9}}}}
        """.trimIndent()
        val wallet = RewardWalletResponseParser.parse(body)!!

        assertEquals(12L, wallet.brl.pendingCents)
        assertEquals(34L, wallet.brl.approvedCents)
        assertEquals(56L, wallet.brl.availableCents)
        assertEquals(7L, wallet.usd.pendingCents)
        assertEquals(8L, wallet.usd.approvedCents)
        assertEquals(9L, wallet.usd.availableCents)
    }

    @Test
    fun parsesPrettyPrintedWalletWithoutRegex() {
        val body = """
            {
              "wallet": {
                "pendingCents": 1,
                "approvedCents": 2,
                "availableCents": 3,
                "balances": {
                  "BRL": { "pendingCents": 4, "approvedCents": 5, "availableCents": 6 },
                  "USD": { "pendingCents": 7, "approvedCents": 8, "availableCents": 9 }
                }
              }
            }
        """.trimIndent()
        val wallet = RewardWalletResponseParser.parse(body)!!

        assertEquals(4L, wallet.brl.pendingCents)
        assertEquals(6L, wallet.brl.availableCents)
        assertEquals(7L, wallet.usd.pendingCents)
        assertEquals(9L, wallet.usd.availableCents)
    }

    @Test
    fun keepsCompatibilityWithV05BrlOnlyWallet() {
        val body = """{"accepted":true,"wallet":{"pendingCents":12,"approvedCents":34,"availableCents":56}}"""
        val wallet = RewardWalletResponseParser.parse(body)!!

        assertEquals(12L, wallet.brl.pendingCents)
        assertEquals(34L, wallet.brl.approvedCents)
        assertEquals(56L, wallet.brl.availableCents)
        assertEquals(0L, wallet.usd.pendingCents)
        assertEquals(0L, wallet.usd.approvedCents)
        assertEquals(0L, wallet.usd.availableCents)
    }

    @Test
    fun rejectsIncompleteWalletPayload() {
        assertNull(RewardWalletResponseParser.parse("""{"wallet":{"pendingCents":1}}"""))
    }
}
