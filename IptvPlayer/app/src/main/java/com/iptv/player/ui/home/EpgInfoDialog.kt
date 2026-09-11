package com.iptv.player.ui.home

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import coil.load
import com.iptv.player.R
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.Program
import com.iptv.player.databinding.DialogEpgInfoBinding
import com.iptv.player.ui.common.BaseActivity
import java.text.DateFormat
import java.util.Date

object EpgInfoDialog {
    fun show(activity: BaseActivity, channel: Channel?, program: Program) {
        val previousFocus = activity.currentFocus
        val binding = DialogEpgInfoBinding.inflate(activity.layoutInflater)
        val now = System.currentTimeMillis()
        val live = program.isLiveAt(now)
        binding.channelName.text = channel?.name.orEmpty()
        binding.channelLogo.load(channel?.logoUrl) {
            placeholder(R.drawable.ic_tv)
            error(R.drawable.ic_tv)
            crossfade(false)
        }
        binding.programTitle.text = program.title
        ViewCompat.setAccessibilityHeading(binding.programTitle, true)
        binding.programDescription.text = program.description.orEmpty().ifBlank {
            activity.getString(R.string.epg_description_unavailable)
        }
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT)
        val start = Date(program.startMs)
        val stop = Date(program.stopMs)
        val endDate = if (date.format(start) == date.format(stop)) "" else date.format(stop) + " · "
        binding.programSchedule.text = date.format(start) + " · " + time.format(start) +
            " – " + endDate + time.format(stop)
        binding.programStatus.setText(when {
            live -> R.string.epg_popup_now
            now < program.startMs -> R.string.epg_popup_upcoming
            else -> R.string.epg_popup_finished
        })
        binding.programProgress.visibility = if (live) View.VISIBLE else View.GONE
        binding.programProgress.progress = (program.progressAt(now) * 100).toInt()

        val dialog = AlertDialog.Builder(activity).setView(binding.root).create()
        binding.closeButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnKeyListener { _, code, event ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val direction = if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                        if (binding.descriptionScroll.canScrollVertically(direction)) {
                            binding.descriptionScroll.requestFocus()
                            binding.descriptionScroll.scrollBy(0,
                                (64 * activity.resources.displayMetrics.density).toInt() * direction)
                        } else binding.closeButton.requestFocus()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) dialog.dismiss()
                    true
                }
                else -> false
            }
        }
        dialog.setOnDismissListener {
            previousFocus?.takeIf { it.isAttachedToWindow }?.requestFocus()
        }
        dialog.show()
        activity.trackIdleInteractions(dialog)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(.72f)
            val metrics = activity.resources.displayMetrics
            setLayout(minOf((680 * metrics.density).toInt(), (metrics.widthPixels * .9f).toInt()),
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        binding.closeButton.requestFocus()
    }
}
