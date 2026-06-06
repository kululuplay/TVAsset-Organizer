/*
 * VodActivity.kt
 * Movies (VOD) screen: categories on the left and a focusable poster grid in the
 * center. Clicking a poster opens the VOD detail screen. Fully D-pad driven for TV.
 */
package com.iptv.player.ui.vod

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.player.databinding.ActivityVodBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.PinLockHelper
import com.iptv.player.ui.common.autoFitColumns
import com.iptv.player.ui.common.isAdult
import com.iptv.player.ui.home.CategoryAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class VodActivity : BaseActivity() {

    private lateinit var binding: ActivityVodBinding
    private val viewModel: VodViewModel by lazy {
        ViewModelProvider(this)[VodViewModel::class.java]
    }

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var vodAdapter: VodAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLists()
        observe()
    }

    override fun onResume() {
        super.onResume()
        binding.dateText.text = DateFormat
            .getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            .format(Date())
    }

    private fun setupLists() {
        categoryAdapter = CategoryAdapter(
            onFocused = { cat ->
                viewModel.selectCategory(cat.id)
                categoryAdapter.setSelected(cat.id)
                binding.contentTitle.text = cat.name
            },
            onClicked = { focusFirstItem() }
        )
        binding.categoryList.layoutManager = LinearLayoutManager(this)
        binding.categoryList.adapter = categoryAdapter

        vodAdapter = VodAdapter(
            onFocused = { },
            onClicked = { item ->
                PinLockHelper.guard(this, isAdult = item.isAdult()) { openDetail(item.id) }
            }
        )
        binding.posterGrid.layoutManager = GridLayoutManager(this, 4)
        binding.posterGrid.autoFitColumns(min = 3)
        binding.posterGrid.adapter = vodAdapter
    }

    private fun observe() {
        lifecycleScope.launch {
            viewModel.categories.collectLatest { cats ->
                val firstLoad = categoryAdapter.currentList.isEmpty()
                categoryAdapter.submitList(cats) {
                    if (firstLoad && cats.isNotEmpty()) {
                        val first = cats.first()
                        viewModel.selectCategory(first.id)
                        categoryAdapter.setSelected(first.id)
                        binding.contentTitle.text = first.name
                        // Land focus on the first category so the screen is
                        // navigable on entry and the user can D-pad down/right.
                        focusFirstCategory()
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.items.collectLatest { items ->
                vodAdapter.submitList(items) {
                    // New category: start from the first poster.
                    binding.posterGrid.scrollToPosition(0)
                }
                binding.emptyState.visibility =
                    if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /** Moves focus onto the first category row (the on-entry focus target). */
    private fun focusFirstCategory() {
        binding.categoryList.post {
            (binding.categoryList.findViewHolderForAdapterPosition(0)?.itemView
                ?: binding.categoryList).requestFocus()
        }
    }

    /** Moves focus into the poster grid, always landing on the first item. */
    private fun focusFirstItem() {
        binding.posterGrid.scrollToPosition(0)
        binding.posterGrid.post {
            (binding.posterGrid.findViewHolderForAdapterPosition(0)?.itemView
                ?: binding.posterGrid).requestFocus()
        }
    }

    private fun openDetail(id: String) {
        val intent = Intent(this, VodDetailActivity::class.java).apply {
            putExtra(VodDetailActivity.EXTRA_VOD_ID, id)
        }
        startActivity(intent)
    }
}
