/*
 * MiniPlayerView.kt
 * A small, persistent player surface you can drop into a corner of any screen to
 * keep the current live channel visible while the user browses. It owns a
 * PlayerController bound to its internal videoContainer.
 *
 * Integration point:
 *   - Add <com.iptv.player.ui.common.MiniPlayerView .../> to a layout (give it a
 *     fixed size, e.g. 320x180dp, in a corner).
 *   - Call play(channel, mode) to start; the PlayerMode usually comes from
 *     ServiceLocator.settings.playerMode.first().
 *   - Call release() from the host's onDestroy.
 *   - setOnCloseListener {} and setOnExpandListener {} (tap the surface to expand
 *     to the full PlayerActivity).
 */
package com.iptv.player.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.databinding.ViewMiniPlayerBinding
import com.iptv.player.player.PlayerController

class MiniPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), PlayerController.Callback {

    private val binding: ViewMiniPlayerBinding

    private var controller: PlayerController? = null
    private var currentChannel: Channel? = null

    private var onClose: (() -> Unit)? = null
    private var onExpand: ((Channel) -> Unit)? = null

    init {
        orientation = VERTICAL
        binding = ViewMiniPlayerBinding.inflate(LayoutInflater.from(context), this)
        binding.miniClose.setOnClickListener { onClose?.invoke() }
        setOnClickListener { currentChannel?.let { ch -> onExpand?.invoke(ch) } }
        isFocusable = true
    }

    fun setOnCloseListener(listener: () -> Unit) { onClose = listener }
    fun setOnExpandListener(listener: (Channel) -> Unit) { onExpand = listener }

    /** Start (or switch to) playback of [channel] using the given [mode]. */
    fun play(channel: Channel, mode: PlayerMode) {
        currentChannel = channel
        binding.miniChannelName.text = channel.name
        if (controller == null) {
            controller = PlayerController(
                context = context,
                container = binding.miniVideoContainer,
                mode = mode,
                callback = this
            )
        }
        controller?.play(channel.streamUrl)
    }

    fun pause() = controller?.pause()
    fun resume() = controller?.resume()
    fun stop() = controller?.stop()

    fun release() {
        controller?.release()
        controller = null
        currentChannel = null
    }

    val channel: Channel? get() = currentChannel

    // ---- PlayerController.Callback (kept minimal for a corner surface) ----
    override fun onBuffering() { /* could show a tiny spinner overlay */ }
    override fun onPlaying(engineName: String) { /* surface is live */ }
    override fun onFatalError() { onClose?.invoke() }
    override fun onRetrying(attempt: Int) { /* silent retry in mini mode */ }
}
