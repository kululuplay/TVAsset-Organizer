/*
 * ContinueWatchingActivity.kt
 * Dedicated "Continue Watching" page opened from the dashboard tile. Shows every
 * in-progress movie/episode in a poster grid; selecting one resumes from the saved
 * position. These items are shown ONLY here - they are no longer rendered inline on
 * the dashboard. Fully D-pad driven.
 */
package com.iptv.player.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.ContinueItem
import com.iptv.player.databinding.ActivityContinueWatchingBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.ui.player.VodPlayerActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ContinueWatchingActivity : BaseActivity() {

    private lateinit var binding: ActivityContinueWatchingBinding
    private lateinit var adapter: ContinueWatchingAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContinueWatchingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ContinueWatchingAdapter(onClicked = { resume(it) })
        binding.continueGrid.layoutManager = GridLayoutManager(this, SPAN_COUNT)
        binding.continueGrid.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceLocator.repository.observeContinueWatching(PAGE_LIMIT)
                    .collectLatest { items ->
                        adapter.submitList(items)
                        binding.emptyText.visibility =
                            if (items.isEmpty()) View.VISIBLE else View.GONE
                        binding.continueGrid.visibility =
                            if (items.isEmpty()) View.GONE else View.VISIBLE
                    }
            }
        }
    }

    private fun resume(item: ContinueItem) {
        PinLockHelper.guard(this, isAdult = PinLockHelper.looksAdult(item.title)) {
            val intent = Intent(this, VodPlayerActivity::class.java).apply {
                putExtra(VodPlayerActivity.EXTRA_STREAM_URL, item.streamUrl)
                putExtra(VodPlayerActivity.EXTRA_TITLE, item.title)
                putExtra(VodPlayerActivity.EXTRA_RESUME_ID, item.contentId)
                putExtra(VodPlayerActivity.EXTRA_RESUME_TYPE, item.kind.raw)
                putExtra(VodPlayerActivity.EXTRA_RESUME_TITLE, item.title)
                putExtra(VodPlayerActivity.EXTRA_POSTER_URL, item.posterUrl)
                putExtra(VodPlayerActivity.EXTRA_VOD_ID, item.vodId)
                putExtra(VodPlayerActivity.EXTRA_SERIES_ID, item.seriesId)
                putExtra(VodPlayerActivity.EXTRA_SEASON, item.seasonNumber)
                putExtra(VodPlayerActivity.EXTRA_EPISODE, item.episodeNumber)
                putExtra(VodPlayerActivity.EXTRA_AUTO_RESUME, true)
            }
            startActivity(intent)
        }
    }

    companion object {
        private const val SPAN_COUNT = 6
        private const val PAGE_LIMIT = 60
    }
}
