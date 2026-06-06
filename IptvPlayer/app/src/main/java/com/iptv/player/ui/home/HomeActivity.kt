/*
 * HomeActivity.kt
 * The 2-pane Android TV Live screen:
 *   left  = a single list that shows categories, and drills into that category's
 *           channels on OK (Back returns to categories).
 *   right = a large channel preview (number + name + current program caption)
 *           on top, and the EPG schedule list for that channel below.
 * Fully D-pad driven. Click a channel to open the player.
 */
package com.iptv.player.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.iptv.player.R
import com.iptv.player.data.model.Channel
import com.iptv.player.databinding.ActivityHomeBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.ChannelText
import com.iptv.player.ui.common.LogoPlaceholder
import com.iptv.player.ui.common.NewContentPopup
import com.iptv.player.ui.common.NumberZapInputHelper
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.ui.common.isAdult
import com.iptv.player.ui.player.PlayerActivity
import com.iptv.player.util.NewContentNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
    private lateinit var epgAdapter: ProgramAdapter

    /** Last channels rendered, used by the number-key zap lookup. */
    private var currentChannels: List<Channel> = emptyList()

    /** Guards the one-time initial category selection on first category emission. */
    private var initialSelectionDone = false

    /** When the category changes, the channel list must snap back to its top. */
    private var pendingScrollReset = false

    /** Last category actually selected; used to detect real category changes. */
    private var lastSelectedCategoryId: String? = null

    /** Adult categories the user has already unlocked this session (avoid re-prompting). */
    private val unlockedCategories = mutableSetOf<String>()

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

    /** True while the left pane shows the channel list (drilled into a category). */
    private val inChannelView: Boolean
        get() = binding.channelList.visibility == View.VISIBLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLists()
        observe()
        observeNewContent()
    }

    /**
     * Flashes a transient top-right notice when the launch refresh found live
     * channels that weren't cached before, then clears the tally so it shows once.
     */
    private fun observeNewContent() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NewContentNotifier.newLive.collectLatest { count ->
                    if (count > 0) {
                        NewContentPopup.show(
                            this@HomeActivity,
                            R.drawable.ic_tv,
                            resources.getQuantityString(R.plurals.new_channels_added, count, count)
                        )
                        NewContentNotifier.consumeLive()
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        zap.handleKeyDown(keyCode) || super.onKeyDown(keyCode, event)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back from the channel list returns to the category list, matching the
        // 2-pane drill-down. Otherwise leave the screen as usual.
        if (inChannelView) {
            showCategories()
        } else {
            super.onBackPressed()
        }
    }

    private fun setupLists() {
        categoryAdapter = CategoryAdapter(
            onFocused = {
                if (it.id != lastSelectedCategoryId) {
                    lastSelectedCategoryId = it.id
                    pendingScrollReset = true
                    viewModel.selectCategory(it.id)
                }
            },
            onClicked = { category ->
                // Locked (adult) categories require the PIN before drilling in.
                if (category.isAdult() && category.id !in unlockedCategories) {
                    PinLockHelper.guard(this, isAdult = true) {
                        unlockedCategories.add(category.id)
                        enterChannelView()
                    }
                } else {
                    enterChannelView()
                }
            }
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

        epgAdapter = ProgramAdapter()
        binding.epgList.layoutManager = LinearLayoutManager(this)
        binding.epgList.adapter = epgAdapter

        // The preview surface opens the currently previewed channel.
        binding.previewCard.setOnClickListener {
            currentInfoChannel?.let { openPlayer(it) }
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            viewModel.categories.collectLatest { cats ->
                categoryAdapter.submitList(cats) {
                    // Run the one-time initial selection after the list has committed
                    // so focusCategory() can find the target row reliably.
                    if (cats.isNotEmpty() && !initialSelectionDone) {
                        initialSelectionDone = true
                        val requested = intent.getStringExtra(EXTRA_INITIAL_CATEGORY)
                        val target = cats.firstOrNull { it.id == requested }
                            ?: cats.getOrNull(2) ?: cats.first()
                        lastSelectedCategoryId = target.id
                        viewModel.selectCategory(target.id)
                        focusCategory(target.id)
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.channels.collectLatest { channels ->
                currentChannels = channels
                channelAdapter.submitList(channels) {
                    if (pendingScrollReset) {
                        pendingScrollReset = false
                        binding.channelList.scrollToPosition(0)
                    }
                    // While still browsing categories, preview the first channel
                    // of the focused category (like the reference design).
                    if (!inChannelView) {
                        val first = channels.firstOrNull()
                        if (first != null) showInfo(first) else clearPreview()
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
        binding.previewNumber.text = channel.number?.toString() ?: ""
        binding.previewTitle.text = ChannelText.clean(channel.name)
        val placeholder = LogoPlaceholder.forName(this, channel.name)
        if (channel.logoUrl.isNullOrBlank()) {
            binding.infoLogo.setImageDrawable(placeholder)
            binding.previewCaptionLogo.setImageDrawable(placeholder)
        } else {
            binding.infoLogo.load(channel.logoUrl) {
                placeholder(placeholder); error(placeholder)
            }
            binding.previewCaptionLogo.load(channel.logoUrl) {
                placeholder(placeholder); error(placeholder)
            }
        }
        binding.previewProgram.text = ""
        epgAdapter.submitList(emptyList())
        binding.epgEmpty.visibility = View.GONE

        nowNextJob?.cancel()
        nowNextJob = lifecycleScope.launch {
            try {
                val programs = viewModel.programs(channel)
                val now = System.currentTimeMillis()
                val current = programs.firstOrNull { it.isLiveAt(now) }
                binding.previewProgram.text = current?.let {
                    "${timeFmt.format(Date(it.startMs))} - ${timeFmt.format(Date(it.stopMs))}  ${it.title}"
                } ?: ""
                epgAdapter.submitList(programs) {
                    val idx = programs.indexOfFirst { it.isLiveAt(now) }
                    if (idx >= 0) binding.epgList.scrollToPosition(idx)
                }
                binding.epgEmpty.visibility =
                    if (programs.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Best-effort guide: never let an EPG fetch crash the UI coroutine.
                epgAdapter.submitList(emptyList())
                binding.epgEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun clearPreview() {
        currentInfoChannel = null
        binding.previewNumber.text = ""
        binding.previewTitle.text = ""
        binding.previewProgram.text = ""
        binding.infoLogo.setImageDrawable(null)
        binding.previewCaptionLogo.setImageDrawable(null)
        nowNextJob?.cancel()
        epgAdapter.submitList(emptyList())
        binding.epgEmpty.visibility = View.GONE
    }

    /** Drills from the category list into its channel list. */
    private fun enterChannelView() {
        if (currentChannels.isEmpty()) return
        categoryAdapter.setSelected(lastSelectedCategoryId)
        binding.channelList.visibility = View.VISIBLE
        binding.categoryList.visibility = View.GONE
        focusFirstChannel()
    }

    /** Returns from the channel list back to the category list. */
    private fun showCategories() {
        binding.categoryList.visibility = View.VISIBLE
        binding.channelList.visibility = View.GONE
        lastSelectedCategoryId?.let { focusCategory(it) }
    }

    /** Moves focus into the channel list, always landing on the first channel. */
    private fun focusFirstChannel() {
        binding.channelList.scrollToPosition(0)
        binding.channelList.post {
            (binding.channelList.findViewHolderForAdapterPosition(0)?.itemView
                ?: binding.channelList).requestFocus()
        }
    }

    /** Moves focus onto a given category row. */
    private fun focusCategory(id: String) {
        val pos = (0 until categoryAdapter.itemCount)
            .firstOrNull { categoryAdapter.currentList.getOrNull(it)?.id == id } ?: 0
        binding.categoryList.scrollToPosition(pos)
        binding.categoryList.post {
            (binding.categoryList.findViewHolderForAdapterPosition(pos)?.itemView
                ?: binding.categoryList).requestFocus()
        }
    }

    private fun openPlayer(channel: Channel) {
        PinLockHelper.guard(this, isAdult = channel.isAdult()) {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                putExtra(PlayerActivity.EXTRA_CATEGORY_ID, lastSelectedCategoryId)
            }
            startActivity(intent)
        }
    }
}
