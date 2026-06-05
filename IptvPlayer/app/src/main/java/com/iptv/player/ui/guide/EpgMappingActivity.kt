/*
 * EpgMappingActivity.kt
 * Lets the user enter / confirm the EPG channel id (tvg-id) for a single live
 * channel passed via EXTRA_CHANNEL_ID. Pre-fills the channel's known epg id and
 * persists the override via repository.setEpgMapping. D-pad friendly.
 */
package com.iptv.player.ui.guide

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.databinding.ActivityEpgMappingBinding
import com.iptv.player.ui.common.BaseActivity
import kotlinx.coroutines.launch

class EpgMappingActivity : BaseActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
    }

    private lateinit var binding: ActivityEpgMappingBinding
    private var channelId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpgMappingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        loadChannel()

        binding.btnSave.setOnClickListener { save() }
        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun loadChannel() {
        val id = channelId ?: run { finish(); return }
        lifecycleScope.launch {
            val channel = ServiceLocator.repository.getChannel(id)
            if (channel == null) {
                finish()
                return@launch
            }
            binding.channelName.text = channel.name
            binding.inputEpgId.setText(channel.epgChannelId ?: "")
        }
    }

    private fun save() {
        val id = channelId ?: return
        val epgId = binding.inputEpgId.text?.toString()?.trim().orEmpty()
        lifecycleScope.launch {
            ServiceLocator.repository.setEpgMapping(id, epgId)
            Toast.makeText(
                this@EpgMappingActivity,
                getString(R.string.epg_mapping_saved),
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }
}
