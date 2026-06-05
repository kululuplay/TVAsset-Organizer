/*
 * VodActivity.kt
 * Movies (VOD) screen: categories on the left, a focusable poster grid in the
 * center, and a search field that filters across all movies. Clicking a poster
 * opens the VOD detail screen. Fully D-pad driven for TV.
 */
package com.iptv.player.ui.vod

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.player.databinding.ActivityVodBinding
import com.iptv.player.ui.common.BaseActivity
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
            onFocused = { viewModel.selectCategory(it.id) },
            onClicked = { binding.posterGrid.requestFocus() }
        )
        binding.categoryList.layoutManager = LinearLayoutManager(this)
        binding.categoryList.adapter = categoryAdapter

        vodAdapter = VodAdapter(
            onFocused = { },
            onClicked = { openDetail(it.id) }
        )
        binding.posterGrid.layoutManager = GridLayoutManager(this, 4)
        binding.posterGrid.adapter = vodAdapter
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observe() {
        lifecycleScope.launch {
            viewModel.categories.collectLatest { cats ->
                categoryAdapter.submitList(cats)
                if (cats.isNotEmpty()) viewModel.selectCategory(cats.first().id)
            }
        }
        lifecycleScope.launch {
            viewModel.items.collectLatest { items ->
                vodAdapter.submitList(items)
                binding.emptyState.visibility =
                    if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openDetail(id: String) {
        val intent = Intent(this, VodDetailActivity::class.java).apply {
            putExtra(VodDetailActivity.EXTRA_VOD_ID, id)
        }
        startActivity(intent)
    }
}
