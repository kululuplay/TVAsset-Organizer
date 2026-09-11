package com.iptv.player.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class DirectMediaDataSourceTest {
    private fun uri(value: String): Uri = mock(Uri::class.java).also {
        `when`(it.toString()).thenReturn(value)
    }

    private fun spec(value: String = "https://example.test/movie/1.mp4", position: Long = 0) =
        DataSpec.Builder().setUri(uri(value)).setPosition(position).build()

    private inner class Source(
        private val bytes: ByteArray,
        private val resolvedUrl: String = "https://cdn.example.test/file.mp4",
        private val mime: String = "video/mp4",
        private val chunk: Int = 7,
    ) : DataSource {
        var opens = 0
        var closes = 0
        var reads = 0
        var position = 0
        override fun open(dataSpec: DataSpec): Long {
            opens++
            position = dataSpec.position.toInt()
            return (bytes.size - position).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (position == bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - position, chunk)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        override fun getUri(): Uri = this@DirectMediaDataSourceTest.uri(resolvedUrl)
        override fun getResponseHeaders() = mapOf("content-type" to listOf(mime))
        override fun addTransferListener(listener: TransferListener) = Unit
        override fun close() { closes++ }
    }

    private fun drain(source: DataSource): ByteArray {
        val output = ByteArrayOutputStream()
        val bytes = ByteArray(9)
        while (true) {
            val count = source.read(bytes, 1, 8)
            if (count == C.RESULT_END_OF_INPUT) return output.toByteArray()
            output.write(bytes, 1, count)
        }
    }

    @Test fun `direct files are replayed byte for byte using one connection`() {
        val bytes = ByteArray(103) { (it + 42).toByte() }
        val upstream = Source(bytes)
        val source = DirectMediaDataSource(upstream)
        assertEquals(103L, source.open(spec()))
        assertEquals(0, source.read(ByteArray(0), 0, 0))
        assertArrayEquals(bytes, drain(source))
        assertEquals(1, upstream.opens)
        source.close()
        assertEquals(1, upstream.closes)
        assertEquals(83L, source.open(spec(position = 20)))
        assertArrayEquals(bytes.copyOfRange(20, 103), drain(source))
    }

    @Test fun `short valid files retain every byte`() {
        val bytes = byteArrayOf(1, 2, 3)
        val source = DirectMediaDataSource(Source(bytes))
        assertEquals(3L, source.open(spec()))
        assertArrayEquals(bytes, drain(source))
    }

    @Test fun `direct HLS is refused before opening the upstream connection`() {
        val upstream = Source(byteArrayOf())
        try {
            DirectMediaDataSource(upstream).open(spec("https://example.test/1.m3u8"))
            fail("manifest accepted")
        } catch (_: IOException) { }
        assertEquals(0, upstream.opens)
    }

    @Test fun `HLS redirects MIME and disguised payloads close the single connection`() {
        val cases = listOf(
            Source(byteArrayOf(), resolvedUrl = "https://cdn.example.test/1.m3u8"),
            Source(byteArrayOf(), mime = "application/vnd.apple.mpegurl"),
            Source("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nsecret-segment.ts".toByteArray()),
        )
        for (upstream in cases) {
            try { DirectMediaDataSource(upstream).open(spec()); fail("manifest accepted") }
            catch (_: IOException) { }
            assertEquals(1, upstream.opens)
            assertEquals(1, upstream.closes)
        }
        assertEquals(0, cases[0].reads)
        assertEquals(0, cases[1].reads)
    }
}
