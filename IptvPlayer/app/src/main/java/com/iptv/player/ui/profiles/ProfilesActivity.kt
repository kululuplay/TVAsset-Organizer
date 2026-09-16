/*
 * ProfilesActivity.kt
 * Manage saved subscriptions: list profiles, add a new one via a small form,
 * switch the active profile (click) and delete one (long-press). Backed by
 * repository.observeProfiles() and ServiceLocator.settings for the active id.
 * Switching applies the profile's own source (server + credentials) and routes
 * through the branded splash exactly like a fresh login does.
 */
package com.iptv.player.ui.profiles

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Profile
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.databinding.ActivityProfilesBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.login.LoginServerUrl
import com.iptv.player.ui.splash.SplashActivity
import com.iptv.player.ui.splash.SplashPrefetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProfilesActivity : BaseActivity() {

    private lateinit var binding: ActivityProfilesBinding
    private lateinit var adapter: ProfileAdapter

    private val repository = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    private var switchInFlight = false

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
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.observeProfiles().collectLatest { profiles ->
                        adapter.submitList(profiles)
                        binding.profilesEmpty.visibility =
                            if (profiles.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    settings.activeProfileId.collectLatest { id -> adapter.activeProfileId = id }
                }
            }
        }
    }

    /**
     * Makes [profile] the streaming account: saves its source the same way the
     * login screen does, marks it active and restarts through the splash so the
     * new account's channels/EPG/library are prefetched before the home screen.
     */
    private fun switchActive(profile: Profile) {
        if (switchInFlight) return
        switchInFlight = true
        lifecycleScope.launch {
            try {
                val alreadyActive = settings.getActiveProfileId() == profile.id &&
                    settings.getSourceConfig() == profile.config
                if (alreadyActive) return@launch

                SplashPrefetch.cancel()
                settings.saveSource(profile.config)
                settings.setActiveProfileId(profile.id)

                val intent = Intent(this@ProfilesActivity, SplashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Toast.makeText(this@ProfilesActivity, R.string.error_unknown, Toast.LENGTH_SHORT)
                    .show()
            } finally {
                switchInFlight = false
            }
        }
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
                    // Same normalisation as the login screen so a switched profile
                    // produces the exact SourceConfig a fresh login would.
                    serverUrl = LoginServerUrl.normalize(serverInput.text.toString()),
                    username = userInput.text.toString().trim(),
                    // Passwords are opaque provider credentials; keep spaces intact
                    // (LoginViewModel deliberately does not trim them either).
                    password = passInput.text.toString()
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
