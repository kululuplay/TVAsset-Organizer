/*
 * HomeActivity.kt
 * The 3-pane Android TV home screen:
 *   left   = categories (incl. Favorites / Recently Watched)
 *   center = channels for the focused category
 *   right  = info panel for the focused channel (logo + name; EPG comes later)
 * Fully D-pad driven. Click a channel to open the player.
 */
package com.iptv.player.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.iptv.player.R
import com.iptv.player.data.model.Channel
import com.iptv.player.databinding.ActivityHomeBinding
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.player.PlayerActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by lazy {
        ViewModelProvider(this)[HomeViewModel::class.java]
    }

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLists()
        observe()
    }

    private fun setupLists() {
        categoryAdapter = CategoryAdapter(
            onFocused = { viewModel.selectCategory(it.id) },
            onClicked = { binding.channelList.requestFocus() }
        )
        binding.categoryList.layoutManager = LinearLayoutManager(this)
        binding.categoryList.adapter = categoryAdapter

        channelAdapter = ChannelAdapter(
            onFocused = { showInfo(it) },
            onClicked = { openPlayer(it) },
            onToggleFavorite = {
                viewModel.toggleFavorite(it.id)
                val msg = if (it.isFavorite) R.string.removed_from_favorites
                else R.string.added_to_favorites
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        )
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = channelAdapter
    }

    private fun observe() {
        lifecycleScope.launch {
            viewModel.categories.collectLatest { cats ->
                categoryAdapter.submitList(cats)
                if (cats.isNotEmpty()) {
                    // Default selection: first real category after the pinned ones.
                    val defaultCat = cats.getOrNull(2) ?: cats.first()
                    viewModel.selectCategory(defaultCat.id)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.channels.collectLatest { channels ->
                channelAdapter.submitList(channels)
                binding.emptyState.visibility =
                    if (channels.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showInfo(channel: Channel) {
        binding.infoName.text = channel.name
        binding.infoCategory.text = channel.categoryName ?: ""
        val placeholder = LogoPlaceholder.forName(this, channel.name)
        if (channel.logoUrl.isNullOrBlank()) {
            binding.infoLogo.setImageDrawable(placeholder)
        } else {
            binding.infoLogo.load(channel.logoUrl) {
                placeholder(placeholder)
                error(placeholder)
            }
        }
    }

    private fun openPlayer(channel: Channel) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
        }
        startActivity(intent)
    }
}
