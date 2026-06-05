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
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.iptv.player.R
import com.iptv.player.data.model.Channel
import com.iptv.player.databinding.ActivityHomeBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.catchup.CatchupActivity
import com.iptv.player.ui.common.NumberZapInputHelper
import com.iptv.player.ui.guide.GuideActivity
import com.iptv.player.ui.player.PlayerActivity
import com.iptv.player.ui.series.SeriesActivity
import com.iptv.player.ui.settings.SettingsActivity
import com.iptv.player.ui.vod.VodActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeActivity : BaseActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by lazy {
        ViewModelProvider(this)[HomeViewModel::class.java]
    }

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter

    /** Last channels rendered, used by the number-key zap lookup. */
    private var currentChannels: List<Channel> = emptyList()

    private val zap by lazy {
        NumberZapInputHelper(
            lookup = { num -> currentChannels.firstOrNull { it.number == num } },
            onResolved = { ch -> ch?.let { openPlayer(it) } },
            onInputChanged = { typed ->
                binding.zapOverlay.text = typed
                binding.zapOverlay.visibility = if (typed.isEmpty()) View.GONE else View.VISIBLE
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNav()
        setupLists()
        observe()
    }

    private fun setupNav() {
        binding.navLive.setOnClickListener { binding.channelList.requestFocus() }
        binding.navGuide.setOnClickListener { startActivity(Intent(this, GuideActivity::class.java)) }
        binding.navCatchup.setOnClickListener { startActivity(Intent(this, CatchupActivity::class.java)) }
        binding.navMovies.setOnClickListener { startActivity(Intent(this, VodActivity::class.java)) }
        binding.navSeries.setOnClickListener { startActivity(Intent(this, SeriesActivity::class.java)) }
        binding.navSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        zap.handleKeyDown(keyCode) || super.onKeyDown(keyCode, event)

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
                currentChannels = channels
                channelAdapter.submitList(channels)
                binding.emptyState.visibility =
                    if (channels.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private var nowNextJob: kotlinx.coroutines.Job? = null

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
        // Now / Next for the focused channel (cancel any pending lookup first).
        binding.infoNow.text = ""
        binding.infoNext.text = ""
        nowNextJob?.cancel()
        nowNextJob = lifecycleScope.launch {
            val nn = viewModel.nowNext(channel)
            binding.infoNow.text = nn.now?.let { getString(R.string.now_playing) + ": " + it.title } ?: ""
            binding.infoNext.text = nn.next?.let { getString(R.string.up_next) + ": " + it.title } ?: ""
        }
    }

    private fun openPlayer(channel: Channel) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
        }
        startActivity(intent)
    }
}
