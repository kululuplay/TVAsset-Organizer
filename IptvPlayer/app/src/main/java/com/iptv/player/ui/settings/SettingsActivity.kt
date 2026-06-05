/*
 * SettingsActivity.kt
 * A vertically scrollable, D-pad friendly settings screen. Rows and section
 * headers are inflated into a LinearLayout inside a ScrollView. Each row reflects
 * the live value from SettingsViewModel and opens a dialog / sub-screen on click.
 */
package com.iptv.player.ui.settings

import android.content.Intent
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.databinding.ActivitySettingsBinding
import com.iptv.player.ui.account.AccountActivity
import com.iptv.player.ui.channels.ChannelManagerActivity
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.PinDialog
import com.iptv.player.ui.diagnostics.DiagnosticsActivity
import com.iptv.player.ui.login.LoginActivity
import com.iptv.player.ui.profiles.ProfilesActivity
import com.iptv.player.util.LocaleManager
import com.iptv.player.work.SyncScheduler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by lazy {
        ViewModelProvider(this)[SettingsViewModel::class.java]
    }

    // Row value views we update reactively.
    private lateinit var playerValue: TextView
    private lateinit var clockValue: TextView
    private lateinit var screensaverValue: TextView
    private lateinit var languageValue: TextView
    private lateinit var tmdbValue: TextView
    private lateinit var lockAdultValue: TextView
    private lateinit var autoSyncValue: TextView
    private lateinit var autoSyncHoursValue: TextView

    private var autoSyncEnabled = false
    private var autoSyncHours = 12

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildRows()
        observe()
    }

    private fun buildRows() {
        val c = binding.settingsContainer

        // ---- Playback ----
        addHeader(c, R.string.settings_playback)
        playerValue = addRow(c, getString(R.string.settings_player_mode)) { showPlayerModeDialog() }
        clockValue = addRow(c, getString(R.string.settings_clock)) { toggleClock() }
        screensaverValue = addRow(c, getString(R.string.settings_screensaver)) { showScreensaverDialog() }

        // ---- General ----
        addHeader(c, R.string.settings_general)
        languageValue = addRow(c, getString(R.string.settings_language)) { showLanguageDialog() }
        tmdbValue = addRow(
            c,
            getString(R.string.settings_tmdb),
            getString(R.string.settings_tmdb_hint)
        ) { showTmdbDialog() }

        // ---- Content / sync ----
        addHeader(c, R.string.settings_content)
        addRow(
            c,
            getString(R.string.settings_channel_manager),
            getString(R.string.settings_channel_manager_hint)
        ) { startActivity(Intent(this, ChannelManagerActivity::class.java)) }
        autoSyncValue = addRow(
            c,
            getString(R.string.settings_auto_sync),
            getString(R.string.settings_auto_sync_hint)
        ) { toggleAutoSync() }
        autoSyncHoursValue = addRow(c, getString(R.string.settings_auto_sync_interval)) {
            showAutoSyncIntervalDialog()
        }

        // ---- Parental control ----
        addHeader(c, R.string.settings_parental)
        lockAdultValue = addRow(c, getString(R.string.settings_lock_adult)) { toggleLockAdult() }
        addRow(c, getString(R.string.settings_parental_pin)) { showSetPinDialog() }

        // ---- Navigation to sub-screens ----
        addHeader(c, R.string.settings_general)
        addRow(c, getString(R.string.settings_profiles)) {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }
        addRow(c, getString(R.string.settings_account)) {
            startActivity(Intent(this, AccountActivity::class.java))
        }
        addRow(c, getString(R.string.settings_diagnostics)) {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        addRow(c, getString(R.string.settings_about)) {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        addRow(c, getString(R.string.settings_logout)) { confirmLogout() }
    }

    private fun observe() {
        lifecycleScope.launch {
            viewModel.playerMode.collectLatest { playerValue.text = playerModeLabel(it) }
        }
        lifecycleScope.launch {
            viewModel.showClock.collectLatest { clockValue.text = onOff(it) }
        }
        lifecycleScope.launch {
            viewModel.screensaverMinutes.collectLatest {
                screensaverValue.text = if (it <= 0) getString(R.string.settings_screensaver_off)
                else getString(R.string.settings_minutes, it)
            }
        }
        lifecycleScope.launch {
            viewModel.languageTag.collectLatest {
                languageValue.text = if (it.isBlank()) "" else LocaleManager.displayName(it)
            }
        }
        lifecycleScope.launch {
            viewModel.tmdbKey.collectLatest { tmdbValue.text = onOff(it.isNotBlank()) }
        }
        lifecycleScope.launch {
            viewModel.lockAdult.collectLatest { lockAdultValue.text = onOff(it) }
        }
        lifecycleScope.launch {
            viewModel.autoSyncEnabled.collectLatest {
                autoSyncEnabled = it
                autoSyncValue.text = onOff(it)
            }
        }
        lifecycleScope.launch {
            viewModel.autoSyncHours.collectLatest {
                autoSyncHours = it
                autoSyncHoursValue.text = getString(R.string.settings_every_hours, it)
            }
        }
    }

    // ---- Dialogs / actions ---------------------------------------------

    private fun showPlayerModeDialog() {
        val modes = listOf(PlayerMode.AUTO, PlayerMode.EXOPLAYER, PlayerMode.VLC)
        val labels = modes.map { playerModeLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_player_mode)
            .setItems(labels) { _, which -> viewModel.setPlayerMode(modes[which]) }
            .show()
    }

    private fun toggleClock() {
        val current = clockValue.text == getString(R.string.settings_on)
        viewModel.setShowClock(!current)
    }

    private fun showScreensaverDialog() {
        val options = listOf(0, 5, 10, 15, 30)
        val labels = options.map {
            if (it == 0) getString(R.string.settings_screensaver_off)
            else getString(R.string.settings_minutes, it)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_screensaver)
            .setItems(labels) { _, which -> viewModel.setScreensaverMinutes(options[which]) }
            .show()
    }

    private fun showLanguageDialog() {
        val tags = LocaleManager.SUPPORTED
        val labels = tags.map { LocaleManager.displayName(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language)
            .setItems(labels) { _, which ->
                viewModel.setLanguageTag(tags[which])
                // Re-create so the new locale is applied to the UI immediately.
                recreate()
            }
            .show()
    }

    private fun showTmdbDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_tmdb)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                viewModel.setTmdbKey(input.text.toString().trim())
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun toggleAutoSync() {
        val enabled = !autoSyncEnabled
        viewModel.setAutoSyncEnabled(enabled)
        if (enabled) SyncScheduler.schedule(this, autoSyncHours)
        else SyncScheduler.cancel(this)
    }

    private fun showAutoSyncIntervalDialog() {
        val options = listOf(6, 12, 24, 48)
        val labels = options.map { getString(R.string.settings_every_hours, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_auto_sync_interval)
            .setItems(labels) { _, which ->
                val hours = options[which]
                viewModel.setAutoSyncHours(hours)
                if (autoSyncEnabled) SyncScheduler.schedule(this, hours)
            }
            .show()
    }

    private fun toggleLockAdult() {
        val current = lockAdultValue.text == getString(R.string.settings_on)
        viewModel.setLockAdult(!current)
    }

    private fun showSetPinDialog() {
        PinDialog.showSet(this) { pin -> viewModel.setPin(pin) }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setMessage(R.string.settings_logout_confirm)
            .setPositiveButton(R.string.settings_logout) { _, _ ->
                lifecycleScope.launch {
                    ServiceLocator.settings.clearSource()
                    val intent = Intent(this@SettingsActivity, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    finish()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---- Row helpers ---------------------------------------------------

    private fun addHeader(container: LinearLayout, titleRes: Int) {
        val header = LayoutInflater.from(this)
            .inflate(R.layout.item_settings_header, container, false) as TextView
        header.setText(titleRes)
        container.addView(header)
    }

    private fun addRow(
        container: LinearLayout,
        title: String,
        summary: String? = null,
        onClick: () -> Unit
    ): TextView {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_settings_row, container, false)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        val summaryView = row.findViewById<TextView>(R.id.rowSummary)
        if (summary.isNullOrBlank()) {
            summaryView.visibility = View.GONE
        } else {
            summaryView.visibility = View.VISIBLE
            summaryView.text = summary
        }
        row.setOnClickListener { onClick() }
        container.addView(row)
        return row.findViewById(R.id.rowValue)
    }

    private fun onOff(enabled: Boolean): String =
        getString(if (enabled) R.string.settings_on else R.string.settings_off)

    private fun playerModeLabel(mode: PlayerMode): String = getString(
        when (mode) {
            PlayerMode.AUTO -> R.string.settings_player_auto
            PlayerMode.EXOPLAYER -> R.string.settings_player_exo
            PlayerMode.VLC -> R.string.settings_player_vlc
        }
    )
}
