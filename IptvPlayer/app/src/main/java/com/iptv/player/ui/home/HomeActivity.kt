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
import com.iptv.player.ui.common.NumberZapInputHelper
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.ui.common.isAdult
import com.iptv.player.ui.player.PlayerActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : BaseActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by lazy {
        ViewModelProvider(this)[HomeViewModel::class.java]
    }

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter

    /** Last channels rendered, used by the number-key zap lookup. */
    private var currentChannels: List<Channel> = emptyList()

    /** Guards the one-time initial category selection on first category emission. */
    private var initialSelectionDone = false

    /** When the category changes, the channel list must snap back to its top. */
    private var pendingScrollReset = false

    /** Last category actually selected; used to detect real category changes. */
    private var lastSelectedCategoryId: String? = null

    companion object {
        /** Optional category id to open on (e.g. Favorites from the dashboard). */
        const val EXTRA_INITIAL_CATEGORY = "extra_initial_category"
    }

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

        setupLists()
        observe()
    }

    override fun onResume() {
        super.onResume()
        // Navigation now lives on the Dashboard; this screen just shows the date.
        binding.dateText.text = DateFormat
            .getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            .format(Date())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        zap.handleKeyDown(keyCode) || super.onKeyDown(keyCode, event)

    private fun setupLists() {
        categoryAdapter = CategoryAdapter(
            onFocused = {
                // Only reset scroll/focus when the category truly changes, so a
                // re-focus of the same category can't leave the flag armed and make
                // an unrelated emission (e.g. a favorite toggle) jump to the top.
                if (it.id != lastSelectedCategoryId) {
                    lastSelectedCategoryId = it.id
                    pendingScrollReset = true
                    viewModel.selectCategory(it.id)
                }
            },
            onClicked = { focusFirstChannel() }
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

        // The preview surface opens the currently focused channel.
        binding.previewCard.setOnClickListener {
            currentInfoChannel?.let { openPlayer(it) }
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            viewModel.categories.collectLatest { cats ->
                categoryAdapter.submitList(cats)
                if (cats.isNotEmpty() && !initialSelectionDone) {
                    initialSelectionDone = true
                    // Honour an explicitly requested category (e.g. Favorites from the
                    // dashboard); otherwise land on the first real category after the
                    // pinned Favorites/Recent entries.
                    val requested = intent.getStringExtra(EXTRA_INITIAL_CATEGORY)
                    val target = cats.firstOrNull { it.id == requested }
                        ?: cats.getOrNull(2) ?: cats.first()
                    lastSelectedCategoryId = target.id
                    viewModel.selectCategory(target.id)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.channels.collectLatest { channels ->
                currentChannels = channels
                channelAdapter.submitList(channels) {
                    // After a category switch, jump the list back to the first item
                    // so focus starts there (not on a leftover scroll position).
                    if (pendingScrollReset) {
                        pendingScrollReset = false
                        binding.channelList.scrollToPosition(0)
                    }
                }
                binding.emptyState.visibility =
                    if (channels.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private var nowNextJob: kotlinx.coroutines.Job? = null

    /** The channel currently shown in the preview/info panel. */
    private var currentInfoChannel: Channel? = null

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun showInfo(channel: Channel) {
        currentInfoChannel = channel
        val number = channel.number?.let { "$it | " } ?: ""
        binding.infoName.text = number + channel.name
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
        // Reset before the async EPG lookup resolves.
        binding.infoNow.text = ""
        binding.infoNowTime.text = ""
        binding.infoNowDesc.text = ""
        binding.infoNext.text = ""
        binding.infoNextTime.text = ""
        binding.liveBadge.visibility = View.GONE
        binding.nowProgress.visibility = View.GONE
        nowNextJob?.cancel()
        nowNextJob = lifecycleScope.launch {
            val nn = viewModel.nowNext(channel)
            nn.now?.let { now ->
                binding.infoNow.text = now.title
                binding.infoNowTime.text = "${timeFmt.format(Date(now.startMs))} - ${timeFmt.format(Date(now.stopMs))}"
                binding.infoNowDesc.text = now.description ?: ""
                binding.liveBadge.visibility = View.VISIBLE
                val span = (now.stopMs - now.startMs).toFloat()
                if (span > 0f) {
                    val pct = ((System.currentTimeMillis() - now.startMs) / span * 100f)
                        .toInt().coerceIn(0, 100)
                    binding.nowProgress.progress = pct
                    binding.nowProgress.visibility = View.VISIBLE
                }
            }
            nn.next?.let { next ->
                binding.infoNext.text = next.title
                binding.infoNextTime.text = "${timeFmt.format(Date(next.startMs))} - ${timeFmt.format(Date(next.stopMs))}"
            }
        }
    }

    /** Moves focus into the channel list, always landing on the first channel. */
    private fun focusFirstChannel() {
        binding.channelList.scrollToPosition(0)
        binding.channelList.post {
            (binding.channelList.findViewHolderForAdapterPosition(0)?.itemView
                ?: binding.channelList).requestFocus()
        }
    }

    private fun openPlayer(channel: Channel) {
        PinLockHelper.guard(this, isAdult = channel.isAdult()) {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            }
            startActivity(intent)
        }
    }
}
