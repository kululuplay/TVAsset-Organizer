/*
 * CategoryManagerActivity.kt
 * Per-type category editor (Live / Movies / Series). OK shows/hides a category;
 * long-press enters "move mode" where D-pad up/down reorders the row (OK or Back
 * commits the new order). For Live, ▶ on a row drills into the channel editor for
 * that category. Hidden + order are persisted in DataStore via the ViewModel.
 */
package com.iptv.player.ui.content

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.player.R
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.ManagedCategory
import com.iptv.player.databinding.ActivityCategoryManagerBinding
import com.iptv.player.ui.channels.ChannelManagerActivity
import com.iptv.player.ui.common.BaseActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CategoryManagerActivity : BaseActivity() {

    private lateinit var binding: ActivityCategoryManagerBinding
    private lateinit var type: ContentType
    private val viewModel: CategoryManagerViewModel by lazy {
        ViewModelProvider(this, CategoryManagerViewModel.Factory(type))[CategoryManagerViewModel::class.java]
    }

    private lateinit var adapter: CategoryManagerAdapter

    // Local working copy so we can reorder live (in move mode) without waiting on
    // the flow. The flow only re-emits on hide toggles / persisted order, so this
    // never fights the collector.
    private var items: MutableList<ManagedCategory> = mutableListOf()
    private var movingId: String? = null
    private var pendingFocusId: String? = null
    private var restoredFocusId: String? = null
    private var lastFocusedId: String? = null
    private var initialFocusApplied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = runCatching { ContentType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "") }
            .getOrDefault(ContentType.LIVE)
        binding = ActivityCategoryManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cmTitle.text = getString(
            when (type) {
                ContentType.LIVE -> R.string.category_manager_title_live
                ContentType.VOD -> R.string.category_manager_title_movies
                ContentType.SERIES -> R.string.category_manager_title_series
            }
        )
        binding.cmHint.text = getString(
            if (type == ContentType.LIVE) R.string.category_manager_hint_live
            else R.string.category_manager_hint
        )
        val (iconRes, badgeRes) = when (type) {
            ContentType.LIVE -> R.drawable.ic_tv to R.drawable.bg_badge_live
            ContentType.VOD -> R.drawable.ic_movie to R.drawable.bg_badge_movies
            ContentType.SERIES -> R.drawable.ic_series to R.drawable.bg_badge_series
        }
        binding.cmIcon.setImageResource(iconRes)
        binding.cmIcon.setBackgroundResource(badgeRes)
        restoredFocusId = savedInstanceState?.getString(STATE_FOCUSED_CATEGORY_ID)
        lastFocusedId = restoredFocusId

        ViewCompat.setAccessibilityPaneTitle(binding.root, binding.cmTitle.text)
        ViewCompat.setAccessibilityHeading(binding.cmTitle, true)

        adapter = CategoryManagerAdapter(
            canDrill = type == ContentType.LIVE,
            onClicked = { if (movingId == null) viewModel.setHidden(it.category.id, !it.hidden) },
            onLongClicked = { enterMoveMode(it) },
            onDrill = { openChannels(it) },
            onFocused = { lastFocusedId = it }
        )
        binding.categoryList.layoutManager = LinearLayoutManager(this)
        binding.categoryList.adapter = adapter
        // Change animations briefly detach/rebind a focused row after a hide
        // toggle. Disabling them prevents visible focus flicker on TV devices.
        binding.categoryList.itemAnimator = null

        binding.cmReset.setOnClickListener {
            if (movingId == null) viewModel.resetOrder()
        }
        binding.cmReset.nextFocusUpId = binding.cmReset.id
        binding.cmReset.nextFocusLeftId = binding.cmReset.id
        binding.cmReset.nextFocusRightId = binding.cmReset.id

        lifecycleScope.launch {
            viewModel.categories.collectLatest { list ->
                // Ignore flow updates while actively reordering a row.
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
            binding.categoryList.post {
                updateFocusGraph()

                val pending = pendingFocusId.also { pendingFocusId = null }
                val initial = if (!initialFocusApplied && items.isNotEmpty()) {
                    initialFocusApplied = true
                    restoredFocusId ?: items.first().category.id
                } else {
                    null
                }

                when {
                    pending != null -> focusCategory(pending)
                    initial != null -> focusCategory(initial)
                    items.isEmpty() && !binding.cmReset.hasFocus() ->
                        binding.cmReset.requestFocus()
                }
            }
        }
    }

    private fun updateFocusGraph() {
        val first = binding.categoryList.layoutManager?.findViewByPosition(0)
        if (first == null) {
            binding.cmReset.nextFocusDownId = binding.cmReset.id
            return
        }
        first.nextFocusUpId = binding.cmReset.id
        binding.cmReset.nextFocusDownId = first.id
    }

    private fun focusCategory(id: String) {
        val position = items.indexOfFirst { it.category.id == id }
            .takeIf { it >= 0 }
            ?: items.indices.firstOrNull()
            ?: return
        binding.categoryList.scrollToPosition(position)
        binding.categoryList.post {
            updateFocusGraph()
            binding.categoryList.layoutManager
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

    private fun enterMoveMode(item: ManagedCategory) {
        movingId = item.category.id
        adapter.movingId = movingId
        refreshMovingRow(item.category.id)
    }

    private fun exitMoveMode() {
        val moving = movingId ?: return
        movingId = null
        adapter.movingId = null
        viewModel.setOrder(items.map { it.category.id })
        refreshMovingRow(moving)
    }

    /**
     * Rebinds the row whose move state just changed (the list order itself is
     * unchanged here, so [submit]'s DiffUtil would skip the rebind) and keeps
     * focus on it. notifyItemChanged is targeted — not a blanket refresh.
     */
    private fun refreshMovingRow(id: String) {
        val pos = items.indexOfFirst { it.category.id == id }
        if (pos < 0) return
        adapter.notifyItemChanged(pos)
        binding.categoryList.post {
            binding.categoryList.layoutManager?.findViewByPosition(pos)?.requestFocus()
        }
    }

    private fun moveBy(delta: Int) {
        val id = movingId ?: return
        val index = items.indexOfFirst { it.category.id == id }
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
        if (binding.cmReset.hasFocus() && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                items.firstOrNull()?.let { focusCategory(it.category.id) }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(
            STATE_FOCUSED_CATEGORY_ID,
            movingId ?: lastFocusedId ?: restoredFocusId
        )
        super.onSaveInstanceState(outState)
    }

    private fun openChannels(item: ManagedCategory) {
        startActivity(
            Intent(this, ChannelManagerActivity::class.java)
                .putExtra(ChannelManagerActivity.EXTRA_CATEGORY_ID, item.category.id)
                .putExtra(ChannelManagerActivity.EXTRA_CATEGORY_NAME, item.category.name)
        )
    }

    companion object {
        const val EXTRA_TYPE = "extra_type"
        private const val STATE_FOCUSED_CATEGORY_ID = "state_focused_category_id"
    }
}
