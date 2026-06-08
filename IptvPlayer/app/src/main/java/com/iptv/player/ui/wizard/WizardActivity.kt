/*
 * WizardActivity.kt
 * First-run setup wizard. Three steps on a single, cinematic split screen:
 *   0) Welcome
 *   1) Language pick (writes settings.setLanguageTag) — a two-column grid of
 *      native language names, the same six the app ships translations for.
 *   2) Connect prompt
 * On finish it marks settings.setWizardDone(true) and opens LoginActivity.
 * Fully D-pad driven; reuses the wizard_* strings and the brand button styles.
 */
package com.iptv.player.ui.wizard

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
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
        // "System default" spans the full row, then each supported language sits
        // in a tidy two-column grid (en | tr, de | fr, nl | ar).
        addLanguageRow("", getString(R.string.wizard_language_system), span = 2)
        LocaleManager.SUPPORTED.forEach { tag ->
            addLanguageRow(tag, LocaleManager.displayName(tag))
        }
    }

    private fun addLanguageRow(tag: String, label: String, span: Int = 1) {
        val chip = TextView(this).apply {
            text = label
            setTextColor(resources.getColor(R.color.text_primary))
            textSize = resources.getDimension(R.dimen.text_body) /
                resources.displayMetrics.scaledDensity
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_card_selectable)
            isFocusable = true
            isFocusableInTouchMode = false
            minHeight = resources.getDimensionPixelSize(R.dimen.wizard_chip_min_height)
            val pad = resources.getDimensionPixelSize(R.dimen.space_m)
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                selectedLanguage = tag
                highlightLanguage()
                // On TV the natural gesture is "focus a language, press OK" — so
                // picking a language confirms it and advances immediately rather
                // than forcing the user to D-pad down to a separate Next button.
                goNext()
            }
        }
        // Width 0 + a column weight lets the cells share the row evenly; a
        // span-2 weight makes the "System default" chip stretch full width.
        val lp = GridLayout.LayoutParams().apply {
            width = 0
            height = GridLayout.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span, span.toFloat())
            rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
            val m = resources.getDimensionPixelSize(R.dimen.space_xs)
            setMargins(m, m, m, m)
        }
        chip.layoutParams = lp
        chip.tag = tag
        binding.languageGroup.addView(chip)
    }

    private fun highlightLanguage() {
        for (i in 0 until binding.languageGroup.childCount) {
            val child = binding.languageGroup.getChildAt(i)
            child.isSelected = (child.tag as? String) == selectedLanguage
        }
    }

    /** Lights the dot for the current step and stretches it into a pill. */
    private fun renderDots() {
        val dots = listOf(binding.dot0, binding.dot1, binding.dot2)
        dots.forEachIndexed { i, dot ->
            dot.isSelected = i == step
            val lp = dot.layoutParams
            lp.width = resources.getDimensionPixelSize(
                if (i == step) R.dimen.wizard_dot_active else R.dimen.wizard_dot_inactive
            )
            dot.layoutParams = lp
        }
    }

    private fun render() {
        renderDots()
        binding.languageScroll.visibility = View.GONE
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
                binding.stepSubtitle.setText(R.string.wizard_language_subtitle)
                binding.languageScroll.visibility = View.VISIBLE
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
