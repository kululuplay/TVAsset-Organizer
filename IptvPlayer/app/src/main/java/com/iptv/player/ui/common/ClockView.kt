/*
 * ClockView.kt
 * A self-contained TextView that shows the current time as HH:mm and refreshes
 * once per minute (aligned to the minute boundary so it ticks on time). Its
 * visibility is gated by settings.showClock. Drop it into any layout corner.
 */
package com.iptv.player.ui.common

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import com.iptv.player.data.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val handler = Handler(Looper.getMainLooper())
    private val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var scope: CoroutineScope? = null
    private var clockEnabled = true

    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            // Schedule the next update on the next minute boundary.
            val now = System.currentTimeMillis()
            val delay = 60_000L - (now % 60_000L)
            handler.postDelayed(this, delay)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTime()
        handler.post(tick)
        observeSetting()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(tick)
        scope?.coroutineContext?.get(Job)?.cancel()
        scope = null
    }

    private fun observeSetting() {
        val s = CoroutineScope(Dispatchers.Main)
        scope = s
        s.launch {
            ServiceLocator.settings.showClock.collectLatest { enabled ->
                clockEnabled = enabled
                applyVisibility()
            }
        }
    }

    private fun applyVisibility() {
        visibility = if (clockEnabled) View.VISIBLE else View.GONE
    }

    private fun updateTime() {
        text = formatter.format(Date())
    }
}
