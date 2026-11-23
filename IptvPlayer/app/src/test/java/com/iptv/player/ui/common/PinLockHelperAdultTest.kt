package com.iptv.player.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLockHelperAdultTest {

    @Test
    fun `adult names in the catalog languages are locked`() {
        listOf(
            "XXX", "Adult", "Sex TV", "Porno", "+18 VIP", "18+",
            "Erotik", "EROTIK HD", "Seks", "Yetişkin", "YETİŞKİN", "Yetiskin",
            "Erwachsene", "FSK 18", "FSK18", "Ab 18",
        ).forEach { assertTrue(it, PinLockHelper.looksAdult(it)) }
    }

    @Test
    fun `ordinary names stay unlocked`() {
        listOf("Essex", "Sussex", "Seksenler", "Ab 1800", "Sport 18 Uhr", "FSK 16", "Kinder", null)
            .forEach { assertFalse(it.toString(), PinLockHelper.looksAdult(it)) }
    }
}
