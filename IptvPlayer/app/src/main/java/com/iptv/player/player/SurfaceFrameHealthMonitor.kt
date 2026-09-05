package com.iptv.player.player

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

/**
 * Samples a SurfaceView into a tiny in-memory bitmap and detects persistent solid
 * green or blank decoder output. No frame is stored or uploaded. PixelCopy
 * failures use a bounded capability probe because some protected/old surfaces
 * cannot be sampled safely; the engine may then fall back to native frame proof.
 */
internal class SurfaceFrameHealthMonitor(
    private val handler: Handler,
    private val onSolidGreen: () -> Unit,
    private val onPersistentBlank: () -> Unit = {},
    private val onHealthyFrame: () -> Unit = {},
    /** Called once when this stream's surface cannot be sampled after bounded retries. */
    private val onSamplingUnavailable: () -> Unit = {},
    // Hardware surfaces remain sampled at a low cadence because some vendor
    // decoders turn green after initially healthy output. Software decode cannot
    // hit that MediaCodec failure; stop after validation to avoid needless
    // PixelCopy/GPU work on weak sticks.
    private val continueAfterHealthy: Boolean = true,
    /** Re-evaluated when source/decoder metadata arrives, not just at construction. */
    private val allowPeriodicSampling: () -> Boolean = { true },
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    // Engines clear their health-handler messages on every zap. Native copy
    // completions must still recycle their bitmap and retire in-flight state;
    // generation checks below suppress all stale playback effects.
    private val copyResultHandler = Handler(handler.looper)
    private val lock = Any()
    private val recoveryGate = GreenFrameRecoveryGate()
    private val unavailableRetry = SurfaceSampleRetryPolicy()
    private var generation = 0
    private var started = false
    private var sampleInFlight = false
    private var classifiedFrame = false
    private var samplingUnavailable = false
    private var surfaceProvider: (() -> SurfaceView?)? = null
    private var progressProbe: ProgressProbe? = null
    // A timed-out native PixelCopy may still complete later. Do not start another
    // probe against the GPU until that request actually returns.
    private var progressSampleInFlight = false

    private class ProgressProbe(
        val generation: Int,
        val onResult: (Boolean) -> Unit,
        val policy: SurfaceProgressProbePolicy = SurfaceProgressProbePolicy(),
    )

    fun hasClassifiedFrame(): Boolean = synchronized(lock) { classifiedFrame }
    fun hasHealthyFrame(): Boolean = synchronized(lock) { recoveryGate.hasHealthyFrame }
    fun isSamplingUnavailable(): Boolean = synchronized(lock) { samplingUnavailable }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.N)
    private fun supportsPixelCopy(): Boolean = sdkInt >= Build.VERSION_CODES.N

    fun reset() {
        synchronized(lock) {
            generation++
            started = false
            sampleInFlight = false
            classifiedFrame = false
            samplingUnavailable = false
            recoveryGate.reset()
            unavailableRetry.reset()
            surfaceProvider = null
            progressProbe?.policy?.cancel()
            progressProbe = null
        }
    }

    /**
     * Samples only on demand when playback appears stuck in buffering without
     * native picture counters. Each probe starts with no historical image and
     * requires two distinct, healthy captures. Failure/unavailability never
     * proves resumed video. Reset or a new surface invalidates every callback.
     */
    fun requestProgressProbe(onResult: (Boolean) -> Unit): Boolean {
        if (!supportsPixelCopy()) return false
        val probe = synchronized(lock) {
            if (surfaceProvider == null || progressProbe != null || progressSampleInFlight) return false
            ProgressProbe(generation, onResult).also { progressProbe = it }
        }
        handler.postDelayed({ finishProgressProbe(probe, false) }, PROBE_DEADLINE_MS)
        handler.post { sampleProgress(probe) }
        return true
    }

    fun cancelProgressProbe() {
        synchronized(lock) {
            progressProbe?.policy?.cancel()
            progressProbe = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun sampleProgress(probe: ProgressProbe) {
        val provider = synchronized(lock) {
            if (progressProbe !== probe || generation != probe.generation) return
            if (sampleInFlight) {
                handler.postDelayed({ sampleProgress(probe) }, PROBE_SAMPLE_INTERVAL_MS)
                return
            }
            surfaceProvider
        }
        val surface = runCatching { provider?.invoke() }.getOrNull()
        if (surface == null || !surface.holder.surface.isValid || surface.width <= 0 || surface.height <= 0) {
            finishProgressProbe(probe, false)
            return
        }
        val bitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
        synchronized(lock) { progressSampleInFlight = true }
        runCatching {
            PixelCopy.request(surface, bitmap, { result ->
                val decision = try {
                    synchronized(lock) {
                        if (progressProbe !== probe || generation != probe.generation) {
                            SurfaceProgressProbePolicy.Decision.FINISHED
                        } else {
                            val pixels = if (result == PixelCopy.SUCCESS) {
                                IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT).also {
                                    bitmap.getPixels(it, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
                                }
                            } else {
                                null
                            }
                            probe.policy.onSample(pixels)
                        }
                    }
                } finally {
                    bitmap.recycle()
                    synchronized(lock) { progressSampleInFlight = false }
                }
                when (decision) {
                    SurfaceProgressProbePolicy.Decision.PROGRESS -> finishProgressProbe(probe, true)
                    SurfaceProgressProbePolicy.Decision.FINISHED -> finishProgressProbe(probe, false)
                    SurfaceProgressProbePolicy.Decision.WAIT ->
                        handler.postDelayed({ sampleProgress(probe) }, PROBE_SAMPLE_INTERVAL_MS)
                }
            }, copyResultHandler)
        }.onFailure {
            bitmap.recycle()
            synchronized(lock) { progressSampleInFlight = false }
            finishProgressProbe(probe, false)
        }
    }

    private fun finishProgressProbe(probe: ProgressProbe, progressed: Boolean) {
        val callback = synchronized(lock) {
            if (progressProbe !== probe) return
            progressProbe = null
            probe.policy.cancel()
            if (generation != probe.generation) return
            probe.onResult
        }
        callback(progressed)
    }

    fun start(provider: () -> SurfaceView?) {
        if (!supportsPixelCopy()) return
        val sampleGeneration = synchronized(lock) {
            if (started) return
            started = true
            sampleInFlight = false
            classifiedFrame = false
            samplingUnavailable = false
            surfaceProvider = provider
            recoveryGate.reset()
            unavailableRetry.reset()
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
        if (!supportsPixelCopy()) return
        val sampleGeneration = synchronized(lock) {
            if (surfaceProvider == null) return
            generation++
            progressProbe?.policy?.cancel()
            progressProbe = null
            started = true
            sampleInFlight = false
            classifiedFrame = false
            samplingUnavailable = false
            recoveryGate.onOutputTransition()
            unavailableRetry.reset()
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
        if (synchronized(lock) {
                sampleGeneration == generation && started &&
                    (progressProbe != null || progressSampleInFlight)
            }
        ) {
            scheduleSample(sampleGeneration, PROBE_SAMPLE_INTERVAL_MS)
            return
        }
        val provider = synchronized(lock) {
            if (
                sampleGeneration != generation ||
                !started ||
                sampleInFlight
            ) {
                null
            } else {
                // Metadata can arrive after the first healthy confirmation. Do
                // not issue one more costly copy merely to discover this change.
                // Keep provider/health evidence for fresh output revalidation.
                if (canStopPeriodicSampling()) {
                    started = false
                    return@synchronized null
                }
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
                                unavailableRetry.onSuccess()
                                val solidGreen = FrameColorClassifier.isSolidGreen(pixels)
                                val visuallyBlank =
                                    !solidGreen &&
                                        FrameColorClassifier.isVisuallyBlank(pixels)
                                decision = recoveryGate.onSample(
                                    solidGreen = solidGreen,
                                    nowMs = nowMs(),
                                    visuallyBlank = visuallyBlank,
                                )
                                if (
                                    decision ==
                                    GreenFrameRecoveryGate.Decision.SOLID_GREEN_FAILURE ||
                                    decision ==
                                    GreenFrameRecoveryGate.Decision.SOLID_BLANK_FAILURE ||
                                    canStopPeriodicSampling()
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
                        nextDelayMs = onSampleUnavailable(sampleGeneration)
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
                copyResultHandler,
            )
        }.onFailure {
            bitmap.recycle()
            retryAfterUnavailableSurface(sampleGeneration)
        }
    }

    /** Caller holds lock. Existing green/blank suspicion must resolve first. */
    private fun canStopPeriodicSampling(): Boolean =
        recoveryGate.hasHealthyFrame && !recoveryGate.hasPendingInvalidFrame &&
            (!continueAfterHealthy || !allowPeriodicSampling())

    @RequiresApi(Build.VERSION_CODES.N)
    private fun retryAfterUnavailableSurface(sampleGeneration: Int) {
        onSampleUnavailable(sampleGeneration)?.let {
            scheduleSample(sampleGeneration, it)
        }
    }

    /**
     * Applies a bounded exponential retry budget. Once exhausted, PixelCopy is
     * disabled for this stream and the engine's native first-frame fallback owns
     * readiness. reset()/a new stream creates a fresh capability probe.
     */
    private fun onSampleUnavailable(sampleGeneration: Int): Long? {
        var exhausted = false
        val delay = synchronized(lock) {
            if (sampleGeneration != generation || !started) return@synchronized null
            sampleInFlight = false
            unavailableRetry.onUnavailable().also { nextDelay ->
                if (nextDelay == null) {
                    started = false
                    samplingUnavailable = true
                    generation++
                    exhausted = true
                }
            }
        }
        if (exhausted) onSamplingUnavailable()
        return delay
    }

    private companion object {
        private const val SAMPLE_WIDTH = 32
        private const val SAMPLE_HEIGHT = 18
        private const val INITIAL_SAMPLE_DELAY_MS = 120L
        private const val STARTUP_SAMPLE_INTERVAL_MS = 220L
        private const val PROBE_SAMPLE_INTERVAL_MS = 200L
        private const val PROBE_DEADLINE_MS = 1_500L
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
