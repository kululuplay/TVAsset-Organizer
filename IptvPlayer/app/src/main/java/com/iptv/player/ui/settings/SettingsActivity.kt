/*
 * SettingsActivity.kt
 * A modern, D-pad friendly master-detail settings screen. The left rail lists
 * every entry (navigation rows, inline toggles and actions); focusing a nav row
 * swaps the right-hand detail panel to match it. All values stay bound to
 * SettingsViewModel so the screen reflects persisted state reactively.
 */
package com.iptv.player.ui.settings

import android.content.Intent
import android.os.Build
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.BufferMode
import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.data.model.SourceType
import com.iptv.player.data.model.StreamFormat
import com.iptv.player.data.prefs.SettingsStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.iptv.player.databinding.ActivitySettingsBinding
import com.iptv.player.ui.content.ContentManagerActivity
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.PinPromptDialog
import com.iptv.player.ui.login.LoginActivity
import com.iptv.player.ui.profiles.ProfilesActivity
import com.iptv.player.util.LocaleManager
import com.iptv.player.util.PlaybackLog
import com.iptv.player.util.PublicIpProvider
import com.iptv.player.util.SpeedTester
import com.iptv.player.work.SyncScheduler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by lazy {
        ViewModelProvider(this)[SettingsViewModel::class.java]
    }

    private enum class Panel { GENERAL, LANGUAGE, PIN, CATEGORIES, PLAYER, TIME, TMDB, SPEEDTEST }

    private var selectedRow: View? = null

    // Reactive widgets we update from the ViewModel flows.
    private var lockAdultSwitch: SwitchCompat? = null
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

    private var autoSyncEnabled = false
    private var autoSyncHours = 12
    private var generalLoaded = false

    // Running download speed-test coroutine (cancelled when leaving the panel).
    private var speedTestJob: kotlinx.coroutines.Job? = null

    // PIN entry state.
    private val pinBoxViews = mutableListOf<TextView>()
    private val pinEntry = StringBuilder()
    private var pinStageNew = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildPanels()
        buildRail()
        observe()
    }

    override fun onResume() {
        super.onResume()
        binding.dateText.text = DateFormat
            .getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            .format(Date())
    }

    override fun onPause() {
        super.onPause()
        // Don't keep a download running while the screen is in the background.
        cancelSpeedTest()
        binding.speedProgress.visibility = View.GONE
        binding.speedStart.isEnabled = true
        binding.speedStart.alpha = 1f
        binding.speedStart.setText(R.string.speedtest_start)
    }

    // ---- Left rail -----------------------------------------------------

    private fun buildRail() {
        val c = binding.settingsContainer
        val first = addNavRow(c, getString(R.string.settings_general_info), Panel.GENERAL)
        addNavRow(c, getString(R.string.settings_language), Panel.LANGUAGE)
        addNavRow(c, getString(R.string.settings_parental_pin), Panel.PIN)
        addNavRow(c, getString(R.string.settings_content_manager), Panel.CATEGORIES)
        addNavRow(c, getString(R.string.settings_player_mode), Panel.PLAYER)
        addNavRow(c, getString(R.string.settings_time_sync), Panel.TIME)
        addNavRow(c, getString(R.string.settings_speedtest), Panel.SPEEDTEST)

        lockAdultSwitch = addToggleRow(c, getString(R.string.settings_lock_adult)) { checked ->
            if (checked) {
                // Enabling the lock is a direct action.
                viewModel.setLockAdult(true)
            } else {
                // Disabling the lock requires the PIN. The switch is bound to the
                // persisted lockAdult flow (see observe()), so it stays visually ON
                // until the PIN is confirmed; on cancel/wrong PIN nothing changes.
                lifecycleScope.launch {
                    val pin = ServiceLocator.settings.getPin()
                    PinPromptDialog.show(
                        context = this@SettingsActivity,
                        expectedPin = pin,
                        onSuccess = { viewModel.setLockAdult(false) },
                        onCancel = { lockAdultSwitch?.isChecked = true }
                    )
                }
            }
        }

        addActionRow(c, getString(R.string.settings_profiles)) {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }
        addActionRow(c, getString(R.string.settings_about)) {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        addActionRow(c, getString(R.string.settings_logout)) { confirmLogout() }

        // Open on General Info by default.
        first.post {
            showPanel(Panel.GENERAL, first)
            first.requestFocus()
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
        return row
    }

    private fun addToggleRow(
        container: LinearLayout,
        title: String,
        onToggle: (Boolean) -> Unit
    ): SwitchCompat {
        val row = inflateMaster(container, title)
        val sw = row.findViewById<SwitchCompat>(R.id.mSwitch)
        sw.visibility = View.VISIBLE
        row.setOnClickListener { onToggle(!sw.isChecked) }
        container.addView(row)
        return sw
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
        buildTmdbPanel()
        buildSpeedtestPanel()
    }

    private fun detailView(panel: Panel): View = when (panel) {
        Panel.GENERAL -> binding.detailGeneral
        Panel.LANGUAGE -> binding.detailLanguage
        Panel.PIN -> binding.detailPin
        Panel.CATEGORIES -> binding.detailCategories
        Panel.PLAYER -> binding.detailPlayer
        Panel.TIME -> binding.detailTime
        Panel.TMDB -> binding.detailTmdb
        Panel.SPEEDTEST -> binding.detailSpeedtest
    }

    private fun showPanel(panel: Panel, row: View) {
        // Stop any in-flight speed test when navigating away from its panel.
        if (panel != Panel.SPEEDTEST) cancelSpeedTest()
        Panel.values().forEach { detailView(it).visibility = View.GONE }
        detailView(panel).visibility = View.VISIBLE

        if (selectedRow !== row) {
            selectedRow?.isSelected = false
            row.isSelected = true
            selectedRow = row
        }
        if (panel == Panel.GENERAL) loadGeneral()
        if (panel == Panel.PIN) resetPin()
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
        lifecycleScope.launch {
            ipValue.text = PublicIpProvider.fetchIpv4() ?: "—"
        }

        addSectionHeader(c, getString(R.string.settings_device_info))
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "—"
        addInfoRow(c, getString(R.string.settings_app_version), version)
        addInfoRow(c, getString(R.string.settings_device), "${Build.MANUFACTURER} ${Build.MODEL}")
        addInfoRow(c, getString(R.string.settings_android), "Android ${Build.VERSION.RELEASE}")

        lifecycleScope.launch {
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
                "$expiry · " + getString(R.string.account_days_left, info.daysRemaining.toInt())
            } else expiry
            val active = info.activeConnections ?: 0
            val maxText = info.maxConnections?.toString() ?: getString(R.string.account_unlimited)
            connValue.text = getString(R.string.account_connections_value, active, maxText)
        }
        generalLoaded = true
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
                viewModel.setLanguageTag(tag)
                recreate()
            }
            c.addView(row)
            languageRows[tag] = row
        }
    }

    private fun buildPlayerPanel() {
        val c = binding.playerContainer
        listOf(PlayerMode.AUTO, PlayerMode.EXOPLAYER, PlayerMode.VLC).forEach { mode ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_settings_choice, c, false)
            row.findViewById<TextView>(R.id.cTitle).text = playerModeLabel(mode)
            val icon = row.findViewById<ImageView>(R.id.cIcon)
            icon.setImageResource(R.drawable.ic_check)
            icon.imageTintList = ContextCompat.getColorStateList(this, R.color.settings_row_text)
            icon.visibility = View.INVISIBLE
            row.setOnClickListener { viewModel.setPlayerMode(mode) }
            c.addView(row)
            playerRows[mode] = row
        }

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
        passthroughRow.setOnClickListener {
            viewModel.setAudioPassthrough(!passthroughSwitch!!.isChecked)
        }
        c.addView(passthroughRow)

        // Share the on-device playback diagnostics log (green/no-audio causes).
        val logRow = inflateMaster(c, getString(R.string.settings_share_playback_log))
        logRow.setOnClickListener { sharePlaybackLog() }
        c.addView(logRow)
    }

    private fun buildTimePanel() {
        val c = binding.timeContainer
        val clockRow = inflateMaster(c, getString(R.string.settings_clock))
        clockSwitch = clockRow.findViewById<SwitchCompat>(R.id.mSwitch).also { it.visibility = View.VISIBLE }
        clockRow.setOnClickListener { viewModel.setShowClock(!clockSwitch!!.isChecked) }
        c.addView(clockRow)

        val saverRow = inflateMaster(c, getString(R.string.settings_screensaver))
        screensaverValue = saverRow.findViewById<TextView>(R.id.mValue).also { it.visibility = View.VISIBLE }
        saverRow.setOnClickListener { showScreensaverDialog() }
        c.addView(saverRow)

        val syncRow = inflateMaster(c, getString(R.string.settings_auto_sync))
        autoSyncSwitch = syncRow.findViewById<SwitchCompat>(R.id.mSwitch).also { it.visibility = View.VISIBLE }
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
        // Four display boxes.
        repeat(4) {
            val box = LayoutInflater.from(this)
                .inflate(R.layout.item_pin_box, binding.pinBoxes, false) as TextView
            binding.pinBoxes.addView(box)
            pinBoxViews.add(box)
        }
        // Keypad: 1-5 / 6-9 0 backspace.
        binding.pinKeys.orientation = LinearLayout.VERTICAL
        val row1 = keypadRow(); listOf("1", "2", "3", "4", "5").forEach { addKey(row1, it) }
        val row2 = keypadRow(); listOf("6", "7", "8", "9", "0").forEach { addKey(row2, it) }
        addKey(row2, "⌫", backspace = true)
        binding.pinKeys.addView(row1)
        binding.pinKeys.addView(row2)
        binding.pinHint.text = getString(R.string.settings_default_pin, SettingsStore.DEFAULT_PIN)
    }

    private fun keypadRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = resources.getDimensionPixelSize(R.dimen.space_s) }
    }

    private fun addKey(row: LinearLayout, label: String, backspace: Boolean = false) {
        val key = LayoutInflater.from(this).inflate(R.layout.item_pin_key, row, false) as TextView
        key.text = label
        key.setOnClickListener { if (backspace) onPinBackspace() else onPinDigit(label) }
        row.addView(key)
    }

    private fun resetPin() {
        pinStageNew = false
        pinEntry.setLength(0)
        binding.pinPrompt.setText(R.string.settings_enter_old_pin)
        updatePinBoxes()
    }

    private fun onPinDigit(d: String) {
        if (pinEntry.length >= 4) return
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

    private fun handlePinComplete() {
        val entered = pinEntry.toString()
        if (pinStageNew) {
            viewModel.setPin(entered)
            Toast.makeText(this, R.string.settings_pin_changed, Toast.LENGTH_SHORT).show()
            resetPin()
            return
        }
        lifecycleScope.launch {
            val stored = ServiceLocator.settings.getPin() ?: SettingsStore.DEFAULT_PIN
            if (entered == stored) {
                pinStageNew = true
                pinEntry.setLength(0)
                binding.pinPrompt.setText(R.string.settings_enter_new_pin)
                updatePinBoxes()
            } else {
                Toast.makeText(this@SettingsActivity, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
                pinEntry.setLength(0)
                updatePinBoxes()
            }
        }
    }

    private fun updatePinBoxes() {
        pinBoxViews.forEachIndexed { i, box ->
            box.text = if (i < pinEntry.length) "●" else ""
        }
    }

    private fun buildTmdbPanel() {
        binding.tmdbSave.setOnClickListener {
            viewModel.setTmdbKey(binding.tmdbInput.text.toString().trim())
            Toast.makeText(this, R.string.action_save, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Speed test ----------------------------------------------------

    private fun buildSpeedtestPanel() {
        binding.speedStart.setOnClickListener { runSpeedTest() }
    }

    private fun runSpeedTest() {
        if (speedTestJob?.isActive == true) return
        binding.speedStatus.visibility = View.GONE
        binding.speedProgress.visibility = View.VISIBLE
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

        speedTestJob = lifecycleScope.launch {
            var completed = false
            try {
                val mbps = SpeedTester.measureMbps(speedTestFallbackUrls()) { live ->
                    binding.speedResult.text = formatMbps(live)
                }
                completed = true
                if (mbps < 0) {
                    binding.speedResult.text = getString(R.string.speedtest_idle)
                    binding.speedStatus.visibility = View.VISIBLE
                    binding.speedStatus.setText(R.string.speedtest_failed)
                    binding.speedStatus.setTextColor(
                        ContextCompat.getColor(this@SettingsActivity, R.color.danger)
                    )
                } else {
                    showSpeedResult(mbps)
                }
            } finally {
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
                speedTestJob = null
            }
        }
    }

    /**
     * Fallback speed-test targets on the customer's OWN portal, tried only if the
     * public CDN mirrors are all unreachable (common on locked-down IPTV boxes
     * that whitelist just the portal host). For Xtream we pull the full m3u_plus
     * playlist export — a large, unthrottled, always-reachable download; for M3U
     * sources we re-fetch the playlist URL itself.
     */
    private suspend fun speedTestFallbackUrls(): List<String> {
        val config = ServiceLocator.settings.getSourceConfig() ?: return emptyList()
        return when (config.type) {
            SourceType.XTREAM -> {
                // Build via HttpUrl so credentials with reserved chars (& ? # space)
                // are percent-encoded into a well-formed URL instead of corrupting
                // the query string.
                if (config.username.isBlank()) {
                    emptyList()
                } else {
                    val url = config.serverUrl.trimEnd('/').toHttpUrlOrNull()
                        ?.newBuilder()
                        ?.addPathSegment("get.php")
                        ?.addQueryParameter("username", config.username)
                        ?.addQueryParameter("password", config.password)
                        ?.addQueryParameter("type", "m3u_plus")
                        ?.addQueryParameter("output", "ts")
                        ?.build()
                        ?.toString()
                    if (url == null) emptyList() else listOf(url)
                }
            }
            SourceType.M3U_URL ->
                if (config.m3uUrl.isBlank()) emptyList() else listOf(config.m3uUrl)
        }
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
        binding.speedStatus.text = "${getString(tierRes)} · ${getString(msgRes)}"
        binding.speedStatus.setTextColor(color)
    }

    private fun formatMbps(mbps: Double): String {
        val v = mbps.coerceAtLeast(0.0)
        return if (v < 10) String.format(Locale.getDefault(), "%.1f", v)
        else String.format(Locale.getDefault(), "%.0f", v)
    }

    private fun cancelSpeedTest() {
        speedTestJob?.cancel()
        speedTestJob = null
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
        }
        container.addView(header)
    }

    private fun addInfoRow(container: LinearLayout, label: String, value: String): TextView {
        val row = LayoutInflater.from(this).inflate(R.layout.item_settings_info, container, false)
        row.findViewById<TextView>(R.id.iLabel).text = label
        val valueView = row.findViewById<TextView>(R.id.iValue)
        valueView.text = value
        container.addView(row)
        return valueView
    }

    private fun firstFocusable(view: View): View? {
        if (view.isFocusable && view.visibility == View.VISIBLE) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                firstFocusable(view.getChildAt(i))?.let { return it }
            }
        }
        return null
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
            viewModel.lockAdult.collectLatest { lockAdultSwitch?.isChecked = it }
        }
        lifecycleScope.launch {
            viewModel.showClock.collectLatest { clockSwitch?.isChecked = it }
        }
        lifecycleScope.launch {
            viewModel.screensaverMinutes.collectLatest {
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
                languageRows.forEach { (t, row) -> row.isSelected = (t == tag) }
            }
        }
        lifecycleScope.launch {
            viewModel.playerMode.collectLatest { mode ->
                playerRows.forEach { (m, row) ->
                    row.findViewById<ImageView>(R.id.cIcon).visibility =
                        if (m == mode) View.VISIBLE else View.INVISIBLE
                }
            }
        }
        lifecycleScope.launch {
            viewModel.decoderMode.collectLatest { decoderModeValue?.text = decoderModeLabel(it) }
        }
        lifecycleScope.launch {
            viewModel.streamFormat.collectLatest { streamFormatValue?.text = streamFormatLabel(it) }
        }
        lifecycleScope.launch {
            viewModel.bufferMode.collectLatest { bufferModeValue?.text = bufferModeLabel(it) }
        }
        lifecycleScope.launch {
            viewModel.audioPassthrough.collectLatest { passthroughSwitch?.isChecked = it }
        }
        lifecycleScope.launch {
            viewModel.tmdbKey.collectLatest {
                if (!binding.tmdbInput.isFocused) binding.tmdbInput.setText(it)
            }
        }
    }

    // ---- Dialogs / actions ---------------------------------------------

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

    private fun playerModeLabel(mode: PlayerMode): String = getString(
        when (mode) {
            PlayerMode.AUTO -> R.string.settings_player_auto
            PlayerMode.EXOPLAYER -> R.string.settings_player_exo
            PlayerMode.VLC -> R.string.settings_player_vlc
        }
    )

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
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_decoder_mode)
            .setItems(labels) { _, which -> viewModel.setDecoderMode(modes[which]) }
            .show()
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
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_stream_format)
            .setItems(labels) { _, which -> viewModel.setStreamFormat(formats[which]) }
            .show()
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
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_buffer)
            .setItems(labels) { _, which -> viewModel.setBufferMode(modes[which]) }
            .show()
    }

    private fun sharePlaybackLog() {
        val file = PlaybackLog.file(this)
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
            Toast.makeText(this, R.string.settings_playback_log_empty, Toast.LENGTH_SHORT).show()
        }
    }
}
