/*
 * DashboardActivity.kt
 * The post-login launcher home. A modern layout: a brand bar with a network-signal
 * chip, live clock/date and a real-weather pill; a centered "what to watch?" prompt;
 * and two rows of gradient destination cards (Live / Movies / Series / Settings /
 * Favorites, then Continue / Search) covering every section. Fully D-pad driven;
 * each card opens its section.
 */
package com.iptv.player.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.databinding.ActivityDashboardBinding
import com.iptv.player.databinding.ItemDashboardCardBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.common.RequestDialog
import com.iptv.player.ui.favorites.FavoritesActivity
import com.iptv.player.ui.home.HomeActivity
import com.iptv.player.ui.search.SearchActivity
import com.iptv.player.ui.series.SeriesActivity
import com.iptv.player.ui.settings.SettingsActivity
import com.iptv.player.ui.vod.VodActivity
import com.iptv.player.update.ExpiryWarningPrompt
import com.iptv.player.update.UpdatePrompt
import com.iptv.player.ui.splash.SplashPrefetch
import com.iptv.player.util.ConnectivityWatcher
import com.iptv.player.util.LaunchCrashGuard
import com.iptv.player.util.Logger
import com.iptv.player.util.NetworkSignal
import com.iptv.player.util.WeatherProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class DashboardActivity : BaseActivity() {

    private lateinit var binding: ActivityDashboardBinding

    // Outage resilience: live connectivity signal + the auto-resync loop that
    // keeps retrying a failed portal sync with growing backoff (see statusBanner).
    private var connectivity: ConnectivityWatcher? = null
    private var autoResyncJob: Job? = null
    private var manualRefreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i("Dashboard", "onCreate begin")
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wireCards()
        wireActions()
        wireFocusGraph()

        // Land on the Live hero so the remote has an obvious starting point.
        binding.tileLive.root.post {
            if (currentFocus == null) binding.tileLive.root.requestFocus()
        }

        // Subscription state takes priority: an expired account must always see
        // its notice at launch, even when an update is available. If no expiry
        // dialog is due, fall back to the update prompt so the two never overlap.
        ExpiryWarningPrompt.maybeShow(this) { UpdatePrompt.maybeShow(this) }

        // Clear the launch-crash guard once the home has drawn its first frame
        // (this post runs after the first traversal and the initial onResume),
        // proving the login -> splash -> home path completed without crashing.
        binding.root.post {
            LaunchCrashGuard.markLaunchSucceeded(this)
            Logger.i("Dashboard", "launch confirmed stable")
        }
        Logger.i("Dashboard", "onCreate end")
    }

    override fun onResume() {
        super.onResume()
        binding.dateText.text =
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
        updateSignal()
        loadWeather()
        loadFooter()
    }

    override fun onStart() {
        super.onStart()
        // Watch connectivity while visible: banner + auto-resync on reconnect.
        connectivity = ConnectivityWatcher(this) { online ->
            onConnectivityChanged(online)
        }.also { it.start() }
        evaluateOutageState()
    }

    override fun onStop() {
        super.onStop()
        connectivity?.stop()
        connectivity = null
        // Never keep hammering the portal from the background.
        autoResyncJob?.cancel()
        autoResyncJob = null
    }

    // ---- Outage banner + auto-resync -------------------------------------

    /** Initial state on entry: offline banner, pending launch failure, or clean. */
    private fun evaluateOutageState() {
        when {
            NetworkSignal.current(this) == NetworkSignal.Level.OFFLINE ->
                showBanner(R.string.dash_offline_banner)
            SplashPrefetch.essentialFailed.value -> {
                showBanner(R.string.dash_portal_unreachable)
                scheduleAutoResync()
            }
            else -> hideBanner()
        }
    }

    private fun onConnectivityChanged(online: Boolean) {
        updateSignal()
        if (!online) {
            // Offline: stop retrying (it can't succeed) and say we're on cache.
            autoResyncJob?.cancel()
            autoResyncJob = null
            showBanner(R.string.dash_offline_banner)
            return
        }
        if (SplashPrefetch.essentialFailed.value) {
            // Connection is back but the launch sync had failed — refresh now.
            showBanner(R.string.dash_reconnected_refreshing)
            scheduleAutoResync()
        } else {
            hideBanner()
        }
    }

    /**
     * Retries the full portal sync until it succeeds, with growing delays
     * (15s → 30s → 60s → 120s cap) so a down portal isn't hammered. The first
     * attempt runs immediately. Cancelled on stop/offline; on success the
     * launch-failure flag is cleared and the banner disappears.
     */
    private fun scheduleAutoResync() {
        if (autoResyncJob?.isActive == true) return
        autoResyncJob = lifecycleScope.launch {
            val delays = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L)
            var attempt = 0
            while (isActive) {
                val config = ServiceLocator.settings.getSourceConfig() ?: break
                val ok = runCatching { ServiceLocator.repository.syncAll(config) }
                    .getOrDefault(false)
                if (ok) {
                    Logger.i("Dashboard", "Auto-resync succeeded (attempt ${attempt + 1})")
                    SplashPrefetch.markRecovered()
                    hideBanner()
                    loadFooter()
                    break
                }
                showBanner(R.string.dash_portal_unreachable)
                delay(delays[minOf(attempt, delays.size - 1)])
                attempt++
            }
        }
    }

    private fun showBanner(textRes: Int) {
        binding.statusBanner.setText(textRes)
        binding.statusBanner.visibility = View.VISIBLE
    }

    private fun hideBanner() {
        binding.statusBanner.visibility = View.GONE
    }

    private fun wireCards() {
        configCard(binding.tileLive, R.drawable.ic_tv, R.drawable.bg_badge_live,
            R.string.nav_live, R.string.dash_sub_live, R.drawable.bg_tile_hero) {
            openScreen(HomeActivity::class.java)
        }
        configCard(binding.tileMovies, R.drawable.ic_movie, R.drawable.bg_badge_movies,
            R.string.nav_movies, R.string.dash_sub_movies) { openScreen(VodActivity::class.java) }
        configCard(binding.tileSeries, R.drawable.ic_series, R.drawable.bg_badge_series,
            R.string.nav_series, R.string.dash_sub_series) { openScreen(SeriesActivity::class.java) }
        // Radio stations live in their own folder; Live TV excludes them.
        configCard(binding.tileRadio, R.drawable.ic_radio, R.drawable.bg_badge_radio,
            R.string.nav_radio, R.string.dash_sub_radio) {
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .putExtra(HomeActivity.EXTRA_RADIO_MODE, true)
            )
        }
        configCard(binding.tileSettings, R.drawable.ic_settings, R.drawable.bg_badge_settings,
            R.string.nav_settings, R.string.dash_sub_settings) { openScreen(SettingsActivity::class.java) }
        // Unified favorites across channels, movies and series (not just Live TV).
        configCard(binding.tileFavorites, R.drawable.ic_star, R.drawable.bg_badge_favorites,
            R.string.nav_favorites, R.string.dash_sub_favorites) { openScreen(FavoritesActivity::class.java) }

        configCard(binding.tileContinue, R.drawable.ic_clock, R.drawable.bg_badge_catchup,
            R.string.section_continue_watching, R.string.dash_sub_continue) {
            openScreen(ContinueWatchingActivity::class.java)
        }
        configCard(binding.tileSearch, R.drawable.ic_search, R.drawable.bg_badge_search,
            R.string.action_search, R.string.dash_sub_search) { openScreen(SearchActivity::class.java) }
        // İstek & Şikayet: opens a modal that posts to the crash-receiver ops panel.
        configCard(binding.tileRequest, R.drawable.ic_feedback, R.drawable.bg_badge_request,
            R.string.dash_request, R.string.dash_sub_request) { RequestDialog.show(this) }
    }

    /**
     * Fills a reusable dashboard card with its icon, badge, label and action.
     * [bgRes], when set, overrides the default glass surface (used to give the
     * Live TV hero its brand-glow background). Keep [onClick] last so the
     * trailing-lambda call sites stay valid.
     */
    private fun configCard(
        card: ItemDashboardCardBinding,
        iconRes: Int,
        badgeRes: Int,
        titleRes: Int,
        subtitleRes: Int,
        bgRes: Int? = null,
        onClick: () -> Unit
    ) {
        card.cardIcon.setImageResource(iconRes)
        card.cardIcon.setBackgroundResource(badgeRes)
        card.cardIcon.contentDescription = getString(titleRes)
        card.cardTitle.setText(titleRes)
        card.cardSubtitle.setText(subtitleRes)
        bgRes?.let { card.root.setBackgroundResource(it) }
        card.root.setOnClickListener { onClick() }
    }

    private fun wireActions() {
        binding.btnRefresh.setOnClickListener { refresh() }
    }

    /**
     * Explicit D-pad graph for the asymmetric hero grid. Geometry-based focus
     * search is unpredictable here because Live is twice as wide as the other
     * cards; fixed links make every remote press deterministic on every TV.
     */
    private fun wireFocusGraph() {
        binding.tileLive.root.apply {
            nextFocusLeftId = R.id.tileLive
            nextFocusRightId = R.id.tileMovies
            nextFocusUpId = R.id.tileLive
            nextFocusDownId = R.id.tileFavorites
        }
        binding.tileMovies.root.apply {
            nextFocusLeftId = R.id.tileLive
            nextFocusRightId = R.id.tileSeries
            nextFocusUpId = R.id.tileMovies
            nextFocusDownId = R.id.tileSearch
        }
        binding.tileSeries.root.apply {
            nextFocusLeftId = R.id.tileMovies
            nextFocusRightId = R.id.tileRadio
            nextFocusUpId = R.id.tileSeries
            nextFocusDownId = R.id.tileSettings
        }
        binding.tileRadio.root.apply {
            nextFocusLeftId = R.id.tileSeries
            nextFocusRightId = R.id.tileRadio
            nextFocusUpId = R.id.btnRefresh
            nextFocusDownId = R.id.tileRequest
        }

        binding.tileFavorites.root.apply {
            nextFocusLeftId = R.id.tileFavorites
            nextFocusRightId = R.id.tileContinue
            nextFocusUpId = R.id.tileLive
            nextFocusDownId = R.id.tileFavorites
        }
        binding.tileContinue.root.apply {
            nextFocusLeftId = R.id.tileFavorites
            nextFocusRightId = R.id.tileSearch
            nextFocusUpId = R.id.tileLive
            nextFocusDownId = R.id.tileContinue
        }
        binding.tileSearch.root.apply {
            nextFocusLeftId = R.id.tileContinue
            nextFocusRightId = R.id.tileSettings
            nextFocusUpId = R.id.tileMovies
            nextFocusDownId = R.id.tileSearch
        }
        binding.tileSettings.root.apply {
            nextFocusLeftId = R.id.tileSearch
            nextFocusRightId = R.id.tileRequest
            nextFocusUpId = R.id.tileSeries
            nextFocusDownId = R.id.tileSettings
        }
        binding.tileRequest.root.apply {
            nextFocusLeftId = R.id.tileSettings
            nextFocusRightId = R.id.tileRequest
            nextFocusUpId = R.id.tileRadio
            nextFocusDownId = R.id.tileRequest
        }
        binding.btnRefresh.apply {
            nextFocusLeftId = R.id.tileRadio
            nextFocusRightId = R.id.btnRefresh
            nextFocusUpId = R.id.btnRefresh
            nextFocusDownId = R.id.tileRadio
        }
    }

    private fun updateSignal() {
        val (textRes, colorRes) = when (NetworkSignal.current(this)) {
            NetworkSignal.Level.GOOD -> R.string.signal_good to R.color.success
            NetworkSignal.Level.WEAK -> R.string.signal_weak to R.color.warning
            NetworkSignal.Level.OFFLINE -> R.string.signal_offline to R.color.danger
        }
        binding.signalText.text = getString(textRes)
        val color = ContextCompat.getColor(this, colorRes)
        binding.signalText.setTextColor(color)
        binding.signalIcon.setColorFilter(color)
    }

    private fun loadWeather() {
        lifecycleScope.launch {
            val weather = WeatherProvider.fetch()
            if (weather == null) {
                binding.weatherIcon.setImageResource(R.drawable.ic_weather_clouds)
                binding.weatherIcon.setColorFilter(
                    ContextCompat.getColor(this@DashboardActivity, R.color.text_muted)
                )
                binding.weatherText.setText(R.string.weather_unavailable)
                binding.weatherText.setTextColor(
                    ContextCompat.getColor(this@DashboardActivity, R.color.text_secondary)
                )
            } else {
                binding.weatherIcon.setImageResource(weather.iconRes)
                binding.weatherIcon.setColorFilter(
                    ContextCompat.getColor(this@DashboardActivity, R.color.text_primary)
                )
                binding.weatherText.text = getString(
                    R.string.weather_temp_format, weather.tempC
                ) + " · " + getString(weather.conditionRes)
                binding.weatherText.setTextColor(
                    ContextCompat.getColor(this@DashboardActivity, R.color.text_primary)
                )
            }
        }
    }

    private fun openScreen(target: Class<*>) {
        startActivity(Intent(this, target))
    }

    private fun refresh() {
        if (manualRefreshJob?.isActive == true) return
        manualRefreshJob = lifecycleScope.launch {
            setRefreshBusy(true)
            try {
                val config = ServiceLocator.settings.getSourceConfig()
                if (config == null) {
                    toast(getString(R.string.dash_no_source))
                    return@launch
                }
                toast(getString(R.string.dash_refreshing))
                val ok = runCatching { ServiceLocator.repository.syncAll(config) }
                    .getOrDefault(false)
                toast(getString(if (ok) R.string.dash_refreshed else R.string.dash_refresh_failed))
                loadFooter()
            } finally {
                setRefreshBusy(false)
            }
        }
    }

    private fun setRefreshBusy(busy: Boolean) {
        // Keep focus on the icon instead of disabling it (disabled focused views
        // make Android TV jump to an arbitrary card); clickability blocks repeats.
        binding.btnRefresh.isClickable = !busy
        binding.btnRefresh.alpha = if (busy) 0.5f else 1f
    }

    /** Shows the account username, expiry, and the app version. */
    private fun loadFooter() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "—"
        binding.footerVersion.text = getString(R.string.dash_version, version)

        lifecycleScope.launch {
            val config = ServiceLocator.settings.getSourceConfig()
            if (config == null) {
                binding.footerUser.visibility = View.VISIBLE
                binding.footerUser.text = getString(R.string.dash_no_source)
                binding.footerExpiry.text = ""
                return@launch
            }
            val user = config.username
            if (user.isBlank()) {
                binding.footerUser.visibility = View.GONE
            } else {
                binding.footerUser.visibility = View.VISIBLE
                binding.footerUser.text = getString(R.string.dash_user, user)
            }

            val info = runCatching { ServiceLocator.repository.getAccountInfo(config) }
                .getOrNull()
            binding.footerExpiry.text = info?.expiryDateMs?.let {
                getString(R.string.dash_expires, DateFormat.getDateInstance().format(Date(it)))
            } ?: ""
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
