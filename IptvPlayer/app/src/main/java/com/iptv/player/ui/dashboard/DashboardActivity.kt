/*
 * DashboardActivity.kt
 * The post-login launcher home. A modern layout: a brand bar with a network-signal
 * chip, live clock/date and a real-weather pill; a centered "what to watch?" prompt;
 * two rows of gradient destination cards (Live / Movies / Series / Settings /
 * Favorites, then Continue / Playlists / Search / Account) covering every section;
 * and a "Newly added" poster rail. Fully D-pad driven; each card opens its section.
 */
package com.iptv.player.ui.dashboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.databinding.ActivityDashboardBinding
import com.iptv.player.databinding.ItemDashboardCardBinding
import com.iptv.player.ui.account.AccountActivity
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.favorites.FavoritesActivity
import com.iptv.player.ui.home.HomeActivity
import com.iptv.player.ui.profiles.ProfilesActivity
import com.iptv.player.ui.search.SearchActivity
import com.iptv.player.ui.series.SeriesActivity
import com.iptv.player.ui.series.SeriesDetailActivity
import com.iptv.player.ui.settings.SettingsActivity
import com.iptv.player.ui.vod.VodActivity
import com.iptv.player.ui.vod.VodDetailActivity
import com.iptv.player.update.ExpiryWarningPrompt
import com.iptv.player.update.UpdatePrompt
import com.iptv.player.util.NetworkSignal
import com.iptv.player.util.WeatherProvider
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class DashboardActivity : BaseActivity() {

    private lateinit var binding: ActivityDashboardBinding

    private val newAddedAdapter by lazy { NewAddedAdapter(::openDetail) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wireCards()
        wireActions()
        setupRail()

        // Land on the Live hero so the remote has an obvious starting point.
        binding.tileLive.root.requestFocus()

        // Quietly check for a newer build once per session and offer to update.
        // If no update prompt shows, fall back to the subscription-expiry reminder
        // so the two launch dialogs never overlap.
        UpdatePrompt.maybeShow(this) { ExpiryWarningPrompt.maybeShow(this) }
    }

    override fun onResume() {
        super.onResume()
        binding.dateText.text =
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
        updateSignal()
        loadWeather()
        loadFooter()
    }

    private fun wireCards() {
        configCard(binding.tileLive, R.drawable.ic_tv, R.drawable.bg_badge_live,
            R.string.nav_live, R.string.dash_sub_live) { open(HomeActivity::class.java) }
        configCard(binding.tileMovies, R.drawable.ic_movie, R.drawable.bg_badge_movies,
            R.string.nav_movies, R.string.dash_sub_movies) { open(VodActivity::class.java) }
        configCard(binding.tileSeries, R.drawable.ic_series, R.drawable.bg_badge_series,
            R.string.nav_series, R.string.dash_sub_series) { open(SeriesActivity::class.java) }
        configCard(binding.tileSettings, R.drawable.ic_settings, R.drawable.bg_badge_settings,
            R.string.nav_settings, R.string.dash_sub_settings) { open(SettingsActivity::class.java) }
        // Unified favorites across channels, movies and series (not just Live TV).
        configCard(binding.tileFavorites, R.drawable.ic_star, R.drawable.bg_badge_favorites,
            R.string.nav_favorites, R.string.dash_sub_favorites) { open(FavoritesActivity::class.java) }

        configCard(binding.tileContinue, R.drawable.ic_clock, R.drawable.bg_badge_catchup,
            R.string.section_continue_watching, R.string.dash_sub_continue) {
            open(ContinueWatchingActivity::class.java)
        }
        configCard(binding.tilePlaylists, R.drawable.ic_playlist, R.drawable.bg_badge_playlists,
            R.string.nav_playlists, R.string.dash_sub_playlists) { open(ProfilesActivity::class.java) }
        configCard(binding.tileSearch, R.drawable.ic_search, R.drawable.bg_badge_search,
            R.string.action_search, R.string.dash_sub_search) { open(SearchActivity::class.java) }
        configCard(binding.tileAccount, R.drawable.ic_account, R.drawable.bg_badge_account,
            R.string.nav_account, R.string.dash_sub_account) { open(AccountActivity::class.java) }
    }

    /** Fills a reusable dashboard card with its icon, badge, label and action. */
    private fun configCard(
        card: ItemDashboardCardBinding,
        iconRes: Int,
        badgeRes: Int,
        titleRes: Int,
        subtitleRes: Int,
        onClick: () -> Unit
    ) {
        card.cardIcon.setImageResource(iconRes)
        card.cardIcon.setBackgroundResource(badgeRes)
        card.cardIcon.contentDescription = getString(titleRes)
        card.cardTitle.setText(titleRes)
        card.cardSubtitle.setText(subtitleRes)
        card.root.setOnClickListener { onClick() }
    }

    private fun wireActions() {
        binding.btnExit.setOnClickListener { finishAffinity() }
        binding.btnRefresh.setOnClickListener { refresh() }
    }

    private fun setupRail() {
        binding.newlyAddedRail.layoutManager =
            LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.newlyAddedRail.adapter = newAddedAdapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    ServiceLocator.repository.observeRecentVod(12),
                    ServiceLocator.repository.observeRecentSeries(12)
                ) { vod, series ->
                    val merged = ArrayList<NewAddedItem>(vod.size + series.size)
                    val max = maxOf(vod.size, series.size)
                    for (i in 0 until max) {
                        vod.getOrNull(i)?.let {
                            merged.add(NewAddedItem(it.id, it.name, it.posterUrl, isSeries = false))
                        }
                        series.getOrNull(i)?.let {
                            merged.add(NewAddedItem(it.id, it.name, it.posterUrl, isSeries = true))
                        }
                    }
                    merged.take(20)
                }.collect { items ->
                    newAddedAdapter.submitList(items)
                    binding.newlyAddedSection.visibility =
                        if (items.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun openDetail(item: NewAddedItem) {
        val intent = if (item.isSeries) {
            Intent(this, SeriesDetailActivity::class.java)
                .putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.id)
        } else {
            Intent(this, VodDetailActivity::class.java)
                .putExtra(VodDetailActivity.EXTRA_VOD_ID, item.id)
        }
        startActivity(intent)
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

    private fun open(target: Class<*>) {
        startActivity(Intent(this, target))
    }

    private fun refresh() {
        lifecycleScope.launch {
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
        }
    }

    /** Shows the active source host and (when available) the account expiry. */
    private fun loadFooter() {
        lifecycleScope.launch {
            val config = ServiceLocator.settings.getSourceConfig()
            if (config == null) {
                binding.footerSource.text = getString(R.string.dash_no_source)
                binding.footerExpiry.text = ""
                return@launch
            }
            val raw = config.serverUrl.ifBlank { config.m3uUrl }
            val label = runCatching { Uri.parse(raw).host }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: raw
            binding.footerSource.text = getString(R.string.dash_playlist, label)

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
