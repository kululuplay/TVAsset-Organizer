/*
 * SettingsActivity.kt
 * A modern, D-pad friendly master-detail settings screen. The left rail contains
 * navigation categories only; settings, toggles and actions live in their
 * matching detail panel. All values stay bound to SettingsViewModel so the
 * screen reflects persisted state reactively.
 */
package com.iptv.player.ui.settings

import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.BufferMode
import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.data.model.StreamFormat
import com.iptv.player.data.prefs.SettingsStore
import com.iptv.player.databinding.ActivitySettingsBinding
import com.iptv.player.ui.content.ContentManagerActivity
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.PinPromptDialog
import com.iptv.player.ui.diagnostics.DiagnosticsActivity
import com.iptv.player.ui.login.LoginActivity
import com.iptv.player.ui.profiles.ProfilesActivity
import com.iptv.player.util.LocaleManager
import com.iptv.player.util.Logger
import com.iptv.player.util.PlaybackLog
import com.iptv.player.util.PublicIpProvider
import com.iptv.player.util.SpeedTester
import com.iptv.player.work.SyncScheduler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by lazy {
        ViewModelProvider(this)[SettingsViewModel::class.java]
    }

    private enum class Panel {
        GENERAL,
        LANGUAGE,
        PIN,
        CATEGORIES,
        PLAYER,
        TIME,
        SPEEDTEST,
        SYSTEM,
    }

    private var selectedRow: View? = null
    private var selectedPanel = Panel.GENERAL
    private val panelRows = linkedMapOf<Panel, View>()
    private var restorePanel = Panel.GENERAL
    private var restoreDetailFocus = false

    // Reactive widgets we update from the ViewModel flows.
    private var lockAdultRow: View? = null
    private var lockAdultSwitch: SwitchCompat? = null
    private var lockAdultActionInFlight = false
    private var clockSwitch: SwitchCompat? = null
    private var screensaverValue: TextView? = null
    private var autoSyncSwitch: SwitchCompat? = null
    private var autoSyncIntervalValue: TextView? = null
    private val languageRows = linkedMapOf<String, View>()
    private val playerRows = linkedMapOf<PlayerMode, View>()
    private var decoderModeValue: TextView? = null
    private var streamFormatValue: TextView? = null
    private var bufferModeValue: TextView? = null
    private var passthroughSwitch: SwitchCompat? = null
    private var debugOverlaySwitch: SwitchCompat? = null
    private var selectedPlayerMode = PlayerMode.AUTO
    private var selectedDecoderMode = DecoderMode.AUTO
    private var selectedStreamFormat = StreamFormat.TS
    private var selectedBufferMode = BufferMode.NORMAL
    private var selectedScreensaverMinutes = 10

    private var autoSyncEnabled = false
    private var autoSyncHours = 12

    // Running download speed-test coroutine (cancelled when leaving the panel).
    private var speedTestJob: kotlinx.coroutines.Job? = null
    private var speedTestRunId = 0L

    // General-info network fetches (public IP + account); cancelled and relaunched
    // each time the General panel is rebuilt so a D-pad pass doesn't pile up calls.
    private var generalJob: kotlinx.coroutines.Job? = null

    // PIN entry state.
    private enum class PinStage { CURRENT, NEW, CONFIRM }

    private val pinBoxViews = mutableListOf<TextView>()
    private val pinKeyViews = mutableListOf<View>()
    private val pinEntry = StringBuilder()
    private var pinStage = PinStage.CURRENT
    private var pendingNewPin = ""
    private var usesDefaultPin = false
    private var pinErrorVisible = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        restorePanel = savedInstanceState
            ?.getString(STATE_PANEL)
            ?.let { runCatching { Panel.valueOf(it) }.getOrNull() }
            ?: Panel.GENERAL
        restoreDetailFocus = savedInstanceState?.getBoolean(STATE_DETAIL_FOCUS) == true

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setAccessibilityHeading(binding.detailTitle, true)

        buildPanels()
        buildRail()
        observe()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (
                    selectedPanel == Panel.PIN &&
                    isFocusInside(binding.detailPin) &&
                    pinEntry.isNotEmpty()
                ) {
                    onPinBackspace()
                } else if (isFocusInside(binding.detailContainer)) {
                    selectedRow?.requestFocus()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        outState.putString(STATE_PANEL, selectedPanel.name)
        outState.putBoolean(STATE_DETAIL_FOCUS, isFocusInside(binding.detailContainer))
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        binding.dateText.text = DateFormat
            .getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            .format(Date())
        // A background transition cancels account/IP I/O in onPause. Relaunch it
        // when returning directly to General so placeholder rows cannot remain
        // stuck after Home, the share sheet or another Activity covered Settings.
        if (selectedRow != null && selectedPanel == Panel.GENERAL) loadGeneral()
    }

    override fun onPause() {
        super.onPause()
        // Don't keep a download running while the screen is in the background.
        cancelSpeedTest()
        generalJob?.cancel()
        generalJob = null
    }

    // ---- Left rail -----------------------------------------------------

    private fun buildRail() {
        val c = binding.settingsContainer
        addNavRow(c, getString(R.string.settings_general_info), Panel.GENERAL)
        addNavRow(c, getString(R.string.settings_language), Panel.LANGUAGE)
        addNavRow(c, getString(R.string.settings_parental_pin), Panel.PIN)
        addNavRow(c, getString(R.string.settings_content_manager), Panel.CATEGORIES)
        addNavRow(c, getString(R.string.settings_player_mode), Panel.PLAYER)
        addNavRow(c, getString(R.string.settings_time_sync), Panel.TIME)
        addNavRow(c, getString(R.string.settings_speedtest), Panel.SPEEDTEST)
        addNavRow(c, getString(R.string.settings_system_support), Panel.SYSTEM)

        val initial = panelRows[restorePanel] ?: panelRows.getValue(Panel.GENERAL)
        initial.post {
            showPanel(restorePanel, initial)
            if (restoreDetailFocus) {
                firstFocusable(detailView(restorePanel))?.requestFocus()
            } else {
                initial.requestFocus()
            }
        }
    }

    private fun addNavRow(container: LinearLayout, title: String, panel: Panel): View {
        val row = inflateMaster(container, title)
        row.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) showPanel(panel, v) }
        row.setOnClickListener {
            showPanel(panel, it)
            firstFocusable(detailView(panel))?.requestFocus()
        }
        container.addView(row)
        panelRows[panel] = row
        return row
    }

    private fun addActionRow(container: LinearLayout, title: String, action: () -> Unit): View {
        val row = inflateMaster(container, title)
        row.setOnClickListener { action() }
        container.addView(row)
        return row
    }

    private fun inflateMaster(container: ViewGroup, title: String): View {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_settings_master, container, false)
        row.findViewById<TextView>(R.id.mTitle).text = title
        return row
    }

    // ---- Detail panels -------------------------------------------------

    private fun buildPanels() {
        buildLanguagePanel()
        buildPlayerPanel()
        buildTimePanel()
        buildCategoriesPanel()
        buildPinPanel()
        buildSpeedtestPanel()
        buildSystemPanel()
    }

    private fun detailView(panel: Panel): View = when (panel) {
        Panel.GENERAL -> binding.detailGeneral
        Panel.LANGUAGE -> binding.detailLanguage
        Panel.PIN -> binding.detailPin
        Panel.CATEGORIES -> binding.detailCategories
        Panel.PLAYER -> binding.detailPlayer
        Panel.TIME -> binding.detailTime
        Panel.SPEEDTEST -> binding.detailSpeedtest
        Panel.SYSTEM -> binding.detailSystem
    }

    private fun showPanel(panel: Panel, row: View) {
        // Stop any in-flight speed test when navigating away from its panel.
        if (panel != Panel.SPEEDTEST) cancelSpeedTest()
        val panelChanged = panel != selectedPanel || selectedRow == null
        Panel.entries.forEach { detailView(it).visibility = View.GONE }
        detailView(panel).visibility = View.VISIBLE
        selectedPanel = panel

        binding.detailTitle.setText(panelTitle(panel))
        binding.detailSubtitle.setText(panelSubtitle(panel))
        binding.detailHeaderIcon.setImageResource(panelIcon(panel))
        ViewCompat.setAccessibilityPaneTitle(
            detailView(panel),
            getString(panelTitle(panel)),
        )

        if (selectedRow !== row) {
            selectedRow?.isSelected = false
            row.isSelected = true
            selectedRow = row
        }
        if (panelChanged && panel == Panel.GENERAL) loadGeneral()
        if (panelChanged && panel == Panel.PIN) resetPin()
    }

    private fun panelTitle(panel: Panel): Int = when (panel) {
        Panel.GENERAL -> R.string.settings_general_info
        Panel.LANGUAGE -> R.string.settings_language
        Panel.PIN -> R.string.settings_parental
        Panel.CATEGORIES -> R.string.settings_content_manager
        Panel.PLAYER -> R.string.settings_player_mode
        Panel.TIME -> R.string.settings_time_sync
        Panel.SPEEDTEST -> R.string.settings_speedtest
        Panel.SYSTEM -> R.string.settings_system_support
    }

    private fun panelSubtitle(panel: Panel): Int = when (panel) {
        Panel.GENERAL -> R.string.settings_panel_general_desc
        Panel.LANGUAGE -> R.string.settings_panel_language_desc
        Panel.PIN -> R.string.settings_panel_parental_desc
        Panel.CATEGORIES -> R.string.settings_panel_content_desc
        Panel.PLAYER -> R.string.settings_panel_player_desc
        Panel.TIME -> R.string.settings_panel_time_desc
        Panel.SPEEDTEST -> R.string.settings_panel_speed_desc
        Panel.SYSTEM -> R.string.settings_panel_system_desc
    }

    private fun panelIcon(panel: Panel): Int = when (panel) {
        Panel.GENERAL -> R.drawable.ic_settings
        Panel.LANGUAGE -> R.drawable.ic_globe
        Panel.PIN -> R.drawable.ic_lock
        Panel.CATEGORIES -> R.drawable.ic_sort
        Panel.PLAYER -> R.drawable.ic_play
        Panel.TIME -> R.drawable.ic_clock
        Panel.SPEEDTEST -> R.drawable.ic_signal
        Panel.SYSTEM -> R.drawable.ic_settings
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (selectedPanel == Panel.PIN && isFocusInside(binding.detailPin)) {
                val digit = when (event.keyCode) {
                    KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> "0"
                    KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> "1"
                    KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> "2"
                    KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> "3"
                    KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> "4"
                    KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> "5"
                    KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> "6"
                    KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> "7"
                    KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> "8"
                    KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> "9"
                    else -> null
                }
                if (digit != null) {
                    if (event.repeatCount == 0) onPinDigit(digit)
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                    onPinBackspace()
                    return true
                }
                if (event.keyCode == KeyEvent.KEYCODE_CLEAR) {
                    clearPinEntry()
                    return true
                }
            }

            val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val detailToRailKey =
                if (rtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            val railToDetailKey =
                if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT

            if (
                event.keyCode == detailToRailKey &&
                isFocusInside(binding.detailContainer) &&
                canLeaveEditTextTowardRail(currentFocus, detailToRailKey) &&
                canLeavePinKeypadTowardRail(currentFocus, detailToRailKey)
            ) {
                selectedRow?.requestFocus()
                return true
            }
            if (
                event.keyCode == railToDetailKey &&
                currentFocus != null &&
                panelRows.values.any { it === currentFocus }
            ) {
                firstFocusable(detailView(selectedPanel))?.requestFocus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun canLeaveEditTextTowardRail(focused: View?, keyCode: Int): Boolean {
        if (focused !is EditText) return true
        if (focused.selectionStart != focused.selectionEnd) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> focused.selectionStart <= 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> focused.selectionStart >= focused.text.length
            else -> false
        }
    }

    /**
     * The master/detail screen normally treats Left (Right in RTL) as "return to
     * the rail". A PIN keypad is a real 3-column control, so only leave from the
     * outer key; otherwise horizontal D-pad navigation must stay inside the row.
     */
    private fun canLeavePinKeypadTowardRail(focused: View?, keyCode: Int): Boolean {
        if (selectedPanel != Panel.PIN || focused !in pinKeyViews) return true
        val row = focused?.parent as? ViewGroup ?: return true
        val position = row.indexOfChild(focused)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> position == 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> position == row.childCount - 1
            else -> true
        }
    }

    private fun isFocusInside(container: View): Boolean {
        var node: View? = currentFocus
        while (node != null) {
            if (node === container) return true
            node = node.parent as? View
        }
        return false
    }

    // General Info: account + device.
    private fun loadGeneral() {
        val c = binding.generalContainer
        c.removeAllViews()

        addSectionHeader(c, getString(R.string.settings_account))
        val statusValue = addInfoRow(c, getString(R.string.account_status), "…")
        val expiryValue = addInfoRow(c, getString(R.string.account_expiry), "…")
        val connValue = addInfoRow(c, getString(R.string.account_connections), "…")
        // Public IPv4 (home WAN address, for support) merged into the account
        // section instead of a separate Network Info block at the bottom.
        val ipValue = addInfoRow(c, getString(R.string.settings_public_ip), "…")

        addSectionHeader(c, getString(R.string.settings_device_info))
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "—"
        addInfoRow(c, getString(R.string.settings_app_version), version)
        addInfoRow(c, getString(R.string.settings_device), "${Build.MANUFACTURER} ${Build.MODEL}")
        addInfoRow(c, getString(R.string.settings_android), "Android ${Build.VERSION.RELEASE}")

        // Focusing the General row rebuilds these rows; cancel the previous fetches
        // so repeated D-pad passes don't spam the public-IP lookup and a full Xtream
        // authenticate (provider connection/rate limits), and so a stale launch can't
        // write into the just-removed rows. IP + account run in parallel under one job.
        generalJob?.cancel()
        generalJob = lifecycleScope.launch {
            launch { ipValue.text = PublicIpProvider.fetchIpv4() ?: "—" }
            val config = ServiceLocator.settings.getSourceConfig()
            val info = config?.let { ServiceLocator.repository.getAccountInfo(it) }
            if (info == null) {
                statusValue.text = getString(R.string.account_none)
                expiryValue.text = "—"
                connValue.text = "—"
                return@launch
            }
            statusValue.text = info.status
                ?: getString(if (info.isActive) R.string.account_active else R.string.account_inactive)
            statusValue.setTextColor(
                ContextCompat.getColor(this@SettingsActivity, if (info.isActive) R.color.success else R.color.danger)
            )
            val expiry = info.expiryDateMs?.let {
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
            } ?: "—"
            expiryValue.text = if (info.daysRemaining != null && info.daysRemaining >= 0) {
                getString(
                    R.string.account_expiry_with_days,
                    expiry,
                    getString(R.string.account_days_left, info.daysRemaining.toInt()),
                )
            } else expiry
            val active = info.activeConnections ?: 0
            val maxText = info.maxConnections?.toString() ?: getString(R.string.account_unlimited)
            connValue.text = getString(R.string.account_connections_value, active, maxText)
        }
    }

    private fun buildLanguagePanel() {
        val c = binding.languageContainer
        LocaleManager.SUPPORTED.forEach { tag ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_settings_choice, c, false)
            row.findViewById<TextView>(R.id.cTitle).text = LocaleManager.displayName(tag)
            val icon = row.findViewById<ImageView>(R.id.cIcon)
            icon.setImageResource(flagFor(tag))
            icon.imageTintList = null
            icon.visibility = View.VISIBLE
            row.setOnClickListener {
                // Already the active language: nothing to do (avoids a needless recreate).
                if (ServiceLocator.settings.languageTagBlocking() == tag) {
                    return@setOnClickListener
                }
                // Persist FIRST, then recreate. setLanguageTag updates the synchronous
                // locale mirror that BaseActivity.attachBaseContext reads; recreating
                // before that write lands made the first tap apply the OLD locale and
                // only the second tap "stick". Awaiting the write fixes the 2-tap bug.
                lifecycleScope.launch {
                    ServiceLocator.settings.setLanguageTag(tag)
                    recreate()
                }
            }
            c.addView(row)
            languageRows[tag] = row
        }
    }

    private fun buildPlayerPanel() {
        val c = binding.playerContainer
        addSectionHeader(c, getString(R.string.settings_live_player_header))
        addPanelDescription(c, getString(R.string.settings_player_scope_desc))
        listOf(PlayerMode.AUTO, PlayerMode.EXOPLAYER, PlayerMode.VLC).forEach { mode ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_settings_choice, c, false)
            row.findViewById<TextView>(R.id.cTitle).text = playerModeLabel(mode)
            row.findViewById<TextView>(R.id.cSubtitle).apply {
                text = playerModeDescription(mode)
                visibility = View.VISIBLE
            }
            val icon = row.findViewById<ImageView>(R.id.cIcon)
            icon.setImageResource(R.drawable.ic_check)
            icon.imageTintList = ContextCompat.getColorStateList(this, R.color.settings_row_text)
            icon.visibility = View.INVISIBLE
            row.setOnClickListener { selectPlayerMode(mode) }
            c.addView(row)
            playerRows[mode] = row
        }

        addSectionHeader(c, getString(R.string.settings_playback_tuning_header))

        // Decoder strategy (Auto / Hardware / Software) for the green-screen fix.
        val decoderRow = inflateMaster(c, getString(R.string.settings_decoder_mode))
        decoderModeValue = decoderRow.findViewById<TextView>(R.id.mValue)
            .also { it.visibility = View.VISIBLE }
        decoderRow.setOnClickListener { showDecoderModeDialog() }
        c.addView(decoderRow)

        // Live stream container preference (TS / HLS) for providers serving both.
        val formatRow = inflateMaster(c, getString(R.string.settings_stream_format))
        streamFormatValue = formatRow.findViewById<TextView>(R.id.mValue)
            .also { it.visibility = View.VISIBLE }
        formatRow.setOnClickListener { showStreamFormatDialog() }
        c.addView(formatRow)

        // Buffer size (Low / Normal / High) -> caching on both engines.
        val bufferRow = inflateMaster(c, getString(R.string.settings_buffer))
        bufferModeValue = bufferRow.findViewById<TextView>(R.id.mValue)
            .also { it.visibility = View.VISIBLE }
        bufferRow.setOnClickListener { showBufferModeDialog() }
        c.addView(bufferRow)

        // Audio passthrough toggle (default OFF = decode to PCM for sound).
        val passthroughRow = inflateMaster(c, getString(R.string.settings_audio_passthrough))
        passthroughSwitch = passthroughRow.findViewById<SwitchCompat>(R.id.mSwitch)
            .also { it.visibility = View.VISIBLE }
        configureToggleRow(
            passthroughRow,
            getString(R.string.settings_audio_passthrough),
            passthroughSwitch!!,
        )
        passthroughRow.setOnClickListener {
            val turningOn = !passthroughSwitch!!.isChecked
            if (turningOn) {
                // Passthrough sends the raw Dolby/DTS bitstream over HDMI; TVs and
                // projectors without a decoder play video with NO sound. Warn and
                // require an explicit confirm before enabling.
                AlertDialog.Builder(this, R.style.ThemeOverlay_Iptv_AlertDialog)
                    .setTitle(R.string.settings_audio_passthrough)
                    .setMessage(R.string.settings_audio_passthrough_warning)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        viewModel.setAudioPassthrough(true)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .showTracked()
            } else {
                viewModel.setAudioPassthrough(false)
            }
        }
        c.addView(passthroughRow)

        // On-screen Debug overlay toggle (engine/stage/resolution + live log tail).
        val debugRow = inflateMaster(c, getString(R.string.settings_debug_overlay))
        debugOverlaySwitch = debugRow.findViewById<SwitchCompat>(R.id.mSwitch)
            .also { it.visibility = View.VISIBLE }
        configureToggleRow(
            debugRow,
            getString(R.string.settings_debug_overlay),
            debugOverlaySwitch!!,
        )
        debugRow.setOnClickListener {
            viewModel.setDebugOverlay(!debugOverlaySwitch!!.isChecked)
        }
        c.addView(debugRow)

        // Share the on-device playback diagnostics log (green/no-audio causes).
        val logRow = inflateMaster(c, getString(R.string.settings_share_playback_log))
        logRow.setOnClickListener { sharePlaybackLog() }
        c.addView(logRow)
    }

    private fun buildTimePanel() {
        val c = binding.timeContainer
        val clockRow = inflateMaster(c, getString(R.string.settings_clock))
        clockSwitch = clockRow.findViewById<SwitchCompat>(R.id.mSwitch).also { it.visibility = View.VISIBLE }
        configureToggleRow(clockRow, getString(R.string.settings_clock), clockSwitch!!)
        clockRow.setOnClickListener { viewModel.setShowClock(!clockSwitch!!.isChecked) }
        c.addView(clockRow)

        val saverRow = inflateMaster(c, getString(R.string.settings_screensaver))
        screensaverValue = saverRow.findViewById<TextView>(R.id.mValue).also { it.visibility = View.VISIBLE }
        saverRow.setOnClickListener { showScreensaverDialog() }
        c.addView(saverRow)

        val syncRow = inflateMaster(c, getString(R.string.settings_auto_sync))
        autoSyncSwitch = syncRow.findViewById<SwitchCompat>(R.id.mSwitch).also { it.visibility = View.VISIBLE }
        configureToggleRow(syncRow, getString(R.string.settings_auto_sync), autoSyncSwitch!!)
        syncRow.setOnClickListener { toggleAutoSync() }
        c.addView(syncRow)

        val intervalRow = inflateMaster(c, getString(R.string.settings_auto_sync_interval))
        autoSyncIntervalValue = intervalRow.findViewById<TextView>(R.id.mValue).also { it.visibility = View.VISIBLE }
        intervalRow.setOnClickListener { showAutoSyncIntervalDialog() }
        c.addView(intervalRow)
    }

    private fun buildCategoriesPanel() {
        val c = binding.categoriesContainer
        val row = LayoutInflater.from(this).inflate(R.layout.item_settings_choice, c, false)
        row.findViewById<TextView>(R.id.cTitle).text = getString(R.string.content_manager_title)
        val icon = row.findViewById<ImageView>(R.id.cIcon)
        icon.setImageResource(R.drawable.ic_arrow_right)
        icon.imageTintList = ContextCompat.getColorStateList(this, R.color.settings_row_text)
        icon.visibility = View.VISIBLE
        row.setOnClickListener { startActivity(Intent(this, ContentManagerActivity::class.java)) }
        c.addView(row)
    }

    private fun buildPinPanel() {
        val lockRow = inflateMaster(
            binding.pinSettingsContainer,
            getString(R.string.settings_lock_adult),
        )
        lockAdultRow = lockRow
        lockAdultSwitch = lockRow.findViewById<SwitchCompat>(R.id.mSwitch).also {
            it.visibility = View.VISIBLE
        }
        configureToggleRow(
            lockRow,
            getString(R.string.settings_lock_adult),
            lockAdultSwitch!!,
        )
        lockRow.setOnClickListener {
            if (lockAdultActionInFlight) return@setOnClickListener
            lockAdultActionInFlight = true
            lifecycleScope.launch {
                val currentlyEnabled = ServiceLocator.settings.lockAdult.first()
                if (!currentlyEnabled) {
                    try {
                        ServiceLocator.settings.setLockAdult(true)
                    } finally {
                        lockAdultActionInFlight = false
                    }
                } else {
                    val pin = ServiceLocator.settings.getPin() ?: SettingsStore.DEFAULT_PIN
                    PinPromptDialog.show(
                        context = this@SettingsActivity,
                        expectedPin = pin,
                        onSuccess = {
                            lifecycleScope.launch {
                                try {
                                    ServiceLocator.settings.setLockAdult(false)
                                } finally {
                                    lockAdultActionInFlight = false
                                }
                            }
                        },
                        onCancel = {
                            lockAdultSwitch?.isChecked = true
                            lockAdultActionInFlight = false
                        },
                    )
                }
            }
        }
        binding.pinSettingsContainer.addView(lockRow)
        addActionRow(
            binding.pinSettingsContainer,
            getString(R.string.settings_profiles),
        ) {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }

        pinBoxViews.clear()
        pinKeyViews.clear()
        // Four display boxes.
        repeat(4) {
            val box = LayoutInflater.from(this)
                .inflate(R.layout.item_pin_box, binding.pinBoxes, false) as TextView
            binding.pinBoxes.addView(box)
            pinBoxViews.add(box)
        }
        // Familiar TV keypad: 1–9 / clear, 0, backspace. Keep it LTR so Arabic
        // layouts don't reverse digits or the physical D-pad muscle memory.
        binding.pinKeys.orientation = LinearLayout.VERTICAL
        binding.pinKeys.layoutDirection = View.LAYOUT_DIRECTION_LTR
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
        ).forEach { labels ->
            keypadRow().also { row ->
                labels.forEach { addKey(row, it) }
                binding.pinKeys.addView(row)
            }
        }
        keypadRow().also { row ->
            addKey(row, "C", clear = true)
            addKey(row, "0")
            addKey(row, "⌫", backspace = true)
            binding.pinKeys.addView(row)
        }
        wirePinKeyFocus()
        binding.pinHint.setText(R.string.settings_pin_remote_hint)
    }

    private fun keypadRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = resources.getDimensionPixelSize(R.dimen.space_s) }
    }

    private fun addKey(
        row: LinearLayout,
        label: String,
        backspace: Boolean = false,
        clear: Boolean = false,
    ) {
        val key = LayoutInflater.from(this).inflate(R.layout.item_pin_key, row, false) as TextView
        key.text = label
        key.id = View.generateViewId()
        key.contentDescription = when {
            clear -> getString(R.string.pin_clear)
            backspace -> getString(R.string.pin_backspace)
            else -> label
        }
        key.setOnClickListener {
            when {
                clear -> clearPinEntry()
                backspace -> onPinBackspace()
                else -> onPinDigit(label)
            }
        }
        row.addView(key)
        pinKeyViews += key
    }

    /** Stable 3×4 navigation even when text scaling changes card geometry. */
    private fun wirePinKeyFocus() {
        val rows = pinKeyViews.chunked(3)
        rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { columnIndex, key ->
                key.nextFocusLeftId =
                    row.getOrNull(columnIndex - 1)?.id ?: key.id
                key.nextFocusRightId =
                    row.getOrNull(columnIndex + 1)?.id ?: key.id
                rows.getOrNull(rowIndex - 1)?.getOrNull(columnIndex)?.let {
                    key.nextFocusUpId = it.id
                }
                rows.getOrNull(rowIndex + 1)?.getOrNull(columnIndex)?.let {
                    key.nextFocusDownId = it.id
                } ?: run { key.nextFocusDownId = key.id }
            }
        }
    }

    private fun resetPin() {
        pinStage = PinStage.CURRENT
        pendingNewPin = ""
        pinEntry.setLength(0)
        binding.pinPrompt.setText(R.string.settings_enter_old_pin)
        usesDefaultPin = false
        pinErrorVisible = false
        renderPinHint()
        updatePinBoxes()
        lifecycleScope.launch {
            usesDefaultPin =
                ServiceLocator.settings.getPin() == SettingsStore.DEFAULT_PIN
            if (!pinErrorVisible) renderPinHint()
        }
    }

    private fun onPinDigit(d: String) {
        if (pinEntry.length >= 4) return
        if (pinErrorVisible) {
            pinErrorVisible = false
            renderPinHint()
        }
        pinEntry.append(d)
        updatePinBoxes()
        if (pinEntry.length == 4) handlePinComplete()
    }

    private fun onPinBackspace() {
        if (pinEntry.isNotEmpty()) {
            pinEntry.deleteCharAt(pinEntry.length - 1)
            updatePinBoxes()
        }
    }

    private fun clearPinEntry() {
        pinEntry.setLength(0)
        updatePinBoxes()
    }

    private fun handlePinComplete() {
        val entered = pinEntry.toString()
        when (pinStage) {
            PinStage.CURRENT -> lifecycleScope.launch {
                val stored = ServiceLocator.settings.getPin() ?: SettingsStore.DEFAULT_PIN
                if (entered == stored) {
                    pinStage = PinStage.NEW
                    pinEntry.setLength(0)
                    binding.pinPrompt.setText(R.string.settings_enter_new_pin)
                    pinErrorVisible = false
                    renderPinHint()
                    updatePinBoxes()
                    binding.pinPrompt.announceForAccessibility(binding.pinPrompt.text)
                } else {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.pin_wrong,
                        Toast.LENGTH_SHORT,
                    ).show()
                    clearPinEntry()
                    showPinError(R.string.pin_wrong)
                }
            }

            PinStage.NEW -> {
                pendingNewPin = entered
                pinStage = PinStage.CONFIRM
                pinEntry.setLength(0)
                binding.pinPrompt.setText(R.string.settings_confirm_new_pin)
                pinErrorVisible = false
                renderPinHint()
                updatePinBoxes()
                binding.pinPrompt.announceForAccessibility(binding.pinPrompt.text)
            }

            PinStage.CONFIRM -> {
                if (entered != pendingNewPin) {
                    Toast.makeText(this, R.string.pin_mismatch, Toast.LENGTH_SHORT).show()
                    pinStage = PinStage.NEW
                    pendingNewPin = ""
                    clearPinEntry()
                    binding.pinPrompt.setText(R.string.settings_enter_new_pin)
                    showPinError(R.string.pin_mismatch)
                    return
                }
                lifecycleScope.launch {
                    ServiceLocator.settings.setPin(entered)
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_pin_changed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    resetPin()
                }
            }
        }
    }

    private fun updatePinBoxes() {
        pinBoxViews.forEachIndexed { i, box ->
            box.text = if (i < pinEntry.length) "●" else ""
            box.isActivated = i < pinEntry.length
        }
        binding.pinBoxes.contentDescription = getString(
            R.string.pin_progress,
            pinEntry.length,
        )
    }

    private fun renderPinHint() {
        binding.pinHint.setTextColor(
            ContextCompat.getColor(this, R.color.text_secondary),
        )
        binding.pinHint.text = if (usesDefaultPin && pinStage == PinStage.CURRENT) {
            buildString {
                append(
                    getString(
                        R.string.settings_default_pin,
                        SettingsStore.DEFAULT_PIN,
                    ),
                )
                append('\n')
                append(getString(R.string.settings_pin_remote_hint))
            }
        } else {
            getString(R.string.settings_pin_remote_hint)
        }
    }

    private fun showPinError(messageRes: Int) {
        pinErrorVisible = true
        binding.pinHint.setTextColor(ContextCompat.getColor(this, R.color.danger))
        binding.pinHint.setText(messageRes)
        binding.pinHint.announceForAccessibility(getString(messageRes))
    }

    private fun buildSystemPanel() {
        val c = binding.systemContainer
        addSectionHeader(c, getString(R.string.settings_support_tools))
        addPanelDescription(c, getString(R.string.settings_support_tools_desc))
        addSupportRow(
            c,
            R.string.settings_diagnostics,
            R.string.settings_diagnostics_desc,
            R.drawable.ic_signal,
        ) {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        addSupportRow(
            c,
            R.string.settings_about,
            R.string.settings_about_desc,
            R.drawable.ic_update,
        ) {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        addSectionHeader(c, getString(R.string.settings_account_actions))
        addSupportRow(
            c,
            R.string.settings_logout,
            R.string.settings_logout_desc,
            R.drawable.ic_power,
            danger = true,
        ) {
            confirmLogout()
        }
    }

    private fun addSupportRow(
        container: LinearLayout,
        titleRes: Int,
        subtitleRes: Int,
        iconRes: Int,
        danger: Boolean = false,
        action: () -> Unit,
    ): View {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_settings_choice, container, false)
        val title = getString(titleRes)
        val subtitle = getString(subtitleRes)
        row.findViewById<TextView>(R.id.cTitle).text = title
        row.findViewById<TextView>(R.id.cSubtitle).apply {
            text = subtitle
            visibility = View.VISIBLE
        }
        row.findViewById<ImageView>(R.id.cIcon).apply {
            setImageResource(iconRes)
            imageTintList = ContextCompat.getColorStateList(
                this@SettingsActivity,
                if (danger) R.color.danger else R.color.settings_row_text,
            )
            visibility = View.VISIBLE
        }
        row.contentDescription = "$title. $subtitle"
        row.setOnClickListener { action() }
        container.addView(row)
        return row
    }

    // ---- Speed test ----------------------------------------------------

    private fun buildSpeedtestPanel() {
        binding.speedStart.setOnClickListener { runSpeedTest() }
    }

    private fun runSpeedTest() {
        // A cancelled run keeps this slot until its OkHttp calls have unwound and
        // SpeedTester has released the global single-run guard. This prevents an
        // immediate retry from being misreported as a network failure (BUSY).
        if (speedTestJob != null) return
        val runId = ++speedTestRunId
        binding.speedStatus.visibility = View.VISIBLE
        binding.speedStatus.setText(R.string.speedtest_running)
        binding.speedStatus.setTextColor(
            ContextCompat.getColor(this, R.color.text_secondary),
        )
        binding.speedMeta.setText(R.string.speedtest_method)
        binding.speedMeta.visibility = View.VISIBLE
        binding.speedProgress.visibility = View.VISIBLE
        binding.speedProgress.isIndeterminate = false
        binding.speedProgress.max = 100
        binding.speedProgress.progress = 0
        binding.speedResult.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        binding.speedResult.text = formatMbps(0.0)
        // Keep the button ENABLED so it retains D-pad focus while testing.
        // Disabling the focused button makes Android move focus to the first
        // left-rail row (General Info); that row's focus listener then switches
        // panels and cancelSpeedTest()s -- i.e. the user gets "thrown" to General
        // Info and the test never runs. The speedTestJob guard above already
        // blocks a double run, so a dim + "Testing…" label is cue enough.
        binding.speedStart.alpha = 0.5f
        binding.speedStart.setText(R.string.speedtest_running)

        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            var completed = false
            try {
                val result = SpeedTester.measure { progress ->
                    if (runId != speedTestRunId) return@measure
                    if (progress.mbps > 0.0) {
                        binding.speedResult.text = formatMbps(progress.mbps)
                    }
                    binding.speedProgress.progress =
                        ((progress.elapsedMs * 100L) / SPEED_TEST_EXPECTED_MS)
                            .toInt()
                            .coerceIn(1, 95)
                    val phaseText = when (progress.phase) {
                        SpeedTester.Phase.CONNECTING -> getString(
                            R.string.speedtest_phase_connecting,
                            progress.activeStreams,
                            progress.attemptedStreams,
                        )
                        SpeedTester.Phase.WARMING_UP -> getString(
                            R.string.speedtest_phase_warming,
                            progress.activeStreams,
                            progress.attemptedStreams,
                        )
                        SpeedTester.Phase.MEASURING -> getString(
                            R.string.speedtest_phase_measuring,
                            progress.activeStreams,
                            progress.attemptedStreams,
                        )
                    }
                    binding.speedStatus.text = phaseText
                }
                if (runId != speedTestRunId) return@launch
                completed = true
                binding.speedProgress.progress = 100
                when (result) {
                    is SpeedTester.Result.Success -> {
                        showSpeedResult(result.mbps)
                        binding.speedMeta.text = getString(
                            R.string.speedtest_result_meta,
                            result.activeStreamHighWaterMark,
                            (result.measuredBytes / 1_000_000L).toInt(),
                            result.measurementDurationMs / 1_000.0,
                        )
                    }
                    is SpeedTester.Result.Failure -> {
                        logSpeedTestFailure(result)
                        val failureMessage = when (result.reason) {
                            SpeedTester.FailureReason.INSUFFICIENT_ACTIVE_STREAMS ->
                                R.string.speedtest_failed_service
                            SpeedTester.FailureReason.INSUFFICIENT_SAMPLES,
                            SpeedTester.FailureReason.INSUFFICIENT_DATA ->
                                R.string.speedtest_failed_unstable
                            SpeedTester.FailureReason.BUSY ->
                                R.string.speedtest_failed_busy
                            SpeedTester.FailureReason.TIMEOUT ->
                                R.string.speedtest_failed_timeout
                            SpeedTester.FailureReason.INTERNAL_ERROR ->
                                R.string.speedtest_failed
                        }
                        binding.speedResult.text = getString(R.string.speedtest_idle)
                        binding.speedStatus.visibility = View.VISIBLE
                        binding.speedStatus.setText(failureMessage)
                        binding.speedStatus.setTextColor(
                            ContextCompat.getColor(this@SettingsActivity, R.color.danger)
                        )
                        binding.speedMeta.setText(R.string.speedtest_method)
                        binding.speedStatus.announceForAccessibility(
                            getString(failureMessage),
                        )
                    }
                }
            } finally {
                if (runId == speedTestRunId) {
                    // Runs on normal completion AND on cancellation (panel switch /
                    // onPause), so the controls never get stuck in the running state.
                    binding.speedProgress.visibility = View.GONE
                    binding.speedStart.isEnabled = true
                    binding.speedStart.alpha = 1f
                    if (completed) {
                        binding.speedStart.setText(R.string.speedtest_retry)
                    } else {
                        // Cancelled before a result: reset to the initial state.
                        binding.speedStart.setText(R.string.speedtest_start)
                        binding.speedResult.text = getString(R.string.speedtest_idle)
                        binding.speedStatus.visibility = View.GONE
                    }
                }
            }
        }
        // Assign before execution so even an immediate BUSY/failure completion
        // cannot leave an already-completed Job stuck in the field.
        speedTestJob = job
        job.invokeOnCompletion {
            binding.root.post {
                if (speedTestJob === job) speedTestJob = null
            }
        }
        job.start()
    }

    private fun logSpeedTestFailure(result: SpeedTester.Result.Failure) {
        val endpoints = result.endpointStats.joinToString(separator = ";") { stats ->
            "${stats.cdn}(${stats.host})=" +
                "attempted:${stats.attemptedStreams}," +
                "started:${stats.startedStreams}," +
                "active:${stats.activeStreams}," +
                "failed:${stats.failedStreams}," +
                "bytes:${stats.transferredBytes}," +
                "errors:${stats.failureCodes}"
        }
        Logger.w(
            "SpeedTest",
            "failed reason=${result.reason} diagnostic=${result.diagnostic} " +
                "elapsedMs=${result.elapsedMs} samples=${result.validSampleCount} " +
                "highWater=${result.activeStreamHighWaterMark} endpoints=[$endpoints]",
        )
    }

    private fun showSpeedResult(mbps: Double) {
        binding.speedResult.text = formatMbps(mbps)
        val (tierRes, msgRes, colorRes) = when {
            mbps < 20 -> Triple(
                R.string.speedtest_tier_poor, R.string.speedtest_msg_poor, R.color.danger
            )
            mbps <= 50 -> Triple(
                R.string.speedtest_tier_normal, R.string.speedtest_msg_normal, R.color.warning
            )
            mbps < 100 -> Triple(
                R.string.speedtest_tier_good, R.string.speedtest_msg_good, R.color.success
            )
            else -> Triple(
                R.string.speedtest_tier_excellent, R.string.speedtest_msg_excellent, R.color.accent_emerald
            )
        }
        val color = ContextCompat.getColor(this, colorRes)
        binding.speedResult.setTextColor(color)
        binding.speedStatus.visibility = View.VISIBLE
        binding.speedStatus.text = getString(
            R.string.speedtest_result_summary,
            getString(tierRes),
            getString(msgRes),
        )
        binding.speedStatus.setTextColor(color)
        binding.speedStatus.announceForAccessibility(binding.speedStatus.text)
    }

    private fun formatMbps(mbps: Double): String {
        val v = mbps.coerceAtLeast(0.0)
        return if (v < 10) String.format(Locale.getDefault(), "%.1f", v)
        else String.format(Locale.getDefault(), "%.0f", v)
    }

    private fun cancelSpeedTest() {
        val job = speedTestJob ?: return
        speedTestRunId++
        job.cancel()
        binding.speedProgress.visibility = View.GONE
        binding.speedStart.isEnabled = true
        binding.speedStart.alpha = 1f
        binding.speedStart.setText(R.string.speedtest_start)
        binding.speedResult.setText(R.string.speedtest_idle)
        binding.speedResult.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        binding.speedStatus.visibility = View.GONE
        binding.speedMeta.setText(R.string.speedtest_method)
    }

    // ---- Detail helpers ------------------------------------------------

    private fun addSectionHeader(container: LinearLayout, title: String) {
        val header = TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val top = if (container.childCount == 0) 0
            else resources.getDimensionPixelSize(R.dimen.space_l)
            setPadding(0, top, 0, resources.getDimensionPixelSize(R.dimen.space_s))
            ViewCompat.setAccessibilityHeading(this, true)
        }
        container.addView(header)
    }

    private fun addPanelDescription(container: LinearLayout, description: String) {
        container.addView(TextView(this).apply {
            text = description
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
            textSize = 16f
            setLineSpacing(0f, 1.12f)
            setPadding(
                0,
                0,
                0,
                resources.getDimensionPixelSize(R.dimen.space_m),
            )
        })
    }

    private fun addInfoRow(container: LinearLayout, label: String, value: String): TextView {
        val row = LayoutInflater.from(this).inflate(R.layout.item_settings_info, container, false)
        row.findViewById<TextView>(R.id.iLabel).text = label
        val valueView = row.findViewById<TextView>(R.id.iValue)
        valueView.text = value
        container.addView(row)
        return valueView
    }

    private fun configureToggleRow(
        row: View,
        title: String,
        switch: SwitchCompat,
    ) {
        row.contentDescription = title
        ViewCompat.setAccessibilityDelegate(
            row,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = SwitchCompat::class.java.name
                    info.isCheckable = true
                    info.isChecked = switch.isChecked
                }
            },
        )
    }

    private fun firstFocusable(view: View): View? {
        if (view.visibility != View.VISIBLE || !view.isEnabled) return null
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                firstFocusable(view.getChildAt(i))?.let { return it }
            }
        }
        return view.takeIf { it.isFocusable }
    }

    private fun flagFor(tag: String): Int = when (tag) {
        "tr" -> R.drawable.flag_tr
        "de" -> R.drawable.flag_de
        "fr" -> R.drawable.flag_fr
        "nl" -> R.drawable.flag_nl
        "ar" -> R.drawable.flag_ar
        else -> R.drawable.flag_en
    }

    // ---- Reactive binding ----------------------------------------------

    private fun observe() {
        lifecycleScope.launch {
            viewModel.lockAdult.collectLatest { enabled ->
                lockAdultSwitch?.isChecked = enabled
                lockAdultRow?.contentDescription = getString(
                    R.string.settings_toggle_state,
                    getString(R.string.settings_lock_adult),
                    getString(if (enabled) R.string.settings_on else R.string.settings_off),
                )
            }
        }
        lifecycleScope.launch {
            viewModel.showClock.collectLatest { clockSwitch?.isChecked = it }
        }
        lifecycleScope.launch {
            viewModel.screensaverMinutes.collectLatest {
                selectedScreensaverMinutes = it
                screensaverValue?.text = if (it <= 0) getString(R.string.settings_screensaver_off)
                else getString(R.string.settings_minutes, it)
            }
        }
        lifecycleScope.launch {
            viewModel.autoSyncEnabled.collectLatest {
                autoSyncEnabled = it
                autoSyncSwitch?.isChecked = it
            }
        }
        lifecycleScope.launch {
            viewModel.autoSyncHours.collectLatest {
                autoSyncHours = it
                autoSyncIntervalValue?.text = getString(R.string.settings_every_hours, it)
            }
        }
        lifecycleScope.launch {
            viewModel.languageTag.collectLatest { tag ->
                languageRows.forEach { (t, row) ->
                    row.isSelected = t == tag
                    row.contentDescription = if (t == tag) {
                        getString(
                            R.string.settings_selected_option,
                            LocaleManager.displayName(t),
                        )
                    } else {
                        LocaleManager.displayName(t)
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.playerMode.collectLatest { mode ->
                selectedPlayerMode = mode
                playerRows.forEach { (m, row) ->
                    row.isSelected = m == mode
                    row.findViewById<ImageView>(R.id.cIcon).visibility =
                        if (m == mode) View.VISIBLE else View.INVISIBLE
                    row.contentDescription = if (m == mode) {
                        getString(R.string.settings_selected_option, playerModeLabel(m))
                    } else {
                        playerModeLabel(m)
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.decoderMode.collectLatest {
                selectedDecoderMode = it
                decoderModeValue?.text = decoderModeLabel(it)
            }
        }
        lifecycleScope.launch {
            viewModel.streamFormat.collectLatest {
                selectedStreamFormat = it
                streamFormatValue?.text = streamFormatLabel(it)
            }
        }
        lifecycleScope.launch {
            viewModel.bufferMode.collectLatest {
                selectedBufferMode = it
                bufferModeValue?.text = bufferModeLabel(it)
            }
        }
        lifecycleScope.launch {
            viewModel.audioPassthrough.collectLatest { passthroughSwitch?.isChecked = it }
        }
        lifecycleScope.launch {
            viewModel.debugOverlay.collectLatest { debugOverlaySwitch?.isChecked = it }
        }
    }

    // ---- Dialogs / actions ---------------------------------------------

    private fun AlertDialog.Builder.showTracked(): AlertDialog =
        show().also { trackIdleInteractions(it) }

    private fun showScreensaverDialog() {
        val options = listOf(0, 5, 10, 15, 30)
        val labels = options.map {
            if (it == 0) getString(R.string.settings_screensaver_off)
            else getString(R.string.settings_minutes, it)
        }.toTypedArray()
        AlertDialog.Builder(this, R.style.ThemeOverlay_Iptv_AlertDialog)
            .setTitle(R.string.settings_screensaver)
            .setSingleChoiceItems(
                labels,
                options.indexOf(selectedScreensaverMinutes).coerceAtLeast(0),
            ) { dialog, which ->
                viewModel.setScreensaverMinutes(options[which])
                dialog.dismiss()
            }
            .showTracked()
    }

    private fun toggleAutoSync() {
        lifecycleScope.launch {
            // Read the persisted value at click time. This avoids the short
            // startup race where the first Flow emission has not reached the UI
            // yet and the local default could toggle in the wrong direction.
            val enabled = !ServiceLocator.settings.isAutoSyncEnabled()
            val hours = ServiceLocator.settings.getAutoSyncHours()
            ServiceLocator.settings.setAutoSyncEnabled(enabled)
            if (enabled) SyncScheduler.schedule(this@SettingsActivity, hours)
            else SyncScheduler.cancel(this@SettingsActivity)
        }
    }

    private fun showAutoSyncIntervalDialog() {
        val options = listOf(6, 12, 24, 48)
        val labels = options.map { getString(R.string.settings_every_hours, it) }.toTypedArray()
        AlertDialog.Builder(this, R.style.ThemeOverlay_Iptv_AlertDialog)
            .setTitle(R.string.settings_auto_sync_interval)
            .setSingleChoiceItems(
                labels,
                options.indexOf(autoSyncHours).coerceAtLeast(0),
            ) { dialog, which ->
                val hours = options[which]
                viewModel.setAutoSyncHours(hours)
                if (autoSyncEnabled) SyncScheduler.schedule(this, hours)
                dialog.dismiss()
            }
            .showTracked()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this, R.style.ThemeOverlay_Iptv_AlertDialog)
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
            .showTracked()
    }

    private fun playerModeLabel(mode: PlayerMode): String = getString(
        when (mode) {
            PlayerMode.AUTO -> R.string.settings_player_auto
            PlayerMode.EXOPLAYER -> R.string.settings_player_exo
            PlayerMode.VLC -> R.string.settings_player_vlc
        }
    )

    private fun playerModeDescription(mode: PlayerMode): String = getString(
        when (mode) {
            PlayerMode.AUTO -> R.string.settings_player_auto_desc
            PlayerMode.EXOPLAYER -> R.string.settings_player_exo_desc
            PlayerMode.VLC -> R.string.settings_player_vlc_desc
        }
    )

    private fun selectPlayerMode(mode: PlayerMode) {
        if (mode == PlayerMode.EXOPLAYER && selectedDecoderMode == DecoderMode.SOFTWARE) {
            AlertDialog.Builder(this, R.style.ThemeOverlay_Iptv_AlertDialog)
                .setTitle(R.string.settings_player_exo)
                .setMessage(R.string.settings_exo_requires_hardware)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    viewModel.setPlaybackSelection(PlayerMode.EXOPLAYER, DecoderMode.HARDWARE)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showTracked()
            return
        }
        viewModel.setPlayerMode(mode)
    }

    private fun decoderModeLabel(mode: DecoderMode): String = getString(
        when (mode) {
            DecoderMode.AUTO -> R.string.settings_decoder_auto
            DecoderMode.HARDWARE -> R.string.settings_decoder_hardware
            DecoderMode.SOFTWARE -> R.string.settings_decoder_software
        }
    )

    private fun showDecoderModeDialog() {
        val modes = listOf(DecoderMode.AUTO, DecoderMode.HARDWARE, DecoderMode.SOFTWARE)
        val labels = modes.map { decoderModeLabel(it) }.toTypedArray()
        AlertDialog.Builder(this, R.style.ThemeOverlay_Iptv_AlertDialog)
            .setTitle(R.string.settings_decoder_mode)
            .setSingleChoiceItems(
                labels,
                modes.indexOf(selectedDecoderMode).coerceAtLeast(0),
            ) { dialog, which ->
                dialog.dismiss()
                val selected = modes[which]
                if (
                    selected == DecoderMode.SOFTWARE &&
                    selectedPlayerMode == PlayerMode.EXOPLAYER
                ) {
                    AlertDialog.Builder(this, R.style.ThemeOverlay_Iptv_AlertDialog)
                        .setTitle(R.string.settings_decoder_software)
                        .setMessage(R.string.settings_software_requires_vlc)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            viewModel.setPlaybackSelection(PlayerMode.VLC, DecoderMode.SOFTWARE)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .showTracked()
                } else {
                    viewModel.setDecoderMode(selected)
                }
            }
            .showTracked()
    }

    private fun streamFormatLabel(format: StreamFormat): String = getString(
        when (format) {
            StreamFormat.TS -> R.string.settings_stream_format_ts
            StreamFormat.HLS -> R.string.settings_stream_format_hls
        }
    )

    private fun showStreamFormatDialog() {
        val formats = listOf(StreamFormat.TS, StreamFormat.HLS)
        val labels = formats.map { streamFormatLabel(it) }.toTypedArray()
        AlertDialog.Builder(this, R.style.ThemeOverlay_Iptv_AlertDialog)
            .setTitle(R.string.settings_stream_format)
            .setSingleChoiceItems(
                labels,
                formats.indexOf(selectedStreamFormat).coerceAtLeast(0),
            ) { dialog, which ->
                viewModel.setStreamFormat(formats[which])
                dialog.dismiss()
            }
            .showTracked()
    }

    private fun bufferModeLabel(mode: BufferMode): String = getString(
        when (mode) {
            BufferMode.LOW -> R.string.settings_buffer_low
            BufferMode.NORMAL -> R.string.settings_buffer_normal
            BufferMode.HIGH -> R.string.settings_buffer_high
        }
    )

    private fun showBufferModeDialog() {
        val modes = listOf(BufferMode.LOW, BufferMode.NORMAL, BufferMode.HIGH)
        val labels = modes.map { bufferModeLabel(it) }.toTypedArray()
        AlertDialog.Builder(this, R.style.ThemeOverlay_Iptv_AlertDialog)
            .setTitle(R.string.settings_buffer)
            .setSingleChoiceItems(
                labels,
                modes.indexOf(selectedBufferMode).coerceAtLeast(0),
            ) { dialog, which ->
                viewModel.setBufferMode(modes[which])
                dialog.dismiss()
            }
            .showTracked()
    }

    private fun sharePlaybackLog() {
        val file = PlaybackLog.shareableFile(this)
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.settings_playback_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(send, getString(R.string.settings_share_playback_log)))
        }.onFailure {
            Toast.makeText(this, R.string.settings_share_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val STATE_PANEL = "settings.panel"
        const val STATE_DETAIL_FOCUS = "settings.detail_focus"
        const val SPEED_TEST_EXPECTED_MS = 15_500L
    }
}
