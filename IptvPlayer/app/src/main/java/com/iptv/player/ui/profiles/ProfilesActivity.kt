/*
 * ProfilesActivity.kt
 * Manage saved subscriptions: list profiles, add a new one via a small form,
 * switch the active profile (click) and delete one (long-press). Backed by
 * repository.observeProfiles() and ServiceLocator.settings for the active id.
 */
package com.iptv.player.ui.profiles

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Profile
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.databinding.ActivityProfilesBinding
import com.iptv.player.ui.common.BaseActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ProfilesActivity : BaseActivity() {

    private lateinit var binding: ActivityProfilesBinding
    private lateinit var adapter: ProfileAdapter

    private val repository = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ProfileAdapter(
            onClicked = { switchActive(it) },
            onLongClicked = { confirmDelete(it) }
        )
        binding.profileList.layoutManager = LinearLayoutManager(this)
        binding.profileList.adapter = adapter

        binding.btnAddProfile.setOnClickListener { showAddDialog() }

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repository.observeProfiles().collectLatest { profiles ->
                adapter.activeProfileId = settings.getActiveProfileId()
                adapter.submitList(profiles)
                binding.profilesEmpty.visibility =
                    if (profiles.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            settings.activeProfileId.collectLatest { id -> adapter.activeProfileId = id }
        }
    }

    private fun switchActive(profile: Profile) {
        lifecycleScope.launch { settings.setActiveProfileId(profile.id) }
    }

    private fun confirmDelete(profile: Profile) {
        AlertDialog.Builder(this)
            .setMessage(R.string.profile_delete_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch { repository.removeProfile(profile.id) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Simple Xtream profile form (name + server + username + password). */
    private fun showAddDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.space_m)
            setPadding(pad, pad, pad, pad)
        }
        val nameInput = editText(R.string.hint_profile_name)
        val serverInput = editText(R.string.hint_server)
        val userInput = editText(R.string.hint_username)
        val passInput = editText(R.string.hint_password).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(nameInput)
        container.addView(serverInput)
        container.addView(userInput)
        container.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_add_profile)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val config = SourceConfig(
                    type = SourceType.XTREAM,
                    serverUrl = serverInput.text.toString().trim(),
                    username = userInput.text.toString().trim(),
                    password = passInput.text.toString().trim()
                )
                lifecycleScope.launch { repository.addProfile(name, config, lockAdult = false) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun editText(hintRes: Int): EditText = EditText(this).apply {
        setHint(hintRes)
        setSingleLine()
    }
}
