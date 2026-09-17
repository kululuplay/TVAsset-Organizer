/*
 * SearchRepository.kt
 * FTS4 search over the live, movie and series indexes (instant, paged for the
 * poster grids). The MATCH expression is built by [toFtsQuery].
 */
package com.iptv.player.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.iptv.player.data.local.AppDatabase
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentSort
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.Series
import com.iptv.player.data.model.VodItem
import com.iptv.player.security.SecureValueCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class SearchRepository(
    db: AppDatabase,
    override val secureValues: SecureValueCodec,
    private val pagingConfig: PagingConfig,
) : EntityMappers {

    private val channelDao = db.channelDao()
    private val vodDao = db.vodDao()
    private val seriesDao = db.seriesDao()

    fun search(
        query: String,
        type: ContentType,
        hiddenCategories: List<String> = emptyList(),
        radio: Int = -1,
    ): Flow<List<Channel>> {
        val match = toFtsQuery(query)
        if (match.isBlank()) return flowOf(emptyList())
        return channelDao.searchFts(match, type.name, hiddenCategories, radio)
            .map { list -> list.map { it.toModel() } }
    }

    /** Instant FTS search, paged. Empty input yields an empty page (no MATCH). */
    fun pagingVodSearch(
        query: String,
        hidden: List<String> = emptyList(),
        sort: ContentSort = ContentSort.RECENT,
    ): Flow<PagingData<VodItem>> {
        val match = toFtsQuery(query)
        if (match.isBlank()) return flowOf(PagingData.empty())
        return Pager(pagingConfig) {
            when (sort) {
                ContentSort.RECENT -> vodDao.pagingSearchRecent(match, hidden)
                ContentSort.NAME -> vodDao.pagingSearchByName(match, hidden)
                ContentSort.RATING -> vodDao.pagingSearchByRating(match, hidden)
                ContentSort.YEAR -> vodDao.pagingSearchByYear(match, hidden)
            }
        }
            .flow.map { data -> data.map { it.toModel() } }
    }

    /** Instant FTS search, paged. Empty input yields an empty page (no MATCH). */
    fun pagingSeriesSearch(
        query: String,
        hidden: List<String> = emptyList(),
        sort: ContentSort = ContentSort.RECENT,
    ): Flow<PagingData<Series>> {
        val match = toFtsQuery(query)
        if (match.isBlank()) return flowOf(PagingData.empty())
        return Pager(pagingConfig) {
            when (sort) {
                ContentSort.RECENT -> seriesDao.pagingSearchRecent(match, hidden)
                ContentSort.NAME -> seriesDao.pagingSearchByName(match, hidden)
                ContentSort.RATING -> seriesDao.pagingSearchByRating(match, hidden)
                ContentSort.YEAR -> seriesDao.pagingSearchByYear(match, hidden)
            }
        }
            .flow.map { data -> data.map { it.toModel() } }
    }
}
