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

        adapter = CategoryManagerAdapter(
            canDrill = type == ContentType.LIVE,
            onClicked = { if (movingId == null) viewModel.setHidden(it.category.id, !it.hidden) },
            onLongClicked = { enterMoveMode(it) },
            onDrill = { openChannels(it) }
        )
        binding.categoryList.layoutManager = LinearLayoutManager(this)
        binding.categoryList.adapter = adapter

        binding.cmReset.setOnClickListener {
            if (movingId == null) viewModel.resetOrder()
        }

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
            pendingFocusId?.let { id ->
                val pos = items.indexOfFirst { it.category.id == id }
                if (pos >= 0) {
                    binding.categoryList.post {
                        binding.categoryList.layoutManager?.findViewByPosition(pos)?.requestFocus()
                    }
                }
                pendingFocusId = null
            }
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
            // list and trigger background reorders; only commit/cancel exits.
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

    private fun openChannels(item: ManagedCategory) {
        startActivity(
            Intent(this, ChannelManagerActivity::class.java)
                .putExtra(ChannelManagerActivity.EXTRA_CATEGORY_ID, item.category.id)
                .putExtra(ChannelManagerActivity.EXTRA_CATEGORY_NAME, item.category.name)
        )
    }

    companion object {
        const val EXTRA_TYPE = "extra_type"
    }
}
