/*
 * AccountActivity.kt
 * Shows the Xtream account status: active/inactive, expiry date, days remaining
 * and connection usage. Loads via repository.getAccountInfo(getSourceConfig()).
 */
package com.iptv.player.ui.account

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.AccountInfo
import com.iptv.player.databinding.ActivityAccountBinding
import com.iptv.player.ui.common.BaseActivity
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class AccountActivity : BaseActivity() {

    private lateinit var binding: ActivityAccountBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)
        load()
    }

    private fun load() {
        binding.accountProgress.visibility = View.VISIBLE
        binding.accountContent.visibility = View.GONE
        binding.accountMessage.visibility = View.GONE

        lifecycleScope.launch {
            val config = ServiceLocator.settings.getSourceConfig()
            val info = config?.let { ServiceLocator.repository.getAccountInfo(it) }
            binding.accountProgress.visibility = View.GONE
            if (info == null) {
                binding.accountMessage.visibility = View.VISIBLE
                binding.accountMessage.setText(R.string.account_none)
            } else {
                bind(info)
            }
        }
    }

    private fun bind(info: AccountInfo) {
        binding.accountContent.visibility = View.VISIBLE

        // Status (textual when present, otherwise active/inactive).
        binding.accountStatus.text = info.status
            ?: getString(if (info.isActive) R.string.account_active else R.string.account_inactive)
        binding.accountStatus.setTextColor(
            ContextCompat.getColor(this, if (info.isActive) R.color.success else R.color.danger)
        )

        // Expiry date.
        binding.accountExpiry.text = info.expiryDateMs?.let {
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
        } ?: "—"

        // Days remaining.
        if (info.daysRemaining != null && info.daysRemaining >= 0) {
            binding.accountDaysLeft.visibility = View.VISIBLE
            binding.accountDaysLeft.text =
                getString(R.string.account_days_left, info.daysRemaining.toInt())
        } else {
            binding.accountDaysLeft.visibility = View.GONE
        }

        // Connections (active / max, or "Unlimited" when max is unknown).
        val active = info.activeConnections ?: 0
        val maxText = info.maxConnections?.toString()
            ?: getString(R.string.account_unlimited)
        binding.accountConnections.text =
            getString(R.string.account_connections_value, active, maxText)
    }
}
