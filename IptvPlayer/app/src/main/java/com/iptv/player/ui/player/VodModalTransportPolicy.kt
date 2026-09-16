package com.iptv.player.ui.player

/**
 * Maps a transport request (hardware media key or MediaSession callback) onto
 * the action the VOD screen may take while one of its modal cards is showing.
 * The next-episode prompt and the terminal error card each own the screen:
 * play means "next episode now" / Retry, pause and seeks are swallowed so the
 * countdown or the broken backend is never driven from behind the card.
 */
internal object VodModalTransportPolicy {
    enum class Transport { PLAY, PAUSE, TOGGLE, SEEK }

    enum class Action { PLAY_NEXT_EPISODE, RETRY, PASS_THROUGH, IGNORE }

    fun resolve(
        transport: Transport,
        nextEpisodePromptShowing: Boolean,
        errorCardShowing: Boolean,
    ): Action = when {
        nextEpisodePromptShowing -> when (transport) {
            Transport.PLAY, Transport.TOGGLE -> Action.PLAY_NEXT_EPISODE
            Transport.PAUSE, Transport.SEEK -> Action.IGNORE
        }
        errorCardShowing -> when (transport) {
            Transport.PLAY, Transport.TOGGLE -> Action.RETRY
            Transport.PAUSE, Transport.SEEK -> Action.IGNORE
        }
        else -> Action.PASS_THROUGH
    }
}
