package com.iptv.player.playback.core

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

@JvmInline
value class PlaybackResourceToken internal constructor(internal val value: Long)

sealed interface IdleWorkResult<out T> {
    data class Completed<T>(val value: T) : IdleWorkResult<T>
    data object InterruptedByPlayback : IdleWorkResult<Nothing>
}

/**
 * Exact-token gate shared by live, VOD and preview playback. It prevents a stale
 * owner's duplicate cleanup from decrementing somebody else's active session.
 */
class PlaybackResourceGate {
    private val lock = Any()
    private val nextToken = AtomicLong(1L)
    private val owners = linkedMapOf<Long, String>()
    private val idleListeners = linkedMapOf<Long, () -> Unit>()
    private val nextListener = AtomicLong(1L)

    private val _activeOwnerCount = MutableStateFlow(0)
    val activeOwnerCount: StateFlow<Int> = _activeOwnerCount

    // Increments on the idle -> active edge. Unlike a Boolean StateFlow, the epoch
    // cannot lose a very short playback session that starts and ends while work is
    // being scheduled.
    private val _playbackEpoch = MutableStateFlow(0L)

    val isPlaybackActive: Boolean
        get() = synchronized(lock) { owners.isNotEmpty() }

    fun begin(owner: String): PlaybackResourceToken {
        val label = owner.trim().take(MAX_OWNER_LABEL_LENGTH).ifEmpty { "player" }
        synchronized(lock) {
            val wasIdle = owners.isEmpty()
            val token = nextToken.getAndIncrement()
            owners[token] = label
            _activeOwnerCount.value = owners.size
            if (wasIdle) _playbackEpoch.value = _playbackEpoch.value + 1L
            return PlaybackResourceToken(token)
        }
    }

    /** Returns false for an unknown/already-ended token; the count never underflows. */
    fun end(token: PlaybackResourceToken?): Boolean {
        if (token == null) return false
        val listeners: List<() -> Unit>
        synchronized(lock) {
            if (owners.remove(token.value) == null) return false
            _activeOwnerCount.value = owners.size
            listeners = if (owners.isEmpty()) idleListeners.values.toList() else emptyList()
        }
        // Call application/WorkManager code outside the lock.
        listeners.forEach { listener -> runCatching(listener) }
        return true
    }

    suspend fun awaitIdle() {
        activeOwnerCount.first { it == 0 }
    }

    /**
     * Runs nonessential work only while idle. A playback start cancels the child
     * cooperatively and reports interruption so the caller can safely defer it.
     */
    suspend fun <T> runWhileIdle(block: suspend () -> T): IdleWorkResult<T> {
        val initialEpoch = synchronized(lock) {
            if (owners.isNotEmpty()) return IdleWorkResult.InterruptedByPlayback
            _playbackEpoch.value
        }
        return coroutineScope {
            val playbackStarted = async(start = CoroutineStart.UNDISPATCHED) {
                _playbackEpoch.first { it != initialEpoch }
            }
            val work = async(start = CoroutineStart.UNDISPATCHED) { block() }
            try {
                select {
                    work.onAwait { IdleWorkResult.Completed(it) }
                    playbackStarted.onAwait {
                        work.cancelAndJoin()
                        IdleWorkResult.InterruptedByPlayback
                    }
                }
            } catch (e: CancellationException) {
                work.cancelAndJoin()
                throw e
            } finally {
                playbackStarted.cancel()
            }
        }
    }

    /** Listener fires on every active -> idle edge until the handle is closed. */
    fun addIdleListener(listener: () -> Unit): AutoCloseable {
        val id = nextListener.getAndIncrement()
        synchronized(lock) { idleListeners[id] = listener }
        return AutoCloseable { synchronized(lock) { idleListeners.remove(id) } }
    }

    private companion object {
        const val MAX_OWNER_LABEL_LENGTH = 48
    }
}

/** Process-wide gate. Player screens only need [begin] and [end]. */
object PlaybackResourceGovernor {
    private val gate = PlaybackResourceGate()

    val activeOwnerCount: StateFlow<Int> = gate.activeOwnerCount
    val isPlaybackActive: Boolean get() = gate.isPlaybackActive

    fun begin(owner: String): PlaybackResourceToken = gate.begin(owner)
    fun end(token: PlaybackResourceToken?): Boolean = gate.end(token)
    suspend fun awaitIdle() = gate.awaitIdle()
    suspend fun <T> runWhileIdle(block: suspend () -> T): IdleWorkResult<T> =
        gate.runWhileIdle(block)
    fun addIdleListener(listener: () -> Unit): AutoCloseable = gate.addIdleListener(listener)
}
