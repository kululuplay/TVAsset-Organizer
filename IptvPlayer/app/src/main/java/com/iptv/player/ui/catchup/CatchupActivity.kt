/*
 * CatchupActivity.kt
 * Two-pane catch-up / timeshift browser:
 *   left   = channels that advertise an archive (catchupDays > 0)
 *   right  = past programs for the focused channel (newest first)
 * Selecting a program builds a timeshift URL and plays it in VodPlayerActivity
 * (ExoPlayer, fully seekable). Catch-up is only available on Xtream live streams.
 */
package com.iptv.player.ui.catchup

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.player.R
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.Program
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.databinding.ActivityCatchupBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.player.VodPlayerActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class CatchupActivity : BaseActivity() {

    private lateinit var binding: ActivityCatchupBinding
    private val viewModel: CatchupViewModel by lazy {
        ViewModelProvider(this)[CatchupViewModel::class.java]
    }

    private lateinit var channelAdapter: CatchupChannelAdapter
    private lateinit var programAdapter: CatchupProgramAdapter

    private var currentChannel: Channel? = null
    private var didInitialFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCatchupBinding.inflate(layoutInflater)
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
        channelAdapter = CatchupChannelAdapter(
            onFocused = { onChannelFocused(it) },
            onClicked = { binding.programList.requestFocus() }
        )
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = channelAdapter

        programAdapter = CatchupProgramAdapter(onClicked = { playProgram(it) })
        binding.programList.layoutManager = LinearLayoutManager(this)
        binding.programList.adapter = programAdapter
    }

    private fun observe() {
        lifecycleScope.launch {
            viewModel.channels.collectLatest { channels ->
                channelAdapter.submitList(channels)
                binding.emptyChannels.visibility =
                    if (channels.isEmpty()) View.VISIBLE else View.GONE
                if (!didInitialFocus && channels.isNotEmpty()) {
                    didInitialFocus = true
                    onChannelFocused(channels.first())
                    binding.channelList.post { binding.channelList.requestFocus() }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.programs.collectLatest { programs ->
                programAdapter.submitList(programs)
                binding.emptyPrograms.visibility =
                    if (programs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun onChannelFocused(channel: Channel) {
        currentChannel = channel
        binding.focusedChannelName.text = channel.name
        viewModel.selectChannel(channel)
    }

    private fun playProgram(program: Program) {
        val channel = currentChannel ?: return
        lifecycleScope.launch {
            val url = viewModel.urlFor(channel, program)
            if (url.isNullOrBlank()) {
                Toast.makeText(this@CatchupActivity, R.string.catchup_unavailable, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val intent = Intent(this@CatchupActivity, VodPlayerActivity::class.java).apply {
                putExtra(VodPlayerActivity.EXTRA_STREAM_URL, url)
                putExtra(VodPlayerActivity.EXTRA_TITLE, "${channel.name} — ${program.title}")
                putExtra(VodPlayerActivity.EXTRA_RESUME_ID, "catchup_${channel.id}_${program.startMs}")
                putExtra(VodPlayerActivity.EXTRA_RESUME_TYPE, ResumeKind.CATCHUP.raw)
            }
            startActivity(intent)
        }
    }
}
