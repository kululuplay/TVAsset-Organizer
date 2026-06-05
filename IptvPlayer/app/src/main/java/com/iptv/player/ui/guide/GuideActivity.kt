/*
 * GuideActivity.kt
 * D-pad TV guide. Left column lists live channels; focusing one loads that
 * channel's program window (now-30m .. now+3h) into the horizontal timeline on
 * the right, highlighting the live program. A "Refresh guide" button downloads
 * fresh XMLTV (progress + Toast). Long-press a channel to match its EPG id.
 */
package com.iptv.player.ui.guide

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.player.R
import com.iptv.player.data.model.Channel
import com.iptv.player.databinding.ActivityGuideBinding
import com.iptv.player.ui.common.BaseActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class GuideActivity : BaseActivity() {

    private lateinit var binding: ActivityGuideBinding
    private val viewModel: GuideViewModel by lazy {
        ViewModelProvider(this)[GuideViewModel::class.java]
    }

    private lateinit var channelAdapter: GuideChannelAdapter
    private lateinit var programAdapter: GuideProgramAdapter

    private var didInitialFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateNowIndicator()
        setupLists()
        setupRefresh()
        observe()
    }

    private fun setupLists() {
        channelAdapter = GuideChannelAdapter(
            scope = lifecycleScope,
            onFocused = { onChannelFocused(it) },
            onClicked = { binding.programList.requestFocus() },
            onLongPress = { openMapping(it) }
        )
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = channelAdapter

        programAdapter = GuideProgramAdapter(onClicked = { /* no-op: timeline preview */ })
        binding.programList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.programList.adapter = programAdapter
    }

    private fun setupRefresh() {
        binding.btnRefresh.setOnClickListener { viewModel.refreshGuide() }
    }

    private fun observe() {
        lifecycleScope.launch {
            viewModel.channels.collectLatest { channels ->
                channelAdapter.submitList(channels)
                if (!didInitialFocus && channels.isNotEmpty()) {
                    didInitialFocus = true
                    viewModel.selectChannel(channels.first())
                    binding.channelList.post { binding.channelList.requestFocus() }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.programs.collectLatest { programs ->
                programAdapter.submitList(programs)
                binding.emptyGuide.visibility =
                    if (programs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            viewModel.refresh.collectLatest { state -> renderRefresh(state) }
        }
    }

    private fun renderRefresh(state: GuideViewModel.RefreshState) {
        when (state) {
            is GuideViewModel.RefreshState.Loading -> {
                binding.refreshProgress.visibility = View.VISIBLE
                binding.btnRefresh.isEnabled = false
            }
            is GuideViewModel.RefreshState.Done -> {
                binding.refreshProgress.visibility = View.GONE
                binding.btnRefresh.isEnabled = true
                Toast.makeText(this, getString(R.string.guide_updated), Toast.LENGTH_SHORT).show()
                viewModel.consumeRefresh()
            }
            is GuideViewModel.RefreshState.Failed -> {
                binding.refreshProgress.visibility = View.GONE
                binding.btnRefresh.isEnabled = true
                Toast.makeText(this, getString(state.messageRes), Toast.LENGTH_SHORT).show()
                viewModel.consumeRefresh()
            }
            is GuideViewModel.RefreshState.Idle -> {
                binding.refreshProgress.visibility = View.GONE
                binding.btnRefresh.isEnabled = true
            }
        }
    }

    private fun onChannelFocused(channel: Channel) {
        binding.focusedChannelName.text = channel.name
        viewModel.selectChannel(channel)
    }

    private fun updateNowIndicator() {
        val now = System.currentTimeMillis()
        binding.nowIndicator.text =
            getString(R.string.guide_now) + " " + EpgTimeFormatter.time(now)
    }

    private fun openMapping(channel: Channel) {
        val intent = Intent(this, EpgMappingActivity::class.java).apply {
            putExtra(EpgMappingActivity.EXTRA_CHANNEL_ID, channel.id)
        }
        startActivity(intent)
    }
}
