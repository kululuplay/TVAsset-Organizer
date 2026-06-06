/*
 * SeriesActivity.kt
 * Series screen: categories on the left, a focusable poster grid in the center,
 * and a search field. Clicking a poster opens the series detail screen.
 * Mirrors VodActivity; fully D-pad driven for TV.
 */
package com.iptv.player.ui.series

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.player.databinding.ActivitySeriesBinding
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

class SeriesActivity : BaseActivity() {

    private lateinit var binding: ActivitySeriesBinding
    private val viewModel: SeriesViewModel by lazy {
        ViewModelProvider(this)[SeriesViewModel::class.java]
    }

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var seriesAdapter: SeriesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLists()
        setupSearch()
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

        seriesAdapter = SeriesAdapter(
            onFocused = { },
            onClicked = { item ->
                PinLockHelper.guard(this, isAdult = item.isAdult()) { openDetail(item.id) }
            }
        )
        binding.posterGrid.layoutManager = GridLayoutManager(this, 4)
        binding.posterGrid.autoFitColumns(min = 3)
        binding.posterGrid.adapter = seriesAdapter
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        // Hard guarantee D-pad escape from the search box: an EditText consumes
        // LEFT/RIGHT for cursor movement and DOWN handling varies across TV
        // devices, so move focus into content explicitly instead of relying on
        // geometric focus search.
        binding.searchInput.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { focusFirstCategory(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { focusFirstItem(); true }
                else -> false
            }
        }
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
                        // Land focus on the content (first category) instead of
                        // the search box, so the screen is navigable on entry and
                        // the user can D-pad down/right; UP returns to search.
                        focusFirstCategory()
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.items.collectLatest { items ->
                seriesAdapter.submitList(items) {
                    // New category / search result: start from the first poster.
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
        val intent = Intent(this, SeriesDetailActivity::class.java).apply {
            putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, id)
        }
        startActivity(intent)
    }
}
