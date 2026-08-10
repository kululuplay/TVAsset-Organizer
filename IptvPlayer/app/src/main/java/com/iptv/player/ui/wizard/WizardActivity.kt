/*
 * WizardActivity.kt
 * Premium three-step, TV-first onboarding. The UI intentionally uses only
 * lightweight XML surfaces and short animations so low-power sticks remain
 * responsive. Dynamic language choices receive an explicit D-pad focus graph.
 */
package com.iptv.player.ui.wizard

import android.animation.AnimatorInflater
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckedTextView
import android.widget.GridLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.databinding.ActivityWizardBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.ui.login.LoginActivity
import com.iptv.player.util.LocaleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class WizardActivity : BaseActivity() {

    private lateinit var binding: ActivityWizardBinding

    private val languageOptions = mutableListOf<CheckedTextView>()
    private val totalSteps = 3
    private var step = 0
    private var selectedLanguage = ""
    private var savingLanguage = false
    private var finishingWizard = false
    private var renderedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        step = savedInstanceState?.getInt(STATE_STEP, 0)
            ?.coerceIn(0, totalSteps - 1) ?: 0
        selectedLanguage = savedInstanceState?.getString(STATE_LANGUAGE)
            ?: ServiceLocator.settings.languageTagBlocking()

        buildLanguageOptions()
        binding.btnBack.setOnClickListener { goBack() }
        binding.btnNext.setOnClickListener { goNext() }

        render(animate = false)
        animateEntrance()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_STEP, step)
        outState.putString(STATE_LANGUAGE, selectedLanguage)
        super.onSaveInstanceState(outState)
    }

    private fun buildLanguageOptions() {
        binding.languageGroup.removeAllViews()
        languageOptions.clear()
        addLanguageOption("", getString(R.string.wizard_language_system), span = 2)
        LocaleManager.SUPPORTED.forEach { tag ->
            addLanguageOption(tag, LocaleManager.displayName(tag))
        }
        updateLanguageSelection()
        linkLanguageFocusGraph()
    }

    private fun addLanguageOption(tag: String, label: String, span: Int = 1) {
        val option = CheckedTextView(this).apply {
            id = View.generateViewId()
            text = label
            setTextColor(ContextCompat.getColor(this@WizardActivity, R.color.text_primary))
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.text_caption),
            )
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_wizard_language_option)
            setCheckMarkDrawable(null)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = false
            minHeight = resources.getDimensionPixelSize(R.dimen.wizard_chip_min_height)
            stateListAnimator = AnimatorInflater.loadStateListAnimator(
                this@WizardActivity,
                R.animator.control_focus,
            )
            val horizontal = resources.getDimensionPixelSize(R.dimen.space_m)
            val vertical = resources.getDimensionPixelSize(R.dimen.space_s)
            setPaddingRelative(horizontal, vertical, horizontal, vertical)
            setOnClickListener {
                if (savingLanguage || finishingWizard) return@setOnClickListener
                selectedLanguage = tag
                updateLanguageSelection()
                hideWizardError()
            }
        }
        option.layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = GridLayout.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span, span.toFloat())
            rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
            val margin = resources.getDimensionPixelSize(R.dimen.space_xs)
            setMargins(margin, margin, margin, margin)
        }
        option.tag = tag
        languageOptions += option
        binding.languageGroup.addView(option)
    }

    private fun updateLanguageSelection() {
        languageOptions.forEach { option ->
            val selected = (option.tag as? String) == selectedLanguage
            option.isChecked = selected
            option.isSelected = selected
            option.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0,
                0,
                if (selected) R.drawable.ic_check else 0,
                0,
            )
            option.compoundDrawablePadding = resources.getDimensionPixelSize(R.dimen.space_s)
            option.contentDescription = if (selected) {
                "${getString(R.string.wizard_language_selected)}: ${option.text}"
            } else {
                option.text
            }
        }
    }

    /** Explicit navigation prevents OEM focus-search differences and handles RTL. */
    private fun linkLanguageFocusGraph() {
        if (languageOptions.size < EXPECTED_LANGUAGE_OPTION_COUNT) return
        val system = languageOptions[0]
        system.nextFocusDownId = languageOptions[1].id

        val rows = listOf(1 to 2, 3 to 4, 5 to 6)
        rows.forEachIndexed { rowIndex, (firstIndex, secondIndex) ->
            val first = languageOptions[firstIndex]
            val second = languageOptions[secondIndex]
            val upperFirst = if (rowIndex == 0) system else languageOptions[firstIndex - 2]
            val upperSecond = if (rowIndex == 0) system else languageOptions[secondIndex - 2]
            first.nextFocusUpId = upperFirst.id
            second.nextFocusUpId = upperSecond.id
            if (rowIndex == rows.lastIndex) {
                first.nextFocusDownId = binding.btnBack.id
                second.nextFocusDownId = binding.btnNext.id
            } else {
                first.nextFocusDownId = languageOptions[firstIndex + 2].id
                second.nextFocusDownId = languageOptions[secondIndex + 2].id
            }
            linkPhysicalHorizontal(first, second)
        }

        binding.btnBack.nextFocusUpId = languageOptions[5].id
        binding.btnNext.nextFocusUpId = languageOptions[6].id
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        if (rtl) {
            binding.btnBack.nextFocusLeftId = binding.btnNext.id
            binding.btnNext.nextFocusRightId = binding.btnBack.id
        } else {
            binding.btnBack.nextFocusRightId = binding.btnNext.id
            binding.btnNext.nextFocusLeftId = binding.btnBack.id
        }
    }

    private fun linkPhysicalHorizontal(first: View, second: View) {
        if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            first.nextFocusLeftId = second.id
            second.nextFocusRightId = first.id
        } else {
            first.nextFocusRightId = second.id
            second.nextFocusLeftId = first.id
        }
    }

    private fun renderProgress() {
        listOf(binding.dot0, binding.dot1, binding.dot2).forEachIndexed { index, dot ->
            dot.isSelected = index == step
            dot.layoutParams = dot.layoutParams.apply {
                width = resources.getDimensionPixelSize(
                    if (index == step) {
                        R.dimen.wizard_dot_active
                    } else {
                        R.dimen.wizard_dot_inactive
                    },
                )
            }
        }
        listOf(binding.railStep0, binding.railStep1, binding.railStep2)
            .forEachIndexed { index, rail -> rail.isSelected = index == step }
    }

    private fun render(animate: Boolean = renderedOnce) {
        hideWizardError()
        binding.wizardStatus.visibility = View.GONE
        renderProgress()
        binding.stepCounter.text = getString(R.string.wizard_step_indicator, step + 1, totalSteps)
        binding.welcomeHighlights.visibility = View.GONE
        binding.languageScroll.visibility = View.GONE
        binding.connectSummary.visibility = View.GONE
        binding.btnBack.visibility = if (step == 0) View.GONE else View.VISIBLE

        when (step) {
            STEP_WELCOME -> {
                binding.stepIcon.setImageResource(R.drawable.ic_tv)
                binding.stepEyebrow.setText(R.string.wizard_eyebrow_welcome)
                binding.stepTitle.setText(R.string.wizard_welcome_title)
                binding.stepSubtitle.setText(R.string.wizard_welcome_subtitle)
                binding.welcomeHighlights.visibility = View.VISIBLE
                binding.btnNext.setText(R.string.wizard_cta_start)
                binding.btnNext.requestFocus()
            }
            STEP_LANGUAGE -> {
                binding.stepIcon.setImageResource(R.drawable.ic_globe)
                binding.stepEyebrow.setText(R.string.wizard_eyebrow_language)
                binding.stepTitle.setText(R.string.wizard_step_language)
                binding.stepSubtitle.setText(R.string.wizard_language_subtitle)
                binding.languageScroll.visibility = View.VISIBLE
                binding.btnNext.setText(R.string.wizard_cta_confirm_language)
                updateLanguageSelection()
                linkLanguageFocusGraph()
                (languageOptions.firstOrNull {
                    (it.tag as? String) == selectedLanguage
                } ?: languageOptions.firstOrNull())?.requestFocus()
            }
            STEP_CONNECT -> {
                binding.stepIcon.setImageResource(R.drawable.ic_lock)
                binding.stepEyebrow.setText(R.string.wizard_eyebrow_connect)
                binding.stepTitle.setText(R.string.wizard_connect_title)
                binding.stepSubtitle.setText(R.string.wizard_connect_helper)
                binding.connectSummary.visibility = View.VISIBLE
                binding.btnNext.setText(R.string.wizard_cta_open_login)
                binding.btnNext.requestFocus()
            }
        }

        if (animate) animateStepChange()
        if (renderedOnce) {
            binding.stepTitle.post {
                binding.stepTitle.announceForAccessibility(binding.stepTitle.text)
            }
        }
        renderedOnce = true
    }

    private fun animateEntrance() {
        val distance = resources.displayMetrics.density * 24f
        val direction = if (
            resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        ) -1f else 1f
        binding.wizardHero.apply {
            alpha = 0f
            translationX = -distance * direction
            animate().alpha(1f).translationX(0f).setDuration(280L).start()
        }
        binding.wizardCard.apply {
            alpha = 0f
            translationX = distance * direction
            animate().alpha(1f).translationX(0f).setStartDelay(60L).setDuration(300L).start()
        }
    }

    private fun animateStepChange() {
        val distance = resources.displayMetrics.density * 12f
        binding.stepContent.animate().cancel()
        binding.stepContent.apply {
            alpha = 0.62f
            translationX = distance
            animate().alpha(1f).translationX(0f).setDuration(190L).start()
        }
    }

    private fun goNext() {
        if (savingLanguage || finishingWizard) return
        when (step) {
            STEP_LANGUAGE -> persistLanguageAndAdvance()
            STEP_CONNECT -> finishWizard()
            else -> {
                step = (step + 1).coerceAtMost(totalSteps - 1)
                render()
            }
        }
    }

    private fun persistLanguageAndAdvance() {
        if (savingLanguage || finishingWizard) return
        savingLanguage = true
        setWorking(true, R.string.wizard_status_saving)
        lifecycleScope.launch {
            val changed = ServiceLocator.settings.languageTagBlocking() != selectedLanguage
            try {
                ServiceLocator.settings.setLanguageTag(selectedLanguage)
                step = STEP_CONNECT
                if (changed) {
                    recreate()
                } else {
                    savingLanguage = false
                    setWorking(false)
                    render()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                savingLanguage = false
                setWorking(false)
                showWizardError(R.string.wizard_error_save_language)
            }
        }
    }

    private fun finishWizard() {
        if (savingLanguage || finishingWizard) return
        finishingWizard = true
        setWorking(true, R.string.wizard_status_finishing)
        lifecycleScope.launch {
            try {
                ServiceLocator.settings.setWizardDone(true)
                if (!isFinishing) {
                    startActivity(Intent(this@WizardActivity, LoginActivity::class.java))
                    finish()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                finishingWizard = false
                setWorking(false)
                showWizardError(R.string.wizard_error_finish)
            }
        }
    }

    private fun setWorking(working: Boolean, statusText: Int? = null) {
        binding.wizardStatus.visibility = if (working) View.VISIBLE else View.GONE
        statusText?.let(binding.wizardStatusText::setText)
        binding.btnBack.isClickable = !working
        binding.btnNext.isClickable = !working
        binding.btnBack.alpha = if (working) 0.72f else 1f
        binding.btnNext.alpha = if (working) 0.78f else 1f
        languageOptions.forEach {
            it.isClickable = !working
            it.alpha = if (working) 0.72f else 1f
        }
    }

    private fun showWizardError(messageRes: Int) {
        binding.wizardInlineError.setText(messageRes)
        binding.wizardInlineError.visibility = View.VISIBLE
        binding.wizardInlineError.announceForAccessibility(binding.wizardInlineError.text)
        binding.btnNext.requestFocus()
    }

    private fun hideWizardError() {
        binding.wizardInlineError.visibility = View.GONE
    }

    private fun goBack() {
        if (savingLanguage || finishingWizard || step == STEP_WELCOME) return
        step--
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (savingLanguage || finishingWizard) return
        if (step > STEP_WELCOME) goBack() else super.onBackPressed()
    }

    companion object {
        private const val STATE_STEP = "wizard_step"
        private const val STATE_LANGUAGE = "wizard_language"
        private const val STEP_WELCOME = 0
        private const val STEP_LANGUAGE = 1
        private const val STEP_CONNECT = 2
        private const val EXPECTED_LANGUAGE_OPTION_COUNT = 7
    }
}
