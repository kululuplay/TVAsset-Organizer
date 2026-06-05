/*
 * WizardActivity.kt
 * Lightweight first-run setup wizard. Three steps on a single screen:
 *   0) Welcome
 *   1) Language pick (writes settings.setLanguageTag)
 *   2) Connect prompt
 * On finish it marks settings.setWizardDone(true) and opens LoginActivity.
 * Fully D-pad driven; reuses the wizard_* strings and the house button style.
 */
package com.iptv.player.ui.wizard

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.databinding.ActivityWizardBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.login.LoginActivity
import com.iptv.player.util.LocaleManager
import kotlinx.coroutines.launch

class WizardActivity : BaseActivity() {

    private lateinit var binding: ActivityWizardBinding

    private val totalSteps = 3
    private var step = 0
    private var selectedLanguage: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildLanguageOptions()

        binding.btnBack.setOnClickListener { goBack() }
        binding.btnNext.setOnClickListener { goNext() }

        render()
    }

    private fun buildLanguageOptions() {
        binding.languageGroup.removeAllViews()
        // "System default" option first, then each supported language.
        addLanguageRow("", getString(R.string.settings_player_auto))
        LocaleManager.SUPPORTED.forEach { tag ->
            addLanguageRow(tag, LocaleManager.displayName(tag))
        }
    }

    private fun addLanguageRow(tag: String, label: String) {
        val row = TextView(this).apply {
            text = label
            setTextColor(resources.getColor(R.color.text_primary))
            textSize = resources.getDimension(R.dimen.text_body) /
                resources.displayMetrics.scaledDensity
            setBackgroundResource(R.drawable.bg_card_selectable)
            isFocusable = true
            isFocusableInTouchMode = false
            val pad = resources.getDimensionPixelSize(R.dimen.space_m)
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                selectedLanguage = tag
                highlightLanguage()
            }
        }
        val lp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = resources.getDimensionPixelSize(R.dimen.space_s)
        row.layoutParams = lp
        row.tag = tag
        binding.languageGroup.addView(row)
    }

    private fun highlightLanguage() {
        for (i in 0 until binding.languageGroup.childCount) {
            val child = binding.languageGroup.getChildAt(i)
            child.isSelected = (child.tag as? String) == selectedLanguage
        }
    }

    private fun render() {
        binding.stepIndicator.text =
            getString(R.string.wizard_step_indicator, step + 1, totalSteps)
        binding.languageGroup.visibility = View.GONE
        binding.btnBack.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE

        when (step) {
            0 -> {
                binding.stepTitle.setText(R.string.wizard_welcome_title)
                binding.stepSubtitle.setText(R.string.wizard_welcome_subtitle)
                binding.btnNext.setText(R.string.wizard_next)
                binding.btnNext.requestFocus()
            }
            1 -> {
                binding.stepTitle.setText(R.string.wizard_step_language)
                binding.stepSubtitle.text = ""
                binding.languageGroup.visibility = View.VISIBLE
                binding.btnNext.setText(R.string.wizard_next)
                highlightLanguage()
                binding.languageGroup.getChildAt(0)?.requestFocus()
            }
            2 -> {
                binding.stepTitle.setText(R.string.wizard_connect_title)
                binding.stepSubtitle.setText(R.string.wizard_connect_message)
                binding.btnNext.setText(R.string.wizard_finish)
                binding.btnNext.requestFocus()
            }
        }
    }

    private fun goNext() {
        if (step == 1) {
            // Persist the chosen language as we leave the language step.
            lifecycleScope.launch { ServiceLocator.settings.setLanguageTag(selectedLanguage) }
        }
        if (step < totalSteps - 1) {
            step++
            render()
        } else {
            finishWizard()
        }
    }

    private fun goBack() {
        if (step > 0) {
            step--
            render()
        }
    }

    private fun finishWizard() {
        lifecycleScope.launch {
            ServiceLocator.settings.setWizardDone(true)
            startActivity(Intent(this@WizardActivity, LoginActivity::class.java))
            finish()
        }
    }

    override fun onBackPressed() {
        if (step > 0) goBack() else super.onBackPressed()
    }
}
