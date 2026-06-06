/*
 * NewContentNotifier.kt
 * Process-wide tally of how many genuinely-new Live / Movie / Series items the
 * launch-time background refresh discovered since the user last saw them. The
 * repository increments the relevant counter when a force-refresh on app launch
 * finds items it had never cached before; each content screen observes its
 * counter, shows a transient "N new …" popup, then [consumeLive]/[consumeMovies]/
 * [consumeSeries] so the notice appears only once. [reset] is called at the start
 * of every cold-start prefetch so the counts reflect only that launch.
 */
package com.iptv.player.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

object NewContentNotifier {

    private val _newLive = MutableStateFlow(0)
    val newLive: StateFlow<Int> = _newLive

    private val _newMovies = MutableStateFlow(0)
    val newMovies: StateFlow<Int> = _newMovies

    private val _newSeries = MutableStateFlow(0)
    val newSeries: StateFlow<Int> = _newSeries

    fun addLive(count: Int) { if (count > 0) _newLive.update { it + count } }
    fun addMovies(count: Int) { if (count > 0) _newMovies.update { it + count } }
    fun addSeries(count: Int) { if (count > 0) _newSeries.update { it + count } }

    fun consumeLive() { _newLive.value = 0 }
    fun consumeMovies() { _newMovies.value = 0 }
    fun consumeSeries() { _newSeries.value = 0 }

    /** Clears all counters at the start of a fresh launch prefetch. */
    fun reset() {
        _newLive.value = 0
        _newMovies.value = 0
        _newSeries.value = 0
    }
}
