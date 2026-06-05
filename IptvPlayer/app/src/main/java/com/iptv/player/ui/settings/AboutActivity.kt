/*
 * AboutActivity.kt
 * Shows app name + version. The "check for updates" button is a deliberate
 * placeholder: it does NOT touch the network yet. The extension point below is
 * where a future remote-config / forced-update check should be wired in.
 */
package com.iptv.player.ui.settings

import android.os.Bundle
import android.view.View
import com.iptv.player.BuildConfig
import com.iptv.player.R
import com.iptv.player.databinding.ActivityAboutBinding
import com.iptv.player.ui.common.BaseActivity

class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.aboutVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }
    }

    /**
     * Placeholder update check. For now it only reports "up to date" without any
     * network access.
     *
     * EXTENSION POINT — future forced update:
     *   1. Fetch a remote config (version code, min supported version, apk url).
     *   2. If remoteVersionCode > BuildConfig.VERSION_CODE -> show
     *      [R.string.about_update_available] + an "Update now" action.
     *   3. If minSupportedVersion > BuildConfig.VERSION_CODE -> show
     *      [R.string.about_update_required] and BLOCK the UI until updated.
     * Keep the network call off the main thread (lifecycleScope + Dispatchers.IO).
     */
    private fun checkForUpdate() {
        // No network call in this milestone — see the extension point above.
        binding.aboutUpdateStatus.visibility = View.VISIBLE
        binding.aboutUpdateStatus.setText(R.string.about_up_to_date)
    }
}
