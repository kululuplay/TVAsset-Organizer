package com.iptv.player.ui.player

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** The playback speeds a viewer can choose for a film or an episode. */
internal object VodPlaybackSpeedPolicy {

    const val DEFAULT = 1f

    /** Slowest first, in the order the selection dialog lists them. */
    val SPEEDS = listOf(0.5f, 0.75f, DEFAULT, 1.25f, 1.5f, 2f)

    /** "0.75x", "1x", and "1,5x" where the viewer's language writes a decimal comma. */
    fun label(speed: Float, locale: Locale): String =
        DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale))
            .format(speed.toDouble()) + "x"
}
