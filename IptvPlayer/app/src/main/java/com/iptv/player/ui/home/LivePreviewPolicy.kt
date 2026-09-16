package com.iptv.player.ui.home

/**
 * Decides whether the Home screen runs the inline live preview.
 *
 * Precedence: an explicit user choice always wins; the remote device override
 * and the compatibility profile only move the default. Weak sticks default to
 * no preview so the decoder is not warmed up twice (preview + fullscreen) and
 * the browse UI keeps its CPU for D-pad scrolling.
 */
internal object LivePreviewPolicy {

    fun enabled(userChoice: Boolean?, remote: Boolean?, compat: Boolean): Boolean =
        userChoice ?: defaultEnabled(remote, compat)

    /** What the toggle shows before the user ever touched it. */
    fun defaultEnabled(remote: Boolean?, compat: Boolean): Boolean =
        remote ?: !compat
}
