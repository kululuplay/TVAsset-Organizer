/*
 * ChannelManagerActivity.kt
 * Lets the user hide/show and reorder live channels. Clicking a channel opens an
 * actions dialog. Hidden channels are filtered out of the Home/Guide lists; the
 * custom order is applied via a LEFT JOIN on the overrides table. Both survive
 * playlist refreshes.
 */
package com.iptv.player.ui.channels

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
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
    private val viewModel: ChannelManagerViewModel by lazy {
        ViewModelProvider(this)[ChannelManagerViewModel::class.java]
    }

    private lateinit var adapter: ChannelManagerAdapter
    private var currentList: List<ManagedChannel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ChannelManagerAdapter(onClicked = { showActions(it) })
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = adapter

        lifecycleScope.launch {
            viewModel.channels.collectLatest { list ->
                currentList = list
                adapter.submitList(list)
                binding.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showActions(item: ManagedChannel) {
        val hideLabel = getString(
            if (item.hidden) R.string.channel_manager_show else R.string.channel_manager_hide
        )
        val favoriteLabel = getString(
            if (item.isFavorite) R.string.channel_manager_unfavorite
            else R.string.channel_manager_favorite
        )
        val labels = arrayOf(
            hideLabel,
            favoriteLabel,
            getString(R.string.channel_manager_move_up),
            getString(R.string.channel_manager_move_down)
        )
        AlertDialog.Builder(this)
            .setTitle(item.channel.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> viewModel.setHidden(item.channel.id, !item.hidden)
                    1 -> viewModel.toggleFavorite(item.channel.id)
                    2 -> move(item, -1)
                    3 -> move(item, +1)
                }
            }
            .show()
    }

    private fun move(item: ManagedChannel, delta: Int) {
        val ids = currentList.map { it.channel.id }.toMutableList()
        val index = ids.indexOf(item.channel.id)
        val target = index + delta
        if (index < 0 || target < 0 || target >= ids.size) return
        ids.removeAt(index)
        ids.add(target, item.channel.id)
        viewModel.applyOrder(ids)
    }
}
