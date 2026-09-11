package com.iptv.player.player

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.ViewStub
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Java-only boundary between Android lifecycle callbacks and libVLC's JNI owner. */
internal class VlcSurfaceCallbackGate {
    @Volatile private var retired = false
    fun isRetired(): Boolean = retired
    fun retire() { retired = true }
    fun dispatch(action: () -> Unit) { if (!retired) action() }
    fun destroyed(onUnexpectedLoss: () -> Unit) {
        if (retired) return
        retired = true
        onUnexpectedLoss()
    }
}

/**
 * AWindow's automatic surfaceDestroyed callback enters MediaPlayer JNI on main.
 * Android may destroy a surface even when our code retains the view (onStop,
 * Activity destruction, display changes). Never forward that callback. Retire
 * the output and let the app's bounded owner-worker cleanup close the decoder.
 * A recreated Android surface must belong to a fresh MediaPlayer/AWindow.
 *
 * Only AWindow registers callbacks on this private holder; PixelCopy and video
 * layout sizing still use the real SurfaceHolder/Surface without interception.
 */
internal class VlcSurfaceView(context: Context) : SurfaceView(context) {
    private var proxy: SurfaceHolder? = null
    private val gate = VlcSurfaceCallbackGate()
    private val ready = CountDownLatch(1)
    var onUnexpectedLoss: () -> Unit = {}

    fun retireCallbacks() = gate.retire()

    fun awaitReady(timeoutMs: Long): Boolean =
        ready.await(timeoutMs, TimeUnit.MILLISECONDS) && !gate.isRetired()

    override fun getHolder(): SurfaceHolder {
        proxy?.let { return it }
        val actual = super.getHolder()
        return object : SurfaceHolder by actual {
            private val callbacks = IdentityHashMap<SurfaceHolder.Callback, SurfaceHolder.Callback>()
            override fun addCallback(callback: SurfaceHolder.Callback) {
                if (callbacks.containsKey(callback)) return
                val wrapped = object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) =
                        gate.dispatch {
                            callback.surfaceCreated(this@VlcSurfaceView.holder)
                            ready.countDown()
                        }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                        gate.dispatch { callback.surfaceChanged(this@VlcSurfaceView.holder, format, width, height) }
                    override fun surfaceDestroyed(holder: SurfaceHolder) =
                        gate.destroyed { onUnexpectedLoss() }
                }
                callbacks[callback] = wrapped
                actual.addCallback(wrapped)
                // AWindow synchronously adopts an already-created Surface in
                // attachViews, before the app dispatches its worker start.
                if (actual.surface?.isValid == true) ready.countDown()
            }
            override fun removeCallback(callback: SurfaceHolder.Callback) {
                callbacks.remove(callback)?.let(actual::removeCallback)
            }
        }.also { proxy = it }
    }
}

internal object VlcSurfaceViews {
    /** Worker-only wait. Avoid mPlayRequested causing AWindow to call play on main. */
    fun awaitReady(layout: VLCVideoLayout?, subtitles: Boolean): Boolean {
        layout ?: return false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        val ids = if (subtitles) listOf(org.videolan.R.id.surface_video, org.videolan.R.id.surface_subtitles)
            else listOf(org.videolan.R.id.surface_video)
        return ids.all { id ->
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0L)
            layout.findViewById<VlcSurfaceView>(id)?.awaitReady(remaining) == true
        }
    }

    fun create(
        context: Context,
        subtitles: Boolean = false,
        onUnexpectedLoss: (VLCVideoLayout) -> Unit,
    ): VLCVideoLayout =
        VLCVideoLayout(context).apply layout@{
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            listOf(
                org.videolan.R.id.surface_stub to org.videolan.R.id.surface_video,
                org.videolan.R.id.subtitles_surface_stub to org.videolan.R.id.surface_subtitles,
            ).filter { (stubId, _) -> subtitles || stubId == org.videolan.R.id.surface_stub }
                .forEach { (stubId, surfaceId) ->
                val stub = findViewById<ViewStub>(stubId)
                val parent = stub.parent as ViewGroup
                val index = parent.indexOfChild(stub)
                val output = VlcSurfaceView(context).apply {
                    id = surfaceId
                    layoutParams = stub.layoutParams
                    this.onUnexpectedLoss = { onUnexpectedLoss(this@layout) }
                }
                parent.removeView(stub)
                parent.addView(output, index)
            }
        }

    fun retire(layout: VLCVideoLayout?) {
        layout ?: return
        layout.findViewById<VlcSurfaceView>(org.videolan.R.id.surface_video)?.retireCallbacks()
        layout.findViewById<VlcSurfaceView>(org.videolan.R.id.surface_subtitles)?.retireCallbacks()
    }
}
