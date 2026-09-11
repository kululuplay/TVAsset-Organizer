package com.iptv.player.ui.common

import com.iptv.player.ui.common.PosterGridNavigation.Direction.*
import org.junit.Assert.*
import org.junit.Test

class PosterGridNavigationTest {
    @Test fun rapidDownNeverLeavesSmallCategoryOrWrapsToTop() {
        var position = 0
        repeat(200) { position = PosterGridNavigation.target(position, 45, 5, DOWN)!! }
        assertEquals(40, position)
    }

    @Test fun newlyAppendedPageContinuesFromTheSameColumn() {
        assertEquals(59, PosterGridNavigation.target(59, 60, 5, DOWN))
        assertEquals(64, PosterGridNavigation.target(59, 120, 5, DOWN))
    }

    @Test fun incompleteLastRowUsesItsLastCardWithoutMovingBackwards() {
        assertEquals(42, PosterGridNavigation.target(39, 43, 5, DOWN))
        assertEquals(42, PosterGridNavigation.target(42, 43, 5, DOWN))
        assertEquals(37, PosterGridNavigation.target(42, 43, 5, UP))
    }

    @Test fun horizontalEdgesStayPutForExplicitActivityExit() {
        assertEquals(10, PosterGridNavigation.target(10, 45, 5, PREVIOUS))
        assertEquals(14, PosterGridNavigation.target(14, 45, 5, NEXT))
        assertEquals(13, PosterGridNavigation.target(14, 45, 5, PREVIOUS))
    }

    @Test fun rejectsPositionsFromAnUncommittedOrEmptyPagingSnapshot() {
        assertNull(PosterGridNavigation.target(-1, 45, 5, DOWN))
        assertNull(PosterGridNavigation.target(45, 45, 5, DOWN))
        assertNull(PosterGridNavigation.target(0, 0, 5, DOWN))
        assertNull(PosterGridNavigation.target(0, 45, 0, DOWN))
    }

    @Test fun everyGridSizeAndDirectionStaysInBounds() {
        for (columns in 1..8) for (count in 1..80) for (position in 0 until count) {
            for (direction in PosterGridNavigation.Direction.values()) {
                val next = PosterGridNavigation.target(position, count, columns, direction)!!
                assertTrue(next in 0 until count)
                if (direction == DOWN) assertTrue(next >= position)
                if (direction == UP) assertTrue(next <= position)
            }
        }
    }
}
