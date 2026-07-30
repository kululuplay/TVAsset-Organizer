package com.iptv.player.player

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi

/**
 * Samples a SurfaceView into a tiny in-memory bitmap and detects persistent solid
 * green or blank decoder output. No frame is stored or uploaded. PixelCopy
 * failures are ignored because some protected/old surfaces cannot be sampled
 * safely.
 */
internal class SurfaceFrameHealthMonitor(
    private val handler: Handler,
    private val onSolidGreen: () -> Unit,
    private val onPersistentBlank: () -> Unit = {},
    private val onHealthyFrame: () -> Unit = {},
    // Hardware surfaces remain sampled at a low cadence because some vendor
    // decoders turn green after initially healthy output. Software decode cannot
    // hit that MediaCodec failure; stop after validation to avoid needless
    // PixelCopy/GPU work on weak sticks.
    private val continueAfterHealthy: Boolean = true,
) {
    private val lock = Any()
    private val recoveryGate = GreenFrameRecoveryGate()
    private var generation = 0
    private var started = false
    private var sampleInFlight = false
    private var classifiedFrame = false
    private var surfaceProvider: (() -> SurfaceView?)? = null

    fun hasClassifiedFrame(): Boolean = synchronized(lock) { classifiedFrame }

    fun reset() {
        synchronized(lock) {
            generation++
            started = false
            sampleInFlight = false
            classifiedFrame = false
            recoveryGate.reset()
            surfaceProvider = null
        }
    }

    fun start(provider: () -> SurfaceView?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val sampleGeneration = synchronized(lock) {
            if (started) return
            started = true
            sampleInFlight = false
            classifiedFrame = false
            surfaceProvider = provider
            recoveryGate.reset()
            generation
        }
        scheduleSample(sampleGeneration, INITIAL_SAMPLE_DELAY_MS)
    }

    /**
     * Re-baseline an already-running monitor after PlayerView changes output
     * size/surface. An in-flight PixelCopy from the retired output is invalidated,
     * while the provider and monitoring lifecycle stay attached to this stream.
     */
    fun onOutputTransition() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val sampleGeneration = synchronized(lock) {
            if (!started || surfaceProvider == null) return
            generation++
            sampleInFlight = false
            classifiedFrame = false
            recoveryGate.onOutputTransition()
            generation
        }
        scheduleSample(sampleGeneration, INITIAL_SAMPLE_DELAY_MS)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun scheduleSample(sampleGeneration: Int, delayMs: Long) {
        handler.postDelayed({ sample(sampleGeneration) }, delayMs)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun sample(sampleGeneration: Int) {
        val provider = synchronized(lock) {
            if (
                sampleGeneration != generation ||
                !started ||
                sampleInFlight
            ) {
                null
            } else {
                sampleInFlight = true
                surfaceProvider
            }
        } ?: return
        val surface = runCatching { provider.invoke() }.getOrNull()
        if (surface == null) {
            retryAfterUnavailableSurface(sampleGeneration)
            return
        }
        if (!surface.holder.surface.isValid || surface.width <= 0 || surface.height <= 0) {
            retryAfterUnavailableSurface(sampleGeneration)
            return
        }

        val bitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(
                surface,
                bitmap,
                { result ->
                    var decision = GreenFrameRecoveryGate.Decision.WAIT
                    var nextDelayMs: Long? = null
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
                                sampleInFlight = false
                                classifiedFrame = true
                                val solidGreen = FrameColorClassifier.isSolidGreen(pixels)
                                val visuallyBlank =
                                    !solidGreen &&
                                        FrameColorClassifier.isVisuallyBlank(pixels)
                                decision = recoveryGate.onSample(
                                    solidGreen = solidGreen,
                                    nowMs = SystemClock.elapsedRealtime(),
                                    visuallyBlank = visuallyBlank,
                                )
                                if (
                                    decision ==
                                    GreenFrameRecoveryGate.Decision.SOLID_GREEN_FAILURE ||
                                    decision ==
                                    GreenFrameRecoveryGate.Decision.SOLID_BLANK_FAILURE ||
                                    (
                                        decision ==
                                            GreenFrameRecoveryGate.Decision.FIRST_HEALTHY_FRAME &&
                                            !continueAfterHealthy
                                        )
                                ) {
                                    started = false
                                    generation++
                                } else {
                                    nextDelayMs = when {
                                        !recoveryGate.hasHealthyFrame ->
                                            STARTUP_SAMPLE_INTERVAL_MS
                                        solidGreen || visuallyBlank ->
                                            SUSPECT_SAMPLE_INTERVAL_MS
                                        else -> STEADY_SAMPLE_INTERVAL_MS
                                    }
                                }
                            }
                        }
                    } else {
                        synchronized(lock) {
                            if (sampleGeneration == generation && started) {
                                sampleInFlight = false
                                nextDelayMs = SAMPLE_RETRY_DELAY_MS
                            }
                        }
                    }
                    bitmap.recycle()
                    when (decision) {
                        GreenFrameRecoveryGate.Decision.FIRST_HEALTHY_FRAME ->
                            onHealthyFrame()
                        GreenFrameRecoveryGate.Decision.SOLID_GREEN_FAILURE ->
                            onSolidGreen()
                        GreenFrameRecoveryGate.Decision.SOLID_BLANK_FAILURE ->
                            onPersistentBlank()
                        GreenFrameRecoveryGate.Decision.WAIT -> Unit
                    }
                    nextDelayMs?.let { scheduleSample(sampleGeneration, it) }
                },
                handler,
            )
        }.onFailure {
            bitmap.recycle()
            retryAfterUnavailableSurface(sampleGeneration)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun retryAfterUnavailableSurface(sampleGeneration: Int) {
        val retry = synchronized(lock) {
            if (sampleGeneration != generation || !started) {
                false
            } else {
                sampleInFlight = false
                true
            }
        }
        if (retry) scheduleSample(sampleGeneration, SAMPLE_RETRY_DELAY_MS)
    }

    private companion object {
        private const val SAMPLE_WIDTH = 32
        private const val SAMPLE_HEIGHT = 18
        private const val INITIAL_SAMPLE_DELAY_MS = 120L
        private const val STARTUP_SAMPLE_INTERVAL_MS = 220L
        private const val SAMPLE_RETRY_DELAY_MS = 350L
        // PixelCopy is serialized: the next capture is scheduled only after the
        // previous callback completes. This prevents a delayed old green capture
        // from racing a newer healthy one and restarting an already-correct image.
        // Healthy Amlogic surfaces log a costly full-resolution gralloc copy for
        // every sample. Sample healthy output sparingly, then temporarily increase
        // cadence as soon as a suspicious green/blank frame is observed.
        private const val STEADY_SAMPLE_INTERVAL_MS = 5_000L
        private const val SUSPECT_SAMPLE_INTERVAL_MS = 500L
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
