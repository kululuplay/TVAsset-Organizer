/*
 * SplashPrefetch.kt
 * Coordinates the one-shot background prefetch shown behind the branded splash.
 * Runs on the application-lifetime scope (ServiceLocator.appScope) so it keeps
 * going even after the splash routes onward — the splash never blocks on it.
 * Refreshes Live channels, EPG, Series (full) and Movie *categories* (movies
 * themselves are loaded lazily per category elsewhere), then best-effort loads
 * the first movie category so "Recently added" has content. Exposes the current
 * [stage] and an [essentialDone] flag the splash uses for its timing.
 */
package com.iptv.player.ui.splash

import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.util.Logger
import com.iptv.player.util.NewContentNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object SplashPrefetch {

    /** Ordered prefetch stages; each maps to a status line shown on the splash. */
    enum class Stage(val labelRes: Int) {
        STARTING(R.string.splash_status_starting),
        LIVE(R.string.splash_status_live),
        EPG(R.string.splash_status_epg),
        LIBRARY(R.string.splash_status_library),
        READY(R.string.splash_status_ready)
    }

    private val _stage = MutableStateFlow(Stage.STARTING)
    val stage: StateFlow<Stage> = _stage

    /** True once the essential refreshes (live/EPG/series/categories) have run. */
    private val _essentialDone = MutableStateFlow(false)
    val essentialDone: StateFlow<Boolean> = _essentialDone

    private val lock = Any()

    @Volatile
    private var job: Job? = null

    /**
     * Starts the prefetch if it isn't already running. Idempotent and race-safe —
     * calling it again while a run is in flight is a no-op, so re-entering the
     * splash (or concurrent callers) never duplicate work.
     */
    fun start(config: SourceConfig) {
        synchronized(lock) {
            if (job?.isActive == true) return
            _stage.value = Stage.STARTING
            _essentialDone.value = false
            // Fresh launch — clear any leftover "new content" tallies so the
            // notices reflect only what this launch's refresh discovers.
            NewContentNotifier.reset()
            job = ServiceLocator.appScope.launch { run(config) }
        }
    }

    /**
     * Runs each essential stage independently so one failing dataset (e.g. EPG)
     * never blocks the others. CancellationException always propagates; any other
     * failure is swallowed per-stage because the prefetch is best-effort and the
     * splash must always proceed. [essentialDone] flips true only after every
     * essential stage has been attempted.
     */
    private suspend fun run(config: SourceConfig) {
        val repo = ServiceLocator.repository
        try {
            stage(Stage.LIVE) { repo.refreshLive(config) }
            stage(Stage.EPG) { repo.refreshEpg(config) }
            stage(Stage.LIBRARY) {
                // Both movies and series are lazy per-category, so only the cheap
                // category lists are fetched here — never the whole catalog.
                repo.refreshSeriesCategories(config)
                repo.refreshVodCategories(config)
            }
        } finally {
            // All essential stages attempted — release the splash regardless.
            _essentialDone.value = true
        }

        // Re-sync the movie/series categories the user has already opened so newly
        // added titles show up on every app launch. Runs in the background after
        // the splash has already proceeded, force-refreshing via upsert so cached
        // items never flicker — only genuinely new content is added. Done before the
        // warm-up below so the first category isn't fetched twice in one run.
        runCatchingCancellable { repo.refreshLoadedVodCategories(config) }
        runCatchingCancellable { repo.refreshLoadedSeriesCategories(config) }

        // Best-effort: warm "Recently added" for both rails without pulling the
        // whole catalog (one category each). Not forced, so a category already
        // refreshed by the loaded-sweep above is skipped here (no duplicate fetch).
        runCatchingCancellable { repo.prefetchFirstVodCategory(config) }
        runCatchingCancellable { repo.prefetchFirstSeriesCategory(config) }
        _stage.value = Stage.READY
    }

    /** Sets the visible stage, then runs [block], isolating non-cancellation failures. */
    private suspend fun stage(stage: Stage, block: suspend () -> Unit) {
        _stage.value = stage
        runCatchingCancellable(block)
    }

    private suspend fun runCatchingCancellable(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: OutOfMemoryError) {
            // Never swallow OOM. Catching it here used to leave the heap exhausted
            // so the *next* allocation (Dashboard inflation) crashed with a
            // misleading trace — the classic low-RAM Fire/Sony "home never opens"
            // failure. Log it and rethrow so the crash points at the real culprit
            // and LaunchCrashGuard surfaces it on the next launch.
            Logger.e(TAG, "Prefetch ran out of memory", e)
            throw e
        } catch (e: Exception) {
            // Best-effort prefetch: ignore ordinary failures and move on.
            Logger.w(TAG, "Prefetch stage failed (ignored): ${e.message}")
        }
    }

    private const val TAG = "SplashPrefetch"
}
