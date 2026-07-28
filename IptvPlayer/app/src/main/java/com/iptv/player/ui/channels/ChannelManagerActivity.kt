/*
 * ChannelManagerActivity.kt
 * Lets the user hide/show, reorder and favourite live channels — optionally
 * scoped to a single category (opened from the Content Manager). OK shows/hides a
 * row; long-press enters "move mode" where D-pad up/down reorders (OK/Back
 * commits); ▶ toggles favourite. Hidden channels are filtered out of the
 * Home/Guide lists and the custom order is applied via the overrides table; both
 * survive playlist refreshes. Reordering within a category keeps the global order
 * of every other channel intact.
 */
package com.iptv.player.ui.channels

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.player.R
import com.iptv.player.data.model.ManagedChannel
import com.iptv.player.databinding.ActivityChannelManagerBinding
import com.iptv.player.ui.common.BaseActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChannelManagerActivity : BaseActivity() {

    private lateinit var binding: ActivityChannelManagerBinding
    private var categoryId: String? = null
    private val viewModel: ChannelManagerViewModel by lazy {
        ViewModelProvider(this, ChannelManagerViewModel.Factory(categoryId))[ChannelManagerViewModel::class.java]
    }

    private lateinit var adapter: ChannelManagerAdapter

    // Display working copy (this category) plus the full live id order, so we can
    // reorder within the category while persisting a globally-consistent order.
    private var items: MutableList<ManagedChannel> = mutableListOf()
    private var fullIds: List<String> = emptyList()
    private var movingId: String? = null
    private var pendingFocusId: String? = null
    private var restoredFocusId: String? = null
    private var lastFocusedId: String? = null
    private var initialFocusApplied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        categoryId = intent.getStringExtra(EXTRA_CATEGORY_ID)
        binding = ActivityChannelManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        intent.getStringExtra(EXTRA_CATEGORY_NAME)?.let { binding.cmTitle.text = it }
        restoredFocusId = savedInstanceState?.getString(STATE_FOCUSED_CHANNEL_ID)
        lastFocusedId = restoredFocusId
        ViewCompat.setAccessibilityPaneTitle(binding.root, binding.cmTitle.text)
        ViewCompat.setAccessibilityHeading(binding.cmTitle, true)

        adapter = ChannelManagerAdapter(
            onClicked = { if (movingId == null) viewModel.setHidden(it.channel.id, !it.hidden) },
            onLongClicked = { enterMoveMode(it) },
            onFavorite = { viewModel.toggleFavorite(it.channel.id) },
            onFocused = { lastFocusedId = it }
        )
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = adapter
        binding.channelList.itemAnimator = null

        lifecycleScope.launch {
            viewModel.allChannels.collectLatest { all -> fullIds = all.map { it.channel.id } }
        }
        lifecycleScope.launch {
            viewModel.channels.collectLatest { list ->
                if (movingId != null) return@collectLatest
                items = list.toMutableList()
                submit()
            }
        }
    }

    private fun submit() {
        adapter.submitList(items.toList()) {
            updateSummary()
            binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            binding.channelList.post {
                val pending = pendingFocusId.also { pendingFocusId = null }
                val initial = if (!initialFocusApplied && items.isNotEmpty()) {
                    initialFocusApplied = true
                    restoredFocusId ?: items.first().channel.id
                } else {
                    null
                }

                when {
                    pending != null -> focusChannel(pending)
                    initial != null -> focusChannel(initial)
                }
            }
        }
    }

    private fun focusChannel(id: String) {
        val position = items.indexOfFirst { it.channel.id == id }
            .takeIf { it >= 0 }
            ?: items.indices.firstOrNull()
            ?: return
        binding.channelList.scrollToPosition(position)
        binding.channelList.post {
            binding.channelList.layoutManager
                ?.findViewByPosition(position)
                ?.requestFocus()
        }
    }

    private fun updateSummary() {
        val hidden = items.count { it.hidden }
        val shown = items.size - hidden
        binding.cmSummary.text = getString(R.string.manager_summary, shown, hidden)
    }

    // ---- Move mode -----------------------------------------------------

    private fun enterMoveMode(item: ManagedChannel) {
        movingId = item.channel.id
        adapter.movingId = movingId
        refreshMovingRow(item.channel.id)
    }

    private fun exitMoveMode() {
        val moving = movingId ?: return
        movingId = null
        adapter.movingId = null
        // Reorder only this category's slots inside the global order, leaving every
        // other channel's position untouched.
        if (fullIds.isNotEmpty()) {
            val displayIds = items.map { it.channel.id }
            val displaySet = displayIds.toHashSet()
            var k = 0
            val newGlobal = fullIds.map { gid ->
                if (gid in displaySet && k < displayIds.size) displayIds[k++] else gid
            }
            viewModel.applyOrder(newGlobal)
        }
        refreshMovingRow(moving)
    }

    /**
     * Rebinds the row whose move state just changed (list order is unchanged
     * here, so [submit]'s DiffUtil would skip the rebind) and keeps focus on it.
     * notifyItemChanged is targeted — not a blanket refresh.
     */
    private fun refreshMovingRow(id: String) {
        val pos = items.indexOfFirst { it.channel.id == id }
        if (pos < 0) return
        adapter.notifyItemChanged(pos)
        binding.channelList.post {
            binding.channelList.layoutManager?.findViewByPosition(pos)?.requestFocus()
        }
    }

    private fun moveBy(delta: Int) {
        val id = movingId ?: return
        val index = items.indexOfFirst { it.channel.id == id }
        val target = index + delta
        if (index < 0 || target < 0 || target >= items.size) return
        val row = items.removeAt(index)
        items.add(target, row)
        pendingFocusId = id
        submit()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (movingId != null) {
            // Trap all D-pad navigation while moving so focus can't escape the
            // list and trigger background reorders; OK or Back commits and exits.
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> moveBy(-1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(+1)
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_BACK -> exitMoveMode()
                }
            }
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BACK -> true
                else -> super.dispatchKeyEvent(event)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(
            STATE_FOCUSED_CHANNEL_ID,
            movingId ?: lastFocusedId ?: restoredFocusId
        )
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_CATEGORY_ID = "extra_category_id"
        const val EXTRA_CATEGORY_NAME = "extra_category_name"
        private const val STATE_FOCUSED_CHANNEL_ID = "state_focused_channel_id"
    }
}
