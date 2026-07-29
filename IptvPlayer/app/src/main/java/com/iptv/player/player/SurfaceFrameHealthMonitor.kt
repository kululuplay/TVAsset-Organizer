package com.iptv.player.player

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi

/**
 * Samples a SurfaceView into a tiny in-memory bitmap and detects persistent solid
 * green decoder output. No frame is stored or uploaded. PixelCopy failures are
 * ignored because some protected/old surfaces cannot be sampled safely.
 */
internal class SurfaceFrameHealthMonitor(
    private val handler: Handler,
    private val onSolidGreen: () -> Unit,
) {
    private val lock = Any()
    private var generation = 0
    private var started = false
    private var consecutiveGreenSamples = 0
    private var surfaceProvider: (() -> SurfaceView?)? = null

    fun reset() {
        synchronized(lock) {
            generation++
            started = false
            consecutiveGreenSamples = 0
            surfaceProvider = null
        }
    }

    fun start(provider: () -> SurfaceView?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val sampleGeneration = synchronized(lock) {
            if (started) return
            started = true
            surfaceProvider = provider
            generation
        }
        for (delayMs in SAMPLE_DELAYS_MS) {
            handler.postDelayed({ sample(sampleGeneration) }, delayMs)
        }
        schedulePeriodicSample(sampleGeneration, PERIODIC_START_DELAY_MS)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun schedulePeriodicSample(sampleGeneration: Int, delayMs: Long) {
        handler.postDelayed({
            val active = synchronized(lock) {
                sampleGeneration == generation && started
            }
            if (!active) return@postDelayed
            sample(sampleGeneration)
            schedulePeriodicSample(sampleGeneration, PERIODIC_SAMPLE_INTERVAL_MS)
        }, delayMs)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun sample(sampleGeneration: Int) {
        val provider = synchronized(lock) {
            if (sampleGeneration != generation || !started) null else surfaceProvider
        } ?: return
        val surface = provider.invoke() ?: return
        if (!surface.holder.surface.isValid || surface.width <= 0 || surface.height <= 0) return

        val bitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(
                surface,
                bitmap,
                { result ->
                    var notifySolidGreen = false
                    if (result == PixelCopy.SUCCESS) {
                        val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
                        bitmap.getPixels(
                            pixels,
                            0,
                            SAMPLE_WIDTH,
                            0,
                            0,
                            SAMPLE_WIDTH,
                            SAMPLE_HEIGHT,
                        )
                        synchronized(lock) {
                            if (sampleGeneration == generation && started) {
                                if (FrameColorClassifier.isSolidGreen(pixels)) {
                                    consecutiveGreenSamples++
                                    if (
                                        consecutiveGreenSamples >=
                                        REQUIRED_GREEN_SAMPLES
                                    ) {
                                        started = false
                                        generation++
                                        notifySolidGreen = true
                                    }
                                } else {
                                    consecutiveGreenSamples = 0
                                }
                            }
                        }
                    }
                    bitmap.recycle()
                    if (notifySolidGreen) onSolidGreen()
                },
                handler,
            )
        }.onFailure { bitmap.recycle() }
    }

    private companion object {
        private const val SAMPLE_WIDTH = 32
        private const val SAMPLE_HEIGHT = 18
        private const val REQUIRED_GREEN_SAMPLES = 2
        private val SAMPLE_DELAYS_MS = longArrayOf(1200L, 3000L, 5500L, 9000L)
        // A decoder/compositor may fail minutes after a healthy start. Continue
        // with a tiny, low-frequency sample so that late solid-green output also
        // recovers without keeping full-size frames in memory.
        private const val PERIODIC_START_DELAY_MS = 24_000L
        private const val PERIODIC_SAMPLE_INTERVAL_MS = 15_000L
    }
}

/** Find the actual video SurfaceView nested inside PlayerView/VLCVideoLayout. */
internal fun findVideoSurface(view: View?): SurfaceView? {
    if (view is SurfaceView) return view
    if (view !is ViewGroup) return null
    for (index in 0 until view.childCount) {
        findVideoSurface(view.getChildAt(index))?.let { return it }
    }
    return null
}
