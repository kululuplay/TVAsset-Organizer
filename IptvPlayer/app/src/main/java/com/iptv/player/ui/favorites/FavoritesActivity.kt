/*
 * FavoritesActivity.kt
 * Dedicated "Favorites" page opened from the dashboard tile. Unlike the Live
 * browser's Favorites category (which only lists channels), this shows favorites
 * of EVERY content type - live channels, movies and series - in one poster grid.
 * Selecting an item routes to the correct destination:
 *   channel -> PlayerActivity (zapping stays within the favorite channels)
 *   movie   -> VodDetailActivity
 *   series  -> SeriesDetailActivity
 * Fully D-pad driven.
 */
package com.iptv.player.ui.favorites

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.FavoriteItem
import com.iptv.player.data.model.FavoriteKind
import com.iptv.player.databinding.ActivityFavoritesBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.ui.home.HomeViewModel
import com.iptv.player.ui.player.PlayerActivity
import com.iptv.player.ui.series.SeriesDetailActivity
import com.iptv.player.ui.vod.VodDetailActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FavoritesActivity : BaseActivity() {

    private lateinit var binding: ActivityFavoritesBinding
    private lateinit var adapter: FavoritesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoritesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = FavoritesAdapter(onClicked = { open(it) })
        binding.favoritesGrid.layoutManager = GridLayoutManager(this, SPAN_COUNT)
        binding.favoritesGrid.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceLocator.repository.observeAllFavorites()
                    .collectLatest { items ->
                        adapter.submitList(items)
                        binding.emptyText.visibility =
                            if (items.isEmpty()) View.VISIBLE else View.GONE
                        binding.favoritesGrid.visibility =
                            if (items.isEmpty()) View.GONE else View.VISIBLE
                    }
            }
        }
    }

    private fun open(item: FavoriteItem) {
        // Mirror the parental gating used by the other entry points: an item is adult
        // if its title OR its category name looks adult. Always guard before opening.
        val isAdult = PinLockHelper.looksAdult(item.title) ||
            PinLockHelper.looksAdult(item.categoryName)
        PinLockHelper.guard(this, isAdult = isAdult) {
            when (item.kind) {
                FavoriteKind.CHANNEL -> startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, item.targetId)
                        // Zap within the favorite channels, not the global list.
                        .putExtra(PlayerActivity.EXTRA_CATEGORY_ID, HomeViewModel.CAT_FAVORITES)
                )
                FavoriteKind.MOVIE -> startActivity(
                    Intent(this, VodDetailActivity::class.java)
                        .putExtra(VodDetailActivity.EXTRA_VOD_ID, item.targetId)
                )
                FavoriteKind.SERIES -> startActivity(
                    Intent(this, SeriesDetailActivity::class.java)
                        .putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.targetId)
                )
            }
        }
    }

    companion object {
        private const val SPAN_COUNT = 6
    }
}
