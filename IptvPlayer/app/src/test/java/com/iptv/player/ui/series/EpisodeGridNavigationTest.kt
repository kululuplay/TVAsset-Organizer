package com.iptv.player.ui.series

import org.junit.Assert.*
import org.junit.Test

class EpisodeGridNavigationTest {
    @Test fun `vertical traversal preserves column in full rows`() {
        assertEquals(5, EpisodeGridNavigation.verticalTarget(2, 12, 3, true))
        assertEquals(2, EpisodeGridNavigation.verticalTarget(5, 12, 3, false))
    }
    @Test fun `last short row remains reachable from any column`() {
        assertEquals(6, EpisodeGridNavigation.verticalTarget(5, 7, 3, true))
        assertEquals(7, EpisodeGridNavigation.verticalTarget(5, 8, 3, true))
    }
    @Test fun `first row returns to seasons and last row exits toward recommendations`() {
        for (p in 0..2) assertNull(EpisodeGridNavigation.verticalTarget(p, 8, 3, false))
        for (p in 6..7) assertNull(EpisodeGridNavigation.verticalTarget(p, 8, 3, true))
    }
    @Test fun `empty or stale adapter positions cannot produce focus targets`() {
        assertNull(EpisodeGridNavigation.verticalTarget(-1, 12, 3, true))
        assertNull(EpisodeGridNavigation.verticalTarget(0, 0, 3, true))
        assertNull(EpisodeGridNavigation.verticalTarget(3, 3, 3, false))
    }
}
