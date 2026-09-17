package com.iptv.player.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlChunkTest {
    @Test
    fun `id lists are split under the SQLite variable limit`() = runBlocking {
        val ids = (1..2_345).map { "id$it" }
        val seen = mutableListOf<List<String>>()
        ids.forEachSqlChunk { seen += it }
        assertEquals(5, seen.size)
        assertTrue(seen.all { it.size <= SQL_ID_CHUNK && it.size < 999 })
        assertEquals(ids, seen.flatten())
    }

    @Test
    fun `an empty list issues no statement`() = runBlocking {
        var calls = 0
        emptyList<String>().forEachSqlChunk { calls++ }
        assertEquals(0, calls)
    }
}
