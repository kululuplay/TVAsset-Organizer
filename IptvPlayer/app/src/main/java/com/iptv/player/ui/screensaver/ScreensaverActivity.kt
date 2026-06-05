/*
 * ScreensaverActivity.kt
 * A simple burn-in-prevention screensaver. A logo + clock block slowly drifts to
 * a new random position every few seconds. Any key press or touch dismisses it.
 * Launch it from an Activity via IdleWatcher after settings.screensaverMinutes.
 */
package com.iptv.player.ui.screensaver

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import com.iptv.player.databinding.ActivityScreensaverBinding
import com.iptv.player.ui.common.BaseActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class ScreensaverActivity : BaseActivity() {

    private lateinit var binding: ActivityScreensaverBinding
    private val handler = Handler(Looper.getMainLooper())
    private val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    companion object {
        private const val MOVE_INTERVAL_MS = 8000L
        private const val CLOCK_INTERVAL_MS = 30_000L
    }

    private val moveRunnable = object : Runnable {
        override fun run() {
            moveBlock()
            handler.postDelayed(this, MOVE_INTERVAL_MS)
        }
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            binding.screensaverClock.text = formatter.format(Date())
            handler.postDelayed(this, CLOCK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreensaverBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.screensaverClock.text = formatter.format(Date())
    }

    override fun onResume() {
        super.onResume()
        handler.post(moveRunnable)
        handler.post(clockRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    private fun moveBlock() {
        val root = binding.screensaverRoot
        val block = binding.floatingBlock
        val maxX = (root.width - block.width).coerceAtLeast(0)
        val maxY = (root.height - block.height).coerceAtLeast(0)
        if (maxX == 0 || maxY == 0) return
        val targetX = Random.nextInt(maxX).toFloat()
        val targetY = Random.nextInt(maxY).toFloat()
        block.animate()
            .x(targetX)
            .y(targetY)
            .setDuration(1500L)
            .start()
    }

    // Any interaction dismisses the screensaver.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        finish()
        return true
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        finish()
        return true
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        finish()
    }
}
