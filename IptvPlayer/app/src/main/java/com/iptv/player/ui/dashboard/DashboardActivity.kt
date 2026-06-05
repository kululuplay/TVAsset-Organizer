/*
 * DashboardActivity.kt
 * The post-login launcher home. A grid of gradient tiles (Live / Movies / Series
 * + Playlists / Catch Up / Favorites) over the cinematic aurora background, with a
 * top bar that shows the clock + date and circular action buttons (search, refresh,
 * settings, account, exit). Fully D-pad driven; each tile opens its section.
 */
package com.iptv.player.ui.dashboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.databinding.ActivityDashboardBinding
import com.iptv.player.ui.account.AccountActivity
import com.iptv.player.ui.catchup.CatchupActivity
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.home.HomeActivity
import com.iptv.player.ui.home.HomeViewModel
import com.iptv.player.ui.profiles.ProfilesActivity
import com.iptv.player.ui.search.SearchActivity
import com.iptv.player.ui.series.SeriesActivity
import com.iptv.player.ui.settings.SettingsActivity
import com.iptv.player.ui.vod.VodActivity
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class DashboardActivity : BaseActivity() {

    private lateinit var binding: ActivityDashboardBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wireTiles()
        wireActions()

        // Land on the Live hero so the remote has an obvious starting point.
        binding.tileLive.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        binding.dateText.text =
            DateFormat.getDateInstance(DateFormat.LONG).format(Date())
        loadFooter()
    }

    private fun wireTiles() {
        binding.tileLive.setOnClickListener { open(HomeActivity::class.java) }
        binding.tileMovies.setOnClickListener { open(VodActivity::class.java) }
        binding.tileSeries.setOnClickListener { open(SeriesActivity::class.java) }
        binding.tilePlaylists.setOnClickListener { open(ProfilesActivity::class.java) }
        binding.tileCatchup.setOnClickListener { open(CatchupActivity::class.java) }
        // Open the Live browser straight on its pinned Favorites category.
        binding.tileFavorites.setOnClickListener {
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .putExtra(HomeActivity.EXTRA_INITIAL_CATEGORY, HomeViewModel.CAT_FAVORITES)
            )
        }
    }

    private fun wireActions() {
        binding.btnSearch.setOnClickListener { open(SearchActivity::class.java) }
        binding.btnSettings.setOnClickListener { open(SettingsActivity::class.java) }
        binding.btnAccount.setOnClickListener { open(AccountActivity::class.java) }
        binding.btnExit.setOnClickListener { finishAffinity() }
        binding.btnRefresh.setOnClickListener { refresh() }
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
