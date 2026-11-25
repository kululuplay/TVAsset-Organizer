/*
 * WatchNextPublisher.kt
 * Mirrors the active profile's "Continue watching" list into the Watch Next row
 * of the Android TV / Google TV home screen (TvProvider, Android 8.0+). The row
 * is public to the room: WatchNextPolicy decides what may appear, and whatever
 * is no longer in the list (finished, removed, logout, another account or
 * profile) is deleted again. Without a TV provider (Fire OS, phones) the first
 * query fails and the publisher switches itself off for this process.
 */
package com.iptv.player.watchnext

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.BaseColumns
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.TvContractCompat.WatchNextPrograms
import androidx.tvprovider.media.tv.WatchNextProgram
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.ContinueItem
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.ui.common.isAdult
import com.iptv.player.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

object WatchNextPublisher {

    private const val TAG = "WatchNext"

    /** One log line per process for a failed write, never one per card. */
    @Volatile
    private var writeFailureLogged = false

    /** Last card count written to the log, so the log says when the row changes and nothing else. */
    @Volatile
    private var loggedCardCount = -1

    /** Call once from the application, after ServiceLocator.init. */
    fun start(context: Context) {
        // The Watch Next table exists from Android 8.0.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val app = context.applicationContext
        // appScope runs on Dispatchers.IO: no provider or database call on the main thread.
        ServiceLocator.appScope.launch {
            try {
                val settings = ServiceLocator.settings
                val repository = ServiceLocator.repository
                var lastKey: Pair<Long, List<String>>? = null
                var lastWriteAt = 0L
                repository.observeContinueWatching(WatchNextPolicy.CANDIDATE_LIMIT)
                    // Logout keeps the resume rows but must clear the home screen.
                    .combine(settings.hasSource.distinctUntilChanged()) { items, loggedIn ->
                        if (loggedIn) items else emptyList()
                    }
                    .collectLatest { items ->
                        val profileId = settings.getActiveProfileId()
                        val key = profileId to items.map { it.contentId }
                        // Same titles, only a new position: wait for the next slot.
                        // A newer save cancels this wait and takes it over.
                        if (key == lastKey) {
                            delay(
                                lastWriteAt + WatchNextPolicy.MIN_WRITE_INTERVAL_MS -
                                    SystemClock.elapsedRealtime(),
                            )
                        }
                        // The profile's only lock today is its adult-lock flag.
                        val profileProtected = repository.getProfile(profileId)?.lockAdult == true
                        val cards = WatchNextPolicy.select(items, profileProtected) {
                            adultByCategory(it)
                        }
                        if (!sync(app, cards)) {
                            Logger.i(TAG, "no TV provider: Watch Next is off")
                            this@launch.cancel()
                            return@collectLatest
                        }
                        lastKey = key
                        lastWriteAt = SystemClock.elapsedRealtime()
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Logger.w(TAG, "Watch Next stopped: ${error.javaClass.simpleName}")
            }
        }
    }

    /**
     * Same catalogue lookup as the in-app Continue Watching screen. A title whose
     * category cannot be read counts as adult: unknown never reaches the home screen.
     */
    private suspend fun adultByCategory(item: ContinueItem): Boolean = try {
        val repository = ServiceLocator.repository
        when (item.kind) {
            ResumeKind.MOVIE -> (item.vodId ?: item.contentId.removePrefix("vod_"))
                .takeIf { it.isNotBlank() }
                ?.let { repository.getVodCached(it)?.isAdult() }
            ResumeKind.EPISODE -> item.seriesId
                ?.let { repository.getSeriesCached(it)?.isAdult() }
            ResumeKind.CATCHUP -> null
        } ?: true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        true
    }

    /**
     * Makes the Watch Next rows equal to [cards]: rows of other titles are deleted
     * first, then each card is updated in place or inserted. Returns false when
     * the device has no usable TV provider.
     */
    // tvprovider 1.0.0 keeps the shared column names and builder setters in
    // library-restricted base types although they are its documented public API.
    @SuppressLint("RestrictedApi")
    private fun sync(context: Context, cards: List<ContinueItem>): Boolean {
        val resolver = context.contentResolver
        val wanted = cards.mapTo(HashSet()) { it.contentId }
        val rowIds = HashMap<String, Long>()
        val obsolete = ArrayList<Long>()
        // The provider refuses a selection from an ordinary app and limits it to
        // its own rows by itself; a row of another package is skipped all the same.
        val readable = runCatching {
            resolver.query(
                WatchNextPrograms.CONTENT_URI,
                arrayOf(
                    BaseColumns._ID,
                    WatchNextPrograms.COLUMN_PACKAGE_NAME,
                    WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val owner = cursor.getString(1)
                    if (owner != null && owner != context.packageName) continue
                    val rowId = cursor.getLong(0)
                    val contentId = cursor.getString(2)
                    if (contentId != null && contentId in wanted && contentId !in rowIds) {
                        rowIds[contentId] = rowId
                    } else {
                        obsolete += rowId
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (!readable) return false

        for (rowId in obsolete) {
            write { resolver.delete(TvContractCompat.buildWatchNextProgramUri(rowId), null, null) }
        }
        for (card in cards) {
            write {
                val values = program(context, card).toContentValues()
                val rowId = rowIds[card.contentId]
                if (rowId == null) {
                    resolver.insert(WatchNextPrograms.CONTENT_URI, values)
                } else {
                    resolver.update(
                        TvContractCompat.buildWatchNextProgramUri(rowId), values, null, null,
                    )
                }
            }
        }
        if (cards.size != loggedCardCount) {
            loggedCardCount = cards.size
            Logger.i(TAG, "Watch Next row: ${cards.size} cards (${rowIds.size} kept, ${obsolete.size} removed)")
        }
        return true
    }

    private inline fun write(block: () -> Unit) {
        runCatching(block).onFailure {
            if (!writeFailureLogged) {
                writeFailureLogged = true
                Logger.w(TAG, "Watch Next write failed: ${it.javaClass.simpleName}")
            }
        }
    }

    /** One card. It carries the content id only: never the stream URL or a credential. */
    @SuppressLint("RestrictedApi") // see sync()
    private fun program(context: Context, card: ContinueItem): WatchNextProgram {
        val episode = card.kind == ResumeKind.EPISODE
        val builder = WatchNextProgram.Builder()
            .setWatchNextType(WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setType(if (episode) WatchNextPrograms.TYPE_TV_EPISODE else WatchNextPrograms.TYPE_MOVIE)
            .setInternalProviderId(card.contentId)
            .setTitle(card.title)
            .setDurationMillis(card.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setLastPlaybackPositionMillis(card.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setLastEngagementTimeUtcMillis(card.updatedAt)
            .setIntent(WatchNextEntryActivity.intent(context, card.contentId))
        card.posterUrl?.takeIf { it.isNotBlank() }?.let { builder.setPosterArtUri(it.toUri()) }
        if (episode) {
            // The title of an episode row is the series name.
            if (card.seasonNumber > 0) builder.setSeasonNumber(card.seasonNumber)
            if (card.episodeNumber > 0) builder.setEpisodeNumber(card.episodeNumber)
        } else {
            builder.setPosterArtAspectRatio(WatchNextPrograms.ASPECT_RATIO_MOVIE_POSTER)
        }
        return builder.build()
    }
}
