package com.iptv.player.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities

/**
 * Restricts which sample MIME types a delegate audio renderer may claim during
 * track selection. Used to place a software (FFmpeg) renderer in front of the
 * platform MediaCodec renderer for a small set of formats without letting it
 * steal every other format the platform decodes well.
 *
 * `DefaultTrackSelector` consults [Renderer.getCapabilities], and the stock
 * [ForwardingRenderer] hands that straight to the delegate, so this class
 * answers as its own [RendererCapabilities] and forwards everything else.
 */
@UnstableApi
internal class CodecGatedAudioRenderer(
    private val delegate: Renderer,
    private val allowedMimes: Set<String>,
) : ForwardingRenderer(delegate), RendererCapabilities {

    private val delegateCapabilities: RendererCapabilities get() = delegate.capabilities

    override fun getCapabilities(): RendererCapabilities = this

    override fun getName(): String = "Gated(${delegate.name})"

    override fun getTrackType(): Int = delegate.trackType

    override fun supportsFormat(format: Format): Int {
        val mime = format.sampleMimeType ?: return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        if (mime !in allowedMimes) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        return delegateCapabilities.supportsFormat(format)
    }

    override fun supportsMixedMimeTypeAdaptation(): Int =
        delegateCapabilities.supportsMixedMimeTypeAdaptation()

    override fun setListener(listener: RendererCapabilities.Listener) {
        delegateCapabilities.setListener(listener)
    }

    override fun clearListener() {
        delegateCapabilities.clearListener()
    }
}
