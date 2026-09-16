package com.iptv.player.player

import android.net.Uri
import android.os.Handler
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito

/** Exercises the real open/close/retire bookkeeping; no network or Media3 loader. */
class ExoProviderConnectionTest {

    @Test fun nothingOpenCompletesInline() {
        Fixture().run {
            var result: Boolean? = null
            connection.awaitClosed(1_500L) { result = it }
            assertEquals(true, result)
            assertTrue(mainTasks.isEmpty())
        }
    }

    @Test fun retiredSourceRefusesToOpenASecondConnection() {
        Fixture().run {
            val source = Delegate()
            val tracked = connection.wrap(source)
            assertFalse(connection.retireAndClose())
            try {
                tracked.open(spec())
                fail("retired source must not open")
            } catch (_: IOException) {
            }
            assertEquals(0, source.opens)
        }
    }

    @Test fun forceCloseClosesTheSocketOffCallerAndReportsOnMain() {
        Fixture().run {
            val source = Delegate()
            val tracked = connection.wrap(source)
            tracked.open(spec())
            var result: Boolean? = null
            assertTrue(connection.retireAndClose())
            assertTrue(connection.hasRetiredOpenConnection())
            connection.awaitClosed(1_500L) { result = it }
            assertNull(result) // Still pending: the close executor has not run.
            assertEquals(1, closeJobs.size)
            closeJobs.removeFirst().run()
            assertEquals(1, source.closes)
            assertFalse(connection.hasRetiredOpenConnection())
            assertNull(result) // Delivered on main, never on the closing thread.
            runMain()
            assertEquals(true, result)
            assertTrue(mainTasks.isEmpty()) // Timeout was cancelled.
        }
    }

    @Test fun boundedWaitReportsFalseWhenTheSocketNeverCloses() {
        Fixture().run {
            val source = Delegate()
            connection.wrap(source).open(spec())
            assertTrue(connection.retireAndClose())
            val results = mutableListOf<Boolean>()
            connection.awaitClosed(1_500L) { results += it }
            assertEquals(1, mainTasks.size)
            assertEquals(1_500L, mainTasks.first().at - now)
            runMain()
            assertEquals(listOf(false), results)
            // The close eventually happens; the expired waiter is not re-fired.
            closeJobs.removeFirst().run()
            runMain()
            assertEquals(listOf(false), results)
        }
    }

    @Test fun loaderCloseAfterForceCloseStaysIdempotent() {
        Fixture().run {
            val source = Delegate()
            val tracked = connection.wrap(source)
            tracked.open(spec())
            connection.retireAndClose()
            val results = mutableListOf<Boolean>()
            connection.awaitClosed(1_500L) { results += it }
            closeJobs.removeFirst().run()
            tracked.close() // Loader thread's own finally-close.
            runMain()
            assertEquals(listOf(true), results)
        }
    }

    @Test fun retireDuringInFlightOpenClosesThatConnection() {
        Fixture().run {
            val source = Delegate(onOpen = { connection.retireAndClose() })
            val tracked = connection.wrap(source)
            try {
                tracked.open(spec())
                fail("connection retired mid-open must be rejected")
            } catch (_: IOException) {
            }
            assertEquals(1, source.opens)
            assertEquals(1, source.closes)
            assertFalse(connection.hasRetiredOpenConnection())
        }
    }

    @Test fun freshGenerationOpensNormallyAfterRetire() {
        Fixture().run {
            val old = Delegate()
            connection.wrap(old).open(spec())
            connection.retireAndClose()
            val fresh = Delegate()
            val tracked = connection.wrap(fresh)
            assertEquals(42L, tracked.open(spec()))
            assertEquals(1, fresh.opens)
            // The new connection is not "retired open": only the old one blocks.
            closeJobs.removeFirst().run()
            assertFalse(connection.hasRetiredOpenConnection())
        }
    }

    private class Delegate(private val onOpen: () -> Unit = {}) : DataSource {
        var opens = 0
        var closes = 0
        override fun open(dataSpec: DataSpec): Long {
            opens++
            onOpen()
            return 42L
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        override fun getUri(): Uri? = null
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun addTransferListener(listener: TransferListener) = Unit
        override fun close() { closes++ }
    }

    private class Fixture {
        class Task(val at: Long, val runnable: Runnable)
        var now = 1_000L
        val mainTasks = ArrayDeque<Task>()
        val closeJobs = ArrayDeque<Runnable>()
        private val handler = Mockito.mock(Handler::class.java) { invocation ->
            when (invocation.method.name) {
                "postDelayed" -> {
                    mainTasks.add(Task(now + (invocation.arguments[1] as Long), invocation.arguments[0] as Runnable))
                    true
                }
                "post" -> { mainTasks.add(Task(now, invocation.arguments[0] as Runnable)); true }
                "removeCallbacks" -> { mainTasks.removeIf { it.runnable === invocation.arguments[0] }; null }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val connection = ExoProviderConnection(handler, closeExecutor = { closeJobs.add(it) })

        fun runMain() {
            while (true) {
                val task = mainTasks.minByOrNull { it.at } ?: return
                mainTasks.remove(task)
                now = maxOf(now, task.at)
                task.runnable.run()
            }
        }

        fun spec(): DataSpec = DataSpec.Builder().setUri(Mockito.mock(Uri::class.java)).build()
    }
}
