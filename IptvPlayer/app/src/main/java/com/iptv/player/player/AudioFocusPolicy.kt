package com.iptv.player.player

/** Pure audio-focus policy; integer values mirror Android AudioManager constants. */
internal object AudioFocusPolicy {
    enum class Action { NONE, PAUSE, PAUSE_AND_RESUME_ON_GAIN, RESUME }

    object Change {
        const val GAIN = 1
        const val LOSS = -1
        const val LOSS_TRANSIENT = -2
        const val LOSS_TRANSIENT_CAN_DUCK = -3
    }

    fun actionFor(change: Int, wasPlaying: Boolean): Action = when (change) {
        Change.GAIN -> Action.RESUME
        Change.LOSS_TRANSIENT,
        Change.LOSS_TRANSIENT_CAN_DUCK ->
            if (wasPlaying) Action.PAUSE_AND_RESUME_ON_GAIN else Action.NONE
        Change.LOSS -> if (wasPlaying) Action.PAUSE else Action.NONE
        else -> Action.NONE
    }
}
