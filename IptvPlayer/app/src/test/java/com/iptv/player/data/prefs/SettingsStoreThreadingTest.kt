package com.iptv.player.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.security.SecureValueCodec
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*

class SettingsStoreThreadingTest {
    private val config = SourceConfig(
        type = SourceType.XTREAM,
        serverUrl = "https://example.test",
        username = "user",
        password = "pass",
    )

    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val current = MutableStateFlow(initial)
        private val mutex = Mutex()
        override val data: Flow<Preferences> = current
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(current.value).also { current.value = it } }
    }

    private class Harness(initial: Preferences = emptyPreferences()) : Closeable {
        val ui = Executors.newSingleThreadExecutor { Thread(it, "test-ui") }.asCoroutineDispatcher()
        val io = Executors.newSingleThreadExecutor { Thread(it, "secure-storage-io") }.asCoroutineDispatcher()
        val codec: SecureValueCodec = mock(SecureValueCodec::class.java)
        val data = MemoryStore(initial)
        val settings = SettingsStore(mock(Context::class.java), codec, io, data)

        init {
            `when`(codec.encrypt(anyString())).thenAnswer {
                assertEquals("secure-storage-io", Thread.currentThread().name)
                val value = it.getArgument<String>(0)
                if (value.isBlank()) value else "encv1:$value"
            }
            `when`(codec.decrypt(anyString())).thenAnswer {
                assertEquals("secure-storage-io", Thread.currentThread().name)
                it.getArgument<String>(0).removePrefix("encv1:")
            }
            `when`(codec.isEncrypted(anyString())).thenAnswer {
                it.getArgument<String>(0).startsWith("encv1:")
            }
        }

        override fun close() { ui.close(); io.close() }
    }

    @Test(timeout = 10000)
    fun `slow first key creation leaves UI responsive and publishes only complete encrypted source`() = runBlocking<Unit> {
        Harness().use { h ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            doAnswer {
                assertEquals("secure-storage-io", Thread.currentThread().name)
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "encv1:" + it.getArgument<String>(0)
            }.`when`(h.codec).encrypt(config.serverUrl)
            val save = async(h.ui) { h.settings.saveSource(config) }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                val uiPulse = async(h.ui) { "responsive" }
                assertEquals("responsive", withTimeout(1500) { uiPulse.await() })
                assertFalse(h.settings.hasSource.first())
                assertTrue(h.data.data.first().asMap().isEmpty())
            } finally { release.countDown() }
            save.await()
            assertTrue(h.settings.hasSource.first())
            assertEquals("encv1:pass", h.data.data.first()[stringPreferencesKey("password")])
            assertEquals(config, withContext(h.ui) { h.settings.getSourceConfig() })
        }
    }

    @Test(timeout = 10000)
    fun `encryption failure preserves saved account without writing plaintext or partial changes`() = runBlocking<Unit> {
        Harness().use { h ->
            withContext(h.ui) { h.settings.saveSource(config) }
            val before = h.data.data.first().asMap()
            doThrow(IllegalStateException("Secure storage unavailable"))
                .`when`(h.codec).encrypt("replacement")
            try {
                withContext(h.ui) { h.settings.saveSource(config.copy(password = "replacement")) }
                fail("save must fail")
            } catch (_: IllegalStateException) { }
            assertEquals(before, h.data.data.first().asMap())
        }
    }

    @Test(timeout = 10000)
    fun `PIN and TMDB key writes reads and observed values never call keystore on UI`() = runBlocking<Unit> {
        Harness().use { h ->
            withContext(h.ui) {
                h.settings.setPin("4729")
                assertEquals("4729", h.settings.getPin())
                h.settings.setTmdbKey("sample")
                assertEquals("sample", h.settings.getTmdbKey())
                assertEquals("sample", h.settings.tmdbKey.first())
            }
            assertEquals("encv1:4729", h.data.data.first()[stringPreferencesKey("parental_pin")])
        }
    }

    @Test(timeout = 10000)
    fun `legacy migration runs encryption on worker and preserves encrypted values`() = runBlocking<Unit> {
        val username = stringPreferencesKey("username")
        val password = stringPreferencesKey("password")
        Harness(preferencesOf(username to "user", password to "encv1:pass")).use { h ->
            withContext(h.ui) { h.settings.migrateSensitiveValues() }
            assertEquals("encv1:user", h.data.data.first()[username])
            assertEquals("encv1:pass", h.data.data.first()[password])
            verify(h.codec, never()).encrypt("encv1:pass")
        }
    }
}
